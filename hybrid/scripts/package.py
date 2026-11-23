"""Build deterministic source and installer ZIPs from tracked-style source paths."""
import argparse
import shutil
from pathlib import Path
from zipfile import ZIP_DEFLATED, ZipFile, ZipInfo

ROOT = Path(__file__).resolve().parents[1]
NAME = "HybridScanner-0.1.0"


def collect(root):
    for path in sorted(root.rglob("*")):
        if (path.is_file() and path.suffix != ".pyc" and
                not any(part.startswith(".") or part in {"build", "__pycache__"}
                        for part in path.relative_to(root).parts) and path.name != "local.properties"):
            yield path


def write_zip(output, entries):
    with ZipFile(output, "w", compression=ZIP_DEFLATED, compresslevel=9) as archive:
        for path, name in entries:
            info = ZipInfo(str(name).replace("\\", "/"), (2026, 1, 1, 0, 0, 0))
            info.compress_type = ZIP_DEFLATED
            info.external_attr = 0o644 << 16
            archive.writestr(info, path.read_bytes())
    with ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise OSError(f"Archive verification failed: {output}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", type=Path, required=True)
    parser.add_argument("--apk", type=Path)
    args = parser.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    addon = ROOT / "blender_addon" / "hybrid_scanner"
    write_zip(args.out / f"{NAME}-Blender.zip", ((p, p.relative_to(addon)) for p in collect(addon)))
    win = [*collect(ROOT / "pc_engine"), *collect(ROOT / "docs"), ROOT / "README.md",
           ROOT / "Setup-Windows.cmd", ROOT / "Start-Scanner.cmd"]
    write_zip(args.out / f"{NAME}-Windows.zip", ((p, p.relative_to(ROOT)) for p in win))
    source = [(p, Path("hybrid") / p.relative_to(ROOT)) for p in collect(ROOT)]
    source.append((ROOT.parent / ".github/workflows/hybrid-scanner.yml",
                   Path(".github/workflows/hybrid-scanner.yml")))
    source.append((ROOT / ".gitignore", Path("hybrid/.gitignore")))
    write_zip(args.out / f"{NAME}-Source.zip", source)
    if args.apk:
        shutil.copyfile(args.apk, args.out / f"{NAME}-debug.apk")


if __name__ == "__main__":
    main()
