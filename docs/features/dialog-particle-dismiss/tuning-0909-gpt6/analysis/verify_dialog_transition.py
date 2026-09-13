"""真实弹窗按钮交接：记录 ripple 剩余量和实际 Texture 提交间隔，避免录屏编码干扰帧率。"""
import argparse
import json
from pathlib import Path
import subprocess
import time
import numpy as np

p = argparse.ArgumentParser()
p.add_argument('serial', choices=['9018f404', 'R5CW20BLNKL'])
p.add_argument('--prefix', required=True)
p.add_argument('--output', required=True)
p.add_argument('--kinds', nargs='+', default=['record', 'chooser', 'close'])
p.add_argument('--repeat', type=int, default=1)
p.add_argument('--check', action='store_true')
p.add_argument('--capture', action='store_true', help='额外检查 ripple 消失后的整图一致性；该轮不作为性能证据')
p.add_argument('--profile', action='store_true', help='采样主线程调用栈；该轮只定位耗时，不作无扰动性能证据')
a = p.parse_args()
out = Path(a.output); out.mkdir(parents=True, exist_ok=True)
adb = ['E:/AndroidSDK/platform-tools/adb.exe', '-s', a.serial]

def call(*args, check=True):
    result = subprocess.run(adb + list(args), check=False, capture_output=True, timeout=30,
                          encoding='utf-8', errors='replace')
    if result.returncode and args[0] == 'logcat':
        result = subprocess.run(adb + list(args), check=False, capture_output=True, timeout=30,
                                encoding='utf-8', errors='replace')
    if check: result.check_returncode()
    return result

results = []
for i in range(a.repeat):
    for kind in a.kinds:
        run = f'{a.prefix}-{kind}-{i}'
        remote = f'/sdcard/Android/data/com.ywwynm.everythingdone/files/particle-transition/{run}'
        assert call('shell', 'test', '-e', f'{remote}/result.json', check=False).returncode != 0
        old_log = set(call('logcat', '-d', '-s', 'ParticleMicroflake:I', '*:S').stdout.splitlines())
        call('shell', 'am', 'start', '-W', '--activity-multiple-task', '-n',
             'com.ywwynm.everythingdone/.views.particledismiss.ParticleTransitionProbeActivity',
             '--es', 'kind', kind, '--es', 'run_id', run, '--ez', 'capture', str(a.capture).lower(),
             '--ez', 'profile', str(a.profile).lower())
        deadline = time.monotonic() + 25
        while call('shell', 'test', '-e', f'{remote}/result.json', check=False).returncode != 0:
            if time.monotonic() > deadline: raise TimeoutError(run)
            time.sleep(.2)
        target = out / run; target.mkdir(exist_ok=True)
        call('pull', remote + '/.', str(target))
        data = json.loads((target / 'result.json').read_text())
        assert 'error' not in data, data
        log = '\n'.join(x for x in call('logcat', '-d', '-s', 'ParticleMicroflake:I', '*:S').stdout.splitlines() if x not in old_log)
        (target / 'animation.log').write_text(log, encoding='utf-8')
        release = next(x['ms'] for x in data['events'] if x['event'] == 'up')
        rows = [x for x in data['samples'] if x['ms'] >= release]
        removed = next(x for x in rows if not x['sourceAttached'])
        at_remove = max((x for x in rows if x['sourceAttached']), key=lambda x: x['ms'])
        gaps = np.diff([x['ms'] for x in rows if x['ms'] < release + 1600])
        metrics = dict(run=run, upMs=release, sourceRemovedAfterUpMs=removed['ms']-release,
                       rippleAlphaAtLastSourceFrame=at_remove['rippleAlpha'],
                       uiMaxGapMs=float(max(gaps)), overlays=[])
        tail = [x for x in rows if 0 < x['rippleAlpha'] < .55]
        metrics['rippleTailMaxGapMs'] = float(max(np.diff([x['ms'] for x in tail]), default=0))
        if data.get('windowFrames'):
            metrics['sourceWindowMaxMs'] = max((x['totalMs'] for x in data['windowFrames']
                if x['source'] and release < x['ms'] < removed['ms']), default=0)
        for overlay in data['overlays']:
            t = overlay['presentedMs']; delta = np.diff(t)
            metrics['overlays'].append(dict(reverse=overlay['reverse'], height=overlay['height'],
                startAfterUpMs=t[0]-release if t else None, frames=len(t),
                p90Ms=float(np.percentile(delta,90)) if len(delta) else None,
                maxMs=float(max(delta)) if len(delta) else None))
            item = metrics['overlays'][-1]
            if overlay.get('committed'):
                submitted = np.array(overlay['submitted']); committed = np.array(overlay['committed'])
                item['committedFrames'] = len(committed)
                item['finalFrameMatches'] = int(submitted[-1,0]) == int(committed[-1,0])
                item['committedP90Ms'] = float(np.percentile(np.diff(committed[:,1])/1e6,90))
                if a.check:
                    assert item['finalFrameMatches'], item
                    assert overlay.get('blankBufferKeptSnapshot', True), overlay
                    if not (a.capture or a.profile):
                        assert item['committedFrames'] >= (28 if overlay['reverse'] else 45), item
        for index, overlay in enumerate(x for x in data['overlays'] if x['reverse'] and x.get('committed')):
            epoch = overlay['presentationDetails'][0]['at']/1e6 - overlay['presentedMs'][0]
            start = overlay['committed'][0][1]/1e6 - epoch
            end = overlay['committed'][-1][1]/1e6 - epoch
            series = [(s['ms'], dim['alpha']) for s in data['samples'] for dim in s.get('dims', [])
                      if dim['source'] == (index == 0)]
            before = [alpha for t,alpha in series if t < start - 3]
            after = next(((t,alpha) for t,alpha in series if t > end + 5), None)
            if a.check:
                assert max(before, default=0) == 0, ('暗层提前出现', overlay['height'])
                assert after is not None and abs(after[1] - .6) < .002, ('暗层未随末帧完成', after)
        results.append(metrics)
        print(json.dumps(metrics, ensure_ascii=False), flush=True)
        if a.check:
            if kind not in ['back', 'outside']: assert data['ripplePresent'], data
            assert metrics['rippleAlphaAtLastSourceFrame'] == 0, metrics
            assert '粒子动画回退' not in log and '完成 count=' in log, metrics
            if kind in ['back', 'outside']:
                assert metrics['sourceRemovedAfterUpMs'] < 250, metrics
            assert all(x['frames'] >= (18 if x['reverse'] else 45) for x in metrics['overlays']), metrics
            if not (a.capture or a.profile):
                assert metrics['uiMaxGapMs'] < 70, metrics
        if a.capture:
            assert data['cleanSnapshotMatches'], data
(out / 'summary.json').write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding='utf-8')
