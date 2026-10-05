"""相邻独立帧的小位移光流：在指定区域内只取高亮颗粒像素，给出随进度变化的中位速度。

只在帧间位移很小（1～3 像素）的高帧率原片上使用；速度以控件短边/单位进度表示。
同时对模型缓存帧做同口径测量（模型帧间隔取 1/120，与原片不同，仅作量级比较）。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_refs import RefFrames

OUT = HERE / 'analysis/match-cache/handoff-review'


def flow_between(a, b, box, bright):
    x0, y0, x1, y1 = box
    ga = cv2.cvtColor(np.ascontiguousarray(a[y0:y1, x0:x1]), cv2.COLOR_RGB2GRAY)
    gb = cv2.cvtColor(np.ascontiguousarray(b[y0:y1, x0:x1]), cv2.COLOR_RGB2GRAY)
    dis = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
    f = dis.calc(ga, gb, None)
    m = (ga >= bright) & (gb >= bright - 20)
    return f, m


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scene', default='reference-0926-2')
    ap.add_argument('--box', type=int, nargs=4, required=True)
    ap.add_argument('--start', type=float, default=.3)
    ap.add_argument('--end', type=float, default=.8)
    ap.add_argument('--bright', type=int, default=150)
    ap.add_argument('--every', type=int, default=6, help='每隔几个独立帧取一对相邻帧')
    a = ap.parse_args()
    ref = RefFrames(a.scene); m = ref.meta; x, y, x1, y1 = m['rect']; span = min(x1 - x, y1 - y)
    idx = [i for i in range(len(ref) - 1) if a.start <= ref.progress[i] <= a.end][::a.every]
    rows = []
    for i in idx:
        dp = float(ref.progress[i + 1] - ref.progress[i])
        f, mask = flow_between(np.asarray(ref.frames[i]), np.asarray(ref.frames[i + 1]), a.box, a.bright)
        if mask.sum() < 30 or dp <= 0: continue
        v = f[mask] / (dp * span)
        rows.append(dict(p=float(ref.progress[i]), n=int(mask.sum()), vx=float(np.median(v[:, 0])), vy=float(np.median(v[:, 1])),
                         speed=float(np.median(np.hypot(v[:, 0], v[:, 1]))), px=float(np.median(np.hypot(f[mask][:, 0], f[mask][:, 1])))))
    print(f'{a.scene} 区域 {a.box} 短边 {span}px；速度单位：短边/单位进度（y 向下为正）')
    for r in rows:
        print(f"p={r['p']:.3f} 像素 {r['n']:5d} 中位位移 {r['px']:.2f}px  速度 ({r['vx']:+.2f},{r['vy']:+.2f}) |v| {r['speed']:.2f}")
    (OUT / f'ring-flow-{a.scene}.json').write_text(json.dumps(rows, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
