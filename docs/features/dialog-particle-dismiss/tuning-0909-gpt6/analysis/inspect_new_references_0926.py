"""提取七段新增原片的概览；仅分析，不覆盖旧场景。"""
from pathlib import Path
import hashlib
import json
import re
import cv2
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / 'analysis/new-references-0926'
FONT = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 17)


def main():
    OUTPUT.mkdir(parents=True, exist_ok=True)
    metadata = []
    for path in sorted((ROOT / 'new-references-0926').glob('*.mp4')):
        number = int(path.name.split('-')[0])
        approximate = int(re.search(r'第(\d+)秒', path.name)[1])
        cap = cv2.VideoCapture(str(path))
        fps = cap.get(cv2.CAP_PROP_FPS)
        count = int(cap.get(cv2.CAP_PROP_FRAME_COUNT))
        width, height = int(cap.get(3)), int(cap.get(4))
        times = np.linspace(max(0, approximate - .65), (count - 2) / fps, 12)
        tile_w = 240
        tile_h = round(height * tile_w / width)
        sheet = Image.new('RGB', (tile_w * 4, (tile_h + 30) * 3), '#111923')
        draw = ImageDraw.Draw(sheet)
        for i, requested in enumerate(times):
            frame = round(requested * fps)
            cap.set(cv2.CAP_PROP_POS_FRAMES, frame)
            ok, bgr = cap.read()
            assert ok, (path, frame)
            image = Image.fromarray(cv2.cvtColor(bgr, cv2.COLOR_BGR2RGB))
            image.save(OUTPUT / f'ref-{number}-{frame:04d}.png')
            x, y = (i % 4) * tile_w, (i // 4) * (tile_h + 30)
            draw.text((x + 5, y + 3), f'{number} · {frame/fps:.3f} s · #{frame}', font=FONT, fill='white')
            sheet.paste(image.resize((tile_w, tile_h), Image.Resampling.LANCZOS), (x, y + 30))
        sheet.save(OUTPUT / f'overview-{number}.jpg', quality=94)
        cap.release()
        metadata.append(dict(number=number, file=path.name, fps=fps, frames=count,
                             frame=[width,height], duration=count/fps, approximate_start=approximate,
                             sha256=hashlib.sha256(path.read_bytes()).hexdigest()))
        print(f'{number}: {width}×{height}, {fps:g} fps, {count/fps:.3f} s', flush=True)
    (OUTPUT / 'metadata.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2), encoding='utf-8')


if __name__ == '__main__':
    main()
