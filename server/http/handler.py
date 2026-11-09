"""HTTP request handler and route table."""

from __future__ import annotations

import sys
import traceback
from collections.abc import Callable
from http import HTTPStatus

from ..api import auth, embed, health, sync
from ..api.protocol import RequestHandler as HandlerProtocol
from ..api.validation import BadRequest
from ..auth import AuthError, InvalidCredentials, InvalidPairingCode, Unauthorized
from .base import BaseHandler

Route = Callable[[HandlerProtocol], None]

GET_ROUTES: dict[str, Route] = {
    "/health": health.health,
    "/auth/me": auth.me,
    "/auth/devices": auth.list_devices,
    "/sync/pull": sync.pull,
    "/sync/status": sync.status,
}

POST_ROUTES: dict[str, Route] = {
    "/auth/login": auth.login,
    "/auth/pair": auth.pair,
    "/auth/refresh": auth.refresh,
    "/auth/logout": auth.logout,
    "/auth/pairing-code": auth.pairing_code,
    "/embed": embed.embed,
    "/embed/text": embed.embed_text,
    "/sync/push": sync.push,
}

DELETE_ROUTES: dict[str, Route] = {
    "/sync": sync.wipe,
}


class EmbedHandler(BaseHandler):
    """Dispatch requests to the route modules and translate exceptions."""

    def do_GET(self) -> None:
        self._dispatch("GET", GET_ROUTES)

    def do_POST(self) -> None:
        self._dispatch("POST", POST_ROUTES)

    def do_DELETE(self) -> None:
        self._dispatch("DELETE", DELETE_ROUTES)

    def _dispatch(self, method: str, routes: dict[str, Route]) -> None:
        try:
            path = self.route_path()
            route = routes.get(path)
            if route is not None:
                route(self)
                return
            if method == "DELETE" and path.startswith("/auth/devices/"):
                auth.revoke_device(self, path)
                return
            self.send_error_json(HTTPStatus.NOT_FOUND, "not found")
        except Unauthorized:
            self.send_unauthorized()
        except (InvalidCredentials, InvalidPairingCode):
            self.send_error_json(HTTPStatus.UNAUTHORIZED, "invalid credentials")
        except AuthError:
            self.send_error_json(HTTPStatus.UNAUTHORIZED, "unauthorized")
        except BadRequest as exc:
            self.send_error_json(HTTPStatus.BAD_REQUEST, str(exc))
        except Exception:  # noqa: BLE001 - last-resort HTTP error boundary
            traceback.print_exc(file=sys.stderr)
            self.send_error_json(HTTPStatus.INTERNAL_SERVER_ERROR, "internal error")
