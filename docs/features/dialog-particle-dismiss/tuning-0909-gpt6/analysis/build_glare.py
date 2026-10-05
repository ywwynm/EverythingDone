"""手持屏摄里随手机晃动的屏幕反光：逐帧在「安静」像素上量原片与不画颗粒的模型画面之差，分块中位数后平滑插值成低频场，
渲染器按参考进度插值、加在屏幕内卡片与叠加层以外（只用于参考复刻，见 replica 文档第 42 节）。

安静像素：手机屏幕内、模型里卡片与叠加层都不在（覆盖率 < 0.02）、模型颗粒层 15 像素以内没有颗粒、不在当时显示的
按进度叠加层与逐帧遮挡框内。块（默认 24 像素）里安静像素 ≥ 60%，且残差的高频部分超过 8 的像素不足 20%（原片颗粒）才取中位数。
块值按权重做归一化高斯平滑（空间 σ 默认 2 块、时间 σ 1 帧），数据不足处逐级换到更粗的尺度外推（颗粒云内部也有值）。
另在模型里卡片完整、附近无颗粒的像素上量同样的残差，得到卡片场（原片里卡片的曝光、压暗层、整体透明度随时间的变化），空间平滑更宽。
产物：assets/<段>/glare.npz（progress：原片帧的参考进度；field / card：(n, H/块, W/块, 3) float16，编码值的加性偏移）。
用法：python analysis/build_glare.py <段名> [块=24] [空间σ(块)=2] [时间σ(帧)=1] [fill|decay，缺省 fill]
录屏各段与参考 1、2 用 decay（外推让粗尺度变差），其余手持段用 fill。
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


def main():
    name = sys.argv[1]
    B = int(sys.argv[2]) if len(sys.argv) > 2 else 24
    ss = float(sys.argv[3]) if len(sys.argv) > 3 else 2.0
    ts = float(sys.argv[4]) if len(sys.argv) > 4 else 1.0
    mode = sys.argv[5] if len(sys.argv) > 5 else 'fill'
    table = json.loads((ROOT / 'native_reference_inputs.json').read_text('utf-8'))
    c = Comparison(name)
    W, H = c.scene.W, c.scene.H
    r = NativeRenderer(name, inputs_from_entry(table[name]), raster=LIVE_RASTER, ss=1)
    r.glare = None
    screen = r.glare_screen
    if screen is None:
        from harmony_native import screen_mask
        sm = screen_mask(name, r.frame)
        screen = (sm.astype(np.float32) / 255.0) if sm is not None else np.ones((H, W), np.float32)
    prog = c.ref.progress
    ny, nx = H // B, W // B
    vals = np.zeros((len(prog), ny, nx, 3), np.float32)
    wts = np.zeros((len(prog), ny, nx), np.float32)
    cvals = np.zeros((len(prog), ny, nx, 3), np.float32)
    cwts = np.zeros((len(prog), ny, nx), np.float32)
    lum = np.float32([.299, .587, .114])
    for i in range(len(prog)):
        q = float(prog[i])
        T = r.device_time(q)
        full = r.render_time(T, q=q).astype(np.float32)
        bare = r.render_time(T, particles=False, q=q).astype(np.float32)
        cov = r.card_alpha()
        ref = np.asarray(c.ref.frames[i]).astype(np.float32)
        R = ref - bare
        part = (np.abs((full - bare) @ lum) >= 2).astype(np.uint8)
        part = cv2.dilate(part, np.ones((31, 31), np.uint8)).astype(bool)
        quiet = (cov < 0.02) & (screen > 0.5) & ~part
        for lo, hi, box, _, _ in r.timed:
            if lo <= q < hi:
                quiet[box] = False
        if r.occluders:
            k = min(range(len(r.occluders)), key=lambda j: abs(r.occluders[j][0] - q))
            p_, box, _, _ = r.occluders[k]
            if abs(p_ - q) <= r.occluder_tol:
                quiet[box] = False
        y = R @ lum
        hf = np.abs(y - cv2.GaussianBlur(y, (0, 0), 2.0)) > 8
        # 卡片本身：模型里卡片完整、附近无颗粒的像素（原片里卡片的曝光、压暗层、整体透明度随时间的变化）
        cq = cv2.erode(((cov > 0.98) & (screen > 0.5) & ~part).astype(np.uint8), np.ones((9, 9), np.uint8)).astype(bool)
        for lo, hi, box, _, _ in r.timed:
            if lo <= q < hi:
                cq[box] = False
        if r.occluders:
            k = min(range(len(r.occluders)), key=lambda j: abs(r.occluders[j][0] - q))
            p_, box, _, _ = r.occluders[k]
            if abs(p_ - q) <= r.occluder_tol:
                cq[box] = False
        for by in range(ny):
            for bx in range(nx):
                sl = (slice(by * B, (by + 1) * B), slice(bx * B, (bx + 1) * B))
                qm = quiet[sl]
                if qm.mean() >= 0.6 and hf[sl][qm].mean() <= 0.2:
                    vals[i, by, bx] = np.median(R[sl][qm], axis=0)
                    wts[i, by, bx] = 1.0
                cm = cq[sl]
                if cm.mean() >= 0.6:
                    cvals[i, by, bx] = np.median(R[sl][cm], axis=0)
                    cwts[i, by, bx] = 1.0
    r.close()
    # 空间与时间的归一化高斯平滑，多尺度外推：细尺度（σ ss 块）数据不足处逐级换到更粗的尺度（3ss、8ss），
    # 最后取这一帧有效块的均值——颗粒云内部没有安静像素，相机自动曝光、整片反光这类整体变化要能延伸进去，不能在颗粒附近归零
    from scipy.ndimage import gaussian_filter

    def fill(v, w, sig):
        if mode == 'decay':
            # 只在有效块附近有值，离开后衰减到 0（录屏段：没有相机自动曝光，局部的界面过渡不该外推到颗粒区）
            num = gaussian_filter(v * w[..., None], sigma=(ts, sig, sig, 0))
            den = gaussian_filter(w, sigma=(ts, sig, sig))
            est = np.where(den[..., None] > 0.05, num / np.maximum(den[..., None], 1e-6), 0.0)
            return est * np.clip(den / 0.15, 0, 1)[..., None]
        out = None
        for k, sc in enumerate((sig, 3 * sig, 8 * sig)):
            num = gaussian_filter(v * w[..., None], sigma=(ts, sc, sc, 0))
            den = gaussian_filter(w, sigma=(ts, sc, sc))
            est = num / np.maximum(den[..., None], 1e-6)
            conf = np.clip(den / 0.3, 0, 1)[..., None]
            if out is None:
                out, have = est * conf, conf
            else:
                out = out + (1 - have) * est * conf
                have = have + (1 - have) * conf
        # 仍不足处：本帧有效块均值（整帧没有有效块时为 0）
        tot = w.sum(axis=(1, 2))
        mean = (v * w[..., None]).sum(axis=(1, 2)) / np.maximum(tot, 1e-6)[:, None]
        mean = np.where(tot[:, None] > 0, mean, 0.0)
        return out + (1 - have) * mean[:, None, None, :]

    field = fill(vals, wts, ss)
    cfield = fill(cvals, cwts, 1.5 * ss)
    out = ROOT / 'assets' / name / 'glare.npz'
    np.savez_compressed(out, progress=np.asarray(prog, np.float64), field=field.astype(np.float16), card=cfield.astype(np.float16), block=B)
    lumc = cfield @ lum
    print(name, '卡片场 有效块占比', round(float(cwts.mean()), 3), '亮度 分位 1/50/99', np.round(np.percentile(lumc, [1, 50, 99]), 1))
    lumf = field @ lum
    print(name, '帧', len(prog), '块', f'{ny}x{nx}', '有效块占比', round(float(wts.mean()), 3),
          '场亮度 分位 1/50/99', np.round(np.percentile(lumf, [1, 50, 99]), 1))


if __name__ == '__main__':
    main()
