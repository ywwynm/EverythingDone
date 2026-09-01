# -*- coding: utf-8 -*-
"""单变量探查下缘凸包：先只改变横向转流强度，再比较低频化方案。"""

from __future__ import annotations

import json

from PIL import Image, ImageDraw

from analyze_lower_envelope_smoothness import PROGRESSES, extract_envelope, model_band
from analyze_reference_morphology import MODEL_SCENARIO
from render_curtain_model import CurtainRenderer, OUT, font


STREAM_TOKEN = "streamTurn * 0.580 * flightAge"
VARIANTS: dict[str, tuple[tuple[str, str], ...]] = {
    "baseline": (),
    "stream-045": ((STREAM_TOKEN, "streamTurn * 0.450 * flightAge"),),
    "stream-032": ((STREAM_TOKEN, "streamTurn * 0.320 * flightAge"),),
    "stream-020": ((STREAM_TOKEN, "streamTurn * 0.200 * flightAge"),),
    "low-pass-030": (
        ("broadField * 0.320 * flightAge", "broadField * 0.220 * flightAge"),
        (
            "curl * 0.120 * flightAge * flightAge",
            "curl * 0.000 * flightAge * flightAge",
        ),
        ("coherentField * 0.045 * transverse", "coherentField * 0.022 * transverse"),
        (STREAM_TOKEN, "streamTurn * 0.300 * flightAge"),
        ("sideRandom * 0.010 * transverse", "sideRandom * 0.006 * transverse"),
    ),
}


def crop_frame(frame: Image.Image) -> Image.Image:
    # 直接保留用户指出的右下折叠区域和相邻上下文。
    return frame.crop((430, 300, 1070, 790)).resize((640, 490), Image.Resampling.LANCZOS)


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
    image_path = OUT / "lower-envelope-probes.png"
    sheet.save(image_path)
    output = {"variants": results, "output": str(image_path)}
    (OUT / "lower-envelope-probes.json").write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
