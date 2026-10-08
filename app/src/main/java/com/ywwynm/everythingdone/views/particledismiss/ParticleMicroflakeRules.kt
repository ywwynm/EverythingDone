package com.ywwynm.everythingdone.views.particledismiss

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties

/** 素材无关的规则；生产渲染与调试建材都从同一打包资源读取。 */
internal class ParticleMicroflakeRules private constructor(
    private val values: Properties,
    val release: FloatArray,
    /** 快照相对卡片本体增加的阴影面积，只补发射网格，不改变可见粒径。 */
    val materialAreaScale: Float = 1f
) {
    /** 阴影扩大取景面积时只增加格子预算，粒径与其它场参数保持原值。 */
    fun withMaterialArea(scale: Float): ParticleMicroflakeRules {
        if (!scale.isFinite() || scale <= 1f) return this
        val adjusted = Properties().apply {
            putAll(this@ParticleMicroflakeRules.values)
            setProperty("max_cells", (1000 + (number("max_cells") - 1000) * scale).toInt().toString())
        }
        return ParticleMicroflakeRules(adjusted, release, materialAreaScale * scale)
    }

    fun decimal(key: String): Double = values.getProperty(key)?.toDouble()
        ?.also { require(it.isFinite()) { "非有限粒子参数：$key" } }
        ?: error("缺少粒子参数：$key")

    fun number(key: String): Float = values.getProperty(key)?.toFloat()
        ?.also { require(it.isFinite()) { "非有限粒子参数：$key" } }
        ?: error("缺少粒子参数：$key")

    companion object {
        fun read(stream: InputStream, releaseStream: InputStream): ParticleMicroflakeRules {
            val properties = stream.bufferedReader(Charsets.UTF_8).use { Properties().apply { load(it) } }
            val bytes = releaseStream.use { it.readBytes() }
            val count = properties.getProperty("release_width").toInt() * properties.getProperty("release_height").toInt()
            require(bytes.size == count * 4) { "共同释放资源长度不匹配" }
            val release = FloatArray(count)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(release)
            require(release.all { it.isFinite() && it >= 0f && it < 1f }) { "共同释放资源包含非法时刻" }
            return ParticleMicroflakeRules(properties, release)
        }
    }
}
