"""Write a single vertex-colored glTF 2.0 binary primitive."""
import json
import struct

import numpy as np


def write_glb(path, mesh):
    # Blender Z-up -> glTF Y-up, preserving winding and scale.
    vertices = np.asarray(mesh.vertices)[:, [0, 2, 1]].astype("<f4")
    vertices[:, 2] *= -1
    colors = np.clip(np.asarray(mesh.vertex_colors), 0, 1).astype("<f4")
    # Convert sRGB vertex samples to linear glTF COLOR_0.
    colors = np.where(colors <= .04045, colors / 12.92, ((colors + .055) / 1.055)**2.4).astype("<f4")
    indices = np.asarray(mesh.triangles, dtype="<u4").reshape(-1)
    buffer = bytearray()
    views, accessors = [], []

    def add(array, component, kind, target):
        index = len(views)
        raw = array.tobytes()
        views.append(dict(buffer=0, byteOffset=len(buffer), byteLength=len(raw), target=target))
        accessors.append(dict(bufferView=index, componentType=component,
                              count=len(array), type=kind))
        buffer.extend(raw)
        return index

    position = add(vertices, 5126, "VEC3", 34962)
    accessors[position].update(min=vertices.min(axis=0).tolist(), max=vertices.max(axis=0).tolist())
    attributes = {"POSITION": position}
    if len(colors) == len(vertices):
        attributes["COLOR_0"] = add(colors, 5126, "VEC3", 34962)
    primitive = dict(attributes=attributes, indices=add(indices, 5125, "SCALAR", 34963))
    doc = dict(asset={"version": "2.0"}, scene=0, scenes=[{"nodes": [0]}], nodes=[{"mesh": 0}],
               meshes=[{"primitives": [primitive]}], buffers=[{"byteLength": len(buffer)}],
               bufferViews=views, accessors=accessors)
    data = json.dumps(doc, separators=(",", ":")).encode()
    data += b" " * (-len(data) % 4)
    with path.open("wb") as out:
        out.write(struct.pack("<4sII", b"glTF", 2, 28 + len(data) + len(buffer)))
        out.write(struct.pack("<I4s", len(data), b"JSON"))
        out.write(data)
        out.write(struct.pack("<I4s", len(buffer), b"BIN\0"))
        out.write(buffer)
