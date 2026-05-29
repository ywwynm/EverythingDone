修复首页图片记事卡片宽度

用户需求：
- 首页记事列表中，带图片附件的记事图片没有占满整个记事卡片宽度。
- 用户询问这是否与之前修复隐藏私密记事宽度有关，并要求修复。

分析：
- card_thing.xml 中图片容器 fl_thing_image 和图片 iv_thing_image 都是 match_parent，布局本身不是直接原因。
- BaseThingsAdapter.updateCardForImageAttachment() 会用 mCardWidth 设置图片区域宽度，因此如果 mCardWidth 偏小，图片就会比卡片窄。
- 之前的 hidden private card 修复新增了 refreshCardWidthFromRecyclerView()，用当前 RecyclerView 宽度和 StaggeredGridLayoutManager spanCount 动态刷新 mCardWidth。
- 根因在该动态公式：它先计算 width = recyclerView.width - paddingLeft - paddingRight，已经扣掉了 activity_things.xml 中 RecyclerView 左右的 thing_card_outer_spacing padding；随后又复用了 DisplayUtil.getThingCardWidth(...) 的完整屏幕公式 `(spanCount + 1)`，把外侧 spacing 又扣了一次。

修改：
- BaseThingsAdapter.kt：refreshCardWidthFromRecyclerView() 在动态路径中改为只扣每个 item 自身的左右 margin：`(width - spacing * 2 * spanCount) / spanCount`。
- 保留动态刷新 mCardWidth 的设计，使隐藏私密记事、图片记事、旋转和多窗口场景仍然使用当前 RecyclerView 的实际 span 宽度。

验证：
- `git diff --check` 通过，仅有既有 CRLF conversion warnings。
- `:app:publishDebugUpdate` 通过，发布 debug update `202605290623` 到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
