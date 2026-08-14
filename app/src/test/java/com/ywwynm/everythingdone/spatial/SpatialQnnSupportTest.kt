package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialQnnSupportTest {

    @Test
    fun `内置表覆盖已实证的主流骁龙`() {
        assertEquals("v73", SpatialQnnSupport.resolveDspArch("SM8550", null))
        assertEquals("v69", SpatialQnnSupport.resolveDspArch("SM8450", null))
        assertEquals("v75", SpatialQnnSupport.resolveDspArch("SM8650", null))
        assertEquals("v79", SpatialQnnSupport.resolveDspArch("SM8750", null))
    }

    @Test
    fun `SoC 型号匹配不区分大小写`() {
        assertEquals("v73", SpatialQnnSupport.resolveDspArch("sm8550", null))
    }

    @Test
    fun `未知 SoC 一律不启用而不是猜最接近的`() {
        // SM8845（8 Gen 5）当前公开资料无一致口径，必须返回 null 而不是回落到 v79/v81。
        assertNull(SpatialQnnSupport.resolveDspArch("SM8845", null))
        assertNull(SpatialQnnSupport.resolveDspArch("MT6989", null))
        assertNull(SpatialQnnSupport.resolveDspArch("", null))
    }

    @Test
    fun `catalog 表优先于内置表`() {
        val profiles = listOf(SpatialQnnSupport.DeviceProfile("SM8550", "v75"))
        assertEquals("v75", SpatialQnnSupport.resolveDspArch("SM8550", profiles))
    }

    @Test
    fun `catalog 可以补充内置表没有的新 SoC`() {
        val profiles = listOf(SpatialQnnSupport.DeviceProfile("SM8845", "v81"))
        assertEquals("v81", SpatialQnnSupport.resolveDspArch("SM8845", profiles))
    }

    @Test
    fun `catalog 里的非法 dsp_arch 被拒绝并回落内置表`() {
        // dsp_arch 会拼进文件名，catalog 是远端来源，必须按白名单挡住路径穿越与任意串。
        val malicious = listOf(
            SpatialQnnSupport.DeviceProfile("SM8550", "../../etc"),
            SpatialQnnSupport.DeviceProfile("SM8650", "v73/../v79"),
            SpatialQnnSupport.DeviceProfile("SM8750", "")
        )
        assertEquals("v73", SpatialQnnSupport.resolveDspArch("SM8550", malicious))
        assertEquals("v75", SpatialQnnSupport.resolveDspArch("SM8650", malicious))
        assertEquals("v79", SpatialQnnSupport.resolveDspArch("SM8750", malicious))
    }

    @Test
    fun `catalog 为未知 SoC 提供非法 arch 时仍然不启用`() {
        val malicious = listOf(SpatialQnnSupport.DeviceProfile("SM9999", "v73;rm -rf"))
        assertNull(SpatialQnnSupport.resolveDspArch("SM9999", malicious))
    }

    @Test
    fun `dsp_arch 白名单只接受 v68 到 v89`() {
        assertTrue(SpatialQnnSupport.isValidDspArch("v68"))
        assertTrue(SpatialQnnSupport.isValidDspArch("v73"))
        assertTrue(SpatialQnnSupport.isValidDspArch("v81"))
        assertFalse(SpatialQnnSupport.isValidDspArch("v67"))
        assertFalse(SpatialQnnSupport.isValidDspArch("v90"))
        assertFalse(SpatialQnnSupport.isValidDspArch("V73"))
        assertFalse(SpatialQnnSupport.isValidDspArch("73"))
        assertFalse(SpatialQnnSupport.isValidDspArch("v73 "))
    }

    @Test
    fun `库名与 QNN 实际加载的文件名一致`() {
        // D217 日志里 QNN 找的正是这两个名字。
        assertEquals("libQnnHtpV73Skel.so", SpatialQnnSupport.skelLibraryName("v73"))
        assertEquals("libQnnHtpV73Stub.so", SpatialQnnSupport.stubLibraryName("v73"))
        assertEquals("libQnnHtpV81Skel.so", SpatialQnnSupport.skelLibraryName("v81"))
    }

    @Test(expected = IllegalStateException::class)
    fun `非法 dsp_arch 不允许生成库名`() {
        SpatialQnnSupport.skelLibraryName("../evil")
    }

    @Test
    fun `内置表本身不含非法 arch`() {
        for (profile in SpatialQnnSupport.builtInProfiles()) {
            assertTrue(
                "内置表 ${profile.socModel} 的 arch 非法：${profile.dspArch}",
                SpatialQnnSupport.isValidDspArch(profile.dspArch)
            )
        }
    }

    @Test
    fun `内置表没有重复 SoC`() {
        val models = SpatialQnnSupport.builtInProfiles().map { it.socModel.uppercase() }
        assertEquals(models.size, models.distinct().size)
    }
}
