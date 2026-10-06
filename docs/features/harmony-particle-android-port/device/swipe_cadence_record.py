"""左滑跟手在屏幕上的实际帧间隔：补帧开 / 关各录一次（R5CW20BLNKL，虚构阴影测试卡片探针，240 Hz 异步注入）。

screenrecord 只在画面变化时出帧。左滑期间粒子层每画一帧画面就变，所以相邻帧的间隔就是屏幕上的更新间隔
（120 fps 时约 8.3 ms，漏掉一拍是 16.7 ms）。统计从画面开始变化起 1.0 s 的那一段（注入 1.1 s，之后是松手回位）。
补帧开关（ParticlePerfTuning.lateGestureFrames）经 run-as 写 files/particle-perf-tuning.json；原来没有这个文件就跑完删掉，
有就写回原文。样式经偏好切到飘散，跑完恢复原文。

另跑一次不录屏的对照，并在每次左滑中途读 `dumpsys display` 的帧率覆盖、从 logcat 取调试包的「飘散跟手」与出帧对比两行：
录屏本身可能改变应用主线程的帧率（2026-10-06 三星上录屏时位置只按 60 Hz 更新），录屏结果要与对照一起看。

用法（Bash 工具里跑）：python swipe_cadence_record.py --run s1
"""
import argparse
import json
import re
import statistics
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402
import perf_compare as pc  # noqa: E402
import stage_b_probes as b  # noqa: E402

CONFIG = 'files/particle-perf-tuning.json'
WINDOW_SECONDS = 1.0


def write_config(text):
    d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    time.sleep(.8)
    subprocess.run([d.ADB, '-s', d.SERIAL, 'shell', 'run-as', d.PACKAGE, 'sh', '-c',
                    "'cat > %s.tmp && mv %s.tmp %s'" % (CONFIG, CONFIG, CONFIG)],
                   input=text.encode('utf-8'), check=True)


def overrides():
    out = d.soft('shell', 'dumpsys display').stdout.decode('utf-8', 'replace')
    return sorted({m.group(0) for m in re.finditer(r'mFrameRateOverrides=\[[^\]]*\]', out)})


def one_swipe(folder, label, record=True):
    d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    time.sleep(.8)
    b.start('ParticleSwipeShadowProbeActivity', run_id='swipe-cadence-%s' % label, allow_right_swipe=True)
    time.sleep(2.0)
    card = None
    for _ in range(20):
        card = d.find(d.dump(), desc=('虚构阴影测试卡片',))
        if card is not None:
            break
        time.sleep(.3)
    if card is None:
        raise RuntimeError('找不到虚构阴影测试卡片')
    width, _ = d.screen_size()
    x1, y1, x2, y2 = d.bounds(card)
    cy = (y1 + y2) // 2
    sx, ex = min(x2 - 60, width - 140), max(40, x1 - 40)
    mid = sx + (ex - sx) * 2 // 5
    rec = r.Recording(4) if record else None
    inject = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'shell', 'PARTICLE_INPUT_ASYNC=1', 'CLASSPATH=' + pc.REMOTE_DRIVER,
                               'app_process', '/system/bin', 'ParticleGestureInput', str(sx), str(cy), 'UP', str(mid), str(cy),
                               '900', str(ex), str(cy), '200'])
    time.sleep(.5)
    during = overrides()
    inject.wait(timeout=30)
    if rec is None:
        time.sleep(2.0)
        return dict(label=label, recorded=False, overrides=during)
    video = rec.finish(folder / ('swipe-%s.mp4' % label))
    time.sleep(1.0)
    items = r.frames(video, folder / ('swipe-%s-frames' % label), scale_width=360)
    gray = [np.asarray(Image.open(p).convert('L'), dtype=np.int16) for _, p in items]
    changed = [0] + [int((np.abs(gray[i] - gray[i - 1]) > 10).sum()) for i in range(1, len(gray))]
    active = [i for i in range(1, len(changed)) if changed[i] > 30]
    if not active:
        return dict(label=label, recorded=True, overrides=during, video=str(video), frames=len(items), active=0)
    start = items[active[0]][0]
    window = [i for i in range(active[0], len(items)) if items[i][0] - start <= WINDOW_SECONDS]
    gaps = [(items[i][0] - items[i - 1][0]) * 1000 for i in window[1:]]
    # 只算画面真的变了的帧：录屏在别的图层变化时也出帧，那一帧粒子层可能没变
    content = [i for i in window if changed[i] > 0]
    content_gaps = [(items[content[k]][0] - items[content[k - 1]][0]) * 1000 for k in range(1, len(content))]
    return dict(label=label, recorded=True, overrides=during, video=str(video), frames=len(window),
                span_ms=round((items[window[-1]][0] - start) * 1000, 1),
                gap_median=round(statistics.median(gaps), 2), gap_max=round(max(gaps), 2),
                content_frames=len(content), content_gap_median=round(statistics.median(content_gaps), 2),
                content_long_gaps=sum(1 for g in content_gaps if g >= 12),
                content_gaps=[round(g, 2) for g in content_gaps])


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    a = p.parse_args()
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    prefs = pc.run_as('cat %s' % pc.PREFS)
    assert prefs.strip().startswith('<?xml'), prefs
    had_config = 'particle-perf-tuning.json' in pc.run_as('ls files')
    config = pc.run_as('cat %s' % CONFIG) if had_config else ''
    base = json.loads(config) if had_config else {}
    results = []
    d.soft('logcat', '-c')
    log = open(folder / 'logcat.txt', 'wb')
    cat = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'logcat', '-v', 'time', '-s', 'ParticlePacing:I', 'ParticleDrift:I'],
                           stdout=log, stderr=subprocess.DEVNULL)
    try:
        pc.set_style(prefs, 'drift')
        for label, late, record in (('on', True, True), ('off', False, True), ('on-norec', True, False)):
            write_config(json.dumps(dict(base, lateGestureFrames=late)))
            results.append(one_swipe(folder, label, record))
            time.sleep(1.0)
        d.adb('shell', 'am', 'force-stop', d.PACKAGE)
    finally:
        cat.terminate()
        log.close()
        if had_config:
            write_config(config)
        else:
            d.adb('shell', 'am', 'force-stop', d.PACKAGE)
            pc.run_as('rm -f %s' % CONFIG)
        pc.write_prefs(prefs)
        config_restored = (pc.run_as('cat %s' % CONFIG) == config) if had_config else \
            'particle-perf-tuning.json' not in pc.run_as('ls files')
        prefs_restored = pc.run_as('cat %s' % pc.PREFS) == prefs
    text = (folder / 'logcat.txt').read_bytes().decode('utf-8', 'replace')
    gesture = re.findall(r'飘散跟手 (.*)', text)
    pacing = re.findall(r'出帧对比 样式=飘散 类型=跟手 (.*)', text)
    for k, item in enumerate(results):
        item['gesture'] = gesture[k].strip() if k < len(gesture) else None
        item['pacing'] = pacing[k].strip() if k < len(pacing) else None
    (folder / 'result.json').write_text(json.dumps(dict(rounds=results, config_restored=config_restored,
                                                        prefs_restored=prefs_restored), ensure_ascii=False, indent=1), 'utf-8')
    for item in results:
        print(json.dumps({k: v for k, v in item.items() if k != 'content_gaps'}, ensure_ascii=False))
    print('config restored', config_restored, 'prefs restored', prefs_restored)


if __name__ == '__main__':
    main()
