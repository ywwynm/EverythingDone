package com.ywwynm.everythingdone.taptransfer.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class TapAntennaClearanceTest {

    private fun place(
        top: Float, height: Float, antennaX: Float, antennaY: Float,
        minTop: Float = 0f, maxBottom: Float = 3000f, minScale: Float = 1f
    ) = TapAntennaClearance.place(
        top, height, left = 400f, right = 1000f, antennaX = antennaX, antennaY = antennaY,
        clearance = 200f, minTop = minTop, maxBottom = maxBottom, minScale = minScale
    )

    @Test
    fun farAntennaLeavesCardInPlace() {
        val p = place(top = 700f, height = 800f, antennaX = 700f, antennaY = 2000f)
        assertEquals(700f, p.top, 0f)
        assertEquals(1f, p.scale, 0f)
    }

    @Test
    fun antennaBesideTheCardHorizontallyIsIgnored() {
        val p = place(top = 700f, height = 800f, antennaX = 1300f, antennaY = 1000f)
        assertEquals(700f, p.top, 0f)
    }

    @Test
    fun antennaInsideCardMovesItBelowWhenAboveHasNoRoom() {
        // 三星 SM-S9180 的实测：天线在屏幕正中，接收方的卡片默认压在它上面
        val p = place(top = 707f, height = 855f, antennaX = 720f, antennaY = 1534f, minTop = 707f, maxBottom = 2840f)
        assertEquals(1734f, p.top, 0f)
        assertEquals(1f, p.scale, 0f)
    }

    @Test
    fun antennaJustBelowCardNudgesItUpByTheShortestDistance() {
        // 发送方：天线离卡片下沿 190，不够 200 的留白，往上挪 10 即可
        val p = place(top = 832f, height = 512f, antennaX = 720f, antennaY = 1534f, minTop = 150f, maxBottom = 1990f)
        assertEquals(822f, p.top, 1e-3f)
        assertEquals(1f, p.scale, 0f)
    }

    @Test
    fun picksTheCloserSideWhenBothFit() {
        // 天线在卡片上部：往下挪 200+（天线 + 留白 - 上沿）比往上挪近
        val p = place(top = 1000f, height = 400f, antennaX = 700f, antennaY = 1050f, minTop = 0f, maxBottom = 3000f)
        // 上方：1050 - 200 - 400 = 450，挪 550；下方：1250，挪 250
        assertEquals(1250f, p.top, 0f)
    }

    @Test
    fun shrinksOnTheRoomierSideWhenNeitherFits() {
        val p = place(
            top = 300f, height = 1000f, antennaX = 700f, antennaY = 1000f,
            minTop = 100f, maxBottom = 1900f, minScale = .6f
        )
        // 上方可用 1000-200-100 = 700，下方 1900-1000-200 = 700：相等时取上方
        assertEquals(.7f, p.scale, 1e-5f)
        assertEquals(100f, p.top, 1e-3f)
    }

    @Test
    fun givesUpWhenShrinkingWouldGoBelowTheLimit() {
        val p = place(
            top = 300f, height = 1000f, antennaX = 700f, antennaY = 1000f,
            minTop = 100f, maxBottom = 1900f, minScale = .8f
        )
        assertEquals(300f, p.top, 0f)
        assertEquals(1f, p.scale, 0f)
    }
}
