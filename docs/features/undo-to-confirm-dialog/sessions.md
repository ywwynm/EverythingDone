# Undo to Confirm Dialog Sessions

## 2026-06-23 - Part A 实现并发布

移除 `ThingsActivity` 的撤销机制：删除 `mUndoSnackbar` / `mHabitSnackbar` 对象、
`setUndoSnackbarEvents` 撤销监听、`showUndoSnackbar` / `showHabitSnackbar` / `getUndoMessages` /
`getVisibleListPositionForUndoThing`。`updateUIAfterStateUpdated` 不再弹 snackbar；
`dismissSnackbars` / `prepareBeforeSwipingThing` 只保留 `mNormalSnackbar`。

滑动路径（`tryToFinishOtherBySwiping` / `tryToFinishHabitOnceBySwiping`）去掉 undo 记账与
habit snackbar，保留实际状态变更（含 checklist 重置、`finishOneTime`），即时生效。

选择模式批量状态变更新增 `confirmSelectedStateChange(stateAfter)`：先弹确认弹窗（标题/确认色用
当前文件夹色或 accent 渐变，文案含选中数量），确认后走原 `handleUpdateStates`（习惯/目标三选项弹窗照常）。

字符串：新增 `confirm_finish/delete/restore/delete_*_selected_things`（英文默认 + zh-rCN，其余语言回退英文）。

遗留：`mUndoThings` / `mUndoPositions` / `mUndoLocations` / `mStateToUndoFrom` / `mUndoAll` /
`mUndoHabitRecords` 部分字段变为只写或未用（仅告警），后续可清理（见 followups）。

`:app:assembleDebug` 通过。`:app:publishDebugUpdate` 发布到阿里云，code `202606231034`。
