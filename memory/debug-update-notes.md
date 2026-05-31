新增首页记事卡片图片位置设置

用户需求：
- 用户希望允许用户修改首页记事卡片里首张图片/视频缩略图的位置。
- 普通卡片只允许图片在上方或下方。
- 放大卡片允许图片在上方、下方、左侧或右侧。
- 用户确认第一版只做位置，不做选择哪张图片、裁切比例、裁切焦点、图库或轮播。
- 用户确认左右布局使用物理左/右语义，图片高度沾满卡片，横向比例是主要可调资源，裁切是预期行为。

分析和规划：
- 使用 **Home Card Image Placement** 作为领域术语。
- 数据字段命名为 `homeCardImagePlacement`，SQLite 字段命名为 `home_card_image_placement`。
- 取值为 `DEFAULT/TOP/BOTTOM/LEFT/RIGHT`，其中 `DEFAULT` 第一版等同于图片在上方。
- 入口放在 Detail 的首张图片/视频附件 tile 上，在删除按钮左侧放一个小图标。
- 点击入口打开 dialog，点选即生效并关闭，参与 Detail 的正常编辑、undo/redo、保存和 `updateTime` 更新。
- 没有图片时不显示入口；删除所有图片不重置设置；私密隐藏卡片不受该设置影响。

修改：
- `Thing.kt`、`Def.kt`、`DBHelper.kt`、`ThingDAO.kt`：
  - 新增 `homeCardImagePlacement` 字段。
  - 数据库版本升至 11。
  - 首次建库和升级迁移都加入 `home_card_image_placement INTEGER NOT NULL DEFAULT 0`。
  - DAO 插入、更新、状态恢复、Parcelable、Cursor 读取和 `Thing.noUpdate()` 都接入新字段。
- `DetailActivity.kt`：
  - 新增 `mHomeCardImagePlacement` 编辑状态。
  - 新增图片位置 dialog，普通卡片显示默认/上/下，放大卡片显示默认/上/下/左/右。
  - 普通卡片遇到已保存的 `LEFT/RIGHT` 时选中默认，但不因打开弹窗而改写原值。
  - 新增 undo/redo action 和 Snackbar/Toast 提示。
- `ImageAttachmentAdapter.kt`、`attachment_image.xml`、`ic_home_card_image_placement.xml`：
  - 在首张图片/视频附件 tile 的删除按钮左侧新增位置入口。
  - 编辑态显示，截图模式隐藏。
- `card_thing.xml`、`BaseThingsAdapter.kt`：
  - 将首页卡片拆成图片容器和文字内容容器。
  - 普通卡片支持图片在上/下，并沿用当前图片尺寸策略。
  - 放大卡片支持图片在上/下/左/右；左右布局图片列约 42%，内容列约 58%。
  - 侧边图片使用 `centerCrop`，贴住卡片外侧、顶部和底部，高度由内容列高度和侧边图片最小高度共同决定。
  - 底部图片隐藏原有底部 spacer，使图片直接顶到底部。
- `strings.xml` 及中文资源：
  - 新增选项文案和提示文案，例如“已将图片放置于记事卡片上方”。
- `CONTEXT.md`、`memory/decisions.md`、`docs/plans/HOME_CARD_IMAGE_PLACEMENT_PLAN.md`：
  - 记录术语、决策和实施计划。

验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。

第四轮追加修复：
- 用户反馈 full-span 且图片在左侧/右侧的首页卡片，在滑动页面、未改变记事大小或图片位置时，Glide 仍可能发生不必要的重复加载。

诊断：
- 首页卡片自己的图片 load key 存在 `ImageView.tag` 上，而 Glide 4 也会使用同一个普通 tag 保存 request，导致 `.into()` 后业务 load key 可能被 Glide 覆盖，下一次重绑时无法命中“同一图片同一尺寸”的跳过逻辑。
- 左右图片布局每次重绑会先把图片容器高度重置为 `thing_card_full_span_side_image_min_height`，再通过 `post` 按文字列实测高度修正。滑动导致 holder 重新绑定时，即使内容和图片位置没有变化，也可能从最终高度回退到最小高度，再触发一次或两次 Glide 重新加载。

修复处理：
- `BaseThingsAdapter.kt`：改用 `R.id.tag_home_card_image_load_key` keyed tag 保存首页卡片图片 load key，避免和 Glide 的普通 tag 冲突。
- `BaseThingsAdapter.kt`：为 full-span 左右图片的实测高度增加按 Thing、图片路径、位置、宽度、`updateTime` 等签名失效的缓存。滑动重绑同一内容时直接沿用上次实测高度，不再先回退到 128dp。
- `BaseThingsAdapter.kt`：图片和遮罩 layout params 只有在宽高变化时才重新设置，减少无意义 layout request。
- `ids.xml`：新增首页卡片图片 load key 和左右图片 bind token 的 keyed tag id。

第四轮追加验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。

第五轮追加修复：
- 用户反馈 full-span 左右图片卡片在滑动时仍可能短暂显示加载进度条，只有图片、没有其它内容的大卡片也会出现。
- 用户要求当图片在右侧时，图片/视频数量提示 UI 显示在图片右下角；其它图片位置仍显示在左下角。

诊断：
- RecyclerView 滑动复用 holder 时，同一条数据可能被绑定到新的 `ImageView`。这种情况下仍需要调用 Glide 把图片绑定到新 view；这不一定是错误的重复解码或重复磁盘加载。
- 但如果同一图片路径和目标宽高此前已经成功加载过，再次滑动绑定时不应该显示加载进度条，否则会出现用户观察到的短暂闪烁。

修复处理：
- `BaseThingsAdapter.kt`：新增已成功加载的首页卡片图片 load key 集合。相同图片路径和目标宽高已经加载成功后，后续滑动绑定仍会让 Glide 绑定图片，但不再显示进度条。
- `BaseThingsAdapter.kt`：加载失败时也隐藏当前匹配请求的进度条，避免失败后进度条停留。
- `BaseThingsAdapter.kt`：首页卡片图片加载增加 `dontAnimate()`，减少从内存缓存回填时的视觉闪动。
- `BaseThingsAdapter.kt`：根据 Home Card Image Placement 调整图片/视频数量提示 UI 的 FrameLayout gravity：右侧图片为右下角，其它位置为左下角。

第五轮追加验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。

第六轮追加调整：
- 用户发现首页卡片图片/视频数量提示 UI 的横向 margin 和纵向 margin 不一致。
- 按用户要求，统一使用较大的数值：左右 margin 和底部 margin 均为 12dp。

第六轮追加验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。

第七轮追加调整：
- 用户觉得首页卡片图片/视频数量提示 UI 的 12dp margin 偏大。
- 将该 UI 的左右 margin 和底部 margin 统一调整为 10dp。

第七轮追加验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。

第八轮追加修复：
- 用户反馈记事详情界面图片位置按钮的白色看起来比删除按钮更亮。
- 用户反馈图片位置按钮和删除按钮按下后的反馈仍是正方形 ripple，应改为圆形 ripple。

修复处理：
- `ic_home_card_image_placement.xml`：将图标填充颜色从不透明白色调整为 `white_76p`，与现有删除图片附件 icon 的有效亮度保持一致。
- `ripple_attachment_icon_circle_light.xml`：新增带 oval mask 的浅色圆形 ripple。
- `attachment_image.xml`：图片位置按钮和删除按钮都改用同一个圆形 ripple 背景。

第八轮追加验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。

第九轮追加调整：
- 用户反馈详情页图片位置按钮和删除按钮的圆形 ripple 下边缘紧贴图片下边缘。
- 保持两个按钮的 40dp 点击区域不变，将圆形 ripple mask 四周内缩 2dp，让可见 ripple 略小并离图片边缘留出空隙。

第九轮追加验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。

追加修复：
- 用户反馈普通卡片和放大卡片在“图片在下方”时，图片和上方内容之间没有间距。
- 用户反馈放大卡片左右布局时，图片应该由其它元素高度限定高度，宽度继续按 42% 比例，之后对图片进行裁切，并确保图片附件数量提示 UI 留在图片内部。
- 用户反馈普通卡片和放大卡片互相切换后，图片比例和大小容易不正确，怀疑与 Glide 缓存或尺寸重设不足有关。

修复处理：
- `BaseThingsAdapter.kt`：底部图片在存在上方内容时为图片容器添加 16dp 顶部间距，同时继续隐藏底部 spacer，让图片贴住卡片底部。
- `BaseThingsAdapter.kt`：左右布局先按资源比例固定侧边图片宽度，再在 layout 后用非图片内容列的实测高度和 180dp 最小高度计算侧边图片高度。
- `BaseThingsAdapter.kt`：侧边图片容器使用固定高度，内部图片和 cover 使用 `MATCH_PARENT`，附件数量提示继续保留在 `fl_thing_image` 内。
- `BaseThingsAdapter.kt`：每次绑定图片时都会清理旧 Glide 请求，并用当前布局的 `width/height` 重新 `override()` 加载，避免 holder 复用或缓存沿用旧比例。

追加验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。

第二轮追加修复：
- 用户反馈图片位置选择 dialog 隐藏确认/取消按钮后，最后一个选项紧贴 dialog 底部。
- 用户反馈 full-span 左右布局的内容列底部留白仍偏大，判断可能来自侧边图片最小高度。
- 用户指出不能每次绑定首页卡片都重新绑定 Glide，只有图片尺寸或图片路径可能变化时才应该重新加载。

修复处理：
- `ChooserDialogFragment.kt`：当 chooser 隐藏底部 action row 时，为 RecyclerView 增加 8dp bottom margin，让最后一项和 dialog 底部保持和其它 dialog 接近的呼吸空间。
- `dimens.xml`：将 `thing_card_full_span_side_image_min_height` 从 180dp 调低到 144dp，减少短内容左右布局的底部空白。
- `BaseThingsAdapter.kt`：首页卡片图片加载新增 `path + width + height` load key。只有图片路径或目标尺寸变化时才 `clear()` 并重新 `override()` 加载；普通内容重新绑定但图片目标尺寸不变时不再重复 Glide 加载。

第二轮追加验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。

第三轮追加调整：
- 用户确认图片位置 dialog 底部间距使用 12dp。
- 用户确认 full-span 侧边图片最小高度使用 128dp。

调整处理：
- `ChooserDialogFragment.kt`：隐藏 action row 时的 RecyclerView bottom margin 从 8dp 调整为 12dp。
- `dimens.xml`：`thing_card_full_span_side_image_min_height` 从 144dp 调整为 128dp。

第三轮追加验证：
- `git diff --check` 通过，仅有 CRLF conversion warnings。
- `:app:assembleDebug` 通过。
