"""Account, device and token endpoints."""

from __future__ import annotations

import time
from http import HTTPStatus

from ..auth import ACCESS_TTL_SECONDS
from .protocol import RequestHandler
from .validation import BadRequest


def me(handler: RequestHandler) -> None:
    session = handler.require_session()
    handler.send_json(
        HTTPStatus.OK,
        {
            "username": session.username,
            "device_id": session.device_id,
            "device_name": session.device_name,
            "expires_in": ACCESS_TTL_SECONDS,
        },
    )


def list_devices(handler: RequestHandler) -> None:
    session = handler.require_session()
    handler.send_json(
        HTTPStatus.OK,
        {
            "devices": handler.ctx.tokens.list_devices(
                session.user_id, session.device_id
            )
        },
    )


def login(handler: RequestHandler) -> None:
    ip = handler.client_ip()
    payload = handler.read_small_json()
    username = payload.get("username")
    password = payload.get("password")
    device_name = payload.get("device_name", "Unknown device")
    if not isinstance(username, str) or not isinstance(password, str):
        raise BadRequest("username and password are required")
    if not handler.rate(f"login:ip:{ip}", 5, 60):
        handler.send_rate_limited(60)
        return
    if not handler.rate(f"login:user:{username.casefold()}", 5, 60):
        handler.send_rate_limited(60)
        return
    issued = handler.ctx.tokens.login(username, password, str(device_name))
    handler.send_json(HTTPStatus.OK, issued.as_dict())


def pair(handler: RequestHandler) -> None:
    ip = handler.client_ip()
    payload = handler.read_small_json()
    code = payload.get("code")
    device_name = payload.get("device_name", "Unknown device")
    if not isinstance(code, str):
        raise BadRequest("code is required")
    if not handler.rate(f"pair:ip:{ip}", 5, 600):
        handler.send_rate_limited(600)
        return
    issued = handler.ctx.tokens.pair(code, str(device_name))
    handler.send_json(HTTPStatus.OK, issued.as_dict())


def refresh(handler: RequestHandler) -> None:
    ip = handler.client_ip()
    payload = handler.read_small_json()
    refresh_token = payload.get("refresh_token")
    if not isinstance(refresh_token, str):
        raise BadRequest("refresh_token is required")
    if not handler.rate(f"refresh:ip:{ip}", 30, 60):
        handler.send_rate_limited(60)
        return
    issued = handler.ctx.tokens.refresh(refresh_token)
    handler.send_json(HTTPStatus.OK, issued.as_dict())


def logout(handler: RequestHandler) -> None:
    session = handler.require_session()
    handler.ctx.tokens.logout(session.device_id)
    handler.send_json(HTTPStatus.OK, {"ok": True})


def pairing_code(handler: RequestHandler) -> None:
    session = handler.require_session()
    code, expires_at = handler.ctx.tokens.create_pairing_code(session.user_id)
    handler.send_json(
        HTTPStatus.OK,
        {
            "code": code,
            "expires_at": expires_at,
            "expires_in": max(0, expires_at - int(time.time())),
        },
    )


def revoke_device(handler: RequestHandler, path: str) -> None:
    session = handler.require_session()
    prefix = "/auth/devices/"
    device_id = path[len(prefix) :].strip()
    if not device_id or "/" in device_id:
        raise BadRequest("invalid device id")
    if not handler.ctx.tokens.revoke_device(session.user_id, device_id):
        handler.send_error_json(HTTPStatus.NOT_FOUND, "device not found")
        return
    handler.send_json(HTTPStatus.OK, {"ok": True})
