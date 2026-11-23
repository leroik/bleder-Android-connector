"""H3S/1 RGB-D packets and coordinate transforms."""
import io
import json
import struct
import uuid
from dataclasses import dataclass

import numpy as np
from PIL import Image

MAGIC = b"H3S1"
MAX_PACKET = 8 * 1024 * 1024
# Camera CV (right, down, forward) -> ARCore camera (right, up, backward).
CV_TO_AR = np.diag([1., -1., -1., 1.])
# ARCore world (Y up) -> Blender world (Z up), proper rotation.
AR_TO_BLENDER = np.array([[1., 0., 0., 0.], [0., 0., -1., 0.],
                          [0., 1., 0., 0.], [0., 0., 0., 1.]])


def session_id(value):
    if str(uuid.UUID(value)) != value:
        raise ValueError("Expected canonical session UUID")
    return value


def camera_transform(ar_pose):
    pose = np.asarray(ar_pose, dtype=np.float64).reshape(4, 4)
    rotation = pose[:3, :3]
    if (not np.isfinite(pose).all() or not np.allclose(pose[3], [0, 0, 0, 1])
            or not np.allclose(rotation.T @ rotation, np.eye(3), atol=1e-3)
            or abs(np.linalg.det(rotation) - 1) > 1e-3
            or np.linalg.norm(pose[:3, 3]) > 1000):
        raise ValueError("Invalid rigid camera pose")
    return AR_TO_BLENDER @ pose @ CV_TO_AR


@dataclass
class Frame:
    meta: dict
    k: np.ndarray
    pose: np.ndarray
    depth: np.ndarray
    confidence: np.ndarray
    color: np.ndarray


def decode(packet):
    if len(packet) < 8 or len(packet) > MAX_PACKET or packet[:4] != MAGIC:
        raise ValueError("Bad packet magic or size")
    size, = struct.unpack_from("<I", packet, 4)
    if not 0 < size <= min(16384, len(packet) - 8):
        raise ValueError("Bad metadata length")
    m = json.loads(packet[8:8 + size])
    if (m["version"] != 1 or m["type"] != "frame.meta" or m["tracking"] != "TRACKING"
            or m["coordinates"] != "arcore_c2w_row_major_m"):
        raise ValueError("Unsupported metadata or tracking lost")
    session_id(m["session"])
    fid, ts = m["id"], m["timestamp_ns"]
    if (type(fid) is not int or not 0 <= fid < 10**9 or type(ts) is not int
            or not 0 < ts < 2**63 or m["depth_timestamp_ns"] != ts or m["rgb_timestamp_ns"] != ts
            or m["confidence_timestamp_ns"] != ts):
        raise ValueError("Invalid frame ID or unsynchronised timestamps")
    w, h = m["width"], m["height"]
    rw, rh = m["rgb_width"], m["rgb_height"]
    if not all(type(n) is int and 0 < n <= 4096 for n in (w, h, rw, rh)):
        raise ValueError("Invalid dimensions")
    if w * h > 1024 * 1024 or rw * rh > 4096 * 2160:
        raise ValueError("Image exceeds limits")
    fx, fy, cx, cy = np.asarray(m["intrinsics"], dtype=np.float64)
    if not np.isfinite([fx, fy, cx, cy]).all() or not (
            1 <= fx <= w * 20 and 1 <= fy <= h * 20 and -w < cx < 2*w and -h < cy < 2*h):
        raise ValueError("Invalid depth intrinsics")
    pose = camera_transform(m["camera_to_world"])
    body = memoryview(packet)[8 + size:]
    n = w * h
    if len(body) <= n * 3:
        raise ValueError("Truncated RGB-D packet")
    depth = np.frombuffer(body[:2*n], dtype="<u2").reshape(h, w)
    confidence = np.frombuffer(body[2*n:3*n], dtype=np.uint8).reshape(h, w)
    # Native, unrotated RGB; depth pixels are mapped into this grid by the sender's affine.
    mapping = np.asarray(m["depth_to_rgb"], dtype=np.float64).reshape(2, 3)
    if (not np.isfinite(mapping).all() or np.max(np.abs(mapping)) > 8192
            or abs(np.linalg.det(mapping[:, :2])) < 1e-6):
        raise ValueError("Invalid depth-to-RGB mapping")
    rgb_calibration = np.asarray(m["rgb_intrinsics"], dtype=np.float64)
    if (rgb_calibration.shape != (4,) or not np.isfinite(rgb_calibration).all()
            or np.any(rgb_calibration[:2] <= 0)):
        raise ValueError("Invalid RGB intrinsics")
    with Image.open(io.BytesIO(body[3*n:])) as image:
        if image.format != "JPEG" or image.size != (rw, rh):
            raise ValueError("Expected JPEG with declared dimensions")
        rgb = np.asarray(image.convert("RGB"))
    y, x = np.ogrid[:h, :w]
    u, v = [np.rint(a*x + b*y + c).astype(np.int32) for a, b, c in mapping]
    color = rgb[v.clip(0, rh-1), u.clip(0, rw-1)].copy()
    color[(u < 0) | (v < 0) | (u >= rw) | (v >= rh)] = 0
    return Frame(m, np.array([[fx, 0, cx], [0, fy, cy], [0, 0, 1.]]),
                 pose, depth, confidence, color)


def encode(meta, depth, confidence, jpeg):
    header = json.dumps(meta, separators=(",", ":")).encode()
    return MAGIC + struct.pack("<I", len(header)) + header + depth + confidence + jpeg
