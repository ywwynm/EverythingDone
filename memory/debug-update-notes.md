修复记事背景上的前景对比度遗漏

用户需求：
- DetailActivity 里的 ThingDoingDialog 底部“开始做这件事”按钮使用了当前记事的 ThingBackground，但按钮文字仍是固定白色，浅色记事背景下不够清晰。
- 做全面排查时，额外发现的候选遗漏点要先列出来给用户确认，确认后再改。
- debug update notes 以后要用中文写；代码、文件名、Gradle task、class 名等技术固有名词按需要保留英文。

分析：
- 静态检查了真正把 ThingBackground 或记事 accent 画成背景的代码路径，再核对这些 surface 上是否有文字或 icon 使用亮度自适应的前景色。
- DetailActivity 主体、NoticeableNotificationActivity 内嵌记事卡片、AppWidget 卡片，以及仅作为预览色块的 gradient preview 已经有合适的前景逻辑，或者没有承载可读前景内容。
- 和用户确认后，本次纳入两个明确遗漏：ThingDoingDialog 底部 action card，以及 DateTimeDialog 重复 tab 中由 RecurrencePickerAdapter 绑定的已选日期/星期/月和“月底”pill。

修改：
- fragment_thing_doing.xml：给底部 action label 增加 tv_start_doing_as_bt_dialog，便于代码里按当前 ThingBackground 更新前景色。
- ThingDoingDialogFragment.kt：底部 CardView 继续使用当前 ThingBackground；内部 label 改为 BackgroundUtil.onColor(..., BackgroundUtil.ON_ALPHA_PRIMARY)，并把固定 XML ripple 替换为根据记事背景代表色亮度生成的 Thing-owned rounded ripple。
- RecurrencePickerAdapter.kt：新增 pickedTextColor()，用 BackgroundUtil.onColor(mAccentColor, BackgroundUtil.ON_ALPHA_PRIMARY) 替代选中态固定 Color.WHITE，覆盖普通 recurrence cell 和 end-of-month pill。
- memory/preferences.md：记录“广泛 UI sweep 发现额外候选遗漏时先汇报、确认后再改”的偏好，以及 debug update notes 默认用中文的偏好。
- .agents/rules/gradle.md：补充 debug update notes 默认用中文，并记录 PowerShell 下 -PdebugUpdateNotesFile 要使用 quoted forward-slash 路径，例如 -PdebugUpdateNotesFile=memory/debug-update-notes.md，避免反斜杠路径被 Gradle 误解析成额外 .md task。

验证：
- git diff --check 通过，仅有既有 CRLF conversion warnings。
- 使用中文 notes 重新执行 `:app:publishDebugUpdate` 通过，发布 debug update `202605290114` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。