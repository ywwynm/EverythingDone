# -*- coding: utf-8 -*-
"""联合探查连续前沿的统一离面距离、包络深度与帷幔独立性。"""

from __future__ import annotations

import json

from analyze_lower_envelope_smoothness import extract_envelope, model_band
from analyze_model_activation import (
    curtain_ridge_metrics,
    fit_alpha_for_comparison,
    scaled_box,
)
from analyze_reference_morphology import MODEL_SCENARIO
from render_curtain_model import CurtainRenderer, OUT


TOKEN = "uSnapshotPx.x * 0.024;"
OFFSETS = (0.018, 0.022, 0.026, 0.030, 0.034, 0.040)


def main() -> None:
    results: list[dict[str, object]] = []
    for offset in OFFSETS:
        renderer = CurtainRenderer(((TOKEN, f"uSnapshotPx.x * {offset:.3f};"),))
        envelope, _, _, _ = extract_envelope(model_band(renderer, 0.47))
        ox, oy = map(int, renderer.origin)
        width, height = renderer.snapshot.size
        expanded_native = (
            ox - 100,
            oy - 100,
            ox + width + 100,
            oy + height + 100,
        )
        band_alpha, scale, offset_x, offset_y = fit_alpha_for_comparison(
            renderer.render_diagnostic_rgba(
                MODEL_SCENARIO, 0.47, "depth-band"
            )[:, :, 3]
        )
        still_alpha, _, _, _ = fit_alpha_for_comparison(
            renderer.render_still_rgba(MODEL_SCENARIO, 0.47)[:, :, 3]
        )
        ridge = curtain_ridge_metrics(
            still_alpha,
            band_alpha,
            scaled_box(expanded_native, scale, offset_x, offset_y),
        )
        results.append({"offset": offset, "envelope": envelope, "ridge": ridge})
    output = {"variants": results}
    path = OUT / "uniform-front-offset-probes.json"
    path.write_text(json.dumps(output, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(output, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
