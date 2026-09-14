"""真实滑动结束后等待只读 GPU 对照；本轮不能混入帧率统计。"""
import argparse,json,subprocess,sys,time
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'thing-creation-animation'))
import device_ui as ui
import device_performance as perf
p=argparse.ArgumentParser();p.add_argument('--title',required=True);p.add_argument('--run',required=True)
a=p.parse_args()
subprocess.run([sys.executable,str(Path(__file__).resolve().parents[1]/'thing-creation-animation/device_performance.py'),
                '--title',a.title,'--run',a.run,'--kind','swipe','--mode','1','--rounds','1','--gpu-check'],check=True)
run=a.run+'-swipe-0'
deadline=time.monotonic()+90
while time.monotonic()<deadline:
    result=perf.remote_json(perf.BASE+run+'/gpu-parity.json')
    if result is not None:
        (perf.ROOT/run/'gpu-parity.json').write_text(json.dumps(result,indent=2),encoding='utf-8')
        print(json.dumps(result),flush=True)
        assert result['passed'] and result['uploadBytesEqual']
        break
    time.sleep(.5)
else: raise RuntimeError('GPU diagnostic did not finish')
