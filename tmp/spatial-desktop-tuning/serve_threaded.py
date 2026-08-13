#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""查看器的静态服务：**必须多线程**。

`python -m http.server` 是单线程串行的：查看器一次 loadScene 会并发拉十几个资源
（depth_z.f32 / hidden_* / assets/*），只要有一个连接被 keep-alive 占住，后面的请求
全部排队，表现就是"页面卡死、渲染队列不再产帧"——2026-08-12 批量出帧时实际发生过，
排查了两轮才定位到不是页面的问题。
"""
import functools
import http.server
import socketserver
import sys
from pathlib import Path


class Handler(http.server.SimpleHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def end_headers(self):
        self.send_header("Cache-Control", "no-store")
        super().end_headers()

    def log_message(self, *a):
        pass


if __name__ == "__main__":
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8642
    root = sys.argv[2] if len(sys.argv) > 2 else str(Path(__file__).parent)
    with socketserver.ThreadingTCPServer(
            ("127.0.0.1", port), functools.partial(Handler, directory=root)) as httpd:
        httpd.daemon_threads = True
        httpd.allow_reuse_address = True
        print(f"serving {root} on http://127.0.0.1:{port}", flush=True)
        httpd.serve_forever()
