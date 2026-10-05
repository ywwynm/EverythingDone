"""固定预览入口；发布清单原子切换后台，页面地址始终不变。"""
import argparse
from datetime import datetime
from http.client import HTTPConnection, HTTPException
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
from urllib.parse import urlsplit
import uuid

HERE = Path(__file__).resolve().parent
MANIFEST = HERE / 'latest.json'
PORT = 8666


def read_release(path=MANIFEST):
    release = json.loads(path.read_text('utf-8'))
    if not isinstance(release['backend_port'], int) or not 1024 <= release['backend_port'] <= 65535:
        raise ValueError('无效渲染服务端口')
    if release['backend_port'] == PORT:
        raise ValueError('渲染服务不能指向固定入口本身')
    for key in ('revision', 'model', 'title', 'status', 'updated_at'):
        if not isinstance(release[key], str) or not release[key]:
            raise ValueError(f'缺少发布字段：{key}')
    if not release['review_path'].startswith('/review/'):
        raise ValueError('预渲染对照必须属于本功能')
    return release


def backend_get(port, path):
    connection = HTTPConnection('127.0.0.1', port, timeout=8)
    try:
        connection.request('GET', path)
        response = connection.getresponse()
        body = response.read()
        if response.status != 200:
            raise RuntimeError(f'发布检查失败：{path} HTTP {response.status}')
        return body
    finally:
        connection.close()


def publish(port, model, title, status, review_path, manifest=MANIFEST):
    release = dict(backend_port=port, model=model, title=title, status=status,
                   review_path=review_path, revision=uuid.uuid4().hex,
                   updated_at=datetime.now().astimezone().isoformat(timespec='seconds'))
    if port == PORT or not 1024 <= port <= 65535 or not review_path.startswith('/review/'):
        raise ValueError('无效发布目标')
    scenes = json.loads(backend_get(port, '/api/scenes'))
    if not scenes or not all(model in scene.get('models', []) for scene in scenes):
        raise RuntimeError(f'后台未提供模型 {model}')
    current = json.loads(backend_get(port, '/api/status'))
    if str(current.get('status', '')).startswith('错误'):
        raise RuntimeError(f'后台渲染异常：{current["status"]}')
    backend_get(port, '/review/reference-limits.json')
    backend_get(port, review_path)
    temporary = manifest.with_name(manifest.name + '.' + uuid.uuid4().hex + '.tmp')
    try:
        temporary.write_text(json.dumps(release, ensure_ascii=False, indent=2) + '\n', 'utf-8')
        read_release(temporary)
        temporary.replace(manifest)
    finally:
        temporary.unlink(missing_ok=True)
    return release


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, code, body=b'', content_type='application/json; charset=utf-8', headers=None):
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        for key, value in (headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionError):
            pass

    def dispatch(self):
        path = urlsplit(self.path).path
        if self.command == 'GET' and path in ('/', '/index.html'):
            return self.send(302, headers={'Location': '/latest.html'})
        if self.command == 'GET' and path == '/latest.html':
            return self.send(200, (HERE / 'live.html').read_bytes(), 'text/html; charset=utf-8')
        if self.command == 'GET' and path == '/favicon.ico':
            return self.send(204)
        release = read_release(self.server.manifest)
        revision = release['revision']
        if self.command == 'GET' and path == '/api/release':
            public = {key: value for key, value in release.items() if key != 'backend_port'}
            return self.send(200, json.dumps(public, ensure_ascii=False).encode('utf-8'))
        if self.command == 'GET' and path in ('/review/latest', '/review/latest.html'):
            return self.send(302, headers={'Location': release['review_path']})
        allowed = (self.command == 'GET' and (path in ('/api/scenes', '/api/status', '/api/frame', '/review')
                   or path.startswith('/review/'))) or (self.command == 'POST' and path == '/api/cmd')
        if not allowed:
            return self.send(404)
        body = None
        headers = {}
        if self.command == 'POST':
            if self.headers.get('X-Preview-Revision', revision) != revision:
                return self.send(409, b'{"error":"preview version changed"}')
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 4096:
                return self.send(400, b'{"error":"invalid command length"}')
            body = self.rfile.read(length)
            headers['Content-Type'] = 'application/json'
        if self.headers.get('Range'):
            headers['Range'] = self.headers['Range']
        if release['backend_port'] == self.server.server_port:
            raise ValueError('渲染服务不能指向固定入口本身')
        connection = HTTPConnection('127.0.0.1', release['backend_port'], timeout=8)
        try:
            connection.request(self.command, self.path, body=body, headers=headers)
            response = connection.getresponse()
            payload = response.read()
            forwarded = {key: value for key, value in response.getheaders()
                         if key.lower() in ('accept-ranges', 'content-range', 'x-frame-id',
                                            'x-request-id', 'x-scene', 'x-progress')}
            forwarded['X-Preview-Revision'] = revision
            return self.send(response.status, payload, response.getheader('Content-Type', 'application/octet-stream'), forwarded)
        finally:
            connection.close()

    def handle_request(self):
        try:
            self.dispatch()
        except (OSError, ValueError, KeyError, RuntimeError, HTTPException) as error:
            self.send(503, json.dumps({'error': f'最新预览暂时不可用：{error}'}, ensure_ascii=False).encode('utf-8'))

    do_GET = handle_request
    do_POST = handle_request


def make_server(port=PORT, manifest=MANIFEST):
    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.daemon_threads = True
    server.manifest = manifest
    return server


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publish-port', type=int, help='核对后台后发布到固定入口')
    parser.add_argument('--model')
    parser.add_argument('--title')
    parser.add_argument('--status')
    parser.add_argument('--review')
    args = parser.parse_args()
    if args.publish_port is not None:
        if not all((args.model, args.title, args.status, args.review)):
            parser.error('发布须给出 --model、--title、--status、--review')
        release = publish(args.publish_port, args.model, args.title, args.status, args.review)
        print(f'已发布：{release["title"]}；http://localhost:{PORT}/latest.html', flush=True)
    else:
        with make_server() as server:
            print(f'固定预览：http://localhost:{PORT}/latest.html', flush=True)
            server.serve_forever()


if __name__ == '__main__':
    main()
