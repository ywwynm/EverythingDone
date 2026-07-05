"""
Static 0-9 preview generator for the Timely digit typography redesign.

For each candidate monoline font it renders three rows per digit:
  row 1  filled reference glyph            (what the font actually looks like)
  row 2  medial-axis skeleton over glyph   (the centreline we would morph)
  row 3  skeleton stroked at a width       (how it renders once stroked)

This doubles as a feasibility spike: if the centreline (row 2) comes out clean
and font-like, a stroke-based skeleton morph (Option A) is viable for that font.

Usage:
  python preview_digits.py <fonts_dir> <out_dir>
"""
import os
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont
from skimage.morphology import skeletonize, disk
from scipy.ndimage import binary_dilation

FONTS_DIR = sys.argv[1] if len(sys.argv) > 1 else "fonts"
OUT_DIR = sys.argv[2] if len(sys.argv) > 2 else "previews"
os.makedirs(OUT_DIR, exist_ok=True)

STYLE = {
    "poppins": "Geometric  |  Poppins",
    "montserrat": "Geometric  |  Montserrat",
    "comfortaa": "Rounded  |  Comfortaa",
    "nunito": "Rounded  |  Nunito",
    "orbitron": "Technical  |  Orbitron",
    "jetbrainsmono": "Technical  |  JetBrains Mono",
}
ORDER = ["poppins", "montserrat", "comfortaa", "nunito", "orbitron", "jetbrainsmono"]

CELL_W, CELL_H = 200, 280
FONT_PX = 210
DIGITS = "0123456789"
INK = (28, 28, 34, 255)
GRAY = (140, 142, 150, 255)
RED = (228, 40, 62, 255)
WHITE = (255, 255, 255, 255)
STROKE_R = 6  # skeleton stroke radius for row 3

try:
    LABEL_FONT = ImageFont.truetype("arialbd.ttf", 24)
except Exception:
    LABEL_FONT = ImageFont.load_default()


def load_font(path):
    font = ImageFont.truetype(path, FONT_PX)
    applied = "default"
    for name in ("Medium", "SemiBold", "Regular", "Bold"):
        try:
            font.set_variation_by_name(name)
            applied = name
            break
        except Exception:
            pass
    return font, applied


def glyph_mask(font, ch):
    img = Image.new("L", (CELL_W, CELL_H), 0)
    d = ImageDraw.Draw(img)
    bbox = d.textbbox((0, 0), ch, font=font)
    w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
    x = (CELL_W - w) // 2 - bbox[0]
    y = (CELL_H - h) // 2 - bbox[1]
    d.text((x, y), ch, fill=255, font=font)
    return np.array(img) > 128


def cell_filled(mask):
    rgba = np.full((CELL_H, CELL_W, 4), WHITE, np.uint8)
    rgba[mask] = INK
    return rgba


def cell_skeleton(mask):
    skel = skeletonize(mask)
    rgba = np.full((CELL_H, CELL_W, 4), WHITE, np.uint8)
    rgba[mask] = GRAY
    rgba[binary_dilation(skel, disk(1))] = RED
    return rgba, skel


def cell_stroked(skel):
    rgba = np.full((CELL_H, CELL_W, 4), WHITE, np.uint8)
    rgba[binary_dilation(skel, disk(STROKE_R))] = INK
    return rgba


def grid_for(fam, path):
    font, applied = load_font(path)
    HEAD = 44
    canvas = Image.new("RGB", (CELL_W * 10, HEAD + CELL_H * 3), (255, 255, 255))
    dr = ImageDraw.Draw(canvas)
    dr.text((12, 10), f"{STYLE[fam]}   (weight: {applied})", fill=(0, 0, 0), font=LABEL_FONT)
    for ci, ch in enumerate(DIGITS):
        try:
            m = glyph_mask(font, ch)
            f = cell_filled(m)
            sk_cell, skel = cell_skeleton(m)
            st = cell_stroked(skel)
        except Exception as e:
            print(f"  ! {fam} '{ch}': {e}")
            continue
        for ri, cell in enumerate([f, sk_cell, st]):
            im = Image.fromarray(cell, "RGBA").convert("RGB")
            canvas.paste(im, (ci * CELL_W, HEAD + ri * CELL_H))
    out = os.path.join(OUT_DIR, f"preview_{fam}.png")
    canvas.save(out)
    return out


def contact_sheet(paths):
    """One filled-only sheet: 6 fonts x 10 digits, for quick style comparison."""
    HEAD = 0
    ROW_H = 150
    LBL_W = 300
    cw = 110
    canvas = Image.new("RGB", (LBL_W + cw * 10, ROW_H * len(ORDER)), (255, 255, 255))
    dr = ImageDraw.Draw(canvas)
    for ri, fam in enumerate(ORDER):
        path = os.path.join(FONTS_DIR, fam + ".ttf")
        if not os.path.exists(path):
            continue
        font = ImageFont.truetype(path, 120)
        for name in ("Medium", "SemiBold", "Regular"):
            try:
                font.set_variation_by_name(name)
                break
            except Exception:
                pass
        dr.text((12, ri * ROW_H + ROW_H // 2 - 12), STYLE[fam], fill=(0, 0, 0), font=LABEL_FONT)
        for ci, ch in enumerate(DIGITS):
            cell = Image.new("RGB", (cw, ROW_H), (255, 255, 255))
            cd = ImageDraw.Draw(cell)
            bbox = cd.textbbox((0, 0), ch, font=font)
            w, h = bbox[2] - bbox[0], bbox[3] - bbox[1]
            cd.text(((cw - w) // 2 - bbox[0], (ROW_H - h) // 2 - bbox[1]), ch, fill=INK[:3], font=font)
            canvas.paste(cell, (LBL_W + ci * cw, ri * ROW_H))
    out = os.path.join(OUT_DIR, "contact_sheet.png")
    canvas.save(out)
    return out


if __name__ == "__main__":
    made = []
    for fam in ORDER:
        p = os.path.join(FONTS_DIR, fam + ".ttf")
        if os.path.exists(p):
            made.append(grid_for(fam, p))
            print("wrote", made[-1])
        else:
            print("missing font:", p)
    cs = contact_sheet(made)
    print("wrote", cs)
