"""Send a moving-camera synthetic RGB-D scan through real HTTP."""
import argparse
import hashlib
import json
import time
import urllib.error
import urllib.request
import uuid

from .test_scanner import packet


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://127.0.0.1:8765")
    parser.add_argument("--token", required=True)
    parser.add_argument("--count", type=int, default=30)
    args = parser.parse_args()
    sid = str(uuid.uuid4())

    def request(path, data=None, headers=None):
        req = urllib.request.Request(args.url + path, data=data, headers={
            "Authorization": "Bearer " + args.token, **(headers or {})})
        with urllib.request.urlopen(req, timeout=5) as response:
            return json.load(response)

    def command(kind, **kwargs):
        return request("/v1/command", json.dumps(
            {"version": 1, "type": kind, "session": sid, **kwargs}).encode(),
            {"Content-Type": "application/json"})

    def ready(phase=None):
        limit = time.monotonic() + 30
        while time.monotonic() < limit:
            status = request("/v1/status")
            if status["error"]:
                raise ValueError(status)
            if not status["pending"] and (not phase or status["phase"] == phase):
                return status
            time.sleep(.05)
        raise TimeoutError(status)

    command("scan.start")
    ready("scanning")
    began = time.monotonic()
    for i in range(args.count):
        raw = packet(sid, i, offset=i*.012)
        while True:
            try:
                request(f"/v1/sessions/{sid}/frames", raw, {"X-SHA256": hashlib.sha256(raw).hexdigest()})
                break
            except urllib.error.HTTPError as error:
                if error.code != 429 or time.monotonic() - began > 120:
                    raise
                time.sleep(.15)
        time.sleep(.1)
    ready()
    command("scan.stop")
    state = ready("stopped")
    for fmt in ("ply", "glb"):
        command("scan.export", format=fmt)
        ready("stopped")
    print(json.dumps({"session": sid, "frames": state["integrated"],
                      "stream_seconds": round(time.monotonic() - began, 3),
                      "last_integration_ms": state["integration_ms"],
                      "last_mesh_ms": state["mesh_ms"]}, indent=2))


if __name__ == "__main__":
    main()
