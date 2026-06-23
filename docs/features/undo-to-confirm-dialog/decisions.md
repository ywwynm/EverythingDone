# Undo to Confirm Dialog Decisions

## 2026-06-23 - 全局移除撤销 snackbar，状态变更改为操作前确认弹窗

权威记录，另见 `docs/adr/0005-replace-undo-snackbar-with-confirmation-dialog.md`。

撤销 snackbar 机制太复杂，全局移除（`mUndoSnackbar` / `mHabitSnackbar` 及
`mUndoThings` / `mUndoLocations` / `mUndoPositions` / `mStateToUndoFrom` / `mUndoAll`
整套 undo 机制）。状态变更的"反悔"统一改为操作前确认弹窗。

会弹确认弹窗：选择模式的批量动作、文件夹内容操作（已有）、永久删除、普通模式工具栏
"全部完成 / 全部删除"。

例外（保持即时、不弹窗、不撤销）：单条直接手势——左滑完成、右滑开始专注、单条习惯完成。
理由：左滑完成是最高频核心手势，逐次弹窗会严重拖慢日常；完成 / 删除本就可逆（已完成列表 / 回收站），
失去 snackbar 不会丢数据，只有永久删除不可逆且已有确认。

注意：本 App 滑动手势是"左滑完成 / 右滑开始专注"，不是滑动删除。

## 2026-06-23 - 提示型 snackbar 暂时保留

本次只移除撤销类。提示型 `mNormalSnackbar`（标题不能为空、放弃新建、权限被拒等一次性通知）
维持现状，`Snackbar.kt` 视图类保留。改用 Toast、彻底移除 Snackbar 类的清理留待后续单独进行。
