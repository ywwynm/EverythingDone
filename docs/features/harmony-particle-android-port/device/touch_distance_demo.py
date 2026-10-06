"""触点远近对照（R5CW20BLNKL，只用多选移除探针里的虚构内容）。

钉住一段参考样式（调试键 debug_drift_force_style；钉住时不看方向与概率，格点、风、湍流都是确定的），在弹窗中线的正上方
按「贴近弹窗上沿」「中段」「贴近屏幕顶部」各点一次弹窗外：三次方向相同（都朝正上方），画面的差别只来自触点远近
（风力倍数 0.55～1.45 与朝终点的偏转）。每次录屏，按动画开始后的同一组时刻取帧拼成对照表；日志里的
「触点距离 / 风力」逐次核对。跑完把偏好恢复成跑之前的原文并读回核对。

用法（Bash 工具里跑）：python touch_distance_demo.py --run t1 [--style reference-0926-7]
"""
import argparse
import json
import re
import subprocess
import sys
import time
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402
import perf_compare as pc  # noqa: E402
import stage_b_probes as b  # noqa: E402

FORCE_KEY = 'debug_drift_force_style'
OFFSETS = [0.0, 0.1, 0.2, 0.3, 0.4, 0.5, 0.65, 0.8]
SCALE_WIDTH = 360


def prefs_with(original, style):
    body = re.sub(r'\s*<string name="(particle_style|%s)">[^<]*</string>' % FORCE_KEY, '', original)
    body = body.replace('<map />', '<map>\n</map>')
    return body.replace('</map>', '    <string name="particle_style">drift</string>\n'
                                  '    <string name="%s">%s</string>\n</map>' % (FORCE_KEY, style))


def panel_bounds(tree, width, height):
    """包含「确定」按钮、又明显小于整屏的最大祖先：弹窗面板。"""
    parents = {child: parent for parent in tree.iter() for child in parent}
    node = d.find(tree, text=('确定', 'OK'))
    best = d.bounds(node)
    while node in parents:
        node = parents[node]
        if node.get('bounds') is None:
            break
        x1, y1, x2, y2 = d.bounds(node)
        if (x2 - x1) >= width * .98 and (y2 - y1) >= height * .9:
            break
        best = (x1, y1, x2, y2)
    return best


def open_dialog():
    toolbar = None
    for _ in range(20):
        toolbar = d.find(d.dump(), rid='contextual_toolbar')
        if toolbar is not None:
            break
        time.sleep(.3)
    d.tap(toolbar)
    b.wait_text(('确定', 'OK'))
    time.sleep(1.4)


def onset(items, box):
    """弹窗区域开始变化的第一帧（与第一帧比，变化像素超过 0.5%）。"""
    x1, y1, x2, y2 = box
    base = np.asarray(Image.open(items[0][1]).convert('L'), dtype=np.float32)[y1:y2, x1:x2]
    for t, path in items:
        frame = np.asarray(Image.open(path).convert('L'), dtype=np.float32)[y1:y2, x1:x2]
        if (np.abs(frame - base) > 16).mean() > .005:
            return t
    return None


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--style', default='reference-0926-7')
    a = p.parse_args()
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)
    original = pc.run_as('cat %s' % pc.PREFS)
    assert original.strip().startswith('<?xml'), original
    d.soft('logcat', '-c')
    log = open(folder / 'logcat.txt', 'wb')
    cat = subprocess.Popen([d.ADB, '-s', d.SERIAL, 'logcat', '-v', 'time', '-s', 'ParticleDrift:I', 'ParticlePacing:I'],
                           stdout=log, stderr=subprocess.DEVNULL)
    rounds = []
    try:
        pc.write_prefs(prefs_with(original, a.style))
        assert ('>%s<' % a.style) in pc.run_as('cat %s' % pc.PREFS)
        b.start('ThingListRemovalProbeActivity', run_id='touch-%s' % a.run, case='single')
        time.sleep(2.5)
        # 整屏尺寸在弹窗打开之前量：弹窗开着时 dump 可能只有弹窗窗口
        width, height = d.screen_size()
        for label in ('near', 'mid', 'far'):
            open_dialog()
            tree = d.dump()
            panel = panel_bounds(tree, width, height)
            cx = (panel[0] + panel[2]) // 2
            # 弹窗判定「点在外面」有窗口触摸容差（16 dp），贴近的那一点要离上沿超过它
            near, far = panel[1] - 130, int(height * .1)
            y = {'near': near, 'mid': (near + far) // 2, 'far': far}[label]
            rec = r.Recording(4)
            d.adb('shell', 'input', 'tap', cx, y)
            video = rec.finish(folder / ('%s.mp4' % label))
            rounds.append(dict(label=label, tap=(cx, y), panel=panel, screen=(width, height), video=str(video)))
            time.sleep(1.5)
            if d.find(d.dump(), text=('确定', 'OK')) is not None:
                raise RuntimeError('点弹窗外没有关掉弹窗：' + label)
    finally:
        cat.terminate()
        log.close()
        pc.write_prefs(original)
        restored = pc.run_as('cat %s' % pc.PREFS) == original
    text = (folder / 'logcat.txt').read_bytes().decode('utf-8', 'replace')
    lines = [line for line in re.findall(r'飘散首帧上屏[^\n]*', text) if '触点距离=无' not in line]
    for item, line in zip(rounds, lines):
        item['log'] = re.search(r'样式=\S+', line).group(0) + ' ' + re.search(r'触点距离=\S+ 风力=\S+', line).group(0)
    # 对照表：每行一个远近，按动画开始后的同一组时刻取帧
    rows = []
    for item in rounds:
        items = r.frames(item['video'], folder / (item['label'] + '-frames'), scale_width=SCALE_WIDTH)
        k = SCALE_WIDTH / item['screen'][0]
        x1, y1, x2, y2 = (int(v * k) for v in item['panel'])
        start = onset(items, (x1, y1, x2, y2))
        item['onset'] = start
        picks = []
        for offset in OFFSETS:
            want = (start or 0) + offset
            best = max((it for it in items if it[0] <= want), key=lambda it: it[0], default=items[0])
            picks.append((offset, best[1]))
        rows.append((item, picks, (int(item['tap'][0] * k), int(item['tap'][1] * k))))
    crop_bottom = int(max(item['panel'][3] for item in rounds) * SCALE_WIDTH / rounds[0]['screen'][0]) + 20
    first = Image.open(rows[0][1][0][1])
    crop_bottom = min(crop_bottom, first.height)
    cell_w, cell_h, pad = first.width, crop_bottom, 30
    sheet = Image.new('RGB', (cell_w * len(OFFSETS), (cell_h + pad) * len(rows)), (36, 36, 36))
    draw = ImageDraw.Draw(sheet)
    names = {'near': '贴近弹窗', 'mid': '中段', 'far': '贴近顶部'}
    for row, (item, picks, tap) in enumerate(rows):
        for col, (offset, path) in enumerate(picks):
            img = Image.open(path).convert('RGB').crop((0, 0, cell_w, cell_h))
            mark = ImageDraw.Draw(img)
            mark.ellipse((tap[0] - 7, tap[1] - 7, tap[0] + 7, tap[1] + 7), outline=(255, 64, 64), width=2)
            x, y = col * cell_w, row * (cell_h + pad)
            sheet.paste(img, (x, y + pad))
            draw.text((x + 6, y + 8), '%s +%.2fs' % (item['label'], offset), fill=(255, 255, 255))
    out = folder / 'touch-distance-sheet.png'
    sheet.save(out)
    (folder / 'result.json').write_text(json.dumps(dict(rounds=rounds, prefs_restored=restored,
                                                        names=names), ensure_ascii=False, indent=1), 'utf-8')
    for item in rounds:
        print(item['label'], item['tap'], item.get('log'), 'onset', item.get('onset'))
    print('sheet', out)
    print('prefs restored', restored)


if __name__ == '__main__':
    main()
