"""把逐帧拟合结果转换为 Android 可直接上传的紧凑常量。"""

from __future__ import annotations

import json
from pathlib import Path


HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
INPUT = HERE / "reference-profile" / "release-profile.json"
OUTPUT = (
    ROOT
    / "app/src/main/java/com/ywwynm/everythingdone/views/particledismiss"
    / "ParticleDismissReferenceProfile.kt"
)


def wrapped(values: list[int], width: int = 10) -> str:
    rows = []
    for start in range(0, len(values), width):
        rows.append("        " + ", ".join(map(str, values[start : start + width])) + ",")
    rows[-1] = rows[-1].rstrip(",")
    return "\n".join(rows)


def main() -> None:
    profile = json.loads(INPUT.read_text(encoding="utf-8"))
    release = [
        round(float(value) * 65535)
        for row in profile["values"]
        for value in row
    ]
    source = f'''package com.ywwynm.everythingdone.views.particledismiss

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 从不透明参考动画的 61 个归一化帧反推的无颜色目标场。
 *
 * `releaseValues` 记录左上消逝时每个材料位置开始粒子化的时刻；运行时只按消逝方向
 * 镜像材料坐标，因此不会携带参考素材的图像内容。密度场覆盖控件外的尾流画布，由
 * Renderer 解码为逐帧 R8 纹理数组。
 */
internal object ParticleDismissReferenceProfile {{
    const val RELEASE_COLUMNS = {profile["columns"]}
    const val RELEASE_ROWS = {profile["rows"]}
    const val DENSITY_COLUMNS = {profile["densityColumns"]}
    const val DENSITY_ROWS = {profile["densityRows"]}
    const val DENSITY_FRAMES = {profile["densityFrames"]}

    private val releaseQ16 = intArrayOf(
{wrapped(release)}
    )

    val releaseValues: FloatArray by lazy(LazyThreadSafetyMode.PUBLICATION) {{
        FloatArray(releaseQ16.size) {{ index -> releaseQ16[index] / 65535f }}
    }}

    fun sampleRelease(
        u: Double,
        v: Double,
        directionX: Double,
        directionY: Double
    ): Double {{
        val canonicalU = if (directionX <= 0.0) u else 1.0 - u
        val canonicalV = if (directionY <= 0.0) v else 1.0 - v
        val x = min(1.0, max(0.0, canonicalU)) * (RELEASE_COLUMNS - 1)
        val y = min(1.0, max(0.0, canonicalV)) * (RELEASE_ROWS - 1)
        val x0 = floor(x).toInt().coerceIn(0, RELEASE_COLUMNS - 1)
        val y0 = floor(y).toInt().coerceIn(0, RELEASE_ROWS - 1)
        val x1 = min(x0 + 1, RELEASE_COLUMNS - 1)
        val y1 = min(y0 + 1, RELEASE_ROWS - 1)
        val tx = x - x0
        val ty = y - y0
        fun value(column: Int, row: Int): Double =
            releaseQ16[row * RELEASE_COLUMNS + column] / 65535.0
        val top = value(x0, y0) + (value(x1, y0) - value(x0, y0)) * tx
        val bottom = value(x0, y1) + (value(x1, y1) - value(x0, y1)) * tx
        return top + (bottom - top) * ty
    }}

    const val DENSITY_BASE64 = "{profile["densityBase64"]}"
}}
'''
    OUTPUT.write_text(source, encoding="utf-8", newline="\n")
    print(f"已生成 {OUTPUT}：{len(release)} 个释放样本")


if __name__ == "__main__":
    main()
