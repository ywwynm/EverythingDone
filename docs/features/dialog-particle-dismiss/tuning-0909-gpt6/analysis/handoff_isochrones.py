"""可见颗粒群的等时轮廓（只作诊断）：同一时间窗内每个原片独立帧的可见颗粒群轮廓按时间着色叠在一张图上，原片与模型并排。

可见颗粒群的口径同 `handoff_groups.Groups.mask`（偏离背景与原位控件、与背景 Lab 色差 ≥22、σ=0.012 短边平滑后密度 ≥0.22）。
轮廓颜色从蓝（窗口起点）到红（窗口终点），底图是窗口起点那一帧的原片、压暗到 35%。
一条轮廓整体平移是颗粒群在走；轮廓一侧外扩、一侧不动是颗粒群在长；同心收缩是颗粒群在变稀或消失。
模型帧缺省按原片码率编码（`recording_codec.coded`）。
"""
from pathlib import Path
import argparse, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all
from recording_codec import coded
from handoff_groups import Groups


def hue(k, n):
    # 蓝 → 青 → 绿 → 黄 → 红（OpenCV HSV 的色相 120 → 0）。
    h = int(round(120 * (1 - k / max(n - 1, 1))))
    c = cv2.cvtColor(np.uint8([[[h, 255, 255]]]), cv2.COLOR_HSV2RGB)[0, 0]
    return tuple(int(x) for x in c)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('scene')
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体（@文件）')
    ap.add_argument('--windows', nargs='+', default=['.08:.28', '.28:.48', '.48:.8'], help='起:止 进度')
    ap.add_argument('--frames', type=int, default=7, help='每个窗口取的独立帧数')
    ap.add_argument('--contrast', type=float, default=22.)
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--scale', type=float, default=.5)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    c = Comparison(a.scene); s = c.scene; G = Groups(s, a.contrast)
    prog = np.asarray(c.ref.progress, float)
    seqs = [('原片', lambda i: np.asarray(c.ref.frames[i]))]
    for v in a.variants:
        label, spec = v.split('=', 1); root = HERE
        if '::' in spec:
            d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
        if spec.startswith('@'): spec = Path(spec[1:]).read_text('utf-8').strip()
        if a.raw:
            fr = render_all(a.scene, root, label, spec=spec)
            seqs.append((label, lambda i, fr=fr: np.asarray(fr[round(float(np.clip(prog[i], 0, 1)) * 120)])))
        else:
            cf = coded(a.scene, label, spec, root=root, verbose=False)[0]
            seqs.append((label, lambda i, cf=cf: np.asarray(cf[i])))
    x, y, x1, y1 = s.meta['rect']; fw, fh = s.meta['frame']; pad = round(min(x1 - x, y1 - y) * .45)
    bx0, by0, bx1, by1 = max(0, x - pad), max(0, y - pad), min(fw, x1 + pad), min(fh, y1 + pad)
    w = round((bx1 - bx0) * a.scale); h = round((by1 - by0) * a.scale)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 14)
    ncol = len(seqs); nrow = len(a.windows)
    sheet = Image.new('RGB', (ncol * (w + 6), nrow * (h + 24)), (14, 20, 30)); dr = ImageDraw.Draw(sheet)
    for r, win in enumerate(a.windows):
        p0, p1 = (float(q) for q in win.split(':'))
        idx = [i for i in c.index if p0 <= prog[i] <= p1]
        if len(idx) > a.frames:
            idx = [idx[round(k)] for k in np.linspace(0, len(idx) - 1, a.frames)]
        base = np.asarray(c.ref.frames[idx[0]]).astype(np.float32) * .35
        for col, (label, get) in enumerate(seqs):
            im = np.ascontiguousarray(base.astype(np.uint8)).copy()
            for k, i in enumerate(idx):
                m = G.mask(get(i)).astype(np.uint8)
                cs, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
                cs = [q for q in cs if cv2.contourArea(q) >= (.03 * s.span) ** 2]
                cv2.drawContours(im, cs, -1, hue(k, len(idx)), 2, cv2.LINE_AA)
            cv2.rectangle(im, (x, y), (x1, y1), (200, 200, 200), 1)
            pim = Image.fromarray(im[by0:by1, bx0:bx1]).resize((w, h), Image.Resampling.LANCZOS)
            sheet.paste(pim, (col * (w + 6), r * (h + 24) + 22))
            dr.text((col * (w + 6) + 3, r * (h + 24) + 3),
                    f'{label}  {prog[idx[0]]:.2f}→{prog[idx[-1]]:.2f}（蓝→红，{len(idx)} 帧）', font=font, fill='white')
    sheet.save(a.out, quality=92)
    print(a.out)


if __name__ == '__main__':
    main()
