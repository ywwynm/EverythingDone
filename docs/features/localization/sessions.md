# Localization Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-10-13 — 补交本地化 debug 发布日志

- 按用户要求补交 `cdfa0d254` 遗漏的 10 份 2026-10-11 本地化 debug 发布日志，以及 `memory/debug-update-notes.md` 中对应的发布汇总。
- 原提交将“发布回填不自动创建提交”误读为“已授权的功能提交也排除发布日志”，与既有的日志纳入版本控制规则冲突；已澄清全局提交偏好，相关日志应随用户授权的功能提交纳入。
- 本次仅补充文档，核对日志文件、暂存范围和差异格式；应用验证沿用已验收结果，无新增构建或设备操作。

## 2026-10-11 — 用户确认提交本轮本地化修改

- 用户确认提交上述最终版本。提交范围包含翻译补齐、产品术语统一、动态语法和日期处理、中文原文及各语言标点恢复，以及本功能的检查脚本和验证记录。
- 验证沿用已验收的 debug `202610110644`：三机各 5,896 项通过，13 套资源无缺项，中文原文基线和术语检查通过。本次提交不新增应用代码改动，不重复构建或运行设备验证。
- 其它功能的工作区修改保留；原提交曾将 `memory/debug-update-notes.md` 中的本地化汇总和本功能 `debug-updates/` 发布日志排除，2026-10-13 按用户要求补交并纠正提交规则的误读。

## 2026-10-11 — 全面恢复中文原文与各语言列表标点

- 用户进一步要求复核全部中文变化，不能将保留中文原意理解为允许润色；英文原标点列举及各语言的数量、文件夹操作范围均使用标点。偏好已明确记录。
- 对照固定的改造前版本检查 4,338 个已有中文资源；74 处变化恢复 65 处，保留 3 处明确术语和 6 处已授权的繁体缺项修复。动态代码恢复日期周期、分享庆祝、目标状态、更新前缀、统计说明、导出路径、百分号和时长空格，中文延时界面恢复原表达。
- 新增中文原代码对照及各语言标点断言。发现并修复百分比切换语言后使用旧 Application 资源的问题；香港繁体原统计句的逗号单独保留，修正测试预期。
- debug `202610110644` 已发布、安装到原 OPPO、三星、魅族，各 5,896 项通过；13 种语言均无资源缺项，中文基线与术语检查通过，数据库及语言恢复一致，公开包和三机安装包散列一致。未创建 Git 提交。
- [完整处理范围与验证证据](chinese-original-and-punctuation-2026-10-11.md)。数量验证通过实际共用格式化入口，没有新建用户样例或在桌面另放小组件。

## 2026-10-11 — 按用户纠正恢复中文数量格式

- 用户指出首页数量摘要被改成用“和”连接，并要求保留中文原有内容；确认附件也被修改了空格、视频量词和逗号。原因是将外语语法修复错误扩大为中文格式统一，先前验证未以原中文显示为回归基准。
- 中文文件夹计数恢复既有资源，覆盖首页、文件夹卡片、小组件；图片、视频和音频恢复原有格式，文件夹操作范围恢复顿号，外语规则保持。将中文原文保护要求写入功能偏好。
- 新增三套中文的精确输出断言。最终 debug `202610110612` 发布并安装到原三机，各 5,101 项通过，数据库与语言一致，公开和安装包散列一致。OPPO 首轮探针初始化竞态已修复并保留记录；生产修复未因此改动。
- 详细范围、原文基准和证据见 [恢复记录](chinese-count-format-restoration-2026-10-11.md)。未创建 Git 提交。

## 2026-10-11 — 完成报告修订、动态语法与三机验收

- 按用户授权处理审查报告的全部 30 组、333 个语言条目：332 条修订，德语正确总称保留。统一 finish / finished、Thing / Note 等产品术语，修正错义、损坏文本、帮助与功能限制说明，保留中文原意。
- 修改 `LocalizedText` 的时间介词选择与句内周数；`Habit`、分享和每日待办帮助共用正确入口。统计行支持长标签换行并避让数值。保持 24 小时制和应用语言日期顺序。
- 13 套语言、1,600 个中文可翻译资源静态检查通过；完整界面验证合并覆盖全部语言。截图继续修正俄语完成率及俄、德、西、法输入提示。
- 最终 debug `202610110559` 已发布并覆盖安装到原三台设备：OPPO 5,153 项、三星及魅族各 5,067 项通过；逐表数据库摘要及语言恢复一致，公开包与三机安装包 SHA-256 相同。临时样例不保存为用户记事，未创建 Git 提交。
- [修订与验证记录](wording-fix-2026-10-11.md) 保留全部处理证据、首轮 USB 遮挡、探针时钟边界、截图时序修正和设备验证边界。其他功能的既有工作区改动保留。

## 2026-10-11 — 多语言产品术语与表达审查

- 根据用户新要求，将英语完成记事使用 `finish / finished` 写入本功能偏好与根目录领域术语。
- 扫描全部 13 套资源，交叉检查核心类型、状态、操作、统计、无障碍描述、新动态模板和高风险说明；结合生产代码、布局、菜单引用及上一轮设备样例判断影响。
- 整理 [30 组问题与建议](wording-audit-2026-10-11.md)，[333 个去重语言条目的证据](wording-audit-evidence-2026-10-11.json) 包含当前译文、中文基准、行号与引用。明确区分错义、术语、润色、动态语法和旧资源，未把全部条目视作当前可见缺陷。
- 承认并单列上一轮新增模板的 finish、中文类型名和句内语法遗漏；记录后续修订、三机语言场景验证及母语审校范围。
- 本轮仅更新领域/本地化文档及审查证据，没有修改生产代码或翻译资源，没有重新操作设备、发布或提交。

## 2026-10-11 — 完成缺项补译、动态文案与三机验证

- 补齐 2,540 处语言资源缺项，并处理卡片外观英文残留及繁体历史确认文案；当前 1,597 个中文可翻译资源在其余 12 套配置中零缺项。
- 新增共用格式化入口及完整句式、复数资源，修复数量英语词尾、分享语序、周期日期、统计时长、快捷提醒标签等代码问题；按用户决定保留 24 小时制并使用应用语言的日期顺序。
- 构建与 17 项清单单测通过；OPPO、三星、魅族正式合并验证各 4,403 项通过，逐表摘要及语言设置一致；页签缩短后在魅族完成定向复核。首次探针限制、完整证据和测试边界见 [执行记录](execution-2026-10-11.md)。
- 最后复核保留中文祝贺原意，魅族 4,379 项检查通过。已发布 debug `202610110252`，公开元数据及 APK 大小、散列校验一致；未创建 Git 提交，未改动空间照片会话文件。

## 2026-10-11 — 补译前检查动态文案与语序

- 按用户调用的 `grill-me` 检查 XML 以外的实现，确认附件数量、时间单位、导出结果等仍给所有非中文语言追加英语词尾，分享标题固定拼接词序，周期提醒与统计说明硬编码英语。
- 读取实际日语和俄语资源复现了 `2 画像s`、`2 изображениеs` 等组合结果，并核对首页卡片、桌面小部件、通知、分享和导出的真实调用链；未连接设备。
- 用户明确选择一起修复全应用中发现的动态文案问题，不限于前一天的资源缺项清单。已记录决策和[实现风险检查](implementation-review-2026-10-11.md)。
- 用户随后确定保留 24 小时制、日期顺序随应用语言，并要求开始实施和验证三台设备（OPPO、三星、魅族）。生产代码、全部缺项译文及动态句式已修改；资源静态检查和 debug 构建通过，设备验证进行中，详见[执行记录](execution-2026-10-11.md)。

## 2026-10-10 — 以简体中文为基准检查全部其他语言

- 解析主应用全部语言目录的文本 XML，包括 `strings.xml` 和 `tap_update.xml`；排除明确不可翻译项后，以 1,541 个简体资源键检查其他 12 套资源。
- 英文无缺项；香港、台湾繁体各缺 136 个，九种其他外语各缺 252 个。去重为 259 个缺失键，其中 201 个发现直接引用、58 个未发现；11 个属于可共用的技术名称或标识。
- 另发现九种外语共同沿用英文的 30 个卡片外观键，以及两套繁体未同步中文信息和参数的 3 个历史确认文案。数组、复数资源和碰一碰更新文案的资源键齐全，无空条目。
- Python 与 PowerShell 两套 XML 解析独立复核得到相同缺项数；259 个清单行与源文件键名、行号逐项对应，文档差异检查通过。
- [完整报告](coverage-audit-2026-10-10.md) 包含逐语言和逐功能统计、全部缺失键的中文原文与行号，以及共用标识和引用情况。仅更新本地化文档，未改翻译资源、连接设备、构建、发布或提交。

## 2026-07-06 - 计时数字风格设置项补齐多语言

- 按用户反馈修正 `settings_doing_digit_style`：默认英文从 `Countdown digit style` 改为 `Timer digit style`，避免把 Doing / 录音等计时读数误限定为倒计时。
- 为德语、西班牙语、法语、印地语、意大利语、日语、韩语、葡萄牙语、俄语、繁体中文香港和繁体中文台湾补齐 `settings_doing_digit_style`，并同步补齐同一弹窗的 `doing_digit_fill` / `doing_digit_outline`。
- `:app:assembleDebug --console=plain` 通过；已随 timely digit typography 功能日志发布 debug update `202607051620`。

## 2026-06-27 - 英文界面标题大小写与抽屉头图操作弹窗修正

- 修正抽屉头图设置入口英文文案：`settings_change_drawer_header` 从 `Drawer Header Image` 改为 `Drawer header image`，遵循设置界面普通条目的句首大写规则；同时将该弹窗内的 `Choose Image` / `Adjust Crop` 调整为句首大写。
- `fragment_two_action_picker.xml` 与 `fragment_three_action_picker.xml` 的操作单元改用统一的 `app_chrome_action_picker_cell_width=108dp`，并允许最多两行显示，避免 `Choose image` 以及其它较长语言在 100dp 图标画布宽度内贴边。
- `fragment_thing_background_editor.xml`、`panel_thing_card_appearance.xml`、`view_thing_background_editor.xml` 的相关标题使用 `android:textAllCaps="true"`：英文下外观 panel 标题、调整颜色 panel 标题，以及颜色编辑器 `Pure` / `Gradient` tab 均显示为大写；中文等无大小写语言不受影响。
- 验证：`:app:assembleDebug --console=plain --no-configuration-cache` 通过；`git diff --check` 仅提示仓库既有 LF/CRLF 规范化警告；已发布 debug update `202606261612`。

## 2026-06-26 - 详情颜色调整文案国际化

- 将 `act_change_background` 从“改变颜色”语义改为“调整颜色”，并新增 `act_adjust_color` 给颜色编辑面板标题使用；`act_select_color` 保留“选择颜色”语义，避免影响搜索菜单。
- 更新默认英文、简体中文、繁体中文（香港/台湾）、德语、西班牙语、法语、印地语、意大利语、日语、韩语、葡萄牙语、俄语资源，避免详情颜色入口和面板标题在非中文 locale 回退到旧语义。
- 验证：`:app:assembleDebug --console=plain` 构建通过；随后 `:app:publishDebugUpdate` 发布 debug 更新 `202606261525`，资源键拆分后重新发布 `202606261527` 成功。

## 2026-06-15 - Card appearance action label rename

- Renamed the user-visible card appearance action from "Customize card
  appearance" / `自定义卡片外观` to "Adjust thing card appearance" /
  `调整记事卡片外观`.
- Updated default English, Simplified Chinese, Traditional Chinese, German,
  Spanish, French, Hindi, Italian, Japanese, Korean, Portuguese, and Russian
  `act_customize_card_appearance` resources.
- The resource key was kept unchanged to avoid unnecessary code churn in the
  existing Thing Card Appearance entry logic.
- Verification: the debug publish task completed successfully, which compiled
  the updated string resources and generated locale config.
- Follow-up: aligned `thing_card_appearance_panel_title` with the renamed entry
  label in the same supported locales, so opening the panel now shows the same
  "Adjust thing card appearance" / `调整记事卡片外观` wording as the contextual
  action.
- Follow-up verification: the debug publish task completed successfully and
  published update `202606150309`, compiling the updated panel-title resources.

## 2026-05-27 - ThingsActivity header collapse centering

Committed the completed localization/language-switching work as `ebeb9aa`.

Investigated the legacy ThingsActivity header issue where the title/subtitle
collapse appeared vertically centred in the toolbar for Chinese and English but
drifted in other locales or on some devices. Root cause: `ActivityHeader`
converted the first-card scroll distance into header `translationY` through
hard-coded density factors keyed to assumed toolbar heights. Those factors do
not account for locale-dependent text metrics, fallback fonts, or font/device
differences.

Updated `ActivityHeader` so the collapsed endpoint is measured from live view
geometry: toolbar centre minus the scaled title centre. The existing scroll
distance and scale timing are preserved, but the final translation endpoint now
tracks the actual title and toolbar layout. The endpoint is recomputed after
header text updates.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed and produced
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 18:27:12`.
- `git diff --check` passed with CRLF warnings only.
- No device visual smoke test was run for the header alignment in this step.

## 2026-05-27 - App language support and language-selection fix

Added app language support for Japanese, Korean, Italian, Spanish, Russian,
French, German, Hindi, and Portuguese. The resource work was corrected to use
`values-zh-rCN/strings.xml` as the translation source after the Google
Translate batch attempt produced mixed Chinese/token artifacts in long Help
strings. The default English Help text was also translated from the Simplified
Chinese source so non-Chinese locale fallbacks no longer expose Chinese Help
content.

Fixed Settings language selection by comparing stored language codes instead
of displayed names, then syncing AppCompat per-app locales from the stored
preference. Added base-context locale wrapping for the Application, the common
base Activity, and AppCompat entry activities that do not inherit that base.
Enabled AGP generated locale config and added `resources.properties` with
English as the unqualified resource locale.

Verification:
- Cleared leftover translation protection tokens from the generated locale
  resources.
- `.\gradlew.bat :app:assembleDebug --console=plain` passed and produced
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 18:04:29`.
- `git diff --check` passed with only the repository's existing CRLF warnings.
- No device UI smoke test was run for the language picker or per-screen locale
  switching in this step.

## 2026-06-14 - Detail attachment appearance strings

- Added media-specific Detail attachment appearance strings for default
  English, Simplified Chinese, Traditional Chinese, German, Spanish, French,
  Hindi, Italian, Japanese, Korean, Portuguese, and Russian resources.
- The new keys cover image/video-specific dialog titles and image/video ratio
  labels, and the non-default locale resources also received the existing
  Detail attachment appearance row labels so the editor does not partially fall
  back to English in those locales.
- Verification: `:app:assembleDebug` initially caught unescaped French
  apostrophes in `l'image`; after escaping them as Android string resources,
  assemble and debug publish both passed.

## 2026-06-14 - Detail video width and cover video ratio strings

- Added `detail_attachment_media_appearance_video_display_width` so video
  attachment width controls can display `视频显示宽度`.
- Added `thing_card_appearance_thumbnail_video_shape` so the home Thing Card
  precise crop dialog can use a video-specific cover-ratio prompt.
- Updated default English, Simplified Chinese, Traditional Chinese, German,
  Spanish, French, Hindi, Italian, Japanese, Korean, Portuguese, and Russian
  resources. The newly added video cover-ratio prompt is localized in each
  existing non-default locale instead of relying on the default English text.
- Verification: `git diff --check` passed with CRLF warnings only, and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  completed successfully.
- Published debug update `202606141559`.
