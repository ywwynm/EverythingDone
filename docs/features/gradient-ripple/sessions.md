# Gradient Ripple — Sessions

## 2026-06-27 — Initial implementation

- Confirmed against AOSP source that the platform `RippleDrawable` cannot render a gradient
  ripple (single-colour paint). The user picked form B (gradient ripple surfacing on press)
  over form A (persistent gradient underlay + plain ripple). See `decisions.md`.
- Added `app/.../views/GradientRippleDrawable.kt` and wired unselected-state gradient
  ripples into three places:
  - drawer `ThingStatusSegmentedView` — status segments (正在进行 / 已完成 / 回收站),
    unselected ROUND_RECT (radius = height/2) ripple from `scopeBackground`.
  - drawer `ThingFilterPanel` — the 5 type icons, unselected OVAL ripple on
    `button.background`.
  - `RecurrencePickerAdapter` — weekly/monthly/yearly circular buttons (OVAL) and the
    month-end pill (ROUND_RECT). Added `onViewRecycled` to stop animations.
- Hardened `DisplayUtil.setRippleColorForCardView` to an `is RippleDrawable` check.
- Builds clean (`:app:assembleDebug`). Published a debug update (code 202606271303) to the
  Aliyun channel with `PEAK_ALPHA = 0.36` for on-device tuning. Not committed — awaiting the
  user's visual review.

## 2026-06-27 — Animation tuning after first on-device test

- Feedback: the ripple followed the finger when sliding and could disappear before filling
  the circle; it also felt slower than the system ripple.
- Reworked `GradientRippleDrawable` animation: lock the origin at press (no finger
  tracking); decouple radius and alpha so the radius always fills even on quick
  release/scroll-cancel (alpha-only fade); speed it up (radius 260ms, alpha enter 60ms,
  alpha exit 300ms). Published debug update 202606271314. Still not committed.
