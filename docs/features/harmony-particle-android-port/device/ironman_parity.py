"""旧钢铁侠：Android 与参考原片并排。

在 R5CW20BLNKL 上起 `DriftParityProbeActivity`（原片的钢铁侠卡片 + 太空背景，钉住 ironman、135°），录屏取帧；
原片帧取自 `tuning-0909-gpt6/assets/ironman/reference.npy`。两边都从「卡片开始变化」的那一帧起算，
按各自整段的同一比例取帧（Android 消散压到 1 秒，原片放慢约 3.42 倍、按录像里的 3.25～8.45 秒算），
再把原片按卡片大小放大到与 Android 同一比例裁剪，卡片在两边的位置与大小一致。

用法：python ironman_parity.py --run p1 [--style ironman]
"""
import argparse
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(Path(__file__).resolve().parent))
import drift_driver as d  # noqa: E402
import drift_record as r  # noqa: E402
import stage_b_probes as b  # noqa: E402

REFERENCE = Path(__file__).resolve().parents[4] / 'docs/features/dialog-particle-dismiss/tuning-0909-gpt6/assets/ironman'
REF_CARD = (126, 261, 594, 735)        # 原片 720 × 1280 画面里的卡片
REF_END = 8.45
FRACTIONS = (0.0, 0.08, 0.16, 0.25, 0.34, 0.44, 0.56, 0.7)


def font(size):
    for name in ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/simhei.ttf'):
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            pass
    return ImageFont.load_default()


def first_change(frames, box, threshold, after=0.0):
    """[after] 秒之后的第一帧当基准（Android 要跳过应用启动的过渡画面），找卡片区域第一次明显变化的时刻。"""
    x1, y1, x2, y2 = box
    frames = [(t, a) for t, a in frames if t >= after]
    base = frames[0][1][y1:y2, x1:x2].astype(np.float32)
    for t, a in frames:
        if np.abs(a[y1:y2, x1:x2].astype(np.float32) - base).mean() > threshold:
            return t
    return None


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--run', required=True)
    p.add_argument('--style', default='ironman')
    p.add_argument('--reuse', action='store_true', help='用已有的录像重新出图，不重新录')
    a = p.parse_args()
    folder = d.OUT / a.run
    folder.mkdir(parents=True, exist_ok=True)

    video = folder / 'android.mp4'
    if not a.reuse or not video.is_file():
        rec = r.Recording(7)
        b.start('DriftParityProbeActivity', style=a.style, delay_ms=2000)
        video = rec.finish(video)
    d.soft('shell', 'am', 'force-stop', d.PACKAGE)
    items = r.frames(video, folder / 'android-frames', scale_width=720)
    android = [(t, np.asarray(Image.open(f).convert('RGB'))) for t, f in items]
    h = android[0][1].shape[0]
    # Android 卡片：350 dp 宽、水平居中，中心在画面高度的 498 / 1280
    card_w = round(720 * 350 * 3.5 / 1440)          # 设备 1440 宽、密度 3.5
    card_h = round(card_w * 474 / 468)
    cy = round(h * 498 / 1280)
    a_card = ((720 - card_w) // 2, cy - card_h // 2, (720 + card_w) // 2, cy + card_h // 2)
    a_start = first_change(android, a_card, 2.0, after=1.6)

    ref = np.load(REFERENCE / 'reference.npy', mmap_mode='r')
    ref_t = np.load(REFERENCE / 'reference-times.npy')
    ref_frames = [(float(ref_t[i]), np.asarray(ref[i])) for i in range(len(ref_t))]
    r_start = first_change(ref_frames, REF_CARD, 2.0)
    print('Android 开始', a_start, '原片开始', r_start)

    # 原片按卡片大小放大到与 Android 同一比例，卡片中心对齐
    scale = card_w / (REF_CARD[2] - REF_CARD[0])
    rcx, rcy = (REF_CARD[0] + REF_CARD[2]) / 2, (REF_CARD[1] + REF_CARD[3]) / 2
    win_w, win_h = 720 / scale, 1280 / scale
    a_crop = (0, cy - round(1280 * 498 / 1280), 720, cy - round(1280 * 498 / 1280) + 1280)
    r_crop = (rcx - win_w / 2, rcy - win_h * 498 / 1280, rcx + win_w / 2, rcy - win_h * 498 / 1280 + win_h)

    cell_w, cell_h = 300, 533
    head, label_w = 34, 120
    sheet = Image.new('RGB', (label_w + cell_w * len(FRACTIONS), head + cell_h * 2), (250, 250, 250))
    draw = ImageDraw.Draw(sheet)
    f = font(20)
    for k, frac in enumerate(FRACTIONS):
        draw.text((label_w + k * cell_w + 8, 6), '%d%%' % round(frac * 100), fill=(30, 30, 30), font=f)
        rt = r_start + frac * (REF_END - r_start)
        _, rf = min(ref_frames, key=lambda it: abs(it[0] - rt))
        rimg = Image.fromarray(rf).crop(tuple(round(v) for v in r_crop)).resize((cell_w, cell_h), Image.LANCZOS)
        at = a_start + frac * 1.0
        _, af = min(android, key=lambda it: abs(it[0] - at))
        aimg = Image.fromarray(af).crop(a_crop).resize((cell_w, cell_h), Image.LANCZOS)
        sheet.paste(rimg, (label_w + k * cell_w, head))
        sheet.paste(aimg, (label_w + k * cell_w, head + cell_h))
    draw.text((8, head + cell_h // 2), '原片', fill=(20, 20, 20), font=font(24))
    draw.text((8, head + cell_h + cell_h // 2), 'Android', fill=(20, 20, 20), font=font(24))
    out = folder / 'ironman-parity.png'
    sheet.save(out)
    print(out, sheet.size)


if __name__ == '__main__':
    main()
