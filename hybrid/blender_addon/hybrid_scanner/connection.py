# SPDX-License-Identifier: GPL-3.0-or-later
"""Polling and downloads. This module has no Blender dependencies."""
import ipaddress
import json
import os
import queue
import re
import tempfile
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

MAX_PREVIEW = 12 * 1024**2
MAX_EXPORT = 256 * 1024**2


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Connector:
    def __init__(self, address, token):
        parsed = urllib.parse.urlsplit(address)
        if (parsed.scheme != "http" or parsed.username or parsed.password or parsed.path not in ("", "/")
                or parsed.query or parsed.fragment or not parsed.hostname or not parsed.port):
            raise ValueError("Use http://LAN-IP:port")
        ip = ipaddress.IPv4Address(parsed.hostname)
        if not (ip.is_private or ip.is_loopback or ip in ipaddress.IPv4Network("100.64.0.0/10")):
            raise ValueError("Use a local IPv4 address")
        if not re.fullmatch(r"[A-Za-z0-9_-]{30,100}", token):
            raise ValueError("Invalid pairing token")
        self.address = f"http://{ip}:{parsed.port}"
        self.token = token
        self.stop = threading.Event()
        self.jobs = queue.Queue(maxsize=6)
        self.results = queue.Queue(maxsize=8)
        self.lock = threading.Lock()
        self.snapshot = {}
        self.interval = .2
        self.points = self.mesh = self.path = True
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.thread = threading.Thread(target=self.run, name="Hybrid Scanner LAN", daemon=True)
        self.thread.start()

    def call(self, route, body=None):
        request = urllib.request.Request(self.address + route,
                                         headers={"Authorization": "Bearer " + self.token})
        if body is not None:
            request.add_header("Content-Type", "application/json")
            request.data = json.dumps({"version": 1, **body}).encode()
        return self.http.open(request, timeout=2.5)

    def receive(self, route, body=None):
        with self.call(route, body) as response:
            data = response.read(MAX_PREVIEW + 1)
        if len(data) > MAX_PREVIEW:
            raise ValueError("Response exceeds preview limit")
        return json.loads(data)

    def take(self):
        with self.lock:
            state = self.snapshot
            self.snapshot = {}
            return state

    def record(self, **values):
        with self.lock:
            self.snapshot.update(values)

    def enqueue(self, action, *args):
        self.jobs.put_nowait((action, args))

    def close(self):
        self.stop.set()

    def result(self, item):
        try:
            self.results.put_nowait(item)
        except queue.Full:
            if item[0] == "import":
                Path(item[1]).unlink(missing_ok=True)

    def download(self, sid, fmt, destination):
        path = Path(destination)
        with self.call(f"/v1/sessions/{sid}/export/{fmt}") as response:
            fd, name = tempfile.mkstemp(dir=path.parent, prefix=".hybrid-", suffix=".tmp")
            temp = Path(name)
            try:
                remaining = MAX_EXPORT
                with os.fdopen(fd, "wb") as out:
                    while chunk := response.read(256 * 1024):
                        remaining -= len(chunk)
                        if remaining < 0:
                            raise ValueError("Export exceeds download limit")
                        out.write(chunk)
                if self.stop.is_set():
                    raise OSError("Connector disconnected")
                temp.replace(path)
            finally:
                temp.unlink(missing_ok=True)

    def execute(self, action, args):
        if action == "command":
            self.receive("/v1/command", args[0])
            return
        sid, fmt, path = args
        if action == "import":
            fd, path = tempfile.mkstemp(suffix="." + fmt)
            os.close(fd)
        delivered = False
        try:
            self.receive("/v1/command", {"type": "scan.export", "session": sid, "format": fmt})
            deadline = time.monotonic()+30
            while not self.stop.is_set():
                try:
                    self.download(sid, fmt, path)
                    self.result((action, path))
                    delivered = True
                    return
                except urllib.error.HTTPError as error:
                    if error.code != 404 or time.monotonic() >= deadline:
                        raise
                state = self.receive("/v1/status")
                if state.get("error"):
                    raise ValueError(state["error"])
                self.stop.wait(.3)
        finally:
            if action == "import" and not delivered:
                Path(path).unlink(missing_ok=True)

    def run(self):
        revisions = {}
        while not self.stop.is_set():
            try:
                job = self.jobs.get_nowait()
            except queue.Empty:
                job = None
            if job:
                try:
                    self.execute(*job)
                except Exception as error:
                    self.result(("error", str(error)))
            try:
                state = self.receive("/v1/status")
                self.record(status=state, network_error="")
                for kind, enabled in (("points", self.points or self.path), ("mesh", self.mesh)):
                    revision = state[f"{'point' if kind == 'points' else 'mesh'}_revision"]
                    marker = (state.get("session"), revision)
                    if enabled and marker != revisions.get(kind):
                        payload = self.receive("/v1/preview/" + kind)
                        if payload.get("session") == marker[0]:
                            self.record(**{kind: payload})
                            revisions[kind] = (marker[0], payload["revision"])
            except Exception as error:
                revisions.clear()
                self.record(network_error=str(error))
                self.stop.wait(.7)
            self.stop.wait(self.interval)
