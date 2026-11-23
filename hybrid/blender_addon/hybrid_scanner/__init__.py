# SPDX-License-Identifier: GPL-3.0-or-later
bl_info = {
    "name": "Hybrid 3D Scanner",
    "author": "Hybrid Scanner contributors",
    "version": (0, 1, 0),
    "blender": (4, 2, 0),
    "category": "3D View",
    "location": "3D View > Sidebar > 3D Scanner",
}

import queue
import uuid
from pathlib import Path

import bpy
import gpu
from bpy.props import BoolProperty, EnumProperty, FloatProperty, PointerProperty, StringProperty
from gpu_extras.batch import batch_for_shader

from .connection import Connector

_connector = None
_state = {}
_error = ""
_work_message = ""
_mesh_object = None
_mesh_session = None
_points = []
_path = []
_draw_handle = None
_gpu_dirty = True
_batches = {}


def update_mesh(payload):
    global _mesh_object, _mesh_session
    scene = bpy.context.scene
    if scene is None:
        return
    sid = payload.get("session")
    verts, faces = payload["vertices"], payload["faces"]
    if _mesh_object is not None:
        try:
            if _mesh_object.name not in bpy.data.objects or scene.objects.get(_mesh_object.name) is None:
                _mesh_object = None
        except ReferenceError:
            _mesh_object = None
    if not verts or not faces:
        if _mesh_object is not None:
            _mesh_object.data.clear_geometry()
            _mesh_object.hide_viewport = True
        _mesh_session = sid
        return
    if _mesh_object is None:
        datablock = bpy.data.meshes.new("Hybrid TSDF Preview")
        _mesh_object = bpy.data.objects.new("Hybrid TSDF Preview", datablock)
        _mesh_object.hide_render = True
        scene.collection.objects.link(_mesh_object)
        _mesh_object["hybrid_scanner_preview"] = True
    else:
        datablock = _mesh_object.data
        datablock.clear_geometry()
    datablock.from_pydata(verts, [], faces)
    datablock.update()
    _mesh_session = sid
    _mesh_object.hide_viewport = not scene.hybrid_scanner.show_mesh


def redraw():
    for manager in bpy.data.window_managers:
        for window in manager.windows:
            for area in window.screen.areas:
                if area.type == "VIEW_3D":
                    area.tag_redraw()


def draw():
    global _gpu_dirty
    scene = bpy.context.scene
    if not scene or _connector is None:
        return
    settings = scene.hybrid_scanner
    if _gpu_dirty:
        _batches.clear()
        if _points:
            shader = gpu.shader.from_builtin("SMOOTH_COLOR")
            vertices = [p[:3] for p in _points]
            colors = [(*[v/255. for v in p[3:6]], 1.) for p in _points]
            _batches["points"] = (shader, batch_for_shader(shader, "POINTS", {"pos": vertices, "color": colors}))
        if len(_path) > 1:
            shader = gpu.shader.from_builtin("UNIFORM_COLOR")
            _batches["path"] = (shader, batch_for_shader(shader, "LINE_STRIP", {"pos": _path}))
        _gpu_dirty = False
    try:
        gpu.state.depth_test_set("LESS_EQUAL")
        if settings.show_points and "points" in _batches:
            shader, batch = _batches["points"]
            gpu.state.point_size_set(3)
            shader.bind()
            batch.draw(shader)
        if settings.show_path and "path" in _batches:
            shader, batch = _batches["path"]
            shader.bind()
            shader.uniform_float("color", (1., .65, .18, 1.))
            batch.draw(shader)
    finally:
        gpu.state.point_size_set(1)
        gpu.state.depth_test_set("NONE")


def import_scan(filename):
    if Path(filename).suffix == ".glb":
        bpy.ops.import_scene.gltf(filepath=filename)
    else:
        bpy.ops.wm.ply_import(filepath=filename)


def poll():
    global _state, _error, _work_message, _points, _path, _gpu_dirty
    if _connector is None:
        return .25
    scene = bpy.context.scene
    if scene is None:
        return .25
    prefs = scene.hybrid_scanner
    _connector.interval = prefs.interval
    _connector.points = prefs.show_points
    _connector.path = prefs.show_path
    _connector.mesh = prefs.show_mesh
    payload = _connector.take()
    if "status" in payload:
        new_state = payload["status"]
        if new_state.get("session") != _state.get("session"):
            _points, _path = [], []
            _gpu_dirty = True
            if _mesh_object is not None:
                try:
                    _mesh_object.hide_viewport = True
                except ReferenceError:
                    pass
        _state = new_state
    if "network_error" in payload:
        _error = payload["network_error"]
    if "mesh" in payload:
        try:
            update_mesh(payload["mesh"])
        except Exception as error:
            _work_message = str(error)
    if _mesh_object is not None and _mesh_session == _state.get("session"):
        try:
            _mesh_object.hide_viewport = not prefs.show_mesh or not len(_mesh_object.data.polygons)
        except ReferenceError:
            pass
    if "points" in payload:
        _points = payload["points"]["points"]
        _path = payload["points"]["path"]
        _gpu_dirty = True
    try:
        category, value = _connector.results.get_nowait()
        if category == "import":
            try:
                import_scan(value)
                _work_message = "Модель импортирована"
            finally:
                Path(value).unlink(missing_ok=True)
        else:
            _work_message = str(value)
    except queue.Empty:
        pass
    except Exception as error:
        _work_message = str(error)
    if payload or _work_message:
        redraw()
    return min(.2, prefs.interval)


def disconnect():
    global _connector, _state, _error, _points, _path, _mesh_object, _mesh_session, _gpu_dirty
    if _connector:
        _connector.close()
    _connector = None
    _state = {}
    _error = ""
    _points, _path = [], []
    _gpu_dirty = True
    if _mesh_object is not None:
        try:
            datablock = _mesh_object.data
            bpy.data.objects.remove(_mesh_object, do_unlink=True)
            if datablock.users == 0:
                bpy.data.meshes.remove(datablock)
        except ReferenceError:
            pass
    _mesh_object = _mesh_session = None
    redraw()


class HYBRID_Settings(bpy.types.PropertyGroup):
    address: StringProperty(name="Адрес ПК", default="http://127.0.0.1:8765")
    token: StringProperty(name="Токен", subtype="PASSWORD", options={"SKIP_SAVE"})
    show_points: BoolProperty(name="Точки", default=True)
    show_mesh: BoolProperty(name="Mesh", default=True)
    show_path: BoolProperty(name="Траектория", default=True)
    interval: FloatProperty(name="Интервал, c", default=.2, min=.12, max=3.)
    format: EnumProperty(name="Формат", items=[("ply", "PLY", ""), ("glb", "GLB", "")])


class HYBRID_OT_connection(bpy.types.Operator):
    bl_idname = "hybrid_scanner.connection"
    bl_label = "Подключение"
    close: BoolProperty(default=False)

    def execute(self, context):
        global _connector, _work_message
        disconnect()
        _work_message = ""
        if self.close:
            return {"FINISHED"}
        props = context.scene.hybrid_scanner
        try:
            _connector = Connector(props.address, props.token)
            props.token = ""
        except (ValueError, OSError) as error:
            self.report({"ERROR"}, str(error))
            return {"CANCELLED"}
        return {"FINISHED"}


class HYBRID_OT_control(bpy.types.Operator):
    bl_idname = "hybrid_scanner.control"
    bl_label = "Команда Scan Engine"
    action: StringProperty()

    def execute(self, context):
        global _work_message
        if not _connector:
            return {"CANCELLED"}
        sid = str(uuid.uuid4()) if self.action == "scan.start" else _state.get("session")
        if sid is None:
            return {"CANCELLED"}
        try:
            _connector.enqueue("command", {"type": self.action, "session": sid, "role": "blender"})
            _work_message = ""
        except queue.Full:
            self.report({"WARNING"}, "Очередь команд занята")
            return {"CANCELLED"}
        return {"FINISHED"}


class HYBRID_OT_final(bpy.types.Operator):
    bl_idname = "hybrid_scanner.final"
    bl_label = "Получить модель"
    filepath: StringProperty(subtype="FILE_PATH")
    import_result: BoolProperty()

    def invoke(self, context, event):
        if self.import_result:
            return self.execute(context)
        fmt = context.scene.hybrid_scanner.format
        self.filepath = str(Path.home() / f"scan.{fmt}")
        context.window_manager.fileselect_add(self)
        return {"RUNNING_MODAL"}

    def execute(self, context):
        global _work_message
        if not _connector or _state.get("phase") != "stopped" or _state.get("pending"):
            self.report({"WARNING"}, "Завершите сканирование и дождитесь обработки")
            return {"CANCELLED"}
        fmt = context.scene.hybrid_scanner.format
        target = bpy.path.ensure_ext(self.filepath, "." + fmt)
        try:
            _connector.enqueue("import" if self.import_result else "download", _state["session"], fmt, target)
        except queue.Full:
            self.report({"WARNING"}, "Очередь команд занята")
            return {"CANCELLED"}
        _work_message = "Подготовка файла…"
        return {"FINISHED"}


class HYBRID_PT_panel(bpy.types.Panel):
    bl_label = "3D Scanner"
    bl_idname = "HYBRID_PT_panel"
    bl_space_type = "VIEW_3D"
    bl_region_type = "UI"
    bl_category = "3D Scanner"

    def draw(self, context):
        ui = self.layout
        settings = context.scene.hybrid_scanner
        if not _connector:
            ui.prop(settings, "address")
            ui.prop(settings, "token")
            ui.operator("hybrid_scanner.connection", text="Подключить")
            return
        ui.operator("hybrid_scanner.connection", text="Отключить").close = True
        if _error:
            ui.label(text=_error, icon="ERROR")
        phase = _state.get("phase", "подключение")
        ui.label(text=f"Scan Engine · {phase}")
        ui.label(text="Телефон в сети" if _state.get("phone_online") else "Телефон вне сети")
        ui.label(text=f"Кадры {_state.get('integrated', 0)} / {_state.get('accepted', 0)}")
        ui.label(text=f"Fusion {_state.get('integration_ms', 0)} мс · Mesh {_state.get('mesh_ms', 0)} мс")
        if _state.get("error"):
            ui.label(text=_state["error"], icon="ERROR")
        controls = ui.row(align=True)
        if phase in {"idle", "stopped", "error"} and not _state.get("pending"):
            controls.operator("hybrid_scanner.control", text="Начать").action = "scan.start"
        if phase in {"scanning", "paused"}:
            scanning = phase == "scanning"
            controls.operator("hybrid_scanner.control", text="Пауза" if scanning else "Продолжить").action = (
                "scan.pause" if scanning else "scan.resume")
        if phase in {"scanning", "paused", "stopping"}:
            controls.operator("hybrid_scanner.control", text="Стоп").action = "scan.stop"
        views = ui.row(align=True)
        for prop in ("show_points", "show_mesh", "show_path"):
            views.prop(settings, prop, toggle=True)
        ui.prop(settings, "interval")
        ui.prop(settings, "format")
        buttons = ui.row(align=True)
        buttons.enabled = phase == "stopped" and not _state.get("pending")
        buttons.operator("hybrid_scanner.final", text="Импорт").import_result = True
        buttons.operator("hybrid_scanner.final", text="Экспорт").import_result = False
        if _work_message:
            ui.label(text=_work_message)


CLASSES = (HYBRID_Settings, HYBRID_OT_connection, HYBRID_OT_control, HYBRID_OT_final, HYBRID_PT_panel)


def register():
    global _draw_handle
    for cls in CLASSES:
        bpy.utils.register_class(cls)
    bpy.types.Scene.hybrid_scanner = PointerProperty(type=HYBRID_Settings)
    if not bpy.app.background:
        _draw_handle = bpy.types.SpaceView3D.draw_handler_add(draw, (), "WINDOW", "POST_VIEW")
    bpy.app.timers.register(poll, persistent=True)


def unregister():
    global _draw_handle
    disconnect()
    if bpy.app.timers.is_registered(poll):
        bpy.app.timers.unregister(poll)
    if _draw_handle is not None:
        bpy.types.SpaceView3D.draw_handler_remove(_draw_handle, "WINDOW")
        _draw_handle = None
    _batches.clear()
    del bpy.types.Scene.hybrid_scanner
    for cls in reversed(CLASSES):
        bpy.utils.unregister_class(cls)
