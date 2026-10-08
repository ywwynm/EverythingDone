package com.ywwynm.everythingdone.views.particledismiss

/** 从当前共同积分器派生准备路径，不复制或改写运动公式。 */
internal object ParticleReverseShader {
    fun integration(source: String, withHistory: Boolean = false, batchSteps: Boolean = false): String {
        val marker = "    float h=min(dt,age);"
        check(source.indexOf(marker) == source.lastIndexOf(marker) && marker in source)
        // 材质与压力采样均不再读取超过寿命的颗粒，后续状态不影响任何可见帧。
        var integrated = source.replace(marker, "    if(age>=m.physical.z)return;\n$marker")
        if (batchSteps) {
            // 压力更新之间各粒子相互独立；合并分发，仍逐次执行原来的 240 Hz 公式。
            integrated = integrated.replace("void main(){", "void integrate_tick(float time){") + """

uniform int integration_first,integration_count;
void main(){
    for(int tick=0;tick<integration_count;tick++)
        integrate_tick(float(integration_first+tick)/240.);
}
"""
        }
        if (!withHistory) return integrated
        val mainMarker = "void main(){"
        check(integrated.indexOf(mainMarker) == integrated.lastIndexOf(mainMarker) && mainMarker in integrated)
        // 原 main 改名后照原样调用；它内部的提前 return 只结束积分，历史照写。
        // 写入的五个分量、下标与判定和 history.comp 的保存方向逐字相同，只是省掉
        // 一次 dispatch 与一次整个状态缓冲的往返（恢复方向仍走 history.comp）。
        return integrated.replace(mainMarker, "void integrate(){") + HISTORY_TAIL
    }

    /** 在 `#version` 之后插入编译期宏；GLSL ES 不允许任何指令出现在 `#version` 之前。 */
    fun withDefines(source: String, defines: String): String {
        if (defines.isEmpty()) return source
        val end = source.indexOf('\n') + 1
        check(end > 0 && source.startsWith("#version")) { "着色器首行必须是 #version" }
        return source.substring(0, end) + defines + source.substring(end)
    }

    private const val HISTORY_TAIL = """

layout(std430,binding=4) readonly buffer Ranges {ivec4 ranges[];};
layout(std430,binding=5) buffer History {float history[];};
uniform int history_frame;
void main(){
    integrate();
    if(history_frame<0)return;
    uint i=gl_GlobalInvocationID.x;
    if(i>=uint(count))return;
    ivec4 r=ranges[i];
    if(history_frame<r.x||history_frame>r.y)return;
    int h=(r.z+history_frame-r.x)*5;
    State s=state[i];
    history[h]=s.pos.x;history[h+1]=s.pos.y;history[h+2]=s.pos.z;
    history[h+3]=s.vel.x;history[h+4]=s.vel.y;
}
"""
}
