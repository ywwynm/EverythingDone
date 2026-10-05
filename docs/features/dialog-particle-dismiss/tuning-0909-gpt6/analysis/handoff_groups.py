"""逐个颗粒群对照（只作诊断）：只用清晰可见的颗粒，把画面分成若干颗粒群，原片与模型逐群配对比较轨迹与形状。

用户 2026-10-01：看不见、看不清的颗粒组成的形状不算；多个消逝起点各自扩散出的颗粒群并不始终相连，要逐群对照。
- 可见颗粒：`Scene.observe` 的 particle（偏离背景且偏离原位控件），且与背景的 Lab 色差 ≥ --contrast（缺省 22）；
- 成片：可见掩码按 σ = 0.012 短边平滑，密度 ≥ 0.22 的区域才算颗粒群，孤立淡点不计；
- 颗粒群：连通区域，面积 ≥ (0.06 短边)²；原片与模型按质心距离做匈牙利配对（距离上限 0.35 短边），
  报每群质心偏移（短边）、面积比（模型 / 原片）、两群掩码交并比，以及只在一边出现的群。
模型帧缺省按原片码率编码（`recording_codec.coded`）。输出逐时刻叠加图：原片群轮廓绿、模型群轮廓品红，底图为原片。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, cv2
from PIL import Image, ImageDraw, ImageFont
from scipy.optimize import linear_sum_assignment

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from recording_codec import coded


def lab(f):
    return cv2.cvtColor(np.clip(np.asarray(f), 0, 255).astype(np.uint8), cv2.COLOR_RGB2LAB).astype(np.float32)


class Groups:
    def __init__(self, scene, contrast):
        self.s = scene; self.bgl = lab(scene.bg); self.contrast = contrast; self.span = scene.span

    def mask(self, frame):
        par = self.s.observe(frame)['particle'] & self.s.roi
        de = np.linalg.norm(lab(frame) - self.bgl, axis=2)
        vis = (par & (de >= self.contrast)).astype(np.float32)
        d = cv2.GaussianBlur(vis, (0, 0), .012 * self.span)
        return d >= .22

    def split(self, m):
        n, lbl, st, cen = cv2.connectedComponentsWithStats(m.astype(np.uint8), 8)
        out = []
        amin = (.06 * self.span) ** 2
        for k in range(1, n):
            if st[k, cv2.CC_STAT_AREA] < amin: continue
            out.append(dict(mask=lbl == k, area=float(st[k, cv2.CC_STAT_AREA]), cx=float(cen[k][0]), cy=float(cen[k][1])))
        return out


def match(ga, gb, span, dmax=.35):
    if not ga or not gb: return [], list(range(len(ga))), list(range(len(gb)))
    C = np.array([[math.hypot(a['cx'] - b['cx'], a['cy'] - b['cy']) / span for b in gb] for a in ga])
    r, c = linear_sum_assignment(C)
    pairs = [(i, j) for i, j in zip(r, c) if C[i, j] <= dmax]
    ua = [i for i in range(len(ga)) if i not in {p[0] for p in pairs}]
    ub = [j for j in range(len(gb)) if j not in {p[1] for p in pairs}]
    return pairs, ua, ub


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('scene')
    ap.add_argument('--variant', required=True, help='标签=[配置目录::]变体（@文件）')
    ap.add_argument('--at', type=float, nargs='+', default=[.15, .25, .35, .45, .55, .65, .75])
    ap.add_argument('--contrast', type=float, default=22.)
    ap.add_argument('--raw', action='store_true')
    ap.add_argument('--scale', type=float, default=.45)
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    label, spec = a.variant.split('=', 1); root = HERE
    if '::' in spec:
        d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
    if spec.startswith('@'): spec = Path(spec[1:]).read_text('utf-8').strip()
    c = Comparison(a.scene); s = c.scene; G = Groups(s, a.contrast)
    if a.raw:
        fr = render_all(a.scene, root, label, spec=spec); pick = lambda i: np.asarray(fr[round(float(c.ref.progress[i]) * 120)])
    else:
        cf = coded(a.scene, label, spec, root=root, verbose=False)[0]; pick = lambda i: np.asarray(cf[i])
    x, y, x1, y1 = s.meta['rect']; fw, fh = s.meta['frame']; pad = round(min(x1 - x, y1 - y) * .45)
    bx0, by0, bx1, by1 = max(0, x - pad), max(0, y - pad), min(fw, x1 + pad), min(fh, y1 + pad)
    w = round((bx1 - bx0) * a.scale); h = round((by1 - by0) * a.scale)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 13)
    per = 4; nrow = (len(a.at) + per - 1) // per
    sheet = Image.new('RGB', (per * (2 * w + 8), nrow * (h + 36)), (14, 20, 30)); dr = ImageDraw.Draw(sheet)
    report = []
    for k, p in enumerate(a.at):
        i, _ = c.ref.nearest(p); pp = float(c.ref.progress[i])
        fo = np.asarray(c.ref.frames[i]); fm = pick(i)
        mo = G.mask(fo); mm = G.mask(fm); go = G.split(mo); gm = G.split(mm)
        pairs, ua, ub = match(go, gm, s.span)
        rows = []
        for ia, ib in pairs:
            A, Bm = go[ia], gm[ib]
            inter = float((A['mask'] & Bm['mask']).sum()); uni = float((A['mask'] | Bm['mask']).sum())
            rows.append(dict(dx=(Bm['cx'] - A['cx']) / s.span, dy=(Bm['cy'] - A['cy']) / s.span, area=Bm['area'] / A['area'], iou=inter / max(uni, 1.)))
        tot_o = float(mo.sum()); tot_m = float(mm.sum())
        report.append(dict(p=pp, n_orig=len(go), n_model=len(gm), pairs=rows, only_orig=[go[j]['area'] / s.span ** 2 for j in ua],
                           only_model=[gm[j]['area'] / s.span ** 2 for j in ub], iou_all=float((mo & mm).sum() / max((mo | mm).sum(), 1)),
                           area_ratio=tot_m / max(tot_o, 1.)))
        txt = '  '.join(f'{r["dx"]:+.2f},{r["dy"]:+.2f} ×{r["area"]:.2f} IoU{r["iou"]:.2f}' for r in rows)
        print(f'{a.scene} {pp:.3f} 原片 {len(go)} 群 / 模型 {len(gm)} 群  整体可见交并比 {report[-1]["iou_all"]:.2f} 面积比 {report[-1]["area_ratio"]:.2f} | {txt}'
              + (f' | 仅原片 {len(ua)}' if ua else '') + (f' | 仅模型 {len(ub)}' if ub else ''), flush=True)
        X = (k % per) * (2 * w + 8); Y = (k // per) * (h + 36)
        for col, (img, gs, tag) in enumerate([(fo, go, '原片'), (fm, gm, label)]):
            im = np.ascontiguousarray(img).copy()
            for g_ in go:
                cs, _ = cv2.findContours(g_['mask'].astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
                cv2.drawContours(im, cs, -1, (60, 255, 90), 2)
            for g_ in gm:
                cs, _ = cv2.findContours(g_['mask'].astype(np.uint8), cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_NONE)
                cv2.drawContours(im, cs, -1, (255, 60, 220), 2)
            pim = Image.fromarray(im[by0:by1, bx0:bx1]).resize((w, h), Image.Resampling.LANCZOS)
            g = ImageDraw.Draw(pim)
            g.rectangle([(x - bx0) * a.scale, (y - by0) * a.scale, (x1 - bx0) * a.scale, (y1 - by0) * a.scale], outline=(255, 220, 0), width=1)
            sheet.paste(pim, (X + col * (w + 4), Y + 34))
            dr.text((X + col * (w + 4) + 3, Y + 16), f'{tag} {pp:.3f}（绿=原片群 品红=模型群）', font=font, fill='white')
        dr.text((X + 3, Y + 1), f'可见交并比 {report[-1]["iou_all"]:.2f}  面积比 {report[-1]["area_ratio"]:.2f}  群 {len(go)}/{len(gm)}', font=font, fill=(255, 230, 120))
    sheet.save(a.out, quality=92)
    Path(a.out).with_suffix('.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')
    print(a.out)


if __name__ == '__main__':
    main()
