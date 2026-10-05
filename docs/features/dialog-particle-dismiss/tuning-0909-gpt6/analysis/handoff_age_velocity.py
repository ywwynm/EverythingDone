"""可见图案沿风向 / 横风的速度随释放后时间 τ 的变化（只作诊断）：检验「静止起步 + 沿共同流向统一加速」。

原片与模型都用原片的解除时刻图 T（`release_integrity.Probe.huawei`）取 τ = p − T，
各自做 PIV（`handoff_piv.piv`），速度分解到风向系：v_along = v·w，v_cross = v·w⊥（短边 / 单位进度）。
按 τ 分箱报加权中位数，另报逆风块占比（v_along < −0.05，只计 |v| > 0.15 的块）。模型帧与原片同一进度对。
与 `handoff_speed_by_age.py` 的区别：那边报速度大小、模型用自己的解除图；这里报分量、两边共用原片的 τ。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all, OUT
from handoff_piv import hp, piv

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
TAUS = [(0., .03), (.03, .06), (.06, .10), (.10, .15), (.15, .22), (.22, .32), (.32, .45)]


def collect(name, variants, gap=.017):
    pr = Probe(name); c = pr.comp; s = pr.scene; T = pr.huawei()
    x, y, _, _ = pr.rect; span = s.span
    ang = math.radians(s.meta['direction']); wind = np.array([math.cos(ang), -math.sin(ang)])
    perp = np.array([-wind[1], wind[0]])
    prog = np.asarray(c.ref.progress, float); roi = s.roi.astype(np.uint8)
    seqs = [('原片', None)] + [(lab, render_all(name, root, lab, spec=spec)) for lab, root, spec in variants]
    acc = {lab: {t: [] for t in TAUS} for lab, _ in seqs}
    for p in np.arange(.06, .80, .02):
        i = int(np.argmin(abs(prog - p))); pa = float(prog[i]); j = max(int(np.argmin(abs(prog - (pa + gap)))), i + 1)
        if j >= len(prog): continue
        dp = float(prog[j]) - pa
        for lab, fr in seqs:
            if fr is None:
                R = piv(hp(c.ref.frames[i]), hp(c.ref.frames[j]), roi, 24, 12, 16, .45, 6.); dt = dp
            else:
                k0 = round(pa * 120); k1 = k0 + max(1, round(dp * 120)); dt = (k1 - k0) / 120
                if k1 > 120: continue
                R = piv(hp(fr[k0]), hp(fr[k1]), roi, 24, 12, 16, .45, 6.)
            for a, b, dx, dy, _ in R:
                bi, bj = int((b - y) // B), int((a - x) // B)
                if not (0 <= bi < pr.bh and 0 <= bj < pr.bw) or not pr.valid[bi, bj] or not np.isfinite(T[bi, bj]): continue
                tau = pa - float(T[bi, bj]); t = next((q for q in TAUS if q[0] <= tau < q[1]), None)
                if t is None: continue
                v = np.array([dx, dy]) / dt / span
                acc[lab][t].append((float(v @ wind), float(v @ perp), float(np.hypot(*v))))
    return acc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体（@文件 读取配置）')
    ap.add_argument('--tag', default='')
    a = ap.parse_args()
    variants = []
    for v in a.variants:
        label, spec = v.split('=', 1); root = HERE
        if '::' in spec:
            d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
        if spec.startswith('@'): spec = Path(spec[1:]).read_text('utf-8').strip()
        variants.append((label, root, spec))
    out = {}
    for name in a.scenes:
        acc = collect(name, variants); out[name] = {}
        print(f'== {name}：沿风向速度中位 / 横风绝对值中位 / 逆风块占比，短边/单位进度（块数）', flush=True)
        print('   τ       ' + ''.join(f'{t[0]:.2f}-{t[1]:.2f}'.rjust(20) for t in TAUS), flush=True)
        for lab in acc:
            cells, rows = [], {}
            for t in TAUS:
                d = np.array(acc[lab][t]).reshape(-1, 3)
                m = d[:, 2] > .15
                if len(d) >= 15:
                    along = float(np.median(d[:, 0])); cross = float(np.median(abs(d[:, 1])))
                    back = float(np.mean(d[m, 0] < -.05)) if m.sum() else float('nan')
                    cells.append(f'{along:+.2f}/{cross:.2f}/{back:.0%}({len(d)})')
                    rows[f'{t[0]:.2f}'] = dict(along=along, cross=cross, back=back, n=len(d))
                else:
                    cells.append('-')
            out[name][lab] = rows
            print(f'   {lab:8s}' + ''.join(q.rjust(20) for q in cells), flush=True)
    (OUT / f'age-velocity{a.tag}.json').write_text(json.dumps(out, ensure_ascii=False), 'utf-8')


if __name__ == '__main__':
    main()
