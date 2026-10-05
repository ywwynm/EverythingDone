"""从背景图提取手机屏幕区域（颗粒只能画在屏幕里；屏幕外是手机外框、手、桌面或宣传画面的边框）。

屏幕录像（全画面即屏幕）不需要；带外框的原片与手持拍屏按以下步骤：
Canny 边缘（σ 模糊后）→ 膨胀 → 从卡片中心泛洪填充非边缘像素 → 凸包 → 向内收 inset 像素。
结果存为 assets/<场景>/screen-mask.png（255 为屏幕内）。这是设备几何输入（与 rect 同类），不含任何颗粒观测。
用法：screen_masks.py 场景... [--check 输出图]
"""
from pathlib import Path
import argparse, json
import numpy as np, cv2
from PIL import Image

HERE = Path(__file__).resolve().parents[1]


def extract(name, low=30, high=90, dilate=2, inset=2):
    bg = np.array(Image.open(HERE / 'assets' / name / 'background.png').convert('RGB'))
    m = json.loads((HERE / 'assets' / name / 'scene.json').read_text('utf-8'))
    g = cv2.GaussianBlur(cv2.cvtColor(bg, cv2.COLOR_RGB2GRAY), (0, 0), 1.2)
    e = cv2.Canny(g, low, high)
    e = cv2.dilate(e, np.ones((2 * dilate + 1, 2 * dilate + 1), np.uint8))
    x, y, x1, y1 = m['rect']; seed = ((x + x1) // 2, (y + y1) // 2)
    fill = (e == 0).astype(np.uint8)
    h, w = fill.shape
    ff = np.zeros((h + 2, w + 2), np.uint8)
    region = fill.copy()
    cv2.floodFill(region, ff, seed, 2)
    inside = (region == 2).astype(np.uint8)
    pts = cv2.findNonZero(inside)
    hull = cv2.convexHull(pts)
    mask = np.zeros_like(inside); cv2.fillPoly(mask, [hull], 1)
    if inset > 0: mask = cv2.erode(mask, np.ones((2 * inset + 1, 2 * inset + 1), np.uint8))
    return mask.astype(bool), bg


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('scenes', nargs='+')
    ap.add_argument('--check')
    ap.add_argument('--save', action='store_true')
    a = ap.parse_args()
    tiles = []
    for name in a.scenes:
        mask, bg = extract(name)
        ys, xs = np.nonzero(mask)
        print(f'{name:22s} 屏幕占比 {mask.mean():.3f}  外接框 x {xs.min()}～{xs.max()}  y {ys.min()}～{ys.max()}', flush=True)
        if a.save: Image.fromarray((mask * 255).astype(np.uint8)).save(HERE / 'assets' / name / 'screen-mask.png')
        o = bg.astype(np.float32).copy(); o[~mask] = o[~mask] * .3 + np.array([200, 0, 0]) * .7
        edge = mask ^ cv2.erode(mask.astype(np.uint8), np.ones((3, 3), np.uint8)).astype(bool); o[edge] = (255, 255, 0)
        im = Image.fromarray(o.astype(np.uint8)); im.thumbnail((300, 520)); tiles.append(im)
    if a.check:
        W = sum(t.width + 6 for t in tiles); H = max(t.height for t in tiles)
        cv = Image.new('RGB', (W, H), (14, 20, 30)); x = 0
        for t in tiles: cv.paste(t, (x, 0)); x += t.width + 6
        cv.save(a.check, quality=90)


if __name__ == '__main__':
    main()
