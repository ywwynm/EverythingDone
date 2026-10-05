"""质量加权的云团几何与尾巴项（2026-09-29 按顾问意见新增，与 handoff_cloud.py 的二值口径并列）。

质量场：可观测背景内、颗粒掩码上的像素，以其与背景的 Lab 距离（截到 0～80）为权重，σ=6 模糊。
- 主体：质量沿风向的 5%～95% 分位为后沿／前沿，横风向 5%～95% 为展宽（长度单位为卡片短边）；
- 尾巴：模型质量中落在原片同一时刻 5%～95% 顺风范围之外的比例（原片自身按定义约为 0.10）；
- 按段报误差与带符号差，并给出每项最差段与「误差 ≤ 容差」的段数，不取十段平均作结论。
模型帧来自 handoff_review 的缓存（handoff_variants 变体）。只作诊断。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_review import render_all, OUT
from handoff_cloud import SCENES

TOL = dict(front=.03, back=.03, width=.04, tail=.05)


def lab(f):
    return cv2.cvtColor(np.clip(f, 0, 255).astype(np.uint8), cv2.COLOR_RGB2LAB).astype(np.float32)


def wquant(v, w, q):
    order = np.argsort(v); v = v[order]; c = np.cumsum(w[order]); c /= c[-1]
    return float(np.interp(q, c, v))


class Mass:
    def __init__(self, scene, wind, cross):
        self.s = scene; self.wind = wind; self.cross = cross
        self.bgl = lab(scene.bg)
        h, w = scene.roi.shape
        ys, xs = np.mgrid[:h, :w].astype(np.float32)
        self.along = (xs * wind[0] + ys * wind[1]) / scene.span
        self.across = (xs * cross[0] + ys * cross[1]) / scene.span

    def field(self, frame):
        par = self.s.observe(frame)['particle'] & self.s.roi
        d = np.clip(np.linalg.norm(lab(frame) - self.bgl, axis=2), 0, 80) * par
        m = cv2.GaussianBlur(d.astype(np.float32), (0, 0), 6.) * self.s.roi
        return m

    def geometry(self, m):
        sel = m > 1e-3
        if m[sel].sum() < 500: return None
        w = m[sel]; a = self.along[sel]; c = self.across[sel]
        return dict(back=wquant(a, w, .05), front=wquant(a, w, .95), width=wquant(c, w, .95) - wquant(c, w, .05),
                    a=a, w=w)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True)
    ap.add_argument('--out', default=str(OUT / 'cloud-mass.json'))
    a = ap.parse_args()
    report = {}
    for name in a.scenes:
        c = Comparison(name, stride=3); s = c.scene
        ang = math.radians(s.meta['direction']); wind = np.array([math.cos(ang), -math.sin(ang)]); cross = np.array([-wind[1], wind[0]])
        mass = Mass(s, wind, cross); refs = {}
        for i in c.index:
            p = float(c.ref.progress[i])
            if .15 <= p <= .85:
                g = mass.geometry(mass.field(np.asarray(c.ref.frames[i])))
                if g: refs[p] = g
        report[name] = {}
        for v in a.variants:
            label, rest = v.split('=', 1); d, _, spec = rest.partition('::'); pth = Path(d)
            frames = render_all(name, pth if pth.is_absolute() else HERE / pth, label, spec=spec)
            rows = {k: [] for k in ['front', 'back', 'width', 'tail']}
            for p, rg in refs.items():
                mg = mass.geometry(mass.field(np.asarray(frames[round(p * 120)])))
                if not mg: continue
                for k in ['front', 'back', 'width']: rows[k].append(mg[k] - rg[k])
                out = ((mg['a'] > rg['front']) | (mg['a'] < rg['back']))
                rows['tail'].append(float(mg['w'][out].sum() / mg['w'].sum()) - .10)
            r = {k: dict(abs=float(np.mean(np.abs(e))), signed=float(np.mean(e))) for k, e in rows.items() if e}
            report[name][label] = r
            print(f'{name:22s} {label:>8}: ' + '  '.join(f'{k} {r[k]["abs"]:.3f}({r[k]["signed"]:+.3f})' for k in r), flush=True)
    labels = [v.split('=', 1)[0] for v in a.variants]
    print('== 每项：最差段 / 达标段数（容差 ' + ' '.join(f'{k}≤{t}' for k, t in TOL.items()) + '）')
    for label in labels:
        parts = []
        for k, tol in TOL.items():
            vals = {n: report[n][label][k]['abs'] for n in report if label in report[n] and k in report[n][label]}
            if not vals: continue
            worst = max(vals, key=vals.get)
            parts.append(f'{k} 最差 {worst} {vals[worst]:.3f}，达标 {sum(x <= tol for x in vals.values())}/{len(vals)}')
        print(f'  {label}: ' + '；'.join(parts))
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
