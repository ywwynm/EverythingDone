"""离线核查仿射时间对齐；拟合输入不进入实时算法。

输入扫描拟合的是原位纹理完整度的消失时刻，不是真实粒子出生时刻。
这里只检验先前遗漏截距的影响，不能据此声称恢复了录像真实时钟。
"""
from dataclasses import replace
from pathlib import Path
import argparse
import json
import math
import sys

import moderngl
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'analysis')]
from harmony_core import HarmonyCoreRenderer, DEFAULT
from harmony_adapted import HarmonyAdaptedRenderer, AdaptedRules
from match_refs import RefFrames


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--inputs', type=Path, required=True)
    ap.add_argument('--out', type=Path, required=True)
    ap.add_argument('--family', choices=['clock', 'life', 'coupled'], default='clock')
    ap.add_argument('--scenes', nargs='+', default=[
        'ironman', 'ironman-up-reference', 'reference-0926-5', 'reference-0926-7'])
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    inputs = json.loads(args.inputs.read_text('utf-8'))
    ctx = moderngl.create_standalone_context(require=430)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 17)
    report = dict(renderer=ctx.info['GL_RENDERER'], visual_acceptance=False, scenes={})
    for name in args.scenes:
        q = inputs[name]['candidates'][0]
        ref = RefFrames(name)
        m = ref.meta
        span = 2. / (DEFAULT.rate * q['clock_slope'])
        # fitted T = slope * threshold + intercept。
        # 测量若对应 mask alpha=.5，原生阈值需减去 .15/2；两种口径并列验证。
        start = -q['clock_intercept'] * span
        middle_start = start - 2. / DEFAULT.rate * .075
        wind_x = DEFAULT.wind_y / math.tan(math.radians(m['direction']))
        variants = [
            ('仅斜率', HarmonyCoreRenderer, 0., 1., None),
            ('完整仿射', HarmonyCoreRenderer, start, 1., None),
            ('半透明口径', HarmonyCoreRenderer, middle_start, 1., None),
            ('仿射＋共同候选', HarmonyAdaptedRenderer, middle_start, 1., None),
        ]
        if args.family == 'life':
            variants = [
                ('共同候选 · 原寿命', HarmonyAdaptedRenderer, middle_start, 1., AdaptedRules()),
                ('寿命×1.5', HarmonyAdaptedRenderer, middle_start, 1.5, AdaptedRules()),
                ('×1.5 · 独立尺寸', HarmonyAdaptedRenderer, middle_start, 1.5,
                 AdaptedRules(growth_seconds=.06, fade_seconds=.15)),
                ('×2 · 独立尺寸', HarmonyAdaptedRenderer, middle_start, 2.,
                 AdaptedRules(growth_seconds=.06, fade_seconds=.15)),
            ]
        if args.family == 'coupled':
            variants = [
                ('仅延寿×1.5', HarmonyAdaptedRenderer, middle_start, 1.5,
                 AdaptedRules(growth_seconds=.06, fade_seconds=.15)),
                ('配合运动时间', HarmonyAdaptedRenderer, middle_start, 1.5,
                 AdaptedRules(growth_seconds=.06, fade_seconds=.15, motion_time_scale=2/3)),
                ('时间配合 · 相位.20', HarmonyAdaptedRenderer, middle_start, 1.5,
                 AdaptedRules(growth_seconds=.06, fade_seconds=.15, motion_time_scale=2/3, phase_spread=.20)),
                ('时间配合 · 相位.35', HarmonyAdaptedRenderer, middle_start, 1.5,
                 AdaptedRules(growth_seconds=.06, fade_seconds=.15, motion_time_scale=2/3, phase_spread=.35)),
            ]
        rs = []
        for title, cls, offset, life_gain, rules in variants:
            # 留出结束余量，避免核心版在中途因进度钳位而强制清场。
            stop = max(span + offset, span) + .05
            config = replace(DEFAULT, native_scale=q['scale'], stop_time=stop,
                             lifetime=tuple(v*life_gain for v in DEFAULT.lifetime),
                             count=40000 if cls is HarmonyAdaptedRenderer else DEFAULT.count)
            extra = {'rules': rules} if rules else {}
            r = cls(name, ctx=ctx, config=config, random_value=q['random'], wind_x=wind_x, **extra)
            if args.family != 'clock':
                r.compute['flow_offset'] = (0., 0., 0.)
                r.flow_offset = (0., 0., 0.)
            rs.append((title, r, offset, stop))
        x, y, x1, y1 = m['rect']
        box = (max(0, x-50), max(0, y-80), min(m['frame'][0], x1+50), min(m['frame'][1], y1+40))
        rows = []
        states = []
        for p in [.18, .30, .42, .54, .66, .78]:
            frames = [ref.nearest(p)[1]]
            for title, r, offset, stop in rs:
                seconds = max(0., span*p+offset)
                frames.append(r.render(seconds/stop))
                state = r.diagnostics()
                assert state['finite'], (name, title, p)
                states.append(dict(variant=title, progress=p, seconds=seconds, **state))
            tiles = []
            for title, frame in zip(['华为原片']+[v[0] for v in variants], frames):
                im = Image.fromarray(frame).crop(box)
                im.thumbnail((300, 360))
                tile = Image.new('RGB', (300, im.height+28), '#10151d')
                tile.paste(im, (0, 28))
                ImageDraw.Draw(tile).text((4, 2), f'{title} · {p:.2f}', font=font, fill='white')
                tiles.append(tile)
            row = Image.new('RGB', (1500, max(t.height for t in tiles)), '#10151d')
            for col, tile in enumerate(tiles):
                row.paste(tile, (col*300, 0))
            rows.append(row)
        for half, selected in [('early', rows[:3]), ('late', rows[3:])]:
            sheet = Image.new('RGB', (1500, sum(row.height for row in selected)), '#10151d')
            yy = 0
            for row in selected:
                sheet.paste(row, (0, yy))
                yy += row.height
            sheet.save(args.out/f'{args.family}-{name}-{half}.jpg', quality=94)
        report['scenes'][name] = dict(fitted_input=q, span=span, start=start,
                                     middle_start=middle_start, states=states,
                                     variants={title:r.describe() for title,r,_,_ in rs})
        (args.out/f'{args.family}-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), 'utf-8')
        for _, r, _, _ in rs:
            r.close()
        print(name, '完成仿射时钟对照', flush=True)
    ctx.release()


if __name__ == '__main__':
    main()
