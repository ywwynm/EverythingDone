"""重建「卡片移除后桌面重排」遮住的那块壁纸，并把重排出现的元素做成按进度显示的叠加层。

参考 1（白衣人物）的原片在进度约 0.859 时桌面重新排布，卡片左上冒出两个应用图标；`background.png` 取自结束帧，
这块只能平涂补上，`background-valid.png` 因此把它整块排除，解除时刻探针与画面判据都量不到卡片左上角。
动画期间这里只有壁纸：取「卡片已释放完、图标还没出现」的那几帧逐像素中值即为真实壁纸。

产物（assets/<段>/）：
  background-clean.png  重建过的背景（其余像素与 background.png 相同，交界处羽化）
  overlay-late.png      重排后出现的元素（RGBA）
  overlay-late.json     {"progress": 出现时刻（参考进度）}
  background-valid.png  把这块改为有效（原文件备份为 background-valid-orig.png）

用法：python analysis/rebuild_late_background.py <段名> <中值帧进度下限> <上限> <叠加层出现进度>
例：python analysis/rebuild_late_background.py reference-0926-1 0.75 0.845 0.85
"""
from pathlib import Path
import json
import shutil
import sys

import cv2
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def main():
    name, lo, hi, appear = sys.argv[1], float(sys.argv[2]), float(sys.argv[3]), float(sys.argv[4])
    d = ROOT / 'assets' / name
    c = ROOT / 'analysis/match-cache' / name
    fr = np.load(c / 'frames.npy', mmap_mode='r')
    pr = np.load(c / 'progress.npy')
    bg = np.asarray(Image.open(d / 'background.png').convert('RGB'))
    valid_path = d / 'background-valid.png'
    orig_valid = d / 'background-valid-orig.png'
    if not orig_valid.exists():
        shutil.copy(valid_path, orig_valid)
    valid = np.asarray(Image.open(orig_valid).convert('L')) > 250
    region = ~valid
    # 只重建无效区里屏幕内的部分（屏幕外本来就不参与）
    scr_path = d / 'screen-mask.png'
    if scr_path.exists():
        region &= np.asarray(Image.open(scr_path).convert('L')) > 127
    take = [i for i in range(len(pr)) if lo <= pr[i] <= hi]
    stack = np.stack([np.asarray(fr[i]) for i in take]).astype(np.float32)
    med = np.median(stack, 0)
    # 交界羽化：区域内用中值，向外 6 像素过渡到原背景
    w = cv2.GaussianBlur(cv2.dilate(region.astype(np.uint8), np.ones((7, 7), np.uint8)).astype(np.float32), (0, 0), 2.0)
    w = np.clip(w, 0, 1)[:, :, None]
    clean = (bg * (1 - w) + med * w + 0.5).clip(0, 255).astype(np.uint8)
    Image.fromarray(clean).save(d / 'background-clean.png')
    # 重排后出现的元素：出现之后各帧的中值与重建背景差得多的像素
    late = [i for i in range(len(pr)) if pr[i] >= appear + 0.02]
    lm = np.median(np.stack([np.asarray(fr[i]) for i in late]).astype(np.float32), 0)
    diff = np.abs(lm - clean.astype(np.float32)).max(2)
    m = (diff > 25) & cv2.dilate(region.astype(np.uint8), np.ones((9, 9), np.uint8)).astype(bool)
    n, lab, st, _ = cv2.connectedComponentsWithStats(m.astype(np.uint8), connectivity=8)
    keep = np.zeros(n, bool); keep[1:] = st[1:, cv2.CC_STAT_AREA] >= 30
    m = keep[lab]
    # 图标下方的浅色文字与壁纸差得不多，阈值抠不全：连通块外扩后整块取晚段中值（壁纸部分本来就与背景一致）
    m = cv2.dilate(m.astype(np.uint8), cv2.getStructuringElement(cv2.MORPH_ELLIPSE, (31, 31))).astype(bool)
    alpha = np.clip(cv2.GaussianBlur(m.astype(np.float32), (0, 0), 2.0) * 1.6, 0, 1)
    rgba = np.dstack([lm.clip(0, 255).astype(np.uint8), (alpha * 255 + 0.5).astype(np.uint8)])
    Image.fromarray(rgba, 'RGBA').save(d / 'overlay-late.png')
    (d / 'overlay-late.json').write_text(json.dumps(dict(progress=appear, frames_for_background=[float(pr[i]) for i in take],
                                                          note='卡片移除后桌面重排出现的元素；参考进度到达 progress 后显示'),
                                                     ensure_ascii=False, indent=1), 'utf-8')
    # 这块已重建，改为有效
    new_valid = valid | region
    Image.fromarray((new_valid * 255).astype(np.uint8)).save(valid_path)
    print(name, '重建像素', int(region.sum()), '中值帧', len(take), '叠加层像素', int(m.sum()), '出现于', appear)


if __name__ == '__main__':
    main()
