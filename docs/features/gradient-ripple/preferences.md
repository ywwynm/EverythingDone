# Gradient Ripple 偏好

## 2026-07-05 - 渐变方向不可改

- `GradientRippleDrawable` 的颜色渐变方向必须复用 `ThingBackground.orientation`，继续通过 `BackgroundUtil.createLinearGradient(...)` 生成；修触摸反馈时只能修扩散圆心、触点顺序、动画状态和裁剪，不能把 ripple 本身改成径向渐变、局部渐变或固定方向。
- 用户指出的“从左上到右下扩展”是触摸扩散行为问题，不是要求改变 ripple 的线性渐变方向。
