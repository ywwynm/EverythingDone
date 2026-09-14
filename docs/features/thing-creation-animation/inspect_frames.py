"""将真实设备截图及其采样时刻排成联系图，不插帧。"""
import argparse
import json
from pathlib import Path
from PIL import Image, ImageDraw

p = argparse.ArgumentParser()
p.add_argument('folder')
p.add_argument('--count', type=int, default=20)
a = p.parse_args()
folder = Path(a.folder)
timings = json.loads((folder / 'timings.json').read_text())
paths = sorted(folder.glob('[0-9][0-9][0-9].png'))[:a.count]
canvas = Image.new('RGB', (480 * 4, 344 * ((len(paths)+3)//4)), 'white')
draw = ImageDraw.Draw(canvas)
for i, path in enumerate(paths):
    frame = Image.open(path).convert('RGB')
    frame.thumbnail((480, 320))
    x, y = i % 4 * 480, i // 4 * 344
    canvas.paste(frame, (x, y+24))
    draw.text((x+8, y+4), f'{i}: {timings[i][0]:.3f}-{timings[i][1]:.3f}s', fill='black')
canvas.save(folder / 'contact.png')
print(folder / 'contact.png')
