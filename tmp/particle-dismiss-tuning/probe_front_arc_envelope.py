# -*- coding: utf-8 -*-
"""单变量探查剥离前沿弓形位移对右下粒子下缘的影响。"""

from __future__ import annotations

import json

from PIL import Image, ImageDraw

from analyze_lower_envelope_smoothness import PROGRESSES, extract_envelope, model_band
from analyze_reference_morphology import MODEL_SCENARIO
from render_curtain_model import CurtainRenderer, OUT, font


ARC_TOKEN = """float frontArc = max(
        0.0,
        1.0 - pow(frontArcCoordinate / 0.45, 2.0)
    );"""
SMOOTH_ARC = """float frontArc = 1.0 - smoothstep(
        0.0,
        0.48,
        abs(frontArcCoordinate)
    );"""
DEPTH_TOKEN = "uSnapshotPx.x * 0.040;"


def replacement(depth: str, smooth: bool = True) -> tuple[tuple[str, str], ...]:
    items: list[tuple[str, str]] = [(DEPTH_TOKEN, f"uSnapshotPx.x * {depth};")]
    if smooth:
        items.insert(0, (ARC_TOKEN, SMOOTH_ARC))
    return tuple(items)


VARIANTS: dict[str, tuple[tuple[str, str], ...]] = {
    "baseline": (),
    "arc-off": ((DEPTH_TOKEN, "uSnapshotPx.x * 0.000;"),),
    "smooth-030": replacement("0.030"),
    "smooth-022": replacement("0.022"),
    "smooth-016": replacement("0.016"),
    "smooth-off": replacement("0.000"),
}


def crop_frame(frame: Image.Image) -> Image.Image:
    return frame.crop((430, 300, 1070, 790)).resize(
        (640, 490), Image.Resampling.LANCZOS
    )


def main() -> None:
    results: dict[str, object] = {}
    panels: list[tuple[str, Image.Image]] = []
    for name, replacements in VARIANTS.items():
        renderer = CurtainRenderer(replacements)
        rows: list[dict[str, float]] = []
        for progress in PROGRESSES:
            metrics, _, _, _ = extract_envelope(model_band(renderer, progress))
            rows.append({"progress": progress, **metrics})
        results[name] = rows
        panels.append((name, crop_frame(renderer.render(MODEL_SCENARIO, 0.47))))

    panel_w, panel_h = 640, 526
    sheet = Image.new("RGB", (panel_w * 2, panel_h * 3), (10, 12, 17))
    draw = ImageDraw.Draw(sheet)
    for index, (name, panel) in enumerate(panels):
        x = (index % 2) * panel_w
        y = (index // 2) * panel_h
        sheet.paste(panel, (x, y + 36))
        draw.text((x + 10, y + 7), name, font=font(20), fill=(238, 240, 245))
    image_path = OUT / "front-arc-envelope-probes.png"
    sheet.save(image_path)
    output = {"variants": results, "output": str(image_path)}
    (OUT / "front-arc-envelope-probes.json").write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
