# 由新 Activity 承载的弹窗背景不变暗

## 现象与入口

用户报告：开始做事（选择做事时长）与更突出的记事通知这两类弹窗出现时背景完全不变暗，
其余弹窗正常。开始做事的两条入口（详情页右下角按钮、首页右滑记事卡片）都复现。

共同点是**弹窗由一个新的透明 Activity 承载**，并且在宿主的第一帧之前就提交：

| 入口 | 宿主 | 提交时机 |
|------|------|----------|
| 开始做事（普通记事） | `StartDoingActivity` | `onCreate` |
| 更突出的记事通知 | `NoticeableNotificationActivity` | `onPostCreate`（`ParticleDialogContentActivity`） |
| 延迟提醒 | `DelayReminderActivity` | `onCreate` |
| 私密内容认证 | `AuthenticationActivity` | `onCreate` |
| 列表小组件配置 | `ThingsListWidgetConfiguration` | `onPostCreate` |

提醒／习惯的开始做事走 `ThingDoingDialogFragment`，宿主是已经在屏上的详情页，不受影响——
这正是「只有部分弹窗不变暗」的分界。

## 根因

暗层全程由应用接管（主题禁用了系统 `FLAG_DIM_BEHIND`），`DialogDimLayer` 把一张纯黑 View
挂到宿主 DecorView 上。第一批性能改动（`6c583e34`）把挂载方式从 `decor.addView(view,
MATCH_PARENT)` 改成 `decor.overlay.add(view)`，省掉挂一层全屏层带来的整窗 `requestLayout`。

代价是 **overlay 的内容不参加宿主的测量布局**，尺寸得自己给，而 `ParticleOverlayHost`
只在 attach 当场算了一次：

```kotlin
fun layoutFullScreen(decor: ViewGroup, view: View) {
    val width = decor.width
    val height = decor.height
    if (width <= 0 || height <= 0) return   // ← 宿主首帧前就是这一支
    ...
}
```

`BaseDialogFragment` 用 `decorView.doOnAttach` 等宿主附着后再挂暗层。对上表里的宿主，附着
发生在 `ViewRootImpl.performTraversals` 的 `dispatchAttachedToWindow`——**早于第一次
`performLayout`**，此刻 `decor.width` 仍是 0。于是暗层以 0×0 留在 overlay 里，之后再没有
任何人给它布局，整个 Dialog 期间一个像素都画不出来。宿主已经在屏上的弹窗 `doOnAttach`
当场执行、decor 早已有尺寸，所以完全正常。

同一处还有第二种失效：宿主的尺寸在挂载之后变化（旋转、分屏、insets），overlay 里的层不会
跟。普通 `addView` 路径由 `MATCH_PARENT` 天然覆盖这两种情况，改挂 overlay 之后必须自己补。

## 修正

1. `ParticleOverlayHost.attach` 挂完之后注册一个宿主布局监听（`FullScreenSync`），宿主每次
   改变布局都按最新尺寸重新 measure + layout 这一层。监听器只弱引用层本身，层撤下或被回收
   后自己从宿主上摘掉，不依赖 `detach` 的调用时机。粒子叠加层、滑动关闭的锚点层与暗层共用
   这一条路径，因此三者一并覆盖。
2. `DialogDimLayer` 的淡入改为挂在 `view.doOnLayout` 上。宿主首帧前挂上来时这一层既没有
   尺寸、也从未进入绘制树，此刻启动的 RenderThread 动画会立刻结束（同类现象见首页新卡片
   的 reveal）。宿主已经布局过时 `doOnLayout` 当场执行，与改动前逐帧一致。
3. `BaseDialogFragment` 在延迟挂上暗层之后按当前状态补一次浓度。出现动画若在暗层挂上之前
   就结束（宿主未附着时 `startCondense` 直接失败、或准备阶段被打断），进度回调全部落在
   `null` 上，暗层会停在 alpha 0；这是同一症状的第三条路径，一并堵上。

## 验证状态

`:app:assembleDebug` 通过；新增 `DialogDimCoverageTest` 三项，逐项确认在修正前的 `HEAD`
源码上都会失败（真回归，不是恒等式）。**本轮没有上真机**，未验证实际观感；JVM 侧只有 JUnit、
没有 Robolectric，View 行为无法在本地驱动，因此三项契约定在源码上。

## 发布复核

阿里云 debug 更新 `202609150741` 已发布。本地发布产物与远端完整 APK 的 SHA-256 均为
`cac1bd0016169bfe6e4c3701ec7ea4b9d4da7d4e15b05e37d4b8c1c4143cc37c`，28,701,204 字节；远端
`latest.json` 的发布号、散列、字节数与本地一致，含完整 releaseNotes（1275 字）。本轮未创建
Git 提交，未连接任何真机——发布包的实际观感待用户在五处入口上确认。
