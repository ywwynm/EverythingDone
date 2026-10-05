"""重建用的拟合目标：从每段原片提取「看得见的颗粒」的疏密图、可见材料的运动场与逐块解除时刻，存成 npz，供新模型自动拟合。

口径与研究工具一致：
- 可见颗粒 = 偏离背景且偏离原位控件、与背景 Lab 色差 ≥ 22 的像素（handoff_groups.Groups），按 σ = 0.012 短边平滑得到密度 D；
- 运动场 = 相邻独立帧 Farneback 光流在可见颗粒上的掩码归一化平滑（σ = 0.02 短边），单位：短边 / 单位进度，y 向下；
- 逐块解除时刻 T = release_integrity.Probe.huawei()（10 像素块，表面完整度降到一半以下且不再回升的进度）。
输出 analysis/match-cache/rebuild/targets/<段>.npz：
progress（独立帧进度）、frames（半分辨率原片 uint8）、D（半分辨率 float16）、flow_p0/flow_p1（每个运动场对应的两帧进度）、
U/V/W（四分之一分辨率 float16：速度与可见权重）、T/T_valid（块）、bg/source/roi（半分辨率）与几何、输入（方向、起点、屏幕范围、录像模糊）。
用法：rebuild_targets.py [段 ...]（缺省十段）
"""
from pathlib import Path
import json, sys, time
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_compare import Comparison
from handoff_groups import Groups, lab
from release_integrity import Probe, B
from recording_codec import capture_blur
from match_observe import SCREEN
from v2_inputs import OBSERVED_SITES

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference', 'reference-0926-1',
          'reference-0926-4', 'reference-0926-5', 'reference-0926-6', 'reference-0926-7']
OUT = HERE / 'analysis/match-cache/rebuild/targets'


def gray(f): return cv2.cvtColor(np.ascontiguousarray(f[..., :3]), cv2.COLOR_RGB2GRAY)


def half(a, interp=cv2.INTER_AREA):
    h, w = a.shape[:2]
    return cv2.resize(a, (w // 2, h // 2), interpolation=interp)


def quarter(a):
    h, w = a.shape[:2]
    return cv2.resize(a, (w // 4, h // 4), interpolation=cv2.INTER_AREA)


def visible_pixels(G, frame):
    s = G.s
    par = s.observe(frame)['particle'] & s.roi
    de = np.linalg.norm(lab(frame) - G.bgl, axis=2)
    return (par & (de >= 22.)).astype(np.float32)


def extract(name):
    t0 = time.time()
    c = Comparison(name); s = c.scene; G = Groups(s, 22.); span = float(s.span)
    prog = np.asarray(c.ref.progress, float)
    idx = [i for i in c.index if -.02 <= prog[i] <= .95]
    frames, D = [], []
    for i in idx:
        f = np.asarray(c.ref.frames[i])[..., :3]
        frames.append(half(f))
        D.append(half(cv2.GaussianBlur(visible_pixels(G, f), (0, 0), .012 * span)).astype(np.float16))
    U, V, Wt, fp0, fp1 = [], [], [], [], []
    sg = .02 * span
    for k in range(len(idx) - 1):
        i, j = idx[k], idx[k + 1]; dt = prog[j] - prog[i]
        if dt <= 0: continue
        A, Bf = np.asarray(c.ref.frames[i])[..., :3], np.asarray(c.ref.frames[j])[..., :3]
        m = visible_pixels(G, A)
        F = cv2.calcOpticalFlowFarneback(gray(A), gray(Bf), None, .5, 4, 15, 3, 5, 1.1, 0) / span / dt
        den = cv2.GaussianBlur(m, (0, 0), sg)
        u = cv2.GaussianBlur(F[..., 0] * m, (0, 0), sg) / np.maximum(den, 1e-4)
        v = cv2.GaussianBlur(F[..., 1] * m, (0, 0), sg) / np.maximum(den, 1e-4)
        U.append(quarter(u).astype(np.float16)); V.append(quarter(v).astype(np.float16)); Wt.append(quarter(den).astype(np.float16))
        fp0.append(prog[i]); fp1.append(prog[j])
    pr = Probe(name); T = pr.huawei().astype(np.float32); Tv = pr.valid & np.isfinite(T) & (T < .99)
    W_, H_ = s.meta['frame']
    meta = dict(name=name, frame=[int(W_), int(H_)], rect=[int(q) for q in s.meta['rect']], span=span,
                direction=float(s.meta['direction']), sites=[list(map(float, q)) for q in OBSERVED_SITES[name]],
                screen=list(SCREEN.get(name, (0, 0, int(W_), int(H_)))), capture_blur=float(capture_blur(name)), block=int(B),
                handheld=name in {'reference-0926-1', 'reference-0926-4', 'reference-0926-7', 'ironman-up-reference'},
                density_sigma_span=.012, flow_sigma_span=.02, contrast=22.)
    OUT.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(OUT / f'{name}.npz', progress=prog[idx].astype(np.float32), frames=np.stack(frames).astype(np.uint8),
                        D=np.stack(D), U=np.stack(U), V=np.stack(V), W=np.stack(Wt),
                        flow_p0=np.asarray(fp0, np.float32), flow_p1=np.asarray(fp1, np.float32),
                        T=T, T_valid=Tv, bg=half(np.clip(s.bg, 0, 255).astype(np.uint8)),
                        source=half(np.clip(s.source, 0, 255).astype(np.uint8)), roi=half(s.roi.astype(np.uint8), cv2.INTER_NEAREST).astype(bool),
                        meta=json.dumps(meta, ensure_ascii=False))
    print(f'{name}: {len(idx)} 帧、{len(U)} 个运动场，{time.time() - t0:.0f} 秒', flush=True)


if __name__ == '__main__':
    for n in (sys.argv[1:] or SCENES):
        extract(n)
