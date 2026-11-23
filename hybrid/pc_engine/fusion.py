"""CPU TSDF and bounded colored point preview."""
from dataclasses import dataclass
from pathlib import Path

import numpy as np
import open3d as o3d


@dataclass(frozen=True)
class Settings:
    voxel: float = 0.02
    truncation: float = 0.08
    depth_min: float = 0.5
    depth_max: float = 3.0
    confidence: int = 96
    integration_hz: float = 10
    mesh_hz: float = 1
    preview_faces: int = 12000
    preview_points: int = 16000
    max_blocks: int = 12000

    def validate(self):
        if not (0.005 <= self.voxel <= 0.2 and 2 <= self.truncation/self.voxel <= 12
                and 0.1 <= self.depth_min < self.depth_max <= 8 and 1 <= self.confidence <= 255
                and 0.1 <= self.integration_hz <= 30 and 0.1 <= self.mesh_hz <= 5
                and 100 <= self.preview_faces <= 30000 and 100 <= self.preview_points <= 30000
                and 128 <= self.max_blocks <= 50000):
            raise ValueError("Settings outside supported limits")


class Fusion:
    def __init__(self, settings):
        settings.validate()
        self.cfg = settings
        f32 = o3d.core.float32
        self.volume = o3d.t.geometry.VoxelBlockGrid(
            attr_names=("tsdf", "weight", "color"), attr_dtypes=(f32, f32, f32),
            attr_channels=((1,), (1,), (3,)), voxel_size=settings.voxel,
            block_resolution=8, block_count=min(2048, settings.max_blocks),
            device=o3d.core.Device("CPU:0"))
        self.cloud = {}
        self.count = 0
        self.last_ts = 0
        self.path = []

    def integrate(self, frame):
        cfg = self.cfg
        ts = frame.meta["timestamp_ns"]
        if ts - self.last_ts < int(1e9 / cfg.integration_hz) and self.last_ts:
            return False
        d = frame.depth.copy()
        d[(frame.confidence < cfg.confidence) | (d < cfg.depth_min * 1000)
          | (d > cfg.depth_max * 1000)] = 0
        if np.count_nonzero(d) < 16:
            return False
        tensor = o3d.core.Tensor
        depth = o3d.t.geometry.Image(d.astype(np.uint16, copy=False))
        color = o3d.t.geometry.Image(frame.color)
        k = tensor(frame.k)
        extrinsic = tensor(np.linalg.inv(frame.pose))
        multiplier = cfg.truncation / cfg.voxel
        keys = self.volume.compute_unique_block_coordinates(depth, k, extrinsic,
                                                           1000., cfg.depth_max, multiplier)
        if not keys.shape[0]:
            return False
        table = self.volume.hashmap()
        _, found = table.find(keys)
        if table.size() + np.count_nonzero(~found.numpy()) > cfg.max_blocks:
            raise RuntimeError("TSDF block limit reached. Finish the scan or increase voxel size.")
        self.volume.integrate(keys, depth, color, k, extrinsic, 1000., cfg.depth_max, multiplier)
        self.add_points(frame, d)
        self.last_ts = ts
        self.count += 1
        self.path.append(frame.pose[:3, 3].round(5).tolist())
        return True

    def add_points(self, frame, depth):
        y, x = np.nonzero(depth)
        stride = max(1, (len(x) + 4095) // 4096)
        x, y = x[::stride], y[::stride]
        z = depth[y, x] * .001
        k = frame.k
        camera = np.stack(((x-k[0, 2])*z/k[0, 0], (y-k[1, 2])*z/k[1, 1], z), axis=1)
        world = camera @ frame.pose[:3, :3].T + frame.pose[:3, 3]
        keys = np.floor(world / self.cfg.voxel).astype(int)
        for key, point, color in zip(keys, world, frame.color[y, x]):
            key = tuple(key)
            if key not in self.cloud and len(self.cloud) >= self.cfg.preview_points:
                del self.cloud[next(iter(self.cloud))]
            self.cloud[key] = [*point.round(5).tolist(), *color.tolist()]

    def points(self):
        return list(self.cloud.values())

    def mesh(self):
        if not self.count:
            return o3d.t.geometry.TriangleMesh().to_legacy()
        return self.volume.extract_triangle_mesh(weight_threshold=1.0).to_legacy()

    def preview(self):
        mesh = self.mesh()
        if len(mesh.triangles) > self.cfg.preview_faces:
            mesh = mesh.simplify_quadric_decimation(self.cfg.preview_faces)
        if len(mesh.triangles) > 30000 or len(mesh.vertices) > 60000:
            return {"vertices": [], "faces": []}
        return {"vertices": np.asarray(mesh.vertices).round(5).tolist(),
                "faces": np.asarray(mesh.triangles).tolist()}

    def export(self, output: Path, fmt):
        mesh = self.mesh()
        if not len(mesh.triangles):
            if fmt == "glb" or not self.cloud:
                raise ValueError("No mesh yet. Capture more overlapping views.")
            points = np.asarray(self.points())
            cloud = o3d.geometry.PointCloud(o3d.utility.Vector3dVector(points[:, :3]))
            cloud.colors = o3d.utility.Vector3dVector(points[:, 3:] / 255.)
            success = o3d.io.write_point_cloud(str(output), cloud)
        elif fmt == "glb":
            from .glb import write_glb
            write_glb(output, mesh)
            success = True
        else:
            mesh.compute_vertex_normals()
            success = o3d.io.write_triangle_mesh(str(output), mesh)
        if not success:
            raise OSError(f"Could not write {output.name}")
