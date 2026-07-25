# Sessions

Global startup session index only. Detailed feature history lives in `docs/features/<kebab-case-feature-slug>/sessions.md`.

## 2026-07-26 - DialogFragment 全量迁到 AndroidX

- `BaseDialogFragment` 由 `android.app.DialogFragment`（API 28 起弃用）迁到
  `androidx.fragment.app.DialogFragment`，27 个对话框随基类一次生效；宿主侧 103 处
  `fragmentManager` 改 `supportFragmentManager`，对话框内部 3 处改 `parentFragmentManager`，
  7 个 helper/adapter 的 `Activity?` 签名改 `FragmentActivity?`。
- 关键坑：4 个 Activity 里 5 处 `is / as? android.app.DialogFragment` 与 androidx 类型不相交，
  编译器既不报错也不告警，`as?` 恒为 null 会让三处 `dismissAllowingStateLoss()` 静默失效。
  这类迁移的收尾必须 grep 旧包名，不能以编译通过为准。
- 详情见 `docs/features/androidx-dialogfragment-migration/`。真机未验证。

## 2026-07-17 - 未跟踪产物分类清理与调研归档

- 将 `tmp/` 下 9 个 `timely-*` 字体调研中间产物目录移入 `docs/features/timely-digit-typography/`；删除 `tmp/` 中与已提交 `research-2026-07-12-*` 三篇内容完全相同的重复副本（仅换行符差异，已逐一比对）。
- FableSol 2026-07-16 水体质感调研（md + 12 张 SVG 配图）移入 `docs/features/audio-visualization-fable-sol/research-2026-07-16-water-quality-uplift-by-opus/`，md 对配图的相对引用保持有效。
- `.gitignore` 新增：`/Everything-Android/`（独立 git 仓库，clone 自 GitHub 同名 repo）、timely 调研目录、fable-sol 三个调研目录（by-opus 版、LaTeX 版 15.8MB、ultra 版 215.5MB）。
- 遗留：`tmp/` 仍有 4 个 FableSol 测试日志（`android_optics_tests.txt` 等），`on-device-perf-testing.md` 未跟踪待提交，均由用户后续处置。

## 2026-07-11 - 项目综合审计

- 用六个并行只读代理 + 一份功能冲突逐行核验，对全项目做了跨维度审计（架构/构建、数据模型、平台可靠性、UI/UX、代码质量、功能完整性），最重的 P0/P1 结论已单独用工具复核。
- 交付文档 `docs/features/project-maintenance/project-audit-2026-07-11.md`：含执行摘要表、九大章节与优先级行动清单。
- 最关键结论：私密体系是明文 UI 门禁（正文/口令/备份全明文，SEC-1/SEC-2）、备份不含附件导致换机丢媒体（DATA-1）、`ThingDAO` 写入静默吞异常（DATA-2）、`onUpgrade` 旧段无守卫可致重装（DATA-3）、`doingThingId` 进程重建失真（ARCH-1）、错过提醒无补偿（REL-1）、数据层零测试无 CI（QA-1）。
- 已排除的误报（勿再当问题）：外观模型走 `org.json` 非 Gson、不受混淆影响；工具链是最新的 AGP 9.2.1/Gradle 9.4.1；私密内容在通知/widget/分享已正确遮蔽，唯一泄露点是搜索；Doing/Ongoing 处理得当。

## 2026-07-05 - 首页沉浸选择阴影与单个 Widget 顶部渐变修复

- 完成一次跨 feature 视觉修复：进入首页选择模式前立即隐藏旧 home actionbar 阴影；单个记事 Widget 配置页把 statusbar 与 actionbar 放进同一顶部容器连续绘制根目录 / 文件夹背景。
- 细节分别记录在 `docs/features/immersive-thing-list/sessions.md` 与 `docs/features/thing-folders/sessions.md`；debug 更新码 `202607051014`。

## 2026-06-26 - 多级清单项设计追问与文档

- 为“最多三级的多级清单项”做了一轮 grill-with-docs，敲定数据模型、存储编码、完成语义、缩进/拖拽
  交互、分级排版与各显示界面。新增功能目录 `docs/features/multi-level-checklist/`、ADR
  `docs/adr/0010-checklist-item-level-encoding.md`，并在根 `CONTEXT.md` 补入清单相关术语。
- 主干设计已定、尚未实现；细节见 `docs/features/multi-level-checklist/`。

## 2026-06-24 - Widget 媒体、单个 Widget 文件夹选择和详情单媒体默认比例

- 完成一次跨 feature 小修复：记事列表 Widget 网格卡片媒体背景改按 slot 宽度投影；单个记事 Widget 配置隐藏空文件夹；详情页单媒体 full-span 默认比例改为 `4:3`，并与用户确认过的设置分离。
- 细节分别记录在 `docs/features/thing-card-media-target-geometry/sessions.md`、`docs/features/thing-folders/sessions.md` 和 `docs/features/detail-attachment-media-appearance/sessions.md`。

## 2026-06-20 - Icon spacing and Folder return restore follow-up

- Completed a follow-up across Thing Folders, Thing Card Appearance, and
  AppWidget presentation: create-Thing vector stroke strength was increased,
  then tuned down; currently-doing icon-to-label spacing was tightened to 4dp;
  and parent Folder return restore now disables the ordinary Things appearing
  animation while applying the saved RecyclerView state before draw.
- Details are recorded in `docs/features/thing-folders/sessions.md`,
  `docs/features/thing-card-appearance/sessions.md`, and
  `docs/features/appwidget-platform-compat/sessions.md`.

## 2026-06-20 - Folder move/privacy polish and media overlay colour fix

- Completed a follow-up slice across Thing Folders, Dark Mode, and Thing Card
  Appearance: Folder move dialogs now show disabled forbidden subtrees, private
  Folder expansion auth is surface-local, Home Folder overflow chrome refreshes
  correctly, and media overlay count colour stays fixed.
- Details are recorded in `docs/features/thing-folders/sessions.md`,
  `docs/features/dark-mode/sessions.md`, and
  `docs/features/thing-card-appearance/sessions.md`.

## 2026-06-18 - Switched shared keyboard helper to WindowInsets

- Replaced the shared keyboard show/hide implementation with direct
  `WindowInsetsCompat.Type.ime()` control after researching the current Android
  guidance for the Folder naming dialog issue.
- Kept the detailed feature context under `docs/features/thing-folders/`.

## 2026-06-06 - Memory documentation reorganization

- Moved feature-scoped preference, decision, follow-up, and session history from global `memory/*.md` into `docs/features/*/` files.
- Rewrote `memory/preferences.md`, `memory/decisions.md`, `memory/followups.md`, and `memory/sessions.md` as lightweight startup indexes.
- Updated `AGENTS.md`, `CLAUDE.md`, and `docs/features/README.md` so new rules point agents to global memory first and then relevant feature directories.

## Feature session indexes

- `android-16-migration`: `docs/features/android-16-migration/sessions.md`
- `appwidget-platform-compat`: `docs/features/appwidget-platform-compat/sessions.md`
- `dark-mode`: `docs/features/dark-mode/sessions.md`
- `debug-update-channel`: `docs/features/debug-update-channel/sessions.md`
- `detail-attachment-media-appearance`: `docs/features/detail-attachment-media-appearance/sessions.md`
- `detail-color-sampling`: `docs/features/detail-color-sampling/sessions.md`
- `documentation-organization`: `docs/features/documentation-organization/sessions.md`
- `home-card-span-mode`: `docs/features/home-card-span-mode/sessions.md`
- `home-contextual-toolbar`: `docs/features/home-contextual-toolbar/sessions.md`
- `home-new-item-animation`: `docs/features/home-new-item-animation/sessions.md`
- `kotlin-migration`: `docs/features/kotlin-migration/sessions.md`
- `localization`: `docs/features/localization/sessions.md`
- `popup-picker-insets`: `docs/features/popup-picker-insets/sessions.md`
- `ratio-slider`: `docs/features/ratio-slider/sessions.md`
- `remote-thing-card-appearance`: `docs/features/remote-thing-card-appearance/sessions.md`
- `share-screenshot`: `docs/features/share-screenshot/sessions.md`
- `system-bar-insets`: `docs/features/system-bar-insets/sessions.md`
- `thing-folders`: `docs/features/thing-folders/sessions.md`
- `thing-card-appearance`: `docs/features/thing-card-appearance/sessions.md`
- `thing-card-image-placement`: `docs/features/thing-card-image-placement/sessions.md`
- `thing-card-media-target-geometry`: `docs/features/thing-card-media-target-geometry/sessions.md`

## Update rule

Add a substantive-work entry to this file only when the work is cross-feature or changes the memory/documentation system itself. Otherwise write the session note to the relevant feature directory.

