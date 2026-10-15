package com.ywwynm.everythingdone.helpers

import org.junit.Assert.*
import org.junit.Test

class CoverGenerationStateTest {
    @Test fun `处理完最后一帧仍须等待最终文件写入`() {
        val state = CoverGenerationState.framesProcessed(75, 75)
        assertEquals(CoverGenerationState.Phase.GENERATING, state.phase)
        assertEquals(99, state.percent)
        assertEquals(100, CoverGenerationState.READY.percent)
    }

    @Test fun `短视频与跳过无法解码的帧使用实际处理位置`() {
        assertEquals(0, CoverGenerationState.framesProcessed(0, 1).percent)
        assertEquals(99, CoverGenerationState.framesProcessed(1, 1).percent)
        assertEquals(36, CoverGenerationState.framesProcessed(27, 75).percent)
        assertEquals(72, CoverGenerationState.framesProcessed(54, 75).percent)
    }

    @Test fun `无效输入不会产生负进度或提前宣告成功`() {
        for (value in listOf(-1 to 75, 75 to 0, 75 to -1)) {
            assertEquals(0, CoverGenerationState.framesProcessed(value.first, value.second).percent)
        }
        assertEquals(99, CoverGenerationState.framesProcessed(Int.MAX_VALUE, Int.MAX_VALUE).percent)
    }
}
