# -*- coding: utf-8 -*-
"""单变量探查解除贴附相位场对右下高密粒子下缘的影响。"""

from __future__ import annotations

import json

from PIL import Image, ImageDraw

from analyze_lower_envelope_smoothness import PROGRESSES, extract_envelope, model_band
from analyze_reference_morphology import MODEL_SCENARIO
from render_curtain_model import CurtainRenderer, OUT, font


ARC_OFF = (("uSnapshotPx.x * 0.040;", "uSnapshotPx.x * 0.000;"),)
VARIANTS: dict[str, dict[str, float | int]] = {
    "arc-off": {},
    "smooth-11": {"smoothing_passes": 11},
    "smooth-18": {"smoothing_passes": 18},
    "detail-off": {"detail_resistance_scale": 0.0},
    "resistance-half": {
        "broad_resistance_scale": 0.55,
        "detail_resistance_scale": 0.0,
    },
    "low-pass": {
        "broad_resistance_scale": 0.70,
        "detail_resistance_scale": 0.0,
        "smoothing_passes": 14,
    },
}


def crop_frame(frame: Image.Image) -> Image.Image:
    return frame.crop((430, 300, 1070, 790)).resize(
        (640, 490), Image.Resampling.LANCZOS
    )


def main() -> None:
    results: dict[str, object] = {}
    panels: list[tuple[str, Image.Image]] = []
    for name, options in VARIANTS.items():
        renderer = CurtainRenderer(ARC_OFF, release_options=options)
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
    image_path = OUT / "release-field-envelope-probes.png"
    sheet.save(image_path)
    output = {"variants": results, "output": str(image_path)}
    (OUT / "release-field-envelope-probes.json").write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
