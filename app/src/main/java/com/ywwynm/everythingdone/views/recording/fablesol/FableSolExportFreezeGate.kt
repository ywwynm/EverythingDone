package com.ywwynm.everythingdone.views.recording.fablesol

/**
 * 导出期间实时水体三个子系统的目标状态。
 *
 * 之所以是"目标状态"而不是"动作"：帧循环、倾斜传感器、播放各有自己的恢复入口
 * （`ensureAnimating()`、`onResume() → startTiltSensor()`、用户点播放键），只发一次
 * "停"的命令必然会被其中某条路径悄悄撤销。调用方每次拿到完整目标状态再幂等地对齐，
 * 就没有"漏了哪条路径"这回事。
 */
internal data class FableSolExportFreezeTarget(
    /** 实时水体是否应当完全冻结（停帧循环 + 冻模拟 + 撤帧率投票）。 */
    val frozen: Boolean,
    /** 倾斜传感器是否应当处于注册状态。 */
    val tiltSensorRegistered: Boolean,
    /** 是否应当压住音频播放。解除压制**不等于**续播——续播由用户发起。 */
    val playbackSuppressed: Boolean
)

/**
 * 「导出进度对话框在前台就冻结实时水体」的判据。
 *
 * 规则本身一句话：**进度对话框存在期间冻结，对话框消失即解冻**——不看导出跑到哪一步。
 * 完成态、等待确认态继续冻着是有意为之：水体被对话框盖住，而后台确认不设超时，冻着反而省电。
 *
 * 抽成不依赖 Android 的纯类，是因为这里出错的方式不是"算错了"，而是"某条生命周期路径把
 * 冻结悄悄撤销了"。已知三条：
 *
 * - `onResume()` → `onVisibilityAggregated(true)` → `ensureAnimating()` 拉回帧循环；
 * - `onResume()` → `startTiltSensor()` 重新注册传感器；
 * - SurfaceView 的 surface 在窗口不可见时被销毁，重建出来的 surface 一帧未画（这条由
 *   `WaveVisualizerFableSolGl` 的按需单帧兜住，不在本类职责内）。
 *
 * 判据集中在这里、由回归测试逐条钉住，View 与 Fragment 只负责执行。
 */
internal class FableSolExportFreezeGate {

    /** 宿主 FragmentManager 上是否存在导出进度对话框。 */
    var exportDialogPresent: Boolean = false
        private set

    /** 宿主对话框自身是否处于 RESUMED。切后台、锁屏时为 false。 */
    var hostResumed: Boolean = false
        private set

    /**
     * 用户是否开启了实时设备倾斜且传感器可用。对话框打开时读一次并固定
     * （见 `FableSolTuning.liveTiltEnabled`），中途不变。
     */
    var tiltAvailable: Boolean = false
        private set

    fun setExportDialogPresent(present: Boolean): FableSolExportFreezeTarget {
        exportDialogPresent = present
        return target()
    }

    fun setHostResumed(resumed: Boolean): FableSolExportFreezeTarget {
        hostResumed = resumed
        return target()
    }

    fun setTiltAvailable(available: Boolean): FableSolExportFreezeTarget {
        tiltAvailable = available
        return target()
    }

    fun target(): FableSolExportFreezeTarget = FableSolExportFreezeTarget(
        frozen = exportDialogPresent,
        // 三个条件缺一不可。少了 exportDialogPresent 这一项，切后台再切回来的
        // onResume 就会在冻结期间把传感器重新注册上。
        tiltSensorRegistered = tiltAvailable && hostResumed && !exportDialogPresent,
        playbackSuppressed = exportDialogPresent
    )
}
