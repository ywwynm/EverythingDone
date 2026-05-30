修复私密卡片宽度影响图片卡片宽度

用户需求：
- 用户反馈：隐藏私密记事卡片手动撑开的宽度，仍然会影响带图片记事卡片中的图片宽度。
- 需要重新分析并修复私密卡片和图片卡片之间的宽度耦合。

分析：
- `BaseThingsAdapter` 之前有两套宽度职责：隐藏私密卡片用 `llContent.minimumWidth` 撑开卡片，图片卡片用 `flImageAttachment.layoutParams.width` 撑开图片区域。
- RecyclerView 复用同一个 `card_thing.xml` holder 时，父容器和图片子容器各自保留宽度状态，容易让私密卡片的手动宽度调整间接影响后续图片卡片的测量。
- 更稳的边界是：卡片是否需要固定内容宽度，由父容器 `llContent` 统一决定；图片容器只负责 `MATCH_PARENT` 填满父容器。

修改：
- `BaseThingsAdapter.kt`：新增 `shouldUseFixedCardContentWidth()`，把 full-span、隐藏私密卡片、图片卡片统一归为需要固定内容宽度的卡片。
- `applyCardContentGeometry()` 现在统一设置 `llContent.layoutParams.width`，并重置 `minimumWidth` / `minimumHeight`。
- 图片容器 `flImageAttachment` 改回 `MATCH_PARENT`，图片宽度由父容器内容宽度决定，不再单独携带上一轮 holder 复用留下的宽度。

验证：
- `git diff --check` 通过，仅有既有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。
- `:app:publishDebugUpdate` 通过，发布 debug update `202605300353` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
