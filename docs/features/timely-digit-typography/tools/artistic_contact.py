"""Faithful 0-9 contact sheet (PIL) for the serif / artistic candidate fonts."""
import os
import sys
from PIL import Image, ImageDraw, ImageFont

FONTS = sys.argv[1] if len(sys.argv) > 1 else "fonts"
OUT = sys.argv[2] if len(sys.argv) > 2 else "previews"
os.makedirs(OUT, exist_ok=True)

ROWS = [("playfairdisplay", "Playfair Display  (Didone, high-contrast)"),
        ("dmserifdisplay", "DM Serif Display  (high-contrast)"),
        ("cormorantgaramond", "Cormorant Garamond  (elegant, thin)"),
        ("abrilfatface", "Abril Fatface  (display Didone, bold)"),
        ("zillaslab", "Zilla Slab  (slab serif)"),
        ("lora", "Lora  (readable serif)")]
DIGITS = "0123456789"
INK = (28, 28, 34)
try:
    LBL = ImageFont.truetype("arialbd.ttf", 22)
except Exception:
    LBL = ImageFont.load_default()

LBL_W, CW, ROW_H = 380, 120, 152
canvas = Image.new("RGB", (LBL_W + CW * 10, ROW_H * len(ROWS)), (255, 255, 255))
dr = ImageDraw.Draw(canvas)
for ri, (fam, label) in enumerate(ROWS):
    p = os.path.join(FONTS, fam + ".ttf")
    if not os.path.exists(p):
        continue
    font = ImageFont.truetype(p, 118)
    for nm in ("Regular", "Medium", "SemiBold"):
        try:
            font.set_variation_by_name(nm)
            break
        except Exception:
            pass
    dr.text((12, ri * ROW_H + ROW_H // 2 - 12), label, fill=(0, 0, 0), font=LBL)
    for ci, ch in enumerate(DIGITS):
        cell = Image.new("RGB", (CW, ROW_H), (255, 255, 255))
        cd = ImageDraw.Draw(cell)
        bb = cd.textbbox((0, 0), ch, font=font)
        w, h = bb[2] - bb[0], bb[3] - bb[1]
        cd.text(((CW - w) // 2 - bb[0], (ROW_H - h) // 2 - bb[1]), ch, fill=INK, font=font)
        canvas.paste(cell, (LBL_W + ci * CW, ri * ROW_H))
out = os.path.join(OUT, "contact_artistic.png")
canvas.save(out)
print("wrote", out)
