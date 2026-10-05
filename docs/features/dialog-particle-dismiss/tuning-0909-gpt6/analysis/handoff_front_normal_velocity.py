"""刚释放材料沿释放前沿法向的速度（只作诊断）：检验「释放时有一个背离起点的初始冲量」。

用原片解除时刻图 T（`release_integrity.Probe.huawei`）的梯度方向 n（T 增大的方向，即前沿推进方向，背离起点）；
原片与模型各自做 PIV（`handoff_piv.piv`），按原片的 T 取释放后时间 τ = p − T，把速度分解为沿 n 的分量 v·n，
并按 n 与风向的夹角分成三组：n 顺风（n·w > 0.3）、横风、n 逆风（n·w < −0.3）。逆风组最能区分「背离起点的冲量」与「风的携带」。
按 τ 分箱报加权中位数（短边 / 单位进度）。模型帧与原片同一进度对。
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np, cv2
from scipy.ndimage import gaussian_filter

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all, OUT
from handoff_piv import hp, piv

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-6', 'reference-0926-7', 'reference-0926-5']
TAUS = [(0., .03), (.03, .06), (.06, .10), (.10, .16), (.16, .25)]
GROUPS = ['顺风', '横风', '逆风']


def normals(T):
    Tf = np.where(np.isfinite(T), T, np.nan)
    m = np.isfinite(Tf); fill = np.where(m, Tf, np.nanmean(Tf))
    w = gaussian_filter(m.astype(float), 1.5); s = gaussian_filter(np.where(m, fill, 0.), 1.5) / np.maximum(w, 1e-6)
    gy, gx = np.gradient(s)
    L = np.hypot(gx, gy)
    return gx / np.maximum(L, 1e-9), gy / np.maximum(L, 1e-9), L


def collect(name, variants, gap=.017):
    pr = Probe(name); c = pr.comp; s = pr.scene; T = pr.huawei()
    x, y, _, _ = pr.rect; span = s.span
    ang = math.radians(s.meta['direction']); wind = np.array([math.cos(ang), -math.sin(ang)])
    nx, ny, L = normals(T)
    prog = np.asarray(c.ref.progress, float); roi = s.roi.astype(np.uint8)
    seqs = [('原片', None)] + [(lab, render_all(name, root, lab, spec=spec)) for lab, root, spec in variants]
    acc = {lab: {(g, t): [] for g in GROUPS for t in TAUS} for lab, _ in seqs}
    for p in np.arange(.06, .75, .02):
        i = int(np.argmin(abs(prog - p))); pa = float(prog[i]); j = max(int(np.argmin(abs(prog - (pa + gap)))), i + 1)
        dp = float(prog[j]) - pa
        for lab, fr in seqs:
            if fr is None:
                R = piv(hp(c.ref.frames[i]), hp(c.ref.frames[j]), roi, 24, 12, 16, .45, 6.); dt = dp
            else:
                k0 = round(pa * 120); k1 = k0 + max(1, round(dp * 120)); dt = (k1 - k0) / 120
                R = piv(hp(fr[k0]), hp(fr[min(k1, 120)]), roi, 24, 12, 16, .45, 6.)
            for a, b, dx, dy, _ in R:
                bi, bj = int((b - y) // B), int((a - x) // B)
                if not (0 <= bi < pr.bh and 0 <= bj < pr.bw) or not pr.valid[bi, bj] or not np.isfinite(T[bi, bj]): continue
                if L[bi, bj] < 1e-4: continue
                tau = pa - float(T[bi, bj]); t = next((q for q in TAUS if q[0] <= tau < q[1]), None)
                if t is None: continue
                n = np.array([nx[bi, bj], ny[bi, bj]]); v = np.array([dx, dy]) / dt / span
                nw = float(n @ wind); g = '顺风' if nw > .3 else ('逆风' if nw < -.3 else '横风')
                acc[lab][(g, t)].append(float(v @ n))
    return acc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体')
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
        print(f'== {name}：沿前沿法向（背离起点）的速度中位，短边/单位进度（块数）', flush=True)
        print('   组/τ    ' + ''.join(f'{t[0]:.2f}-{t[1]:.2f}'.rjust(22) for t in TAUS), flush=True)
        for g in GROUPS:
            for lab in acc:
                cells = []
                for t in TAUS:
                    v = acc[lab][(g, t)]
                    cells.append(f'{np.median(v):+.2f}({len(v)})' if len(v) >= 15 else '-')
                out[name].setdefault(lab, {})[g] = cells
                print(f'   {g} {lab:6s}' + ''.join(q.rjust(22) for q in cells), flush=True)
    (OUT / 'front-normal-velocity.json').write_text(json.dumps(out, ensure_ascii=False), 'utf-8')


if __name__ == '__main__':
    main()
