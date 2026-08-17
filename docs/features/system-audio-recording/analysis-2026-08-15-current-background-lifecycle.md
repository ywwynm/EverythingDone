# 当前录音与 FableSol 的后台生命周期

## 结论

现有录音对话框不是可靠的后台录音实现。切到其他 App 后，应用自己的采集线程通常仍会运行，但 Android 可能停止向没有麦克风前台服务的后台应用提供真实麦克风 PCM；进程也可能被系统终止。

## 当前代码行为

- `AudioRecordDialogFragment.onPause()` 只停止倾斜传感器，不停止 `AudioRecorder`。
- `AudioRecorder` 只在 Dialog dismiss 后通过单线程任务队列执行 `release()`；因此仅切换 App 时，`RecordingThread` 通常仍在读 `AudioRecord` 并向 raw 文件写入返回的数据。
- 项目没有承载录音的前台服务，Manifest 也未声明 `microphone` / `mediaProjection` 录音服务类型。
- Android 9 起，没有可见前台 UI 或相应前台服务的普通应用可能继续运行 `AudioRecord`，但收到静音。因此“线程仍活着”不能证明录音内容有效。

## 当前 FableSol 行为

- FableSol GLES 与 Canvas 路径都会在窗口不可见时停止帧循环，并在窗口重新可见时重启。
- `AudioRecorder` 与 `FableSolRealtimeAnalyzer` 不随 Fragment `onPause()` 停止，仍可能把特征批次投递给 View。
- `FableSolAnalysisBatchInbox` 当前是无上限列表。隐藏期间渲染器不 drain，返回前台后会在首帧集中处理积压；长时间后台可能造成内存增长、回前台卡顿或视觉状态跳变。
- 若 Activity/Dialog 与进程均存活，FableSol 帧循环能够重新启动，但现状不能保证无积压地恢复到当前声音。

## 新功能的架构含义

- 若正式录音要在切到其他 App 后可靠继续，录音所有权必须从 Fragment 内部线程提升到前台服务。
- “麦克风”服务需要 `microphone` 类型；“系统”需要 `mediaProjection` 类型；“麦克风+系统”需要同时声明并启动两种类型。
- 服务必须在 Activity 仍可见且授权有效时启动，之后才能在后台继续使用 while-in-use 麦克风权限。
- FableSol 在后台不应继续绘制，也不能向不可见 View 无限积压批次；恢复策略需要只呈现当前实时状态，而不是快速回放整段后台历史。
