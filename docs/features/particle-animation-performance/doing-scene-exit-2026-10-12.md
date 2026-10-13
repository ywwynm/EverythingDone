# Doing 整页 GL 实验的退出回归（2026-10-12）

## 用户观察

- 用户确认上一轮整页 GL 对照起滑、收尾已不闪烁，顶部动态色条持续移动。这一正面结果只覆盖其已观察的场景，不能扩展成全部生命周期正确。
- 卓易通点击底部关闭按钮：先出现调试主页背景，Doing 控件仍残留；控件消失之后仍需再等一段时间才能滑动主页。系统返回键正常。
- 原生 Android：关闭按钮和返回键均有整页闪烁。
- 本轮重点是 Activity 退出时页面 Surface、窗口转场和输入归属；保留 SurfaceView，不重新要求用户验证早已失败的自有 SurfaceControl 色块。

## 回归缺口

上一轮自动化直接启动 Doing 虚构 Activity，最后调用 finish，没有把真实调试主页放在下层，也没有检查退出过程逐帧画面与主页的首个滚动事件。本轮补充真实两层 Activity 的退出回路，分别使用关闭按钮与系统返回，并记录生命周期、Surface 撤除和返回后触摸。

## 状态

已完成本轮局部修复与 Android 回归，发布 debug `202610120511`。业务 Doing 沿用当前默认，整页 GL 仍为 debug 独立对照入口；卓易通最终画面等待用户验收。

## Android 复现与假设验证

- 补充真实调试主页承接后，三星连续两次点击关闭复现约 8 秒阻塞；魅族也复现。原版日志显示 `dequeueBuffer failed, error = -110`、`image_unavailable`，三星跳过 911 帧。
- 临时线程采样定位到 `HardwareRenderer.nPause`／`nSetStopped`，不是业务交互锁主动拒绝输入。退出前最后一次点击触发 Ripple，离屏 HardwareRenderer 可以由 RenderThread 自行续帧；原消费者把每张 Image 保留到主线程转交 GL 后才释放，主线程又可能在窗口转场等待同一 RenderThread，形成队列等待。
- 调试主页恢复时同步读取历史报告另占三星约 428.5ms、魅族约 399.0ms；这是独立的主线程耗时，不能把全部 8 秒归到报告解析。
- 按优先级验证：① 捕获消费者与渲染线程互等；② 窗口尚可见时 GL 被清空；③ 报告汇总阻塞；④ 交互锁未释放。先仅修正消费者保留上限作对照，暂不同时更改窗口退出动画。
- 平台约束参考：[HardwareRenderer](https://developer.android.com/reference/android/graphics/HardwareRenderer)、[ImageReader](https://developer.android.com/reference/android/media/ImageReader)。自有消费者必须及时归还缓冲；`acquireLatestImage()` 合并通知后返回 null 本身不是失败。

## 实施结果

- `ParticleDoingGlScene` 将 Image 跨线程保留限制为一张；多余生产帧立即在工作线程关闭，空通知不再触发失败。GPU 读取完成和本次 GL 交换完成必须都到达，才开放下一轮接收，避免涟漪续帧重复推进同一个页面交换。
- 单独改消费者时，三星／魅族关闭约 8 秒的阻塞消失；魅族仍有退出后 `SYNC_FRAME_DROPPED` 导致页面被提前清空。因此又补齐 Activity 退出：`finish` 先停止捕获和离屏生产者，仍保留屏幕内已有整页，直到整个窗口分离，不在 onDestroy 提前清空。
- 确定退出后保留的是正在退出窗口的末帧，不是滑动过程中固定背景。卡片取消／消散时的持续动态捕获保留。系统原有窗口转场未关闭、未覆盖，没有额外退出计时器。
- `ParticlePresentationDiagnosticsActivity` 在工作线程汇总最近报告；异步结果用代次与 Activity 存活状态检查，避免旧结果覆盖新选择。复制简报也使用后台汇总。
- 新增退出、捕获停止、窗口分离、资源释放、返回主页焦点和首个触摸／滚动事件，原 ZIP 导出无需 ADB 可读。临时 `[DEBUG-doing-exit]` 线程采样已从源码移除。

## 验证

完整数值见 [回归摘要](diagnostics/doing-scene-exit-2026-10-12/regression.json)。

- 三机各 3 个退出场景（关闭、系统返回、粒子进行中返回），以及各 3 个滑动场景（DRIFT 取消、200ms 快速完成、FLOW 取消），共 18 个有效场景。退出断言检查实际主页收到滚动、Doing 销毁、生产者与缓冲释放、窗口分离和无采集失败，均通过。工具连接中断及 UIAutomation 冲突的无效样本未计入。
- 三星退出后恢复焦点约 72～90ms，首次脚本滚动 258～283ms；魅族 117～137ms、295～371ms；OPPO 75～92ms、239～261ms。首次滚动时间包括脚本派发，不能解释为精确输入处理延迟。
- 与原生绘制对照，系统窗口转场本来就会暂时叠显两页；候选没有人为删除转场。三星／魅族新录像未出现数秒滞留、清空整页后又恢复的异常帧。OPPO 无录屏权限，不计入逐帧画面结论。
- 三星／魅族 4 段取消录像总计 2,891 帧，无整卡空白，前后卡面中值偏差最多 1/255；顶部色条持续移动。快速完成均回调一次且消散进度达 1，取消不触发完成；三机最终卡片 alpha=1、容器 alpha=0.84。
- 15 项相关单测通过。所有有效样本的诊断 ZIP CRC 通过。三机 371／180／72 条已有记事及其它表未变化（排除历史 HEADER 初始化）。
- 本轮滑动使用关键图像开启条件，数值不可直接与上一轮关闭图像的性能样本相减。魅族 DRIFT 的准备／渲染仍慢于三星和 OPPO，本轮没有解决这一性能问题，也未推广整页实验到实际业务页。

## 发布与设备恢复

- 已发布 [debug 202610120511](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610120511.apk)，29,834,605 字节，SHA-256 `1a58c396018dd3535094d92fd3e1c7116a05a6f190e45b3eba2f8becfcb3ca75`。公开元数据、重新下载的 APK、CRC、构建号与退出诊断字段均通过核验，无 testOnly。
- 发布使用 [本轮中文说明](debug-updates/update-20261012131103.md)。已用公开下载包在三星额外验证默认关键图像采集开启时的关闭流程，首次脚本滚动约 282.5ms，资源释放与导出成功。
- 三台均恢复测试前 APK 并校验 SHA-256 `d01ac1464b0454fa5eda806119fb8e31ba2266d3f72eb382739eb7700835c2d6`；恢复后再次核对记事和所有数据库表，无变化。见 [设备恢复与发布包检查](diagnostics/doing-scene-exit-2026-10-12/device-restore-and-smoke.json) 和 [发布核验](diagnostics/doing-scene-exit-2026-10-12/publish.json)。没有 Git 提交。
