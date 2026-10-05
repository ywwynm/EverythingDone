"""桌面模拟器的 Android 输入规则（tuning-0909-gpt6/android_drift.py）与设备上的正式代码逐位核对。

1. 一次动画的输入：同一组（快照尺寸、方向、种子）分别交给设备上的 ParticleDriftModel.random（DriftParityRenderActivity
   的 inputs 模式）与 Python 移植，逐项比较样式、格点值、风、粒子数、湍流偏移与换算、起止时刻（单精度逐位）。
2. 内容强调：drift_parity.py 某次运行里设备导出的预乘快照与 ParticleDriftContent 的结果，用 Python 移植重算比较。
3. 预乘：同一张 PNG，Python 按 Skia 的取整预乘，与设备解码出来的字节比较。

用法（conda 环境 everythingdone）：python -X utf8 check_inputs.py --run i1 [--parity-run p2]
"""
import argparse
import json
import math
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_parity as dp  # noqa: E402
import android_drift as ad  # noqa: E402  （drift_parity 已把 tuning 目录加进 sys.path）

SIZES = [(1225, 760), (917, 915), (980, 1580), (1260, 700), (1048, 1061), (1440, 2400), (600, 420)]
ANGLES = [135, 125, 145, 157, 112, 90, 63, 45, 30, 0, -45, -90, 180, 200]


def cases():
    out = []
    rng = np.random.RandomState(7)
    for w, h in SIZES:
        for a in ANGLES:
            for _ in range(6):
                reach = 800.0
                out.append(dict(width=w, height=h, dx=math.cos(math.radians(a)) * reach,
                                dy=-math.sin(math.radians(a)) * reach,
                                seed=int(rng.randint(-2**31, 2**31 - 1)), amount=1.0))
    return out


def device_inputs(run, items):
    folder = dp.OUT / run
    folder.mkdir(parents=True, exist_ok=True)
    (folder / 'cases.json').write_text(json.dumps(items), 'utf-8')
    remote = f'{dp.DEVICE_ROOT}/{run}'
    dp.adb('shell', 'rm', '-rf', remote)
    dp.adb('shell', 'mkdir', '-p', remote)
    dp.adb('push', str(folder / 'cases.json'), remote + '/')
    dp.adb('shell', 'am', 'start', '-n', f'{dp.PACKAGE}/.views.particledismiss.DriftParityRenderActivity',
           '--es', 'inputs', run)
    for _ in range(120):
        listing = dp.adb('shell', 'ls', remote)
        if 'error.txt' in listing:
            dp.pull_verified(remote + '/error.txt', folder / 'error.txt')
            raise RuntimeError((folder / 'error.txt').read_text('utf-8'))
        if 'inputs.json' in listing:
            break
        time.sleep(0.5)
    else:
        raise RuntimeError('设备没有写出 inputs.json')
    time.sleep(0.3)
    dp.pull_verified(remote + '/inputs.json', folder / 'inputs.json')
    dp.adb('shell', 'rm', '-rf', remote)
    return json.loads((folder / 'inputs.json').read_text('utf-8'))


def same(a, b):
    """单精度逐位相同（设备把 Float 转 Double 写进 JSON，转回 float32 即原值）。
    Android 的 org.json 把 −0.0 写成整数 0，两边先加 0.0 把 −0.0 归成 +0.0（数值与用法都相同）。"""
    a = np.asarray(a, np.float32).reshape(-1) + np.float32(0.0)
    b = np.asarray(b, np.float32).reshape(-1) + np.float32(0.0)
    return a.shape == b.shape and np.array_equal(a.view(np.uint32), b.view(np.uint32))


def check_models(run):
    items = cases()
    device = device_inputs(run, items)
    styles = ad.load_styles()
    bad = []
    picked = {}
    for c, d in zip(items, device):
        m = ad.random_model(c['width'], c['height'], np.float32(c['dx']), np.float32(c['dy']), c['amount'],
                            ad.seeded_random(c['seed']), styles)
        picked[m.style_name or '随机'] = picked.get(m.style_name or '随机', 0) + 1
        checks = dict(
            style=(m.style_name or None) == d['style'],
            lattice=(m.lattice_width, m.lattice_height) == (d['latticeWidth'], d['latticeHeight']) and same(m.lattice, d['lattice']),
            wind=same([m.wind_x, m.wind_y], [d['windX'], d['windY']]),
            count=m.count == d['count'],
            fade=same([m.fade_size_power, m.fade_alpha_power, m.over_alpha_mul],
                      [d['fadeSizePower'], d['fadeAlphaPower'], d['overAlphaMul']]),
            offset=same(m.turbulence_offset, d['turbulenceOffset']),
            frame=(m.turbulence_frame is None) == (d['turbulenceFrame'] is None) and
                  (m.turbulence_frame is None or same(m.turbulence_frame, d['turbulenceFrame'])),
            times=same([m.derived['scaleU'], m.derived['startSeconds'], m.derived['bodyGoneSeconds'], m.derived['totalSeconds']],
                       [d['scaleU'], d['startSeconds'], d['bodyGoneSeconds'], d['totalSeconds']]))
        failed = [k for k, v in checks.items() if not v]
        if failed:
            bad.append(dict(case=c, failed=failed, python=dict(style=m.style_name, wind=[float(m.wind_x), float(m.wind_y)],
                                                              lattice=[float(v) for v in m.lattice]),
                            device=dict(style=d['style'], wind=[d['windX'], d['windY']], lattice=d['lattice'])))
    print('输入：%d 条，不一致 %d 条' % (len(items), len(bad)))
    print('样式分布', json.dumps(picked, ensure_ascii=False))
    for b in bad[:5]:
        print(json.dumps(b, ensure_ascii=False)[:600])
    return len(bad) == 0


def check_content(parity_run):
    ok = True
    root = dp.OUT / parity_run
    for case in sorted(p for p in root.iterdir() if (p / 'device/result.json').exists()):
        result = json.loads((case / 'device/result.json').read_text('utf-8'))
        w, h = result['width'], result['height']
        premul = np.frombuffer((case / 'device/snapshot.rgba').read_bytes(), np.uint8).reshape(h, w, 4)
        mine = ad.premultiply(np.asarray(Image.open(case / 'snapshot.png').convert('RGBA')))
        pm = np.array_equal(mine, premul)
        dev = result['content'] if json.loads((case / 'spec.json').read_text('utf-8')).get('content') else None
        if dev is None:
            print(case.name, '预乘逐字节相同' if pm else '预乘不同', '（未开内容强调）')
            ok &= pm
            continue
        py = ad.analyse(premul, result['count'])
        fields = dict(panel=same(py['panel'], dev['panel']), panelWeight=same([py['panelWeight']], [dev['panelWeight']]),
                      grid=py['grid'] == dev['grid'], replicas=py['replicas'] == dev['replicas'],
                      cumulative=same(py['cumulative'], dev['cumulative']))
        print(case.name, '预乘逐字节相同' if pm else '预乘不同', '内容强调', fields,
              '副本 %d / %d' % (py['replicas'], dev['replicas']))
        ok &= pm and all(fields.values())
    return ok


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--parity-run', default='p2')
    a = p.parse_args()
    ok = check_content(a.parity_run)
    ok = check_models(a.run) and ok
    print('全部一致' if ok else '有不一致')


if __name__ == '__main__':
    main()
