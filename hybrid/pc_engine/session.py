"""Durable session spool with a single, bounded reconstruction worker."""
import hashlib
import logging
import os
import queue
import threading
import time

from .frame import decode, session_id
from .fusion import Fusion

MAX_FRAMES = 5000
MAX_SESSION_BYTES = 2 * 1024**3
LOG = logging.getLogger(__name__)


class Rejected(Exception):
    def __init__(self, code, detail):
        super().__init__(detail)
        self.code = code


class ScanManager:
    def __init__(self, root, settings):
        self.root = root
        self.settings = settings
        settings.validate()
        root.mkdir(parents=True, exist_ok=True)
        self.lock = threading.RLock()
        self.jobs = queue.Queue(maxsize=8)
        self.sid = None
        self.phase = "idle"
        self.error = ""
        self.pending = self.accepted = self.bytes = self.integrated = 0
        self.geometry = {"points": [], "path": [], "mesh": {"vertices": [], "faces": []}}
        self.point_revision = self.mesh_revision = 0
        self.last_phone = self.last_mesh = self.last_points = 0.
        self.integration_ms = self.mesh_ms = 0.
        self.fusion = None
        self.worker = threading.Thread(target=self.run, name="TSDF worker", daemon=True)
        self.worker.start()

    def close(self):
        self.jobs.put(("shutdown", None))
        self.worker.join()
        self.fusion = None

    def status(self):
        with self.lock:
            return dict(type="scan.status", version=1, session=self.sid, phase=self.phase,
                        accepted=self.accepted, integrated=self.integrated, pending=self.pending,
                        phone_online=time.monotonic()-self.last_phone < 6, error=self.error,
                        point_revision=self.point_revision, mesh_revision=self.mesh_revision,
                        integration_ms=round(self.integration_ms, 1), mesh_ms=round(self.mesh_ms, 1))

    def command(self, kind, sid=None, fmt=None, role=None):
        if kind == "connection.heartbeat":
            with self.lock:
                if role == "android":
                    self.last_phone = time.monotonic()
                return self.status()
        with self.lock:
            if kind == "scan.start":
                if self.sid == sid:
                    return self.status()
                if self.phase not in {"idle", "stopped", "error"} or self.pending:
                    raise Rejected(409, "Finish the active session first")
                folder = self.root / session_id(sid)
                folder.mkdir(exist_ok=True)
                files = list(folder.glob("*.h3s"))
                self.sid, self.phase, self.error = sid, "restoring", ""
                self.integrated = 0
                self.integration_ms = self.mesh_ms = 0.
                self.accepted = len(files)
                self.bytes = sum(p.stat().st_size for p in files)
                self.geometry = {"points": [], "path": [], "mesh": {"vertices": [], "faces": []}}
                self.point_revision += 1
                self.mesh_revision += 1
                self.pending = 1
                self.jobs.put_nowait(("restore", folder))
            else:
                if sid != self.sid or sid is None:
                    raise Rejected(409, "Session is not active")
                if kind == "scan.pause" and self.phase in {"scanning", "paused"}:
                    self.phase = "paused"
                elif kind == "scan.resume" and self.phase in {"paused", "stopped", "scanning"} and not (
                        self.phase == "stopped" and self.pending):
                    self.phase = "scanning"
                elif kind == "scan.stop" and self.phase in {"scanning", "paused", "stopping", "stopped"}:
                    if role == "blender" and self.phase != "stopped" and time.monotonic()-self.last_phone < 6:
                        self.phase = "stopping"
                    elif self.phase != "stopped":
                        self.submit("finalize", None)
                        self.phase = "stopped"
                elif kind == "scan.export" and self.phase == "stopped" and not self.pending and fmt in {"ply", "glb"}:
                    output = self.root / sid / f"scan.{fmt}"
                    output.unlink(missing_ok=True)
                    self.error = ""
                    self.submit("export", output)
                else:
                    raise Rejected(409, "Command unavailable in current phase")
            return self.status()

    def submit(self, kind, arg):
        try:
            self.jobs.put_nowait((kind, arg))
        except queue.Full as exc:
            raise Rejected(429, "Reconstruction queue is full") from exc
        self.pending += 1

    def ingest(self, sid, packet, digest):
        actual = hashlib.sha256(packet).hexdigest()
        if actual != digest:
            raise Rejected(400, "SHA-256 mismatch")
        frame = decode(packet)
        if frame.meta["session"] != sid:
            raise Rejected(400, "Packet session mismatch")
        path = self.root / sid / f'{frame.meta["id"]:09d}.h3s'
        with self.lock:
            if sid != self.sid:
                raise Rejected(409, "Session is not active")
            if path.exists():
                if hashlib.sha256(path.read_bytes()).hexdigest() != actual:
                    raise Rejected(409, "Frame ID already identifies different bytes")
                return {"ack": frame.meta["id"], "duplicate": True}
            if self.phase not in {"scanning", "paused", "stopping"}:
                raise Rejected(409, "Session is not accepting frames")
            if self.accepted >= MAX_FRAMES or self.bytes + len(packet) > MAX_SESSION_BYTES:
                raise Rejected(507, "Session storage limit reached")
            if self.jobs.full():
                raise Rejected(429, "Reconstruction queue is full")
            tmp = path.with_suffix(".tmp")
            with tmp.open("wb") as handle:
                handle.write(packet)
                handle.flush()
                os.fsync(handle.fileno())
            tmp.replace(path)
            self.accepted += 1
            self.bytes += len(packet)
            self.submit("frame", frame)
            return {"ack": frame.meta["id"], "duplicate": False}

    def apply(self, frame):
        started = time.monotonic()
        self.fusion.integrate(frame)
        with self.lock:
            self.integrated = self.fusion.count
            self.integration_ms = 1000 * (time.monotonic() - started)

    def update(self, force=False):
        now = time.monotonic()
        points = path = mesh = None
        if force or now - self.last_points >= .12:
            points, path = self.fusion.points(), self.fusion.path.copy()
            self.last_points = now
        if force or now - self.last_mesh >= 1 / self.settings.mesh_hz:
            started = time.monotonic()
            mesh = self.fusion.preview()
            self.last_mesh = time.monotonic()
            self.mesh_ms = 1000 * (self.last_mesh - started)
        with self.lock:
            if points is not None:
                self.geometry.update(points=points, path=path)
                self.point_revision += 1
            if mesh is not None:
                self.geometry["mesh"] = mesh
                self.mesh_revision += 1

    def run(self):
        while True:
            kind, arg = self.jobs.get()
            if kind == "shutdown":
                self.jobs.task_done()
                return
            try:
                if kind == "restore":
                    self.fusion = Fusion(self.settings)
                    for path in sorted(arg.glob("*.h3s")):
                        self.apply(decode(path.read_bytes()))
                    self.update(force=True)
                elif kind == "export":
                    temporary = arg.with_name(f".writing{arg.suffix}")
                    try:
                        self.fusion.export(temporary, arg.suffix[1:])
                        temporary.replace(arg)
                    finally:
                        temporary.unlink(missing_ok=True)
                elif kind == "finalize":
                    self.update(force=True)
                elif self.phase != "error":
                    self.apply(arg)
                    self.update()
            except Exception as exc:
                LOG.exception("Scan worker error (%s)", kind)
                with self.lock:
                    if kind != "export":
                        self.phase = "error"
                    self.error = str(exc)
            finally:
                with self.lock:
                    self.pending -= 1
                    if kind == "restore" and self.phase == "restoring":
                        self.phase = "scanning"
                self.jobs.task_done()
