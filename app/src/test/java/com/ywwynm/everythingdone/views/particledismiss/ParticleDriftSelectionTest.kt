package com.ywwynm.everythingdone.views.particledismiss

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** 连续调用正式模型选择器，覆盖相同方向和出现／消散交替时的参考来源。 */
class ParticleDriftSelectionTest {
    private val sequence = ParticleDriftSequence()
    private val styles = listOf("ironman", "kobe", "thanos", "reference-0926-3").map { name ->
        ParticleDriftStyles.Style(name, 135f, 28000f, 400, 400, 500f, 500f,
            floatArrayOf(.1f, .8f, .6f, .9f), 2, 2, .5f, .5f, 1.15f)
    }

    private fun family(model: ParticleDriftModel) =
        model.styleName?.substringBefore(ParticleDriftStyles.VARIANT_SUFFIX)

    private fun prepare(seed: Int, reverse: Boolean) = sequence.prepare(reverse) { excluded ->
        ParticleDriftModel.random(400, 400, -1f, -1f, 1f, ParticleDriftModel.seededRandom(seed), styles,
            excludedReferences = excluded)
    }

    private fun next(seed: Int, reverse: Boolean): ParticleDriftModel = prepare(seed, reverse).start()

    @Test fun `同方向连续播放不重复参考来源`() {
        for (reverse in listOf(false, true)) {
            var previous: String? = null
            repeat(256) { seed ->
                val current = family(next(seed, reverse))
                if (current != null) assertNotEquals("reverse=$reverse seed=$seed", previous, current)
                previous = current
            }
        }
    }

    @Test fun `开关交替时连续两次出现和两次消散也不重复`() {
        val previous = arrayOfNulls<String>(2)
        var last: String? = null
        repeat(512) { seed ->
            val lane = seed % 2
            val current = family(next(seed, lane == 0))
            if (current != null) {
                assertNotEquals("与上一段重复 seed=$seed", last, current)
                assertNotEquals("与同类上一段重复 seed=$seed", previous[lane], current)
            }
            previous[lane] = current
            last = current
        }
    }

    @Test fun `灭霸不进入普通随机候选`() {
        repeat(256) { seed ->
            assertFalse(prepare(seed, false).model.styleName?.startsWith("thanos") == true)
        }
    }

    @Test fun `尚未播放的预热不会改变记录而提前准备的候选开播时会重新检查`() {
        // 两个候选在任一动画播放前已准备好，开播顺序决定谁应避开谁。
        val seed = (0 until 256).first { family(prepare(it, false).model) != null }
        val first = prepare(seed, false)
        val second = prepare(seed, false)
        val original = first.model
        repeat(20) { prepare(it, false) } // 都被取消，未调用 start。
        assertSame(original, first.start())
        assertNotEquals(family(first.model), family(second.start()))
        assertSame(first.model, first.start()) // 回退、重复绘制不能再次抽取。
        assertSame(second.model, second.start())
    }
}
