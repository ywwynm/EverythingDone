# 卡片与阴影在同一窗口交接

用户指出独立淡出的原生阴影仍留下完整轮廓，上一轮的像素检查只排除了卡片内容残留，没有验证阴影随材料离开。验收必须包含实际合成画面。

本轮验证的假设：

1. 带根节点 elevation 与 outline 的硬件快照可以包含原生外圈阴影；光源必须读取设备主题并复现窗口的高度修正。
2. 旧方案的阴影叠加来自透明像素在两个 Surface 间先显后撤。卡片专用 TextureView 与真实卡片在同一窗口绘制，可在一次窗口提交中切换可见性。
3. 原位完整轮廓会随着原生卡片整体隐藏而消失；外圈阴影材料与卡片使用同一释放场，不能单独留下。
4. 扩展快照不能减少卡片本体的粒子密度；需按原本体面积调整材料预算。

已在隔离测试页覆盖慢拖、短滑停住、回拖越过原点、快甩及完成后回聚。OPPO 验证通过；魅族当前 ADB offline，待连接恢复后补测。

## 已验证的缺陷

- `Outline()` 默认 alpha 为 0，而框架 `View.rebuildOutline` 在调用 provider 之前会设为 1。CardView 的 provider 只填轮廓几何；离屏快照若漏掉初始化，会正常裁圆角但完全不投影。OPPO 落盘快照的外圈 alpha 全为 0，修正后出现阴影像素。[框架实现](https://android.googlesource.com/platform/frameworks/base/+/0ffd49c/core/java/android/view/View.java)。
- TextureView 已在 OPPO 真实输入下显示粒子，原卡片 alpha 为 0，卡片内容不再残留。窗口 PixelCopy 现在包含完整合成结果，不能沿用上轮“Window 只有原生阴影”的测量解释。
- 第一版快照外圈较静止投影浅；同帧交接并不能消除快照与原生投影本身的差异，需先完成下述光源与方向校准。

## OPPO 点光源差异的实证

- 分别关闭环境光／点光源阴影测量：环境光逐像素一致，差异只存在于点光源。实色背景、捕获完整父列表都不能消除差异，排除透明合成和根卡片节点遗漏。
- 通过只读调试器核对窗口 `ThreadedRenderer`：lightRadius=2100、lightY=0、lightZ=1312.5，设备框架字节码也确认使用 450dp 加权公式；数值读取无误。
- OPPO 当前 display rotation=3。未旋转快照上缘 -15/-10/-5 px 的阴影深度约 2/4/9，原生为 18/24/33；将离屏绘制面旋转 -90°、按同一变换调整光源，再将像素转回，得到 18.18/23.92/33.49。偏差来自绘制面方向，不能再调光源常数补偿。
- 已通过只读 JNI 查询 HWUI 使用的两项系统属性，Vulkan 跟随窗口缓冲方向，OpenGL 保持原方向。API 35+ 使用 `bufferTransformHint`，旧系统取显示方向。没有硬编码 OPPO 横屏，也不修改系统属性。[HWUI 后端选择](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/hwui/Properties.cpp)、[Vulkan 绘制方向](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/libs/hwui/pipeline/skia/SkiaVulkanPipeline.cpp)。

## 最终实现与回归

- `ParticleCardSnapshot` 读取窗口投影参数，扩展并裁剪卡片快照；`ParticleHardwareSnapshot` 记录带真实 outline 和正常 elevation 的子节点，保留环境光与点光源阴影。旋转后按四方向最大投影范围留边，避免截出硬边。
- 手势和 `ThingSwipeReturn` 的卡片粒子使用各自的 TextureView，共享原 GL 线程与模型。原 View 与 TextureView 在同一次窗口提交中交接，移除层级仍延后到绘制返回。普通弹窗继续使用共享 Surface。
- 外圈阴影像素进入相同的材料与释放场，按扩展面积增加材料预算，保持卡片本体粒径；原位不再保留独立完整投影。
- 快甩中发现呈现通知晚于窗口已消费的粒子帧，补全动画从较早相位启动，导致消散倒退、阴影重新加深。`ParticleGestureProgress.beginCompletion` 在确认完成后以已提交进度作为下限；正常反向拖动、取消回弹仍可倒放。新增单测先复现失败，再修复通过。
- OPPO 320dp 卡片的 `texture18-short`（12% 停住）、`texture18-reverse`、`texture18-fast`、`texture18-fling` 均通过；宽卡片 `texture18-return` 完成后回聚通过。最终原卡片 alpha=1、translationX=0、z=21px，完成回调次数符合预期。回位交接相邻采样阴影差均为 0 灰阶；静止前后小卡片 14.769、宽卡片 26.769，数值一致。截图确认消散位置没有完整轮廓残留。
- 窗口 PixelCopy 是合成画面采样，不是逐物理帧的性能基准。TextureView 已消费首帧后才隐藏原卡片；不再用跨 Surface 的固定延时判断半透明阴影交接。
- 全量 JVM 检查 1045 项：1044 通过、1 跳过；`git diff --check` 通过。仅用不读写 DAO、偏好的 debug 虚构页面，未操作已有记事。
- 魅族 `392QBFED2226B` 多次检查及一次定向 reconnect 后仍为 offline，未获得真机数据。API 26–28 保留软件内容快照，外圈阴影仍不进入快照；正在做事的共享入口已编译检查，未操作用户的计时页面。
