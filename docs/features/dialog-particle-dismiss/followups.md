# dialog-particle-dismiss 待办

- **PixelCopy 抓取路径**（2026-08-26 记）：含 SurfaceView/TextureView 的
  Dialog（AudioPlayDialogFragment、AudioRecordDialogFragment、
  FableSolTuningDialogFragment、CameraColorSamplingDialogFragment 等）目前由
  ParticleDismissController.containsLiveSurface 检测后跳过动画走普通退出。
  minSdk 26 支持 `PixelCopy.request(window, ...)` 整窗抓取（含 GL 层）；需要
  处理其异步回调与 dismiss 的时序（在 window 存活时发起、1–2 帧内回调，或
  这几个 Dialog 提前维护最近快照），失败降级路径已有。
- **动画期间设备旋转的快照方向**（低优先级）：Activity 正在旋转时已跳过
  动画；极端时序（动画播放中途旋转）overlay 随 DecorView 重建消失，行为
  正确但无动画收尾，可接受。
