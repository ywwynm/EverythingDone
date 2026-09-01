# -*- coding: utf-8 -*-
"""单变量探测脱离薄面后的连续流线剪切，不修改 Android canonical Shader。"""

from __future__ import annotations

import json

import cv2
import numpy as np
from PIL import Image, ImageDraw

from analyze_directional_topology import envelope_metrics
from analyze_model_cohesion import cohesion_metrics, performance_proxy
from analyze_reference_morphology import MODEL_SCENARIO, frame_metrics, model_layers
from render_curtain_model import CurtainRenderer, OUT, font, load_canonical_shaders


DOCUMENTED_OLD_FLIGHT = """float memory = 0.10 + 0.08 * h2;
                    float lateWind = 1.0 + 0.32 * smoothstep(0.52, 0.82, uTime);
                    float coherentField = sin((uv.x * 3.7 + uv.y * 2.9) * PI_VALUE + 0.35);
                    float curl = sin(
                        (uv.x * 2.8 + uv.y * 2.2) * PI_VALUE * 2.0 -
                            flightAge * 4.6 + 0.55
                    );
                    float foldedGust = (0.66 + 0.18 * coherentField) * flightAge +
                        (1.08 + 0.16 * curl) * lateWind * flightAge * flightAge;
                    float individualGust = (h1 - 0.5) * 0.22 * flightAge +
                        (h2 - 0.5) * 0.34 * lateWind * flightAge * flightAge;
                    float gust = foldedGust + individualGust *
                        (1.0 - curtainWeight * 0.72);
                    float transverse = smoothstep(0.0, 0.16, flightAge) *
                        (1.0 - 0.35 * smoothstep(0.72, 0.97, uTime));
                    float crossDrift = (
                        coherentField * 0.082 + curl * 0.044 + sideRandom * 0.014
                    ) * transverse;
                    float normalDrift = (
                        coherentField * 0.052 +
                        sin((uv.x * 1.9 - uv.y * 2.6) * PI_VALUE * 2.0 +
                            flightAge * 3.1) * 0.026 +
                        liftRandom * 0.012
                    ) * transverse;
                    float sourceAlong = clamp(
                        dot(sourceVelocity.xy, direction),
                        0.0,
                        2.8
                    );
                    float sourceSide = clamp(
                        dot(sourceVelocity.xy, perpendicular),
                        -0.65,
                        0.65
                    );
                    float inheritedAlong = sourceAlong * flightAge * memory;
                    float inheritedSide = sourceSide * flightAge * 0.055;
                    vec3 flightPoint = vec3(
                        sourcePoint.xy +
                            direction * (inheritedAlong + gust) +
                            perpendicular * (inheritedSide + crossDrift) +
                            sourceNormal.xy * normalDrift,
                        sourcePoint.z +
                            clamp(sourceVelocity.z, -1.4, 2.4) * flightAge * memory * 0.58 +
                            (0.20 + 0.24 * h1) * flightAge +
                            sourceNormal.z * normalDrift -
                            0.10 * flightAge * flightAge
                    );"""


def canonical_flight_block() -> str:
    vertex = load_canonical_shaders()[0]
    start = vertex.index("float memory = 0.10 + 0.08 * h2;")
    end = vertex.index("\n        point = mix(surfacePoint, flightPoint, free);", start)
    return vertex[start:end]


OLD_FLIGHT = canonical_flight_block()


def canonical_detach_block() -> str:
    vertex = load_canonical_shaders()[0]
    start = vertex.index("float lateRelease = smoothstep(0.22, 0.58, delay);")
    end = vertex.index("\n    int detachFrame = clamp(", start)
    return vertex[start:end]


OLD_DETACH = canonical_detach_block()

OLD_GUST = """float gust = foldedGust + individualGust *
            (1.0 - curtainWeight * 0.72);"""


def gust_block(scale: float) -> str:
    return f"""float gust = (foldedGust + individualGust *
            (1.0 - curtainWeight * 0.72)) * {scale:.4f};"""


def detach_block(
    base: float,
    amplitude: float,
    random_amount: float,
    late_advance: float,
) -> str:
    return f"""float lateRelease = smoothstep(0.22, 0.58, delay);
        vec2 detachDirection = normalize(uSweepDir);
        vec2 detachPerpendicular = vec2(-detachDirection.y, detachDirection.x);
        float detachPhase = (
            dot((uv - 0.5) * uClothCardSize, detachDirection) * 0.72 +
            dot((uv - 0.5) * uClothCardSize, detachPerpendicular) * 1.08
        ) / max(uClothCardSize.x, 0.001) * PI_VALUE * 2.0 + 0.72;
        float coherentDetach = 0.5 + 0.5 * sin(detachPhase);
        float detachTime = clamp(
            delay + {base:.4f} + {amplitude:.4f} * coherentDetach +
                {random_amount:.4f} * h3 - {late_advance:.4f} * lateRelease,
            0.025,
            0.86
        );"""


def flight_block(
    shear: float,
    bend: float,
    relax: float,
    quadratic: float = 1.10,
) -> str:
    return f"""float memory = 0.08 + 0.06 * h2;
                    float lateWind = 1.0 + 0.32 * smoothstep(0.52, 0.82, uTime);
                    vec2 materialPoint = (uv - 0.5) * uClothCardSize;
                    float alongExtent = max(
                        abs(direction.x) * uClothCardSize.x * 0.5 +
                            abs(direction.y) * uClothCardSize.y * 0.5,
                        0.001
                    );
                    float crossExtent = max(
                        abs(perpendicular.x) * uClothCardSize.x * 0.5 +
                            abs(perpendicular.y) * uClothCardSize.y * 0.5,
                        0.001
                    );
                    float materialAlong = dot(materialPoint, direction) / alongExtent;
                    float materialCross = dot(materialPoint, perpendicular) / crossExtent;
                    float streamlineA = sin(
                        (materialCross * 0.82 + materialAlong * 0.24) * PI_VALUE + 0.35
                    );
                    float streamlineB = sin(
                        (materialCross * 0.46 - materialAlong * 0.71) * PI_VALUE * 2.0 + 1.10
                    );
                    float speedShear = clamp(
                        1.0 + (streamlineA * 0.68 + streamlineB * 0.32) * {shear:.4f},
                        0.58,
                        1.42
                    );
                    float pathPhase = materialCross * PI_VALUE * 1.18 +
                        materialAlong * 0.52 + 0.40;
                    float pathTurn = sin(
                        pathPhase + flightAge * (3.0 + streamlineB * 0.42)
                    ) - sin(pathPhase);
                    float transverse = smoothstep(0.0, 0.20, flightAge) *
                        (1.0 - 0.28 * smoothstep(0.76, 0.98, uTime));
                    float coherentField = sin(
                        (materialCross * 0.74 + materialAlong * 0.31) * PI_VALUE * 2.0 +
                            flightAge * 1.45 + 0.62
                    );
                    float foldedGust = (
                        (0.64 + 0.12 * streamlineA) * flightAge +
                        ({quadratic:.4f} + 0.20 * streamlineB) * lateWind * flightAge * flightAge
                    ) * speedShear;
                    float individualGust = (
                        (h1 - 0.5) * 0.055 * flightAge +
                        (h2 - 0.5) * 0.080 * lateWind * flightAge * flightAge
                    ) * (1.0 - curtainWeight * 0.72);
                    float gust = foldedGust + individualGust;
                    float sourceCross = dot(sourcePoint.xy, perpendicular);
                    float crossRelax = smoothstep(0.055, 0.42, flightAge) *
                        ({relax:.4f} + 0.055 * streamlineB);
                    float crossDrift = (
                        pathTurn * {bend:.4f} + coherentField * 0.038 -
                            sourceCross * crossRelax
                    ) * transverse;
                    float normalDrift = (
                        pathTurn * {bend * 0.52:.4f} + coherentField * 0.048 +
                            sin(pathPhase * 0.72 + flightAge * 2.65) * 0.024 +
                            liftRandom * 0.008
                    ) * transverse;
                    float sourceAlong = clamp(
                        dot(sourceVelocity.xy, direction),
                        0.0,
                        2.8
                    );
                    float sourceSide = clamp(
                        dot(sourceVelocity.xy, perpendicular),
                        -0.65,
                        0.65
                    );
                    float inheritedAlong = sourceAlong * flightAge * memory;
                    float inheritedSide = sourceSide * flightAge * 0.045;
                    vec3 flightPoint = vec3(
                        sourcePoint.xy +
                            direction * (inheritedAlong + gust) +
                            perpendicular * (inheritedSide + crossDrift) +
                            sourceNormal.xy * normalDrift,
                        sourcePoint.z +
                            clamp(sourceVelocity.z, -1.4, 2.4) * flightAge * memory * 0.54 +
                            (0.21 + 0.16 * (0.5 + 0.5 * streamlineA)) * flightAge +
                            sourceNormal.z * normalDrift -
                            0.095 * flightAge * flightAge
                    );"""


def straight_line_score(particle: np.ndarray) -> dict[str, float]:
    alpha = particle.astype(np.float32)
    blurred = cv2.GaussianBlur(alpha, (0, 0), 2.0)
    active = blurred[blurred > 0.01]
    if active.size < 64:
        return {"longest_line_source": 0.0, "long_line_count": 0.0}
    threshold = max(0.015, float(np.percentile(active, 42)))
    edges = cv2.Canny((np.clip(blurred / max(threshold, 1e-6), 0, 1) * 255).astype(np.uint8), 60, 150)
    lines = cv2.HoughLinesP(edges, 1, np.pi / 180, 26, minLineLength=24, maxLineGap=10)
    if lines is None:
        return {"longest_line_source": 0.0, "long_line_count": 0.0}
    lengths = []
    for line in lines:
        x1, y1, x2, y2 = line.reshape(-1)[:4]
        lengths.append(float(np.hypot(x2 - x1, y2 - y1)))
    return {
        "longest_line_source": max(lengths) / 400.0,
        "long_line_count": float(sum(length >= 60.0 for length in lengths)),
    }


def measure(name: str, replacements: tuple[tuple[str, str], ...]) -> tuple[dict[str, object], CurtainRenderer]:
    renderer = CurtainRenderer(replacements)
    rows = []
    for progress in (0.40, 0.47, 0.54, 0.62, 0.70):
        layers = model_layers(renderer, progress)
        rows.append({
            "progress": progress,
            "envelope": envelope_metrics(layers.particle),
            "lines": straight_line_score(layers.particle),
            "cohesion": cohesion_metrics(layers.particle),
            "morphology": frame_metrics(layers),
            "performance": performance_proxy(renderer, progress),
        })
    return {"name": name, "frames": rows}, renderer


def main() -> None:
    variants = (
        ("baseline", ()),
        ("detach-fast", ((OLD_DETACH, detach_block(0.025, 0.055, 0.014, 0.020)),)),
        ("detach-fast-gust-125", (
            (OLD_DETACH, detach_block(0.025, 0.055, 0.014, 0.020)),
            (OLD_GUST, gust_block(1.25)),
        )),
        ("detach-fast-gust-140", (
            (OLD_DETACH, detach_block(0.025, 0.055, 0.014, 0.020)),
            (OLD_GUST, gust_block(1.40)),
        )),
    )
    results = []
    renderers = []
    for name, replacements in variants:
        result, renderer = measure(name, replacements)
        results.append(result)
        renderers.append(renderer)
    output_json = OUT / "streamline-transport-probe.json"
    output_json.write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")

    progresses = (0.40, 0.47, 0.54, 0.62, 0.70)
    sheet = Image.new("RGB", (640 * len(progresses), 474 * len(variants)), (17, 19, 24))
    draw = ImageDraw.Draw(sheet)
    for row, ((name, _), renderer) in enumerate(zip(variants, renderers)):
        for column, progress in enumerate(progresses):
            frame = renderer.render(MODEL_SCENARIO, progress).resize((640, 450), Image.Resampling.LANCZOS)
            x, y = column * 640, row * 474
            sheet.paste(frame, (x, y + 24))
            draw.text((x + 6, y + 3), f"{name} t={progress:.2f}", font=font(14), fill=(232, 234, 239))
    output_image = OUT / "streamline-transport-probe.png"
    sheet.save(output_image)
    print(json.dumps({"results": results, "outputs": [str(output_json), str(output_image)]}, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
