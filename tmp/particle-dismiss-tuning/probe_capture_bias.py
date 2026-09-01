"""探测中段捕风脉冲是否只作用于后释放的主布面。

左上/上边缘早发材料不应被用于清除右下残留的捕风脉冲提前甩远；两者仍
共享同一风场，只按连续 release delay 调整脉冲耦合强度。
"""

from __future__ import annotations

import json

from probe_top_left_cohesion import compact, measure


CAPTURE_BLOCK = """float mainSheetCapture = 1.0 + (1.0 - preShed) * (
            0.90 * smoothstep(0.26, 0.50, uTime) +
            2.50 * capturePulse
        );"""
PULSE_BLOCK = """float capturePulse = smoothstep(0.44, 0.51, uTime) *
            (1.0 - smoothstep(0.58, 0.72, uTime));"""


def capture_bias(minimum: float, maximum: float = 1.0) -> tuple[tuple[str, str], ...]:
    return (
        (
            CAPTURE_BLOCK,
            f"""float captureCoupling = mix(
            {minimum:.2f}, {maximum:.2f}, lateRelease
        );
        float mainSheetCapture = 1.0 + (1.0 - preShed) * (
            0.90 * smoothstep(0.26, 0.50, uTime) +
            2.50 * capturePulse * captureCoupling
        );""",
        ),
    )


def capture_window(
    start: float,
    end: float,
    amplitude: float,
) -> tuple[tuple[str, str], ...]:
    return (
        (
            PULSE_BLOCK,
            f"""float capturePulse = smoothstep({start:.2f}, {end:.2f}, uTime) *
            (1.0 - smoothstep(0.58, 0.72, uTime));""",
        ),
        ("2.50 * capturePulse", f"{amplitude:.2f} * capturePulse"),
    )


def edge_restraint(
    continued_age: float,
    stretch: float,
) -> tuple[tuple[str, str], ...]:
    return (
        (
            "max(flightAge - 0.20, 0.0) * 0.28",
            f"max(flightAge - 0.20, 0.0) * {continued_age:.2f}",
        ),
        (
            "point.xy += direction * preShedStretch * 0.070;",
            f"point.xy += direction * preShedStretch * {stretch:.3f};",
        ),
    )


def main() -> None:
    variants = [("baseline", ())]
    variants.extend([
        ("window-044-051-a300", capture_window(0.44, 0.51, 3.00)),
        ("window-044-051-a310", capture_window(0.44, 0.51, 3.10)),
        ("window-044-051-a320", capture_window(0.44, 0.51, 3.20)),
        ("window-044-051-a330", capture_window(0.44, 0.51, 3.30)),
    ])
    results = []
    for name, replacements in variants:
        measured = measure(name, replacements)
        result = compact(measured)
        result["top_protrusion_047"] = measured["edge"]["0.47"][
            "top_protrusion_p95_h"
        ]
        result["left_protrusion_047"] = measured["edge"]["0.47"][
            "left_protrusion_p95_w"
        ]
        results.append(result)
    print(json.dumps(results, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
