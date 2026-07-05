# Thing Background 编辑器偏好

## 2026-07-05 - 渐变 ripple 触点

- 所有使用 `GradientRippleDrawable` 的触摸反馈都应从真实触摸点扩散，不能在纯色/渐变 tab、首页长按颜色面板、详情页颜色面板或其它入口中从控件中心固定出现。
- 这类问题优先在 `GradientRippleDrawable` 内统一处理 hotspot 与 pressed 状态的到达顺序，以及快速连续点击时旧触点复用的问题；不要为每个调用点单独安装 `setOnTouchListener`。

## 2026-06-26

- 首页记事/文件夹外观 panel 为当前卡片预留的轻量可见高度按用户已调整的 `36dp` 处理；后续修复键盘、滚动或动画问题时不要改回按卡片完整高度或百分比预留的方案。
