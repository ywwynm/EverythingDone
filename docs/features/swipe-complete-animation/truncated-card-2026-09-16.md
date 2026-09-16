# 被截断的卡片：粒子不该把它补全（2026-09-16）

## 现象

正在做事界面的记事卡片可能被截断——卡片比列表高的时候，列表按一条直线把它切掉一截。
左右滑动完成记事时，粒子动画接管的那一帧却把**整张卡片**显示出来：观感是卡片突然长高，
被截掉的内容凭空出现。

截断线与卡片圆角的关系有三种，都要照做：截在圆角以下时下沿是直角；截在圆角那一段时是
半截圆角；没截到时仍是完整圆角。

## 根因

两处一凑就成了这个样子：

1. **截断由列表做，不由卡片做。** `DoingActivity.setRecyclerViewEvent` 在每次全局布局后把
   `rv_thing_doing` 的高度压到 `getThingCardRegionMaxHeight()`（时钟下方到按钮上方，再扣掉
   「滑动完成」提示与上下边距）。卡片自身的高度不变，超出的部分由 RecyclerView 的
   `clipChildren` 裁掉——**只是没画出来，不是不存在**。

2. **快照抓的是整张卡片。** `ParticleSwipeAnimator.Session.prepare` 调
   `ParticleDismissController.captureContent(view)`，它按 `view.width × view.height` 离屏渲染
   （`ParticleHardwareSnapshot`：`ImageReader.newInstance(view.width, view.height, …)` +
   `node.setPosition(0, 0, view.width, view.height)`）。离屏渲染器不经过列表那一层，
   列表的裁剪对它不存在。

于是粒子拿到的材料比屏幕上那一块大，`buildOverlay` 又按卡片的布局原点摆放，多出来的部分
正好长在截断线以下。

## 修法

让快照只抓卡片**露出来的那一块**，动画层跟着挪到那一块的左上角。

- `ParticleVisibleBounds`（纯几何、可单测）：给定卡片尺寸，逐层并进容器的裁剪矩形，得出
  可见区；整张都露着时 `covers` 为真。
- `ParticleCardClip.visibleRect(view)`：沿父链读出每层的 `clipChildren` / `clipToPadding` /
  `scrollX/Y` 与 padding，交给上面那个对象算。**一律按 `left`/`top` 这类布局位置算，不看
  translation 与 scale**——滑动中的位移和按压反馈的缩放都不改变卡片静止时露出的形状。
  整张都露着时返回 null，调用方走原来那条不裁的路，逐帧与以前完全一致。
- `captureContent` / `ParticleHardwareSnapshot.capture` / `captureViaSoftwareDraw` 增加可选的
  `clip`：离屏节点按可见区定尺寸，记录前 `canvas.translate(-left, -top)`，outline 用
  `Outline.offset(-left, -top)` 跟着挪，阴影光源的原点也加上同一偏移。
  **在这里裁而不是抓完再裁**，省掉一次整张卡片大小的位图拷贝。
- `ParticleSwipeAnimator.Session` 记下 `clipOffsetX/Y`：`buildOverlay` 的原点加上它，
  准备期补画的 `drawOriginal` 也按它偏移。

三种圆角形状不需要额外判断：圆角本来就由 `setClipToOutline` 烤进快照，而截断是矩形裁剪，
两者相交自然得到「完整圆角 / 半截圆角 / 直角」。

## 影响面

`ParticleSwipeAnimator` 同时服务首页与正在做事界面，因此首页上被列表上下边缘截住的卡片
（滚到一半时）也一并按可见区播放。卡片完整可见时 `visibleRect` 返回 null，走的还是原来那条路。

本轮没有改 `ThingSwipeReturn` 走的 `appearContent`（滑走之后回位的倒放出现）——那条路在首页，
捕获时机与这条不同，留作后续。

## 验证

- `:app:assembleDebug` 通过。
- `ParticleVisibleBoundsTest` 7 项全过：装得下／截下沿／截上沿（滚动）／多层取交集／左右截断／
  完全移出为空／容器更大时仍算完整。
- 真机：用户实测 debug 更新 `202609160726`，正在做事界面被截断的卡片按可见区播放，确认修好。
