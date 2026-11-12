"""LAN history API. Binds one host. The phone is the only HTTP writer."""

from __future__ import annotations

import argparse
import hmac
import json
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from store import METRICS, RANGES, VERSION, History

MAX_BODY = 1_000_000


class HistoryApp:
    def __init__(self, history: History, token: str) -> None:
        self.history = history
        self.token = token

    def handle(self, method: str, path: str, body: bytes, authorization: str, now_ms: int | None = None) -> tuple[int, dict]:
        if not self.token or not hmac.compare_digest(_bearer(authorization), self.token):
            return 401, {"error": "unauthorized"}
        now = int(time.time() * 1000) if now_ms is None else now_ms
        route, _, query = path.partition("?")
        try:
            if method == "GET" and route == "/health":
                return 200, self.history.health()
            if method == "GET" and route == "/v1/bound":
                return 200, {"bound": self.history.bound()}
            if method == "GET" and route == "/v1/latest":
                return 200, {"devices": self.history.latest()}
            if method == "GET" and route == "/v1/series":
                return self._series(query, now)
            if method == "POST" and route == "/v1/readings":
                return self._post(body)
        except (KeyError, ValueError, TypeError, json.JSONDecodeError):
            return 400, {"error": "bad request"}
        return 404, {"error": "not found"}

    def _series(self, query: str, now_ms: int) -> tuple[int, dict]:
        params = _query(query)
        range_name = params.get("range", "")
        metric = params.get("metric", "")
        if range_name not in RANGES:
            return 400, {"error": "bad range"}
        if not _metric_ok(metric):
            return 400, {"error": "bad metric"}
        address = params.get("address", "")
        label = params.get("label", "")
        if not address or not label:
            return 400, {"error": "bad request"}
        return 200, self.history.series(address, label, metric, range_name, now_ms)

    def _post(self, body: bytes) -> tuple[int, dict]:
        if len(body) > MAX_BODY:
            return 400, {"error": "too large"}
        payload = json.loads(body.decode("utf-8") or "{}")
        readings = payload.get("readings")
        if not isinstance(readings, list):
            return 400, {"error": "bad request"}
        phone_rows = []
        for reading in readings:
            if not isinstance(reading, dict):
                continue
            if reading.get("source") not in (None, "phone"):
                continue
            phone_rows.append(reading)
        result = self.history.ingest(phone_rows, "phone")
        return 200, result


def serve(history: History, token: str, host: str, port: int) -> ThreadingHTTPServer:
    app = HistoryApp(history, token)
    roller = threading.Thread(target=_rollup_loop, args=(history,), daemon=True)
    roller.start()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self) -> None:  # noqa: N802
            self._reply("GET")

        def do_POST(self) -> None:  # noqa: N802
            self._reply("POST")

        def _reply(self, method: str) -> None:
            length = int(self.headers.get("Content-Length", "0") or 0)
            if length > MAX_BODY:
                body = b""
                status, payload = 400, {"error": "too large"}
            else:
                body = self.rfile.read(length) if length else b""
                status, payload = app.handle(
                    method,
                    self.path,
                    body,
                    self.headers.get("Authorization", ""),
                )
            raw = json.dumps(payload).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(raw)))
            self.end_headers()
            self.wfile.write(raw)

        def log_message(self, fmt: str, *args) -> None:
            return

    httpd = ThreadingHTTPServer((host, port), Handler)
    return httpd


def _rollup_loop(history: History) -> None:
    while True:
        time.sleep(15 * 60)
        try:
            history.rollup(int(time.time() * 1000))
        except sqlite3.Error:
            continue


def _bearer(header: str) -> str:
    text = header.strip()
    if text.lower().startswith("bearer "):
        return text[7:].strip()
    return ""


def _query(query: str) -> dict[str, str]:
    out: dict[str, str] = {}
    if not query:
        return out
    for part in query.split("&"):
        if not part:
            continue
        key, _, value = part.partition("=")
        out[_unquote(key)] = _unquote(value)
    return out


def _unquote(value: str) -> str:
    from urllib.parse import unquote
    return unquote(value.replace("+", " "))


def _metric_ok(metric: str) -> bool:
    if metric in METRICS:
        return True
    if not metric.startswith("probe_"):
        return False
    return metric[6:].isdigit()


def main() -> None:
    parser = argparse.ArgumentParser(description="Govee history server")
    parser.add_argument("--host", default="10.0.0.69")
    parser.add_argument("--port", type=int, default=8787)
    parser.add_argument("--db", default="/var/lib/govee-history/history.db")
    parser.add_argument("--token-file", default="/etc/govee-history/token")
    parser.add_argument("--rollup-only", action="store_true")
    args = parser.parse_args()
    history = History(args.db)
    if args.rollup_only:
        history.rollup(int(time.time() * 1000))
        return
    token = open(args.token_file, encoding="utf-8").read().strip()
    httpd = serve(history, token, args.host, args.port)
    print(f"govee-history {VERSION} on {args.host}:{args.port}", flush=True)
    httpd.serve_forever()


if __name__ == "__main__":
    main()
