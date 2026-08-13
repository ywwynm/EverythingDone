"""
Generate a synthetic "long screenshot" frame sequence that mimics an
EverythingDone Detail page share:
  - gradient background (the app uses gradient thing backgrounds)
  - lots of anti-aliased text rows (title + content + checklist)
  - an attachment grid; N cells are "animated" (video / motion photo / gif)
    and carry high-entropy moving content, the rest are still photos.
Writes raw rgb24 frames to stdout for ffmpeg.

usage: python gen_longshot.py <W> <H> <NFRAMES> <N_ANIMATED>
"""
import sys
import numpy as np
from PIL import Image, ImageDraw, ImageFont

W = int(sys.argv[1])
H = int(sys.argv[2])
NF = int(sys.argv[3])
NANIM = int(sys.argv[4])

rng = np.random.default_rng(20260726)


def load_font(size):
    for p in (r"C:\Windows\Fonts\msyh.ttc", r"C:\Windows\Fonts\segoeui.ttf",
              r"C:\Windows\Fonts\arial.ttf"):
        try:
            return ImageFont.truetype(p, size)
        except Exception:
            continue
    return ImageFont.load_default()


def build_static_base():
    """The parts of the long screenshot that never change across frames."""
    # vertical gradient background, teal -> deep purple (typical accent bg)
    y = np.linspace(0, 1, H, dtype=np.float32)[:, None]
    top = np.array([0x00, 0x96, 0x88], dtype=np.float32)
    bot = np.array([0x3F, 0x22, 0x6B], dtype=np.float32)
    bg = (top * (1 - y) + bot * y)[:, None, :].repeat(W, axis=1)
    # subtle horizontal shading so it is not a pure 1-D ramp
    x = np.linspace(-0.12, 0.12, W, dtype=np.float32)[None, :, None]
    bg = np.clip(bg * (1 + x), 0, 255)
    img = Image.fromarray(bg.astype(np.uint8), "RGB")

    d = ImageDraw.Draw(img)
    f_title = load_font(int(W * 0.052))
    f_body = load_font(int(W * 0.036))
    d.text((int(W * 0.06), int(H * 0.02)), "周末计划 / Weekend plan",
           font=f_title, fill=(255, 255, 255))
    lines = [
        "把上周拍的视频剪一版发出去，顺便试试新的分享功能。",
        "Check the attachment rendering on the long screenshot path.",
        "· 早上 09:00 出门，带上相机和备用电池",
        "· 中午在江边吃饭，拍一段实况照片",
        "· 下午回来整理素材，导出 1080p",
        "· 晚上写一段记录，附上录音",
    ]
    yy = int(H * 0.075)
    for ln in lines:
        d.text((int(W * 0.06), yy), ln, font=f_body, fill=(255, 255, 255, 230))
        yy += int(W * 0.055)
    return np.array(img, dtype=np.uint8), yy


base, text_bottom = build_static_base()

# --- attachment grid geometry: 2 columns, cells are square ---
pad = int(W * 0.05)
gap = int(W * 0.02)
cell = (W - 2 * pad - gap) // 2
grid_top = text_bottom + int(W * 0.04)
rows = max(2, (H - grid_top - int(H * 0.12)) // (cell + gap))
n_cells = rows * 2


def photo_tile(seed, size):
    """A still photo-ish tile: smooth gradients + structure + grain."""
    r = np.random.default_rng(seed)
    g = np.zeros((size, size, 3), np.float32)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32) / size
    for c in range(3):
        g[:, :, c] = (
            120 + 90 * np.sin(6.0 * xx * (1 + 0.3 * c) + r.uniform(0, 6))
            + 60 * np.cos(4.5 * yy * (1 + 0.2 * c) + r.uniform(0, 6))
        )
    g += r.normal(0, 4, g.shape)  # sensor grain
    return np.clip(g, 0, 255).astype(np.uint8)


still_tiles = {i: photo_tile(1000 + i, cell) for i in range(n_cells)}
# the animated attachments occupy the first NANIM cells
anim_cells = list(range(NANIM))


def anim_tile(idx, t, size):
    """A frame of video-ish content: panning texture + moving subject."""
    r = np.random.default_rng(500 + idx)
    yy, xx = np.mgrid[0:size, 0:size].astype(np.float32) / size
    ph = t * 0.9
    g = np.zeros((size, size, 3), np.float32)
    for c in range(3):
        g[:, :, c] = (
            130 + 80 * np.sin(5.0 * (xx + 0.25 * ph) * (1 + 0.25 * c) + r.uniform(0, 6))
            + 55 * np.cos(4.0 * (yy - 0.15 * ph) * (1 + 0.2 * c) + r.uniform(0, 6))
        )
    # a moving bright subject
    cx = 0.5 + 0.28 * np.sin(2.6 * ph + idx)
    cy = 0.5 + 0.18 * np.cos(2.0 * ph + idx)
    d2 = (xx - cx) ** 2 + (yy - cy) ** 2
    blob = np.exp(-d2 / 0.010)
    g += blob[:, :, None] * np.array([110, 60, -40], np.float32)
    g += r.normal(0, 5, g.shape)  # per-frame grain: video noise floor
    return np.clip(g, 0, 255).astype(np.uint8)


# paste the still tiles into the base once
for i in range(n_cells):
    if i in anim_cells:
        continue
    rr, cc = divmod(i, 2)
    y0 = grid_top + rr * (cell + gap)
    x0 = pad + cc * (cell + gap)
    if y0 + cell > H:
        break
    base[y0:y0 + cell, x0:x0 + cell] = still_tiles[i]

out = sys.stdout.buffer
for fi in range(NF):
    frame = base.copy()
    t = fi / 25.0
    for i in anim_cells:
        rr, cc = divmod(i, 2)
        y0 = grid_top + rr * (cell + gap)
        x0 = pad + cc * (cell + gap)
        if y0 + cell > H:
            continue
        frame[y0:y0 + cell, x0:x0 + cell] = anim_tile(i, t, cell)
    out.write(frame.tobytes())
out.flush()
sys.stderr.write(
    f"geom W={W} H={H} cell={cell} grid_top={grid_top} rows={rows} "
    f"cells={n_cells} animated={NANIM}\n")
