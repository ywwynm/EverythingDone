package com.ywwynm.everythingdone.model

import android.content.res.Resources
import android.database.MatrixCursor
import com.ywwynm.everythingdone.App
import com.ywwynm.everythingdone.Def
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, application = AppearanceParsingTest.TestApp::class)
class AppearanceParsingTest {
    class TestApp : App() {
        override fun onCreate() { ReflectionHelpers.setStaticField(App::class.java, "app", this) }
        override fun getResources(): Resources = TestResources(super.getResources())
    }
    @Suppress("DEPRECATION")
    class TestResources(base: Resources) : Resources(base.assets, base.displayMetrics, base.configuration) {
        override fun getString(id: Int) = "test-$id"
    }
    private val cardJson = """{"spanMode":1,"mediaSourceKey":"image","sources":{"image":{"fileSize":12,"presentations":{"thumbnail":{"targetAspectRatio":1.5,"crop":{"centerX":0.2,"centerY":0.7,"scale":1.2}}}}}}"""
    private val detailJson = """{"sources":{"image":{"fullSpanEnabled":true,"videoFrameMs":123,"presentations":{"fullSpan":{"targetAspectRatio":1.5,"crop":{"centerX":0.2,"centerY":0.7,"scale":1.2}}}}}}"""
    private val backgroundJson = """{"mode":"GRADIENT","color":-65536,"endColor":-16711936,"orientation":"LB_RT"}"""

    @Test fun repeatedReadsReuseParsedValues() {
        assertSame(ThingCardAppearance.fromJson(cardJson), ThingCardAppearance.fromJson(String(cardJson.toCharArray())))
        assertSame(DetailAttachmentMediaAppearance.fromJson(detailJson), DetailAttachmentMediaAppearance.fromJson(String(detailJson.toCharArray())))
        assertSame(ThingBackground.fromJson(backgroundJson), ThingBackground.fromJson(String(backgroundJson.toCharArray())))
    }

    @Test fun changedJsonIsVisibleWithoutChangingNoteIdOrUpdateTime() {
        val first = readThing(cardJson, detailJson)
        val changed = readThing(cardJson.replace("1.5", "2.0"), detailJson.replace("123", "789"))
        assertEquals(first.id, changed.id)
        assertEquals(first.updateTime, changed.updateTime)
        assertEquals(1.5, first.thingCardAppearance.sources.getValue("image").presentations.getValue("thumbnail").targetAspectRatio!!, 0.0)
        assertEquals(2.0, changed.thingCardAppearance.sources.getValue("image").presentations.getValue("thumbnail").targetAspectRatio!!, 0.0)
        assertEquals(789L, changed.detailAttachmentMediaAppearance.sources.getValue("image").videoFrameMs)
    }

    @Test fun separatelyLoadedNotesAndEditorDraftsStayIndependent() {
        val first = readThing(cardJson, detailJson)
        val second = readThing(cardJson, detailJson)
        assertNotSame(first, second)
        assertSame(first.thingCardAppearance, second.thingCardAppearance)
        first.title = "仅修改当前对象"
        first.thingCardAppearance = first.thingCardAppearance.removeSources(setOf("image"))
        first.detailAttachmentMediaAppearance = first.detailAttachmentMediaAppearance.removeSource("image")
        val third = readThing(cardJson, detailJson)
        assertEquals("原标题", second.title)
        assertEquals(second.thingCardAppearance, third.thingCardAppearance)
        assertEquals(second.detailAttachmentMediaAppearance, third.detailAttachmentMediaAppearance)
        assertEquals(setOf("image"), third.thingCardAppearance.sources.keys)
        assertEquals(setOf("image"), third.detailAttachmentMediaAppearance.sources.keys)
    }

    @Test fun parsedNestedMapsCannotContaminateOtherReaders() {
        val card = ThingCardAppearance.fromJson(cardJson)!!
        val detail = DetailAttachmentMediaAppearance.fromJson(detailJson)!!
        assertThrows(UnsupportedOperationException::class.java) { (card.sources as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (card.sources.getValue("image").presentations as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (detail.sources as MutableMap).clear() }
        assertThrows(UnsupportedOperationException::class.java) { (detail.sources.getValue("image").presentations as MutableMap).clear() }
        assertEquals(card, ThingCardAppearance.fromJson(card.toJson()))
        assertEquals(detail, DetailAttachmentMediaAppearance.fromJson(detail.toJson()))
    }

    @Test fun invalidAndLegacyValuesKeepFallbacks() {
        for (json in listOf(null, "", "{broken")) {
            repeat(2) {
                assertNull(ThingCardAppearance.fromJson(json))
                assertNull(DetailAttachmentMediaAppearance.fromJson(json))
                assertNull(ThingBackground.fromJson(json))
            }
        }
        val note = readThing("{broken", null, "{broken")
        assertEquals(Thing.THING_CARD_SPAN_FULL, note.thingCardSpanMode)
        assertEquals(Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM, note.thingCardImagePlacement)
        assertTrue(note.detailAttachmentMediaAppearance.isDefault())
        assertEquals(-65536, note.getBackground()!!.representativeColor())
        val unknownOrientation = backgroundJson.replace("LB_RT", "future")
        assertEquals(ThingBackground.Orientation.L_R, ThingBackground.fromJson(unknownOrientation)!!.orientation)
    }

    @Test fun importedSourceRemappingDoesNotChangeCachedLocalAppearance() {
        val original = ThingCardAppearance.fromJson(cardJson)!!
        val local = original.remapSources(mapOf("image" to "attachments/copied.png"))
        assertEquals(setOf("attachments/copied.png"), local.sources.keys)
        assertEquals(setOf("image"), ThingCardAppearance.fromJson(cardJson)!!.sources.keys)
        val originalDetail = DetailAttachmentMediaAppearance.fromJson(detailJson)!!
        assertEquals(setOf("attachments/copied.png"), originalDetail.remapSources(mapOf("image" to "attachments/copied.png")).sources.keys)
        assertEquals(originalDetail, DetailAttachmentMediaAppearance.fromJson(detailJson))
    }

    @Test fun backgroundAndUiReadersCanParseConcurrently() {
        val pool = Executors.newFixedThreadPool(4)
        try {
            val expected = ThingCardAppearance.fromJson(cardJson)
            val jobs = List(32) { Callable { ThingCardAppearance.fromJson(String(cardJson.toCharArray())) } }
            pool.invokeAll(jobs).forEach { assertSame(expected, it.get(3, TimeUnit.SECONDS)) }
        } finally { pool.shutdownNow() }
    }

    private fun readThing(card: String?, detail: String?, background: String? = backgroundJson): Thing {
        val columns = arrayOf("id", "type", "state", "color", "title", "content", "attachment",
            "location", "create_time", "update_time", "finish_time",
            Def.Database.COLUMN_BACKGROUND_THINGS, Def.Database.COLUMN_THING_CARD_SPAN_MODE_THINGS,
            Def.Database.COLUMN_THING_CARD_IMAGE_PLACEMENT_THINGS, Def.Database.COLUMN_THING_CARD_APPEARANCE_THINGS,
            Def.Database.COLUMN_DETAIL_ATTACHMENT_MEDIA_APPEARANCE_THINGS)
        return MatrixCursor(columns).use { cursor ->
            cursor.addRow(arrayOf(42L, Thing.NOTE, Thing.UNDERWAY, -65536, "原标题", "原正文", "", 1L,
                1L, 1L, 0L, background, Thing.THING_CARD_SPAN_FULL, Thing.THING_CARD_IMAGE_PLACEMENT_BOTTOM, card, detail))
            cursor.moveToFirst()
            Thing(cursor)
        }
    }
}
