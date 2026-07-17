# Decisions

Global startup decision index only. Feature-specific decisions live in `docs/features/<kebab-case-feature-slug>/decisions.md`.

## 2026-07-17 - 调研归档产物随 feature 目录本地存放且不进版本库

大体积或一次性的调研产物（LaTeX 报告及配图、对比图集、字体候选与子集化中间产物）归档在所属 `docs/features/<slug>/` 目录下，并在根 `.gitignore` 中按具体路径忽略，不进入版本库；已提交文档按目录名提及它们即可。本仓库亦不跟踪 `Everything-Android/`（独立 git 仓库，仅本地参考）。轻量调研 md 是否入库由用户逐一决定。

## 2026-06-24 - Things without a title can be made private

Setting a Thing private no longer requires a non-empty title. The previous
empty-title guards were removed from all three entry points (Detail return,
single-select toggle, multi-select batch). Privacy stays encoded as the
`PRIVATE_THING_PREFIX` title prefix, so a no-title private Thing simply stores
the prefix with an empty display title. The batch "skipped" Toast now only
covers the currently-timed (doing) Thing.

## 2026-06-18 - KeyboardUtil uses WindowInsets for IME visibility

`KeyboardUtil` should control IME show/hide through AndroidX
`WindowCompat.getInsetsController(...).show/hide(WindowInsetsCompat.Type.ime())`
instead of `InputMethodManager` or soft-input state flags. Callers with only a
`View` can resolve the owning `Activity.window` and then use the same
WindowInsets path.

## 2026-06-06 - Memory files are lightweight global indexes

Canonical memory files should stay small and cross-feature. Detailed feature-scoped decisions, follow-ups, and session history belong under the relevant `docs/features/<kebab-case-feature-slug>/` directory. Agents should read global memory at session start, then read the relevant feature directory before working in that area.

## 2026-06-06 - Feature documentation directory structure

Feature-specific project documentation is organized under `docs/features/<kebab-case-feature-slug>/`, with one directory per feature, migration, review track, or substantial technical initiative. `CONTEXT.md`, `docs/adr/`, and canonical `memory/*.md` files remain global sources.

## Feature decision indexes

- `android-16-migration`: `docs/features/android-16-migration/decisions.md`
- `animated-video-cover`: `docs/features/animated-video-cover/decisions.md`
- `app-chrome-polish`: `docs/features/app-chrome-polish/decisions.md`
- `appwidget-platform-compat`: `docs/features/appwidget-platform-compat/decisions.md`
- `cloud-sync`: `docs/features/cloud-sync/decisions.md`
- `color-system-migration`: `docs/features/color-system-migration/decisions.md`
- `dark-mode`: `docs/features/dark-mode/decisions.md`
- `detail-color-sampling`: `docs/features/detail-color-sampling/decisions.md`
- `detail-attachment-media-appearance`: `docs/features/detail-attachment-media-appearance/decisions.md`
- `doing-thing-organize`: `docs/features/doing-thing-organize/decisions.md`
- `home-contextual-toolbar`: `docs/features/home-contextual-toolbar/decisions.md`
- `home-empty-state`: `docs/features/home-empty-state/decisions.md`
- `home-new-item-animation`: `docs/features/home-new-item-animation/decisions.md`
- `kotlin-migration`: `docs/features/kotlin-migration/decisions.md`
- `localization`: `docs/features/localization/decisions.md`
- `popup-picker-insets`: `docs/features/popup-picker-insets/decisions.md`
- `project-maintenance`: `docs/features/project-maintenance/decisions.md`
- `ratio-slider`: `docs/features/ratio-slider/decisions.md`
- `audio-visualization-fable-sol`: `docs/features/audio-visualization-fable-sol/decisions.md`
- `remote-thing-card-appearance`: `docs/features/remote-thing-card-appearance/decisions.md`
- `selection-batch-actions`: `docs/features/selection-batch-actions/decisions.md`
- `undo-to-confirm-dialog`: `docs/features/undo-to-confirm-dialog/decisions.md`
- `system-bar-insets`: `docs/features/system-bar-insets/decisions.md`
- `thing-folders`: `docs/features/thing-folders/decisions.md`
- `thing-card-image-placement`: `docs/features/thing-card-image-placement/decisions.md`
- `thing-card-media-target-geometry`: `docs/features/thing-card-media-target-geometry/decisions.md`
- `timely-digit-typography`: `docs/features/timely-digit-typography/decisions.md`
