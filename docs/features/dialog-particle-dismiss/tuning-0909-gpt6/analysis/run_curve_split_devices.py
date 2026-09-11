"""串行验证另一种方向、起点分布和屏幕内近远，避免重复覆盖设备输出。"""
from pathlib import Path
import sys,subprocess,json
HERE=Path(__file__).resolve().parents[1];sys.path.insert(0,str(HERE))
from touch_geometry import distance_cases
def run(script,args):
    subprocess.run([sys.executable,'-X','utf8',str(HERE/'analysis'/script),*args],cwd=HERE,check=True)
meta=json.loads((HERE/'assets/color/scene.json').read_text('utf-8'))
near,_,far=distance_cases(meta,135)
cases=[('-local',['ironman-up-reference','attachment'],['--direction','90','--seed','489']),
       ('-multiple',['thanos','color'],['--direction','130','--seed','323']),
       ('-side',['kobe'],['--direction','128','--seed','494']),
       ('-near',['color'],['--direction',str(near['angle']),'--touch-gap',str(near['gap'])]),
       ('-far',['color'],['--direction',str(far['angle']),'--touch-gap',str(far['gap'])])]
for suffix,scenes,inputs in cases:
    folder='device-curve-split'+suffix
    run('validate_unified_devices.py',['9018f404','--scenes',*scenes,'--output',folder,*inputs])
    run('compare_unified_devices.py',['--serials','9018f404','--scenes',*scenes,'--device-dir',folder,
        '--report-dir','analysis/curve-split/device'+suffix,*inputs])
print('补充七组独立设备链路完成',flush=True)
