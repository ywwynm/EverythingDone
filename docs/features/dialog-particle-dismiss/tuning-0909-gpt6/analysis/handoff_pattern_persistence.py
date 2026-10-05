"""斑点图案在相邻独立帧之间的延续性：高通亮度的归一化互相关（在 ±r 像素内取最佳整数平移），
以及每帧高频能量（检查清晰帧与偏糊帧是否交替）。原片与模型同口径，只作诊断。

若颗粒图案整体平流，最佳平移后的相关应较高且平移量平稳；若颗粒各自随机运动，相关随帧距迅速下降。
"""
from pathlib import Path
import argparse, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_refs import RefFrames
from handoff_review import render_all

LUM = np.float32([.2126, .7152, .0722])


def hp(frame, box):
    x0, y0, x1, y1 = box
    l = frame[y0:y1, x0:x1].astype(np.float32) @ LUM
    return l - cv2.GaussianBlur(l, (0, 0), 3.)


def best_shift(a, b, r):
    best = (-2., 0, 0)
    h, w = a.shape
    for dy in range(-r, r + 1):
        for dx in range(-r, r + 1):
            aa = a[max(0, dy):h + min(0, dy), max(0, dx):w + min(0, dx)]
            bb = b[max(0, -dy):h + min(0, -dy), max(0, -dx):w + min(0, -dx)]
            aa = aa - aa.mean(); bb = bb - bb.mean()
            c = float((aa * bb).sum() / np.sqrt((aa * aa).sum() * (bb * bb).sum() + 1e-9))
            if c > best[0]: best = (c, dx, dy)
    return best


def blockwise(a, b, r, size):
    """把框切成 size×size 小块，各自取最佳平移后的相关，返回中位数（扣除框内速度梯度的影响）。"""
    H, W = a.shape; vals = []
    for y in range(r, H - size - r + 1, size):
        for x in range(r, W - size - r + 1, size):
            t = a[y:y + size, x:x + size]
            if t.std() < 3: continue
            win = b[y - r:y + size + r, x - r:x + size + r]
            import cv2 as _cv
            res = _cv.matchTemplate(win.astype(np.float32), t.astype(np.float32), _cv.TM_CCOEFF_NORMED)
            vals.append(float(res.max()))
    return float(np.median(vals)) if vals else np.nan


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', default='ironman')
    ap.add_argument('--box', type=int, nargs=4, required=True)
    ap.add_argument('--start', type=float, default=.4)
    ap.add_argument('--count', type=int, default=10)
    ap.add_argument('--gaps', type=int, nargs='+', default=[1, 2, 4])
    ap.add_argument('--radius', type=int, default=8)
    ap.add_argument('--spec', default=None, help='给出时同时测模型（handoff_variants 变体，空串为默认完整链）')
    ap.add_argument('--block', type=int, default=0, help='>0 时改用分块配准（块边长，像素）')
    a = ap.parse_args()
    if a.block > 0:
        # 分块配准：外扩搜索半径，框内每个小块各自对齐。
        global best_shift
        pad = a.radius
        x0, y0, x1, y1 = a.box
        a.box = [x0 - pad, y0 - pad, x1 + pad, y1 + pad]
        best_shift = lambda A, Bm, r: (blockwise(A, Bm, r, a.block), 0, 0)
    ref = RefFrames(a.scene)
    i0 = int(np.argmin(abs(ref.progress - a.start)))
    idx = list(range(i0, min(len(ref), i0 + a.count + max(a.gaps))))
    H = {i: hp(np.asarray(ref.frames[i]), a.box) for i in idx}
    print('原片高频能量:', ' '.join(f'#{i}:{H[i].std():.1f}' for i in idx[:a.count]))
    for g in a.gaps:
        res = [best_shift(H[i], H[i + g], a.radius) for i in idx[:a.count] if i + g in H]
        print(f'原片 间隔{g}帧(Δp≈{g * float(np.median(np.diff(ref.progress))):.4f}): 相关 ' + ' '.join(f'{c:.2f}' for c, _, _ in res)
              + '  平移 ' + ' '.join(f'({dx},{dy})' for _, dx, dy in res))
    if a.spec is not None:
        frames = render_all(a.scene, HERE, 'persistence', spec=a.spec)
        dp = float(np.median(np.diff(ref.progress)))
        # 模型帧按 1/120 进度采样；取与原片帧间隔最接近的整数帧距。
        ps = [float(ref.progress[i]) for i in idx[:a.count]]
        M = lambda p: hp(np.asarray(frames[round(np.clip(p, 0, 1) * 120)]), a.box)
        print('模型高频能量:', ' '.join(f'{p:.3f}:{M(p).std():.1f}' for p in ps))
        for g in a.gaps:
            step = max(1, round(g * dp * 120))
            res = [best_shift(M(p), hp(np.asarray(frames[min(120, round(p * 120) + step)]), a.box), a.radius) for p in ps]
            print(f'模型 间隔{step}/120: 相关 ' + ' '.join(f'{c:.2f}' for c, _, _ in res) + '  平移 ' + ' '.join(f'({dx},{dy})' for _, dx, dy in res))


if __name__ == '__main__':
    main()
