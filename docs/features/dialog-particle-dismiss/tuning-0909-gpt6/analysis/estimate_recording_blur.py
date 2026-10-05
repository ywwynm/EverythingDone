"""按段估计录制模糊 σ（评估口径用，不进入模型）：在已释放、背景可观测的颗粒区域，比较原片与「模型帧经 σ 高斯模糊后」的高频能量，
取使两者相等的 σ。前提是模型颗粒的固有高频与华为相当（清晰录屏上已按颗粒尺度与亮峰定标），所以结果只作量级参考。

素材前景图是从录像截出的卡片（本身含模糊），不能用「素材对首帧」的办法。
"""
from pathlib import Path
import argparse, json, sys
import numpy as np, cv2

HERE = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(HERE), str(HERE / 'analysis')]
from release_integrity import Probe, B
from handoff_review import render_all, OUT
from handoff_scorecard import model_release
from handoff_cloud import SCENES

LUM = np.float32([.2126, .7152, .0722])
SIGMAS = [0., .3, .5, .7, .9, 1.1, 1.4, 1.8]


def hf(frame, mask):
    l = frame.astype(np.float32) @ LUM
    h = l - cv2.GaussianBlur(l, (0, 0), 1.5)
    return float((h[mask] ** 2).mean())


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--scenes', nargs='+', default=SCENES)
    ap.add_argument('--spec', required=True, help='用于比较的模型变体（handoff_variants）')
    ap.add_argument('--out', default=str(OUT / 'recording-blur.json'))
    a = ap.parse_args()
    report = {}
    for name in a.scenes:
        probe = Probe(name); c = probe.comp; s = probe.scene
        if not s.roi.any(): continue
        frames = render_all(name, HERE, 'blur-estimate', spec=a.spec)
        T = probe.huawei(); Tm = model_release(probe, name, frames)
        x, y, x1, y1 = probe.rect; bh, bw = probe.bh, probe.bw
        def released(Tmap, p):
            m = np.zeros(s.roi.shape, bool)
            blk = np.where(probe.valid, p - Tmap >= .05, False)
            m[y:y + bh * B, x:x + bw * B] = np.repeat(np.repeat(blk, B, 0), B, 1)
            return (m | ~probe.comp.scene.card) & s.roi
        ro, mo = [], {sg: [] for sg in SIGMAS}
        for p in [.40, .48, .56, .64]:
            i, ref = c.ref.nearest(p); ref = np.asarray(ref); f = np.asarray(frames[round(p * 120)]).astype(np.float32)
            m = released(T, p) & released(Tm, p)
            if m.sum() < 5000: continue
            ro.append(hf(ref, m))
            for sg in SIGMAS: mo[sg].append(hf(cv2.GaussianBlur(f, (0, 0), sg) if sg else f, m))
        if not ro: continue
        target = float(np.mean(ro)); curve = {sg: float(np.mean(v)) for sg, v in mo.items()}
        # 高频能量随 σ 单调下降：在相邻两档之间线性插值求交点；原片比未模糊的模型还「锐」时记 0。
        sig = 0.
        for s0, s1 in zip(SIGMAS[:-1], SIGMAS[1:]):
            if curve[s0] >= target >= curve[s1]:
                sig = s0 + (curve[s0] - target) / max(curve[s0] - curve[s1], 1e-9) * (s1 - s0); break
        else:
            sig = 0. if target > curve[0.] else SIGMAS[-1]
        report[name] = dict(sigma=round(sig, 2), target=target, curve=curve)
        print(f'{name:22s} 估计 σ≈{sig:.2f}  原片高频 {target:.1f}  模型 σ=0 {curve[0.]:.1f}  σ=1.1 {curve[1.1]:.1f}', flush=True)
    Path(a.out).write_text(json.dumps(report, ensure_ascii=False, indent=1), 'utf-8')


if __name__ == '__main__':
    main()
