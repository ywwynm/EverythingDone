"""把 lift_card_check.py 的逐条结果拼成两张总览图：普通卡片一张、通栏卡片一张。每一对左边是首页卡片，右边是
抬起的卡片，两边按原像素并排（字号应当一样大），上方标样例写法、抬起后的尺寸与缩放，以及 TapLift 日志里的
删减情况（不删减时的高度、正文行数或清单条数减到几、封面压到多高）。

用法：python lift_card_sheet.py <lift_card_check 的输出目录>
"""
import json
import os
import re
import sys

from PIL import Image, ImageDraw, ImageFont

out_dir = sys.argv[1]
with open(os.path.join(out_dir, 'result.json'), encoding='utf-8') as f:
    data = json.load(f)
rows = {r['k']: r for r in data['results']}
font = ImageFont.truetype(r'C:\Windows\Fonts\msyh.ttc', 30)
GAP = 24
LABEL = 96

# TapLift 日志按出现的先后与结果逐条对应（逐条核对从最后一条往前，日志也是这个顺序）
fits = []
log_path = os.path.join(out_dir, 'logcat.txt')
if os.path.exists(log_path):
    with open(log_path, 'rb') as f:
        for line in f.read().decode('utf-8', 'replace').splitlines():
            m = re.search(r'fit \d+ width=(\d+) max=(\d+) natural=(\d+) textCap=(\w+) coverCap=(\w+) height=(\d+) ms=(\d+)',
                          line)
            if m:
                fits.append(m.groups())
order = [r['k'] for r in data['results']]
fit_of = {k: fits[i] for i, k in enumerate(order) if i < len(fits)}


def tile(k):
    r = rows[k]
    pair = Image.open(os.path.join(out_dir, 'pair-%02d.png' % k)).convert('RGB')
    t = Image.new('RGB', (pair.width, pair.height + LABEL), (245, 245, 245))
    lifted = r['lifted']
    text = '%d  %s  首页 %d×%d → 抬起 %d×%d ×%.2f' % (k, r['spec'], r['home'][0], r['home'][1],
                                                  lifted['size'][0], lifted['size'][1], lifted['scale'])
    fit = fit_of.get(k)
    detail = ''
    if fit:
        width, max_h, natural, text_cap, cover_cap, height, ms = fit
        parts = ['上限 %s' % max_h, '不删减 %s' % natural]
        if text_cap != 'null':
            parts.append('文字减到 %s' % text_cap)
        if cover_cap != 'null':
            parts.append('封面压到 %s' % cover_cap)
        parts.append('%s ms' % ms)
        detail = '，'.join(parts)
    if not r['home_complete']:
        detail += '（首页那张只露出一部分）'
    draw = ImageDraw.Draw(t)
    draw.text((8, 6), text, fill=(20, 20, 20), font=font)
    draw.text((8, 50), detail, fill=(90, 90, 90), font=font)
    t.paste(pair, (0, LABEL))
    return t


def sheet(ks, name, max_width=3600):
    if not ks:
        return
    tiles = [tile(k) for k in ks]
    lines, line, width = [], [], 0
    for t in tiles:
        if line and width + t.width > max_width:
            lines.append(line)
            line, width = [], 0
        line.append(t)
        width += t.width + 40
    lines.append(line)
    total_w = max(sum(t.width for t in l) + 40 * (len(l) - 1) for l in lines)
    total_h = sum(max(t.height for t in l) for l in lines) + 40 * (len(lines) - 1)
    total = Image.new('RGB', (total_w, total_h), (190, 190, 190))
    y = 0
    for l in lines:
        x = 0
        for t in l:
            total.paste(t, (x, y))
            x += t.width + 40
        y += max(t.height for t in l) + 40
    path = os.path.join(out_dir, name)
    total.save(path)
    print(path, total.size)


def is_full(k):
    spec = rows[k]['spec']
    return '!' in spec or spec.split('@')[0].split('+')[0] in ('left', 'right', 'wide')


ks = sorted(rows)
sheet([k for k in ks if not is_full(k)], 'sheet-normal.png')
sheet([k for k in ks if is_full(k)], 'sheet-full.png')
