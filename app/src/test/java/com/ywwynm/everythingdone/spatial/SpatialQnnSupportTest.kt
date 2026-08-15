package com.ywwynm.everythingdone.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialQnnSupportTest {

    @Test
    fun `内置表覆盖已实证的主流骁龙`() {
        assertEquals("v73", SpatialQnnSupport.hardwareArch("SM8550", null))
        assertEquals("v69", SpatialQnnSupport.hardwareArch("SM8450", null))
        assertEquals("v75", SpatialQnnSupport.hardwareArch("SM8650", null))
        assertEquals("v79", SpatialQnnSupport.hardwareArch("SM8750", null))
    }

    @Test
    fun `8 Gen 5 故意不登记，留作全 arch 兜底的验证基准`() {
        // D271：登记着 OPD2515（SM8845P，硅 v85、/odm 下装的是 V81 Skel）就永远走
        // "查得到 arch"那条老路，全 arch 兜底路径便没有活体可验。拿掉之后它解析不出架构，
        // 正好走兜底——判定仍须放行（不是"确定不支持"），由全 arch 包接住。
        assertNull(SpatialQnnSupport.hardwareArch("SM8845P", null))
        assertFalse(
            SpatialQnnSupport.isArchUnserved("v81", SpatialQnnSupport.builtInServedArchs())
        )
    }

    @Test
    fun `catalog 可以随时把 8 Gen 5 登记回来省下全 arch 包的差额`() {
        // 恢复单份包（省 14.55 MB）不必发版：覆盖表补一条即可，取证结论仍然有效。
        val profiles = listOf(SpatialQnnSupport.DeviceProfile("SM8845P", "v81"))
        assertEquals("v81", SpatialQnnSupport.hardwareArch("SM8845P", profiles))
    }

    @Test
    fun `SoC 型号匹配不区分大小写`() {
        assertEquals("v73", SpatialQnnSupport.hardwareArch("sm8550", null))
    }

    @Test
    fun `未知 SoC 一律不启用而不是猜最接近的`() {
        // 裸 SM8845 手上没有这样上报的设备（实证的是 SM8845P），按 fail-closed 不猜。
        assertNull(SpatialQnnSupport.hardwareArch("SM8845", null))
        assertNull(SpatialQnnSupport.hardwareArch("MT6989", null))
        assertNull(SpatialQnnSupport.hardwareArch("", null))
    }

    @Test
    fun `catalog 表优先于内置表`() {
        val profiles = listOf(SpatialQnnSupport.DeviceProfile("SM8550", "v75"))
        assertEquals("v75", SpatialQnnSupport.hardwareArch("SM8550", profiles))
    }

    @Test
    fun `catalog 可以补充内置表没有的新 SoC`() {
        val profiles = listOf(SpatialQnnSupport.DeviceProfile("SM8845", "v81"))
        assertEquals("v81", SpatialQnnSupport.hardwareArch("SM8845", profiles))
    }

    @Test
    fun `catalog 里的非法 dsp_arch 被拒绝并回落内置表`() {
        // dsp_arch 会拼进文件名，catalog 是远端来源，必须按白名单挡住路径穿越与任意串。
        val malicious = listOf(
            SpatialQnnSupport.DeviceProfile("SM8550", "../../etc"),
            SpatialQnnSupport.DeviceProfile("SM8650", "v73/../v79"),
            SpatialQnnSupport.DeviceProfile("SM8750", "")
        )
        assertEquals("v73", SpatialQnnSupport.hardwareArch("SM8550", malicious))
        assertEquals("v75", SpatialQnnSupport.hardwareArch("SM8650", malicious))
        assertEquals("v79", SpatialQnnSupport.hardwareArch("SM8750", malicious))
    }

    @Test
    fun `catalog 为未知 SoC 提供非法 arch 时仍然不启用`() {
        val malicious = listOf(SpatialQnnSupport.DeviceProfile("SM9999", "v73;rm -rf"))
        assertNull(SpatialQnnSupport.hardwareArch("SM9999", malicious))
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
        assertNull(SpatialQnnSupport.hardwareArch("SM8150", profiles))
    }

    @Test
    fun `未知 SoC 解析不出架构但不等于确定不支持`() {
        // D267 的极性：白名单查不到只是"不知道"，不是"不能用"。真正的禁止由黑名单表达，
        // 而黑名单是 v65 v66 那个封闭集合。
        assertNull(SpatialQnnSupport.hardwareArch("SM9999", null))
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

    @Test
    fun `8+ Gen 1 判定表认得出但我们没出过货`() {
        // D270：Z Fold4（SM8475 → v69）上设置页显示 NPU 可用、开关能开、全 arch 组件也能
        // 装完，而运行组件里根本没有 V69 的 Skel，所有模型静默回落 CPU。
        val arch = SpatialQnnSupport.hardwareArch("SM8475", null)
        assertEquals("v69", arch)
        assertTrue(
            SpatialQnnSupport.isArchUnserved(arch!!, SpatialQnnSupport.builtInServedArchs())
        )
    }

    @Test
    fun `已经出过货的四档一律放行`() {
        for (arch in listOf("v73", "v75", "v79", "v81")) {
            assertFalse(
                arch,
                SpatialQnnSupport.isArchUnserved(arch, SpatialQnnSupport.builtInServedArchs())
            )
        }
    }

    @Test
    fun `问不到已发布档位时一律放行`() {
        // 空集合的含义是"无从判断"。一次拉取失败绝不能把所有设备的 NPU 判成不可用。
        assertFalse(SpatialQnnSupport.isArchUnserved("v69", emptySet()))
    }

    @Test
    fun `catalog 补发一档之后不必升级 App`() {
        // 判据从 catalog 的 qnnRuntimes 现推，补发 v69 的包 = 判定自动跟上。
        val served = setOf("v69", "v73", "v75", "v79", "v81")
        assertFalse(SpatialQnnSupport.isArchUnserved("v69", served))
    }

    @Test
    fun `非法 arch 不参与已发布判定`() {
        // 非法值另有 isValidDspArch 挡着，这里放行以免两处判据打架。
        assertFalse(SpatialQnnSupport.isArchUnserved("../evil", SpatialQnnSupport.builtInServedArchs()))
    }

    @Test
    fun `比我们出过的最高档还新的硅，落到最高的那一档`() {
        // 这正是 QNN 自己的选法（D267 实测：硅 v85、四份 Skel 同在，它挑了 V81）。
        // 判定跟着它走，两边才不会打架——拼出来的库名一定在包里，按这一档取的预编译
        // 产物也一定加载得了。**这是 OPD2515 的实际情形**：把它从表里拿掉之后靠设备
        // 自证得到 v81（/odm 下装的就是 V81 Skel），Big-LaMa 的 v81 产物照常可用。
        assertEquals(
            "v81",
            SpatialQnnSupport.resolveDspArch("v85", SpatialQnnSupport.builtInServedArchs())
        )
        assertFalse(
            SpatialQnnSupport.isArchUnserved("v85", SpatialQnnSupport.builtInServedArchs())
        )
    }

    @Test
    fun `硅档低于我们出过的所有档才算不可用`() {
        // 8+ Gen 1 的 v69：{v73,v75,v79,v81} 里没有一档落得到它上面。
        assertNull(
            SpatialQnnSupport.resolveDspArch("v69", SpatialQnnSupport.builtInServedArchs())
        )
        assertTrue(
            SpatialQnnSupport.isArchUnserved("v69", SpatialQnnSupport.builtInServedArchs())
        )
    }

    @Test
    fun `硅档正好等于某一档时取它自己而不是更低的`() {
        assertEquals(
            "v75",
            SpatialQnnSupport.resolveDspArch("v75", SpatialQnnSupport.builtInServedArchs())
        )
        // 档位之间的硅（假想的 v77）落到不超过它的最高档 v75，而不是判不可用。
        assertEquals(
            "v77",
            SpatialQnnSupport.hardwareArch("SM7777", listOf(
                SpatialQnnSupport.DeviceProfile("SM7777", "v77")
            ))
        )
        assertEquals(
            "v75",
            SpatialQnnSupport.resolveDspArch("v77", SpatialQnnSupport.builtInServedArchs())
        )
    }

    @Test
    fun `硅档未知时不判不可用，交给全 arch 包兜底`() {
        assertNull(SpatialQnnSupport.resolveDspArch(null, SpatialQnnSupport.builtInServedArchs()))
        assertFalse(
            SpatialQnnSupport.isArchUnserved(null, SpatialQnnSupport.builtInServedArchs())
        )
    }

    @Test
    fun `两种不可用标记的界面处置不同但判定相同`() {
        // D274 用户裁定：表/厂商库判出的（下载前就知道）隐藏，自探判出的（装完组件才知道）
        // 置灰。两者在"能不能用"上是同一个答案，差别只在界面。
        val served = SpatialQnnSupport.builtInServedArchs()
        assertTrue(SpatialQnnSupport.isArchUnserved(SpatialQnnSupport.TOO_OLD_ARCH_MARK, served))
        assertTrue(SpatialQnnSupport.isArchUnserved(SpatialQnnSupport.PROBE_FAILED_MARK, served))
        assertNotEquals(
            SpatialQnnSupport.TOO_OLD_ARCH_MARK, SpatialQnnSupport.PROBE_FAILED_MARK
        )
    }

    @Test
    fun `确定不可用时按组件在位分档而不是按结论来源`() {
        // 2026-08-15 审查：按来源分档在两个方向上都错位——表判隐藏但组件已装时
        // 192 MB 没有删除入口，自探失败但组件已删时文案还写着"可删除"。
        // 组件在磁盘上就必须可见可删，不论结论从哪条来源得出。
        assertEquals(
            SpatialQnnSupport.NpuVerdict.UNUSABLE_INSTALLED,
            SpatialQnnSupport.npuVerdict(
                candidate = true, unusable = true, qnnVariantInstalled = true
            )
        )
        assertEquals(
            SpatialQnnSupport.NpuVerdict.HIDDEN,
            SpatialQnnSupport.npuVerdict(
                candidate = true, unusable = true, qnnVariantInstalled = false
            )
        )
        // 可用与非骁龙两档不受组件在位影响
        assertEquals(
            SpatialQnnSupport.NpuVerdict.USABLE,
            SpatialQnnSupport.npuVerdict(
                candidate = true, unusable = false, qnnVariantInstalled = false
            )
        )
        assertEquals(
            SpatialQnnSupport.NpuVerdict.HIDDEN,
            SpatialQnnSupport.npuVerdict(
                candidate = false, unusable = false, qnnVariantInstalled = true
            )
        )
    }

    @Test
    fun `自探失败的标记不允许出现在 catalog 覆盖表里`() {
        // 那是设备本地的实测结论，不是可以下发的知识。
        assertFalse(SpatialQnnSupport.isValidProfileArch(SpatialQnnSupport.PROBE_FAILED_MARK))
        assertFalse(SpatialQnnSupport.isValidDspArch(SpatialQnnSupport.PROBE_FAILED_MARK))
        val profiles = listOf(
            SpatialQnnSupport.DeviceProfile("SM9999", SpatialQnnSupport.PROBE_FAILED_MARK)
        )
        assertNull(SpatialQnnSupport.hardwareArch("SM9999", profiles))
    }

    @Test
    fun `确定不支持的标记一律判不可用`() {
        // 三个来源都会落到这个标记上：catalog 覆盖表显式标注、设备自证扫到的最高档低于
        // v68、以及 SpatialQnnArchProbe 连续两次建不起 session（组件齐全却起不来 = 这台机
        // 确定落在最低已发布档之下，D273）。当作"没探到"处理会退回全 arch 包，
        // 让用户白下 63 MB 又用不上。
        assertTrue(
            SpatialQnnSupport.isArchUnserved(
                SpatialQnnSupport.TOO_OLD_ARCH_MARK, SpatialQnnSupport.builtInServedArchs()
            )
        )
        // 这个标记永远形不成 libQnnHtpV<arch>Skel.so
        assertFalse(SpatialQnnSupport.isValidDspArch(SpatialQnnSupport.TOO_OLD_ARCH_MARK))
        assertTrue(SpatialQnnSupport.isValidProfileArch(SpatialQnnSupport.TOO_OLD_ARCH_MARK))
    }

    @Test
    fun `已发布集合本身只含合法 arch`() {
        for (arch in SpatialQnnSupport.builtInServedArchs()) {
            assertTrue("已发布集合含非法 arch：$arch", SpatialQnnSupport.isValidDspArch(arch))
        }
    }

    @Test
    fun `内置表里目前还没有货的型号`() {
        // 守门断言：补发 v68／v69 的运行组件时这一条会红，提醒把内置集合一起改掉，
        // 否则老用户在拉到新 catalog 之前仍会被判成不可用。
        val unserved = SpatialQnnSupport.builtInProfiles()
            .filter {
                SpatialQnnSupport.isArchUnserved(
                    it.dspArch, SpatialQnnSupport.builtInServedArchs()
                )
            }
            .map { it.socModel }
        assertEquals(listOf("SM7325", "SM8350", "SM8450", "SM8475"), unserved)
    }
}
