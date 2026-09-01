# -*- coding: utf-8 -*-
"""单变量探查脱离相位对右下高密粒子下缘的影响。"""

from __future__ import annotations

import json

from PIL import Image, ImageDraw

from analyze_lower_envelope_smoothness import PROGRESSES, extract_envelope, model_band
from analyze_reference_morphology import MODEL_SCENARIO
from render_curtain_model import CurtainRenderer, OUT, font


ARC_DEPTH = ("uSnapshotPx.x * 0.040;", "uSnapshotPx.x * 0.000;")
PHASE_TOKEN = """dot((uv - 0.5) * uClothCardSize, detachDirection) * 0.72 +
        dot((uv - 0.5) * uClothCardSize, detachPerpendicular) * 1.08"""
COHERENCE_TOKEN = "float coherentDetach = 0.5 + 0.5 * sin(detachPhase);"
SPAN_TOKEN = "float detachCoherenceSpan = mix(0.165, 0.105, lateRelease);"


VARIANTS: dict[str, tuple[tuple[str, str], ...]] = {
    "arc-off": (ARC_DEPTH,),
    "coherence-constant": (
        ARC_DEPTH,
        (COHERENCE_TOKEN, "float coherentDetach = 0.5;"),
    ),
    "coherence-broad": (
        ARC_DEPTH,
        (
            PHASE_TOKEN,
            """dot((uv - 0.5) * uClothCardSize, detachDirection) * 0.34 +
        dot((uv - 0.5) * uClothCardSize, detachPerpendicular) * 0.46""",
        ),
    ),
    "span-short": (
        ARC_DEPTH,
        (SPAN_TOKEN, "float detachCoherenceSpan = mix(0.105, 0.070, lateRelease);"),
    ),
    "broad-short": (
        ARC_DEPTH,
        (
            PHASE_TOKEN,
            """dot((uv - 0.5) * uClothCardSize, detachDirection) * 0.34 +
        dot((uv - 0.5) * uClothCardSize, detachPerpendicular) * 0.46""",
        ),
        (SPAN_TOKEN, "float detachCoherenceSpan = mix(0.105, 0.070, lateRelease);"),
    ),
    "constant-short": (
        ARC_DEPTH,
        (COHERENCE_TOKEN, "float coherentDetach = 0.5;"),
        (SPAN_TOKEN, "float detachCoherenceSpan = mix(0.105, 0.070, lateRelease);"),
    ),
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
        rows: list[dict[str, float | str]] = []
        for progress in PROGRESSES:
            try:
                metrics, _, _, _ = extract_envelope(model_band(renderer, progress))
                rows.append({"progress": progress, **metrics})
            except RuntimeError as error:
                rows.append({"progress": progress, "error": str(error)})
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
    image_path = OUT / "detach-coherence-envelope-probes.png"
    sheet.save(image_path)
    output = {"variants": results, "output": str(image_path)}
    (OUT / "detach-coherence-envelope-probes.json").write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
