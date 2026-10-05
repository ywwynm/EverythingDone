"""释放后的颗粒覆盖率随年龄（只作诊断与候选排序）。

取源图与背景亮度差 ≥90 的 10×10 块，按原片解除时刻图 T 对齐（原片与模型用同一张图，避免各自时刻图的定义差异），
统计 τ=p−T 时块内位于源色一侧的像素比例 c（u=(L−bg)/(src−bg)>0.5）与块均值保留比 r=(L̄−bg)/(src−bg)。
c 描述「释放后这里还有多少面积被颗粒盖着」，r 描述「还剩多少材料亮度」；两者一起区分颗粒散开、变小与变透明。
模型帧默认先按原片码率编码（recording_codec），与原片同一录像口径。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all, OUT
from recording_codec import coded

LUM = np.float32([.2126, .7152, .0722])
TAUS = [0., .02, .04, .06, .08, .10, .13, .16, .20, .25]
SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'reference-0926-1', 'reference-0926-4',
          'reference-0926-6', 'reference-0926-7', 'reference-0926-5', 'ironman-up-reference']


def curves(name, variants, codec=True, polarity='all', tbin=None):
    pr = Probe(name); c = pr.comp; sc = pr.scene; x, y, _, _ = pr.rect; T = pr.huawei()
    src = sc.source.astype(np.float32) @ LUM; bg = sc.bg.astype(np.float32) @ LUM
    prog = np.asarray(c.ref.progress, float)
    seqs = [('原片', lambda i: np.asarray(c.ref.frames[i]))]
    for label, root, spec in variants:
        if codec:
            cf = coded(name, label, spec, root=root, verbose=False)[0]; seqs.append((label, lambda i, cf=cf: np.asarray(cf[i])))
        else:
            fr = render_all(name, root, label, spec=spec)
            seqs.append((label, lambda i, fr=fr: np.asarray(fr[round(float(np.clip(prog[i], 0, 1)) * 120)])))
    blocks = []
    for bi in range(pr.bh):
        for bj in range(pr.bw):
            if not pr.valid[bi, bj] or not np.isfinite(T[bi, bj]): continue
            ys, xs = slice(y + bi * B, y + bi * B + B), slice(x + bj * B, x + bj * B + B)
            s, b = float(src[ys, xs].mean()), float(bg[ys, xs].mean())
            if abs(s - b) < 90: continue
            if polarity == 'bright' and s < b or polarity == 'dark' and s > b: continue
            if tbin and not (tbin[0] <= float(T[bi, bj]) < tbin[1]): continue
            blocks.append((ys, xs, s, b, float(T[bi, bj])))
    need = {}
    for k, (ys, xs, s, b, t0) in enumerate(blocks):
        for t in TAUS:
            i = int(np.argmin(abs(prog - (t0 + t))))
            if abs(prog[i] - (t0 + t)) <= .006: need.setdefault(i, []).append((k, t))
    res = {lab: {t: [] for t in TAUS} for lab, _ in seqs}
    for i in sorted(need):
        for lab, get in seqs:
            L = get(i).astype(np.float32) @ LUM
            for k, t in need[i]:
                ys, xs, s, b, _ = blocks[k]
                u = (L[ys, xs] - b) / (s - b)
                res[lab][t].append(((u > .5).mean(), float(u.mean())))
    out = {}
    for lab in res:
        out[lab] = {f'{t:.2f}': [float(np.mean([q[0] for q in v])), float(np.mean([q[1] for q in v])), len(v)]
                    for t, v in res[lab].items() if len(v) >= 30}
    return out, len(blocks)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--variants', nargs='+', required=True, help='标签=[配置目录::]变体（空变体为交接默认）')
    ap.add_argument('--raw', action='store_true', help='模型帧不经编码')
    ap.add_argument('--polarity', choices=['all', 'bright', 'dark'], default='all', help='只用亮源暗底或暗源亮底的块')
    ap.add_argument('--tbin', type=float, nargs=2, help='只用原片解除时刻在 [a, b) 内的块')
    ap.add_argument('--out', default=str(OUT / 'coverage-age.json'))
    a = ap.parse_args()
    variants = []
    for v in a.variants:
        label, spec = v.split('=', 1); root = HERE
        if '::' in spec:
            d, spec = spec.split('::', 1); root = Path(d) if Path(d).is_absolute() else HERE / d
        variants.append((label, root, spec))
    path = Path(a.out); allres = json.loads(path.read_text('utf-8')) if path.is_file() else {}
    summary = {lab: [] for lab, _, _ in variants}
    for name in a.scenes:
        res, nb = curves(name, variants, codec=not a.raw, polarity=a.polarity, tbin=a.tbin)
        if nb < 30: print(f'== {name}：{a.polarity} 块不足', flush=True); continue
        allres.setdefault(name, {}).update(res)
        taus = [t for t in res['原片']]
        print(f'== {name}（{nb} 块）覆盖率 c / 保留比 r，τ=' + ' '.join(taus), flush=True)
        for lab, d in res.items():
            print(f'  {lab:10s} ' + ' '.join(f'{d[t][0]:.2f}/{d[t][1]:.2f}' if t in d else '   -     ' for t in taus), flush=True)
        for lab, _, _ in variants:
            e = [abs(res[lab][t][0] - res['原片'][t][0]) for t in taus if t in res[lab] and float(t) >= .02]
            s = [res[lab][t][0] - res['原片'][t][0] for t in taus if t in res[lab] and float(t) >= .02]
            if e: summary[lab].append((name, float(np.mean(e)), float(np.mean(s))))
    path.write_text(json.dumps(allres, ensure_ascii=False, indent=1), 'utf-8')
    print('== 覆盖率与原片的平均绝对差（τ≥0.02；括号内为带符号平均，负为偏少）')
    for lab, v in summary.items():
        print(f'  {lab:10s} 平均 {np.mean([q[1] for q in v]):.3f} | ' + '  '.join(f'{n[:10]} {e:.3f}({s:+.3f})' for n, e, s in v), flush=True)


if __name__ == '__main__':
    main()
