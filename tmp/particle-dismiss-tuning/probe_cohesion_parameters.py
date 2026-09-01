"""单变量探针：定位多条持久粒带、右下滞留和三层过绘的贡献项。"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

from analyze_model_cohesion import (
    KEY_PROGRESSES,
    cohesion_metrics,
    directional_metrics,
    performance_proxy,
)
from analyze_reference_morphology import (
    MODEL_SCENARIO,
    REFERENCE_FINISH_S,
    REFERENCE_ONSET_S,
    frame_at,
    model_layers,
    read_frames,
    reference_layers,
    save_detail_sheet,
)
from render_curtain_model import CurtainRenderer


DISABLE_COHORTS = (
    (
        """bandWeight = max(
            bandWeight, max(bridgeSheet, persistentCohort)
    );""",
        "bandWeight = bandWeight;",
    ),
)

SINGLE_GLOBAL_FRONT = (
    (
        """bandWeight = max(
            bandWeight, max(bridgeSheet, persistentCohort)
    );""",
        """vec2 globalDirection = normalize(uSweepDir);
                vec2 globalLocalPx = basePx - uOriginPx;
                float globalTravelBase = min(0.0, snapshotPx.x * globalDirection.x)
                        + min(0.0, snapshotPx.y * globalDirection.y);
                float globalTravelSpan = abs(snapshotPx.x * globalDirection.x)
                        + abs(snapshotPx.y * globalDirection.y);
                float globalTravel = clamp(
                        (dot(globalLocalPx, globalDirection) - globalTravelBase)
                                / max(globalTravelSpan, 1.0),
                        0.0, 1.0
                );
                vec2 globalPerpendicular = vec2(-globalDirection.y, globalDirection.x);
                float globalCross = dot(
                        globalLocalPx - snapshotPx * 0.5, globalPerpendicular
                ) / max(length(snapshotPx), 1.0);
                float globalFrontField = globalTravel
                        + sin(globalCross * 5.4 + 0.35) * 0.055
                        + (curtainNoise(
                            globalLocalPx / max(uNoiseScalePx * 1.75, 1.0) + 61.7
                        ) - 0.5) * 0.045;
                float globalFrontPosition = min(uTime * 1.46, 1.03);
                float globalFrontProfile = 1.0 - smoothstep(
                        0.055, 0.165,
                        abs(globalFrontField - globalFrontPosition)
                );
                float globalFrontMerge = smoothstep(0.24, 0.46, uTime);
                float globalFrontRelease = 1.0
                        - smoothstep(0.84, 0.98, uTime);
                bandWeight = max(
                        bandWeight * (1.0 - globalFrontMerge * 0.72),
                        globalFrontProfile * globalFrontMerge * globalFrontRelease
                );""",
    ),
)

BALANCED_GLOBAL_FRONT = (
    (
        """bandWeight = max(
            bandWeight, max(bridgeSheet, persistentCohort)
    );""",
        """vec2 globalDirection = normalize(uSweepDir);
    vec2 globalLocalPx = basePx - uOriginPx;
    float globalTravelBase = min(0.0, snapshotPx.x * globalDirection.x)
            + min(0.0, snapshotPx.y * globalDirection.y);
    float globalTravelSpan = abs(snapshotPx.x * globalDirection.x)
            + abs(snapshotPx.y * globalDirection.y);
    float globalTravel = clamp(
            (dot(globalLocalPx, globalDirection) - globalTravelBase)
                    / max(globalTravelSpan, 1.0),
            0.0, 1.0
    );
    vec2 globalPerpendicular = vec2(-globalDirection.y, globalDirection.x);
    float globalCross = dot(
            globalLocalPx - snapshotPx * 0.5, globalPerpendicular
    ) / max(length(snapshotPx), 1.0);
    float globalFrontField = globalTravel
            + sin(globalCross * 4.8 + 0.35) * 0.060
            + (curtainNoise(
                globalLocalPx / max(uNoiseScalePx * 1.75, 1.0) + 61.7
            ) - 0.5) * 0.040;
    float globalFrontPosition = min(0.10 + uTime * 1.05, 0.84);
    float globalFrontProfile = 1.0 - smoothstep(
            0.060, 0.200,
            abs(globalFrontField - globalFrontPosition)
    );
    float globalFrontMerge = smoothstep(0.24, 0.46, uTime);
    float globalFrontRelease = 1.0
            - smoothstep(0.90, 0.99, uTime);
    bandWeight = max(
            bandWeight * (1.0 - globalFrontMerge * 0.78),
            globalFrontProfile * globalFrontMerge * globalFrontRelease * 0.92
    );""",
    ),
)

DIRECTIONAL_LIFETIME = (
    (
        """float lifetime = 0.800 + regionLife * 0.080
            + longTail * (0.120 + 0.035 * randomB);""",
        """vec2 lifeDirection = normalize(uSweepDir);
                vec2 lifeLocalPx = basePx - uOriginPx;
                float lifeTravelBase = min(0.0, snapshotPx.x * lifeDirection.x)
                        + min(0.0, snapshotPx.y * lifeDirection.y);
                float lifeTravelSpan = abs(snapshotPx.x * lifeDirection.x)
                        + abs(snapshotPx.y * lifeDirection.y);
                float lifeTravel = clamp(
                        (dot(lifeLocalPx, lifeDirection) - lifeTravelBase)
                                / max(lifeTravelSpan, 1.0),
                        0.0, 1.0
                );
                float lifetime = (0.800 + regionLife * 0.080
                        + longTail * (0.120 + 0.035 * randomB))
                        * mix(0.70, 1.0, smoothstep(0.05, 0.82, lifeTravel));""",
    ),
)

REDUCED_LOCAL_DEFORMATION = (
    ("* 0.112\n            * flowAccumulation", "* 0.058\n            * flowAccumulation"),
    ("edgeTangent * coherentShear * 0.18", "edgeTangent * coherentShear * 0.070"),
    ("- sourceNormal * secondaryFold * 0.055", "- sourceNormal * secondaryFold * 0.030"),
    ("* uDriftPx * 0.110 * bandWeight", "* uDriftPx * 0.045 * bandWeight"),
)

SLOWER_FRONT_POSITION = (
    (
        "float globalFrontPosition = min(0.10 + uTime * 1.05, 0.84);",
        "float globalFrontPosition = min(0.12 + uTime * 0.72, 0.64);",
    ),
)

PLATEAU_FRONT_POSITION = (
    (
        "float globalFrontPosition = min(0.10 + uTime * 1.05, 0.84);",
        """float globalFrontPosition = min(
            0.12 + smoothstep(0.08, 0.50, uTime) * 0.50
                    + max(uTime - 0.50, 0.0) * 0.12,
            0.66
    );""",
    ),
)

NARROW_FRONT_PROFILE = (
    (
        """float globalFrontProfile = 1.0 - smoothstep(
            0.060, 0.200,""",
        """float globalFrontProfile = 1.0 - smoothstep(
            0.052, 0.165,""",
    ),
)

SOFTER_CURVED_FRONT = (
    (
        "sin(globalCross * 4.8 + 0.35) * 0.060",
        """sin(globalCross * 4.0 + 0.35) * 0.095
            + globalCross * globalCross * 0.24""",
    ),
    ("0.052, 0.165,", "0.045, 0.185,"),
    (
        "globalFrontProfile * globalFrontMerge * globalFrontRelease * 0.92",
        "globalFrontProfile * globalFrontMerge * globalFrontRelease * 0.82",
    ),
    (
        """float bandKeep = 0.74 + bandCluster * 0.20
            + contentWeight * 0.10;""",
        """float bandKeep = 0.64 + bandCluster * 0.18
            + contentWeight * 0.08;""",
    ),
    ("float releasedSize = mix(0.40, 0.58, bandWeight);", "float releasedSize = mix(0.40, 0.52, bandWeight);"),
    ("* mix(1.03, 1.70, bandWeight)", "* mix(1.03, 1.55, bandWeight)"),
)

STRONGER_GLOBAL_CURVE = (
    (
        """sin(globalCross * 4.0 + 0.35) * 0.095
            + globalCross * globalCross * 0.24""",
        """sin(globalCross * 4.0 + 0.35) * 0.140
            + globalCross * globalCross * 0.42""",
    ),
)

SINGLE_ARC_FRONT = (
    (
        """sin(globalCross * 4.0 + 0.35) * 0.095
            + globalCross * globalCross * 0.24""",
        """globalCross * globalCross * 0.72
            + globalCross * 0.045""",
    ),
)

WIDER_GLOBAL_SHEET = (
    ("0.045, 0.185,", "0.060, 0.210,"),
)

LOWER_RIGHT_BOW = (
    (
        """        ) - 0.5) * 0.040;
    float globalFrontPosition""",
        """        ) - 0.5) * 0.040;
    vec2 globalUv = globalLocalPx / max(snapshotPx, vec2(1.0));
    float lowerRightBow = smoothstep(0.28, 0.92, globalUv.x)
            * smoothstep(0.28, 0.92, globalUv.y)
            * (globalUv.x - 0.32) * (globalUv.y - 0.32) * 0.55;
    globalFrontField += lowerRightBow;
    float globalFrontPosition""",
    ),
)

UNIFIED_GLOBAL_SHEET = (
    (
        """bandWeight = max(
            bandWeight * (1.0 - globalFrontMerge * 0.78),
            globalFrontProfile * globalFrontMerge * globalFrontRelease * 0.82
    );""",
        """float globalSheetWeight = globalFrontProfile
            * globalFrontMerge * globalFrontRelease * 0.82;
    bandWeight = max(
            bandWeight * (1.0 - globalFrontMerge * 0.78),
            globalSheetWeight
    );""",
    ),
    (
        """vec2 trajectoryDirection = normalize(
            direction + perpendicular * centerlineBend
    );""",
        """vec2 trajectoryDirection = normalize(
            direction + perpendicular * centerlineBend
                    * (1.0 - globalSheetWeight * 0.90)
    );""",
    ),
    (
        "* sharedSpeed * regionSpeed * speedScale * 0.82;",
        """* mix(sharedSpeed * regionSpeed, 1.0, globalSheetWeight * 0.90)
            * speedScale * 0.82;""",
    ),
    (
        """vec2 positionPx = basePx + earlyDrift + forwardDrift + foldDrift + curlDrift
            + curtainArcDrift
            + layerNormalDrift + layerProjectionDrift + flowDrift
            + edgeSideDrift + tailFanDrift + releaseDrift + microJitter;""",
        """float localStructureWeight = 1.0 - globalSheetWeight * 0.92;
    float globalSheetPhase = globalCross * 4.0 + 0.35;
    vec2 globalSheetDrift = (
        perpendicular * sin(globalSheetPhase) * 0.026
                + direction * (1.0 - cos(globalSheetPhase)) * 0.010
    ) * uDriftPx * globalSheetWeight;
    vec2 positionPx = basePx + earlyDrift + forwardDrift
            + (foldDrift + curlDrift + curtainArcDrift + flowDrift
                + edgeSideDrift + tailFanDrift + releaseDrift + microJitter)
                    * localStructureWeight
            + globalSheetDrift
            + layerNormalDrift + layerProjectionDrift;""",
    ),
)

STRONG_GLOBAL_SHEET_BEND = (
    (
        """perpendicular * sin(globalSheetPhase) * 0.026
                + direction * (1.0 - cos(globalSheetPhase)) * 0.010""",
        """perpendicular * (
            sin(globalSheetPhase) * 0.070
                    + globalCross * globalCross * 0.110
        ) + direction * (1.0 - cos(globalSheetPhase)) * 0.022""",
    ),
)

SMOOTH_GLOBAL_SHEET_BEND = (
    (
        """perpendicular * sin(globalSheetPhase) * 0.026
                + direction * (1.0 - cos(globalSheetPhase)) * 0.010""",
        """perpendicular * (
            globalCross * globalCross * 0.125 + globalCross * 0.030
        ) + direction * abs(globalCross) * 0.018""",
    ),
)

SUPPRESS_LOCAL_BRIDGE = (
    (
        """float bridgeWeight = 1.0
            - smoothstep(0.075, 0.220, activeAge);""",
        """float bridgeWeight = 1.0
            - smoothstep(0.075, 0.220, activeAge);
    // 全局卷流接管后，局部交接层只负责 still→粒子的柔化，不能继续以
    // 高占用率形成多条彼此独立的亮带。
    bridgeWeight *= 1.0 - globalFrontMerge * 0.90;""",
    ),
)

COHERENT_GLOBAL_ADVECTION = (
    (
        """float forwardEase = max(
            windPotential - birthWindPotential, 0.0
    );""",
        """float localForwardEase = max(
            windPotential - birthWindPotential, 0.0
    );
    // 同一全局锋线使用同一累计风程。若继续按各局部起点分别减去出生
    // 风程，一条源空间曲线会在输运后被重新拆成数段。
    float globalForwardEase = max(windPotential - 0.012, 0.0);
    float forwardEase = mix(
            localForwardEase, globalForwardEase,
            globalSheetWeight * globalFrontMerge * 0.88
    );""",
    ),
)

EARLY_GLOBAL_CATCHUP = (
    (
        "float globalForwardEase = max(windPotential - 0.012, 0.0);",
        """float globalCatchup = smoothstep(0.28, 0.48, uTime)
            * (1.0 - smoothstep(0.52, 0.68, uTime)) * 0.065;
    float globalForwardEase = max(windPotential - 0.012, 0.0)
            + globalCatchup;""",
    ),
)


def probe(name: str, replacements: tuple[tuple[str, str], ...]) -> dict[str, object]:
    renderer = CurtainRenderer(replacements)
    rows = []
    for progress in KEY_PROGRESSES:
        model = model_layers(renderer, progress)
        rows.append(
            {
                "progress": progress,
                "cohesion": cohesion_metrics(model.particle),
                "directional": directional_metrics(model.particle),
                "performance": performance_proxy(renderer, progress),
            }
        )
    return {"name": name, "frames": rows}


def save_visuals(
    name: str,
    replacements: tuple[tuple[str, str], ...],
    reference_path: Path,
) -> None:
    frames, fps = read_frames(reference_path)
    baseline = np.median(
        np.stack(frames[int(round(2.90 * fps)) : int(round(3.16 * fps))]), axis=0
    ).astype(np.uint8)
    background = np.median(
        np.stack(
            frames[
                int(round(7.85 * fps)) : min(len(frames), int(round(8.16 * fps)))
            ]
        ), axis=0
    ).astype(np.uint8)
    renderer = CurtainRenderer(replacements)
    for progress in KEY_PROGRESSES:
        reference_time = REFERENCE_ONSET_S + progress * (
            REFERENCE_FINISH_S - REFERENCE_ONSET_S
        )
        reference = reference_layers(
            frame_at(frames, fps, reference_time), baseline, background
        )
        save_detail_sheet(
            progress,
            reference,
            model_layers(renderer, progress),
            name,
        )


def main() -> None:
    variants = {
        "baseline": (),
        "disable_cohorts": DISABLE_COHORTS,
        "single_global_front": SINGLE_GLOBAL_FRONT,
        "single_front_directional_lifetime": (
            *SINGLE_GLOBAL_FRONT,
            *DIRECTIONAL_LIFETIME,
        ),
        "single_front_reduced_local": (
            *SINGLE_GLOBAL_FRONT,
            *REDUCED_LOCAL_DEFORMATION,
        ),
        "balanced_global_front": BALANCED_GLOBAL_FRONT,
        "balanced_front_reduced_local": (
            *BALANCED_GLOBAL_FRONT,
            *REDUCED_LOCAL_DEFORMATION,
        ),
        "balanced_slow_front": (
            *BALANCED_GLOBAL_FRONT,
            *SLOWER_FRONT_POSITION,
        ),
        "balanced_slow_reduced": (
            *BALANCED_GLOBAL_FRONT,
            *SLOWER_FRONT_POSITION,
            *REDUCED_LOCAL_DEFORMATION,
        ),
        "balanced_plateau_narrow": (
            *BALANCED_GLOBAL_FRONT,
            *PLATEAU_FRONT_POSITION,
            *NARROW_FRONT_PROFILE,
        ),
        "balanced_plateau_narrow_reduced": (
            *BALANCED_GLOBAL_FRONT,
            *PLATEAU_FRONT_POSITION,
            *NARROW_FRONT_PROFILE,
            *REDUCED_LOCAL_DEFORMATION,
        ),
        "balanced_plateau_soft_curve": (
            *BALANCED_GLOBAL_FRONT,
            *PLATEAU_FRONT_POSITION,
            *NARROW_FRONT_PROFILE,
            *SOFTER_CURVED_FRONT,
        ),
        "balanced_plateau_stronger_curve": (
            *BALANCED_GLOBAL_FRONT,
            *PLATEAU_FRONT_POSITION,
            *NARROW_FRONT_PROFILE,
            *SOFTER_CURVED_FRONT,
            *STRONGER_GLOBAL_CURVE,
        ),
        "balanced_plateau_right_bow": (
            *BALANCED_GLOBAL_FRONT,
            *PLATEAU_FRONT_POSITION,
            *NARROW_FRONT_PROFILE,
            *SOFTER_CURVED_FRONT,
            *STRONGER_GLOBAL_CURVE,
            *LOWER_RIGHT_BOW,
        ),
        "balanced_plateau_unified_sheet": (
            *BALANCED_GLOBAL_FRONT,
            *PLATEAU_FRONT_POSITION,
            *NARROW_FRONT_PROFILE,
            *SOFTER_CURVED_FRONT,
            *STRONGER_GLOBAL_CURVE,
            *LOWER_RIGHT_BOW,
            *UNIFIED_GLOBAL_SHEET,
        ),
        "balanced_plateau_unified_bent_sheet": (
            *BALANCED_GLOBAL_FRONT,
            *PLATEAU_FRONT_POSITION,
            *NARROW_FRONT_PROFILE,
            *SOFTER_CURVED_FRONT,
            *STRONGER_GLOBAL_CURVE,
            *LOWER_RIGHT_BOW,
            *UNIFIED_GLOBAL_SHEET,
            *STRONG_GLOBAL_SHEET_BEND,
        ),
        "balanced_plateau_clean_sheet": (
            *BALANCED_GLOBAL_FRONT,
            *PLATEAU_FRONT_POSITION,
            *NARROW_FRONT_PROFILE,
            *SOFTER_CURVED_FRONT,
            *STRONGER_GLOBAL_CURVE,
            *LOWER_RIGHT_BOW,
            *UNIFIED_GLOBAL_SHEET,
            *STRONG_GLOBAL_SHEET_BEND,
            *SUPPRESS_LOCAL_BRIDGE,
        ),
        "balanced_plateau_coherent_sheet": (
            *BALANCED_GLOBAL_FRONT,
            *PLATEAU_FRONT_POSITION,
            *NARROW_FRONT_PROFILE,
            *SOFTER_CURVED_FRONT,
            *STRONGER_GLOBAL_CURVE,
            *LOWER_RIGHT_BOW,
            *UNIFIED_GLOBAL_SHEET,
            *STRONG_GLOBAL_SHEET_BEND,
            *SUPPRESS_LOCAL_BRIDGE,
            *COHERENT_GLOBAL_ADVECTION,
        ),
        "balanced_plateau_single_arc_sheet": (
            *BALANCED_GLOBAL_FRONT,
            *PLATEAU_FRONT_POSITION,
            *NARROW_FRONT_PROFILE,
            *SOFTER_CURVED_FRONT,
            *SINGLE_ARC_FRONT,
            *WIDER_GLOBAL_SHEET,
            *UNIFIED_GLOBAL_SHEET,
            *SMOOTH_GLOBAL_SHEET_BEND,
            *SUPPRESS_LOCAL_BRIDGE,
            *COHERENT_GLOBAL_ADVECTION,
            *EARLY_GLOBAL_CATCHUP,
        ),
        "combined": (
            *SINGLE_GLOBAL_FRONT,
            *DIRECTIONAL_LIFETIME,
            *REDUCED_LOCAL_DEFORMATION,
        ),
    }
    print(json.dumps(
        [probe(name, replacements) for name, replacements in variants.items()],
        ensure_ascii=False,
        indent=2,
    ))


if __name__ == "__main__":
    main()
