"""估计每段原片相对源图的录制模糊：取动画开始前卡片完整的原片帧，对源图做不同 σ 的高斯模糊，
在卡片内与原片比较高频能量，选最接近的 σ。只用于公平比较颗粒外观（模型帧做同样模糊），不进入运行。"""
from pathlib import Path
import json, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from match_refs import RefFrames
from match_observe import Scene

SCENES = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference'] + [f'reference-0926-{i}' for i in range(1, 8)]


def energy(img, mask):
    g = cv2.cvtColor(img.astype(np.uint8), cv2.COLOR_RGB2GRAY).astype(np.float32)
    hp = g - cv2.GaussianBlur(g, (0, 0), 1.5)
    return float(np.abs(hp)[mask].mean())


def main():
    out = {}
    for name in SCENES:
        s = Scene(name); ref = RefFrames(name)
        i = int(np.argmin(np.abs(ref.progress - (-.03))))
        frame = np.asarray(ref.frames[i]).astype(np.float32)
        mask = cv2.erode(s.card.astype(np.uint8), np.ones((15, 15), np.uint8)).astype(bool) & s.distinct
        target = energy(frame, mask)
        best = None
        for sigma in np.arange(0., 3.01, .1):
            src = s.source if sigma == 0 else cv2.GaussianBlur(s.source, (0, 0), float(sigma))
            e = energy(src, mask)
            if best is None or abs(e - target) < best[1]: best = (float(sigma), abs(e - target), e)
        out[name] = dict(sigma=round(best[0], 2), frame_energy=round(target, 3), progress=float(ref.progress[i]))
        print(f'{name:>22}  σ≈{best[0]:.1f}  原片高频 {target:.2f}  源图模糊后 {best[2]:.2f}  （进度 {ref.progress[i]:.3f}）')
    (HERE / 'analysis/match-cache/handoff-review/capture-blur.json').write_text(json.dumps(out, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
