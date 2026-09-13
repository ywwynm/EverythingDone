"""同一详情页连续复用；实际提交帧与海浪首帧捕获分开验收。"""
import argparse
import json
import gzip
from pathlib import Path
import subprocess
import time
import numpy as np

p = argparse.ArgumentParser()
p.add_argument('serial', choices=['9018f404', 'R5CW20BLNKL'])
p.add_argument('--run', required=True)
p.add_argument('--output', required=True)
p.add_argument('--kind', choices=['attachment', 'record'], default='attachment')
p.add_argument('--repeat', type=int, default=8)
p.add_argument('--capture', action='store_true')
p.add_argument('--check', action='store_true')
p.add_argument('--direct', action='store_true', help='消融：绕过真实工具栏触摸反馈')
a = p.parse_args()
out = Path(a.output); out.mkdir(parents=True, exist_ok=True)
adb = ['E:/AndroidSDK/platform-tools/adb.exe', '-s', a.serial]
def call(*args, check=True):
    return subprocess.run(adb + list(args), check=check, capture_output=True, timeout=30,
                          encoding='utf-8', errors='replace')
remote = f'/sdcard/Android/data/com.ywwynm.everythingdone/files/particle-repeat/{a.run}'
assert call('shell', 'test', '-e', f'{remote}/result.json', check=False).returncode != 0
old = set(call('logcat', '-d', '-s', 'ParticleMicroflake:I', 'FableSolSurfProbe:I', '*:S').stdout.splitlines())
call('shell', 'am', 'start', '-W', '--activity-multiple-task', '-n',
     'com.ywwynm.everythingdone/.views.particledismiss.ParticleRepeatProbeActivity',
     '--es', 'run_id', a.run, '--es', 'kind', a.kind, '--ei', 'repeat', str(a.repeat),
     '--ez', 'capture', str(a.capture).lower(), '--ez', 'menu', str(not a.direct).lower())
deadline = time.monotonic() + a.repeat * 12 + 25
while call('shell', 'test', '-e', f'{remote}/result.json', check=False).returncode != 0:
    if time.monotonic() > deadline: raise TimeoutError(a.run)
    time.sleep(.5)
call('pull', remote + '/.', str(out))
log = '\n'.join(x for x in call('logcat', '-d', '-s', 'ParticleMicroflake:I', 'FableSolSurfProbe:I', '*:S')
                .stdout.splitlines() if x not in old)
(out/'animation.log').write_text(log, encoding='utf-8')
d = json.loads((out/'result.json').read_text())
assert 'error' not in d, d.get('error')
assert d['rounds'] == a.repeat and d['menu'] == (not a.direct), d
for cycle in range(a.repeat):
    rows = [r for r in d['overlays'] if r['cycle'] == cycle]
    expected = 2 if a.kind == 'attachment' else 4
    assert len(rows) == expected and sum(r['reverse'] for r in rows) == expected // 2, (cycle, rows)
    if a.capture:
        frames = [w for w in d.get('waves', []) if w['file'].startswith(f'{cycle}-')]
        assert len(frames) == 6 and sum(w['frozen'] for w in frames) == 2, (cycle, frames)
summary = []
failures = []
for row in d['overlays']:
    s = np.array(row.get('submitted', [])); c = np.array(row.get('committed', []))
    if not len(s) or not len(c):
        item = dict(cycle=row['cycle'], error='未获得提交帧')
        summary.append(item)
        failures.append(item)
        continue
    delta = np.diff(c[:, 1]) / 1e6
    item = dict(cycle=row['cycle'], reverse=row['reverse'], width=row['width'], height=row['height'],
                submitted=len(s), committed=len(c), p90=round(float(np.percentile(delta, 90)), 2),
                max=round(float(max(delta)), 2), lastMatches=int(s[-1,0]) == int(c[-1,0]),
                sourceP90=round(float(np.percentile(np.diff(s[:,0])/1e6, 90)), 2))
    summary.append(item)
    if c.shape[1] > 2:
        item['callbackDispatchMaxMs'] = round(float((c[:, 2] - c[:, 1]).max() / 1e6), 2)
        item['observerP90Ms'] = round(float(np.percentile(np.diff(c[:, 2]) / 1e6, 90)), 2)
    if a.check and not a.capture:
        if not (item['lastMatches'] and item['committed'] >= (32 if row['reverse'] else 49)
                and item['p90'] < 25 and item['max'] < 65): failures.append(item)
for w in d.get('waves', []):
    from PIL import Image
    assert w['error'] == 0 and w.get('beforeError', 0) == 0, w
    source = out / w['file']
    raw = gzip.decompress(source.read_bytes()) if source.suffix == '.gz' else source.read_bytes()
    rgba = np.frombuffer(raw, np.float32).reshape(w['height'], w['width'], 4)[::-1].copy()
    if w.get('linear'):
        rgb=rgba[...,:3]
        rgba[...,:3]=np.where(rgb<=.0031308,rgb*12.92,1.055*np.maximum(rgb,0)**(1/2.4)-.055)
    Image.fromarray((rgba.clip(0,1)*255).astype('uint8')).save(out / w['file'].replace('.f32.gz', '.png').replace('.f32', '.png'))
    assert w.get('zeroPositions', 0) == 0 and w.get('sceneError', 0) == 0, w
(out/'summary.json').write_text(json.dumps(summary, indent=2, ensure_ascii=False), encoding='utf-8')
print(json.dumps(summary, ensure_ascii=False), flush=True)
assert not failures, failures
