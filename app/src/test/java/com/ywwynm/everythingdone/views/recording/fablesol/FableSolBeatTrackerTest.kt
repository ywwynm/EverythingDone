package com.ywwynm.everythingdone.views.recording.fablesol

import org.junit.Assert.assertEquals
import org.junit.Test

class FableSolBeatTrackerTest {

    @Test
    fun halfIntegerDiscretizationMatchesPythonTiesToEven() {
        assertEquals(0.0, FableSolMath.roundTiesToEven(0.5), 0.0)
        assertEquals(2.0, FableSolMath.roundTiesToEven(1.5), 0.0)
        assertEquals(2.0, FableSolMath.roundTiesToEven(2.5), 0.0)
        assertEquals(4.0, FableSolMath.roundTiesToEven(3.5), 0.0)
    }

    @Test
    fun twoThirdsTempoFamilyKeepsEvenLagAtHalfInteger() {
        // 53 秒附近的真实输入会得到 76.5 帧；Python 选择 76，而 half-up 会错误选择 77。
        assertEquals(76, FableSolMath.roundedFrameCount(76.5))
    }
}
