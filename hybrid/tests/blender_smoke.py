"""Run in Blender background against the server started by run_smoke.py."""
import json
import os
import sys
import time
from pathlib import Path

import bpy

sys.path.insert(0, str(Path.cwd() / "blender_addon"))
import hybrid_scanner as scanner

config = json.loads(os.environ["HYBRID_SMOKE_CONFIG"])
url, token, session, files = (config[k] for k in ("url", "token", "session", "files"))
scanner.register()
try:
    scanner._connector = scanner.Connector(url, token)
    deadline = time.monotonic() + 12
    while not (scanner._points and scanner._mesh_object and scanner._path):
        scanner.poll()
        if time.monotonic() > deadline:
            raise RuntimeError((scanner._state, scanner._error, scanner._work_message))
        time.sleep(.05)
    assert scanner._state["session"] == session
    assert len(scanner._mesh_object.data.polygons) > 100
    assert len(scanner._path) >= 20
    for file in files:
        previous = set(bpy.data.objects)
        scanner.import_scan(file)
        objects = [o for o in bpy.data.objects if o not in previous and o.type == "MESH"]
        assert len(objects) == 1
        obj = objects[0]
        assert len(obj.data.polygons) > 100
        points = [obj.matrix_world @ v.co for v in obj.data.vertices]
        assert max(abs(v.y - 1.2) for v in points) < .03
        print(f"IMPORT_OK {Path(file).suffix} {len(obj.data.vertices)} vertices {len(obj.data.polygons)} triangles")
    print(f"CONNECTOR_OK {len(scanner._points)} points; {len(scanner._path)} camera poses")
finally:
    scanner.unregister()
