package com.ywwynm.everythingdone.views.particledismiss

import android.app.Activity
import android.content.Intent
import android.os.Bundle

/**
 * Debug 专用：把第二阶段各开关组合的着色器**各编译一遍**，只报链接结果。
 *
 * 为什么不是广播：**EMUI 会拦掉发给清单接收器的广播**——华为 MatePad Pro 13.2（Android 12）上
 * `am broadcast -n <组件>` 返回 `result=0`，系统日志里却是
 * `App .../com.ywwynm.everythingdone targets O+, restricted` +
 * `shouldPreventSendBroadcast`，接收器一次也没跑（2026-09-18 实测；魅族上同一条命令正常）。
 * 导出的 Activity 不受这条限制。
 *
 * 用法：`am start -n <包>/com.ywwynm.everythingdone.views.particledismiss.ParticleShaderMatrixActivity`，
 * 产物写到 `files/particle-unified/generated/shader-matrix.json`（应用外部私有目录）。
 */
class ParticleShaderMatrixActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(Intent(this, ParticleMicroflakeProbeReceiver::class.java)
            .putExtra("shaderMatrix", true))
        finish()
    }
}
