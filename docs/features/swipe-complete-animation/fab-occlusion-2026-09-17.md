# FAB 遮挡连续性修复

## 目标与反馈回路

- 用户确认 FAB 在起滑、消散、回弹及回聚全过程保持在卡片、阴影和粒子上方，继续使用 SurfaceView。
- 仅操作魅族 `392QBFED2226B` 的虚构列表。页面加入真实 FAB 控件，不绑定记事业务；不改设备偏好和现有记事。
- Window PixelCopy 不含独立 Surface，不能验证遮挡。采用系统录屏的最终合成画面，逐帧比较 FAB 内圈与静止参考，同时检查按钮边缘、阴影及恢复状态。

## 定位过程

- `fab-baseline-cancel-01` 复现完整卡片快照、粒子两处覆盖。FAB 内圈平均 RGB 误差最高 85.49，186 帧中 179 帧超过 5；保持段截图误差 30.47。
- 初版自有 FAB 图层挂在窗口根下、设置最大 layer，仍被更上方的粒子 Surface 覆盖。`fab-surface-cancel-01` 最大误差 85.80，198 帧中 75 帧超过 5。保持段刚好尚未交接，单张截图误差为零，不能据此验收。
- 将自有 FAB 图层改挂到共享 SurfaceView 的 `surfaceControl` 下。这是该公开接口明确支持的用法，不改 SurfaceView 自身属性，不使用隐藏的 `setRelativeLayer`。
- `fab-surface-cancel-02` 实际完成 FAB 图层进入、退出：192 个录屏帧最大内圈误差 3.53，没有超过 5 的帧；静止 FAB 只提交 1 个硬件缓冲。尚需移动、缩放、回聚与兼容分支回归。
- `fab-surface-motion-01` 暴露动态停顿：真实 View 属性正常变化，但硬件层仅渲染 4 帧后停住。首尾交接必须与窗口同步，接管后的单独 FAB 缓冲则无需等待没有可见内容变化的窗口帧。将更新事务改为直接提交后，`fab-surface-motion-02` 连续渲染 34 帧，旧缓冲释放回调持续到达；静止后停止重绘。
- `fab-final-short-01`、`fab-final-return-01` 与 `fab-final-texture-02` 的录屏遮挡断言通过，按钮内圈最大误差分别 1.97、3.52、3.53。`fab-final-motion-03` 真实合成画面的垂直移动 224.33px，24 次绘制，最终属性恢复。
- 实际点击 `fab-final-input-01/02` 暴露另一问题：系统 RippleDrawable 在第二个硬件 renderer 内自发出帧，日志出现无 animation handle 的警告和 4 秒 dequeueBuffer 超时，不能把已收到点击当作验证通过。改为真实 FAB 向可复用的小位图绘制内容，原生阴影继续由 RenderNode / HWUI 生成，隔开波纹的 RenderThread 动画；粒子播放仍走共享 SurfaceView。正在回归此修正。
- 曾尝试把 release fence 交给 `Image.setFence` 再关闭，动态测试发生提前回退；恢复独立工作线程等待 fence，并限制未归还缓冲数量后通过。不能将未核验的 Image 子类实现当作可用的异步归还保证。

## 实现约束

- FAB 留在原 View 树，保留输入、布局、业务状态和动画；动画期间通过 `transitionAlpha` 交接绘制。
- 小型图层记录真实按钮内容及原生阴影，ImageReader 的硬件缓冲直接交给 SurfaceControl，静止复用，变化时重绘。按钮内容的 CPU 绘制范围仅为 FAB 本身；没有 GPU 像素读回，也不将粒子纹理放回窗口合成。
- 缓冲复用遵循 [SurfaceControl.Transaction 文档](https://developer.android.com/reference/android/view/SurfaceControl.Transaction)：等待 release callback 携带的 fence 后再归还 ImageReader；分配包含合成器所需的 usage。
- FAB 与卡片第一帧合入同一窗口事务，最后一个卡片会话释放后同步恢复真实按钮。低版本 Texture 分支放在 FAB 的父容器内、保持原生前后关系。
- 开发验证数据与最终发布包的验收分开记录，见下文。

## 最终实现回归

- `fab-ripple-short-01`：265 个合成视频帧，按钮内圈最大 RGB 误差 3.504，无超过 5 的帧；静止 FAB 仅绘制 1 次。此前记录的 FAB 下侧阴影区域在原生 / 临时层截图之间逐像素相同。
- `fab-ripple-motion-01`：合成画面垂直移动 224.39px，移动、缩放及透明度恢复；91 次绘制后停止更新。
- `fab-ripple-input-02`：实际点击恰好一次，点击时 `transitionAlpha=0`，证明原控件在绘制接管期间仍接收输入。消散 25 帧、回聚 34 帧，两段末帧进度均为 1；无波纹 animation handle 警告及 dequeueBuffer 超时。
- `fab-ripple-tap-01`：卡片点按只预热，不出现粒子，原阴影采样无变化。`fab-final-texture-02`：兼容路径完成、回聚，FAB 内圈最大误差 3.521，阴影交接差为 0。
- `fab-final-perf-motion-01`：关闭录屏和 PixelCopy 的单次样本，FAB 更新 101 次；窗口帧耗时中位数 5.04ms、P95 22.12ms、最大 49.79ms。只能用于排除本轮复现的秒级阻塞，不据此宣称优于旧后端或证明长期功耗。
- 自动检查脚本 `check_fab_recording.py` 对旧版 `fab-baseline-cancel-01` 明确失败（最大误差 77.078，117 帧超过 5），对修正后录屏通过。阈值适用于该虚构页面及 32 Mbps 录屏，并非通用画质度量。
- 完整 JVM 检查 1044 通过、1 跳过；临时 `[DEBUG-fab-layer]` 日志已移除。
- `fab-final-exit-04` 在手势 ACTION_UP 后、FAB 仍由临时层接管且有 1 个活跃 Overlay 时退出虚构页面；随后 `ParticleFabForeground` / `ParticleCardHandoff` 均无残留。此前单纯在脚本完成后 BACK 已错过活跃阶段，不作为中途退出证据。

## 发布核验

- debug `202609170402` 已发布并覆盖安装到魅族。本地发布产物、远端完整 APK 与设备安装包 SHA-256 一致：`38eac7bae9bd84839727400e92e452801c54b12eec31bd4593de7bc911440f9a`，26,285,004 字节。
- 发布包 `fab-published-short-01`：179 个录屏帧最大内圈误差 3.505，超过 5 的帧数为 0，FAB 仅渲染 1 帧。
- 发布包 `fab-published-input-01`：原控件在接管期间收到一次真实点击；消散 25 帧、回聚 34 帧，两段末帧进度均为 1，完成回调一次。卡片 alpha=1、translationX=0、z=28px，阴影恢复原值；FAB 属性恢复。
- 发布包无新增波纹 animation handle 警告或 dequeueBuffer 超时，crash 缓冲为空。虚构页面已退出，自有卡片与 FAB 图层均无残留。未改现有记事或偏好，未创建 Git 提交。
