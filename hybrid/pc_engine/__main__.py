import argparse
import json
import logging
import os
import secrets
import socket
from pathlib import Path

# Limit OpenMP oversubscription on desktop CPUs.
os.environ.setdefault("OMP_NUM_THREADS", "4")

import qrcode
import uvicorn

from .frame import decode, session_id
from .fusion import Fusion, Settings
from .server import make_app
from .session import ScanManager


def lan_ip():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
        try:
            probe.connect(("192.0.2.1", 9))
            return probe.getsockname()[0]
        except OSError:
            return "127.0.0.1"


def main():
    parser = argparse.ArgumentParser(description="Hybrid Scanner")
    parser.add_argument("--settings", type=Path)
    commands = parser.add_subparsers(dest="command", required=True)
    serve = commands.add_parser("serve")
    serve.add_argument("--bind", default="0.0.0.0")
    serve.add_argument("--host", default=None, help="Advertised LAN IPv4")
    serve.add_argument("--port", type=int, default=8765)
    serve.add_argument("--data", type=Path, default=Path("scans"))
    replay = commands.add_parser("replay")
    replay.add_argument("session", type=Path)
    replay.add_argument("--output", type=Path, required=True)
    replay.add_argument("--poses", type=Path, help="JSON object mapping frame ID to row-major corrected ARCore c2w")
    args = parser.parse_args()
    settings = Settings(**json.loads(args.settings.read_text(encoding="utf-8"))) if args.settings else Settings()
    logging.basicConfig(level=logging.INFO)
    if args.command == "replay":
        from .frame import camera_transform
        fusion = Fusion(settings)
        poses = json.loads(args.poses.read_text(encoding="utf-8")) if args.poses else {}
        if args.output.suffix not in {".ply", ".glb"}:
            parser.error("Output suffix must be .ply or .glb")
        files = sorted(args.session.glob("*.h3s"))
        if not files:
            parser.error("No H3S frames in session directory")
        sid = session_id(args.session.resolve().name)
        for path in files:
            frame = decode(path.read_bytes())
            if frame.meta["session"] != sid:
                parser.error(f"Mismatched session in {path}")
            pose = poses.get(str(frame.meta["id"]))
            if pose is not None:
                frame.pose = camera_transform(pose)
            fusion.integrate(frame)
        fusion.export(args.output, args.output.suffix[1:])
        print(f"{fusion.count}/{len(files)} frames integrated. Export complete.")
        return
    token = secrets.token_urlsafe(32)
    host = args.host or lan_ip()
    code = f"h3s1:{host}:{args.port}:{token}"
    qr = qrcode.QRCode(border=4)
    qr.add_data(code)
    qr.make(fit=True)
    try:
        qr.print_ascii(tty=False)
    except UnicodeEncodeError:
        print("QR requires a UTF-8 terminal. Use the pairing code below.")
    print(f"Address: http://{host}:{args.port}\nPairing code: {code}\nToken: {token}\nValid for 12 hours.", flush=True)
    manager = ScanManager(args.data, settings)
    try:
        uvicorn.run(make_app(manager, token), host=args.bind, port=args.port,
                    ws="websockets", ws_max_size=4096, ws_max_queue=4,
                    limit_concurrency=16, access_log=False)
    finally:
        manager.close()


if __name__ == "__main__":
    main()
