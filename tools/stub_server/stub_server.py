#!/usr/bin/env python3
"""泊車副駕 本機 stub server（Step 3.5／SC-018）。

只用 Python 標準庫。用 docs/api/ 嘅範例 JSON 回應 App 嘅 6 個 POST 端點，
方便 debug 版（HKP_USE_REAL_API=true）喺模擬器或真機（adb reverse）試真 HTTP。
唔係雲端伺服器，唔做打分／鎖／Firestore。

用法：
  python tools/stub_server/stub_server.py --port 8787 --key dev-local-key [--scenario ok]

情境（--scenario，或者行緊時 GET /__scenario?name=xxx 切換）：
  ok          全部正常（預設）
  lock-full   commit 回 409 LOCK_FULL（照播「呢度滿咗」）
  trip-409    trip-report 回 409（App 靜默）
  trip-404    trip-report 回 404（App 靜默）
  rate-limit  recommend 回 429 + Retry-After: 3
  error-500   全部回 503（App 分級重試後 GREY）
  slow        全部延遲 4 秒先回（超過 2.5s 逾時）
  bad-json    recommend 回 200 但 body 唔係 JSON（App 當網絡失敗）
"""

import argparse
import json
import os
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
API_DIR = os.path.join(ROOT, "docs", "api")

ROUTES = {
    "/v1/recommend-and-route": "recommend-and-route",
    "/v1/session/commit": "session-commit",
    "/v1/session/release": "session-release",
    "/v1/sync/outbox": "sync-outbox",
    "/v1/feedback/crowd-report": "crowd-report",
    "/v1/feedback/trip-report": "trip-report",
}
SCENARIOS = ["ok", "lock-full", "trip-409", "trip-404", "rate-limit", "error-500", "slow", "bad-json"]

STATE = {"scenario": "ok", "key": "dev-local-key"}


def load_example(name):
    with open(os.path.join(API_DIR, name), "rb") as fh:
        return fh.read()


class Handler(BaseHTTPRequestHandler):
    server_version = "HkpStub/0.1"

    def log_message(self, fmt, *args):  # 唔印 key、唔印 body
        sys.stderr.write("[stub] %s %s\n" % (self.address_string(), fmt % args))

    def _send(self, code, body=b"", headers=None):
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        for k, v in (headers or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if body:
            self.wfile.write(body)

    def do_GET(self):
        url = urlparse(self.path)
        if url.path == "/__scenario":
            name = parse_qs(url.query).get("name", [""])[0]
            if name in SCENARIOS:
                STATE["scenario"] = name
            self._send(200, json.dumps({"scenario": STATE["scenario"], "choices": SCENARIOS}).encode())
            return
        self._send(404, b'{"status":"NOT_FOUND"}')

    def do_POST(self):
        path = urlparse(self.path).path
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""
        if self.headers.get("X-Internal-API-Key", "") != STATE["key"]:
            self._send(401, b'{"status":"UNAUTHORIZED"}')
            return
        base = ROUTES.get(path)
        if base is None:
            self._send(404, b'{"status":"NOT_FOUND"}')
            return
        try:
            json.loads(raw.decode("utf-8") or "{}")
        except ValueError:
            self._send(400, b'{"status":"BAD_REQUEST"}')
            return
        sc = STATE["scenario"]
        if sc == "slow":
            time.sleep(4)
        if sc == "error-500":
            self._send(503, b'{"status":"UNAVAILABLE"}')
            return
        if sc == "rate-limit" and base == "recommend-and-route":
            self._send(429, load_example("recommend-and-route.response.429.json"), {"Retry-After": "3"})
            return
        if sc == "bad-json" and base == "recommend-and-route":
            self._send(200, b"not json")
            return
        if sc == "lock-full" and base == "session-commit":
            self._send(409, load_example("session-commit.response.409.json"))
            return
        if sc == "trip-409" and base == "trip-report":
            self._send(409, load_example("trip-report.response.409.json"))
            return
        if sc == "trip-404" and base == "trip-report":
            self._send(404, b'{"status":"NOT_FOUND"}')
            return
        self._send(200, load_example(base + ".response.200.json"))


def main():
    ap = argparse.ArgumentParser(description="泊車副駕 本機 stub server")
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=8787)
    ap.add_argument("--key", default=os.environ.get("HKP_API_KEY", "dev-local-key"))
    ap.add_argument("--scenario", default="ok", choices=SCENARIOS)
    args = ap.parse_args()
    STATE["key"] = args.key
    STATE["scenario"] = args.scenario
    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    print("stub server http://%s:%d scenario=%s（Ctrl+C 停）" % (args.host, args.port, args.scenario))
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
