# Current Debug Update Notes

## 2026-06-07 - 用户手调后提交搜索 HUE_BUCKET ColorPicker 间距

用户在上一版基础上又做了一次本地视觉调整，并要求直接 Git 提交。

本次提交采用用户最终手调后的状态：搜索页实际使用的仍是 `Def.PickerType.HUE_BUCKET`；第一行 hue bucket FAB 的 top margin 为 8dp，仍小于原始 16dp；最后一行 hue bucket FAB 的 bottom margin 为 12dp；`HUE_BUCKET` 的 RecyclerView 固定高度保持 256dp。同步清理了不再使用的局部 margin 变量。

同步更新 `docs/features/popup-picker-insets/preferences.md` 与 `sessions.md`，确保文档记录的是最终提交值，而不是上一版发布测试时的 18dp/262dp。用户本轮只要求提交，因此没有在最终手调后重新发布 debug update。

## 2026-06-07 - 搜索 HUE_BUCKET ColorPicker 间距回调到中间值

用户确认上一版已经改到实际搜索使用的 `HUE_BUCKET`，但要求把第一行 FAB 的 top margin 从 0 恢复到比最开始小一些，底部 margin 也恢复到比当前小一些，并考虑是否需要调整整个 popup 高度。

本次修改：`HUE_BUCKET` 第一行 FAB 的 top margin 从 0dp 调整为 8dp，仍小于最初的 16dp；最后一行 FAB 的 bottom margin 从 20dp 调整为 18dp，仍略大于左侧 FAB 到 popup 左侧的 16dp margin；`HUE_BUCKET` 的 RecyclerView 固定高度从 256dp 调整为 262dp，用来吸收行高增加并保留底部留白。

同步更新 `docs/features/popup-picker-insets/preferences.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070448` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 的第一行 FAB 间距、底部留白和 popup 总高度。

## 2026-06-07 - 修正搜索 ColorPicker 实际使用的 HUE_BUCKET 间距

用户反馈：即使手动把 `COLOR_HAVE_ALL` 的 `params.height` 改成 400dp，搜索 ColorPicker 的高度和间距仍然没有变化；要求仔细检查是否还有其它地方更新高度和 margin，并明确实现目标：缩小“全部颜色”和第一行 FAB 的间距，放大最后一行 FAB 和 popup 底部的间距，底部间距要比左侧 FAB 到 popup 左侧的 16dp margin 稍大一点。

本次诊断：搜索页实际创建的是 `ColorPicker(this, window.decorView, Def.PickerType.HUE_BUCKET)`，不是 `COLOR_HAVE_ALL`。因此之前调整 `COLOR_HAVE_ALL` 的 fixed height 或 margin，不会影响正在测试的首页搜索 ColorPicker。搜索 popup 里的“全部颜色”是 `HUE_BUCKET` 的 all-filter row，下方 FAB 是 8 个 hue bucket，分成 4 行。

本次修改：恢复 `COLOR_HAVE_ALL` 的高度和 margin 基线；改为调整 `HUE_BUCKET`。第一行 hue bucket FAB 的 top margin 改为 0dp，让它更贴近“全部颜色”；最后一行 hue bucket FAB 的 bottom margin 改为 20dp，比左侧 FAB 到 popup 左侧的 16dp margin 稍大一点。`HUE_BUCKET` 的 RecyclerView 固定高度保持 256dp。

同步更新 `docs/features/popup-picker-insets/preferences.md` 与 `sessions.md`，记录首页搜索 ColorPicker 对应的是 `HUE_BUCKET`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070439` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 的第一行 hue bucket FAB 间距和底部留白。

## 2026-06-07 - 直接修正搜索 ColorPicker FAB 间距

用户反馈上一版仍然很怪，并明确要求：第一行 FAB 的 top margin 直接改成 0，最后一行 margin 加大。

本次修改：`COLOR_HAVE_ALL` 第一行两个 FAB 的 top margin 从 4dp 改为 0dp；最后一行两个 FAB 的 bottom margin 从 16dp 改为 24dp；搜索 ColorPicker 的 RecyclerView 固定高度继续保持 312dp。这样“全部颜色”和第一行 FAB 之间会明显收紧，最后一行 FAB 到 popup 底部的留白也会明确加大。

同步更新 `docs/features/popup-picker-insets/preferences.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070430` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 的第一行 FAB 贴近程度和底部留白。

## 2026-06-07 - 重新调整搜索 ColorPicker 底部留白来源

用户测试 `202606070415` 后反馈视觉区别不大，并指出 FAB 的高度、margin 本身没问题；因为 ColorPicker 的 RecyclerView 高度是写死的，要增加底部留白更应该增大 RecyclerView 固定高度，同时第一行 FAB top margin 仍然要减小。

本次诊断：`COLOR_HAVE_ALL` 由一个 48dp 的“全部颜色”行，加 10 个颜色 FAB 组成的 5 行双列网格构成。上一版只是把第一行 FAB 上方 4dp 挪到最后一行 FAB 的 bottom margin，整体内容高度基本不变，RecyclerView 固定高度仍是 304dp，所以实际底部留白变化不明显。

本次修改：`COLOR_HAVE_ALL` 的 RecyclerView 固定高度从 304dp 增加到 312dp；第一行 FAB 的 top margin 保持从 8dp 减到 4dp；最后一行 FAB 的 bottom margin 恢复普通的 16dp，不再把最后一行 item 自己撑大。这样底部新增空间来自 RecyclerView viewport，而不是来自 FAB item margin。

同步更新 `docs/features/popup-picker-insets/preferences.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070426` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 的整体高度、全部颜色到第一行 FAB 的间距，以及最后一行 FAB 到 popup 底部的留白。

## 2026-06-07 - 微调搜索 ColorPicker 的垂直间距

用户反馈：搜索时的 ColorPicker 里，“全部颜色”和下方 FAB 网格之间的间距稍微大了点，而最下方 FAB 和 popup 底部的间距又稍微小了一点。

本次修改：只调整 `Def.PickerType.COLOR_HAVE_ALL`，不影响详情页改色 ColorPicker、Hue bucket picker 或 COLOR_EDIT picker。第一行 FAB 的 top margin 从 8dp 改为 4dp，让“全部颜色”和 FAB 网格之间更紧一点；最后一行 FAB 的 bottom margin 从 16dp 改为 20dp，让 popup 底部留白更足一点。由于上方减少的 4dp 正好移动到底部，搜索 ColorPicker 的总高度保持不变。

同步新增 `docs/features/popup-picker-insets/preferences.md`，并更新 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070415` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 的“全部颜色”到第一行 FAB 间距、最后一行 FAB 到 popup 底部间距，以及 popup 总体高度是否仍稳定。

## 2026-06-07 - ColorPicker 改为从 anchor 右上角出现

用户确认 `202606070356` 中自定义卡片外观选择封面来源 popup 的位置 OK，并提出最后一个调整：ColorPicker 不要再从 anchor center 显示，改为直接从 anchor 右上角显示，但动画方向保持不变。

本次修改：`ColorPicker` 继续使用 `PopupPicker.installContentSurfaceScaleTransition(1, 0)`，因此 popup surface 仍然从自身右上角向左下展开、反向收起。定位上，`ColorPicker.show()` 的有效 anchor 分支从 `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)` 改为 `showAsDropDown(anchor, 0, -anchor.height, Gravity.END)`，让 popup 的右上角直接落在 trigger view 的右上角，而不是 trigger view 的中心。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070400` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 与详情页改色 ColorPicker 的出现锚点和出现/隐藏动画方向。

## 2026-06-07 - 修正 source picker 右下角锚点偏下

用户测试 `202606070343` 后反馈：自定义卡片外观选择封面来源 popup 仍然没有从对应 TextView 的右下角出现，而是比右下角更偏下。

本次诊断：上一版已经去掉了 source picker 的 8dp screen-margin clamp，但它仍然使用 `Gravity.BOTTOM | Gravity.START`，并且按 `mParent.height - navBottom` 计算 bottom reference。与此同时 content-surface popup 已经统一设置 `PopupWindow.isClippingEnabled = false`。在这个组合下，bottom gravity 可能按完整 parent/window bottom 结算，继续减 `navBottom` 会让 popup bottom 比目标 anchor bottom 低一个系统栏 inset 左右，视觉上就是“更偏下方一些”。

本次修改：`ThingCardAppearanceSourcePicker` 不再使用 bottom gravity 做右下角定位，改为先测量 popup 宽高，再使用 `Gravity.TOP | Gravity.START`：`x = anchorRight - popupWidth`，`y = anchorBottom - popupHeight`。这样 popup 的右下角和 source TextView 的右下角是直接几何对齐，不再受 bottom gravity / navbar compensation 语义影响。出现和隐藏动画仍保持从右下向左上展开、反向收起。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070356` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试自定义卡片外观选择封面来源 popup 的右下角锚点是否已经贴到对应 TextView 右下角。

## 2026-06-07 - 修正 content surface popup 的精确锚点

用户测试 `202606070330` 后提出两个问题：
1. 自定义卡片外观选择封面来源 popup 的右下角锚点不像预期中的 pill 触摸区域右下角；
2. DateTimeDialog “一段时间之后” tab 里的 time-type popup 出现锚点高于对应 pill 左上角，而“重复” tab 因为 dialog 更高没有同样问题。用户询问是否与 pill 的 `clipToOutline` 或 dialog 高度限制有关，以及是否有不改 dialog 高度的更好办法。

本次诊断：`BackgroundUtil.installPillRipple(...)` 会清掉 background、设置 foreground ripple，并用 `clipToOutline` 把绘制裁成 pill，但这不会改变 TextView 的 measured bounds；所以 pill 触摸区域仍然是整个 TextView 矩形，不是 outline 另算出来的小区域。source picker 的偏差主要来自上一版为防越界加的 8dp margin/clamp。DateTimePicker “一段时间之后” tab 的问题则来自我们把 time-type popup 的 y offset clamp 到 `mParent.height - popupHeight`；after tab 的 dialog 内容更矮，popup 放不下时被我们自己提前上移了。重复 tab 内容更高，所以没有触发这个 clamp。

本次修改：
- `PopupPicker.installContentSurfaceScaleTransition(...)` 对 content-surface transition popup 统一设置 `mPopupWindow.isClippingEnabled = false`，让较矮的 dialog tab 可以让 popup 越出局部内容高度，而不需要改 dialog 高度。
- `DateTimePicker` 改为使用精确 anchor offset：quick-remind 的 popup bottom 直接对齐 anchor TextView 垂直中心，time-type 的 popup top 直接对齐 anchor TextView top，不再按父容器高度做可容纳性 clamp。
- `ThingCardAppearanceSourcePicker` 改为 popup right/bottom 直接对齐 anchor TextView right/bottom，不再额外套 8dp screen-margin clamp。首尾 item 的 8dp row margin 保持不变。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070343` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试自定义卡片外观选择封面来源 popup 的右下角锚点、DateTimeDialog “一段时间之后” tab 的 time-type 左上角锚点，以及这几个 popup 的隐藏动画。

## 2026-06-07 - 统一剩余 popup picker 的 content surface transition

用户反馈：app 里还有多处使用原本的 `PopupWindow` window animation，需要全部改掉，并指定三类 popup 的出现位置与动画方向：
1. quick-remind：popup 出现在对应 TextView 左侧，Y 方向居中，从左下向右上展开；
2. time-type：popup 出现在对应 TextView 左上角，从左上向右下展开；
3. 自定义卡片外观选择封面图片：popup 出现在对应 TextView 右下角，从右下向左上展开；同时 popup 内第一项/最后一项分别增加 top/bottom margin，参考 quick-remind。

本次修改：
- `DateTimePicker` 内按 picker type 分开处理 transition 和定位。`AFTER_TIME` 继续使用 content surface 左下角 pivot `(0, 1)`，popup 左边缘对齐 anchor TextView 左侧，底边对齐 anchor 垂直中心，并保留 bottom system-bar inset 补偿。time-type popup 改为 content surface 左上角 pivot `(0, 0)`，popup 左上角对齐 anchor TextView 左上角。
- `ThingCardAppearanceSourcePicker` 不再使用 `QuickRemindPickerAnimation`，改为 `popupAnimStyle = 0` 并调用 `installContentSurfaceScaleTransition(1, 1)`，让可见圆角 surface 从右下角向左上展开/反向收起。定位改为 popup 右下角对齐 anchor TextView 右下角，并保留 bottom system-bar inset 补偿。
- `ThingCardAppearanceSourcePicker` 的 adapter 在首项增加 8dp top margin、末项增加 8dp bottom margin，保持和 quick-remind picker row spacing 一致。
- 删除剩余旧 popup window animation style/resource：`QuickRemindPickerAnimation`、`quick_remind_picker_show.xml`、`quick_remind_picker_hide.xml`。此前已删除 `ColorPickerAnimation` 与 `TimeTypePickerAnimation`。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070330` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试详情页 quick-remind、DateTimeDialog 的 time-type 下拉、自定义卡片外观选择封面来源 popup，以及三者的出现/隐藏动画和点击外部/BACK 隐藏动画。

## 2026-06-07 - DateTimePicker 改用左下角 content surface transition

用户确认上一版 ColorPicker 的 content-surface transition 方案可以接受，并要求把 DateTimePicker 也按同样思路改掉：popup 从左下向右上展开，但出现位置可以是对应 TextView 的左侧，而不是水平居中；Y 方向仍以 TextView 垂直中心为基准。

本次修改：把可复用的 content surface scale transition 抽到 `PopupPicker.installContentSurfaceScaleTransition(...)`。ColorPicker 改为继续使用这个共享 helper，pivot 为右上角 `(1, 0)`。DateTimePicker 不再使用 `PopupWindow` window animation，改为 `popupAnimStyle = 0`，清空 `PopupWindow` background，并通过共享 helper 安装 pivot 为左下角 `(0, 1)` 的 `enterTransition` / `exitTransition`，让可见圆角 popup surface 从左下向右上展开和收起。

DateTimePicker 的定位也同步调整：不再按旧动画资源里的内部 pivot 计算，也不再水平居中到 anchor TextView。现在先读取 anchor TextView 的 `getLocationInWindow(...)`，X 使用 TextView 左侧，Y 使用 TextView 垂直中心；popup 左边缘对齐该 X，popup 底边对齐该 Y。`AFTER_TIME` 分支仍保留 bottom system-bar inset 补偿，再换算为 `Gravity.BOTTOM` 所需的 y offset；time-type 分支使用 `Gravity.TOP`。为了避免旧 window animation 方案回流，删除了只剩 DateTimePicker 使用的 `TimeTypePickerAnimation`、`time_type_picker_show.xml` 与 `time_type_picker_hide.xml`；`QuickRemindPickerAnimation` 仍保留，因为 `ThingCardAppearanceSourcePicker` 还在使用。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070321` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试详情页 quick-remind DateTimePicker、DateTimeDialog 中的 time-type picker、出现锚点、出现动画、隐藏动画，以及点击外部/BACK 的隐藏动画。

## 2026-06-07 - ColorPicker 改用 content surface transition，避开 window animation 锚点漂移

用户测试 `202606070304` 后反馈：恢复 `PopupWindow` window animation 后，动画层级对了，但又变成不是从 anchor center 显示 popup。用户判断 window animation 可能直接影响出现锚点，并要求搜索网页寻找更好的解决方案。

本次检索与诊断：查阅 Android Developers `PopupWindow` 文档和 AOSP `PopupWindow.java` 源码后确认，`setAnimationStyle(...)` 使用的是 `windowEnterAnimation` / `windowExitAnimation`，AOSP 会把它写入 `WindowManager.LayoutParams.windowAnimations`。同时 `PopupWindow` 会使用 `PopupDecorView`，有 background 时还会包一层 background view，并为 elevation 设置 surface inset。因此 window animation 的百分比 pivot 作用在 window/decor/surface bounds 上，不一定等同于可见圆角 picker surface 的右上角；这解释了为什么 final position 可以正确，但动画视觉 origin 会偏离 anchor center。

本次修改：ColorPicker 不再使用 window animation，也删除旧的 `R.style.ColorPickerAnimation`、`color_picker_show.xml` 和 `color_picker_hide.xml`。ColorPicker 现在传入 `popupAnimStyle = 0`，清空 `PopupWindow` background，保留 `mContentView` 上的圆角 elevated background 作为唯一可见 popup surface，并通过 `PopupWindow.enterTransition` / `exitTransition` 安装 `ColorPickerSurfaceTransition`。该 transition 直接缩放可见 content surface，pivot 固定在 surface 右上角；定位仍使用已验证的 `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)`，让 surface 右上角对应 anchor center。因为使用的是 PopupWindow transition 而不是手写 show 后 content 动画，`dismiss()`、点击外部、BACK 等平台 dismiss 路径也能触发 exit transition。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录 ColorPicker 不再使用 window animation 做 anchor-origin surface motion。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070312` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试 ColorPicker 的出现锚点、出现动画、隐藏动画，以及点击外部/BACK 的隐藏动画。

## 2026-06-07 - 恢复 ColorPicker 的 popup 窗口级动画

用户测试上一版 `202606070258` 后反馈：位置终于对了，但动画不对。当前表现是 popup surface 直接出现，没有窗口动画；只有里面的 content 从右上向左下缩放出现；隐藏时 popup 也直接消失。用户需要的是 popup 本身的出现/隐藏动画，而不是 content 动画。

本次修改：撤销 ColorPicker 的 content-level scale/alpha animation，恢复 `R.style.ColorPickerAnimation` 作为 `PopupWindow` window animation style，让 popup surface 本身负责 show/hide 动画。保留已经验证位置正确的 `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)` 定位路径，以及显式写回 `mPopupWindow.width/height` 的做法。`color_picker_show.xml` / `color_picker_hide.xml` 仍保持 `pivotX="100%"`、`pivotY="0%"`，让窗口动画从 popup 右上角展开/收起。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录 content animation 是已废弃方案，正式方案应保留 PopupWindow window animation。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070304` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试 ColorPicker 的位置、出现动画和隐藏动画。

## 2026-06-07 - ColorPicker 改为 content 级别展开动画

用户纠正：ColorPicker 的触发点是 toolbar menu item 的 icon view，不应继续从 TextView/文字布局角度解释。结合线上检索和 Android 官方文档，`PopupWindow.setAnimationStyle(...)` 走的是 window enter/exit animation，动画作用在 popup window/decor 层；而不是 picker content 本身。这个 window/decor 层可能包含平台 surface inset、shadow 或窗口管理调整，因此即使 anchor 定位 API 没问题，窗口动画 pivot 仍可能和实际 picker surface 的视觉右上角不一致。

本次修改：ColorPicker 不再使用 `R.style.ColorPickerAnimation` 作为 `PopupWindow` window animation style，而是传入 `0` 禁用 ColorPicker 的窗口级动画。定位仍保留上一版的 `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)`，让平台负责 anchor 坐标换算。popup 放置后，直接在 `mContentView` 上执行 scale/alpha enter animation：`pivotX = mContentView.width`、`pivotY = 0`，从 `scaleX/scaleY/alpha = 0` 动画到 `1`，让实际 picker surface 从自身右上角展开。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录 ColorPicker 的动画层从 `PopupWindow` window animation 改为 picker content animation。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070258` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 与详情页改色 ColorPicker 的出现锚点。

## 2026-06-07 - ColorPicker 改用 PopupWindow 平台 anchor 定位

用户继续反馈：ColorPicker 仍然不像是从 anchor view 中心出现，并追问是否 anchor view 的计算位置不对。本次进一步阅读 Android 36 `PopupWindow` 源码：`showAsDropDown(...)` 内部会用 `anchor.getLocationOnScreen()` 减去 app root 的 screen location 来得到 app-window 坐标；而上一版 `ColorPicker` 是手动用 `getLocationInWindow()` 再传给 `showAtLocation(...)`。在 edge-to-edge、多窗口或 popup child-window parent frame 场景中，这种手算坐标系可能和平台内部转换不完全一致。

本次修改：`ColorPicker.show()` 在测量 popup content 后，将测得的 width/height 显式写回 `mPopupWindow`，这样 `showAsDropDown(..., Gravity.END)` 的 right-align 能使用绝对尺寸。有效 anchor 存在时改为调用 `showAsDropDown(anchor, -anchor.width / 2, -anchor.height / 2, Gravity.END)`，等价于让 popup top-right corner 钉到 anchor view center，但坐标转换交给 `PopupWindow` 平台路径处理。无有效 anchor 时才保留 `showAtLocation(...)` 右上兜底。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录 ColorPicker 的 top-right-corner 模型应使用平台 anchor positioning。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070247` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 与详情页改色 ColorPicker 的出现锚点。

## 2026-06-07 - ColorPicker 改为 popup 右上角向左下展开

用户提出新的判断：`ColorPicker` 的 anchor view 一般位于屏幕右上角，如果沿用当前从 popup 顶部偏中间位置展开的动画，popup 右侧可能先超出屏幕范围，然后被系统或定位 clamp 自动修正，导致实际看到的出现锚点仍偏左上。这个判断符合上一版 `202606070232` 仍未校准好的现象。

本次修改：`ColorPicker` 不再使用 `86%, 10%` 的内部 animation pivot，也不再使用 `0.25 * anchorSize` 的视觉补偿。`color_picker_show.xml` 和 `color_picker_hide.xml` 改为 `pivotX="100%"`、`pivotY="0%"`，让动画明确从 popup 右上角向左下展开。`ColorPicker.show()` 的定位公式同步改为将 popup 的 top-right corner 直接钉到 anchor view 的 window-relative center，并对 x/y offset 做 parent window 内的 clamp，避免 popup 右边先越界再被系统修正。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录 ColorPicker 从内部 pivot/视觉补偿模型切换到 top-right-corner 模型。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070239` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 与详情页改色 ColorPicker 的出现锚点。

## 2026-06-07 - ColorPicker popup 锚点右下校准

用户远程测试上一版 `202606070222` 后反馈：`ColorPicker` 的 popup 出现锚点仍然偏左上。因为本地无法稳定复现 Android `PopupWindow` 的实际动画视觉，只能通过代码级定位公式和用户反馈做小步校准；本轮没有改动 `DateTimePicker`。

本次修改：`ColorPicker.show()` 在读取 anchor view 的 window-relative center 后，额外将目标点向右下移动 `0.25 * anchor.width` 与 `0.25 * anchor.height`，再按 `color_picker_show` 的 popup animation pivot `(0.86, 0.10)` 换算为 `showAtLocation(...)` offset。这样保持 window-relative、anchor-driven 的定位模型，同时专门对冲 ColorPicker 目前看到的左上偏差。

同步更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录这次为 ColorPicker-only visual correction。`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070232` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点测试首页搜索模式 ColorPicker 与详情页改色 ColorPicker 的 popup 出现锚点。

## 2026-06-07 - 修正 ColorPicker / DateTimePicker popup 的 anchor 中心定位

用户反馈：`ColorPicker` 的 popup 现在看起来像是出现在对应 icon view 的左上角，而不是以该 view 的中心作为出现位置；`DateTimePicker` 的 popup 也有类似问题。用户希望两个 picker 都改为围绕对应 view 的中心来定位，并在修改后直接发布 debug update 方便远程测试。

本次诊断：复查了 `PopupPicker` 及其两个子类的 `showAtLocation(...)` 坐标语义。原实现仍混有旧的边缘/魔数定位规则：`ColorPicker` 以 toolbar action 的右边缘推导 x offset，`DateTimePicker` 的 quick-remind 分支让 popup bottom 贴到 anchor 中线，time-type 分支则仍有旧的 x/y 魔数。结合现有动画资源，新的规则改为读取 anchor 的 window-relative 中心点，并让 popup 自身动画 pivot 对齐到该中心点。

本次修改：
- `PopupPicker.kt` 新增共享 helper：测量当前 popup content 尺寸、读取 anchor center、根据 anchor center + popup animation pivot 计算并夹取 window-relative offset。
- `ColorPicker.kt` 删除剩余的 display-global fallback 坐标计算，不再使用 `getLocationOnScreen()` / `getDisplaySize()` 推导位置；有效 anchor 存在时按 `color_picker_show` 的 pivot `(0.86, 0.10)` 对齐到 anchor center，无有效 anchor 时仍兜底贴窗口右侧。
- `DateTimePicker.kt` 的 quick-remind 和 time-type popup 都改为按 anchor center + 对应动画 pivot 定位；quick-remind 分支保留 bottom system-bar inset 补偿，再把计算出的 popup top 换算为 `Gravity.BOTTOM` 所需的 y offset。
- 更新 `docs/features/popup-picker-insets/decisions.md` 与 `sessions.md`，记录本次规则修正：`DateTimePicker` quick-remind 不再以 “popup bottom = anchor center” 作为最终规则，而是以 anchor center 对齐 popup animation pivot。

验证与发布：`git diff --check` 已通过，仅有仓库既有 LF/CRLF warning；已执行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，发布 debug update `202606070222` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。请重点远程测试首页搜索模式的 ColorPicker、详情页改色 ColorPicker、详情页 quick-remind DateTimePicker，以及 DateTimeDialog 中时间单位 DateTimePicker 的 popup 出现位置。

用户最初反馈：昨晚通过 `WindowInsets` 修复了首页进入搜索/选择模式后
contextual toolbar 与 statusbar 区域的外观问题，但怀疑 app 里还有不少旧的
system bar 处理方式，例如直接估算 statusbar 或 navigation bar 高度，而不是读取
实机当前 window insets。

前置修正：上一轮已经继续修复首页 contextual toolbar 的 statusbar strip 退出动画。
`view_contextual_status_bar` 保持在 `rl_contextual_toolbar` 内，由父级统一控制
show/hide 和进入/退出动画，避免退出选择态时 statusbar strip 提前 invisible 造成闪烁。

本轮分析：用 `grill-with-docs` 方式核对了 `CONTEXT.md`、Android 16 migration 文档、
`home-contextual-toolbar` 与 `popup-picker-insets` feature docs，并扫描代码。结论是这次
应作为 App Chrome 的 system-bar inset 技术债清理，而不是引入
Everything-Android 的 `BaseActivity.latestWindowInsets` 架构。原因是当前 EverythingDone
已经有 `DisplayUtil.chainDecorInsetsCallback(...)`，并且它包含 IME animation 时序处理；
直接换成 Activity 基类协议会扩大改动面，并可能和现有 helper-based padding/margin 更新冲突。

本轮修改：
- 在 `docs/features/system-bar-insets/` 新增 feature 文档，记录分析、范围决定、follow-up
  与实现会话。
- 删除 `DisplayUtil.getStatusbarHeight(...)`、`DisplayUtil.getNavigationBarHeight(...)` 和
  `DisplayUtil.hasNavigationBar(...)`，不再保留旧的 system bar resource/display-size 估算 helper。
- 在 `DisplayUtil` 中新增 runtime top inset 相关 helper，用 `WindowInsetsCompat.Type.systemBars()`
  与 `displayCutout()` 读取当前窗口 inset。
- 将 `DisplayUtil.chainDecorInsetsCallback(...)` 从简单 list 改为 keyed callback map，让同一
  target/helper 的重复注册变成覆盖而不是累积，避免重复叠加 bottom inset。
- 更新 `DetailActivity` 和 `StatisticActivity`，由 statusbar spacer 的 inset callback 缓存当前
  top inset，并用于滚动阈值、actionbar shadow、image cover、bottom bar shadow 等计算。
- 更新 `ImageViewerActivity`，用 runtime top inset helper 设置全屏 viewer 的 actionbar top margin。
- 更新 `ThingsActivity`，移除对旧 statusbar resource height fallback 的依赖，改读当前 window top inset。
- 更新 `BaseThingWidgetConfiguration`，移除 configuration change 中重复注册 bottom inset margin 的路径，
  并在退出 preview/config 状态时清理 bottom inset margin helper。
- 更新 `ColorPicker` 与非 `AFTER_TIME` 的 `DateTimePicker` top-gravity popup placement，将旧
  statusbar height 读取替换为当前 top inset，保留原有 window-relative / multi-window 坐标模型。

验证状态：
- `rg` 确认 `app/src/main` 下已经没有 `getStatusbarHeight`、`getNavigationBarHeight`、
  `hasNavigationBar`、`status_bar_height`、`navigation_bar_height` 或未使用的
  `getCurrentBottomSystemInset` 引用。
- 沙箱内 `:app:assembleDebug` 曾因 Kotlin daemon 访问用户目录被拒绝而失败；提升权限后
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。
- 最终 `git diff --check` 已通过，仅有仓库已有的 LF/CRLF warning。
- 本次用户明确授权通过 Gradle `:app:publishDebugUpdate` 将 APK 与相关文件上传到阿里云服务器；
  已使用 `memory/debug-update-notes.md` 作为 debug update notes 发布 debug update `202606070204`。
  远端 debug channel metadata：`http://120.25.194.207/everythingdone-updates/debug/latest.json`。

建议测试重点：详情页滚动阴影与图片附件顶部 cover、统计页标题/FAB 变化、图片查看器 toolbar、
Widget 配置 preview 底栏、首页 contextual toolbar 的进入/退出 statusbar strip 动画，以及
ColorPicker / DateTimePicker 的 top popup 位置。
