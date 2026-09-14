# 实现决策

## 2026-09-14 光带起点角、内容放大承接与涟漪圆心

- **起点角而非翻转。** `ShiningBorder` 新增 `setStartCorner`，`START_BOTTOM_LEFT` 为默认值（历史行为），`START_BOTTOM_RIGHT` 走新写的 `addRoundRectCWFromBottomRight`：底边向左 → 左边向上 → 顶边向右 → 右边向下，回到右下角。旋转方向与 `addRoundRectCW` 相同，只换起点，不再使用 `scaleX = -1`。新函数按绝对坐标写，段长直接给出（不沿用 `-rh + r` 那种靠 `arcTo` 隐式补线凑出来的写法），r = 0 与 r > 0 都正确。
- **翻转为什么不该用。** 水平镜像会把顺时针变成逆时针：镜像后的走向是「右下起步 → 右边向上 → 顶边向左 → 左边向下」，与目标相反。也就是说即使翻转生效也不是要的效果。翻转在真机上为何完全看不出效果未查明，见 sessions.md。
- **路径接收端抽象。** 三条边角构造改为写入 `ShiningBorder.BorderPathSink`，Android 侧用 `Path` 适配，JVM 测试用折线采样器。几何只有一处实现，测试不重写一遍；否则 `Path`／`PathMeasure` 是框架实现，单测里根本建不出来。
- **内容承接是纯放大，不是淡入。** 光带 `onAnimationEnd`（此时暗段余光已退完）后，内容以右下角为轴（pivot = content 宽高）从 0.84 放大到 1，216 ms，`DecelerateInterpolator`。内容从第一帧起 alpha 就是 1，没有透明度变化——用户第二次裁定明确去掉了淡入。放大结束的回调里才 `finish(true)`，此前首页快照一直在底层。
- **中断必须复位。** `finish()` 统一 `animate().cancel()` 并把 scaleX／scaleY／alpha 复位为 1，覆盖 onPause、onConfigurationChanged、content 尺寸变化与 attach 失败四条路径，不允许留下缩小或半透明的页面。
- **卡片级光带同样右下起步。** `findViews()` 里对 `mShiningBorder` 设一次 `START_BOTTOM_RIGHT`，卡片级覆写与 `restoreShiningBorderDefaults()` 都不再碰起点角，避免一次右下一次左下。1600 ms、末尾 220 ms 淡入、几何保护与中断逻辑不变。
- **显式矩形不能被 View 边界覆盖（既有缺陷）。** `assignPathAndFrame(l, t, r, b)` 现在同时置 `mPathAssigned = true`、清 `mReassignBeforePlay`。此前 `onDraw`（View 之前是 INVISIBLE、从未绘制）与 `startAnimation`（布局后尺寸变过）都会用 View 自身边界重算，把卡片矩形覆盖成整屏，表现为进程内第一次保存播的是整屏光带、第二次起才正常。两台设备均已复现并在修复后验证。
- **涟漪圆心取 FAB 中心。** `ThingsActivity` 用 `mFab.getLocationInWindow` 加宽高一半算出窗口坐标传给 `launch()`，`startRipple()` 换算为 content 局部坐标并钳制到 [0, w]×[0, h]，终止半径取 `hypot(max(cx, w-cx), max(cy, h-cy))`。没有来源坐标（NaN）时退回内容右下角。600 ms 不变。粒子档未改。

## 2026-09-14 设置标题

- 标题改为“新建记事动画风格”，同步 13 套语言资源，选项值及旧设置迁移规则不变。

## 2026-09-14 首帧准备

- 同一份快照像素先用于 GL 前景转换与上传，同时后台继续建材；不更改像素、材料、积分或轨迹。分段上传在设备上与原打包字节相等。
- 新建首帧仍需要完整轨迹准备，实测改善有限，不宣称立即开始。压力微调、扩大队列、寿命索引及提前暂停页内过渡均因收益不足撤回；批量积分因实际 GPU 像素差异撤回。
- 不保留捕获与 Activity 并发启动的试验：首轮出现长等待，不能用未证实稳定的窗口交接换取少量热启动收益。保持原承接顺序与 Activity 架构。

## 2026-09-13 保存后的三阶段入场

- 用户要求“列表腾位 → 粒子出现 → 显示完整记事”。实机逐帧观察确认原实现与 RecyclerView 插入动画并发：粒子进度约 22% 时，默认添加动画已把真实卡片 alpha 写回 1。
- 粒子档在原滚动门控之后继续等待真实 ItemAnimator 的完成通知，等待期间用 INVISIBLE 保留布局空间；不依赖固定延时，也不提前结束旧卡片的移动。
- 待列表动画全部结束，再让共同粒子管线接管 alpha。正常结束仍以粒子末帧实际提交为准恢复真实卡片；延后启动受原有代次与生命周期清理保护，失效回调不再启动动画。
- 回归通过 debug 专用 NewItemAppearanceProbeActivity 旁观真实首页及真实保存入口，逐帧断言腾位与粒子不重叠、粒子末帧前真实卡片不可见。探针不修改生产参数或记事数据。

## 2026-09-13

- 使用新整数键保存三档选择，旧 Boolean 键保留只读兼容，原 true 对应边框光效、false 对应涟漪。
- 保留 DetailActivity 架构。尝试在本次跳转中禁用系统过渡，通过一次性的首页快照承接窗口切换，再在详情窗口播放实际内容的入场动画；Activity 初始化等待仍然存在，不宣称消除启动成本。
- 保存后卡片继续沿用已有“滚动到完整可见再播放”的编排，粒子使用固定右下来源，不受弹窗动画开关控制。
- 转场期间保持输入法隐藏，内容完整显示后再恢复正文焦点并展开；避免首页快照阶段先出现键盘，也避免快照准备过程中窗口高度变化。
- 粒子层释放通知必须在父容器完成子节点移除后发出，不能在 detach 回调中同步移除同一父容器的其它层；实机已复现原顺序导致 ViewGroup 子节点数组异常。

实现边界：使用 ActivityOptions 的本次自定义零时长过渡，不动态修改 Activity 主题。AOSP 的 [ActivityRecord 起始窗口选择](https://android.googlesource.com/platform/frameworks/base/%2B/d29b9cdc8e4061321fa602baac1382c408a1c810/services/core/java/com/android/server/wm/ActivityRecord.java)区分同任务内启动与新任务／任务切换；本入口保持现有同任务跳转，不引入透明 Activity 或 Fragment 重构。厂商系统上的窗口切换观感仍须实际验证。
