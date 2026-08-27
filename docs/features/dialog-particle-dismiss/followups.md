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
- **第四十二轮真机视觉与性能验收**（2026-08-27）：安装调试版本 `202608270805` 后，
  用真实 Dialog 分别测试向上、向下、向左消失，重点检查起点是否“多数但非全部”位于
  飞行反向侧、五个局部锋线是否在约 100ms 内出现、未触及区域是否保持完整，以及浓带
  尺寸、亮度、尾流、内容色强调和 60fps 稳定性。按仓库规则本轮未获授权使用 ADB，
  暂未执行。
- **屏摄参考的材质偏差**：HarmonyOS 参考画面同时包含多张半透明通知卡及屏摄曝光光晕，
  桌面单 Dialog 离屏结果只能验证结构，不能直接等同比较总体粒子数量与亮度；后续材质
  微调应以同一真机、同一 Dialog 的逐帧录屏为准，避免按屏摄光晕继续堆亮。
