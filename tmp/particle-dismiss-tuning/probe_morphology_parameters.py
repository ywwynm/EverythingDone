"""对逐帧形态根因做单变量 Shader 探针。

所有替换只存在于桌面进程内；脚本不会修改 Android canonical Shader。输出用于判断
淡出、高密窗口、边界交接和弯曲位移分别影响哪一项失败指标。
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from analyze_reference_morphology import (
    MODEL_SCENARIO,
    PROGRESSES,
    FrameLayers,
    frame_metrics,
    model_layers,
    reference_layers,
    save_detail_sheet,
    save_region_sheets,
    summarise,
    to_normalized_canvas,
)
from analyze_reference_motion import frame_at, read_frames
from render_curtain_model import CurtainRenderer, OUT


LATE_FADE = (
    (
        "float baseFade = 1.0 - smoothstep(0.76, 0.99, localTime);",
        "float baseFade = 1.0 - smoothstep(0.90, 1.0, localTime);",
    ),
    (
        "float tailFade = 1.0 - smoothstep(0.84, 1.0, localTime);",
        "float tailFade = 1.0 - smoothstep(0.94, 1.0, localTime);",
    ),
)

LONG_BAND = (
    (
        "float bandRelease = 1.0 - smoothstep(0.170, 0.285, activeAge);",
        "float bandRelease = 1.0 - smoothstep(0.480, 0.760, activeAge);",
    ),
)

COHERENT_SHEET = (
    (
        "float bandWeight = bandRise * bandRelease;",
        """float bandWeight = bandRise * bandRelease;
                float coherentSheetRelease = 1.0
                        - smoothstep(0.480, 0.760, activeAge);
                float coherentSheetWeight = bandRise * coherentSheetRelease * 0.48;
                bandWeight = max(bandWeight, coherentSheetWeight);""",
    ),
)

PERSISTENT_COHORT = (
    (
        "float bandWeight = bandRise * bandRelease;",
        """float bandWeight = bandRise * bandRelease;
                float cohortCenter = 0.110
                        + curtainRandom(int(curtainId + 0.5), 0x6a09e667u) * 0.090;
                float cohortDistance = abs(delay - cohortCenter);
                float cohortProfile = 1.0
                        - smoothstep(0.014, 0.046, cohortDistance);
                float cohortRelease = 1.0
                        - smoothstep(0.620, 0.840, activeAge);
                float persistentCohort = bandRise * cohortRelease * cohortProfile;
                bandWeight = max(bandWeight, persistentCohort);""",
    ),
)

MODERATE_SHEET = (
    (
        "float bandWeight = bandRise * bandRelease;",
        """float bandWeight = bandRise * bandRelease;
                float coherentSheetRelease = 1.0
                        - smoothstep(0.500, 0.800, activeAge);
                float coherentSheetWeight = bandRise * coherentSheetRelease * 0.32;
                bandWeight = max(bandWeight, coherentSheetWeight);""",
    ),
)

HYBRID_RIDGE = (
    (
        "float bandWeight = bandRise * bandRelease;",
        """float bandWeight = bandRise * bandRelease;
                float coherentSheetRelease = 1.0
                        - smoothstep(0.500, 0.800, activeAge);
                float coherentSheetWeight = bandRise * coherentSheetRelease * 0.30;
                float cohortCenter = 0.110
                        + curtainRandom(int(curtainId + 0.5), 0x6a09e667u) * 0.090;
                float cohortProfile = 1.0 - smoothstep(
                        0.014, 0.052, abs(delay - cohortCenter)
                );
                float cohortRelease = 1.0
                        - smoothstep(0.620, 0.840, activeAge);
                float persistentCohort = bandRise * cohortRelease * cohortProfile;
                bandWeight = max(bandWeight, max(coherentSheetWeight, persistentCohort));""",
    ),
)

MULTI_COHORT = (
    (
        "float bandWeight = bandRise * bandRelease;",
        """float bandWeight = bandRise * bandRelease;
                float cohortJitter = (
                    curtainRandom(int(curtainId + 0.5), 0x6a09e667u) - 0.5
                ) * 0.028;
                float cohortA = 1.0 - smoothstep(
                        0.012, 0.043, abs(delay - (0.105 + cohortJitter))
                );
                float cohortB = 1.0 - smoothstep(
                        0.012, 0.043, abs(delay - (0.265 - cohortJitter * 0.45))
                );
                float cohortC = 1.0 - smoothstep(
                        0.012, 0.043, abs(delay - (0.425 + cohortJitter * 0.25))
                );
                float cohortProfile = max(cohortA, max(cohortB, cohortC));
                float cohortRelease = 1.0
                        - smoothstep(0.660, 0.880, activeAge);
                float bridgeSheet = bandRise * cohortRelease * 0.18;
                float persistentCohort = bandRise * cohortRelease * cohortProfile;
                bandWeight = max(
                        bandWeight, max(bridgeSheet, persistentCohort)
                );""",
    ),
)

SMOOTHER_FRONT = (
    (
        "broadWarp * 0.130 + detailWarp * 0.055",
        "broadWarp * 0.082 + detailWarp * 0.030",
    ),
    (
        "foldedWarp * 0.045",
        "foldedWarp * 0.020",
    ),
)

AGE_ORDERED_TRANSPORT = (
    (
        "vec2 forwardDrift = trajectoryDirection * uDriftPx * forwardEase",
        """float ageTransportScale = 0.62
                        + smoothstep(0.035, 0.620, activeAge) * 0.78;
                vec2 forwardDrift = trajectoryDirection * uDriftPx * forwardEase
                        * ageTransportScale""",
    ),
)

STRONGER_CURL = (
    (
        "vec2 curlDrift = -sourceNormal * curlSide * uDriftPx * 0.048",
        "vec2 curlDrift = -sourceNormal * curlSide * uDriftPx * 0.090",
    ),
)

DENSE_BAND = (
    (
        "float bandKeep = 0.48 + bandCluster * 0.18",
        "float bandKeep = 0.66 + bandCluster * 0.20",
    ),
    (
        "mix(1.03, 1.35, bandWeight)",
        "mix(1.03, 1.48, bandWeight)",
    ),
)

SPARSE_TAIL = (
    (
        "float tailKeep = 0.10 + regionLife * 0.08",
        "float tailKeep = 0.08 + regionLife * 0.06",
    ),
)

EXTRA_DENSE_BAND = (
    (
        "float bandKeep = 0.48 + bandCluster * 0.18",
        "float bandKeep = 0.74 + bandCluster * 0.20",
    ),
    (
        "mix(1.03, 1.35, bandWeight)",
        "mix(1.03, 1.60, bandWeight)",
    ),
)

EXTRA_SPARSE_TAIL = (
    (
        "float tailKeep = 0.10 + regionLife * 0.08",
        "float tailKeep = 0.06 + regionLife * 0.05",
    ),
)

SURFACE_BRIDGE = (
    (
        "float keepProbability = mix(tailKeep, bandKeep, bandWeight);",
        """float bridgeWeight = curtainHandoff(age)
                        * (1.0 - smoothstep(0.090, 0.210, activeAge));
                float keepProbability = mix(
                        tailKeep, bandKeep, max(bandWeight, bridgeWeight)
                );""",
    ),
)

STRONG_SURFACE_BRIDGE = (
    (
        "float keepProbability = mix(tailKeep, bandKeep, bandWeight);",
        """float bridgeWeight = 1.0
                        - smoothstep(0.075, 0.220, activeAge);
                bandWeight = max(bandWeight, bridgeWeight * 0.72);
                float keepProbability = mix(
                        tailKeep, bandKeep, max(bandWeight, bridgeWeight)
                );""",
    ),
)

POROUS_STILL = (
    (
        "float stillAlpha = 1.0 - curtainHandoff(age);",
        """float handoff = curtainHandoff(age);
                float poreNoise = curtainNoise(
                        basePx / max(uCellPx * 1.55, 1.0) + vec2(17.3, 83.1)
                );
                float porousHandoff = smoothstep(
                        poreNoise - 0.17, poreNoise + 0.17, handoff
                );
                float stillAlpha = 1.0 - mix(handoff, porousHandoff, 0.58);""",
    ),
)

CURVED_SHEET = (
    (
        "vec2 positionPx = basePx + earlyDrift + forwardDrift + foldDrift + curlDrift",
        """float coherentArc = (1.0 - sourceCoordinate * sourceCoordinate)
                        * (0.72 + 0.28 * sin(curtainPhase));
                vec2 coherentArcDrift = (
                    -sourceNormal * (0.050 + coherentArc * 0.052)
                            + edgeTangent * sourceCoordinate * 0.024
                ) * uDriftPx * bandWeight * structureTransport;
                vec2 positionPx = basePx + earlyDrift + forwardDrift + foldDrift + curlDrift
                        + coherentArcDrift""",
    ),
)

ARC_BEND_POSITIVE = (
    (
        "vec2 positionPx = basePx + earlyDrift + forwardDrift + foldDrift + curlDrift",
        """float curtainArcProfile = 1.0
                        - sourceCoordinate * sourceCoordinate;
                vec2 curtainArcDrift = sourceNormal * curtainArcProfile
                        * uDriftPx * 0.110 * bandWeight * structureTransport;
                vec2 positionPx = basePx + earlyDrift + forwardDrift + foldDrift + curlDrift
                        + curtainArcDrift""",
    ),
)

ARC_BEND_NEGATIVE = (
    (
        "vec2 positionPx = basePx + earlyDrift + forwardDrift + foldDrift + curlDrift",
        """float curtainArcProfile = 1.0
                        - sourceCoordinate * sourceCoordinate;
                vec2 curtainArcDrift = -sourceNormal * curtainArcProfile
                        * uDriftPx * 0.110 * bandWeight * structureTransport;
                vec2 positionPx = basePx + earlyDrift + forwardDrift + foldDrift + curlDrift
                        + curtainArcDrift""",
    ),
)


def model_rows(replacements: tuple[tuple[str, str], ...]) -> list[dict[str, object]]:
    renderer = CurtainRenderer(replacements)
    box = (
        int(renderer.origin[0]),
        int(renderer.origin[1]),
        renderer.snapshot.width,
        renderer.snapshot.height,
    )
    rows: list[dict[str, object]] = []
    blank = np.zeros((688, 688, 3), dtype=np.uint8)
    for progress in PROGRESSES:
        particle = (
            renderer.render_particle_rgba(MODEL_SCENARIO, progress)[:, :, 3]
            .astype(np.float32) / 255.0
        )
        intact = (
            renderer.render_still_rgba(MODEL_SCENARIO, progress)[:, :, 3]
            .astype(np.float32) / 255.0
        )
        layers = FrameLayers(
            ordinary=blank,
            particle=to_normalized_canvas(particle, box),
            intact=to_normalized_canvas(intact, box),
        )
        rows.append({"progress": progress, **frame_metrics(layers)})
    return rows


def save_variant_visuals(
    name: str,
    replacements: tuple[tuple[str, str], ...],
) -> None:
    baseline_analysis = json.loads((OUT / "morphology-analysis.json").read_text(encoding="utf-8"))
    frames, fps = read_frames(Path(baseline_analysis["reference"]))
    baseline = np.median(np.stack(frames[int(2.90 * fps) : int(3.16 * fps)]), axis=0).astype(np.uint8)
    background = np.median(np.stack(frames[int(7.85 * fps) : int(8.16 * fps)]), axis=0).astype(np.uint8)
    renderer = CurtainRenderer(replacements)
    for progress in (0.47, 0.54, 0.70):
        reference_time = 3.35 + progress * (8.05 - 3.35)
        reference = reference_layers(frame_at(frames, fps, reference_time), baseline, background)
        model = model_layers(renderer, progress)
        save_detail_sheet(progress, reference, model, name)


def save_variant_region_visuals(
    name: str,
    replacements: tuple[tuple[str, str], ...],
) -> None:
    baseline_analysis = json.loads((OUT / "morphology-analysis.json").read_text(encoding="utf-8"))
    frames, fps = read_frames(Path(baseline_analysis["reference"]))
    baseline = np.median(np.stack(frames[int(2.90 * fps) : int(3.16 * fps)]), axis=0).astype(np.uint8)
    background = np.median(np.stack(frames[int(7.85 * fps) : int(8.16 * fps)]), axis=0).astype(np.uint8)
    renderer = CurtainRenderer(replacements)
    layers: dict[float, tuple[FrameLayers, FrameLayers]] = {}
    for progress in (0.24, 0.32, 0.40, 0.47, 0.54, 0.62, 0.70, 0.78):
        reference_time = 3.35 + progress * (8.05 - 3.35)
        reference = reference_layers(frame_at(frames, fps, reference_time), baseline, background)
        layers[progress] = (reference, model_layers(renderer, progress))
    save_region_sheets(layers, name)


def main() -> None:
    variants = {
        "baseline": (),
        "late_fade_only": LATE_FADE,
        "long_band_only": LONG_BAND,
        "coherent_sheet_only": COHERENT_SHEET,
        "persistent_cohort_only": PERSISTENT_COHORT,
        "persistent_cohort_stronger_curl": (*PERSISTENT_COHORT, *STRONGER_CURL),
        "persistent_cohort_bridge": (*PERSISTENT_COHORT, *SURFACE_BRIDGE),
        "moderate_sheet_only": MODERATE_SHEET,
        "age_ordered_transport_only": AGE_ORDERED_TRANSPORT,
        "moderate_sheet_age_ordered": (*MODERATE_SHEET, *AGE_ORDERED_TRANSPORT),
        "hybrid_ridge_age_ordered": (*HYBRID_RIDGE, *AGE_ORDERED_TRANSPORT),
        "multi_cohort_only": MULTI_COHORT,
        "multi_cohort_smooth_front": (*MULTI_COHORT, *SMOOTHER_FRONT),
        "multi_cohort_smooth_age": (*MULTI_COHORT, *SMOOTHER_FRONT, *AGE_ORDERED_TRANSPORT),
        "multi_cohort_dense_band": (*MULTI_COHORT, *DENSE_BAND),
        "multi_cohort_dense_sparse": (*MULTI_COHORT, *DENSE_BAND, *SPARSE_TAIL),
        "porous_bridge_only": (*STRONG_SURFACE_BRIDGE, *POROUS_STILL),
        "multi_dense_porous_bridge": (
            *MULTI_COHORT, *DENSE_BAND, *SPARSE_TAIL,
            *STRONG_SURFACE_BRIDGE, *POROUS_STILL,
        ),
        "surface_bridge_only": SURFACE_BRIDGE,
        "curved_sheet_only": CURVED_SHEET,
        "late_fade_long_band": (*LATE_FADE, *LONG_BAND),
        "combined": (*PERSISTENT_COHORT, *STRONGER_CURL, *SURFACE_BRIDGE),
    }
    result: dict[str, object] = {}
    for name, replacements in variants.items():
        rows = model_rows(replacements)
        result[name] = {
            "summary": summarise(rows),
            "frames": rows,
        }
        print(name, json.dumps(result[name]["summary"], ensure_ascii=False))
    output = OUT / "morphology-probes.json"
    output.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    for name in (
        "long_band_only", "coherent_sheet_only", "persistent_cohort_only",
        "persistent_cohort_stronger_curl", "combined",
        "moderate_sheet_only", "moderate_sheet_age_ordered", "hybrid_ridge_age_ordered",
        "multi_cohort_only", "multi_cohort_smooth_front", "multi_cohort_smooth_age",
        "multi_cohort_dense_band", "multi_cohort_dense_sparse",
        "porous_bridge_only", "multi_dense_porous_bridge",
    ):
        save_variant_visuals(name, variants[name])
    print(output)


if __name__ == "__main__":
    main()
