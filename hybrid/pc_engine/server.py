"""Authenticated LAN HTTP and WebSocket server for H3S/1."""
import asyncio
import hmac
import json
import logging
import time

from fastapi import FastAPI, HTTPException, Request, WebSocket, WebSocketDisconnect
from fastapi.responses import FileResponse
from starlette.concurrency import run_in_threadpool

from .frame import MAX_PACKET, session_id
from .session import Rejected


def make_app(manager, token, ttl=12*3600):
    app = FastAPI(title="Hybrid Scan Engine", version="0.1.0", docs_url=None,
                  redoc_url=None, openapi_url=None)
    expires = time.monotonic() + ttl
    incoming = asyncio.Semaphore(2)

    def authorized(headers):
        candidate = headers.get("authorization", "")
        return (time.monotonic() < expires and candidate.isascii()
                and hmac.compare_digest(candidate, "Bearer " + token))

    def validate(headers):
        if not authorized(headers):
            raise HTTPException(401, "Invalid or expired pairing token")

    async def read(request, limit):
        buf = bytearray()
        async for chunk in request.stream():
            if len(buf) + len(chunk) > limit:
                raise HTTPException(413, "Body exceeds protocol limit")
            buf.extend(chunk)
        return bytes(buf)

    def command(msg):
        if not isinstance(msg, dict) or msg.get("version") != 1:
            raise HTTPException(400, "Expected protocol version 1")
        sid = msg.get("session")
        try:
            if sid is not None:
                session_id(sid)
            return manager.command(msg.get("type"), sid, msg.get("format"), msg.get("role"))
        except Rejected as exc:
            raise HTTPException(exc.code, str(exc)) from exc
        except (TypeError, ValueError, AttributeError) as exc:
            raise HTTPException(400, "Invalid command or missing session") from exc
        except OSError as exc:
            logging.exception("Session storage error")
            raise HTTPException(507, "Session storage unavailable") from exc

    @app.get("/v1/status")
    def status(req: Request):
        validate(req.headers)
        return manager.status()

    @app.post("/v1/command")
    async def action(req: Request):
        validate(req.headers)
        try:
            msg = json.loads(await read(req, 4096))
        except ValueError as exc:
            raise HTTPException(400, "Invalid JSON") from exc
        return await run_in_threadpool(command, msg)

    @app.post("/v1/sessions/{sid}/frames")
    async def frame(sid: str, req: Request):
        validate(req.headers)
        if incoming.locked():
            raise HTTPException(429, "Upload slots are busy")
        async with incoming:
            try:
                session_id(sid)
                data = await asyncio.wait_for(read(req, MAX_PACKET), timeout=15)
                return await run_in_threadpool(manager.ingest, sid, data, req.headers.get("x-sha256"))
            except Rejected as exc:
                raise HTTPException(exc.code, str(exc)) from exc
            except TimeoutError as exc:
                raise HTTPException(408, "Upload timed out") from exc
            except (ValueError, TypeError, KeyError, IndexError, AttributeError, OverflowError) as exc:
                raise HTTPException(400, f"Invalid frame: {exc}") from exc
            except OSError as exc:
                if exc.errno is None:
                    raise HTTPException(400, "Corrupt JPEG") from exc
                raise HTTPException(507, "Raw frame storage unavailable") from exc

    @app.get("/v1/preview/{kind}")
    def preview(kind: str, req: Request):
        validate(req.headers)
        with manager.lock:
            if kind == "points":
                return dict(session=manager.sid, revision=manager.point_revision,
                            points=manager.geometry["points"], path=manager.geometry["path"])
            if kind == "mesh":
                return dict(session=manager.sid, revision=manager.mesh_revision,
                            **manager.geometry["mesh"])
        raise HTTPException(404, "Unknown preview type")

    @app.get("/v1/sessions/{sid}/export/{fmt}")
    def download(sid: str, fmt: str, req: Request):
        validate(req.headers)
        try:
            session_id(sid)
        except ValueError as exc:
            raise HTTPException(404, "Unknown session") from exc
        if fmt not in {"ply", "glb"}:
            raise HTTPException(404, "Unknown export")
        path = manager.root / sid / f"scan.{fmt}"
        if not path.is_file():
            raise HTTPException(404, "Export not ready")
        return FileResponse(path, filename=f"{sid}.{fmt}")

    @app.websocket("/v1/events")
    async def events(ws: WebSocket):
        if not authorized(ws.headers):
            await ws.close(1008)
            return
        await ws.accept()
        previous_mesh = -1
        try:
            while authorized(ws.headers):
                state = manager.status()
                await ws.send_json(state)
                if state["mesh_revision"] != previous_mesh:
                    previous_mesh = state["mesh_revision"]
                    await ws.send_json(dict(type="mesh.updated", version=1, session=state["session"],
                                            revision=previous_mesh))
                try:
                    raw = await asyncio.wait_for(ws.receive_text(), timeout=.25)
                except TimeoutError:
                    continue
                try:
                    msg = json.loads(raw)
                    reply = await run_in_threadpool(command, msg)
                    await ws.send_json(reply)
                except (HTTPException, ValueError) as exc:
                    await ws.send_json(dict(type="error", version=1,
                                            detail=exc.detail if isinstance(exc, HTTPException) else "Invalid JSON"))
            await ws.close(1008)
        except WebSocketDisconnect:
            pass

    return app
