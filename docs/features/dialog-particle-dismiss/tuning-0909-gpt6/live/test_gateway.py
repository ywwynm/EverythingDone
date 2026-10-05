"""用两个独立后台检验固定入口切换；不占用用户的 GPU 或预览会话。"""
from contextlib import contextmanager
from http.client import HTTPConnection
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import tempfile
import threading
import unittest

from preview_gateway import make_server, publish, read_release


@contextmanager
def running(server):
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield server
    finally:
        server.shutdown()
        server.server_close()
        thread.join(timeout=2)


class Backend(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_GET(self):
        code = 200
        headers = {'Content-Type': 'application/json'}
        if self.path == '/api/scenes':
            body = json.dumps([dict(name='scene', models=['material', 'baseline'])]).encode()
        elif self.path == '/api/status':
            body = b'{"status":"ready"}'
        elif self.path == '/review/reference-limits.json':
            body = b'{}'
        elif self.path == '/review/material/index.html':
            body = self.server.label.encode()
        elif self.path == '/review/clip.mp4':
            self.server.range = self.headers.get('Range')
            code, body = 206, b'2345'
            headers.update({'Content-Type': 'video/mp4', 'Content-Range': 'bytes 2-5/10', 'Accept-Ranges': 'bytes'})
        elif self.path.startswith('/api/frame'):
            body = self.server.label.encode()
            headers.update({'Content-Type': 'image/jpeg', 'X-Frame-Id': '17', 'X-Request-Id': '9',
                            'X-Scene': 'scene', 'X-Progress': '0.5'})
        else:
            code, body = 404, b''
        self.send_response(code)
        for key, value in headers.items():
            self.send_header(key, value)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self):
        self.server.commands.append(self.rfile.read(int(self.headers['Content-Length'])))
        body = b'{"request_id":9}'
        self.send_response(200)
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)


class GatewayTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.manifest = Path(self.temp.name) / 'latest.json'
        self.backends = []
        for label in ('version-A', 'version-B'):
            server = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
            server.label, server.commands = label, []
            self.enterContext(running(server))
            self.backends.append(server)
        self.release = self.publish(0)
        self.gateway = self.enterContext(running(make_server(0, self.manifest)))

    def publish(self, index):
        return publish(self.backends[index].server_port, 'material', f'版本 {index}', '未验收',
                       '/review/material/index.html', self.manifest)

    def request(self, path, method='GET', body=None, headers=None):
        connection = HTTPConnection('127.0.0.1', self.gateway.server_port, timeout=3)
        try:
            connection.request(method, path, body=body, headers=headers or {})
            response = connection.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            connection.close()

    def test_fixed_entry_and_current_review(self):
        code, headers, _ = self.request('/')
        self.assertEqual((code, headers['Location']), (302, '/latest.html'))
        code, headers, body = self.request('/latest.html')
        self.assertEqual(code, 200)
        self.assertIn('最新预览'.encode(), body)
        self.assertEqual(headers['Cache-Control'], 'no-store')
        self.assertEqual(self.request('/review/latest.html')[1]['Location'], '/review/material/index.html')

    def test_command_and_frame_identity_preserved(self):
        command = b'{"kind":"seek","scene":"scene","model":"material","seed":42,"progress":0.5}'
        self.assertEqual(self.request('/api/cmd', 'POST', command)[0], 200)
        self.assertEqual(self.backends[0].commands, [command])
        code, headers, body = self.request('/api/frame?after=1')
        self.assertEqual((code, body, headers['X-Frame-Id'], headers['X-Request-Id']), (200, b'version-A', '17', '9'))
        self.assertEqual(headers['X-Preview-Revision'], self.release['revision'])

    def test_video_seek_keeps_byte_range(self):
        code, headers, body = self.request('/review/clip.mp4', headers={'Range': 'bytes=2-5'})
        self.assertEqual((code, body, headers['Content-Range']), (206, b'2345', 'bytes 2-5/10'))
        self.assertEqual(self.backends[0].range, 'bytes=2-5')

    def test_publish_switches_same_url_and_rejects_stale_command(self):
        next_release = self.publish(1)
        code, headers, body = self.request('/api/frame')
        self.assertEqual((code, body, headers['X-Preview-Revision']), (200, b'version-B', next_release['revision']))
        self.assertEqual(json.loads(self.request('/api/release')[2])['revision'], next_release['revision'])
        self.assertEqual(self.request('/api/cmd', 'POST', b'{}', {'X-Preview-Revision': self.release['revision']})[0], 409)
        self.assertEqual(self.backends[1].commands, [])

    def test_invalid_publication_preserves_last_working_release(self):
        before = self.manifest.read_bytes()
        with self.assertRaisesRegex(RuntimeError, '未提供模型'):
            publish(self.backends[1].server_port, 'unknown', 'test', 'test', '/review/material/index.html', self.manifest)
        self.assertEqual(self.manifest.read_bytes(), before)

    def test_unavailable_backend_is_explicit_without_old_fallback(self):
        unused = ThreadingHTTPServer(('127.0.0.1', 0), Backend)
        port = unused.server_port
        unused.server_close()
        release = read_release(self.manifest)
        release['backend_port'] = port
        self.manifest.write_text(json.dumps(release), 'utf-8')
        code, _, body = self.request('/api/frame')
        self.assertEqual(code, 503)
        self.assertIn('最新预览暂时不可用', json.loads(body)['error'])


if __name__ == '__main__':
    unittest.main(verbosity=2)
