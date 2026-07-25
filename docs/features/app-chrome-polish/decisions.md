# App Chrome Polish Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-07-26 - dialog 的「点击外部取消」以手势起点为准

系统 `Window.shouldCloseOnTouch` 只判断 `ACTION_UP` 的落点，从 dialog 内部无控件
消费 touch 的位置按下、滑到外部抬手也会取消 dialog。`BaseDialogFragment` 改为在
`onCreateDialog` 返回同文件内的 `GestureAnchoredDialog`：它在 `ACTION_DOWN` 时用
与 framework `Window.isOutOfBounds` 等价的算法（decorView 宽高 ±
`scaledWindowTouchSlop`）记下起点位置，起点在 dialog 内则整段手势不参与取消判定。
`ACTION_OUTSIDE` 视为起点在外，保留非 modal window 的默认行为。

只在这一条路径上拦截，不碰 `setCancelable` / `setCanceledOnTouchOutside` / back 键
取消 / cancel-dismiss 回调 / dialog 内控件的事件分发。全项目 27 个 dialog 都是
`BaseDialogFragment` 子类，且此前只有基类覆写 `onCreateDialog`，因此这一处改动即
全量生效。

同日后续：`BaseDialogFragment` 已迁到 androidx，`GestureAnchoredDialog` 随之改为继承
`ComponentDialog` 以保住 `OnBackPressedDispatcher`，见
[androidx-dialogfragment-migration](../androidx-dialogfragment-migration/decisions.md)。

## 2026-05-29 - NoticeableNotificationActivity keeps its embedded Thing row square

`NoticeableNotificationActivity` is a Hybrid Chrome Surface: the outer
dialog-like App Chrome shell may be rounded, but the embedded full-row Thing
card inside the dialog is still Thing Background content and should remain a
square full-row surface. Do not let normal home-card corner radius or App Chrome
dialog corner clipping leak into that embedded card.

## 2026-05-18 - Recurrence picker NORMAL cells: Material FAB → fake-FAB
`RecurrencePickerAdapter`'s `NormalViewHolder` swapped Material FAB
for a fake-FAB (FrameLayout + bg View + RippleDrawable foreground,
mirroring `color_picker_fab.xml`). This lets picked cells carry a real
OVAL `GradientDrawable` instead of being flattened to representative
via `setBackgroundTintList`. The ripple waveform itself remains
single-int representative (Android `RippleDrawable` `ColorStateList`
limit) — assessed acceptable; "real-gradient ripple via custom touch
animation" is a follow-up, not Phase 8 scope.

## 2026-05-18 - Plan §4.7.4 "FAB tint must be single int" is overruled where fake-FAB is feasible
The color-system migration plan classification of FAB as "single-int only"
was too conservative — the fake-FAB pattern bypasses the API
restriction by replacing the widget. Plan §4.7.4 still applies to
genuine Android-API single-int seams (Notification.setColor,
PorterDuff tints, EdgeEffect.setColor, RippleDrawable ColorStateList).
