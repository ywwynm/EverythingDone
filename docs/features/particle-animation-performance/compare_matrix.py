"""把两轮矩阵（修前 / 修后）的同名场景摆到一张表里。

口径与 per-frame-baseline 第 2 节一致：呈现帧数/应有帧数、跨度、呈现间隔 p90 与最大、
请求→首帧提交/呈现、CPU 提交 p90。行是「场景 + 动画层」，同一场景有两层时按出现顺序配对。

用法：python compare_matrix.py <serial> <修前前缀…> -- <修后前缀>
简写：python compare_matrix.py 392QBFED2226B matrix1,matrix2 b1matrix
"""
import json
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[3]
ROOT = REPO / 'tmp/particle-perf/per-frame'


def load(serial, prefixes):
    runs = {}
    for prefix in prefixes:
        path = ROOT / serial / ('summary-%s.json' % prefix)
        if not path.is_file():
            continue
        for run, data in json.loads(path.read_text(encoding='utf-8')).items():
            scene = run.split('-', 1)[1]
            for index, overlay in enumerate(data.get('overlays') or []):
                overlay = dict(overlay, card=data.get('card'))
                runs.setdefault('%s#%d' % (scene, index), overlay)
    return runs


def cell(overlay):
    if not overlay:
        return None
    ratio = overlay.get('ratio')
    gaps = overlay.get('presentGapMs') or {}
    return dict(count=overlay.get('count'),
                presented=overlay.get('presented'), expected=overlay.get('expected'),
                ratio=ratio, spanMs=overlay.get('spanMs'),
                p90=gaps.get('p90'), max=gaps.get('max'),
                toSubmit=overlay.get('firstSubmitMs'),
                toPresent=overlay.get('firstPresentMs'),
                drawP90=(overlay.get('cpuIssueMs') or {}).get('p90'))


def fmt(value, digits=2):
    return '—' if value is None else ('%.*f' % (digits, value))


def main():
    serial = sys.argv[1]
    before = sys.argv[2].split(',')
    after = sys.argv[3].split(',')
    a = load(serial, before)
    b = load(serial, after)
    keys = [k for k in a if k in b]
    print('| 场景#层 | 片数 | 呈现/应有 前→后 | 间隔 p90 前→后 | 间隔 max 前→后 | 请求→呈现 前→后 | CPU 提交 p90 前→后 |')
    print('| --- | --- | --- | --- | --- | --- | --- |')
    for key in keys:
        x, y = cell(a[key]), cell(b[key])
        print('| %s | %s | %s → %s | %s → %s | %s → %s | %s → %s | %s → %s |' % (
            key, y['count'],
            fmt(x['ratio'], 3), fmt(y['ratio'], 3),
            fmt(x['p90']), fmt(y['p90']),
            fmt(x['max']), fmt(y['max']),
            fmt(x['toPresent'], 1), fmt(y['toPresent'], 1),
            fmt(x['drawP90']), fmt(y['drawP90'])))
    missing = [k for k in b if k not in a]
    if missing:
        print('\n只在修后出现：', ', '.join(missing))
    swipes = [k for k in keys if k.startswith('swipe')
              and (a[k].get('gestureLag') or b[k].get('gestureLag'))]
    if not swipes:
        return
    # 左滑单独一张：移动期的不同画面数与「手指要的相位 − 实际画出的相位」。
    print('\n| 左滑场景 | 卡高 px | 片数 | 移动期画面 前→后 | 移动期呈现 前→后 |'
          ' 移动期间隔 p90 前→后 | 滞后 p90 档 前→后 | 滞后 max 档 前→后 | 被卡住的帧占比 前→后 |')
    print('| --- | --- | --- | --- | --- | --- | --- | --- | --- |')
    for key in swipes:
        def lag(row, field, digits=2):
            data = row.get('gestureLag') or {}
            value = (data.get('lagSamples') or {}).get(field) if field in ('p90', 'max') \
                else data.get(field)
            return fmt(value, digits)

        def move(row, field):
            return fmt((row.get('inMove') or {}).get(field), 0)

        def gap(row, field):
            return fmt(((row.get('inMove') or {}).get('gapMs') or {}).get(field))
        height = ((b[key].get('card') or {}) or {}).get('heightPx')
        print('| %s | %s | %s | %s → %s | %s → %s | %s → %s | %s → %s | %s → %s | %s → %s |' % (
            key, height if height else '—', b[key].get('count'),
            move(a[key], 'pictures'), move(b[key], 'pictures'),
            move(a[key], 'presented'), move(b[key], 'presented'),
            gap(a[key], 'p90'), gap(b[key], 'p90'),
            lag(a[key], 'p90'), lag(b[key], 'p90'),
            lag(a[key], 'max'), lag(b[key], 'max'),
            lag(a[key], 'clampedRatio', 3), lag(b[key], 'clampedRatio', 3)))


if __name__ == '__main__':
    main()
