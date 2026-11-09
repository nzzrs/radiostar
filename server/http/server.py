"""Threading HTTP server with a hard cap on concurrent handlers."""

from __future__ import annotations

import socket
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

DEFAULT_MAX_THREADS = 16


class BoundedThreadingHTTPServer(ThreadingHTTPServer):
    """ThreadingHTTPServer with a hard cap on concurrent handler threads."""

    daemon_threads = True
    allow_reuse_address = True

    def __init__(
        self,
        server_address: tuple[str, int],
        request_handler: type[BaseHTTPRequestHandler],
        max_threads: int = DEFAULT_MAX_THREADS,
    ) -> None:
        self._thread_slots = threading.BoundedSemaphore(max(1, max_threads))
        self.request_queue_size = max(16, max_threads * 2)
        super().__init__(server_address, request_handler)

    def process_request(
        self, request: socket.socket, client_address: tuple[str, int]
    ) -> None:
        if not self._thread_slots.acquire(timeout=5):
            try:
                request.sendall(
                    b"HTTP/1.1 503 Service Unavailable\r\n"
                    b"content-length: 0\r\nconnection: close\r\n\r\n"
                )
            finally:
                request.close()
            return
        try:
            super().process_request(request, client_address)
        except BaseException:
            self._thread_slots.release()
            raise

    def process_request_thread(
        self, request: socket.socket, client_address: tuple[str, int]
    ) -> None:
        try:
            super().process_request_thread(request, client_address)
        finally:
            self._thread_slots.release()
