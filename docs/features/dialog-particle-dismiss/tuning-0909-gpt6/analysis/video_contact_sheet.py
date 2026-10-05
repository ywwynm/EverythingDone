"""从导出的三栏成片中按帧号抽帧拼成联系表，用于连续检查编码后的动画（不是缓存帧）。"""
from pathlib import Path
import argparse, subprocess, tempfile
from PIL import Image, ImageDraw, ImageFont

FFMPEG = 'C:/ffmpeg/bin/ffmpeg.exe'
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 16)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('video')
    ap.add_argument('--start', type=int, default=0)
    ap.add_argument('--count', type=int, default=24)
    ap.add_argument('--step', type=int, default=1)
    ap.add_argument('--per-row', type=int, default=4)
    ap.add_argument('--scale', type=float, default=.35)
    ap.add_argument('--crop', type=int, nargs=4, help='帧内裁剪 x0 y0 x1 y1（原尺寸像素）')
    ap.add_argument('--out', required=True)
    a = ap.parse_args()
    with tempfile.TemporaryDirectory() as tmp:
        sel = '+'.join(f'eq(n\\,{a.start + k * a.step})' for k in range(a.count))
        subprocess.run([FFMPEG, '-v', 'error', '-i', a.video, '-vf', f"select='{sel}'", '-vsync', '0', f'{tmp}/f%03d.png'], check=True)
        files = sorted(Path(tmp).glob('f*.png'))
        ims = []
        for k, f in enumerate(files):
            im = Image.open(f).convert('RGB')
            if a.crop: im = im.crop(a.crop)
            im = im.resize((round(im.width * a.scale), round(im.height * a.scale)), Image.Resampling.LANCZOS)
            ImageDraw.Draw(im).text((4, 2), f'#{a.start + k * a.step}', font=FONT, fill=(255, 255, 0))
            ims.append(im)
    w, h = ims[0].size; rows = (len(ims) + a.per_row - 1) // a.per_row
    sheet = Image.new('RGB', (a.per_row * (w + 3), rows * (h + 3)), (10, 10, 14))
    for k, im in enumerate(ims):
        sheet.paste(im, ((k % a.per_row) * (w + 3), (k // a.per_row) * (h + 3)))
    sheet.save(a.out, quality=90); print(a.out, len(ims))


if __name__ == '__main__':
    main()
