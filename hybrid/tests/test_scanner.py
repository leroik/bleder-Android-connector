import hashlib
import io
import json
import os
import struct
import tempfile
import time
import unittest
import uuid
from pathlib import Path

os.environ.setdefault("OMP_NUM_THREADS", "4")
import numpy as np
from fastapi.testclient import TestClient
from PIL import Image

from pc_engine.frame import camera_transform, decode, encode
from pc_engine.fusion import Fusion, Settings
from pc_engine.server import make_app
from pc_engine.session import ScanManager


def packet(sid, i=0, confidence=255, tracking="TRACKING", offset=.0):
    width, height = 64, 48
    pose = np.eye(4)
    pose[0, 3] = offset
    ts = 1_000_000_000 + i * 200_000_000
    meta = dict(type="frame.meta", version=1, session=sid, id=i,
                timestamp_ns=ts, rgb_timestamp_ns=ts, depth_timestamp_ns=ts,
                confidence_timestamp_ns=ts, tracking=tracking,
                coordinates="arcore_c2w_row_major_m", camera_to_world=pose.ravel().tolist(),
                width=width, height=height, rgb_width=width*2, rgb_height=height*2,
                intrinsics=[58, 58, 32, 24], rgb_intrinsics=[116, 116, 64, 48],
                depth_to_rgb=[2, 0, 0, 0, 2, 0])
    rgb = np.zeros((height*2, width*2, 3), dtype=np.uint8)
    rgb[:, :width] = [220, 30, 15]
    rgb[:, width:] = [15, 20, 220]
    jpg = io.BytesIO()
    Image.fromarray(rgb).save(jpg, format="JPEG", quality=95)
    return encode(meta, np.full((height, width), 1200, dtype="<u2").tobytes(),
                  np.full((height, width), confidence, dtype=np.uint8).tobytes(), jpg.getvalue())


def wait_ready(manager):
    deadline = time.monotonic() + 20
    while manager.status()["pending"]:
        if time.monotonic() > deadline:
            raise TimeoutError(manager.status())
        time.sleep(.015)
    return manager.status()


class FrameTests(unittest.TestCase):
    def test_projection_and_rgb(self):
        frame = decode(packet(str(uuid.uuid4()), offset=.25))
        self.assertAlmostEqual(np.linalg.det(frame.pose[:3, :3]), 1.)
        np.testing.assert_allclose(frame.pose @ [0, 0, 1.2, 1], [.25, 1.2, 0, 1])
        np.testing.assert_allclose(frame.pose @ [.1, .2, 1.2, 1], [.35, 1.2, -.2, 1])
        self.assertGreater(frame.color[24, 2, 0], 200)
        self.assertGreater(frame.color[24, -2, 2], 200)

    def test_tracking_and_mirror_rejected(self):
        with self.assertRaises(ValueError):
            decode(packet(str(uuid.uuid4()), tracking="PAUSED"))
        with self.assertRaises(ValueError):
            camera_transform(np.diag([-1, 1, 1, 1]))
        with self.assertRaises(ValueError):
            decode(b"H3S1\x00\x00\x00\x00")

    def test_timestamp_mismatch(self):
        content = packet(str(uuid.uuid4()))
        length, = struct.unpack_from("<I", content, 4)
        meta = json.loads(content[8:8+length])
        meta["rgb_timestamp_ns"] -= 1
        altered = encode(meta, b"", b"", content[8+length:])
        with self.assertRaisesRegex(ValueError, "unsynchronised"):
            decode(altered)


class FusionTests(unittest.TestCase):
    def test_plane_with_two_camera_positions(self):
        fusion = Fusion(Settings())
        sid = str(uuid.uuid4())
        self.assertFalse(fusion.integrate(decode(packet(sid, confidence=0))))
        for i in range(6):
            self.assertTrue(fusion.integrate(decode(packet(sid, i, offset=i*.06))))
        points = np.asarray(fusion.points())
        self.assertGreater(len(points), 100)
        np.testing.assert_allclose(points[:, 1], 1.2, atol=1e-5)
        self.assertAlmostEqual(fusion.path[-1][0], .3)
        mesh = fusion.mesh()
        self.assertGreater(len(mesh.triangles), 100)
        vertices = np.asarray(mesh.vertices)
        self.assertLess(float(np.max(np.abs(vertices[:, 1] - 1.2))), .03)
        with tempfile.TemporaryDirectory() as root:
            for fmt in ("ply", "glb"):
                output = Path(root) / f"scan.{fmt}"
                fusion.export(output, fmt)
                self.assertGreater(output.stat().st_size, 100)
                if fmt == "glb":
                    data = output.read_bytes()
                    self.assertEqual(struct.unpack_from("<4sII", data), (b"glTF", 2, len(data)))


class StreamTests(unittest.TestCase):
    def test_pairing_upload_dedup_stop_export_and_restore(self):
        with tempfile.TemporaryDirectory() as root:
            root = Path(root)
            manager = ScanManager(root, Settings())
            app = make_app(manager, "unit-test-token")
            hdr = {"Authorization": "Bearer unit-test-token"}
            sid = str(uuid.uuid4())
            with TestClient(app) as client:
                self.assertEqual(client.get("/v1/status").status_code, 401)
                self.assertEqual(client.post("/v1/command", headers=hdr, json=[]).status_code, 400)

                def command(kind, **extras):
                    result = client.post("/v1/command", headers=hdr,
                                         json=dict(type=kind, version=1, session=sid, **extras))
                    self.assertEqual(result.status_code, 200, result.text)
                    return result.json()

                def upload(i, **extras):
                    raw = packet(sid, i, **extras)
                    return client.post(f"/v1/sessions/{sid}/frames", content=raw,
                                       headers={**hdr, "X-Sha256": hashlib.sha256(raw).hexdigest()})

                command("scan.start")
                self.assertEqual(wait_ready(manager)["phase"], "scanning")
                self.assertTrue(command("connection.heartbeat", role="android")["phone_online"])
                with client.websocket_connect("/v1/events", headers=hdr) as ws:
                    self.assertEqual(ws.receive_json()["type"], "scan.status")
                    self.assertEqual(ws.receive_json()["type"], "mesh.updated")
                    ws.send_json({"type": "scan.pause", "version": 1, "session": sid})
                    self.assertEqual(ws.receive_json()["phase"], "paused")
                command("scan.resume")
                self.assertEqual(client.post(f"/v1/sessions/{sid}/frames", content=b"bad", headers=hdr).status_code, 400)
                for i in range(4):
                    result = upload(i, offset=i*.04)
                    self.assertEqual(result.status_code, 200, result.text)
                self.assertTrue(upload(0).json()["duplicate"])
                self.assertEqual(upload(0, offset=.1).status_code, 409)
                self.assertEqual(upload(5, tracking="PAUSED").status_code, 400)
                self.assertEqual(command("scan.stop", role="blender")["phase"], "stopping")
                self.assertTrue(upload(3, offset=3*.04).json()["duplicate"])
                self.assertEqual(command("scan.stop", role="android")["phase"], "stopped")
                self.assertEqual(wait_ready(manager)["integrated"], 4)
                self.assertGreater(len(client.get("/v1/preview/points", headers=hdr).json()["points"]), 100)
                self.assertGreater(len(client.get("/v1/preview/mesh", headers=hdr).json()["faces"]), 100)
                command("scan.export", format="ply")
                wait_ready(manager)
                self.assertEqual(client.get(f"/v1/sessions/{sid}/export/ply", headers=hdr).status_code, 200)

            manager.close()
            restarted = ScanManager(root, Settings())
            restarted.command("scan.start", sid)
            self.assertEqual(wait_ready(restarted)["integrated"], 4)
            restarted.close()

    def test_disk_ack_and_bounded_queue(self):
        import threading

        with tempfile.TemporaryDirectory() as root:
            manager = ScanManager(Path(root), Settings())
            sid = str(uuid.uuid4())
            manager.command("scan.start", sid)
            wait_ready(manager)
            release = threading.Event()
            entered = threading.Event()
            original = manager.apply

            def blocked(frame):
                entered.set()
                if not release.wait(10):
                    raise TimeoutError("test did not release worker")
                original(frame)

            manager.apply = blocked
            try:
                raw = packet(sid)
                manager.ingest(sid, raw, hashlib.sha256(raw).hexdigest())
                self.assertTrue(entered.wait(5))
                for i in range(1, 9):
                    raw = packet(sid, i)
                    manager.ingest(sid, raw, hashlib.sha256(raw).hexdigest())
                self.assertEqual(manager.jobs.qsize(), 8)
                self.assertEqual(len(list((Path(root) / sid).glob("*.h3s"))), 9)
                raw = packet(sid, 9)
                from pc_engine.session import Rejected
                with self.assertRaises(Rejected) as rejected:
                    manager.ingest(sid, raw, hashlib.sha256(raw).hexdigest())
                self.assertEqual(rejected.exception.code, 429)
            finally:
                release.set()
            self.assertEqual(wait_ready(manager)["integrated"], 9)
            raw = packet(sid, 9)
            self.assertEqual(manager.ingest(sid, raw, hashlib.sha256(raw).hexdigest())["ack"], 9)
            self.assertEqual(wait_ready(manager)["integrated"], 10)
            manager.close()


if __name__ == "__main__":
    unittest.main()
