# 自定义卡片外观 UI 隐藏后刷新首页 header 与 actionbar 阴影

本次 debug update 修正自定义记事卡片外观 UI 隐藏后，首页 header 展开/折叠状态和 actionbar 动态阴影可能仍按旧 RecyclerView padding 或旧卡片布局计算的问题。

实现修正：

- 更新 `ThingsActivity.kt`：
  - 在 Thing Card Appearance panel 实际隐藏并恢复 RecyclerView bottom padding 后，请求重新计算 ActivityHeader 状态；
  - 确认、取消、返回关闭和其它隐藏入口都会走同一条刷新路径；
  - 刷新操作 post 到 RecyclerView 下一帧执行，确保先等待 panel 消失、padding 恢复和卡片 relayout；
  - 重新读取当前 first visible position，并调用 `ActivityHeader.updateAll(..., false)` 同步 header 位置和阴影状态。
- 更新 `ActivityHeader.kt`：
  - `updateAll()` 每次重新计算时都会同步最新 actionbar shadow alpha 缓存；
  - 非动画刷新 actionbar shadow 时会先取消仍在运行的 shadow 动画，再写入新的 alpha，避免退出 selecting mode 时旧动画覆盖新阴影状态。

验证状态：

- `git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。
- 已发布 debug update `202606060339` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

# 小组件调整大小后重新投影媒体

本次 debug update 让桌面小组件在 launcher 调整大小后直接重新生成 RemoteViews 和媒体 bitmap。

实现修正：

- 单一记事 widget：
  - 增加 `onAppWidgetOptionsChanged()`；
  - launcher 尺寸 options 变化后复用正常 widget 更新路径；
  - 重新读取新的 widget 宽高 options，并重新渲染封面、sidePanel 和 mediaBackground bitmap。
- 记事列表 widget：
  - 增加 `onAppWidgetOptionsChanged()`；
  - launcher 尺寸 options 变化后先刷新 collection row 数据；
  - 再更新外层 RemoteViews，让可见行按新的 widget 宽高 options 重新生成。

验证状态：

- `git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。
- 已发布 debug update `202606060214` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

# 小组件媒体投影 clamp reason 日志

本次 debug update 给 AppWidget 媒体投影的关键 clamp / guardrail 边界补充结构化日志，方便后续排查小组件图片比例、裁切和行高问题。

实现修正：

- 更新 `AppWidgetHelper.kt`：
  - widget content width 被 RemoteViews bitmap 最大尺寸限制时，记录 `remote-bitmap-max-dimension`；
  - Things List widget 的 media-background target height 被内容自然高度、最大背景高度或 hard height 影响时，记录对应 reason；
  - Things List widget 的 media-background bitmap 被最大边长或像素预算缩小时，记录 `list-media-background-max-bitmap-dimension` / `list-media-background-pixel-budget`；
  - sidePanel target ratio 无效或被远程渲染边界归一化时，记录 ratio reason；
  - sidePanel media width 被 min/max width guardrail 限制，或列表投影只能取 best-effort 结果时，记录对应 reason。

验证状态：

- `git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。
- 已发布 debug update `202606060201` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

# 记事列表 widget 背景图裁切一致性修正

本次 debug update 修正 Things List widget 中 `mediaBackground` 记事的背景图可能被拉伸的问题。

用户反馈与诊断：

- 用户观察到同一个 `mediaBackground` 记事，在单一记事 4x2 widget 中可以保留裁切中心并上下裁切，但在记事列表 widget 中会变成拉伸。
- 诊断确认：单一记事 widget 的背景 bitmap 是按固定 widget surface 渲染的，`fitXY` 不会改变已经烘焙好的裁切结果。
- 诊断确认：Things List widget 的行高由文字内容撑开；此前背景 bitmap 会按 `mediaBackground.targetAspectRatio` 渲染成较高的目标面，但列表行实际高度可能很短，最终被 `fitXY` 压缩到短行里，表现为拉伸。

实现修正：

- 更新 `AppWidgetHelper.kt`：
  - Things List widget 的 `mediaBackground` target height 改为 `max(保存比例投影高度, 估算内容自然高度)`；
  - 当列表行使用 `mediaBackground` 时，通过 `RemoteViews.setInt(..., "setMinimumHeight", ...)` 给 row root 设置最小高度；
  - 背景 bitmap 仍按同一个目标面渲染，再应用列表 widget 的像素预算等比缩小；
  - 非 `mediaBackground` 列表行会显式恢复 root `minimumHeight = 0`，避免 collection row 复用时残留高度。

验证状态：

- `git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。
- 已发布 debug update `202606060130` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

# 裁切 dialog 比例条与列表 widget 背景图保护

本次 debug update 修正两个问题：裁切封面图片/视频 dialog 的比例条显示不完整，以及 Things List widget 中图片作为背景的记事可能导致该行及后续行不显示。

用户反馈与诊断：

- 用户指出裁切 dialog 里不是所有 presentation 模式都有比例条，应统一显示；视频源应先显示视频帧拖动条，再显示宽高比例拖动条。
- 用户指出 Things List widget 中图片作为背景的记事以及之后的记事可能不显示，要求判断是实现 bug 还是 bitmap 过大导致的 widget 内存/传输限制。
- 诊断确认：crop dialog 原先通过 `canResizeThingCardCropEditorFrame()` 在 `mediaBackground` 和 full-span sidePanel 下隐藏比例条，这是旧逻辑遗留。
- 诊断确认：Things List widget 的 media-background bitmap 上限按 `720dp x 360dp` 再乘 screen density 计算，在高 density 设备上可能生成数 MB 级单行 bitmap。RemoteViews collection 对每行 bitmap 传输/缓存很敏感，某一行过大时确实可能导致该行和后续行不显示。

实现修正：

- 更新 `ThingsActivity.kt`：
  - 移除 crop dialog 的 presentation 特殊隐藏逻辑；
  - 所有 presentation 模式下都显示 ratio slider；
  - 视频源继续先显示 video frame slider，ratio slider 放在其下方；
  - 确认裁切时始终保存当前 presentation 的 `targetAspectRatio` 和 crop。
- 更新 `AppWidgetHelper.kt`：
  - Things List widget 的 media-background target bitmap 增加像素预算；
  - 宽高任一维超过 `960px` 或总像素超过 `240000` 时按比例缩小；
  - 如果 media-background 渲染发生 `OutOfMemoryError` 或其它异常，降级为普通 widget 背景，不再让该行渲染失败影响后续行；
  - 加入 `Clamp widget media background...` 和 `Skip widget media background...` 日志，方便用 logcat 确认是否命中 bitmap 限制或降级。
- 更新 `docs/features/thing-card-media-target-geometry/execution.md` 和 memory，记录 ratio slider 显示策略与 list widget bitmap 保护。

验证状态：

- `git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。
- 已发布 debug update `202606051623` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

# sidePanel 封面图片宽度拖动条

本次 debug update 在自定义记事卡片外观的 sidePanel 模式下新增第二个拖动条：`封面图片宽度`。

用户反馈与目标：

- 用户希望在 sidePanel 模式下，除了 `封面图片比例` 之外，再提供一个位于其下方的宽度拖动条。
- 这个宽度表示封面图片宽度占整个记事卡片宽度的百分比。
- 两个拖动条需要互相更新：调比例会更新宽度，调宽度也会更新比例。
- 宽度调整同样不能让拖动条刻度或 progress 闪烁。

实现修正：

- 更新 `panel_thing_card_appearance.xml`：
  - 复用旧 side-width row，但移动到 `封面图片比例` 控件下方；
  - 文案改为 `封面图片宽度 · %1$d%%`。
- 更新 `ThingsActivity.kt`：
  - 宽度拖动条只在 active presentation 为 `sidePanel` 时显示；
  - 宽度拖动条不再写 legacy `sideMediaWidthPercent`；
  - 宽度百分比会通过 sidePanel projection 换算为 `sidePanel.targetAspectRatio`，继续保持 target ratio 是 canonical source；
  - ratio slider 和 width slider 共用同一套 sidePanel projection helper，因此二者互相更新时使用一致的内容测量和 min/max guardrails；
  - 拖动宽度 slider 时也冻结 active ratio range，避免 rebind preview 时造成 progress/ticks 重新映射。
- 更新 string resources：
  - 默认和非中文 locale 文案改为 `Cover image width · %1$d%%`；
  - 简中为 `封面图片宽度 · %1$d%%`；
  - 繁中/香港为 `封面圖片寬度 · %1$d%%`。
- 更新 `docs/features/thing-card-media-target-geometry/execution.md` 和 memory，记录此控件是 `sidePanel.targetAspectRatio` 的 alternate projection control，而不是恢复旧的 `sideMediaWidthPercent`。

验证状态：

- `git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。
- 已发布 debug update `202606051559` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

# sidePanel target ratio 稳定投影修正

本次 debug update 继续修正自定义记事卡片外观中 `sidePanel.targetAspectRatio` 的布局反馈问题。

用户反馈与诊断：

- 用户指出 left/right sidePanel 模式下，调整图片 target ratio 会改变图片列宽，进而改变右侧内容列宽、文字换行和卡片高度；卡片高度变化后又会反过来影响图片宽度，形成循环。
- 这个循环会导致 ratio slider 的刻度和 progress 映射在拖动中变化，有时出现跳变。
- 首页预览里还存在一个直接溢出点：post 之后根据内容高度修正图片宽高时，只更新了 `flImageAttachment`，没有同步更新 `llTextContent.width`，因此图片变宽后两列总宽可能超过卡片宽度。
- 小组件 Things List 路径之前只有两步估算，在文字换行阈值附近也可能不是真正稳定的 projection。

实现修正：

- 更新 `BaseThingsAdapter.kt`：
  - 新增 side image projection，统一求出 `imageWidth`、`imageHeight` 和 `textWidth`；
  - projection 通过有限迭代近似 `imageWidth = targetRatio * measuredContentHeight(textWidth)` 的固定点；
  - 首次布局和 post 后修正都同步应用图片列宽、内容列宽和图片高度；
  - 删除旧的 side image height cache 和旧的 `getSideImageWidth()` 高度反推入口，避免重新引入 live-height 反馈。
- 更新 `ThingsActivity.kt`：
  - sidePanel ratio slider 的 min/max 不再读取当前 side image View 高度；
  - range 改为从 side media 最小/最大宽度 guardrails 和当前绑定内容的离屏测量高度推导；
  - 主面板 ratio slider 和裁切弹窗 ratio slider 在拖动期间冻结当前 range，避免拖动中 rebind preview 导致 ticks/progress 重新映射；
  - sidePanel legacy ratio fallback 不再从当前 side image View 比例读取，而是从 legacy `sideMediaWidthPercent` 和内容测量推导。
- 更新 `AppWidgetHelper.kt`：
  - Things List widget side media 从两步估算改为有限迭代 projection；
  - single-Thing widget 继续使用 widget 高度作为稳定高度预算，并套用同一 min/max side media width guardrails。
- 更新 `memory/followups.md`：把 side-panel target-ratio layout solving 从 deferred 标记为 resolved，并保留手动视觉验证风险。

验证状态：

- `git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。
- 已发布 debug update `202606051547` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

# 记事卡片媒体 target ratio 与 widget 投影修正

本次 debug update 用于发布自定义记事卡片外观的媒体几何模型更新：把封面比例、侧边图片宽度、背景高度统一到 per-source、per-presentation 的 `Thing Card Media Target Aspect Ratio` + crop 模型，并修正小组件 left/right 图片不尊重目标比例的问题。

用户反馈与诊断：

- 用户发现 4x4 单一记事小组件中，宽记事左侧图片在自定义外观设置为 42% 时看起来没有达到预期宽度，即使右侧内容很短、理论上有空间。
- 诊断确认旧的单一记事小组件路径会用 `sideMediaDisplayAspectRatioHint` 按 widget 高度反推图片宽度，导致保存的 side width 可能被首页卡片测得的窄比例覆盖。
- 随后用户希望统一外观 UI 和说法：侧边图片宽度、背景卡片高度都改为和封面图片比例一样的 target ratio 概念；crop center 和 crop zoom 继续独立保存。
- 用户进一步要求 target ratio 尽量优先，min/max 只作为内容可读性和平台限制的 guardrails；切换 presentation 时可从旧 presentation 派生，但取消不持久化，切回原 presentation 应恢复原状态。
- 审查后继续修正三点：confirm 路径命名不清楚、sidePanel 首次初始化应优先反映 legacy `sideMediaWidthPercent`、`getThingCardMediaBackgroundTargetMinHeight` 命名误导。

实现修正：

- 更新 `ThingCardAppearance.kt`：
  - 新增 `version = 2` 的 nested `presentations`，包含 `thumbnail`、`sidePanel`、`mediaBackground`；
  - 每个 presentation 保存 `targetAspectRatio` 和 crop；`mediaBackground` 额外保存 `maskStrength`；
  - legacy 字段仍可读取，但新序列化不再写 `sideMediaWidthPercent`、`thumbnailCrop.sourceAspectRatio`、`mediaBackgroundHeightRatio`、`backgroundCrop`、`mediaBackgroundMaskStrength`、`sideMediaDisplayAspectRatioHint`；
  - `hasSamePresentationAs` 改为比较规范化 JSON，避免 legacy 兼容字段影响 presentation-change 判断。
- 更新 `ThingsActivity.kt`：
  - 设置面板复用现有 ratio slider，根据当前 presentation 绑定 `thumbnail`、`sidePanel` 或 `mediaBackground`；
  - 旧 side-width row 和 background-height row 不再作为主 UI 显示，背景 mask 继续保留；
  - presentation 切换时非破坏性 seeding，缺失目标 presentation 时从旧 presentation 或 legacy side width 派生；
  - sidePanel 首次 materialize 时优先使用 legacy `sideMediaWidthPercent` 推导 target ratio；
  - confirm 路径改为显式 `materializeThingCardPresentationsForConfirm()`，并在删除 legacy JSON 字段前保留非默认 legacy side width 到 `sidePanel`；
  - crop editor 读写 active presentation 的 target ratio 和 crop，避免 thumbnail、sidePanel、mediaBackground 互相覆盖。
- 更新 `BaseThingsAdapter.kt`：
  - 首页 top/bottom、left/right、mediaBackground 渲染改为读取 active presentation target ratio/crop；
  - side panel 按 `sidePanel.targetAspectRatio` 投影，再套用原有 side-width min/max guardrails；
  - `getThingCardMediaBackgroundTargetMinHeight` 重命名为 `getThingCardMediaBackgroundClampedTargetHeight`，准确表达其职责是 desired height 的上限 clamp，真正自然内容高度仍由 effective height 函数处理。
- 更新 `AppWidgetHelper.kt` 和 `RemoteThingCardMediaRenderer.kt`：
  - 小组件和 remote bitmap 渲染支持传入 presentation；
  - left/right widget media 从 `sidePanel.targetAspectRatio` 和 `sidePanel.crop` 投影；
  - `sideMediaDisplayAspectRatioHint` 不再被 widget 读取或写入。
- 更新文档：
  - 新增 ADR 0003 和 `docs/features/thing-card-media-target-geometry/plan.md` / `execution.md`；
  - 旧的 `docs/features/thing-card-appearance/plan.md`、`docs/features/remote-thing-card-appearance/plan.md`、ADR 0002 标记为 media geometry 已由 ADR 0003 supersede；
  - execution checklist 已同步端点标签策略：动态 min/max 不显示非常见比例文字，只有落在已有 preset tick 上的常见比例会自然显示。

验证状态：

- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。
- `git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。
- 最新 debug update 发布状态见文件顶部。

# 记事列表 widget 左右侧图片几何修正

本次 debug update 用于修正自定义记事卡片外观移植到 widget 后，Things List widget 和单一记事 widget 在 left/right 媒体位置下出现的图片拉伸、宽度估算偏大和 side media 高度来源错误。

用户反馈与诊断：

- 记事列表 widget 中，图片放在左/右时没有稳定占满卡片整行高度，显示比例也会被拉伸。
- 普通单一记事 widget 的 left/right 图片也存在类似风险。
- 用户给出的根因分析指出：
  - Things List widget 行宽 fallback 使用固定 320dp，可能大于实际 3x3 widget 宽度，导致 `sideMediaWidthPercent` 投影到 widget 后占比偏大；
  - `getThingsListWidgetSideMediaSlotTargetHeight()` 在没有 `sideMediaDisplayAspectRatioHint` 时使用了基于源图宽高比的 thumbnail 高度公式，这个公式只适合 top/bottom 缩略图，不适合 left/right full-height side panel；
  - left/right `ImageView` 使用 `fitXY`，当预渲染 bitmap 尺寸和 RemoteViews 最终 layout 尺寸略有不一致时，会产生非等比拉伸。

实现修正：

- 更新 `AppWidgetHelper.kt`：
  - `createRemoteViewsForThingsListItem()` 现在会根据 `appWidgetId` 反查实际 Things List provider class，不再硬编码 `ThingsListWidget`；
  - widget 默认宽高估算同时支持 single-Thing 和 Things List provider cell span；
  - list widget 的 fallback 宽度不再使用单一 320dp，而是优先使用 launcher options，缺失时回落到 provider preset 对应的 cell 宽度；
  - Things List left/right side media 在没有 `sideMediaDisplayAspectRatioHint` 时，不再用源图片宽高比反推高度，而是按标题、正文、checklist、音频数量、提醒、习惯、状态等内容预估整行自然高度；
  - 预估高度只受 RemoteViews bitmap hard cap 限制，不写回数据库里的卡片外观属性。
- 更新 `app_widget_item_thing.xml` 和 `app_widget_thing.xml`：
  - left/right side media `ImageView` 从 `fitXY` 改为 `centerCrop`，避免 RemoteViews 最终尺寸和 bitmap 尺寸轻微不一致时出现非均匀拉伸。
- 保留上一轮已经实现的 `sideMediaDisplayAspectRatioHint` 路径：
  - 如果保存外观时已经拿到首页卡片的实际 side media 显示比例，widget 仍优先使用该 hint；
  - 如果 hint 不存在，则使用本次新增的内容高度投影 fallback。

验证状态：

- `git diff --check` 已通过，仅有仓库既有 LF/CRLF warning。
- sandbox 内第一次运行 `:app:assembleDebug` 时，Kotlin daemon 临时目录访问被拒绝；按项目规则提权重跑同一命令后，`assembleDebug` 已通过。
- 已重新发布 debug update `202606051300` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。此前同一轮的 `202606051259` 只用于中间发布，latest 已由 `202606051300` 覆盖。
