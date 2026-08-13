#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""真帧导出接收端：查看器把 canvas 像素 POST 过来落盘，绕开被收起的浏览器面板。"""
import http.server
import re
from pathlib import Path
from urllib.parse import parse_qs, urlparse

BASE = Path(__file__).parent / "qa"
OUT = BASE / "viewer-dump.png"


class Handler(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        data = self.rfile.read(length)
        query = parse_qs(urlparse(self.path).query)
        name = query.get("name", [""])[0]
        if name and re.fullmatch(r"[A-Za-z0-9._/-]+\.png", name) and ".." not in name:
            target = BASE / "orbit" / name
        else:
            target = OUT
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(data)
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(b"ok")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "POST")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def log_message(self, *args):
        pass


http.server.ThreadingHTTPServer(("127.0.0.1", 8643), Handler).serve_forever()
