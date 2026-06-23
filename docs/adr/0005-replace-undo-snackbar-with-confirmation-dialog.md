# 用确认弹窗取代撤销 snackbar

EverythingDone 此前对状态变更（完成 / 删除 / 恢复 / 永久删除，含单条、批量、习惯）采用事后撤销
snackbar，背后是一整套 undo 机制（`mUndoThings` / `mUndoLocations` / `mUndoPositions` /
`mStateToUndoFrom` / `mUndoAll` + 状态回滚），维护复杂，且与文件夹内容操作"先确认"的模型不一致。
决定全局移除该撤销机制，状态变更统一改为操作前确认弹窗（选择模式批量动作、文件夹内容操作、永久删除、
普通模式工具栏"全部完成 / 全部删除"）。

## Considered Options

- 保留撤销 snackbar。否决：undo 机制复杂，且与文件夹内容操作已有的"先确认、无撤销"模型割裂，
  导致一半操作可事后撤销、一半只能事前确认。
- 连单条滑动手势也加确认弹窗（完全统一）。否决：本 App 左滑=完成是最高频核心手势，逐次弹窗严重
  拖慢日常使用。

## Consequences

- 单条直接手势保持即时、无确认、无撤销：左滑完成、右滑开始专注、单条习惯完成。
- 完成与删除本就可逆（已完成列表 / 回收站可恢复），失去 snackbar 不会丢数据；只有永久删除不可逆，
  且本就有确认弹窗。
- 提示型 snackbar（`mNormalSnackbar`：标题不能为空、放弃新建、权限被拒等一次性通知）暂时保留，
  `Snackbar.kt` 视图类不删除；改用 Toast 彻底移除 Snackbar 类的清理留待后续。
