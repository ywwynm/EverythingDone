# Preferences

## 提交纪律（2026-07-17）

- **未经用户明确要求不得 git commit**（包括代码）；完成工作后把改动留在工作区并
  说明状态，提交时机与分组由用户掌控。
- 用户要求提交时：按逻辑分组；重要工作（如性能优化）的提交日志必须详尽——改了
  什么、为什么、数据证据，中英双语，不得几句带过。
- 发布回填（memory/debug-update-notes.md、debug-updates/update-*.md）只写文件，
  不为其创建提交。
- 文档归属：`.claude/rules/` 只放跨功能的工具链通用规范；某功能专用的测试/测量
  方法写 `docs/features/<slug>/`（例：FableSol 实机测量在
  docs/features/audio-visualization-fable-sol/on-device-perf-testing.md）。

## 参数数字偏好（2026-07-15，2026-07-23 更新清单）

- 用户喜欢数字 `6、9、12、16、21、24、27、32、36、42、45、49、54、56、60、64、72、75、81、84、91、96、108、115、120、129、144、150、160、180、196、216、224、256`（2026-07-23 版；较 07-15 版新增 9/115/120/256、移除 50/121），07-15 曾表示最喜欢 `129`。
- 设计参数在多个近似可行值之间选择时，可以适度靠近这些数字；实际观感、物理合理性、性能和可维护性始终优先，不得为了迎合数字偏好勉强采用较差参数。

Global startup preferences only. Feature-specific preferences live in `docs/features/<kebab-case-feature-slug>/preferences.md`.

## Communication

Use professional, concise Chinese in agent-user conversation. When updating
repository instruction, memory, planning, review, or analysis documents for
this project workflow, write those updates in English unless the target file is
explicitly a localisation resource or already requires another language.

## Workflow

**Never commit unless explicitly asked.** Successful compile ≠ feature
correctness — UI changes may still need visual review. Stage and commit
only after the user has tested and given explicit go-ahead (e.g. "now
commit", "commit this"). Reverting an unrequested commit was needed
once on 2026-05-18; avoid the same mistake. Applies even when code
compiles and tasks look "done".

当用户要求进行网上调研时，不要只浏览少量搜索结果后下结论；需要覆盖官方文档、平台/API 约束、相关工程实践和可借鉴的产品/论文资料，并在回答中说明依据来源与取舍。

When a broad UI sweep finds additional candidate omissions beyond the user's
explicitly reported bug, report those candidates first and wait for user
confirmation before modifying them.

When the user explicitly invokes `grill-with-docs`, treat it as a request to
stress-test and clarify the design before implementation. Even if the request
contains concrete implementation preferences, ask at least the remaining
product/design trade-off questions one at a time unless the user explicitly
asks to proceed directly to coding.

When grilling a design, avoid low-value scope-confirmation questions when the
scope is already obvious from the user's request. Focus on decisions that can
change the main implementation shape or user-visible behaviour.

当主要分支已经收敛时，用户希望 grilling 明确告知剩余问题数量，并加快节奏，只继续询问会改变实现
形态、发布边界或用户可见行为的关键问题；其余细节交给代码核对和 PoC。

### 快速演进 AI 模型的调研与选型（2026-08-01）

- 调研深度估计、分割、matting、生成式补全等快速演进的 AI 能力时，必须先核验截至调研日的最新上游版本、发布日期、权重、许可与维护状态，再进行工程选型。
- 旧模型或旧设备基准不能被包装成当前最佳方案；若因体积、许可或端侧兼容性保留，只能明确列为兼容/低配档，并同时说明它与当前质量前沿的差距。
- 论文的新旧、模型质量和产品可落地性必须分开判断；最终候选还需经过目标 Android 设备上的内存、时延、功耗和输出质量验证。

## Android dialogs

Prefer custom `DialogFragment` implementations under `app/src/main/java/.../fragments/`,
usually extending `BaseDialogFragment`, for in-app dialogs. Avoid constructing
raw `android.app.Dialog` instances directly inside Activities for feature UI.
If a dialog's content is tightly coupled to Activity state, use a thin custom
`DialogFragment` host rather than keeping the raw `Dialog` in the Activity.

自定义 View 放进 BaseDialogFragment 的对话框时**必须重写 `onMeasure`**：窗口是
WRAP_CONTENT、根布局以 null parent 充气并靠 `minWidth/minHeight` 声明固有尺寸，
而默认 View（getDefaultSize）在 AT_MOST 下会按可用空间上报尺寸，把对话框撑大。
正确做法：非 EXACTLY 模式只上报固有最小尺寸，让根布局的 min 尺寸决定对话框大小
（FrameLayout 会对 match_parent 子项以 EXACTLY 二次测量铺满）。此坑在录音波形
动画的多轮迭代中反复出现（GPT 与 Claude 均踩过，2026-07-03 再次确认并修复）。

## Documentation organization

For every new feature request or substantial technical initiative, create a
dedicated documentation directory under
`docs/features/<kebab-case-feature-slug>/`. Keep feature-specific planning,
review, analysis, execution checklists, and archived debug notes in that
directory. Do not add new feature plans to `docs/plans/`.

Keep `CONTEXT.md`, `docs/adr/`, and canonical `memory/*.md` files global.
Feature directories may link to those global documents but should not move or
duplicate their authority.

## Commit messages

When writing bilingual commit messages, do not prefix paragraphs with language
labels such as `English:`, `EN:`, `中文：`, or `Chinese:`. Do not include Gradle
command output or APK verification details in the commit message body. Keep the
repository's normal Git author/committer identity, and add the collaborator
trailer for the collaborating model that actually did the work. The project has
worked with the models below, so these formats may appear in history (use the
one matching the model that wrote the commit, not multiple trailers):

`Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
`Co-Authored-By: GPT-5.5 gpt-5.5@openai.com`
`Co-authored-by: GPT 5.6 Sol <noreply@openai.com>`

For substantive commits, follow the recent project style: use a bilingual
subject in the form `English / Chinese`, then write paired English and Chinese
body paragraphs that describe the same implementation decisions at a useful
review level.

## Screenshot frugality

Each emulator screenshot costs ADB `screencap` + `pull` + file
transfer + the vision-token cost of `Read`-ing the PNG. Don't take
them at every micro-step during exploratory or navigation work.

**Take a screenshot when**:
- One-time Phase 0 baseline capture (per scene)
- End-of-group V3 verification (per planned scene the group should
  have touched)
- A `adb input tap` had uncertain effect and a visual is the only
  reliable confirmation

**Don't take a screenshot when**:
- The expected state is unambiguous and downstream commands don't
  branch on a visual
- A `uiautomator dump` (plain text, ~10 KB vs ~150 KB PNG plus
  vision tokens) would confirm the same thing
- Multiple intermediate steps follow before the next decision point
  — capture only at the decision point

When in doubt, dump UI hierarchy first (see `.claude/rules/adb.md`).
Reach for screencap only when an actual pixel comparison is required.

## Debugging and exception handling

Do not add silent exception catches to hide newly observed runtime crashes.
For RecyclerView/layout crashes and similar framework errors, fix the caller
state/update path so the error disappears; if an error still happens, it should
remain visible in crash logs rather than being swallowed by a new broad catch.
Existing legacy catches should not be expanded without an explicit product or
technical reason.

## Publishing and commits

When the user asks to "submit" during a debug-testing cycle, interpret it as
publishing a debug update, not creating a Git commit. Only create a Git commit
when the user explicitly asks for `commit`, `git commit`, or says the tested
version has no obvious bugs and is ready to commit.

For small bug fixes, after implementing the fix and running the appropriate
verification, publish the debug update directly by default. The user has granted
standing permission for the Gradle debug update publish task in this small-bug
workflow; still respect sandbox/escalation policy prompts if the environment
requires them.

2026-07-06 补充：当用户在 debug 版本试用后反馈方向、手感、视觉等问题，代理修复并完成本地验证后，应直接发布新的阿里云 debug 版本；不要再让用户单独说“发布”。仍然不要自动安装到物理设备，也不要自动创建 Git commit。

**（2026-07-04 扩大范围）** 不限于小 bug 修复：凡是改完代码、`:app:assembleDebug` 编译通过、
且需要真机看效果的迭代改动（如录音波浪可视化的视觉调整），**默认直接发布 debug 到阿里云**，
不要每次再问用户"发不发"——用户对反复确认感到啰嗦，已授予该发布任务的常驻许可。发布 = 建
`docs/features/<slug>/debug-updates/update-*.md` 日志并调 `:app:publishDebugUpdate` 传入。提交
（commit）仍需用户明确指示，不因编译通过或"看起来完成"就自动提交。

Do not auto-install the debug APK onto the connected physical device (the
`BYZL…` serial). The user verifies on their own device remotely: after a
successful compile, publish a debug update to the Aliyun channel and let the
user test there. This matters especially for features that can only be confirmed
visually on real hardware (e.g. HDR rendering, which an emulator cannot show).

## 发布日志纳入版本控制

发布日志文件（`docs/features/*/debug-updates/update-*.md` 与 `memory/debug-updates/*.md`）是**纳入 Git 版本控制**的——项目历史上已提交数百个，每次发布的日志随其对应的代码 / 文档改动一起提交，**不要**加进 `.gitignore`。注意：会话之间可能有少数发布日志暂处于未跟踪状态，这并不代表项目不收录它们；判断前先用 `git ls-files` 查证，不要只凭单次 `git status` 的表象误判。

## Memory and feature documentation

- Keep `memory/*.md` lightweight and cross-feature. Do not store detailed feature implementation history here.
- Put feature-scoped preferences, decisions, follow-ups, sessions, execution notes, and debug-note archives under `docs/features/<kebab-case-feature-slug>/`.
- When working on a feature, read that feature directory after reading the global memory files.
- If a new note applies to multiple features or to agent behavior generally, record it in global `memory/*.md`; otherwise record it in the feature directory.

## Feature-scoped preference indexes

- `app-chrome-polish`: `docs/features/app-chrome-polish/preferences.md`
- `color-system-migration`: `docs/features/color-system-migration/preferences.md`
- `dark-mode`: `docs/features/dark-mode/preferences.md`
- `debug-update-channel`: `docs/features/debug-update-channel/preferences.md`
- `detail-attachment-media-appearance`: `docs/features/detail-attachment-media-appearance/preferences.md`
- `detail-color-sampling`: `docs/features/detail-color-sampling/preferences.md`
- `kotlin-migration`: `docs/features/kotlin-migration/preferences.md`
- `localization`: `docs/features/localization/preferences.md`
- `thing-folders`: `docs/features/thing-folders/preferences.md`
- `remote-thing-card-appearance`: `docs/features/remote-thing-card-appearance/preferences.md`
- `thing-background-editor`: `docs/features/thing-background-editor/preferences.md`
- `timely-digit-typography`: `docs/features/timely-digit-typography/preferences.md`
