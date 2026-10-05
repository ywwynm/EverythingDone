"""提取参考画面顶部随时间变化的界面元素（录屏计时、状态栏与水印的切换、视频平台水印），做成按进度显示的叠加层。

背景图取自动画结束后的画面，顶部的界面元素也定格在结束时的样子；而原片里它们会变：录屏计时每秒跳一次，
参考 5 的状态栏在约 0.29 换成「酱紫Oo」水印，钢铁侠画面上方的「小红书」水印在约 0.33 才出现。
这些元素都在颗粒层之上（颗粒从它们后面经过）。

做法（只看卡片上沿以上那一带）：
  1. 逐帧求与背景差得多、且在前后各 3 帧内保持不变的像素（颗粒在动，不会连续几帧不变）；
  2. 按这些像素的取值把时间轴切成段：相邻帧在两者并集上的取值一致就属于同一段；
  3. 每一段取这些像素在段内的中位数，做成一张 RGBA 叠加层，显示区间为该段起止进度。
产物：assets/<段>/timed/<k>.png 与 assets/<段>/timed-overlays.json（[{file, from, to}]，进度为参考进度）。

用法：python analysis/build_timed_overlays.py [段名 ...]
"""
from pathlib import Path
import json
import shutil
import sys

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path[:0] = [str(ROOT)]
from harmony_native import screen_mask
REFS = ['ironman', 'thanos', 'kobe', 'spiderman-original', 'ironman-up-reference'] + [f'reference-0926-{i}' for i in range(1, 8)]
DIFF = 30          # 与背景的差
STILL = 14         # 前后几帧内的变化上限
WIN = 3            # 前后各几帧
GLYPH = 25         # 「字形」判据：与 21 像素中值滤波的差
STEP_WIN = 4       # 切点前后各几帧保持不变
STEP_PIXELS = 25   # 切点处跳变的像素数下限（计时只换一位数字时只有几十个像素，例如 5 → 6）
CAMERA = {'ironman-up-reference', 'reference-0926-1', 'reference-0926-2', 'reference-0926-3', 'reference-0926-4',
          'reference-0926-7'}   # 相机拍摄的参考
MIN_PIXELS = 40    # 一段的界面差异像素少于此数就不做叠加层


def band_segments(fr, pr, idx, bg, rows):
    """一条横带上按界面变化切段；返回 [((起, 止), 遮罩, 段内中位数)]，只含「字形」类像素。
    起止为参考进度，边界放在切点那一帧之前一点：切点那一帧属于新的一段（取整不能把它挤到旧的一段）。"""
    F = np.stack([np.asarray(fr[i])[rows] for i in idx]).astype(np.int16)        # (n, h, W, 3)
    n = len(idx)
    ui = np.zeros(F.shape[:3], bool)
    for t in range(n):
        lo, hi = max(0, t - WIN), min(n, t + WIN + 1)
        still = np.abs(F[lo:hi] - F[t][None]).max(axis=(0, 3)) < STILL
        ui[t] = still & (np.abs(F[t] - bg).max(2) > DIFF)
    # 切段：某一帧有足够多的像素在前后各 STEP_WIN 帧内都不变、而这一帧前后跳变——界面换了（计时跳秒、水印切换）。
    # 颗粒在动，不会前后都静止；相邻的切点合并。窗口中位数抗编码噪声（字形边缘的抗锯齿像素逐帧抖动较大）。
    cuts = []
    for t in range(STEP_WIN, n - STEP_WIN + 1):
        before, after = F[t - STEP_WIN:t], F[t:t + STEP_WIN]
        mb, ma = np.median(before, axis=0), np.median(after, axis=0)
        sb = np.abs(before - mb[None]).max(axis=(0, 3)) < STILL * 1.8
        sa = np.abs(after - ma[None]).max(axis=(0, 3)) < STILL * 1.8
        jump = np.abs(ma - mb).max(2) > DIFF
        if int((sb & sa & jump).sum()) >= STEP_PIXELS and (not cuts or t - cuts[-1] > STEP_WIN):
            cuts.append(t)
    bounds = [0] + cuts + [n]
    segs = [list(range(bounds[k], bounds[k + 1])) for k in range(len(bounds) - 1)]
    bg_u8 = bg.clip(0, 255).astype(np.uint8)
    bg_glyph = np.abs(bg.astype(np.int16) - cv2.medianBlur(bg_u8, 21).astype(np.int16)).max(2) > GLYPH
    out = []
    for k, seg in enumerate(segs):
        votes = (ui[seg].mean(0) >= 0.6)
        # 只要「字形」类的像素（文字、图标、计时数字）：在这一段的画面或背景里与周围明显不同。
        # 壁纸本身（云、渐变）的差异多半来自背景重建，那里颗粒会经过，不能盖住。
        med = np.median(F[seg], axis=0).clip(0, 255).astype(np.uint8)
        seg_glyph = np.abs(med.astype(np.int16) - cv2.medianBlur(med, 21).astype(np.int16)).max(2) > GLYPH
        votes = (votes & cv2.dilate((seg_glyph | bg_glyph).astype(np.uint8), np.ones((5, 5), np.uint8)).astype(bool)).astype(np.uint8)
        # 去掉零星噪点（文字笔画只有 1～2 像素宽，不能用开运算）
        cnt, lab, st, _ = cv2.connectedComponentsWithStats(votes, connectivity=8)
        keep = np.zeros(cnt, bool); keep[1:] = st[1:, cv2.CC_STAT_AREA] >= 6
        votes = keep[lab].astype(np.uint8)
        if votes.sum() < MIN_PIXELS:
            continue
        mask = cv2.dilate(votes, np.ones((3, 3), np.uint8)) > 0
        start = float(pr[idx[seg[0]]]) - 5e-4 if k > 0 else -9.0
        end = float(pr[idx[segs[k + 1][0]]]) - 5e-4 if k + 1 < len(segs) else 9.0
        out.append(((start, end), mask, med))
    return out


def build(name):
    d = ROOT / 'assets' / name
    if name in CAMERA:
        # 相机拍摄的参考：顶部界面元素又小又糊，与背景的差异主要来自曝光漂移与配准，不做时间叠加层
        entries = []
        late = d / 'overlay-late.json'
        if late.exists():
            entries.append(dict(file='overlay-late.png', **{'from': json.loads(late.read_text('utf-8'))['progress'], 'to': 9.0},
                                pixels=-1))
        (d / 'timed-overlays.json').write_text(json.dumps(entries, ensure_ascii=False, indent=1), 'utf-8')
        if (d / 'timed').exists():
            shutil.rmtree(d / 'timed')
        return entries
    meta = json.loads((d / 'scene.json').read_text('utf-8'))
    x0, y0, x1, y1 = meta['rect']
    W, H = meta['frame']
    bgp = d / 'background-clean.png'
    bg = np.asarray(Image.open(bgp if bgp.exists() else d / 'background.png').convert('RGB')).astype(np.int16)
    c = ROOT / 'analysis/match-cache' / name
    fr = np.load(c / 'frames.npy', mmap_mode='r')
    pr = np.load(c / 'progress.npy')
    valid = meta['reference'].get('valid_progress', 1.0)
    idx = [i for i in range(len(pr)) if pr[i] <= valid]
    out_dir = d / 'timed'
    if out_dir.exists():
        shutil.rmtree(out_dir)
    entries = []
    # 卡片上沿以上一带（状态栏、计时、字幕、水印），卡片下沿以下一带（视频后期放在底部的水印）
    for band, rows in (('top', slice(0, max(0, y0 - 4))), ('bottom', slice(min(H, y1 + 4), H))):
        if rows.stop - rows.start < 8:
            continue
        for k, (seg, mask, med) in enumerate(band_segments(fr, pr, idx, bg[rows], rows)):
            rgba = np.zeros((H, W, 4), np.uint8)
            rgba[rows, :, :3] = med
            rgba[rows, :, 3] = mask * 255
            out_dir.mkdir(exist_ok=True)
            fn = f'timed/{band}-{k:02d}.png'
            Image.fromarray(rgba, 'RGBA').save(d / fn)
            entries.append(dict(file=fn, **{'from': round(seg[0], 4), 'to': round(seg[1], 4)}, pixels=int(mask.sum())))
    # 视频后期途中加上的水印（build_post_overlay.py）排在最前：之后各段的界面元素取自已经带着这层后期的画面，要盖在它上面；
    # 卡片移除后桌面重排出现的元素（rebuild_late_background.py）排在最后
    if (d / 'post-overlay.json').exists():
        entries.insert(0, dict(file='post-overlay.png', **{'from': json.loads((d / 'post-overlay.json').read_text('utf-8'))['progress'],
                                                         'to': 9.0}, pixels=-1))
    if (d / 'overlay-late.json').exists():
        entries.append(dict(file='overlay-late.png', **{'from': json.loads((d / 'overlay-late.json').read_text('utf-8'))['progress'],
                                                       'to': 9.0}, pixels=-1))
    (d / 'timed-overlays.json').write_text(json.dumps(entries, ensure_ascii=False, indent=1), 'utf-8')
    return entries


def main():
    for name in (sys.argv[1:] or REFS):
        e = build(name)
        print(name, len(e), '段', [(q['from'], q['to'], q['pixels']) for q in e], flush=True)


if __name__ == '__main__':
    main()
