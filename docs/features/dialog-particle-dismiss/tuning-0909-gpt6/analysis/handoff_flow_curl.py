"""清晰可见颗粒的卷曲与流动对照（只作诊断）：原片与模型原始画面（不经录像模拟）逐帧对。

- 可见颗粒：`handoff_groups.Groups.mask` 口径（偏离背景与原位控件、与背景 Lab 色差 ≥22、σ=0.012 短边平滑后密度 ≥0.22）；
- 流场：相邻原片独立帧之间的 Farneback 光流，只取帧 A 的可见颗粒像素，按 σ = --sigma 短边做掩码归一化平滑（只在颗粒上求平均，不把背景的零速度混进来）；
  速度单位为短边 / 单位进度；
- 卷曲：旋度 ω = ∂v/∂x − ∂u/∂y（屏幕坐标换成 y 向上之后的逆时针为正），单位 1 / 单位进度；
- 逐时段（早 0.1～0.3 / 中 0.3～0.5 / 后 0.5～0.8）报：原片与模型各自在可见区的平均 |ω|、两者都可见处 ω 的相关系数与同号比例、流向夹角中位数、
  模型与原片的平均速度之比；
- 图：每个所选时刻一行，原片与各变体并排，底图压暗，可见区内按 ω 着色（红 = 逆时针，蓝 = 顺时针），叠加流线（白线）。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all
from handoff_groups import Groups

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
BINS = [(.1, .3, '早'), (.3, .5, '中'), (.5, .8, '后')]


def gray(f):
    return cv2.cvtColor(np.ascontiguousarray(np.asarray(f)[..., :3]), cv2.COLOR_RGB2GRAY)


def field(fa, fb, mask, dt, span, sigma):
    F = cv2.calcOpticalFlowFarneback(gray(fa), gray(fb), None, .5, 4, 15, 3, 5, 1.1, 0)
    m = mask.astype(np.float32)
    s = sigma * span
    den = cv2.GaussianBlur(m, (0, 0), s)
    u = cv2.GaussianBlur(F[..., 0] * m, (0, 0), s) / np.maximum(den, 1e-4) / span / dt
    v = -cv2.GaussianBlur(F[..., 1] * m, (0, 0), s) / np.maximum(den, 1e-4) / span / dt   # y 向上
    # 旋度：∂v/∂x − ∂u/∂y，y 向上时 ∂/∂y = −∂/∂row。导数按短边归一化。
    dvdx = cv2.Sobel(v, cv2.CV_32F, 1, 0, ksize=3) / 8 * span
    dudy = -cv2.Sobel(u, cv2.CV_32F, 0, 1, ksize=3) / 8 * span
    return u, v, dvdx - dudy, den


def streamlines(u, v, mask, step, length=14):
    lines = []
    H, W = mask.shape
    for y0 in range(step // 2, H, step):
        for x0 in range(step // 2, W, step):
            if not mask[y0, x0]: continue
            pts = [(x0, y0)]; x, y = float(x0), float(y0)
            for _ in range(length):
                xi, yi = int(round(x)), int(round(y))
                if not (0 <= xi < W and 0 <= yi < H) or not mask[yi, xi]: break
                du, dv = u[yi, xi], -v[yi, xi]
                n = math.hypot(du, dv)
                if n < 1e-6: break
                x += 2.5 * du / n; y += 2.5 * dv / n; pts.append((x, y))
            if len(pts) > 3: lines.append(pts)
    return lines


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体（@文件）')
    ap.add_argument('--sigma', type=float, default=.035)
    ap.add_argument('--at', type=float, nargs='+', default=[.2, .35, .5, .65])
    ap.add_argument('--scale', type=float, default=.42)
    ap.add_argument('--out', required=True, help='输出目录')
    a = ap.parse_args()
    out = Path(a.out); out.mkdir(parents=True, exist_ok=True)
    variants = []
    for v in a.variants:
        label, spec = v.split('=', 1); root = HERE
        if '::' in spec:
            d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
        if spec.startswith('@'): spec = Path(spec[1:]).read_text('utf-8').strip()
        variants.append((label, root, spec))
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 14)
    report = {}
    for name in a.scenes:
        c = Comparison(name); s = c.scene; G = Groups(s, 22.); prog = np.asarray(c.ref.progress, float); span = s.span
        seqs = [('原片', lambda q: np.asarray(c.ref.frames[q]))]
        for label, root, spec in variants:
            fr = render_all(name, root, label, spec=spec)
            seqs.append((label, lambda q, fr=fr: np.asarray(fr[round(float(np.clip(prog[q], 0, 1)) * 120)])))
        idx = [i for i in c.index if .08 <= prog[i] <= .82]
        pairs = [(idx[k], idx[k + 1]) for k in range(len(idx) - 1) if prog[idx[k + 1]] > prog[idx[k]]]
        acc = {lab: {t: dict(w=[], cos=[], sp=[]) for _, _, t in BINS} for lab, _ in seqs[1:]}
        absw = {lab: {t: [] for _, _, t in BINS} for lab, _ in seqs}
        pick = {}
        for want in a.at:
            k = int(np.argmin([abs(prog[i] - want) for i, _ in pairs])); pick[k] = want
        panels = {}
        for k, (i, j) in enumerate(pairs):
            p = float(prog[i]); dt = float(prog[j] - prog[i])
            tbin = next((t for lo, hi, t in BINS if lo <= p < hi), None)
            if tbin is None and k not in pick: continue
            res = {}
            for lab, get in seqs:
                fa, fb = get(i), get(j); m = G.mask(fa)
                if m.sum() < 400: res[lab] = None; continue
                u, vv, w, den = field(fa, fb, m, dt, span, a.sigma)
                res[lab] = (u, vv, w, m & (den > .2))
            o = res['原片']
            if o is not None and tbin is not None:
                absw['原片'][tbin].append(float(np.abs(o[2][o[3]]).mean()))
                for lab, _ in seqs[1:]:
                    mm = res[lab]
                    if mm is None: continue
                    absw[lab][tbin].append(float(np.abs(mm[2][mm[3]]).mean()))
                    both = o[3] & mm[3]
                    if both.sum() < 300: continue
                    wo, wm = o[2][both], mm[2][both]
                    acc[lab][tbin]['w'].append((float(np.corrcoef(wo, wm)[0, 1]), float((np.sign(wo) == np.sign(wm)).mean())))
                    uo, vo, um, vm = o[0][both], o[1][both], mm[0][both], mm[1][both]
                    no, nm = np.hypot(uo, vo) + 1e-6, np.hypot(um, vm) + 1e-6
                    acc[lab][tbin]['cos'].append(float(np.median((uo * um + vo * vm) / (no * nm))))
                    acc[lab][tbin]['sp'].append(float(np.median(nm) / np.median(no)))
            if k in pick:
                panels[k] = (p, res, np.asarray(c.ref.frames[i]))
        # 汇总
        rep = {}
        line = f'{name:22s}'
        for lab, _ in seqs[1:]:
            r = {}
            for _, _, t in BINS:
                ws = acc[lab][t]['w']
                r[t] = dict(abs_orig=float(np.mean(absw['原片'][t])) if absw['原片'][t] else float('nan'),
                            abs_model=float(np.mean(absw[lab][t])) if absw[lab][t] else float('nan'),
                            corr=float(np.mean([q[0] for q in ws])) if ws else float('nan'),
                            same_sign=float(np.mean([q[1] for q in ws])) if ws else float('nan'),
                            angle=float(np.degrees(np.arccos(np.clip(np.median(acc[lab][t]['cos']), -1, 1)))) if acc[lab][t]['cos'] else float('nan'),
                            speed=float(np.median(acc[lab][t]['sp'])) if acc[lab][t]['sp'] else float('nan'))
            rep[lab] = r
            line += f' | {lab}: ' + '  '.join(f'{t} |ω| {r[t]["abs_orig"]:.2f}/{r[t]["abs_model"]:.2f} 相关 {r[t]["corr"]:+.2f} 同号 {r[t]["same_sign"]:.2f} 夹角 {r[t]["angle"]:.0f}° 速比 {r[t]["speed"]:.2f}' for _, _, t in BINS)
        print(line, flush=True)
        report[name] = rep
        # 图
        x, y, x1, y1 = s.meta['rect']; fw, fh = s.meta['frame']; pad = round(min(x1 - x, y1 - y) * .4)
        bx0, by0, bx1, by1 = max(0, x - pad), max(0, y - pad), min(fw, x1 + pad), min(fh, y1 + pad)
        w_ = round((bx1 - bx0) * a.scale); h_ = round((by1 - by0) * a.scale)
        ncol = len(seqs); rows = sorted(panels)
        sheet = Image.new('RGB', (ncol * (w_ + 6), len(rows) * (h_ + 22)), (14, 20, 30)); dr = ImageDraw.Draw(sheet)
        for r_, k in enumerate(rows):
            p, res, base = panels[k]
            for col, (lab, _) in enumerate(seqs):
                im = (np.asarray(base)[..., :3].astype(np.float32) * .3).astype(np.uint8).copy()
                if res.get(lab) is not None:
                    u, vv, w, m = res[lab]
                    lim = 6.
                    t_ = np.clip(w / lim, -1, 1)
                    col_ = np.zeros(im.shape, np.float32)
                    col_[..., 0] = np.clip(t_, 0, 1) * 255; col_[..., 2] = np.clip(-t_, 0, 1) * 255; col_[..., 1] = 40
                    im[m] = (im[m] * .3 + col_[m] * .7).astype(np.uint8)
                    for pts in streamlines(u, vv, m, max(8, round(.05 * span))):
                        cv2.polylines(im, [np.round(np.array(pts)).astype(np.int32)], False, (235, 235, 235), 1, cv2.LINE_AA)
                cv2.rectangle(im, (x, y), (x1, y1), (180, 180, 60), 1)
                pim = Image.fromarray(im[by0:by1, bx0:bx1]).resize((w_, h_), Image.Resampling.LANCZOS)
                sheet.paste(pim, (col * (w_ + 6), r_ * (h_ + 22) + 20))
                dr.text((col * (w_ + 6) + 3, r_ * (h_ + 22) + 2), f'{lab} {p:.2f}（红=逆时针 蓝=顺时针）', font=font, fill='white')
        sheet.save(out / f'curl-{name}.jpg', quality=90)
    (out / 'flow-curl.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
