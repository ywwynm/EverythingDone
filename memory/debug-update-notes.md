新增首页记事卡片放大/缩小提示

用户需求：
- 点击详情页菜单中的“放大记事卡片”或“缩小记事卡片”后，需要立刻提示用户。
- 因为实际视觉效果要回到首页才能看到，所以不能点击后没有任何反馈。
- 优先使用 `DetailActivity` 中已有的 Snackbar；如果没有可用 Snackbar，则退回 Toast。

修改：
- `DetailActivity.kt`：在 `toggleHomeCardSpanMode()` 切换后调用即时反馈。
- 新增 `showHomeCardSpanModeFeedback()`：Snackbar 可用时显示提示，否则用 Toast。
- 新增提示文案：简体中文为“已放大记事卡片”和“已缩小记事卡片”，并补齐所有现有语言资源。

验证：
- `git diff --check` 通过，仅有既有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。
- `:app:publishDebugUpdate` 通过，发布 debug update `202605300306` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
