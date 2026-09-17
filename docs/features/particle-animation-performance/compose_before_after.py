"""把修前 / 修后两套对照帧拼成并排图，并在图上标出场景、进度与哪边是哪边。

前提是两边用**同一个裁剪框**：`extract_baseline_frames.py --crops <修前的 frames.json>`
会把修前那一轮的 crop 原样用在修后的录屏上，不然放大块框的位置不同，比的就不是同一处。

用法：
    python compose_before_after.py --before <修前目录> --after <修后目录> --out <输出目录>
"""
import argparse
import json
from pathlib import Path

from PIL import Image, ImageDraw

GAP = 16
BAR = 34
BG = (18, 18, 20)
FG = (236, 236, 240)


def label(draw, x, y, text):
    draw.text((x + 8, y + 9), text, fill=FG)


def compose(before, after, out, title):
    left = Image.open(before).convert('RGB')
    right = Image.open(after).convert('RGB')
    height = max(left.height, right.height)
    width = left.width + right.width + GAP
    canvas = Image.new('RGB', (width, height + BAR * 2), BG)
    canvas.paste(left, (0, BAR))
    canvas.paste(right, (left.width + GAP, BAR))
    draw = ImageDraw.Draw(canvas)
    label(draw, 0, 0, title)
    label(draw, 0, height + BAR, 'before  ' + before.name)
    label(draw, left.width + GAP, height + BAR, 'after  ' + after.name)
    canvas.save(out)


def pairs(folder, out, zoom):
    """离屏受控 A/B 的落盘：同一目录下 `<label>-<frame>-{before,after}-{full,crop}.png`。

    同一份材料、同一个种子、同一台设备，只切一个开关——这才是能逐像素比的那种对照；
    真机录屏拍的是不同记事（背景色与随机方向都不同），只能看观感，不能逐像素比。
    """
    folder = Path(folder)
    out.mkdir(parents=True, exist_ok=True)
    made = []
    for before in sorted(folder.glob('*-before-*.png')):
        after = folder / before.name.replace('-before-', '-after-')
        if not after.is_file():
            continue
        target = out / before.name.replace('-before-', '-')
        if before.name.endswith('-crop.png') and zoom > 1:
            tmp = []
            for src in (before, after):
                image = Image.open(src).convert('RGB')
                scaled = image.resize((image.width * zoom, image.height * zoom), Image.NEAREST)
                path = out / (src.stem + '-x%d.png' % zoom)
                scaled.save(path)
                tmp.append(path)
            # 标签一律 ASCII：PIL 的默认位图字体没有 CJK 字形，中文会画成方块。
            compose(tmp[0], tmp[1], target, before.name.replace('-before-crop.png', '') +
                    '  x%d zoom  (centred on this frame largest-difference pixel)' % zoom)
            for path in tmp:
                path.unlink()
        else:
            compose(before, after, target, before.name.replace('-before-full.png', '') + '  full frame')
        made.append(str(target))
    return made


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--before', default='')
    parser.add_argument('--after', default='')
    parser.add_argument('--pairs', default='', help='离屏受控 A/B 的落盘目录')
    parser.add_argument('--zoom', type=int, default=4)
    parser.add_argument('--out', required=True)
    args = parser.parse_args()
    if args.pairs:
        for path in pairs(args.pairs, Path(args.out), args.zoom):
            print(path)
        return
    before = Path(args.before)
    after = Path(args.after)
    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    report = json.loads((after / 'frames.json').read_text(encoding='utf-8'))
    made = []
    for scene in sorted(report):
        for pct in (10, 50, 90):
            for kind in ('full', 'zoom'):
                name = '%s-%02d-%s.png' % (scene, pct, kind)
                a, b = before / name, after / name
                if not (a.is_file() and b.is_file()):
                    continue
                target = out / name
                compose(a, b, target, '%s  %d%%  %s' % (scene, pct, kind))
                made.append(str(target))
    for path in made:
        print(path)
    print('共', len(made), '张')


if __name__ == '__main__':
    main()
