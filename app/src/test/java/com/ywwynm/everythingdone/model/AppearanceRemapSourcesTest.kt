package com.ywwynm.everythingdone.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * 记事文件（Thing File）导出与导入时重写外观设置键的契约。
 *
 * 两份外观设置的键是附件的 typePathName（类型 + **本机绝对路径**），附件换了设备或换了
 * 目录之后这些键就全部失效，显示比例、裁剪、卡片封面都会丢。导出时把键换成与设备无关的
 * 条目名、导入时换成接收方的新路径，靠的就是这里测的 `remapSources`。
 *
 * 只用 Map 操作，不碰 JSON，所以能在 JVM 单元测试里跑。
 */
class AppearanceRemapSourcesTest {

    private val keyA = "0/storage/emulated/0/Pictures/a.png"
    private val keyB = "0/storage/emulated/0/Pictures/b.png"
    private val keyC = "0/storage/emulated/0/Pictures/c.png"

    private val entryA = "attachments/0/0_a.png"
    private val entryC = "attachments/0/2_c.png"

    // ------------------------------------------------------------ 卡片外观

    @Test
    fun cardAppearance_remapsSourceKeysAndDropsUnmapped() {
        val appearance = ThingCardAppearance(
            sources = linkedMapOf(
                keyA to ThingCardAppearance.SourceAppearance(fileSize = 11L),
                keyB to ThingCardAppearance.SourceAppearance(fileSize = 22L)
            )
        )

        // 只给 A 提供映射：B 没有对应的附件（例如导出时被用户取消勾选），应当整条丢掉，
        // 而不是留着一个指向不存在文件的键。
        val remapped = appearance.remapSources(mapOf(keyA to entryA))

        assertEquals(setOf(entryA), remapped.sources.keys)
        assertEquals(11L, remapped.sources[entryA]?.fileSize)
    }

    @Test
    fun cardAppearance_remapsMediaSourceKey() {
        val appearance = ThingCardAppearance(
            mediaSourceKey = keyC,
            sources = linkedMapOf(keyC to ThingCardAppearance.SourceAppearance(fileSize = 33L))
        )

        val remapped = appearance.remapSources(mapOf(keyC to entryC))

        // 封面指向的那个附件也必须跟着换，否则导入后卡片选不到封面
        assertEquals(entryC, remapped.mediaSourceKey)
        assertEquals(setOf(entryC), remapped.sources.keys)
    }

    @Test
    fun cardAppearance_keepsMediaSourceNoneSentinel() {
        val appearance = ThingCardAppearance(
            mediaSourceKey = ThingCardAppearance.MEDIA_SOURCE_NONE,
            sources = linkedMapOf(keyA to ThingCardAppearance.SourceAppearance())
        )

        val remapped = appearance.remapSources(mapOf(keyA to entryA))

        // "不显示封面"是哨兵值而非路径，不能被当成找不到映射而清成 null
        assertEquals(ThingCardAppearance.MEDIA_SOURCE_NONE, remapped.mediaSourceKey)
    }

    @Test
    fun cardAppearance_clearsMediaSourceKeyWhenItsAttachmentIsGone() {
        val appearance = ThingCardAppearance(
            mediaSourceKey = keyB,
            sources = linkedMapOf(keyA to ThingCardAppearance.SourceAppearance())
        )

        val remapped = appearance.remapSources(mapOf(keyA to entryA))

        // 封面附件没跟过去，封面只能落空，绝不能保留发送方的旧路径
        assertNull(remapped.mediaSourceKey)
    }

    /**
     * 空映射表示这条记事一个附件也没带过去（例如附件文件全被删了）。留下的 key 只能是发送方的
     * 绝对路径，必须和「映射不到」一样全部丢掉，不能写进记事文件、更不能落进接收方的数据库。
     */
    @Test
    fun cardAppearance_emptyMappingDropsAllSources() {
        val appearance = ThingCardAppearance(
            mediaSourceKey = keyA,
            sources = linkedMapOf(keyA to ThingCardAppearance.SourceAppearance())
        )

        val remapped = appearance.remapSources(emptyMap())

        assertEquals(emptySet<String>(), remapped.sources.keys)
        assertNull(remapped.mediaSourceKey)
    }

    @Test
    fun cardAppearance_emptyMappingKeepsMediaSourceNoneSentinel() {
        val appearance = ThingCardAppearance(mediaSourceKey = ThingCardAppearance.MEDIA_SOURCE_NONE)

        val remapped = appearance.remapSources(emptyMap())

        assertEquals(ThingCardAppearance.MEDIA_SOURCE_NONE, remapped.mediaSourceKey)
    }

    // ------------------------------------------------------------ 详情页附件外观

    @Test
    fun detailAppearance_remapsSourceKeysAndDropsUnmapped() {
        val appearance = DetailAttachmentMediaAppearance(
            sources = linkedMapOf(
                keyA to DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true),
                keyB to DetailAttachmentMediaAppearance.SourceAppearance(fileSize = 22L)
            )
        )

        val remapped = appearance.remapSources(mapOf(keyA to entryA))

        assertEquals(setOf(entryA), remapped.sources.keys)
        assertEquals(true, remapped.sources[entryA]?.fullSpanEnabled)
    }

    @Test
    fun detailAppearance_emptyMappingDropsAllSources() {
        val appearance = DetailAttachmentMediaAppearance(
            sources = linkedMapOf(
                keyA to DetailAttachmentMediaAppearance.SourceAppearance(fullSpanEnabled = true)
            )
        )

        assertEquals(emptySet<String>(), appearance.remapSources(emptyMap()).sources.keys)
    }

    @Test
    fun detailAppearance_withoutSourcesIsReturnedAsIs() {
        val appearance = DetailAttachmentMediaAppearance.default()

        assertSame(appearance, appearance.remapSources(mapOf(keyA to entryA)))
    }

    /**
     * 导出换一次、导入再换一次，两次之后外观必须仍然挂在同一个附件上。
     * 这条模拟的是真实链路：本机路径 → 文件内的条目名 → 接收方的新路径。
     */
    @Test
    fun detailAppearance_survivesExportThenImportRoundTrip() {
        val importedA = "0/storage/emulated/0/Android/data/pkg/files/images/import_1_0.png"
        val importedC = "0/storage/emulated/0/Android/data/pkg/files/images/import_1_2.png"

        val original = DetailAttachmentMediaAppearance(
            sources = linkedMapOf(
                keyA to DetailAttachmentMediaAppearance.SourceAppearance(fileSize = 1L),
                keyC to DetailAttachmentMediaAppearance.SourceAppearance(fileSize = 3L)
            )
        )

        val exported = original.remapSources(mapOf(keyA to entryA, keyC to entryC))
        val imported = exported.remapSources(mapOf(entryA to importedA, entryC to importedC))

        assertEquals(setOf(importedA, importedC), imported.sources.keys)
        assertEquals(1L, imported.sources[importedA]?.fileSize)
        assertEquals(3L, imported.sources[importedC]?.fileSize)
    }

    /**
     * 中间那个附件没有外观设置时，映射不能把相邻条目的外观挪过去（off-by-one）。
     */
    @Test
    fun detailAppearance_doesNotShiftOntoTheMiddleAttachment() {
        val entryB = "attachments/0/1_b.png"
        val importedA = "images/import_0.png"
        val importedB = "images/import_1.png"
        val importedC = "images/import_2.png"

        val exported = DetailAttachmentMediaAppearance(
            sources = linkedMapOf(
                entryA to DetailAttachmentMediaAppearance.SourceAppearance(fileSize = 1L),
                entryC to DetailAttachmentMediaAppearance.SourceAppearance(fileSize = 3L)
            )
        )

        val imported = exported.remapSources(
            mapOf(entryA to importedA, entryB to importedB, entryC to importedC)
        )

        assertEquals(setOf(importedA, importedC), imported.sources.keys)
        assertNull(imported.sources[importedB])
    }
}
