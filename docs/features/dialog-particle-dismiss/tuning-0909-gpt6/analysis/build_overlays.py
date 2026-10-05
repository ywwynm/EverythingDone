"""提取参考画面里盖在颗粒上面的叠加层（录屏的状态栏图标、灵动胶囊、视频后期字幕与水印）。

原片里这些元素在颗粒层之上：颗粒从字幕笔画后面穿过，笔画本身从头到尾不变，笔画周围却被颗粒扫过。
模型把整幅背景画在颗粒之下，颗粒就会盖住字幕，卡片上方因此多出一片颗粒。

判据（逐像素，场景分辨率），只在卡片上沿以上那一带找（状态栏、灵动胶囊、字幕、水印都在这里，
颗粒在风的作用下也主要往上飞；卡片以下的 Dock 图标与壁纸纹理不在颗粒的路径上，按全幅找会大量误判）：
  1. 整段动画里相对背景的最大偏离很小（始终不变）；
  2. 附近有颗粒经过（最大偏离大的像素在 40 像素以内）；
  3. 与壁纸不同：先用「与 21 像素中值滤波差得多」找出笔画与轮廓，把它们周围修补成壁纸，
     再取与修补结果差得多的像素——灵动胶囊这类大块纯色的内部只能这样找出来；
  4. 手持参考只取手机屏幕内。
产物 assets/<段>/overlay.png（RGBA，颜色取背景，alpha 为叠加层），原生渲染器在最后一步把它叠在最上面。
同时输出 analysis/match-cache/overlays/<段>.jpg 供目检。

用法：python analysis/build_overlays.py [段名 ...]
"""
from pathlib import Path
import json
import sys

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT), str(ROOT / 'analysis')]
from harmony_native import screen_mask

OUT = ROOT / 'analysis/match-cache/overlays'
REFS = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference'] + [f'reference-0926-{i}' for i in range(1, 8)]
STILL = 12          # 整段最大偏离低于此值视为不变
MOVING = 40         # 颗粒经过的像素
NEAR = 40           # 「附近有颗粒经过」的半径
DISTINCT = 30       # 与壁纸的差
SPREAD = 61         # 笔画周围按壁纸修补的范围（直径；要覆盖灵动胶囊约 50 像素高的内部）


FILL_TOL = 10       # 漫水：与种子颜色的差（各通道），固定参照种子而不是相邻像素，渐变壁纸接不过去
FILL_MAX = 30000    # 单次漫水面积上限（像素）；灵动胶囊约 1.8 万


def fill_uniform(m, bg, allowed, band):
    """从叠加层内部颜色均匀的像素出发做固定容差漫水，补上纯色的内部（灵动胶囊的黑底）。
    碰到画面边缘或搜索带的边界、或面积过大的，说明漫到了壁纸上，整块丢弃。
    胶囊里会变的像素（录屏计时数字）只挡住漫水，不算边界。"""
    h, w = m.shape
    var = cv2.blur((bg.astype(np.float32) ** 2).sum(2), (5, 5)) - (cv2.blur(bg.astype(np.float32), (5, 5)) ** 2).sum(2)
    seeds = np.argwhere(m & (var < 8.0) & (cv2.erode(m.astype(np.uint8), np.ones((5, 5), np.uint8)) > 0))
    out = m.copy()
    done = np.zeros_like(m)
    block = (~allowed).astype(np.uint8)
    edge = np.zeros_like(m)
    edge[0, :] = edge[-1, :] = edge[:, 0] = edge[:, -1] = True
    edge |= (cv2.dilate((~band).astype(np.uint8), np.ones((3, 3), np.uint8)) > 0) & band
    for y, x in seeds[::7]:
        if done[y, x]:
            continue
        mask = np.zeros((h + 2, w + 2), np.uint8)
        mask[1:-1, 1:-1] = block
        img = bg.copy()
        flags = 8 | cv2.FLOODFILL_FIXED_RANGE | cv2.FLOODFILL_MASK_ONLY | (255 << 8)
        cv2.floodFill(img, mask, (int(x), int(y)), 0, (FILL_TOL,) * 3, (FILL_TOL,) * 3, flags)
        region = (mask[1:-1, 1:-1] == 255)
        done |= region
        if region.sum() > FILL_MAX or (region & edge).any():
            continue
        out |= region
    return out


def build(name):
    d = ROOT / 'assets' / name
    meta = json.loads((d / 'scene.json').read_text('utf-8'))
    bgp = d / 'background-clean.png'
    bg = np.asarray(Image.open(bgp if bgp.exists() else d / 'background.png').convert('RGB'))
    c = ROOT / 'analysis/match-cache' / name
    fr = np.load(c / 'frames.npy', mmap_mode='r')
    pr = np.load(c / 'progress.npy')
    valid = meta['reference'].get('valid_progress', 1.0)
    dev = np.zeros(bg.shape[:2], np.int16)
    b16 = bg.astype(np.int16)
    for i in range(len(pr)):
        if 0.05 <= pr[i] <= min(0.95, valid):
            dev = np.maximum(dev, np.abs(np.asarray(fr[i]).astype(np.int16) - b16).max(2))
    still = dev < STILL
    moving = (dev > MOVING).astype(np.uint8)
    near = cv2.dilate(moving, cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (2 * NEAR + 1, 2 * NEAR + 1))) > 0
    x0, y0, x1, y1 = meta['rect']
    band = np.zeros(bg.shape[:2], bool)
    band[:max(0, y0 - 4), :] = True
    scr = screen_mask(name, tuple(meta['frame']))
    if scr is not None:
        band &= cv2.erode(scr, np.ones((7, 7), np.uint8)) > 0     # 屏幕边框本身不算
    med = cv2.medianBlur(bg, 21)
    cand = still & band & (np.abs(bg.astype(np.int16) - med.astype(np.int16)).max(2) > DISTINCT)
    hole = cv2.dilate(cand.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (SPREAD, SPREAD)))
    wall = cv2.inpaint(bg, hole, 9, cv2.INPAINT_TELEA)
    distinct = np.abs(bg.astype(np.int16) - wall.astype(np.int16)).max(2) > DISTINCT
    card = np.zeros(bg.shape[:2], bool)
    card[max(0, y0 - 4):y1 + 4, max(0, x0 - 4):x1 + 4] = True
    m = still & near & distinct & band & ~card
    # 去掉孤立噪点，补上笔画的抗锯齿边（只补始终不变的像素）
    n, lab, st, _ = cv2.connectedComponentsWithStats(m.astype(np.uint8), connectivity=8)
    keep = np.zeros(n, bool); keep[1:] = st[1:, cv2.CC_STAT_AREA] >= 6
    m = keep[lab]
    grow = cv2.dilate(m.astype(np.uint8), np.ones((3, 3), np.uint8)) > 0
    m = m | (grow & (dev < STILL * 2) & ~card)
    # 纯色的叠加层内部（灵动胶囊的黑底）按「与壁纸不同」找不出来，胶囊上下边又会被颗粒经过时的
    # 编码振铃扰动，轮廓不闭合。改用固定容差漫水（见 fill_uniform）。
    m = fill_uniform(m, bg, still & band & ~card, band & ~card)
    alpha = cv2.GaussianBlur(m.astype(np.float32), (0, 0), 0.6)
    alpha = np.where(m, 1.0, np.clip(alpha * 1.5, 0, 1))
    rgba = np.dstack([bg, (alpha * 255 + 0.5).astype(np.uint8)])
    Image.fromarray(rgba, 'RGBA').save(d / 'overlay.png')
    OUT.mkdir(parents=True, exist_ok=True)
    vis = bg.copy()
    vis[m] = (255, 0, 255)
    dv = np.repeat(np.clip(dev * 3, 0, 255).astype(np.uint8)[:, :, None], 3, 2)
    Image.fromarray(np.concatenate([vis, dv], 1)).save(OUT / f'{name}.jpg', quality=88)
    return int(m.sum())


def main():
    names = sys.argv[1:] or REFS
    for name in names:
        print(name, '叠加层像素', build(name), flush=True)


if __name__ == '__main__':
    main()
