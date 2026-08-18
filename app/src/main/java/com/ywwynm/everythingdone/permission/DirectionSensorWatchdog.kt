package com.ywwynm.everythingdone.permission

/**
 * 方向传感器首样本看门狗。
 *
 * 部分系统（实测 ColorOS 的「设备动作与方向」权限处于「仅开屏时不允许」档）会在应用回到
 * 前台后的约 6 秒内，于系统服务侧过滤重力、加速度、旋转矢量等方向传感器事件。应用注册成功、
 * 渲染照常，但收不到任何新样本，表现为「画面不响应设备倾斜」。
 *
 * 判据只有一个物理含义：**注册成功之后有没有拿到第一个样本**。因此它与触发路径无关——冷启动、
 * 从外部 Activity 返回、应用内跳转、权限弹窗、MediaProjection 授权页往返全部覆盖，不需要
 * 逐条枚举厂商的 launch-stage 语义。
 *
 * 应用侧读不到那个 AppOp，也读不到 `dumpsys sensorservice` 的 `has sensor access`
 * （实测该字段在拦截期间仍为 `true`），所以没有比首样本更直接的信号。
 *
 * [onSample] 由传感器线程调用，其余方法由主线程调用，故状态访问统一加锁。
 */
class DirectionSensorWatchdog {

    enum class State {
        /** 没有注册，或已注销。 */
        IDLE,

        /** 注册成功，正在等第一个样本。 */
        WAITING,

        /** 已经拿到过样本，方向数据可用。 */
        RECEIVING,

        /** 等待超时仍无样本，判定为方向数据当前不可用。 */
        STALLED
    }

    private var state = State.IDLE

    /**
     * `registerListener` 返回 true 之后调用。调用方随即按
     * [FIRST_SAMPLE_TIMEOUT_MS] 安排一次延时检查。
     *
     * 每次注册都重新起算，上一轮的结论不带过来。
     */
    @Synchronized
    fun onRegistered() {
        state = State.WAITING
    }

    /**
     * 收到方向样本。返回 true 表示这一个样本改变了结论（原本在等待或已判定不可用），
     * 调用方需要撤掉延时检查并刷新提示。
     *
     * 注销之后仍可能有在途样本抵达，那时保持 [State.IDLE]——已经不在监测中，不该被
     * 一个迟到的样本改写成"可用"。
     */
    @Synchronized
    fun onSample(): Boolean {
        if (state == State.IDLE || state == State.RECEIVING) return false
        state = State.RECEIVING
        return true
    }

    /** 延时检查到期时调用。返回 true 表示刚刚判定为不可用，调用方需要显示提示。 */
    @Synchronized
    fun onWaitExpired(): Boolean {
        if (state != State.WAITING) return false
        state = State.STALLED
        return true
    }

    /** 注销传感器时调用；提示随之隐藏。 */
    @Synchronized
    fun onUnregistered() {
        state = State.IDLE
    }

    /** 当前是否应当显示"方向数据不可用"的提示。 */
    @Synchronized
    fun isStalled(): Boolean = state == State.STALLED

    @Synchronized
    fun currentState(): State = state

    companion object {

        /**
         * 注册成功后等待第一个样本的时长。
         *
         * 实测正常首样本延迟为 16～41 毫秒（OPD2515），而受限窗口约 6 秒，1.5 秒落在两者
         * 之间且留有 37 倍以上的正常余量。传感器回调跑在独立 HandlerThread 上，不受主线程
         * 首帧繁忙影响，因此不需要更大的余量。
         */
        const val FIRST_SAMPLE_TIMEOUT_MS = 1_500L
    }
}
