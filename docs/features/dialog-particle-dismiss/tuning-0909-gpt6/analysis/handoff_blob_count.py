"""在深色、干净背景的区域数颗粒：连通块数量、面积分布、峰值亮度（原片与模型同口径）。

只用于标定颗粒的数量与大小；区域由命令行给出（场景像素），应选颗粒稀疏、背景干净的位置。
"""
from pathlib import Path
import argparse, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_refs import RefFrames
from match_observe import Scene
from handoff_review import render_all


def blobs(scene, frame, box, threshold):
    x0, y0, x1, y1 = box
    f = frame[y0:y1, x0:x1].astype(np.float32); bg = scene.bgmax[y0:y1, x0:x1]
    excess = np.maximum(f - bg, 0).max(2)
    mask = (excess > threshold).astype(np.uint8)
    n, lab, st, _ = cv2.connectedComponentsWithStats(mask, connectivity=8)
    areas = st[1:, cv2.CC_STAT_AREA]
    peak = np.array([excess[lab == k].max() for k in range(1, n)]) if n > 1 else np.zeros(0)
    return dict(count=int(n - 1), area_q=np.quantile(areas, [.25, .5, .75, .9]).round(1).tolist() if len(areas) else [],
                fill=float(mask.mean()), peak_med=float(np.median(peak)) if len(peak) else 0.)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', default='ironman')
    ap.add_argument('--box', type=int, nargs=4, required=True)
    ap.add_argument('--phases', type=float, nargs='+', default=[.6, .65, .7, .75])
    ap.add_argument('--threshold', type=float, default=40.)
    ap.add_argument('--variants', nargs='*', default=[])
    a = ap.parse_args()
    s = Scene(a.scene); ref = RefFrames(a.scene)
    frames = {}
    for v in a.variants:
        label, rest = v.split('=', 1); d, _, spec = rest.partition('::'); p = Path(d)
        frames[label] = render_all(a.scene, p if p.is_absolute() else HERE / p, label, spec=spec)
    for p in a.phases:
        i, f = ref.nearest(p)
        out = [('原片', blobs(s, np.asarray(f), a.box, a.threshold))]
        out += [(l, blobs(s, np.asarray(fr[round(p * 120)]), a.box, a.threshold)) for l, fr in frames.items()]
        print(f'{p:.2f}  ' + '  '.join(f'{l}: 数{r["count"]} 面积{r["area_q"]} 填{r["fill"]:.3f} 峰{r["peak_med"]:.0f}' for l, r in out))


if __name__ == '__main__':
    main()
