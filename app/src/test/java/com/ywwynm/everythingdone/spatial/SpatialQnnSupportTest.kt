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
    fun `8 Gen 5 按 OPD2515 实证的型号串登记`() {
        // 上报串带 P 后缀，查表是全等匹配——写成 SM8845 匹配不上，整块 NPU 设置就不可见。
        assertEquals("v81", SpatialQnnSupport.resolveDspArch("SM8845P", null))
    }

    @Test
    fun `SoC 型号匹配不区分大小写`() {
        assertEquals("v73", SpatialQnnSupport.resolveDspArch("sm8550", null))
    }

    @Test
    fun `未知 SoC 一律不启用而不是猜最接近的`() {
        // 裸 SM8845 手上没有这样上报的设备（实证的是 SM8845P），按 fail-closed 不猜。
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

    @Test
    fun `全 arch 标记不是合法 dsp_arch`() {
        // "all" 只用来标记 catalog 里那个带全部 Skel 的包，绝不能拼进库文件名。
        assertFalse(SpatialQnnSupport.isValidDspArch(SpatialQnnSupport.ALL_ARCH))
        assertFalse(SpatialQnnSupport.isValidProfileArch(SpatialQnnSupport.ALL_ARCH))
    }

    @Test
    fun `过老标记允许进覆盖表但不是合法 dsp_arch`() {
        // catalog 要能补挡 v65 v66 的型号，但这个取值绝不能形成 libQnnHtpV<arch>Skel.so。
        assertTrue(SpatialQnnSupport.isValidProfileArch(SpatialQnnSupport.TOO_OLD_ARCH_MARK))
        assertFalse(SpatialQnnSupport.isValidDspArch(SpatialQnnSupport.TOO_OLD_ARCH_MARK))
    }

    @Test
    fun `过老标记不会被当成架构解析出来`() {
        val profiles = listOf(
            SpatialQnnSupport.DeviceProfile("SM8150", SpatialQnnSupport.TOO_OLD_ARCH_MARK)
        )
        assertNull(SpatialQnnSupport.resolveDspArch("SM8150", profiles))
    }

    @Test
    fun `未知 SoC 解析不出架构但不等于确定不支持`() {
        // D267 的极性：白名单查不到只是"不知道"，不是"不能用"。真正的禁止由黑名单表达，
        // 而黑名单是 v65 v66 那个封闭集合。
        assertNull(SpatialQnnSupport.resolveDspArch("SM9999", null))
    }

    @Test
    fun `SoC 型号白名单挡住快照分隔符`() {
        // 覆盖表快照按 `型号=arch;型号=arch` 编码，型号里混进分隔符会把一条拆成两条，
        // 从而伪造出一条本不存在的映射。写入与读回都按这个白名单过滤。
        assertTrue(SpatialQnnSupport.isValidSocModel("SM8550"))
        assertTrue(SpatialQnnSupport.isValidSocModel("SM8845P"))
        assertFalse(SpatialQnnSupport.isValidSocModel("SM8550;SM9999=v81"))
        assertFalse(SpatialQnnSupport.isValidSocModel("SM8550=v81"))
        assertFalse(SpatialQnnSupport.isValidSocModel("SM 8550"))
        assertFalse(SpatialQnnSupport.isValidSocModel(""))
        assertFalse(SpatialQnnSupport.isValidSocModel("S".repeat(33)))
    }

    @Test
    fun `内置表的型号串本身合法`() {
        for (profile in SpatialQnnSupport.builtInProfiles()) {
            assertTrue(
                "内置表型号串非法：${profile.socModel}",
                SpatialQnnSupport.isValidSocModel(profile.socModel)
            )
        }
    }
}
