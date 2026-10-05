"""桌面实时预览：python live/live_server.py 8652。

与预渲染调用同一模型；首帧无需构建 GPU。有限 JPEG 响应携带请求编号，
避免静止 MJPEG 等待下一段数据、异步旧帧覆盖新场景。
"""
import json
import math
import mimetypes
from pathlib import Path
import secrets
import sys
import threading
import time
import traceback
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, unquote, urlsplit
import cv2
import moderngl
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path[:0] = [str(ROOT), str(ROOT / 'analysis')]
from desktop_inputs import SCENES, REFS, META, MODELS, GAP, scene_info, animation_inputs, make_renderer
from match_refs import RefFrames

REVIEW = ROOT / 'analysis/match-cache/takeover-20261004'


class State:
    def __init__(self):
        self.cond = threading.Condition()
        self.pending = None
        self.request_id = 0
        self.frame_id = 0
        self.jpeg = b''
        self.info = {}

    def submit(self, command):
        with self.cond:
            self.request_id += 1
            self.pending = dict(command, request_id=self.request_id)
            self.cond.notify_all()
            return self.request_id


STATE = State()
REF_CACHE = {}
STILL_CACHE = {}


def compose(name, frame, progress):
    if name not in REFS:
        return frame
    if name not in REF_CACHE:
        REF_CACHE[name] = RefFrames(name)
    _, ref = REF_CACHE[name].nearest(progress)
    h, w = frame.shape[:2]
    out = np.full((h, 2*w+GAP, 3), 16, np.uint8)
    out[:, :w] = ref[..., :3]
    out[:, w+GAP:] = frame
    return out


def still(name):
    if name not in STILL_CACHE:
        m = META[name]
        d = ROOT / 'assets' / name
        # 与原生渲染器一致：有修过的背景／卡片图（去掉残留颗粒、触摸指示点）就用修过的
        bg = Image.open(d / 'background-clean.png' if (d / 'background-clean.png').exists() else d / 'background.png').convert('RGB')
        a = np.array(bg, dtype='float32') * (1-m['dim_alpha'])
        bg = Image.fromarray(np.rint(a).clip(0, 255).astype('uint8')).convert('RGBA')
        fg = Image.open(d / 'foreground-clean.png' if (d / 'foreground-clean.png').exists() else d / 'foreground.png').convert('RGBA')
        bg.alpha_composite(fg, dest=tuple(m['rect'][:2]))
        STILL_CACHE[name] = compose(name, np.asarray(bg.convert('RGB')), 0.)
    return STILL_CACHE[name]


def publish(frame, info, request_id):
    ok, encoded = cv2.imencode('.jpg', cv2.cvtColor(frame, cv2.COLOR_RGB2BGR), [cv2.IMWRITE_JPEG_QUALITY, 94])
    if not ok:
        raise RuntimeError('JPEG 编码失败')
    with STATE.cond:
        if request_id != STATE.request_id:
            return
        STATE.frame_id += 1
        STATE.jpeg = encoded.tobytes()
        STATE.info = dict(info, request_id=request_id, frame_id=STATE.frame_id)
        STATE.cond.notify_all()


def render_loop():
    ctx = renderer = inputs = None
    name, model, progress, playing, speed = 'ironman', 'harmony-core', 0., False, 1.
    request_id, info, last_tick = 0, {}, time.perf_counter()
    try:
        while True:
            with STATE.cond:
                if STATE.pending is None and not playing:
                    STATE.cond.wait(timeout=.5)
                command, STATE.pending = STATE.pending, None
            try:
                if command:
                    request_id = command['request_id']
                    kind = command['kind']
                    selected, next_model = command['scene'], command['model']
                    speed = command.get('speed', speed)
                    if kind == 'scene':
                        name, model, progress, playing, inputs = selected, next_model, 0., False, None
                        info = dict(scene=name, model=model, model_title=MODELS[model][0], status='就绪', progress=0., playing=False)
                        publish(still(name), info, request_id)
                        if renderer is not None:
                            renderer.close()
                            renderer = None
                        continue
                    rebuild = renderer is None or name != selected or model != next_model
                    name, model = selected, next_model
                    if kind in ('ref', 'back', 'tap') or inputs is None or inputs['scene'] != name:
                        input_kind = kind if kind in ('ref', 'back', 'tap') else ('ref' if name in REFS else 'back')
                        seed = command.get('seed')
                        if seed is None:
                            seed = META[name]['seed'] if input_kind == 'ref' else secrets.randbits(31)
                        tap = [command['x'], command['y']] if kind == 'tap' else None
                        inputs = animation_inputs(name, input_kind, seed, tap)
                        rebuild = True
                    if rebuild:
                        if renderer is not None:
                            renderer.close()
                            renderer = None
                        if ctx is None:
                            ctx = moderngl.create_standalone_context(require=430)
                        started = time.perf_counter()
                        renderer = make_renderer(inputs, model, ctx)
                        info = dict(scene=name, model=model, model_title=MODELS[model][0], seed=inputs['seed'],
                                    direction=round(inputs['direction'], 1), touch_gap=inputs['touch_gap'],
                                    source=inputs['source'] if model in ('candidate','material','harmony-origins') else '正式共同释放规则',
                                    origins=inputs['origins'] if model in ('candidate','material','harmony-origins') else [], tap=inputs['tap'],
                                    build_ms=round((time.perf_counter()-started)*1000), particles=renderer.n)
                        if model in ('harmony-core','harmony-origins','harmony-native'):
                            core = renderer.describe()
                            info.update(source=inputs['source'] if model=='harmony-origins' else core['source'],
                                        origins=core.get('origins',[]), core=core,
                                        direction=round(math.degrees(math.atan2(core['wind'][1],core['wind'][0]))%360,1))
                    if kind == 'seek':
                        progress, playing = command['progress'], False
                    elif kind == 'pause':
                        playing = False
                    elif kind == 'resume':
                        progress = 0. if progress >= 1. else progress
                        playing = True
                    else:
                        progress, playing = 0., True
                        renderer.reset()
                    last_tick = time.perf_counter()
                    info.update(progress=progress, playing=playing, speed=speed, status='播放' if playing else '暂停')
                    frame = renderer.render(progress)
                    if model in ('harmony-core','harmony-origins','harmony-native'):
                        info.update(renderer.diagnostics())
                    publish(compose(name, frame, progress), info, request_id)
                elif playing and renderer is not None:
                    now = time.perf_counter()
                    delta, last_tick = now-last_tick, now
                    progress = min(1., progress+delta*speed/getattr(renderer,'playback_duration',1.))
                    playing = progress < 1.
                    frame = compose(name, renderer.render(progress), progress)
                    if model in ('harmony-core','harmony-origins','harmony-native'):
                        info.update(renderer.diagnostics())
                    info.update(progress=round(progress, 4), playing=playing, speed=speed,
                                fps=round(1/max(delta, 1e-6), 1), status='播放' if playing else '结束')
                    publish(frame, info, request_id)
                    with STATE.cond:
                        if STATE.pending is None:
                            STATE.cond.wait(timeout=max(0., 1/60-(time.perf_counter()-now)))
            except Exception as error:
                traceback.print_exc()
                playing = False
                if renderer is not None:
                    renderer.close()
                    renderer = None
                info.update(scene=name, model=model, status=f'错误：{error}', playing=False)
                publish(still(name), info, request_id)
    finally:
        if renderer is not None:
            renderer.close()
        if ctx is not None:
            ctx.release()


def validate(command):
    if not isinstance(command, dict) or command.get('kind') not in ('scene', 'ref', 'back', 'tap', 'replay', 'seek', 'pause', 'resume'):
        raise ValueError('未知命令')
    if command.get('scene') not in SCENES or command.get('model') not in MODELS:
        raise ValueError('未知场景或模型')
    for key, lo, hi in [('speed', .1, 2.), ('progress', 0., 1.), ('x', -10000, 10000), ('y', -10000, 10000)]:
        if key in command:
            value = float(command[key])
            if not math.isfinite(value) or not lo <= value <= hi:
                raise ValueError(f'{key} 超出范围')
            command[key] = value
    if command.get('seed') is not None:
        seed = command['seed']
        if isinstance(seed, bool) or not isinstance(seed, int) or not 0 <= seed <= 0x7fffffff:
            raise ValueError('随机种子须为 0–2147483647 的整数')
    if command['kind'] == 'seek' and 'progress' not in command:
        raise ValueError('缺少进度')
    if command['kind'] == 'tap':
        x, y = command['x'], command['y']
        info = scene_info(command['scene'])
        l, t, r, b = info['touch_rect']
        dl, dt, dr, db = info['rect']
        if not (l <= x <= r and t <= y <= b) or (dl <= x <= dr and dt <= y <= db):
            raise ValueError('触点须在弹窗外的可点击区域内')
    return command


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def send(self, code, body=b'', content_type='application/json; charset=utf-8', headers=None):
        self.send_response(code)
        self.send_header('Content-Type', content_type)
        self.send_header('Content-Length', str(len(body)))
        self.send_header('Cache-Control', 'no-store')
        for key, value in (headers or {}).items():
            self.send_header(key, str(value))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError, ConnectionAbortedError):
            pass

    def do_GET(self):
        url = urlsplit(self.path)
        path = unquote(url.path)
        if path in ('/', '/index.html'):
            return self.send(200, (HERE/'live.html').read_bytes(), 'text/html; charset=utf-8')
        if path == '/api/scenes':
            return self.send(200, json.dumps([dict(scene_info(n), models=list(MODELS),
                                                 model_titles={k:v[0] for k,v in MODELS.items()}) for n in SCENES], ensure_ascii=False).encode())
        if path == '/api/status':
            with STATE.cond:
                info = dict(STATE.info)
            return self.send(200, json.dumps(info, ensure_ascii=False).encode())
        if path == '/api/frame':
            try:
                after = int(parse_qs(url.query).get('after', ['-1'])[0])
            except ValueError:
                return self.send(400, b'bad frame id', 'text/plain')
            with STATE.cond:
                STATE.cond.wait_for(lambda: STATE.frame_id > after and bool(STATE.jpeg), timeout=2.)
                jpeg, info = STATE.jpeg, dict(STATE.info)
            if not jpeg or info['frame_id'] <= after:
                return self.send(204)
            return self.send(200, jpeg, 'image/jpeg', {'X-Frame-Id': info['frame_id'], 'X-Request-Id': info['request_id'],
                                                     'X-Scene': info['scene'], 'X-Progress': info.get('progress', 0)})
        if path == '/review' or path.startswith('/review/'):
            relative = path[len('/review/'):].strip('/') if path.startswith('/review/') else ''
            target = (REVIEW/(relative or 'index.html')).resolve()
            if not target.is_relative_to(REVIEW.resolve()) or not target.is_file():
                return self.send(404, b'not found', 'text/plain')
            size = target.stat().st_size
            start, end, code = 0, size-1, 200
            interval = self.headers.get('Range')
            if interval:
                try:
                    units, extent = interval.split('=', 1)
                    a, b = extent.split('-', 1)
                    if units != 'bytes' or ',' in extent:
                        raise ValueError()
                    start, end = (int(a), min(size-1, int(b)) if b else size-1) if a else (max(0, size-int(b)), size-1)
                    if not 0 <= start <= end < size:
                        raise ValueError()
                    code = 206
                except ValueError:
                    return self.send(416, headers={'Content-Range': f'bytes */{size}'})
            with target.open('rb') as stream:
                stream.seek(start)
                data = stream.read(end-start+1)
            headers = {'Accept-Ranges': 'bytes'}
            if code == 206:
                headers['Content-Range'] = f'bytes {start}-{end}/{size}'
            return self.send(code, data, mimetypes.guess_type(target)[0] or 'application/octet-stream', headers)
        return self.send(404, b'not found', 'text/plain')

    def do_POST(self):
        if self.path != '/api/cmd':
            return self.send(404)
        try:
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 4096:
                raise ValueError('命令长度无效')
            command = validate(json.loads(self.rfile.read(length)))
            request_id = STATE.submit(command)
            return self.send(200, json.dumps({'request_id': request_id}).encode())
        except (ValueError, TypeError, KeyError, OverflowError) as error:
            return self.send(400, json.dumps({'error': str(error)}, ensure_ascii=False).encode())


def main():
    from export_reference_inputs import main as export_reference_inputs
    export_reference_inputs()
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8652
    server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
    server.daemon_threads = True
    threading.Thread(target=render_loop, daemon=True).start()
    STATE.submit(dict(kind='scene', scene='ironman', model='harmony-core'))
    print(f'实时预览：http://localhost:{port}/', flush=True)
    server.serve_forever()


if __name__ == '__main__':
    main()
