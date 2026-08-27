# -*- coding: utf-8 -*-
"""检查 canonical 消失 Shader 是否确实强调 Dialog 的高饱和内容色。"""

from __future__ import annotations

import numpy as np

from render_curtain_model import CurtainRenderer, Scenario


SATURATION_THRESHOLD = 0.18
MIN_EMPHASIS_RATIO = 2.0
SEEDS = (19, 42, 73, 127)
TIMES = (0.32, 0.40, 0.50, 0.60)


def saturated_alpha_share(rgba: np.ndarray) -> tuple[float, float]:
    pixels = rgba.astype(np.float32) / 255.0
    alpha = pixels[:, :, 3]
    straight_rgb = np.where(
        alpha[:, :, None] > 1e-5,
        pixels[:, :, :3] / np.maximum(alpha[:, :, None], 1e-5),
        0.0,
    )
    saturation = straight_rgb.max(axis=2) - straight_rgb.min(axis=2)
    saturated_mass = float((alpha * (saturation >= SATURATION_THRESHOLD)).sum())
    return saturated_mass, float(alpha.sum())


def main() -> None:
    renderer = CurtainRenderer()
    snapshot = np.asarray(renderer.snapshot)
    source_saturated, source_total = saturated_alpha_share(snapshot)
    source_share = source_saturated / source_total

    particle_saturated = 0.0
    particle_total = 0.0
    for seed in SEEDS:
        scenario = Scenario(f"up-{seed}", -90.0, seed)
        for time_seconds in TIMES:
            saturated, total = saturated_alpha_share(
                renderer.render_particle_rgba(scenario, time_seconds)
            )
            particle_saturated += saturated
            particle_total += total

    particle_share = particle_saturated / particle_total
    emphasis_ratio = particle_share / source_share
    print(
        f"source={source_share:.4f}; particles={particle_share:.4f}; "
        f"emphasis={emphasis_ratio:.2f}x; required>={MIN_EMPHASIS_RATIO:.2f}x"
    )
    if emphasis_ratio < MIN_EMPHASIS_RATIO:
        raise SystemExit("内容色强调回归：高饱和粒子的视觉权重不足")


if __name__ == "__main__":
    main()
