# Thing Card image placement and single-card surfaces

## 1. 首页记事卡片图片位置设置

用户最初希望允许修改首页记事卡片里首张图片/视频缩略图的位置。

确认范围：
- 普通卡片只允许图片在上方或下方。
- 放大卡片允许图片在上方、下方、左侧或右侧。
- 第一版只处理图片位置，不处理选择哪张图片、裁切比例、裁切焦点、图库或轮播。
- 左右布局使用物理左/右语义；图片高度贴满卡片侧边，横向比例是主要配置，裁切是预期行为。

第一版实现：
- `Thing.kt`、`Def.kt`、`DBHelper.kt`、`ThingDAO.kt` 增加 `homeCardImagePlacement` / `home_card_image_placement`，数据库版本升至 11，首次建库和升级迁移都写入默认值。
- `DetailActivity.kt` 增加编辑状态、图片位置 dialog、undo/redo action、保存接入和 Snackbar/Toast 提示。
- `ImageAttachmentAdapter.kt`、`attachment_image.xml`、`ic_home_card_image_placement.xml` 在首张图片/视频附件 tile 的删除按钮左侧增加图片位置入口。
- `card_thing.xml`、`BaseThingsAdapter.kt` 将卡片拆成图片容器和文字内容容器，支持普通卡片上/下图片，以及放大卡片上/下/左/右图片。
- `strings.xml` 和中文资源增加选项文案与提示文案，例如“已将图片放置于记事卡片上方”。
- `CONTEXT.md`、`memory/decisions.md`、`docs/plans/HOME_CARD_IMAGE_PLACEMENT_PLAN.md` 记录术语、决策和计划。

后续修复：
- 图片在下方时，图片和上方内容之间增加间距；同时隐藏底部 spacer，让底部图片直接贴住卡片底部。
- 左右布局按内容列实测高度和最小高度决定侧边图片高度；侧边图片使用 `centerCrop`，图片/视频数量提示 UI 保留在图片内部。
- 图片位置 dialog 隐藏底部 action row 时增加 12dp 底部间距。
- `thing_card_full_span_side_image_min_height` 最终调整为 128dp。
- 首页卡片图片加载改用 keyed tag 和 load key，减少 Glide 与业务 tag 冲突、holder 复用和尺寸未变时的重复加载。
- 已成功加载过的相同图片路径和目标尺寸，滑动复用时不再显示加载进度条；加载失败也隐藏当前请求的进度条；图片加载增加 `dontAnimate()`。
- 图片在右侧时，图片/视频数量提示 UI 显示在右下角；其它位置显示在左下角。
- 图片/视频数量提示 UI 的横向和纵向 margin 统一为 10dp。
- 详情页图片位置按钮与删除按钮使用一致亮度和圆形 ripple；圆形 ripple mask 四周内缩 2dp，避免贴住图片边缘。

## 2. 从 Home Card 改为 Thing Card

用户指出这些设置已经影响首页之外的卡片场景，因此不应继续叫 Home Card。

命名与数据库调整：
- Kotlin 字段和常量从 `homeCardSpanMode` / `homeCardImagePlacement` 改为 `thingCardSpanMode` / `thingCardImagePlacement`。
- SQLite 新字段改为 `thing_card_span_mode` 和 `thing_card_image_placement`。
- legacy `home_card_span_mode` / `home_card_image_placement` 只保留用于迁移和兼容读取。
- 数据库版本升至 12；升级时补新列，并从 legacy 列复制已有值；首次建库直接创建新列。
- `Thing(Cursor)` 优先读取新列，必要时 fallback 到 legacy 列。
- `ThingDAO` 的创建、更新、批量更新都写入新字段。

资源与文档调整：
- `CONTEXT.md` 改用 `Thing Card`、`Full-Span Thing Card`、`Thing Card Span Mode`、`Thing Card Image Placement`。
- 计划文档从 `docs/plans/HOME_CARD_IMAGE_PLACEMENT_PLAN.md` 改名为 `docs/plans/THING_CARD_IMAGE_PLACEMENT_PLAN.md`。
- 菜单 id、drawable 名称、字符串 key、locales 文案和 keyed tag id 改为 Thing Card 语义。
- 中文显示文案继续使用“记事卡片”。

## 3. DoingActivity 和 NoticeableNotificationActivity 适配

用户要求非首页单卡片场景也支持 Thing Card Span Mode 和 Thing Card Image Placement。

`DoingActivity`：
- 根据 `thingCardSpanMode == Thing.THING_CARD_SPAN_FULL` 判断 full-span。
- 单卡片 adapter 覆盖 `isFullSpanThingCard(...)`，复用 `BaseThingsAdapter` 的图片位置布局。
- 标题、正文和 checklist 取消行数限制，尽量展示完整内容。
- 卡片区域从 `wrap_content` 起步；过长内容按 RecyclerView 顶部、底部按钮顶部、滑动完成提示高度和 `doing_thing_card_vertical_margin` 计算最大高度，并开启滚动 over-scroll。

`NoticeableNotificationActivity`：
- 根据 `thingCardSpanMode == Thing.THING_CARD_SPAN_FULL` 判断 full-span。
- 单卡片 adapter 覆盖 `isFullSpanThingCard(...)`，复用 `BaseThingsAdapter` 的图片位置布局。
- dialog 根宽度在代码中按当前记事的 normal/full-span 状态设置，adapter 使用同一宽度作为卡片宽度。

共享 adapter 调整：
- `BaseThingsAdapter` 暴露 `isFullSpanThingCard(...)` 和 `setFullSpanCardWidth(...)` 给非首页单卡片场景。
- `setCardWidth(...)` 在单卡片场景中同步普通和 full-span 宽度。
- 首页列表仍会在绑定到 `StaggeredGridLayoutManager` 后根据 RecyclerView 实际宽度刷新普通和 full-span 宽度。
- 左/右图片位置继续只在 full-span 卡片上生效；非 full-span 遇到左/右设置会 fallback 到上方图片。

## 4. 单卡片场景固定宽度

用户随后要求 `DoingActivity` 和 `NoticeableNotificationActivity` 使用同一套固定 dp 宽度，不再使用 `DisplayUtil.getThingCardWidth() * 1.2` 或 Activity 私有 max width 资源。normal-span 和 full-span 仍要有差别。

第一版固定宽度：
- normal-span 曾设为 280dp。
- full-span 曾设为 420dp。

本轮最终宽度：
- normal-span：`thing_card_single_surface_normal_width = 256dp`，定义在 `app/src/main/res/values/dimens.xml:12`。
- full-span：`thing_card_single_surface_full_span_width = 300dp`，定义在 `app/src/main/res/values/dimens.xml:13`。
- 小屏幕保护 margin：`thing_card_single_surface_horizontal_margin = 16dp`，定义在 `app/src/main/res/values/dimens.xml:14`。

对应代码位置：
- `DoingActivity.getDoingThingCardWidth(...)`：`app/src/main/java/com/ywwynm/everythingdone/activities/DoingActivity.kt:180`。
- `NoticeableNotificationActivity.getNoticeableThingCardWidth(...)`：`app/src/main/java/com/ywwynm/everythingdone/activities/NoticeableNotificationActivity.kt:155`。
- `activity_noticeable_notification.xml` 的初始布局宽度使用 normal 宽度资源：`app/src/main/res/layout/activity_noticeable_notification.xml:6`。

## 5. 验证与发布

已完成的验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。
- 上一轮曾发布 debug update `202605311648`。

本轮验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。
- `:app:publishDebugUpdate` 通过，最终发布 debug update `202606010137`
  到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。

追加调整：
- 用户要求将 full-span 单卡片宽度从 288dp 调整为 300dp。
- `thing_card_single_surface_normal_width` 仍为 256dp。
- `thing_card_single_surface_full_span_width` 调整为 300dp。
- `:app:assembleDebug` 通过。
- `:app:publishDebugUpdate` 通过，已发布 debug update `202606011558`
  到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
