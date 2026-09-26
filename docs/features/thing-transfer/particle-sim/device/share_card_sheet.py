"""把 share_card_check.py 的逐条结果拼成两张总览图：普通卡片一张、通栏卡片一张。每一对左边是首页卡片，右边是
抬起的卡片（按匹配到的位置从截屏里取出，缩放回首页那张的尺寸），上方标样例写法与数据。

用法：python share_card_sheet.py <share_card_check 的输出目录>
"""
import json
import os
import sys

from PIL import Image, ImageDraw, ImageFont

out_dir = sys.argv[1]
with open(os.path.join(out_dir, 'result.json'), encoding='utf-8') as f:
    data = json.load(f)
specs = data['samples'].split(',')
rows = {r['k']: r for r in data['results']}
font = ImageFont.truetype(r'C:\Windows\Fonts\msyh.ttc', 24)
GAP = 16
LABEL = 44


def tile(k, height):
    r = rows[k]
    home = Image.open(os.path.join(out_dir, 'home-card-%02d.png' % k)).convert('RGB')
    shot = Image.open(os.path.join(out_dir, 'lift-%02d.png' % k)).convert('RGB')
    x, y, w, h = r['lifted_at']
    lifted = shot.crop((x, y, x + w, y + h)).resize(home.size, Image.LANCZOS)
    scale = height / home.height
    size = (max(1, int(home.width * scale)), height)
    home, lifted = home.resize(size, Image.LANCZOS), lifted.resize(size, Image.LANCZOS)
    t = Image.new('RGB', (size[0] * 2 + GAP, height + LABEL), (245, 245, 245))
    text = '%d  %s  抬起×%.2f  匹配 %.3f%s' % (k, specs[k - 1], r['scale'], r['score'],
                                            '' if r['complete'] else '  首页只露出一部分')
    ImageDraw.Draw(t).text((6, 8), text, fill=(20, 20, 20), font=font)
    t.paste(home, (0, LABEL))
    t.paste(lifted, (size[0] + GAP, LABEL))
    return t


def sheet(ks, height, per_row, name):
    if not ks:
        return
    tiles = [tile(k, height) for k in ks]
    lines = [tiles[i:i + per_row] for i in range(0, len(tiles), per_row)]
    width = max(sum(t.width for t in line) + 40 * (len(line) - 1) for line in lines)
    total = Image.new('RGB', (width, sum(max(t.height for t in line) for line in lines) + 40 * (len(lines) - 1)),
                      (190, 190, 190))
    y = 0
    for line in lines:
        x = 0
        for t in line:
            total.paste(t, (x, y))
            x += t.width + 40
        y += max(t.height for t in line) + 40
    path = os.path.join(out_dir, name)
    total.save(path)
    print(path, total.size)


def is_full(k):
    return specs[k - 1].endswith('!') or specs[k - 1].split('@')[0].split('/')[0] in ('left', 'right', 'wide')


ks = sorted(rows)
sheet([k for k in ks if not is_full(k)], 760, 4, 'sheet-normal.png')
sheet([k for k in ks if is_full(k)], 420, 2, 'sheet-full.png')
