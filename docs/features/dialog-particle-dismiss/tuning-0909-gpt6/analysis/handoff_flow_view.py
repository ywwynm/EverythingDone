"""共同速度表在自身坐标中的样子：释放时刻等值线 + 各时刻速度箭头，并按「释放后年龄」着色。

只读 shared/particle-dismiss 的共同资源，用于理解其结构；不改任何资源。
"""
from pathlib import Path
import sys
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from unified_model import RULES, RELEASE, guidance, flow_confidence

OUT = HERE / 'analysis/match-cache/handoff-review'


def main():
    flow = guidance()                      # (T, H, W, 2)
    conf = flow_confidence().astype('float32') / 255.
    nt, nh, nw, _ = flow.shape
    low, size = RULES['field_min'], RULES['field_size']
    # 速度表与释放表共用归一化坐标 [low, low+size]。
    u = low + (np.arange(nw) + .5) / nw * size; v = low + (np.arange(nh) + .5) / nh * size
    ru = low + (np.arange(RELEASE.shape[1]) + .5) / RELEASE.shape[1] * size
    rv = low + (np.arange(RELEASE.shape[0]) + .5) / RELEASE.shape[0] * size
    times = [.10, .25, .40, .55, .70, .85]
    fig, axes = plt.subplots(2, 3, figsize=(18, 12))
    for ax, t in zip(axes.ravel(), times):
        k = min(nt - 1, int(round(t * (nt - 1))))
        f = flow[k]; c = conf[k]
        # 年龄：该点当前时刻减去释放时刻（释放表插值到速度网格）。
        from scipy.ndimage import map_coordinates
        vv, uu = np.meshgrid(v, u, indexing='ij')
        T = map_coordinates(RELEASE, [(vv - low) / size * RELEASE.shape[0] - .5, (uu - low) / size * RELEASE.shape[1] - .5], order=1, mode='nearest')
        age = t - T
        ax.contour(ru, rv, RELEASE, levels=np.arange(0, .9, .05), colors='gray', linewidths=.6)
        ax.contour(ru, rv, RELEASE, levels=[t], colors='red', linewidths=2)
        s = 2
        speed = np.hypot(f[..., 0], f[..., 1])
        sl = (slice(None, None, s), slice(None, None, s))
        trusted = c[sl] > .5
        q = ax.quiver(uu[sl][trusted], vv[sl][trusted], f[sl][..., 0][trusted], -f[sl][..., 1][trusted],
                      np.clip(age[sl][trusted], -.2, .6), cmap='viridis', clim=(-.2, .6), scale=12, width=.003)
        ax.quiver(uu[sl][~trusted], vv[sl][~trusted], f[sl][..., 0][~trusted], -f[sl][..., 1][~trusted],
                  color=(.75, .75, .75), scale=12, width=.002)
        ax.plot([0, 1, 1, 0, 0], [0, 0, 1, 1, 0], 'k-', lw=1.5)
        ax.set_xlim(low, low + size); ax.set_ylim(low + size, low); ax.set_aspect('equal')
        ax.set_title(f't={t:.2f}  |v| 中位 {np.median(speed):.2f}  红线=当前释放前沿')
    fig.colorbar(q, ax=axes, shrink=.6, label='释放后年龄')
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei']
    out = OUT / 'common-flow.png'; fig.savefig(out, dpi=70); print(out)
    print('速度表形状', flow.shape, '释放表', RELEASE.shape, '释放范围', float(RELEASE.min()), float(RELEASE.max()))


if __name__ == '__main__':
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei']
    main()
