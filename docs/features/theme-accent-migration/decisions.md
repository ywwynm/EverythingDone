# 主题强调色迁移 Decisions

## 2026-06-23 - 渐变 checkbox 选中态需与未选中态外径一致

设备验证发现选中态比未选中态小一圈。根因：未选中态是居中 STROKE（描边跨在路径上，外缘比 `rect` 外扩 `strokePx/2`），选中态是 FILL（只填到 `rect`），于是选中比未选中小 `strokePx/2`。不能在选中态补描边——描边色是 `uncheckedColor`，盖在强调色填充上颜色不对；而未选中态的描边色本身是对的、不动。

决策：选中态的填充**外扩到未选中描边的外缘**（`rect` 外扩 `strokePx/2`、圆角同步 `+strokePx/2`），使选中/未选中外径相同，且选中态只有强调色填充、没有错色描边；过渡动画期间填充与渐隐的描边外缘也对齐、不跳变。未选中态保持原样。

附带修正：`GradientCheckboxDrawable` 的渐变由 `linearGradientFor` 建在 box 本地 `(0,0)-(side,side)` 空间，需用 `gradientMatrix.setTranslate(left, top)` 平移到 box 上——因为方框现在居中在更大的 footprint 里（`left/top` 非 0），否则选中态渐变会错位并被 clamp。ColorPicker 走 compound drawable（画布已平移、`left/top=0`），平移为 0、不受影响。

## 2026-06-23 - 渐变 checkbox 不得改变 checkbox 的占位几何与对齐

设置页 checkbox 加渐变支持后出现两个回归：文本与方框之间没了间距、方框与同列“?”帮助图标对不齐。

根因：`applyCheckboxAccent` 把 CheckBox 的 `buttonDrawable` 换成 `GradientCheckboxDrawable`（intrinsic 24dp、内缩 3dp、可见方框约 18dp，见 `BackgroundUtil.kt`），其占位几何比系统原生 button drawable 更小、四周透明留白更少；而 `activity_settings.xml` 的行布局原本是按原生 checkbox 的占位调好的（文本 `toLeftOf` checkbox，间距全靠 drawable 左侧透明留白）。留白骤减 → 文本贴住方框；方框比“?”图标的 24dp 小、落点也因两类行结构不同而错开 → 不成列。

参考：设置页“?”帮助图标是 **24dp** 图、`scaleType=center` 居中在 **48dp** 容器、行右内边距 **8dp**；checkbox 行右内边距 **16dp**。两类行结构不同，对齐以可见控件的中心线为准。

修复原则与取舍（已确认）：渐变只是视觉皮肤，不应改变 checkbox 的占位几何。

- 恢复文本与方框之间约 **8dp** 间距。
- checkbox 可见方框保持**常规大小（约 18–20dp）**，不放大到问号图标的 24dp；通过**中心线**与右侧“?”帮助图标对齐成列（方框比问号略小是正常的）。
- 实现优先把“四周留白 + 居中固定尺寸方框”的占位逻辑做进 `GradientCheckboxDrawable` 自身（让它像原生 drawable 一样自带留白），尽量少改 `activity_settings.xml`；最终 dp 连模拟器对着“?”图标微调验证。
