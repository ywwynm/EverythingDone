"""开场淡出的确认框（及按「移除」的手指）：逐帧从原片抠出，并入逐帧遮挡层（只用于参考复刻）。

原片动画开始时，「是否移除卡片」确认框还在卡片下部淡出（约 0.03～0.05 进度），参考 3、4 还有按按钮的手指；
它们与粒子效果无关，模型里没有。判据：与同一时刻不画颗粒的模型画面差别明显（> 阈值），形态学开运算去掉颗粒样的小点，
只留面积足够大的连通块，限在搜索框内（卡片下部外扩，手指可以伸出卡片下沿）；进度上限之后不再抠。
已有的逐帧遮挡（参考 4 的手指，analysis/build_occluder_frames.py）按帧合并：同一帧的两块补丁取外接框、alpha 取大。

产物：assets/<段>/occluder-frames.json 与 assets/<段>/occluder/<序号>.png（RGBA）。旧文件先备份到 occluder-before-opening/。
用法：python analysis/build_opening_frames.py <段名> <进度上限> [阈值 28] [最小面积 2500]
"""
from pathlib import Path
import json
import shutil
import sys

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'analysis')]
from harmony_native import NativeRenderer, LIVE_RASTER, inputs_from_entry
from match_compare import Comparison


def main():
    name, p_hi = sys.argv[1], float(sys.argv[2])
    thr = float(sys.argv[3]) if len(sys.argv) > 3 else 28.0
    min_area = int(sys.argv[4]) if len(sys.argv) > 4 else 2500
    table = json.loads((ROOT / 'native_reference_inputs.json').read_text('utf-8'))
    c = Comparison(name)
    W, H = c.scene.W, c.scene.H
    x0, y0, x1, y1 = c.scene.rect
    h = y1 - y0
    bx0, by0, bx1, by1 = max(0, x0 - 40), int(y0 + 0.3 * h), min(W, x1 + 40), min(H, y1 + 260)
    r = NativeRenderer(name, inputs_from_entry(table[name]), raster=LIVE_RASTER, ss=1)
    r.occluders = []
    d = ROOT / 'assets' / name
    od = d / 'occluder'
    old = []
    meta = d / 'occluder-frames.json'
    if meta.exists():
        bk = d / 'occluder-before-opening'
        if not bk.exists():
            bk.mkdir()
            shutil.copy(meta, bk / 'occluder-frames.json')
            if od.exists():
                shutil.copytree(od, bk / 'occluder')
        src = json.loads((bk / 'occluder-frames.json').read_text('utf-8'))
        for e in src['frames']:
            a = np.asarray(Image.open(bk / e['file']).convert('RGBA'))
            old.append((float(e['progress']), e['box'], a))
    od.mkdir(exist_ok=True)
    for f in od.glob('*.png'):
        f.unlink()
    prog = c.ref.progress
    entries = []
    for i in range(len(prog)):
        p = float(prog[i])
        a = np.asarray(c.ref.frames[i])
        full = np.zeros((H, W), np.float32)
        rgb = np.zeros((H, W, 3), np.uint8)
        if p <= p_hi:
            bare = r.render_time(r.device_time(p), particles=False, q=p)
            diff = np.abs(a.astype(np.int16) - bare.astype(np.int16)).max(2) > thr
            m = np.zeros((H, W), np.uint8)
            m[by0:by1, bx0:bx1] = diff[by0:by1, bx0:bx1]
            m = cv2.morphologyEx(m, cv2.MORPH_OPEN, np.ones((9, 9), np.uint8))
            m = cv2.morphologyEx(m, cv2.MORPH_CLOSE, np.ones((21, 21), np.uint8))
            n, lab, st, _ = cv2.connectedComponentsWithStats(m, connectivity=8)
            keep = np.zeros((H, W), bool)
            for k in range(1, n):
                if st[k][4] >= min_area:
                    keep |= lab == k
            if keep.sum() >= min_area:
                alpha = np.clip(cv2.GaussianBlur(keep.astype(np.float32), (0, 0), 1.5) * 1.6, 0, 1)
                full = np.maximum(full, alpha)
                rgb = np.where(alpha[..., None] > 0, a, rgb)
        # 并入同一帧（容差内）的旧遮挡
        for p_, box, patch in old:
            if abs(p_ - p) <= 1e-6:
                ox0, oy0, ox1, oy1 = box
                al = patch[..., 3].astype(np.float32) / 255.0
                region = (slice(oy0, oy1), slice(ox0, ox1))
                take = al > full[region]
                rgb[region] = np.where(take[..., None], patch[..., :3], rgb[region])
                full[region] = np.maximum(full[region], al)
        if full.max() <= 0.02:
            continue
        ys, xs = np.nonzero(full > 0.02)
        yy0, yy1, xx0, xx1 = ys.min(), ys.max() + 1, xs.min(), xs.max() + 1
        patch = np.dstack([rgb[yy0:yy1, xx0:xx1], (full[yy0:yy1, xx0:xx1] * 255).astype(np.uint8)])
        fn = f'occluder/{i:04d}.png'
        Image.fromarray(patch, 'RGBA').save(d / fn)
        entries.append(dict(progress=p, file=fn, box=[int(xx0), int(yy0), int(xx1), int(yy1)]))
    r.close()
    steps = np.diff(np.sort(prog))
    tol = float(np.median(steps[steps > 0]) * 0.5) if len(steps) else 0.005
    meta.write_text(json.dumps(dict(tolerance=tol, frames=entries), ensure_ascii=False, indent=1), 'utf-8')
    print(name, '遮挡帧', len(entries), '其中开场', sum(1 for e in entries if e['progress'] <= p_hi), '容差', round(tol, 4))


if __name__ == '__main__':
    main()
