"""真实外观布局复用的动画输入必须与 B 的最终外观页整图一致。"""
import argparse
import json
from pathlib import Path
import subprocess
import time

p = argparse.ArgumentParser()
p.add_argument('serial', choices=['9018f404', 'R5CW20BLNKL'])
p.add_argument('--run', required=True)
p.add_argument('--output', required=True)
a = p.parse_args()
adb = ['E:/AndroidSDK/platform-tools/adb.exe', '-s', a.serial]
remote = f'/sdcard/Android/data/com.ywwynm.everythingdone/files/particle-panel-reuse/{a.run}'
def call(*args, check=True):
    return subprocess.run(adb + list(args), check=check, capture_output=True, timeout=30)
assert call('shell', 'test', '-e', remote + '/result.json', check=False).returncode != 0
call('shell', 'am', 'start', '-W', '-n',
     'com.ywwynm.everythingdone/.views.particledismiss.ParticlePanelReuseProbeActivity',
     '--es', 'run_id', a.run)
deadline = time.monotonic() + 20
while call('shell', 'test', '-e', remote + '/result.json', check=False).returncode:
    if time.monotonic() > deadline: raise TimeoutError(a.run)
    time.sleep(.2)
out = Path(a.output); out.mkdir(parents=True, exist_ok=True)
call('pull', remote + '/.', str(out))
result = json.loads((out / 'result.json').read_text())
print(json.dumps(result, ensure_ascii=False))
assert 'error' not in result and result['matchesFinal'], result
