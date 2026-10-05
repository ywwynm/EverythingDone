"""连续薄膜模型（重建，研究中，未接入任何默认模型）。

思路（rebuild-2026-10-01-plan.md）：卡片是一张被平滑运动场带走的薄膜。每个材料点只有一个开动时刻 T(x0)，之后的位移是 x0 的平滑函数：
  x(τ) = x0 + ŵ·短边·(V0·τ + ½·A·τ²) + n̂(x0)·短边·Vn·τn·(1 − e^(−τ/τn)) + ∫ 静态卷动场,   τ = t − T(x0)
运动没有逐颗随机（sv 默认 0，留给拟合判定）；颗粒是材料格上的小方块（格距同现行模型），随年龄缩小、淡出，颜色取源图，
逐颗随机只用于外观（寿命、闪光、格内位置）。相邻颗粒始终相邻，压缩处自然叠成致密带，拉伸处自然变稀。
渲染：方块按与像素的精确重叠面积溅射到全分辨率画面，覆盖率截到 1；未开动的材料按原位画源图。
测量与原片同口径：Scene.observe 的 particle（偏离背景包络、偏离原位源图）且与背景的 Lab 色差 ≥ 22，按 σ = 0.012 短边平滑得到密度 D，
取 D ≥ 0.22 / 0.45 / 0.65 三档与原片逐帧求交并比（core_iou.py 的口径，原始画面，不加录像模糊）。
释放场暂取现行候选（GLHMRP）的平滑释放场（rebuild_extract_release.py 导出），只比较运动与外观；之后再换成参数化前沿。
运行环境：spatialtuning（CUDA torch）。
"""
from pathlib import Path
from dataclasses import dataclass, asdict, replace
import json, math, sys, time
import numpy as np, cv2, torch
from PIL import Image

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison

DEV = 'cuda'
REL = HERE / 'analysis/match-cache/rebuild/release'
LV = (.22, .45, .65)
BINS = ((.1, .25), (.25, .5), (.5, .85))


@dataclass
class Params:
    V0: float = .15          # 沿风向的初速（短边/进度）
    A: float = 1.6           # 沿风向的加速度（短边/进度²）；逐段由终点输入给出
    Vn: float = .15          # 沿前沿推进方向的初速（短边/进度），按 τn 衰减
    tn: float = .08
    sv: float = 0.           # 逐颗随机速度（短边/进度，各向同性）；连续薄膜假设下应接近 0，由拟合决定
    curl: float = 0.         # 静态卷动场的速度幅度（短边/进度）
    curl_scale: float = .3   # 卷动场的空间尺度（短边）
    jitter: float = 0.       # 逐颗开动时刻抖动（进度，均匀分布宽度）
    life: float = .35        # 寿命中位（进度）
    life_var: float = .4     # 寿命逐颗随机的相对幅度
    size0: float = 1.        # 开动时颗粒边长 / 材料格距
    size_pow: float = 1.     # 边长随年龄缩到 0 的幂：size0·(1 − (τ/寿命)^size_pow)
    fade_in: float = .01     # 开动后不透明度升满所需进度
    gain: float = 1.         # 颗粒颜色增益（sRGB）
    glint: float = 0.        # 逐颗「闪光」：颜色向白色混合的比例上限（随机 u^3 × glint）
    pos_jitter: float = .5   # 颗粒在材料格内的静态位置随机（格距的比例）
    sb: float = 0.           # 逐颗亮度（对数正态）的标准差：薄片朝向不同，有的偏暗、有的偏亮；亮到溢出时各通道截到 255
    tw: float = 0.           # 亮度随年龄转动的角频率（弧度/进度）：0 为每颗亮度固定，>0 时同一颗随时间明暗交替（翻转闪烁）


def _gauss_kernel(sigma):
    k = int(round(sigma * 4 * 2 + 1)) | 1
    return torch.tensor(cv2.getGaussianKernel(k, sigma)[:, 0], dtype=torch.float32, device=DEV)


def blur(img, kern):
    """与 cv2.GaussianBlur(img, (0,0), σ)（float32，BORDER_REFLECT_101）同口径的可分离卷积。"""
    k = kern.numel(); p = k // 2
    x = img[None, None]
    x = torch.nn.functional.pad(x, (p, p, 0, 0), mode='reflect')
    x = torch.nn.functional.conv2d(x, kern.view(1, 1, 1, k))
    x = torch.nn.functional.pad(x, (0, 0, p, p), mode='reflect')
    x = torch.nn.functional.conv2d(x, kern.view(1, 1, k, 1))
    return x[0, 0]


_M = None


def lab8(f):
    """与 cv2.cvtColor(uint8, COLOR_RGB2LAB) 同口径（8 位 Lab：L·255/100、a+128、b+128），输入 0～255。"""
    global _M
    if _M is None:
        _M = torch.tensor([[0.412453, 0.357580, 0.180423], [0.212671, 0.715160, 0.072169], [0.019334, 0.119193, 0.950227]],
                          device=f.device) / torch.tensor([[0.950456], [1.], [1.088754]], device=f.device)
    c = f / 255.
    lin = torch.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)
    xyz = lin @ _M.T
    eps = 0.008856
    fx = torch.where(xyz > eps, xyz.clamp_min(1e-12) ** (1 / 3), 7.787 * xyz + 16 / 116)
    L = torch.where(xyz[..., 1] > eps, 116 * fx[..., 1] - 16, 903.3 * xyz[..., 1])
    a = 500 * (fx[..., 0] - fx[..., 1]); b = 200 * (fx[..., 1] - fx[..., 2])
    return torch.stack([L * 255 / 100, a + 128, b + 128], -1).round().clamp(0, 255)


def _sample(img_hwc, uu, vv, mode='bilinear'):
    hh, ww = img_hwc.shape[:2]
    gx = uu / ww * 2 - 1; gy = vv / hh * 2 - 1
    out = torch.nn.functional.grid_sample(img_hwc.permute(2, 0, 1)[None], torch.stack([gx, gy], -1)[None, None], mode=mode,
                                          align_corners=False, padding_mode='border')
    return out[0, :, 0].permute(1, 0)


class SceneCF:
    def __init__(self, name, release='GLHMRP', frames='core', fuzzy=False):
        t0 = time.time()
        self.name = name; self.fuzzy = fuzzy; self._fz = []
        c = Comparison(name); s = c.scene; self.c, self.s = c, s
        z = np.load(REL / release / f'{name}.npz')
        x0, y0, x1, y1 = [int(q) for q in s.meta['rect']]; cw, ch = x1 - x0, y1 - y0
        self.rect = (x0, y0, x1, y1); self.span = float(min(cw, ch)); self.H, self.W = s.H, s.W
        th = math.radians(float(z['direction'])); self.wind = (math.cos(th), -math.sin(th)); self.direction = float(z['direction'])
        nx, ny = int(z['nx']), int(z['ny']); self.cell = (cw / nx, ch / ny); self.hc = math.sqrt(self.cell[0] * self.cell[1])
        # 材料格（与现行模型同一格距），只保留不透明处
        yy, xx = np.mgrid[:ny, :nx]
        u = (xx.ravel() + .5) * cw / nx; v = (yy.ravel() + .5) * ch / ny
        fg = np.array(Image.open(HERE / 'assets' / name / 'foreground.png').convert('RGBA'))
        a_ = fg[np.clip((v / ch * fg.shape[0]).astype(int), 0, fg.shape[0] - 1), np.clip((u / cw * fg.shape[1]).astype(int), 0, fg.shape[1] - 1), 3]
        keep = a_ > 127
        u, v = u[keep], v[keep]
        rng = np.random.default_rng(int(z['seed']))
        self.rand = torch.tensor(rng.random((len(u), 10)), dtype=torch.float32, device=DEV)
        U = torch.tensor(u, dtype=torch.float32, device=DEV); V = torch.tensor(v, dtype=torch.float32, device=DEV)
        T2 = torch.tensor(z['T2'], dtype=torch.float32, device=DEV)[..., None]
        self.T = _sample(T2, U * T2.shape[1] / cw, V * T2.shape[0] / ch)[:, 0]
        Tn = cv2.GaussianBlur(z['T2'].astype(np.float32), (0, 0), 3)
        gy, gx = np.gradient(Tn)
        ln = np.maximum(np.hypot(gx, gy), 1e-9)
        nrm = torch.tensor(np.stack([cv2.GaussianBlur((gx / ln).astype(np.float32), (0, 0), 7),
                                     cv2.GaussianBlur((gy / ln).astype(np.float32), (0, 0), 7)], -1), device=DEV)
        N = _sample(nrm, U * T2.shape[1] / cw, V * T2.shape[0] / ch)
        self.N = N / N.norm(dim=-1, keepdim=True).clamp_min(1e-6)
        src = torch.tensor(s.source, dtype=torch.float32, device=DEV)
        self.U, self.V = U, V
        self.X0c = U + x0; self.Y0c = V + y0
        self.col = _sample(src, self.X0c, self.Y0c)
        self.seed = int(z['seed'])
        # 未开动表面：逐像素开动时刻（卡片外为 +∞）
        py, px = torch.meshgrid(torch.arange(self.H, device=DEV, dtype=torch.float32) + .5, torch.arange(self.W, device=DEV, dtype=torch.float32) + .5, indexing='ij')
        cu, cv_ = px - x0, py - y0
        inside = (cu >= 0) & (cu < cw) & (cv_ >= 0) & (cv_ < ch)
        T2i = T2.permute(2, 0, 1)[None]
        g = torch.stack([(cu.clamp(0, cw - 1e-3) * T2.shape[1] / cw) / T2.shape[1] * 2 - 1, (cv_.clamp(0, ch - 1e-3) * T2.shape[0] / ch) / T2.shape[0] * 2 - 1], -1)[None]
        Tp = torch.nn.functional.grid_sample(T2i, g, mode='bilinear', align_corners=False, padding_mode='border')[0, 0]
        fgt = torch.tensor(fg[..., 3:].astype(np.float32), device=DEV).permute(2, 0, 1)[None]
        g2 = torch.stack([cu.clamp(0, cw - 1e-3) / cw * 2 - 1, cv_.clamp(0, ch - 1e-3) / ch * 2 - 1], -1)[None]
        ap = torch.nn.functional.grid_sample(fgt, g2, mode='bilinear', align_corners=False, padding_mode='border')[0, 0]
        self.Tpix = torch.where(inside & (ap > 127.5), Tp, torch.full_like(Tp, float('inf')))
        self.src = src; self.bg = torch.tensor(s.bg, dtype=torch.float32, device=DEV)
        # 观测口径
        self.bgmax = torch.tensor(s.bgmax, device=DEV); self.bgmin = torch.tensor(s.bgmin, device=DEV)
        self.srcmax = torch.tensor(s.srcmax, device=DEV); self.srcmin = torch.tensor(s.srcmin, device=DEV)
        self.thr = torch.tensor(s.threshold, device=DEV); self.roi = torch.tensor(s.roi, device=DEV)
        self.bglab = lab8(self.bg)
        self.kern = _gauss_kernel(.012 * self.span)
        # 原片密度（与 core_iou.density 同口径，CPU 计算一次）
        from handoff_groups import Groups, lab
        G = Groups(s, 22.)
        prog = np.asarray(c.ref.progress, float)
        self.idx = [i for i in c.index if .1 <= prog[i] < .85] if frames == 'core' else [i for i in c.index if -.02 <= prog[i] <= .95]
        self.prog = [float(prog[i]) for i in self.idx]
        ref = []
        for i in self.idx:
            f = np.asarray(c.ref.frames[i])[..., :3]
            par = s.observe(f)['particle'] & s.roi
            de = np.linalg.norm(lab(f) - G.bgl, axis=2)
            vis = (par & (de >= 22.)).astype(np.float32)
            ref.append(torch.tensor(cv2.GaussianBlur(vis, (0, 0), .012 * self.span), dtype=torch.float16, device=DEV))
        self.ref = ref
        print(f'{name}: 颗粒 {len(u)}（格距 {self.hc:.2f} 像素），评测帧 {len(self.idx)}，准备 {time.time() - t0:.1f} 秒', flush=True)

    def curl_field(self, X, Y, P):
        """静态卷动场：若干随机傅里叶模态的流函数取旋度（无散），速度幅度 P.curl（短边/进度）。"""
        g = np.random.default_rng(self.seed + 77)
        k0 = 2 * math.pi / (P.curl_scale * self.span)
        ux = torch.zeros_like(X); uy = torch.zeros_like(Y); M = 8
        for m in range(M):
            ang = g.uniform(0, 2 * math.pi); kk = k0 * g.uniform(.7, 1.4); ph = g.uniform(0, 2 * math.pi)
            kx, ky = kk * math.cos(ang), kk * math.sin(ang)
            cs = torch.cos(kx * X + ky * Y + ph)
            ux += cs * (ky / kk); uy += -cs * (kx / kk)
        sc = P.curl * self.span * math.sqrt(2. / M)
        return ux * sc, uy * sc

    def birth(self, P):
        return self.T + P.jitter * (self.rand[:, 0] - .5)

    def positions(self, P, t, A=None, dth=0.):
        A = P.A if A is None else A
        Tb = self.birth(P)
        tau = (t - Tb).clamp_min(0.)
        if dth:
            th = math.radians(self.direction + dth); wx, wy = math.cos(th), -math.sin(th)
        else:
            wx, wy = self.wind
        jx = (self.rand[:, 1] - .5) * P.pos_jitter * self.cell[0]; jy = (self.rand[:, 2] - .5) * P.pos_jitter * self.cell[1]
        X0 = self.X0c + jx; Y0 = self.Y0c + jy

        def path(ta):
            sa = P.V0 * ta + .5 * A * ta * ta
            qa = P.Vn * P.tn * (1 - torch.exp(-ta / max(P.tn, 1e-4)))
            xa = X0 + self.span * (wx * sa + self.N[:, 0] * qa); ya = Y0 + self.span * (wy * sa + self.N[:, 1] * qa)
            if P.sv != 0:
                ang = self.rand[:, 3] * 2 * math.pi; mag = P.sv * torch.sqrt(-2 * torch.log(self.rand[:, 4].clamp_min(1e-6))) * .7071
                xa = xa + self.span * mag * torch.cos(ang) * ta; ya = ya + self.span * mag * torch.sin(ang) * ta
            return xa, ya
        X, Y = path(tau)
        if P.curl != 0:
            # 沿不含卷动的路径用 4 点高斯-勒让德积分静态场
            xi = (0.0694318, 0.3300095, 0.6699905, 0.9305682); wq = (0.1739274, 0.3260726, 0.3260726, 0.1739274)
            dx = torch.zeros_like(X); dy = torch.zeros_like(Y)
            for a, b in zip(xi, wq):
                xa, ya = path(tau * a)
                ux, uy = self.curl_field(xa, ya, P)
                dx += b * ux; dy += b * uy
            X = X + dx * tau; Y = Y + dy * tau
        return X, Y, tau, Tb

    def render(self, P, t, A=None, dth=0.):
        X, Y, tau, Tb = self.positions(P, t, A, dth)
        life = P.life * (1 + P.life_var * (2 * self.rand[:, 5] - 1))
        x = (tau / life).clamp(0, 1)
        side = P.size0 * (1 - x ** P.size_pow) * self.hc
        fade = (tau / max(P.fade_in, 1e-4)).clamp(0, 1)
        vis = (Tb < t) & (tau < life) & (side > 0.02)
        H, W = self.H, self.W
        acc = torch.zeros(H * W, device=DEV); cacc = torch.zeros(H * W, 3, device=DEV)
        g = (self.rand[:, 6] ** 3 * P.glint)[:, None]
        colw = self.col * P.gain
        if P.sb != 0:
            rr = torch.sqrt(-2 * torch.log(self.rand[:, 8].clamp_min(1e-6)))
            z1 = rr * torch.cos(2 * math.pi * self.rand[:, 9]); z2 = rr * torch.sin(2 * math.pi * self.rand[:, 9])
            z = z1 * torch.cos(P.tw * tau) + z2 * torch.sin(P.tw * tau) if P.tw != 0 else z1
            colw = colw * torch.exp(P.sb * z)[:, None]
        colw = colw.clamp(0, 255) * (1 - g) + 255. * g
        half = side * .5
        lx = X - half; ly = Y - half
        jx0 = torch.floor(lx).long(); jy0 = torch.floor(ly).long()
        for dj in range(3):
            cx0 = jx0 + dj
            ox = (torch.minimum(X + half, (cx0 + 1).float()) - torch.maximum(lx, cx0.float())).clamp_min(0)
            for di in range(3):
                cy0 = jy0 + di
                oy = (torch.minimum(Y + half, (cy0 + 1).float()) - torch.maximum(ly, cy0.float())).clamp_min(0)
                wv = ox * oy * fade
                ok = vis & (wv > 0) & (cx0 >= 0) & (cx0 < W) & (cy0 >= 0) & (cy0 < H)
                ind = (cy0 * W + cx0)[ok]; w_ = wv[ok]
                acc.index_add_(0, ind, w_); cacc.index_add_(0, ind, colw[ok] * w_[:, None])
        acc = acc.view(H, W); cacc = cacc.view(H, W, 3)
        cov = acc.clamp(max=1.)[..., None]
        pc = cacc / acc.clamp_min(1e-6)[..., None]
        base = torch.where((self.Tpix > t)[..., None], self.src, self.bg)
        out = base * (1 - cov) + pc.clamp(0, 255) * cov
        return out.round().clamp(0, 255)

    def density(self, frame):
        eb = torch.maximum((frame - self.bgmax).clamp_min(0), (self.bgmin - frame).clamp_min(0)).amax(-1)
        es = torch.maximum((frame - self.srcmax).clamp_min(0), (self.srcmin - frame).clamp_min(0)).amax(-1)
        par = (eb > self.thr) & (es > 36.) & self.roi
        de = (lab8(frame) - self.bglab).norm(dim=-1)
        vis = (par & (de >= 22.)).float()
        return blur(vis, self.kern)

    def loss(self, P, A=None, dth=0., frames=None):
        """拟合用：所选帧上三档 (1 − 交并比) 的均值（原片该档掩码太小的帧不计）。"""
        ks = range(len(self.idx)) if frames is None else frames
        inter = []; union = []; refn = []
        self._fz = []
        for k in ks:
            dm = self.density(self.render(P, self.prog[k], A, dth)); do = self.ref[k].float()
            if self.fuzzy:
                self._fz.append(torch.minimum(dm, do).sum() / torch.maximum(dm, do).sum().clamp_min(1.))
            for th_ in LV:
                mo, mm = do >= th_, dm >= th_
                inter.append((mo & mm).sum()); union.append((mo | mm).sum()); refn.append(mo.sum())
        I = torch.stack(inter).float().view(-1, 3); U_ = torch.stack(union).float().view(-1, 3); Rn = torch.stack(refn).float().view(-1, 3)
        ok = (U_ >= 50) & (Rn >= 20)
        iou = torch.where(ok, I / U_.clamp_min(1), torch.zeros_like(I))
        per = [(1 - iou[:, j][ok[:, j]]).mean().item() if ok[:, j].any() else 1. for j in range(3)]
        if self.fuzzy:
            fz = torch.stack(self._fz).mean().item(); self._fz = []
            return float(.5 * np.mean(per) + .5 * (1 - fz)), per + [1 - fz]
        return float(np.mean(per)), per

    def evaluate(self, P, A=None, frames=None, dth=0.):
        """逐帧三档交并比；返回 (3 档 × 3 时段) 中位与逐帧表。"""
        rows = []
        for k, (i, p) in enumerate(zip(self.idx, self.prog)):
            if frames is not None and k not in frames: continue
            dm = self.density(self.render(P, p, A, dth)); do = self.ref[k].float()
            r = [p]
            for t in LV:
                mo, mm = do >= t, dm >= t
                u = (mo | mm).sum().item()
                r.append((mo & mm).sum().item() / u if u >= 50 and mo.sum().item() >= 20 else np.nan)
            rows.append(r)
        R = np.array(rows)
        med = np.array([[np.nanmedian(R[(R[:, 0] >= lo) & (R[:, 0] < hi), 1 + j]) if ((R[:, 0] >= lo) & (R[:, 0] < hi)).any() else np.nan
                         for lo, hi in BINS] for j in range(len(LV))])
        return med, R


FRAMES = HERE / 'analysis/match-cache/rebuild/frames'


def export_frames(sc, P, A, dth, tag, count=121):
    """按 0..1 共 count 帧渲染并存成与 handoff_review.render_all 相同形状的 npy（研究工具用 spec='cf:<标签>' 读取）。"""
    out = FRAMES / tag; out.mkdir(parents=True, exist_ok=True)
    path = out / f'{sc.name}.npy'; tmp = path.with_suffix('.partial.npy')
    arr = np.lib.format.open_memmap(tmp, mode='w+', dtype='uint8', shape=(count, sc.H, sc.W, 3))
    for k in range(count):
        arr[k] = sc.render(P, k / (count - 1), A, dth).byte().cpu().numpy()
    arr.flush(); del arr
    tmp.replace(path)
    return path


def fmt(med):
    return ' | '.join('/'.join(f'{x:.2f}' for x in med[j]) for j in range(len(LV)))


def sheet(sc, P, A, path, ps=(.15, .3, .45, .6, .75), zoom=None):
    rows = []
    for p in ps:
        k = int(np.argmin(np.abs(np.array(sc.prog) - p))); i = sc.idx[k]
        ref = np.asarray(sc.c.ref.frames[i])[..., :3]
        mod = sc.render(P, sc.prog[k], A).byte().cpu().numpy()
        if zoom:
            x0, y0, x1, y1 = zoom
            pair = np.concatenate([ref[y0:y1, x0:x1], mod[y0:y1, x0:x1]], 1)
            pair = cv2.resize(pair, None, fx=2, fy=2, interpolation=cv2.INTER_NEAREST)
        else:
            x0, y0, x1, y1 = sc.rect; m = int(sc.span * .45)
            box = (max(0, y0 - m), min(sc.H, y1 + m), max(0, x0 - m), min(sc.W, x1 + m))
            pair = np.concatenate([ref[box[0]:box[1], box[2]:box[3]], mod[box[0]:box[1], box[2]:box[3]]], 1)
        rows.append(pair)
    Image.fromarray(np.concatenate(rows, 0)).save(path, quality=88)


if __name__ == '__main__':
    import argparse
    ap = argparse.ArgumentParser()
    ap.add_argument('scenes'); ap.add_argument('--params', default='{}'); ap.add_argument('--A', default='{}')
    ap.add_argument('--sheet', default=''); ap.add_argument('--zoom', default='{}')
    a = ap.parse_args()
    P = replace(Params(), **json.loads(a.params)); Atab = json.loads(a.A); zooms = json.loads(a.zoom)
    tot = []
    for name in a.scenes.split(','):
        sc = SceneCF(name)
        t0 = time.time()
        med, R = sc.evaluate(P, Atab.get(name))
        tot.append(med)
        print(f'{name:22s} {fmt(med)}  （{time.time() - t0:.1f} 秒）', flush=True)
        if a.sheet:
            sheet(sc, P, Atab.get(name), f'{a.sheet}-{name}.jpg')
            if name in zooms: sheet(sc, P, Atab.get(name), f'{a.sheet}-{name}-zoom.jpg', ps=(.2, .3, .4), zoom=zooms[name])
    print('平均', fmt(np.nanmean(np.stack(tot), 0)))
