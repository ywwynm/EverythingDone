"""飘散渲染的 GPU 基准（R5CW20BLNKL，调试包 DriftParityRenderActivity 的 bench 模式，只用合成快照）。

每个场景把整段时间轴画一遍到窗口大小的离屏目标，只在末尾 glFinish；条件逐项去掉 compute / 颗粒绘制 / 叠加 / 卡片残留，
成对轮换、取中位数，得出各遍的份额。见 DriftParityRender.bench。

用法（Bash 工具里跑）：python gpu_bench.py --run g1 [--repeats 7]
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402

REMOTE = '/sdcard/Android/data/%s/files/drift-parity' % d.PACKAGE
ACTIVITY = d.PACKAGE + '/.views.particledismiss.DriftParityRenderActivity'
CASES = [
    dict(name='新建页 1440×3088', width=1440, height=3088, pattern='page', seed=11, dx=0, dy=-1),
    dict(name='多选移除 734×2499', width=734, height=2499, pattern='list', seed=12, dx=-1, dy=0),
    dict(name='弹窗 1200×587', width=1200, height=587, pattern='dialog', seed=13, dx=0, dy=-1),
    dict(name='左滑卡片 1300×300', width=1300, height=300, pattern='list', seed=14, dx=-1, dy=0),
]


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--repeats', type=int, default=7)
    p.add_argument('--frames', type=int, default=60)
    p.add_argument('--verify-reuse', action='store_true')
    p.add_argument('--fast', action='store_true', help='同包交错比较原式、静态缓存与噪声拟合')
    p.add_argument('--width', type=int, default=1440)
    p.add_argument('--height', type=int, default=3088)
    a = p.parse_args()
    if not re.fullmatch(r'[A-Za-z0-9_-]+', a.run):
        p.error('--run 只能包含字母、数字、下划线和连字符')
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    width, height = a.width, a.height
    cases = [dict(c, width=round(c['width'] * width / 1440),
                  height=round(c['height'] * height / 3088)) for c in CASES]
    for c in cases:
        c['name'] = c['name'].split()[0] + ' %d×%d' % (c['width'], c['height'])
    if a.verify_reuse:
        cases.append(dict(name='加光／奇数尺寸／非默认大小', width=901, height=347, pattern='dialog',
                          seed=42, dx=-1, dy=-1, additive=True, size=.8, amount=1.6))
    if a.fast:
        cases.append(dict(name='颜色大弹窗／高数量／大粒子', width=round(1200*width/1440),
                          height=round(1400*height/3088), pattern='dialog', seed=25,
                          dx=0, dy=-1, amount=1.6, size=3))
    spec = dict(output=[width, height], frames=a.frames, repeats=a.repeats, cases=cases,
                verifyReuse=a.verify_reuse, fast=a.fast)
    (folder / 'bench.json').write_text(json.dumps(spec, ensure_ascii=False), 'utf-8')
    remote = '%s/%s' % (REMOTE, a.run)
    d.soft('shell', 'rm', '-rf', remote)
    d.adb('shell', 'mkdir', '-p', remote)
    d.adb('push', str(folder / 'bench.json'), remote + '/bench.json')
    d.adb('shell', 'am', 'start', '-n', ACTIVITY, '--es', 'bench', a.run)
    started = time.time()
    while time.time() - started < 600:
        time.sleep(3)
        listing = d.soft('shell', 'ls', remote).stdout.decode('utf-8', 'replace')
        if 'error.txt' in listing:
            d.soft('pull', remote + '/error.txt', str(folder / 'error.txt'))
            raise RuntimeError((folder / 'error.txt').read_text('utf-8')[:2000])
        if 'bench-result.json' in listing:
            break
    else:
        raise RuntimeError('基准超时')
    d.adb('pull', remote + '/bench-result.json', str(folder / 'bench-result.json'))
    result = json.loads((folder / 'bench-result.json').read_text('utf-8'))
    for case in result['cases']:
        if a.fast:
            print(case['name'], case['count'], json.dumps(case['costs'], ensure_ascii=False))
            continue
        print('  重复帧', case.get('repeatedFrame'), '逐字节核对', case.get('reuseVerification'))
        f = case['perFrame']
        full = f['full']['ms']
        print('%s 颗粒 %d+%d 样式 %s：整套 %.2f ms/帧（提交 %.2f）' % (
            case['name'], case['count'], case['replicas'], case['style'], full, f['full']['issueMs']))
        for name, label in (('noCompute', 'compute'), ('noParticles', '颗粒绘制'), ('noComposite', '叠加'),
                            ('noCard', '卡片残留')):
            print('   去掉%s %.2f（份额 %.2f）' % (label, f[name]['ms'], full - f[name]['ms']))
        print('   全部去掉（只剩清屏与固定开销） %.2f' % f['none']['ms'])


if __name__ == '__main__':
    main()
