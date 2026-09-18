package com.ywwynm.everythingdone.views.particledismiss

import java.io.File
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.max

class ParticleBodyReleaseTest {
    @Test fun `补位门槛晚于所有原表面交接而早于尾部结束`() {
        val root = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .first { File(it, "shared/particle-dismiss/rules.properties").isFile }
        val rules = ParticleMicroflakeRules.read(
            File(root, "shared/particle-dismiss/rules.properties").inputStream(),
            File(root, "shared/particle-dismiss/common-release.f32").inputStream())
        // 两块不同颜色，中间和四周透明，覆盖批量素材的非矩形外观。
        val pixels = IntArray(48 * 96) { i ->
            when {
                i % 48 !in 4..43 -> 0
                i / 48 in 4..32 -> 0xffd17c32.toInt()
                i / 48 in 60..89 -> 0xff65b7c0.toInt()
                else -> 0
            }
        }
        for (direction in listOf(135f, 180f, 225f)) {
            val material = ParticleMicroflakeModel.build(240f, 480f, pixels, 48, 96, direction, 129L, rules)
            val spread = rules.number("release_spread") + rules.number("white_spread") * material.bodyWeight
            val end = ParticleBodyRelease.progress(material.values, spread)
            assertTrue("必须留有粒子尾段，实际=$end", end < .98f)
            for (i in material.values.indices step 12) {
                val birth = material.values[i + 2]
                val source = birth - spread * (material.values[i + 11] - .5f)
                // shader 的 trailing 项非负，只会提前消失；不计它仍须完全越过表面淡出末端。
                assertTrue(end > birth)
                assertTrue(end >= max(.018f, source + .060f))
            }
        }
    }
}
