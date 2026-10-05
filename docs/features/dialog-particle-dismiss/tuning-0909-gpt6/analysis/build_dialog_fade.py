"""开场确认框的淡出尾段（逐帧遮挡层截止之后还看得见的那一段，参考 4、参考 5）：只用于参考复刻，见 replica 文档第 43 节。

逐帧遮挡层（build_opening_frames.py）按「与不画颗粒的模型差 > 28」抠确认框，淡到后半段差值不够就抠不到了，
而原片里参考 4 的确认框要到 0.13 左右才看不见。确认框是盖在卡片上的半透明层：同一处卡片未碎时，
原片 − 不画颗粒的模型 = f(t)·K(x)，K 是确认框完整可见时的加性图案，f 是淡出系数。
K 取 p_ref 那一帧（确认框完整、卡片未碎）与 p_late 那一帧（确认框已看不见、卡片仍未碎）残差之差——减掉卡片本身
与模型的静态差别，否则 f 会停在一个平台上不归零；手指按原有的逐帧手指层（occluder-before-opening/）排除。
确认框取差值里外接框填充率最高的大连通块（圆角矩形）。f 逐帧最小二乘（只用确认框内两帧都是卡片完整、附近无颗粒、
不在手指层里的像素），保序回归成单调不增，p_late 之后为 0。
渲染器在 p_start（逐帧遮挡层里确认框的截止进度）之后按 f 加上 f·K（卡片已碎处的误差为 f·A·(卡片 − 现在的底)，f 小时可忽略）。
产物：assets/<段>/dialog-fade.npz（K、box、progress、f、p_start）。
用法：python analysis/build_dialog_fade.py <段名> <p_ref> <p_start> <p_late> [确认框场景矩形 x0,y0,x1,y1]
"""
from pathlib import Path
import json
import sys

import cv2
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'analysis')]
from harmony_native import NativeRenderer, LIVE_RASTER, inputs_from_entry
from match_compare import Comparison


def pava_decreasing(y, w):
    """加权保序回归（单调不增）。"""
    y = list(-np.asarray(y, np.float64)); w = list(np.asarray(w, np.float64))
    blocks = [[yi, wi, 1] for yi, wi in zip(y, w)]
    i = 0
    while i < len(blocks) - 1:
        if blocks[i][0] > blocks[i + 1][0]:
            a, b = blocks[i], blocks[i + 1]
            wsum = a[1] + b[1]
            blocks[i] = [(a[0] * a[1] + b[0] * b[1]) / wsum, wsum, a[2] + b[2]]
            del blocks[i + 1]
            i = max(i - 1, 0)
        else:
            i += 1
    out = []
    for v, _, n in blocks:
        out += [-v] * n
    return np.array(out)


def finger_alpha(d, W, H):
    """原有的逐帧手指层（建开场确认框之前的备份）：按进度返回整幅 alpha。"""
    meta = d / 'occluder-before-opening' / 'occluder-frames.json'
    if not meta.exists():
        return lambda p: np.zeros((H, W), np.float32)
    from PIL import Image
    o = json.loads(meta.read_text('utf-8'))
    tol = float(o.get('tolerance', 0.005))
    fr = []
    for e in o['frames']:
        a = np.asarray(Image.open(d / 'occluder-before-opening' / e['file']).convert('RGBA'))[..., 3].astype(np.float32) / 255.0
        fr.append((float(e['progress']), e['box'], a))
    def get(p):
        full = np.zeros((H, W), np.float32)
        if fr:
            k = min(range(len(fr)), key=lambda j: abs(fr[j][0] - p))
            p_, (bx0, by0, bx1, by1), a = fr[k]
            if abs(p_ - p) <= tol * 2:
                full[by0:by1, bx0:bx1] = a
        return cv2.dilate(full, np.ones((15, 15), np.uint8))
    return get


def main():
    name, p_ref, p_start, p_late = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
    table = json.loads((ROOT / 'native_reference_inputs.json').read_text('utf-8'))
    c = Comparison(name)
    x0, y0, x1, y1 = c.scene.rect
    h = y1 - y0
    W, H = c.scene.W, c.scene.H
    bx0, by0, bx1, by1 = max(0, x0 - 40), int(y0 + 0.3 * h), min(W, x1 + 40), min(H, y1 + 260)
    box = (slice(by0, by1), slice(bx0, bx1))
    d = ROOT / 'assets' / name
    finger = finger_alpha(d, W, H)
    r = NativeRenderer(name, inputs_from_entry(table[name]), raster=LIVE_RASTER, ss=1)
    r.occluders = []
    r.glare = None
    lum = np.float32([.299, .587, .114])
    prog = c.ref.progress

    def state(p):
        i = int(np.argmin(np.abs(prog - p)))
        p = float(prog[i])
        T = r.device_time(p)
        full = r.render_time(T, q=p).astype(np.float32)
        bare = r.render_time(T, particles=False, q=p).astype(np.float32)
        cov = r.card_alpha()
        part = cv2.dilate((np.abs((full - bare) @ lum) >= 2).astype(np.uint8), np.ones((31, 31), np.uint8)).astype(bool)
        R = np.asarray(c.ref.frames[i]).astype(np.float32) - bare
        ok = (cov > 0.98) & ~part & (finger(p) < 0.02)
        return p, R[box], ok[box]

    p0, R0, ok0 = state(p_ref)
    pl, RL, okl = state(p_late)
    # 确认框的形状取自 p_ref（完整可见、反差最大）：卡片矩形内、手指以外，残差最大的近似矩形连通块
    inside = np.zeros(R0.shape[:2], bool)
    inside[max(0, y0 - by0):y1 - by0, max(0, x0 - bx0):x1 - bx0] = True
    # 磨砂玻璃的确认框把底下的画面往中性灰白推，增量近似无色；手指（肤色压在蓝底上）增量偏暖，按色差排除
    neutral = (np.abs(R0[..., 0] - R0[..., 2]) < 40) & (np.abs(R0[..., 1] - 0.5 * (R0[..., 0] + R0[..., 2])) < 30)
    m = ((np.abs(R0 @ lum) > 10) & inside & neutral).astype(np.uint8)
    m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((5, 5), np.uint8))
    m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((15, 15), np.uint8))
    n, lab, st, _ = cv2.connectedComponentsWithStats(m, connectivity=8)
    best, best_score = None, 0.0
    for k in range(1, n):
        x, y, w, hh, area = st[k]
        fill = area / max(w * hh, 1)
        score = area * fill ** 2
        if area > 3000 and score > best_score:
            best, best_score = k, score
    if best is None:
        print(name, '找不到确认框'); r.close(); return
    x, y, w, hh, area = st[best]
    # 确认框是圆角矩形：按行、列的占比裁掉连通块里伸出去的部分（占比 < 0.6 的行列），再画圆角矩形；
    # 手指横穿确认框、背景同时被压暗时自动检测会截成一半，这时用第 5 个参数给出确认框的场景矩形
    if len(sys.argv) > 5:
        ax0, ay0, ax1, ay1 = (int(v) for v in sys.argv[5].split(','))
        ya, yb, xa, xb = ay0 - by0, ay1 - by0, ax0 - bx0, ax1 - bx0
    else:
        comp = (lab == best)
        rows = comp.sum(1) / max(1, w); cols = comp.sum(0) / max(1, hh)
        ry = np.nonzero(rows >= 0.6)[0]; rx = np.nonzero(cols >= 0.6)[0]
        ya, yb, xa, xb = int(ry.min()), int(ry.max()) + 1, int(rx.min()), int(rx.max()) + 1
    rad = int(min(yb - ya, xb - xa) * 0.12)
    M = np.zeros(R0.shape[:2], np.uint8)
    cv2.rectangle(M, (xa + rad, ya), (xb - 1 - rad, yb - 1), 1, -1)
    cv2.rectangle(M, (xa, ya + rad), (xb - 1, yb - 1 - rad), 1, -1)
    for cx_, cy_ in ((xa + rad, ya + rad), (xb - 1 - rad, ya + rad), (xa + rad, yb - 1 - rad), (xb - 1 - rad, yb - 1 - rad)):
        cv2.circle(M, (cx_, cy_), rad, 1, -1)
    w, hh, area = xb - xa, yb - ya, int(M.sum())
    Mf = cv2.GaussianBlur(M.astype(np.float32), (0, 0), 1.5)
    # K = p_ref 残差 − 卡片本身与模型的静态差别：两帧卡片都完整处直接取 p_late 的残差，其余处取它的平滑外推
    good = (M > 0) & ok0 & okl
    RLg = np.where(okl[..., None], RL, 0.0).astype(np.float32)
    num = np.stack([cv2.GaussianBlur(RLg[..., k], (0, 0), 12.0) for k in range(3)], -1)
    den = cv2.GaussianBlur(okl.astype(np.float32), (0, 0), 12.0)[..., None]
    S = np.where(okl[..., None], RL, np.where(den > 0.05, num / np.maximum(den, 1e-3), 0.0))
    D = R0 - S
    K = D * Mf[..., None]
    print(f'{name} p_ref={p0:.3f} p_late={pl:.3f} 确认框 {w}x{hh} 填充率 {area / (w * hh):.2f} 两帧可用占比 {good.sum() / max(M.sum(), 1):.2f} '
          f'|K| 均值 {np.abs(D[M > 0] @ lum).mean():.1f}')
    ps, fs, ws = [], [], []
    Mb = M.astype(bool)
    for i in range(len(prog)):
        p = float(prog[i])
        if p <= p_start or p >= pl:
            continue
        _, R, ok = state(p)
        q = Mb & ok & okl
        if q.sum() < 500:
            continue
        f = float(((R - RL)[q] * K[q]).sum() / max((K[q] * K[q]).sum(), 1e-6))
        ps.append(p); fs.append(min(max(f, 0.0), 1.0)); ws.append(float(q.sum()))
    r.close()
    if not ps:
        print(name, '没有可用帧'); return
    fm = pava_decreasing(fs, ws)
    ps.append(pl); fm = np.append(fm, 0.0)
    out = d / 'dialog-fade.npz'
    np.savez_compressed(out, K=K.astype(np.float16), box=np.array([by0, by1, bx0, bx1]), progress=np.array(ps),
                        f=fm.astype(np.float32), p_start=float(p_start))
    print(name, '淡出系数（原始 → 单调）', ' '.join(f'{p:.3f}:{a:.2f}→{b:.2f}' for p, a, b in zip(ps, fs + [0.0], fm)))


if __name__ == '__main__':
    main()
