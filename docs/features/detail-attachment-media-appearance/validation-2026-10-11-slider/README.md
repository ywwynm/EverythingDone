# 首次滑条与滚动窗口验证结果

- `9018f404-slider-baseline-portrait.json`：原启用状态逻辑，首次打开 4/4 失败，缓存重开 4/4 通过。
- `R5CW20BLNKL-layout-baseline-landscape.json`：已修复启用状态，但未加入滚动区域；6/8 因滑条超出屏幕无法拖动。
- `192.168.100.67_42641-input-failure-portrait.json`：中间版本的一次系统输入注入失败，保留排查记录，未计入通过。
- `*-final-portrait.json`、`*-final-landscape.json`：最终修复的三机横竖屏结果，每个文件 8/8 通过、清理 4 条临时记事。

每个最终样本记录滑条内外启用状态、拖动前后比例、裁剪手势是否保留，以及滚动位置、范围、视口高度和上下分割线。`hide-ratio`、`show-ratio` 检查“正常/宽”切换后的边界刷新；`gesture` 是真实纵向手势，`top/middle/bottom` 是精确位置检查。详细复现步骤与验证范围见[诊断记录](../diagnosis-2026-10-11-first-open-slider.md)。
