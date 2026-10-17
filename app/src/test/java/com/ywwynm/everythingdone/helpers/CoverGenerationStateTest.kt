package com.ywwynm.everythingdone.helpers

import org.junit.Assert.*
import org.junit.Test

class CoverGenerationStateTest {
    @Test fun `重调度不复用上一轮百分比或集中编码状态`() {
        assertEquals(CoverGenerationState.PREPARING,
            CoverGenerationState.running(9, 8, "GENERATING", 98))
        assertEquals(CoverGenerationState.PREPARING,
            CoverGenerationState.running(2, 1, "FINALIZING", 0))
        assertEquals(CoverGenerationState.PREPARING,
            CoverGenerationState.running(1, -1, null, 99))
        assertEquals(CoverGenerationState.WAITING,
            CoverGenerationState.running(9, 9, "WAITING", 0))
        assertEquals(CoverGenerationState.framesProcessed(27, 75),
            CoverGenerationState.running(9, 9, "GENERATING", 36))
        assertEquals(CoverGenerationState.FINALIZING,
            CoverGenerationState.running(9, 9, "FINALIZING", 0))
    }
    @Test fun `处理完最后一帧不把尚未执行的集中编码显示为百分之九十九`() {
        val state = CoverGenerationState.framesProcessed(75, 75)
        assertEquals(CoverGenerationState.FINALIZING, state)
        assertEquals(100, CoverGenerationState.READY.percent)
    }

    @Test fun `短视频与跳过无法解码的帧使用实际处理位置`() {
        assertEquals(0, CoverGenerationState.framesProcessed(0, 1).percent)
        assertEquals(CoverGenerationState.FINALIZING, CoverGenerationState.framesProcessed(1, 1))
        assertEquals(36, CoverGenerationState.framesProcessed(27, 75).percent)
        assertEquals(72, CoverGenerationState.framesProcessed(54, 75).percent)
    }

    @Test fun `无效输入不会产生负进度或提前宣告成功`() {
        for (value in listOf(-1 to 75, 75 to 0, 75 to -1)) {
            assertEquals(0, CoverGenerationState.framesProcessed(value.first, value.second).percent)
        }
        assertEquals(0, CoverGenerationState.framesProcessed(Int.MAX_VALUE, Int.MAX_VALUE).percent)
    }
}
