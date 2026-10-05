"""把屏幕上的前置摄像头开孔并入叠加层（开孔在屏幕之前，颗粒不能画在它上面；只用于参考复刻）。
从原片里开孔附近没有颗粒的那一帧取像素，alpha 为开孔的暗色圆斑（亮度 < 阈值的连通块按外轮廓填实，外扩 2 像素、羽化 1 像素）。
原叠加层先备份为 overlay-before-hole.png。
用法：python analysis/add_hole_overlay.py <段名> <中心x,中心y> <取像素的进度> [半径上限 24] [亮度阈值 45]
"""
from pathlib import Path
import shutil
import sys

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'analysis')]
from match_compare import Comparison


def main():
    name = sys.argv[1]
    cx, cy = (float(v) for v in sys.argv[2].split(','))
    p = float(sys.argv[3])
    rmax = int(sys.argv[4]) if len(sys.argv) > 4 else 24
    thr = float(sys.argv[5]) if len(sys.argv) > 5 else 45.0
    c = Comparison(name)
    i = int(np.argmin(np.abs(c.ref.progress - p)))
    a = np.asarray(c.ref.frames[i])
    y = a.astype(np.float32) @ np.float32([.299, .587, .114])
    x0, y0, x1, y1 = int(cx - rmax), int(cy - rmax), int(cx + rmax + 1), int(cy + rmax + 1)
    dark = (y[y0:y1, x0:x1] < thr).astype(np.uint8)
    n, lab, st, cen = cv2.connectedComponentsWithStats(dark, 8)
    k = 1 + int(np.argmin([np.hypot(cen[j][0] + x0 - cx, cen[j][1] + y0 - cy) for j in range(1, n)]))
    m = (lab == k).astype(np.uint8)
    # 开孔中间可能比外圈亮（镜头的反光），只取到外圈：按外轮廓填实
    cnts, _ = cv2.findContours(m, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)
    cv2.drawContours(m, cnts, -1, 1, -1)
    m = cv2.dilate(m, np.ones((5, 5), np.uint8))
    alpha = np.clip(cv2.GaussianBlur(m.astype(np.float32), (0, 0), 1.0) * 1.3, 0, 1)
    d = ROOT / 'assets' / name
    ovp = d / 'overlay.png'
    H, W = a.shape[:2]
    if ovp.exists():
        bk = d / 'overlay-before-hole.png'
        if not bk.exists():
            shutil.copy(ovp, bk)
        ov = np.asarray(Image.open(bk).convert('RGBA')).copy()
    else:
        ov = np.zeros((H, W, 4), np.uint8)
    reg = ov[y0:y1, x0:x1]
    al = alpha * 255
    take = al > reg[..., 3]
    reg[..., :3] = np.where(take[..., None], a[y0:y1, x0:x1], reg[..., :3])
    reg[..., 3] = np.maximum(reg[..., 3], al.astype(np.uint8))
    Image.fromarray(ov, 'RGBA').save(ovp)
    print(name, '开孔', int(st[k][2]), 'x', int(st[k][3]), '中心', round(cen[k][0] + x0, 1), round(cen[k][1] + y0, 1), '取自进度', round(float(c.ref.progress[i]), 3))


if __name__ == '__main__':
    main()
