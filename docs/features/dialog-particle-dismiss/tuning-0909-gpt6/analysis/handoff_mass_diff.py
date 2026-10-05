"""颗粒质量场的带符号差分图（只作诊断）：同一进度原片与模型的颗粒质量场相减，看颗粒群形状差在哪里。

质量场同 `handoff_cloud_mass.Mass.field`（颗粒掩码上与背景的 Lab 距离，σ=6 模糊）。模型帧缺省先按原片码率编码
（`recording_codec.coded`），与原片同一录像口径。每个进度一行：原片 | 模型 | 差分（红 = 模型多出的颗粒质量，
蓝 = 原片有而模型缺的），差分底图为原片的灰度。另报两边质量沿风向 5%/95% 分位与横风 5%～95% 展宽（短边）。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from handoff_cloud_mass import Mass
from recording_codec import coded


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('scene')
    ap.add_argument('--variant', required=True, help='标签=[配置目录::]变体（@文件 读取配置）')
    ap.add_argument('--at', type=float, nargs='+', default=[.2, .3, .4, .5, .6, .7])
    ap.add_argument('--raw', action='store_true', help='模型帧不过编码')
    ap.add_argument('--scale', type=float, default=.5)
    ap.add_argument('--gain', type=float, default=18., help='差分满色对应的质量差')
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    label, spec = a.variant.split('=', 1); root = HERE
    if '::' in spec:
        d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
    if spec.startswith('@'): spec = Path(spec[1:]).read_text('utf-8').strip()
    c = Comparison(a.scene); s = c.scene
    ang = math.radians(s.meta['direction']); wind = np.array([math.cos(ang), -math.sin(ang)]); cross = np.array([-wind[1], wind[0]])
    mass = Mass(s, wind, cross)
    if a.raw:
        frames = render_all(a.scene, root, label, spec=spec); pick = lambda i: np.asarray(frames[round(float(c.ref.progress[i]) * 120)])
    else:
        cf = coded(a.scene, label, spec, root=root, verbose=False)[0]; pick = lambda i: np.asarray(cf[i])
    x, y, x1, y1 = s.meta['rect']; fw, fh = s.meta['frame']; pad = round(min(x1 - x, y1 - y) * .45)
    bx0, by0, bx1, by1 = max(0, x - pad), max(0, y - pad), min(fw, x1 + pad), min(fh, y1 + pad)
    w = round((bx1 - bx0) * a.scale); h = round((by1 - by0) * a.scale)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 14)
    sheet = Image.new('RGB', (3 * (w + 4), len(a.at) * (h + 22)), (14, 20, 30)); dr = ImageDraw.Draw(sheet)
    report = []
    for r, p in enumerate(a.at):
        i, _ = c.ref.nearest(p); pp = float(c.ref.progress[i])
        fo = np.asarray(c.ref.frames[i]); fm = pick(i)
        mo = mass.field(fo); mm = mass.field(fm)
        go = mass.geometry(mo); gm = mass.geometry(mm)
        row = dict(p=pp)
        for tag, g in (('orig', go), ('model', gm)):
            row[tag] = None if g is None else dict(back=g['back'], front=g['front'], width=g['width'])
        report.append(row)
        gray = cv2.cvtColor(fo, cv2.COLOR_RGB2GRAY).astype(np.float32) * .45
        diff = np.clip((mm - mo) / a.gain, -1, 1)
        vis = np.stack([gray + 255 * np.clip(diff, 0, 1), gray, gray + 255 * np.clip(-diff, 0, 1)], -1)
        vis = np.clip(vis, 0, 255).astype(np.uint8)
        Y = r * (h + 22)
        for k, (img, name) in enumerate([(fo, '原片'), (fm, label), (vis, '差分 红=模型多 蓝=模型缺')]):
            im = Image.fromarray(np.ascontiguousarray(img[by0:by1, bx0:bx1])).resize((w, h), Image.Resampling.LANCZOS)
            g = ImageDraw.Draw(im)
            g.rectangle([(x - bx0) * a.scale, (y - by0) * a.scale, (x1 - bx0) * a.scale, (y1 - by0) * a.scale], outline=(255, 220, 0), width=1)
            sheet.paste(im, (k * (w + 4), Y + 20))
            txt = f'{name} {pp:.3f}'
            if k < 2:
                g0 = go if k == 0 else gm
                if g0: txt += f'  后{g0["back"]:.2f} 前{g0["front"]:.2f} 宽{g0["width"]:.2f}'
            dr.text((k * (w + 4) + 3, Y + 2), txt, font=font, fill='white')
        print(f'{a.scene} {pp:.3f} ' + '  '.join(f'{t}: ' + ('-' if row[t] is None else ' '.join(f'{k} {v:.3f}' for k, v in row[t].items())) for t in ('orig', 'model')), flush=True)
    sheet.save(a.out, quality=92)
    Path(a.out).with_suffix('.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')
    print(a.out)


if __name__ == '__main__':
    main()
