# -*- coding: utf-8 -*-
"""探查捕风脉冲对上缘外移与中段下缘连续性的共同影响。"""

from __future__ import annotations

import json

from analyze_edge_sequence import edge_metrics
from analyze_lower_envelope_smoothness import extract_envelope, model_band
from analyze_reference_morphology import MODEL_SCENARIO
from render_curtain_model import CurtainRenderer


TOKEN = "3.10 * capturePulse"
AMPLITUDES = (2.55, 2.70, 2.85, 3.00, 3.10)


def main() -> None:
    rows: list[dict[str, object]] = []
    for amplitude in AMPLITUDES:
        renderer = CurtainRenderer(((TOKEN, f"{amplitude:.2f} * capturePulse"),))
        envelope, _, _, _ = extract_envelope(model_band(renderer, 0.47))
        alpha = renderer.render_particle_rgba(
            MODEL_SCENARIO, 28 / 60
        )[:, :, 3].astype("float32") / 255.0
        model_box = (
            int(renderer.origin[0]),
            int(renderer.origin[1]),
            renderer.snapshot.width,
            renderer.snapshot.height,
        )
        edge = edge_metrics(alpha, model_box)
        rows.append({"amplitude": amplitude, "envelope": envelope, "edge": edge})
    print(json.dumps({"variants": rows}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
