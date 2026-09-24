package com.ywwynm.everythingdone.taptransfer.ui

import kotlin.math.max
import kotlin.math.min

/**
 * 卡片与 NFC 天线（折算到屏幕上的那一点）的相对位置。
 *
 * 颗粒要看得出是流进（或流出）天线：天线不能压在卡片里，卡片边缘到天线之间还要留出一段让颗粒
 * 流过。天线落在卡片（含这段留白）的范围里时，把卡片沿竖直方向挪到天线的上方或下方，选离默认
 * 位置近的一侧；两侧都放不下原尺寸时按 [minScale] 允许缩小；缩到下限也放不下就不挪
 * （决策 2026-09-24「卡片让开天线」）。
 *
 * 只算数，坐标系由调用方决定（同一坐标系即可）。
 */
internal object TapAntennaClearance {

    /** [top] 是挪过之后的上沿，[scale] 是在原尺寸上再乘的缩放（1 为不缩）。 */
    class Placement(val top: Float, val scale: Float)

    /**
     * @param top 卡片默认的上沿
     * @param height 卡片高度
     * @param left 卡片左沿，只用来判断天线是否在卡片的横向范围里
     * @param right 卡片右沿
     * @param clearance 卡片边缘到天线至少留出的距离
     * @param minTop 卡片上沿不能高于这里
     * @param maxBottom 卡片下沿不能低于这里
     * @param minScale 为了让开天线最多缩到原尺寸的多少；1 表示不缩
     */
    fun place(
        top: Float, height: Float, left: Float, right: Float,
        antennaX: Float, antennaY: Float, clearance: Float,
        minTop: Float, maxBottom: Float, minScale: Float = 1f
    ): Placement {
        val bottom = top + height
        val overlapsX = antennaX >= left - clearance && antennaX <= right + clearance
        val overlapsY = antennaY >= top - clearance && antennaY <= bottom + clearance
        if (!overlapsX || !overlapsY || height <= 0f) {
            return Placement(top, 1f)
        }
        // 原尺寸：上方贴着留白放、下方贴着留白放，取离默认位置近的那一侧
        val above = min(top, antennaY - clearance - height)
        val below = max(top, antennaY + clearance)
        val aboveFits = above >= minTop
        val belowFits = below + height <= maxBottom
        if (aboveFits || belowFits) {
            val useAbove = aboveFits && (!belowFits || top - above <= below - top)
            return Placement(if (useAbove) above else below, 1f)
        }
        // 两侧都放不下原尺寸：在空间大的那一侧缩小
        val roomAbove = antennaY - clearance - minTop
        val roomBelow = maxBottom - antennaY - clearance
        val room = max(roomAbove, roomBelow)
        val scale = room / height
        if (scale < minScale) {
            return Placement(top, 1f)
        }
        val scaled = height * scale
        return if (roomAbove >= roomBelow) {
            Placement(antennaY - clearance - scaled, scale)
        } else {
            Placement(antennaY + clearance, scale)
        }
    }
}
