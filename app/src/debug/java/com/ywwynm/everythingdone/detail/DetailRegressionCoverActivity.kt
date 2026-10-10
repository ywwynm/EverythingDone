package com.ywwynm.everythingdone.detail

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

/** 触发真实 onPause/onResume 的调试窗口，不执行业务动作。 */
class DetailRegressionCoverActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(TextView(this).apply { text = "详情前后台恢复验证" })
    }
}
