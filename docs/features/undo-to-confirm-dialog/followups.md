# Undo to Confirm Dialog Followups

## 提示型 snackbar 迁移到 Toast、删除 Snackbar 视图类

本次只移除撤销类 snackbar。提示型 `mNormalSnackbar`（标题不能为空、放弃新建、权限被拒、
找不到 Activity 等）仍用 `Snackbar.kt`。后续可把这些通知改为 Toast，并在确认无其它引用后
删除 `Snackbar.kt`，实现"全局不再使用 snackbar"。涉及 `ThingsActivity`、`DetailActivity`、
`SettingsActivity`、`AddAttachmentDialogFragment` 等。

## 清理只写/未用的 undo 残留字段

移除撤销机制后，`ThingsActivity` 中 `mUndoLocations`、`mStateToUndoFrom`、`mUndoAll`、
`mUndoHabitRecords` 已变为只写或完全未用（仅编译告警）。`mUndoThings` / `mUndoPositions`
仍作为 `handleUpdateStates` 的临时收集器使用，可考虑改名为中性命名（如 `mPendingThings`）。
后续清理这些残留并消除告警。

## 新增确认弹窗字符串的完整本地化

`confirm_finish/delete/restore/delete_*_selected_things` 目前只有英文（默认）与简体中文（zh-rCN），
其余语言（ja/ko/fr/de/es/it/pt/ru/hi/zh-rTW/zh-rHK）回退英文。后续补齐翻译。
