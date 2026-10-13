# 卓易通 GL 候选：可见性改善与交接闪烁反馈

用户在上一轮发布的 debug `202610110948` 之后反馈默认组合的实际画面。以下为用户观察，尚无本轮 ZIP 或逐帧录像佐证；不把它扩展成所有场景验收。

## 已确认的改善

- 默认 GL 与 Dialog 内容隐藏组合下，固定色块可见，弹窗粒子动画正常。
- 滑动记事卡片时，actionbar 等前景元素不再消失。
- 用户未提供原实现下修正后独立色块的结果，因此不能认定直接 SurfaceControl 探针也已通过。

## 新问题

- 滑动卡片、播放或结束粒子动画时，actionbar 下方阴影、卡片阴影、FAB 阴影，以及“观察标题、图标和FAB”文字会短暂闪烁。
- “独立 Surface—固定色块弹窗”打开后，橙蓝色块区域也会短暂闪烁。
- 追问后用户确认：卡片场景的阴影和提示文字是短暂变深／变粗。独立 Surface 的橙蓝区域则短暂露出深灰区域。是否全部局限于交接边界仍需日志或逐帧画面，不能把两者直接归入同一种透明度错误。

## 当前处理

对照共享 GL 前景交接、宿主帧提交及 Dialog 独立 Surface 恢复流程做静态分析，继续使用应用内验证，不用 ADB。尚未据本轮反馈修改应用或发布新包。

## 静态证据与判断

1. **前景及卡片交接存在重复绘制的时间窗口，高优先级。** `ParticleGlCardHandoff.start()` 先令 GL 静态卡片与前景可见，再在 GL 帧判定回调中隐藏原 View；`finishReturn()` 先恢复原 View，再等待宿主帧提交后撤下 GL 图像。`ParticleForegroundSurface.release()` 也先恢复 transitionAlpha，再等待提交。分步交接保护了内容连续，但不保证两份半透明内容不会同时可见。用户确认的短暂变深与这条路径吻合，尚无本轮最终屏幕逐帧记录。
2. **提示文字本身半透明。** `include_activity_header_things.xml` 的副标题使用 `app_chrome_on_surface_secondary`，浅色资源值为 `#8A000000`。约 54% 黑色重复叠加两次时等效覆盖约 79%；阴影及文字抗锯齿边缘也有部分透明像素，因此相比不透明区域更容易显出重复绘制。
3. **独立 Surface 的恢复就绪不在当前等待条件内，单独的高优先级问题。** `ParticleDismissController` 第 717 行恢复窗口／Decor，第 718 行调用 `runAfterShown(decor, ...)`，随后释放快照持有并撤下粒子层。该函数只注册普通窗口的 frame commit，没有独立 Surface 的重绘完成或缓冲恢复条件。测试色块仅在 `SurfaceHolder.Callback.surfaceCreated/Changed` 中整幅绘制，未实现 `Callback2` 重绘完成协议。深灰区域符合恢复阶段的空白／遮挡窗口，但尚不能区分缓冲、透明区域更新和层级恢复的具体原因。
4. **统一混合参数或位图预乘错误降为次要假设。** GL 位图使用预乘颜色与 `ONE / ONE_MINUS_SRC_ALPHA`，上一轮桌面 5 项实际着色器检查通过；若是固定混合错误，通常应在 GL 接管期间持续偏深，而非只短暂变化。该检查不覆盖目标 Android 上传和最终合成，仍须保留边界。

## 可证伪的后续验证与修正方向

- 对前景记录 GL 图像开始／结束、原 View 隐藏／恢复及宿主帧提交，优先检查变深是否落在两份内容同时存在的区间。改进应实现协调交接、使同一像素只贡献一次，不统一降低阴影或文字 alpha；增加固定等待会扩大重复绘制窗口。
- GL 内“静态卡片 → 粒子”的替代已在同一缓冲完成，本轮问题重点是两端“真实 View ↔ GL”，不要再次改粒子模型或切换后端。
- 对独立 Surface 补充 created／changed／destroyed／重绘请求及绘制提交事件，记录恢复前后的尺寸、可见性和缓冲状态。仅对带独立 Surface 的 Dialog 协调恢复与最终撤除；补 `Callback2` 作为正确的探针重绘协议，但不宣称加接口就必然消除闪烁。
- 普通窗口的 frame commit 不能单独作为独立 Surface 已显示的证明；也不能靠固定多等一帧保证卓易通中的最终呈现。需要应用内对照与用户实际画面验收。

## 平台依据

- [ViewTreeObserver.registerFrameCommitCallback](https://developer.android.com/reference/android/view/ViewTreeObserver#registerFrameCommitCallback(java.lang.Runnable))：回调表示内容已渲染并提交交换队列，不保证此时显示器已经显示该帧。
- [SurfaceHolder.Callback2](https://developer.android.com/reference/android/view/SurfaceHolder.Callback2)：为独立表面提供重绘及重绘完成协议，异步实现需要在绘制完成后回调。
- [SurfaceView](https://developer.android.com/reference/android/view/SurfaceView)：表面内容和窗口中用于显示其内容的透明区域有独立合成语义；不能把普通 Decor 的提交与表面呈现等同。
