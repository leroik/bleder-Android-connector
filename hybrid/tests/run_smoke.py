"""Start real HTTP locally, send synthetic packets, optionally verify Blender imports."""
import argparse
import contextlib
import io
import json
import os
import secrets
import socket
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

os.environ.setdefault("OMP_NUM_THREADS", "4")

import uvicorn

from pc_engine.fusion import Settings
from pc_engine.server import make_app
from pc_engine.session import ScanManager
from tests import synthetic_demo


def run(root, blender):
    token = secrets.token_urlsafe(32)
    manager = ScanManager(root, Settings())
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
        server = uvicorn.Server(uvicorn.Config(make_app(manager, token), log_level="warning"))
        run_server = threading.Thread(target=server.run, kwargs={"sockets": [sock]}, daemon=True)
        run_server.start()
        try:
            deadline = time.monotonic() + 10
            while not server.started:
                if time.monotonic() > deadline:
                    raise TimeoutError("Server startup")
                time.sleep(.025)
            base = f"http://127.0.0.1:{port}"
            buffer = io.StringIO()
            sys.argv = ["synthetic_demo", "--url", base, "--token", token]
            with contextlib.redirect_stdout(buffer):
                synthetic_demo.main()
            result = json.loads(buffer.getvalue())
            print(json.dumps(result, indent=2), flush=True)
            assert result["frames"] == 30
            sid = result["session"]
            files = [str(root / sid / f"scan.{fmt}") for fmt in ("ply", "glb")]
            replay = root / "replay.ply"
            posefile = root / "poses.json"
            posefile.write_text(json.dumps({"29": [1, 0, 0, .351, 0, 1, 0, 0, 0, 0, 1, 0, 0, 0, 0, 1]}))
            subprocess.run([sys.executable, "-m", "pc_engine", "replay", str(root / sid),
                            "--output", str(replay), "--poses", str(posefile)], check=True)
            assert replay.exists()
            if blender:
                env = {**os.environ, "HYBRID_SMOKE_CONFIG": json.dumps(
                    {"url": base, "token": token, "session": sid, "files": files})}
                subprocess.run([blender, "-b", "-t", "2", "--factory-startup", "--python-exit-code", "1",
                                "--python", "tests/blender_smoke.py"], env=env, check=True, timeout=35)
        finally:
            server.should_exit = True
            run_server.join(timeout=5)
            manager.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--blender", help="Path to Blender executable")
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    if args.output:
        run(args.output.resolve(), args.blender)
    else:
        with tempfile.TemporaryDirectory() as tmp:
            run(Path(tmp), args.blender)
