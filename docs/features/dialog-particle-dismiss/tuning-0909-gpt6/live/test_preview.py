"""独立端口的 HTTP / GPU 回归，不操作用户正在浏览的实时会话。

运行：python -X utf8 live/test_preview.py
"""
import hashlib
import json
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import time
import unittest
from urllib.error import HTTPError
from urllib.request import Request, urlopen
import cv2
import moderngl
import numpy as np

HERE=Path(__file__).resolve().parent
ROOT=HERE.parent
sys.path[:0]=[str(ROOT), str(HERE)]
from desktop_inputs import SCENES, META, scene_info, animation_inputs, make_renderer
from desktop_candidate import sample_origins
from live_server import compose


def offline_frames(directory):
    name='reference-0926-5'
    ctx=moderngl.create_standalone_context(require=430)
    try:
        for model in ('candidate','material'):
            renderer=make_renderer(animation_inputs(name,'ref',META[name]['seed']),model,ctx)
            try:
                frame=compose(name,renderer.render(.5),.5)
                ok,jpeg=cv2.imencode('.jpg',cv2.cvtColor(frame,cv2.COLOR_RGB2BGR),[cv2.IMWRITE_JPEG_QUALITY,94])
                if not ok:
                    raise AssertionError('离线帧编码失败')
                (directory/f'{model}.jpg').write_bytes(jpeg.tobytes())
            finally:
                renderer.close()
    finally:
        ctx.release()


class PreviewTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        # 离线与实时使用先后结束的独立进程，避免测试进程保留 WGL 驱动状态。
        with tempfile.TemporaryDirectory(prefix='particle-preview-') as directory:
            offline=subprocess.run([sys.executable,'-X','utf8',str(Path(__file__).resolve()),'--offline',directory],
                                   capture_output=True,text=True,encoding='utf-8',
                                   creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
            if offline.returncode:
                raise AssertionError('离线取帧失败：\n'+offline.stdout+offline.stderr)
            cls.offline={model:(Path(directory)/f'{model}.jpg').read_bytes() for model in ('candidate','material')}
        with socket.socket() as probe:
            probe.bind(('127.0.0.1', 0))
            port=probe.getsockname()[1]
        cls.url=f'http://127.0.0.1:{port}'
        cls.log=tempfile.TemporaryFile()
        cls.process=subprocess.Popen([sys.executable, '-X', 'utf8', str(HERE/'live_server.py'), str(port)], cwd=ROOT,
                                     stdout=cls.log, stderr=cls.log, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        cls.addClassCleanup(cls.cleanup)
        for _ in range(200):
            try:
                with urlopen(cls.url+'/api/status', timeout=.5) as response:
                    if json.load(response).get('scene'):
                        return
            except OSError:
                pass
            time.sleep(.025)
        raise AssertionError('隔离预览服务没有启动')

    @classmethod
    def cleanup(cls):
        cls.process.terminate()
        cls.process.wait(timeout=10)
        cls.log.seek(0)
        log=cls.log.read().decode('utf-8', errors='replace')
        cls.log.close()
        if 'Traceback' in log:
            raise AssertionError(log)

    def post(self, kind, scene='ironman', model='candidate', **extra):
        data=json.dumps(dict(kind=kind, scene=scene, model=model, **extra)).encode()
        with urlopen(Request(self.url+'/api/cmd', data, {'Content-Type':'application/json'}), timeout=10) as response:
            return json.load(response)['request_id']

    def frame(self, request_id):
        after=-1
        for _ in range(25):
            with urlopen(self.url+f'/api/frame?after={after}', timeout=5) as response:
                data=response.read()
                if response.status==204:
                    continue
                after=int(response.headers['X-Frame-Id'])
                if int(response.headers['X-Request-Id'])>=request_id:
                    status=self.status()
                    self.assertFalse(status.get('status','').startswith('错误：'),status)
                    return response.headers, data
        self.fail('没有收到该请求的画面')

    def status(self):
        with urlopen(self.url+'/api/status', timeout=5) as response:
            return json.load(response)

    def test_scene_selection_publishes_without_playing(self):
        durations=[]
        for name in SCENES:
            started=time.perf_counter()
            request_id=self.post('scene', scene=name)
            headers,data=self.frame(request_id)
            durations.append(time.perf_counter()-started)
            self.assertEqual(headers['X-Scene'],name)
            self.assertEqual(float(headers['X-Progress']),0)
            image=cv2.imdecode(np.frombuffer(data,np.uint8),cv2.IMREAD_COLOR)
            self.assertEqual(list(image.shape[1::-1]),scene_info(name)['view'])
            self.assertFalse(self.status()['playing'])
        print(f'场景切换：中位 {np.median(durations)*1000:.1f} ms，最长 {max(durations)*1000:.1f} ms')

    def test_rapid_selection_cannot_publish_an_old_scene(self):
        for name in SCENES:
            request_id=self.post('scene',scene=name)
        headers,_=self.frame(request_id)
        self.assertEqual(headers['X-Scene'],SCENES[-1])
        self.assertEqual(int(headers['X-Request-Id']),request_id)

    def test_replay_and_rewind_are_deterministic(self):
        request_id=self.post('ref',seed=27182,speed=.1)
        self.frame(request_id)
        _,a=self.frame(self.post('seek',progress=.5))
        self.frame(self.post('seek',progress=.75))
        _,b=self.frame(self.post('seek',progress=.5))
        self.assertEqual(hashlib.sha256(a).digest(),hashlib.sha256(b).digest())
        self.frame(self.post('replay',speed=.1))
        _,c=self.frame(self.post('seek',progress=.5))
        self.assertEqual(a,c)
        self.assertEqual(self.status()['seed'],27182)

    def test_live_equals_the_same_offline_renderer(self):
        name='reference-0926-5'
        for model in ('candidate','material'):
            with self.subTest(model=model):
                self.frame(self.post('ref',scene=name,model=model,seed=META[name]['seed'],speed=.1))
                _,live=self.frame(self.post('seek',scene=name,model=model,progress=.5))
                self.assertEqual(live,self.offline[model])

    def test_model_switch_preserves_input(self):
        self.frame(self.post('ref',seed=831,speed=.1))
        self.frame(self.post('seek',progress=.43))
        original=self.status()
        self.frame(self.post('seek',model='material',progress=.43))
        material=self.status()
        self.assertEqual(material['origins'],original['origins'])
        self.assertEqual(material['seed'],original['seed'])
        self.assertEqual(material['direction'],original['direction'])
        self.assertLess(material['particles'],original['particles'])
        self.frame(self.post('seek',model='baseline',progress=.43))
        baseline=self.status()
        self.assertEqual(baseline['seed'],original['seed'])
        self.assertEqual(baseline['direction'],original['direction'])
        self.assertLess(original['particles'],baseline['particles']*.4)
        self.frame(self.post('seek',model='candidate',progress=.43))
        self.assertEqual(self.status()['origins'],original['origins'])

    def test_invalid_commands_do_not_break_render_thread(self):
        for kwargs in [dict(kind='invalid'),dict(kind='seek',progress=float('nan')),dict(kind='ref',seed=-1),dict(kind='tap',x=350,y=400)]:
            with self.assertRaises(HTTPError) as error:
                self.post(**kwargs)
            self.assertEqual(error.exception.code,400)
        self.frame(self.post('scene'))
        self.assertEqual(self.status()['status'],'就绪')

    def test_random_inputs_are_seeded_and_not_reference_donors(self):
        self.assertEqual(sample_origins(19,400,500),sample_origins(19,400,500))
        self.assertNotEqual(sample_origins(19,400,500),sample_origins(20,400,500))
        a=animation_inputs('attachment','back',19)
        b=animation_inputs('attachment','tap',19,tap=[20,100])
        self.assertEqual(a['origins'],b['origins'])
        self.assertNotEqual(a['direction'],b['direction'])


if __name__=='__main__':
    if len(sys.argv)==3 and sys.argv[1]=='--offline':
        offline_frames(Path(sys.argv[2]))
    else:
        unittest.main(verbosity=2)
