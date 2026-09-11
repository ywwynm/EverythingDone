"""从最终导出视频取连续进度，检查多参考形态与屏幕内距离。"""
from pathlib import Path
import json,argparse
import cv2
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parents[1]
OUT = HERE / 'analysis/flow-family'
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 20)


def frame(cap, p):
    cap.set(cv2.CAP_PROP_POS_MSEC, (.35 + p) * 1000)
    ok, raw = cap.read()
    assert ok
    return Image.fromarray(cv2.cvtColor(raw, cv2.COLOR_BGR2RGB))


def main():
    global OUT
    parser=argparse.ArgumentParser();parser.add_argument('--output',default='analysis/flow-family');args=parser.parse_args()
    OUT=HERE/args.output;OUT.mkdir(exist_ok=True,parents=True)
    for name in ['ironman', 'ironman-up-reference', 'thanos', 'kobe']:
        cap = cv2.VideoCapture(str(HERE / 'videos' / f'{name}-family-reference-1x.mp4'))
        samples = [frame(cap, p) for p in [.20, .35, .48, .63, .78]]
        cap.release()
        half = (samples[0].width - 16) // 2
        h = round((samples[0].height - 88 - 54) * 280 / half)
        sheet = Image.new('RGB', (1400, 74 + 2 * (h + 36)), '#101720')
        draw = ImageDraw.Draw(sheet)
        draw.text((12, 8), name + ' · 上排华为参考 / 下排当前模型', font=FONT, fill='white')
        for col, (p, sample) in enumerate(zip([.20, .35, .48, .63, .78], samples)):
            draw.text((col * 280 + 10, 40), f'进度 {p:.2f}', font=FONT, fill='#bcd2df')
            for row in range(2):
                x = row * (half + 16)
                tile = sample.crop((x, 88, x + half, sample.height - 54)).resize((280, h), Image.Resampling.LANCZOS)
                sheet.paste(tile, (col * 280, 74 + row * (h + 36)))
        sheet.save(OUT / f'final-reference-{name}.jpg', quality=95)
    for name in ['ironman', 'attachment', 'color']:
        for kind in ['touch-distances', 'flow-family']:
            cap = cv2.VideoCapture(str(HERE / 'videos' / f'{name}-{kind}-1x.mp4'))
            for p in [.35, .63]:
                image = frame(cap, p)
                image.thumbnail((1600, 1600), Image.Resampling.LANCZOS)
                image.save(OUT / f'final-{name}-{kind}-{p:g}.jpg', quality=95)
            cap.release()
    print('最终视频观测图已生成')


if __name__ == '__main__':
    main()
