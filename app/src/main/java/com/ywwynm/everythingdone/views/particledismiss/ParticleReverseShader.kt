package com.ywwynm.everythingdone.views.particledismiss

/** 从当前共同积分器派生准备路径，不复制或改写运动公式。 */
internal object ParticleReverseShader {
    fun integration(source: String): String {
        val marker = "    float h=min(dt,age);"
        check(source.indexOf(marker) == source.lastIndexOf(marker) && marker in source)
        // 材质与压力采样均不再读取超过寿命的颗粒，后续状态不影响任何可见帧。
        return source.replace(marker, "    if(age>=m.physical.z)return;\n$marker")
    }
}
