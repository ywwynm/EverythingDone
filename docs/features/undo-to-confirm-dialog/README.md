# Undo to Confirm Dialog（撤销 snackbar 改确认弹窗）

Status: 设计已定（2026-06-23），待实现。

## Goal

全局移除状态变更的撤销 snackbar 及整套 undo 机制，改为操作前确认弹窗。这是从
`selection-batch-actions` 盘问中分出的 App 级交互改动，单独跟踪。

权威决策见 `docs/adr/0005-replace-undo-snackbar-with-confirmation-dialog.md`。

## Scope

移除（撤销类）：
- `mUndoSnackbar`、`mHabitSnackbar` 及其展示/监听路径。
- undo 机制字段与回滚：`mUndoThings` / `mUndoLocations` / `mUndoPositions` /
  `mStateToUndoFrom` / `mUndoAll` 等（主要在 `ThingsActivity`）。

改为确认弹窗的入口：
- 选择模式的批量状态变更（完成 / 删除 / 恢复 / 永久删除）。
- 普通模式工具栏"全部完成 / 全部删除"。
- 文件夹内容操作已是确认弹窗，保持不变。

保持即时、不弹窗、不撤销（例外）：
- 单条左滑完成、右滑开始专注、单条习惯完成。

不在本次范围：
- 提示型 `mNormalSnackbar`（标题不能为空、放弃新建、权限被拒等）维持现状，`Snackbar.kt` 保留。
  改 Toast、删除 Snackbar 类留待后续。

## Related Records

- `docs/adr/0005-replace-undo-snackbar-with-confirmation-dialog.md`
- `docs/features/selection-batch-actions/`（批量动作的确认弹窗文案、习惯/目标三选项流程）
- `docs/features/thing-folders/use-cases.md`（文件夹内容操作的确认弹窗语义）
