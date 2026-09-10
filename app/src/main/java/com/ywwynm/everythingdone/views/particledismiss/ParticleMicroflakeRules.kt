package com.ywwynm.everythingdone.views.particledismiss

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Properties

/** 素材无关的规则；生产渲染与调试建材都从同一打包资源读取。 */
internal class ParticleMicroflakeRules private constructor(private val values: Properties, val release: FloatArray) {
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
