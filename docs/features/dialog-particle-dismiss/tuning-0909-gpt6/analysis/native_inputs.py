"""华为原生实现（harmony_native.py）的逐段输入估计：一次动画的随机实现与设备／录像口径。

估计对象（都属于「这一次动画」，不是算法参数）：
- 消蚀值噪声的格点值（设备 GPU 的 sin 精度决定格点，桌面无法由随机数复现，直接拟合）；
- 横风随机值、设备像素比例（设备物理像素 / 场景像素）、扩展尺寸（工作区宽高，决定湍流取样窗口）；
- 进度跨度与偏移（场景进度 0..1 对应的设备时刻）。

流程（每段）：
  search  粗网格：比例 × 跨度 × 扩展高 × 横风，判据为占用与离开原位颗粒的软交并比（σ3）；
  span    联合确定跨度：每个候选跨度先按解除时刻图闭环拟合格点与偏移，再比较颗粒总量的衰减曲线（寿命钟）；
  geo     取样窗口细扫：比例 × 扩展宽高 × 横风，判据为致密颗粒片交并比、边缘重合、亮度相关（σ2，对卷曲敏感）；
  cma     CMA-ES 直接按画面判据联合拟合格点（以闭环解为先验）、偏移、跨度、横风、扩展尺寸；
  write   写入 native_reference_inputs.json。
中间结果写到 analysis/match-cache/native-fit/（被 Git 忽略）。

用法示例：
  python analysis/native_inputs.py search ironman --scales 2.0,2.2,2.4 --screen-w 593
  python analysis/native_inputs.py span ironman
  python analysis/native_inputs.py geo ironman --scales 2.1,2.2,2.3 --eh 1500,1650,1800 --winds=-55000,-45000 --screen-w 593
  python analysis/native_inputs.py cma ironman --gens 30 --pop 12 --sigma 0.03
"""
from pathlib import Path
import argparse, json, os, sys, time
from dataclasses import replace
import numpy as np
import cv2
import moderngl
from scipy.optimize import lsq_linear

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from harmony_native import NativeRenderer, NativeInputs, FormParams, LIVE_RASTER, inputs_from_entry
from match_compare import Comparison
from release_integrity import Probe

OUT = HERE / 'analysis/match-cache/native-fit'
TABLE = HERE / 'native_reference_inputs.json'
SEC_PER_NOISE = 2.0 * 0.8 / 2.1        # 卡片 duration 2 s、ablationRate 2.1：单位噪声对应的设备秒数


# ---------------------------------------------------------------- 消蚀格点

def design(name, scale, B=10):
    """解除时刻图（B 像素块）与格点值之间的线性设计矩阵（平滑插值权重）。"""
    m = json.loads((HERE / 'assets' / name / 'scene.json').read_text('utf-8'))
    x0, y0, x1, y1 = m['rect']
    cw, ch = x1 - x0, y1 - y0
    W, H = max(2, int(round(cw * scale))), max(2, int(round(ch * scale)))
    u = 205.0 / (W * H) + 0.002
    nx, ny = int(np.floor(W * u)) + 2, int(np.floor(H * u)) + 2
    T = Probe(name).huawei()
    bh, bw = T.shape
    ys, xs = np.mgrid[0:bh, 0:bw]
    qx, qy = (xs + 0.5) * B * W / cw * u, (ys + 0.5) * B * H / ch * u
    ix, iy = np.floor(qx).astype(int), np.floor(qy).astype(int)
    fx, fy = qx - ix, qy - iy
    fx = fx * fx * (3 - 2 * fx); fy = fy * fy * (3 - 2 * fy)
    rows = np.flatnonzero(np.isfinite(T).ravel())
    A = np.zeros((len(rows), nx * ny))
    for k, r in enumerate(rows):
        i, j, a, b = ix.ravel()[r], iy.ravel()[r], fx.ravel()[r], fy.ravel()[r]
        for di, dj, w in ((0, 0, (1 - a) * (1 - b)), (1, 0, a * (1 - b)), (0, 1, (1 - a) * b), (1, 1, a * b)):
            A[k, min(j + dj, ny - 1) * nx + min(i + di, nx - 1)] += w
    return dict(A=A, y=T.ravel()[rows], nx=nx, ny=ny, rows=rows, shape=T.shape)


def fit_release(name, scale, span):
    """有界线性最小二乘：解除进度 ≈ a·噪声 + b；返回格点值与换算的时间偏移。"""
    d = design(name, scale)
    a = SEC_PER_NOISE / span
    A = np.hstack([a * d['A'], np.ones((len(d['y']), 1))])
    lb = np.r_[np.zeros(d['nx'] * d['ny']), -5.0]
    ub = np.r_[np.ones(d['nx'] * d['ny']), 5.0]
    r = lsq_linear(A, d['y'], bounds=(lb, ub))
    L = r.x[:-1].reshape(d['ny'], d['nx'])
    rmse = float(np.sqrt(np.mean((A @ r.x - d['y']) ** 2)))
    # 测量判为解除约在遮罩 alpha 0.5：n − 1.05T = 0.075
    return L, 0.119 - r.x[-1] * span, rmse


# 粒子数与颗粒尺寸的试验口径（--count / --size）；不给时用表内粒子数与 LIVE_RASTER
COUNT = None
RASTER = LIVE_RASTER


def renderer(name, e, ctx, ss=2):
    inp = inputs_from_entry(e, count=int(COUNT or e['count']))
    return NativeRenderer(name, inp, raster=RASTER, ctx=ctx, ss=ss)


def refine_release(name, e, ctx, iters=3, damping=0.8):
    """闭环：原片与模型用同一 Probe 测解除时刻，按差值修正格点值与时间偏移。"""
    p = Probe(name)
    TH = p.huawei()
    d = design(name, e['scale'])
    L = np.asarray(e['lattice'], np.float64).copy()
    offset, span = float(e['offset']), float(e['span'])
    hist = []
    for it in range(iters + 1):
        r = renderer(name, dict(e, lattice=L.tolist(), offset=offset), ctx)
        TM = p.model(r)
        r.close()
        th, tm = TH.ravel()[d['rows']], TM.ravel()[d['rows']]
        ok = np.isfinite(th) & np.isfinite(tm) & (th < 1) & (tm < 1)
        hist.append(dict(it=it, mae=float(np.mean(np.abs(th[ok] - tm[ok]))), bias=float(np.mean((th - tm)[ok])), offset=offset))
        if it == iters:
            break
        dn = (th[ok] - tm[ok]) * span / SEC_PER_NOISE
        M = np.hstack([d['A'][ok], -np.ones((ok.sum(), 1)) / SEC_PER_NOISE])
        sol = lsq_linear(M, dn, bounds=(np.r_[-L.ravel(), -0.3], np.r_[1 - L.ravel(), 0.3]))
        L = np.clip(L + damping * sol.x[:-1].reshape(L.shape), 0, 1)
        offset += damping * sol.x[-1]
    return dict(e, lattice=np.round(L, 5).tolist(), offset=offset), hist


# ---------------------------------------------------------------- 判据

class Evaluator:
    """占用（偏离背景）与离开原位颗粒的软交并比；背景重建的参考只在屏幕内、卡片外计量。"""

    def __init__(self, name, n=14, p_lo=0.05, p_hi=0.9, sigma=3.0):
        self.name = name
        self.c = Comparison(name)
        s = self.c.scene
        if not s.roi.any():
            from match_observe import SCREEN
            from PIL import Image
            mp = HERE / 'assets' / name / 'screen-mask.png'
            if mp.exists():
                scr = np.asarray(Image.open(mp).convert('L')) > 127
            else:
                sx, sy, sx1, sy1 = SCREEN.get(name, (0, 0, s.W, s.H))
                scr = np.zeros((s.H, s.W), bool); scr[sy:sy1, sx:sx1] = True
            card = cv2.dilate(s.card.astype(np.uint8), np.ones((9, 9), np.uint8)).astype(bool)
            x, y, x1, y1 = s.rect
            m = round(s.span * .45)
            near = np.zeros((s.H, s.W), bool); near[max(0, y - m):y1 + m, max(0, x - m):x1 + m] = True
            s.roi = scr & ~card & near
            p_hi = min(p_hi, s.meta['reference'].get('valid_progress', 1.) - 0.02)
            if name == 'reference-0926-3':
                p_lo = max(p_lo, 0.14)          # 开场确认框与手指到约 0.13 才离开
        prog = self.c.ref.progress
        idx = [i for i in self.c.index if p_lo <= prog[i] <= p_hi]
        take = np.linspace(0, len(idx) - 1, min(n, len(idx))).round().astype(int)
        self.idx = [idx[k] for k in take]
        self.ps = [float(prog[i]) for i in self.idx]
        self.sigma = sigma
        x, y, x1, y1 = s.rect
        m = round(s.span * .45)
        self.box = (max(0, y - m), min(s.H, y1 + m), max(0, x - m), min(s.W, x1 + m))
        self.ref = [self.maps(np.asarray(self.c.ref.frames[i])) for i in self.idx]

    def maps(self, frame):
        s = self.c.scene
        o = s.observe(frame)
        y0, y1, x0, x1 = self.box
        roi = s.roi[y0:y1, x0:x1]
        occ = (o['occ'][y0:y1, x0:x1] & roi).astype('float32')
        par = ((o['occ'] & ~o['surface'])[y0:y1, x0:x1] & roi).astype('float32')
        g = lambda a: cv2.GaussianBlur(a, (0, 0), self.sigma)
        return dict(occ=g(occ), par=g(par), roi=roi)

    @staticmethod
    def siou(a, b):
        return float(np.minimum(a, b).sum() / max(np.maximum(a, b).sum(), 1e-6))

    def score(self, render, detail=False):
        rows = []
        for p, r in zip(self.ps, self.ref):
            m = self.maps(render(p))
            rows.append(dict(p=p, occ=self.siou(r['occ'], m['occ']), par=self.siou(r['par'], m['par']),
                             mass_ref=float(r['par'].sum()), mass_mod=float(m['par'].sum())))
        occ = float(np.mean([q['occ'] for q in rows])); par = float(np.mean([q['par'] for q in rows]))
        return (occ, par, rows) if detail else (occ, par)


class StartEvaluator(Evaluator):
    """起步：首批颗粒出现前后（进度 −0.04～0.12）画面相对起步前那一帧的变化量，软交并比；两边都还没变化时记 1。
    其余判据从 0.05 / 0.3 起算，量不到「模型比原片早出颗粒」；格点值与时间偏移此消彼长，没有这一项时
    偏移可以偏大、早释放的区域在原片第一帧之前就已经出颗粒（向上钢铁侠首帧右上角一团）。
    「离开原位颗粒」在起步阶段有很大的本底（手持拍摄的曝光、对齐误差），这里改用相对起步前一帧的变化。"""
    START = {}
    # 参考 3：开场被确认框与手指挡住；向上钢铁侠：没有起点之前的画面，首帧卡片中部还有确认浮层的残影（一道横线），
    # 浮层退去会被算成「变化」，反而奖励提前起步。改用 source.png 做基准也不行：手持片的曝光与配准让源图与首帧差出一大片。
    SKIP = {'reference-0926-3', 'ironman-up-reference'}
    EPS = 500.0                               # 约等于一小簇颗粒的变化面积（像素），两边都几乎没变时得分接近 1
    DIFF = 30.0

    def __init__(self, name, n=8):
        super().__init__(name, n=n, p_lo=self.START.get(name, -0.04), p_hi=0.12, sigma=3.0)
        self.first = np.asarray(self.c.ref.frames[self.idx[0]]).astype('float32')
        self.ref = [self.change(np.asarray(self.c.ref.frames[i]), self.first) for i in self.idx]

    def change(self, frame, first):
        y0, y1, x0, x1 = self.box
        d = np.abs(frame.astype('float32') - first).max(2)[y0:y1, x0:x1]
        x, y, x1_, y1_ = self.c.scene.rect
        roi = self.c.scene.roi.copy()
        roi[y:y1_, x:x1_] = True                    # 卡片本身的变化（消蚀、颗粒）也算
        m = ((d > self.DIFF) & roi[y0:y1, x0:x1]).astype('float32')
        return cv2.GaussianBlur(m, (0, 0), self.sigma)

    def score(self, render, detail=False):
        first = render(-1.0).astype('float32')        # 设备时刻 < 0：完整卡片
        rows = []
        for p, a in zip(self.ps, self.ref):
            b = self.change(render(p), first)
            rows.append(dict(p=p, s=float((np.minimum(a, b).sum() + self.EPS) / (np.maximum(a, b).sum() + self.EPS)),
                             mass_ref=float(a.sum()), mass_mod=float(b.sum())))
        v = float(np.mean([q['s'] for q in rows]))
        return (v, rows) if detail else v


class BlurEvaluator:
    """粗尺度画面差：原片与模型都做 σ4 模糊，在卡片外扩 0.3 宽度的范围内逐帧求平均绝对差（越小越好）。
    直接对应「正常观看时像不像」，对颗粒片的密度、亮度与时机都敏感。"""

    def __init__(self, name, step=0.05, p_lo=0.2, p_hi=0.85, sigma=4.0):
        self.c = Comparison(name)
        s = self.c.scene
        x0, y0, x1, y1 = s.rect
        m = int(0.3 * (x1 - x0))
        self.box = (max(0, y0 - m), min(s.H, y1 + m), max(0, x0 - m), min(s.W, x1 + m))
        valid = s.meta['reference'].get('valid_progress', 1.0)
        prog = self.c.ref.progress
        targets = np.arange(p_lo, min(p_hi, valid - 0.02) + 1e-9, step)
        self.idx = sorted({int(np.argmin(abs(prog - t))) for t in targets})
        self.ps = [float(prog[i]) for i in self.idx]
        self.sigma = sigma
        self.ref = [self.blur(np.asarray(self.c.ref.frames[i])) for i in self.idx]

    def blur(self, a):
        y0, y1, x0, x1 = self.box
        return cv2.GaussianBlur(a[y0:y1, x0:x1].astype(np.float32), (0, 0), self.sigma)

    def score(self, render):
        return float(np.mean([np.abs(self.blur(render(p)) - r).mean() for p, r in zip(self.ps, self.ref)]))


class SheetEvaluator(Evaluator):
    """对卷曲敏感：致密颗粒片（σ2 密度阈值 0.35 / 0.6）的交并比、边缘双向距离、亮度超出量的相关。"""

    def __init__(self, name, n=16, p_lo=0.3, p_hi=0.8, sigma=2.0, levels=(0.35, 0.6)):
        self.levels = levels
        super().__init__(name, n=n, p_lo=p_lo, p_hi=p_hi, sigma=sigma)

    def maps(self, frame):
        s = self.c.scene
        o = s.observe(frame)
        y0, y1, x0, x1 = self.box
        roi = s.roi[y0:y1, x0:x1]
        par = ((o['occ'] & ~o['surface'])[y0:y1, x0:x1] & roi).astype('float32')
        d = cv2.GaussianBlur(par, (0, 0), self.sigma)
        e = np.minimum(o['energy'][y0:y1, x0:x1], 160.0) / 160.0 * roi
        el = cv2.GaussianBlur(e.astype('float32'), (0, 0), self.sigma)
        masks = [d > t for t in self.levels]
        edges = [cv2.morphologyEx(m.astype(np.uint8), cv2.MORPH_GRADIENT, np.ones((3, 3), np.uint8)) > 0 for m in masks]
        return dict(d=d, el=el, masks=masks, edges=edges, roi=roi)

    def score(self, render, detail=False):
        rows = []
        for p, r in zip(self.ps, self.ref):
            m = self.maps(render(p))
            ious = [float((a & b).sum() / max((a | b).sum(), 1)) for a, b in zip(r['masks'], m['masks'])]
            edge = []
            for ea, eb in zip(r['edges'], m['edges']):
                if ea.sum() < 20 or eb.sum() < 20:
                    continue
                da = cv2.distanceTransform((~ea).astype(np.uint8), cv2.DIST_L2, 3)
                db = cv2.distanceTransform((~eb).astype(np.uint8), cv2.DIST_L2, 3)
                edge.append(float(np.exp(-0.5 * (da[eb].mean() + db[ea].mean()) / 12.0)))
            corr = float(np.corrcoef(r['el'].ravel(), m['el'].ravel())[0, 1])
            rows.append(dict(p=p, iou=float(np.mean(ious)), edge=float(np.mean(edge)) if edge else 0.0, corr=corr))
        iou, edge, corr = (float(np.mean([q[k] for q in rows])) for k in ('iou', 'edge', 'corr'))
        return (iou, edge, corr, rows) if detail else (iou, edge, corr)


def mass_error(ev, e, ctx):
    """离开原位颗粒总量随进度的衰减（p ≥ 0.45）对数误差：颗粒寿命是设备时间的钟。"""
    r = renderer(ev.name, e, ctx)
    occ, par, rows = ev.score(r.render, detail=True)
    r.close()
    ref = np.array([q['mass_ref'] for q in rows]); mod = np.array([q['mass_mod'] for q in rows])
    p = np.array([q['p'] for q in rows]); c = 0.05 * ref.max(); late = p >= 0.45
    return float(np.mean(np.abs(np.log((mod[late] + c) / (ref[late] + c))))), occ, par


# ---------------------------------------------------------------- 命令

def table():
    return json.loads(TABLE.read_text('utf-8')) if TABLE.exists() else {}


def save_table(t):
    TABLE.write_text(json.dumps(t, ensure_ascii=False, indent=1), 'utf-8')


def cmd_search(a, ctx):
    ev = Evaluator(a.name)
    res = []
    for s in [float(v) for v in a.scales.split(',')]:
        for span in [float(v) for v in a.spans.split(',')]:
            L, offset, rmse = fit_release(a.name, s, span)
            for eh in [float(v) for v in a.eh.split(',')]:
                for wx in [float(v) for v in a.winds.split(',')]:
                    e = dict(scale=s, expansion=[min(a.screen_w * s, 2100.0), eh], count=20000, wind_x=wx,
                             span=span, offset=offset, lattice=L.tolist())
                    r = renderer(a.name, e, ctx)
                    occ, par = ev.score(r.render)
                    r.close()
                    res.append(dict(e, occ=occ, par=par, rmse=rmse))
                    print(f'{a.name} s={s} span={span} eh={eh:.0f} wx={wx:+.0f} occ={occ:.4f} par={par:.4f}', flush=True)
    res.sort(key=lambda q: -(q['occ'] + q['par']))
    (OUT / f'search_{a.name}.json').write_text(json.dumps(res), 'utf-8')
    return res[0]


def cmd_span(a, ctx):
    base = table()[a.name]
    ev = Evaluator(a.name, n=24, p_lo=0.05, p_hi=0.97)
    rows = []
    for span in [float(v) for v in a.spans.split(',')]:
        L, offset, _ = fit_release(a.name, base['scale'], span)
        e, hist = refine_release(a.name, dict(base, span=span, offset=offset, lattice=L.tolist()), ctx, iters=2)
        merr, occ, par = mass_error(ev, e, ctx)
        rows.append(dict(span=span, mae=hist[-1]['mae'], mass=merr, occ=occ, par=par, entry=e))
        print(f'{a.name} span={span} mae={hist[-1]["mae"]:.4f} mass={merr:.3f} occ={occ:.3f} par={par:.3f}', flush=True)
    best = min(rows, key=lambda q: q['mae'] / 0.03 + q['mass'] / 0.15)
    return best['entry']


def cmd_geo(a, ctx):
    base = table()[a.name]
    ev = SheetEvaluator(a.name)
    res = []
    for s in [float(v) for v in a.scales.split(',')]:
        L, offset, _ = fit_release(a.name, s, base['span'])
        e, _ = refine_release(a.name, dict(base, scale=s, offset=offset, lattice=L.tolist()), ctx, iters=1)
        for eh in [float(v) for v in a.eh.split(',')]:
            for wx in [float(v) for v in a.winds.split(',')]:
                x = dict(e, expansion=[min(a.screen_w * s, 2100.0), eh], wind_x=wx)
                r = renderer(a.name, x, ctx)
                iou, edge, corr = ev.score(r.render)
                r.close()
                res.append(dict(x, iou=iou, edge=edge, corr=corr))
                print(f'{a.name} s={s} eh={eh:.0f} wx={wx:+.0f} iou={iou:.4f} edge={edge:.4f} corr={corr:.4f}', flush=True)
    res.sort(key=lambda q: -(q['iou'] + q['edge'] + q['corr']))
    (OUT / f'geo_{a.name}.json').write_text(json.dumps(res), 'utf-8')
    return {k: v for k, v in res[0].items() if k not in ('iou', 'edge', 'corr')}


def cmd_cma(a, ctx):
    import cma
    e0 = table()[a.name]
    L0 = np.asarray(e0['lattice'], np.float64)
    ev_occ, ev_sheet = Evaluator(a.name), SheetEvaluator(a.name)
    ev_start = StartEvaluator(a.name) if a.start and a.name not in StartEvaluator.SKIP else None
    ev_blur = BlurEvaluator(a.name) if a.blur else None
    m = json.loads((HERE / 'assets' / a.name / 'scene.json').read_text('utf-8'))
    x0, y0, x1, y1 = m['rect']
    lo = np.r_[np.zeros(L0.size), e0['offset'] - 0.15, 0.75, -60000, (x1 - x0) * e0['scale'] + 2, (y1 - y0) * e0['scale'] + 2,
               e0['scale'] * 0.8]
    hi = np.r_[np.ones(L0.size), e0['offset'] + 0.15, 1.3, 60000, 2100, 2400, e0['scale'] * 1.4]
    v0 = np.r_[L0.ravel(), e0['offset'], e0['span'], e0['wind_x'], e0['expansion'][0], e0['expansion'][1], e0['scale']]
    if not a.fit_scale:                      # 比例默认不参与（固定为表内值）
        lo[-1] = hi[-1] = v0[-1] = e0['scale']
        hi[-1] = e0['scale'] + 1e-6
    u0 = np.clip((v0 - lo) / (hi - lo), 0, 1)

    def decode(u):
        v = lo + (hi - lo) * np.clip(u, 0, 1); n = L0.size
        sc = float(v[n + 5])
        ex = [max(float(v[n + 3]), (x1 - x0) * sc + 2), max(float(v[n + 4]), (y1 - y0) * sc + 2)]
        return dict(e0, lattice=v[:n].reshape(L0.shape).tolist(), offset=float(v[n]), span=float(v[n + 1]),
                    wind_x=float(v[n + 2]), expansion=ex, scale=sc)

    def f(u):
        e = decode(u)
        r = renderer(a.name, e, ctx)
        occ, par = ev_occ.score(r.render); r.reset()
        iou, edge, corr = ev_sheet.score(r.render)
        terms = [occ, par, iou, edge, corr]
        if ev_start is not None:
            r.reset(); terms.append(ev_start.score(r.render))
        mae = 0.0
        if ev_blur is not None:
            r.reset(); mae = ev_blur.score(r.render)
        r.close()
        structural = -float(np.mean(terms)) * (0.5 if ev_blur is not None else 1.0)
        return structural + a.blur * 0.1 * mae + a.reg * float(np.mean((np.asarray(e['lattice']) - L0) ** 2))

    best = (f(u0), u0)
    es = cma.CMAEvolutionStrategy(u0, a.sigma, {'bounds': [0, 1], 'popsize': a.pop, 'seed': 11, 'verbose': -9})
    for g in range(a.gens):
        us = es.ask(); fs = [f(u) for u in us]; es.tell(us, fs)
        i = int(np.argmin(fs))
        if fs[i] < best[0]:
            best = (fs[i], np.array(us[i]))
        print(f'gen {g} best {best[0]:.4f}', flush=True)
    return decode(best[1])


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('command', choices=['search', 'span', 'geo', 'cma'])
    ap.add_argument('name')
    ap.add_argument('--scales', default='1.75,2.0,2.25')
    ap.add_argument('--spans', default='0.85,0.9,0.95,1.0,1.05,1.1,1.15,1.2')
    ap.add_argument('--eh', default='1500,1900,2400')
    ap.add_argument('--winds', default='-60000,-40000,-20000,0,20000,40000,60000')
    ap.add_argument('--screen-w', type=float, default=720.0, help='手机屏幕宽（场景像素）；扩展宽 = min(屏宽 × 比例, 2100)')
    ap.add_argument('--gens', type=int, default=30)
    ap.add_argument('--pop', type=int, default=12)
    ap.add_argument('--sigma', type=float, default=0.03)
    ap.add_argument('--reg', type=float, default=2.0)
    ap.add_argument('--count', type=int, default=None, help='覆盖表内粒子数（试验口径）')
    ap.add_argument('--size', type=float, default=None, help='覆盖颗粒直径倍数（试验口径）')
    ap.add_argument('--tag', default='', help='结果另存为 cma_<段名>_<tag>.json，不写表')
    ap.add_argument('--start', action='store_true', help='联合拟合加入起步判据（进度 −0.04～0.12）')
    ap.add_argument('--blur', type=float, default=0.0, help='粗尺度画面差的权重（目标里加 权重 × 0.1 × 平均绝对差；结构判据减半）')
    ap.add_argument('--write', action='store_true', help='结果写入 native_reference_inputs.json')
    ap.add_argument('--fit-scale', action='store_true', help='cma 时把设备比例也作为变量（×0.8～×1.4）')
    a = ap.parse_args()
    global COUNT, RASTER
    COUNT = a.count
    if a.size is not None:
        RASTER = replace(LIVE_RASTER, size_mul=a.size)
    OUT.mkdir(parents=True, exist_ok=True)
    ctx = moderngl.create_standalone_context(require=430)
    t0 = time.time()
    e = dict(search=cmd_search, span=cmd_span, geo=cmd_geo, cma=cmd_cma)[a.command](a, ctx)
    e = {k: v for k, v in e.items() if k not in ('occ', 'par', 'rmse')}
    if COUNT:
        e['count'] = int(COUNT)
    print(a.command, 'done', round(time.time() - t0), 's', {k: v for k, v in e.items() if k != 'lattice'})
    if a.tag:
        (OUT / f'{a.command}_{a.name}_{a.tag}.json').write_text(json.dumps(dict(e, raster_size=RASTER.size_mul), ensure_ascii=False), 'utf-8')
    elif a.write:
        # 多个拟合进程并行时，读改写要互斥，否则后写者会覆盖先写者的条目
        lock = TABLE.with_suffix('.lock')
        for _ in range(600):
            try:
                fd = os.open(str(lock), os.O_CREAT | os.O_EXCL | os.O_WRONLY)
                break
            except FileExistsError:
                time.sleep(0.5)
        else:
            raise RuntimeError('参考输入表被占用')
        try:
            t = table(); t[a.name] = dict(t.get(a.name, {}), **e); save_table(t)
        finally:
            os.close(fd); os.unlink(str(lock))


if __name__ == '__main__':
    main()
