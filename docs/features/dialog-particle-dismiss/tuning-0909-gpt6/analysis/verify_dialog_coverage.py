"""在已授权设备上检查共同入口是否实际创建出现及消散动画，保存真实动画输入。"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import numpy as np
from PIL import Image

p = argparse.ArgumentParser()
p.add_argument('serial', choices=['9018f404', 'R5CW20BLNKL'])
p.add_argument('--prefix', required=True)
p.add_argument('--output', required=True)
p.add_argument('--kinds', nargs='+', default=['plain', 'transparent', 'hidden-surface', 'texture', 'surface'])
p.add_argument('--diagnose', action='store_true')
a = p.parse_args()
out = Path(a.output); out.mkdir(parents=True, exist_ok=True)
adb = ['E:/AndroidSDK/platform-tools/adb.exe', '-s', a.serial]
def call(*args, check=True):
    return subprocess.run(adb + list(args), check=check, capture_output=True, timeout=30,
                          encoding='utf-8', errors='replace')
results = []
for kind in a.kinds:
    run = f'{a.prefix}-{kind}'
    remote = f'/sdcard/Android/data/com.ywwynm.everythingdone/files/particle-coverage/{run}'
    assert call('shell', 'test', '-e', f'{remote}/result.json', check=False).returncode != 0
    before_log = set(call('logcat', '-d', '-s', 'ParticleMicroflake:I', '*:S').stdout.splitlines())
    call('shell', 'am', 'start', '-W', '-n', 'com.ywwynm.everythingdone/.views.particledismiss.ParticleCoverageProbeActivity',
         '--es', 'kind', kind, '--es', 'run_id', run)
    deadline = time.monotonic() + 22
    while call('shell', 'test', '-e', f'{remote}/result.json', check=False).returncode != 0:
        if time.monotonic() > deadline: raise TimeoutError(run)
        time.sleep(.15)
    # onDestroy 补记浮动宿主退出时序；等待真实 Activity 消失，不能提前读取临时结果。
    while time.monotonic() < deadline:
        resumed = call('shell', 'dumpsys', 'activity', 'activities').stdout
        if not any('ParticleCoverageProbeActivity' in line for line in resumed.splitlines() if 'topResumedActivity=' in line):
            break
        time.sleep(.1)
    if kind in ['floating', 'finish-during-capture']:
        while time.monotonic() < deadline:
            data = json.loads(call('shell', 'cat', f'{remote}/result.json').stdout)
            if 'hostWaitMs' in data: break
            time.sleep(.1)
    target = out / run; target.mkdir(exist_ok=True)
    call('pull', remote + '/.', str(target))
    result = json.loads((target / 'result.json').read_text(encoding='utf-8'))
    log = '\n'.join(line for line in call('logcat', '-d', '-s', 'ParticleMicroflake:I', '*:S').stdout.splitlines()
                    if line not in before_log)
    (target / 'animation.log').write_text(log, encoding='utf-8')
    result['gpuAppearanceCompleted'] = '出现完成 particles=' in log
    result['gpuDismissalCompleted'] = '完成 count=' in log
    metrics = {}
    for phase in ['appearance', 'dismissal']:
        path = target / f'{phase}.png'
        if not path.exists(): continue
        pixels = np.array(Image.open(path).convert('RGBA'))
        opaque = pixels[:, :, 3] > 240
        rgb = pixels[:, :, :3].astype(float)
        cyan = (rgb[:, :, 1] - rgb[:, :, 0] > 12) & (rgb[:, :, 2] - rgb[:, :, 0] > 12)
        red = (rgb[:, :, 0] > 200) & (rgb[:, :, 1] < 80) & (rgb[:, :, 2] < 80)
        metrics[phase] = dict(opaqueFraction=float(opaque.mean()), cyanFraction=float(cyan.mean()), redPixels=int(red.sum()))
    result['snapshotPixels'] = metrics
    if kind.endswith('-handoff'):
        frames = [np.array(Image.open(target / f'handoff-{i}.png').convert('RGBA')).astype(np.int16) for i in range(4)]
        opaque = frames[0][:, :, 3] > 250
        protected = np.abs(frames[1] - frames[2]).max(2)[opaque]
        exposed = np.abs(frames[1] - frames[3]).max(2)[opaque]
        result['handoffPixels'] = dict(protectedChangedFraction=float((protected > 2).mean()),
                                      protectedMax=int(protected.max()),
                                      unprotectedChangedFraction=float((exposed > 2).mean()))
        # 必须先证明撤掉保护会暴露问题，再确认 Surface 消失时保护画面不变。
        assert result['handoffPixels']['unprotectedChangedFraction'] > .1, result
        assert result['handoffPixels']['protectedChangedFraction'] < .001, result
    if not a.diagnose:
        assert 'error' not in result and result.get('removed'), result
        assert result['gpuDismissalCompleted'], result
        if kind != 'surface-quick': assert result['gpuAppearanceCompleted'], result
        if kind in ['surface', 'surface-quick', 'texture']:
            for phase, metric in metrics.items():
                assert metric['opaqueFraction'] > .98 and metric['redPixels'] > 200, (kind, phase, metric)
                if kind == 'texture' or phase == 'dismissal' and kind == 'surface':
                    assert metric['cyanFraction'] > .05, (kind, phase, metric)
        if kind in ['floating', 'finish-during-capture']: assert result.get('hostWaitMs', 0) >= 1000, result
    results.append(result)
    print(run, result, flush=True)
(out / 'summary.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
if not a.diagnose:
    assert all((r.get('appearance') or r['kind'] == 'surface-quick') and r.get('dismissal') for r in results)
