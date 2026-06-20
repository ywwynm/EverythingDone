# Current Debug Update Notes

## 2026-06-20 - 修复 activity header 边界闪烁和两行文件夹标题缩放
这次 debug update 修复正在进行界面和文件夹界面滚动到 action bar 附近时的 header 闪烁：

- 用户反馈：记事列表向上滑动到第一个可见卡片接近 action bar 时，activity header 的标题可能突然消失，action bar 阴影也可能突然消失；再滑动一点又会闪烁回来。文件夹界面同样会发生，并且“X个文件夹，Y件记事”的副标题可能突然出现在列表卡片下面，被列表挡住。
- 根因是 `ActivityHeader.updateAll(...)` 还沿用旧的 102dp header 高度假设：当 `scrollY >= 102dp` 时会把 `scrollY` 重置为 0。RecyclerView 在边界处可能仍然认为第 0 个不可见 header spacer 可见，但它的 top 已经超过旧的 102dp 阈值，于是 header 状态会从“折叠”突然跳回“展开”，导致标题、阴影和副标题 alpha 闪烁。
- 现在这段逻辑改为把 `scrollY` clamp 到当前真实 header spacer 高度，不再把超出值重置为 0。这样标题、subtitle alpha 和 action bar 阴影会连续变化，不会在 header spacer 边界跳变。
- `ThingsActivity` 里所有传给 `ActivityHeader` 的 first visible position 也改为取 staggered grid 所有 span 的最小可见 adapter position，而不是只取 `positions[0]`，避免多列边界时误判第一个可见项。
- 如果文件夹名称折叠到 action bar 时需要显示两行，最终标题 scale 会比普通单行折叠标题更小一点，并且这个额外缩小会跟随同一个滚动折叠进度连续完成。两行标题的竖直居中计算也会立即使用两行视觉高度，避免抵达 action bar 时位置跳一下。
- 相关实现主要涉及 `ActivityHeader.kt` 和 `ThingsActivity.kt`。

验证状态：

- 源码检查确认旧的 `scrollY >= 102dp -> 0` 重置路径已经移除，`positions[0]` 传给 ActivityHeader 的路径也已经移除。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200248`，并回读远端 `latest.json` 确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200248.apk`，SHA-256 为 `8c967fa38d199131fec7b5129e0248c0101cc2c0039abc1c0cedb5ac7d4ebcaf`。

## 2026-06-20 - 修复文件夹内 header 滚动闪退和折叠居中
这次 debug update 针对文件夹内 header 滚动造成的闪退做根因修复：

- 用户提供的崩溃日志显示：`RecyclerView` 在触摸滚动过程中进入 `StaggeredGridLayoutManager.fill(...)`，随后抛出 `IllegalArgumentException: Called attach on a child which is not detached`。崩溃对象是 `rv_things` 的第 0 个 header spacer 对应的 holder。
- 根因是上一版为了支持长文件夹名，把 header 的真实测量高度同步给 RecyclerView 的不可见 spacer；但 `ActivityHeader` 在滚动过程中会改变标题宽度和折叠行数，触发 header 重新测量，继而在 `RecyclerView` 正在布局/attach 子 View 时执行 `notifyItemChanged(0)`，导致重复 attach 崩溃。
- 现在 spacer 不再跟随滚动过程中的 header 布局变化更新。它只在明确的展开态刷新点更新，例如进入/切换文件夹后的 `updateText()`，或者 header reset 之后。
- `ThingsActivity` 侧新增了一层保护：spacer 高度请求只保留最后一次，并且会等到 `RecyclerView` 不在 `isComputingLayout`、滚动状态也已经回到 `SCROLL_STATE_IDLE` 后，再应用到 adapter，避免以后类似路径再次在布局中途通知第 0 项。
- 文件夹名称折叠到 action bar 后的竖直居中也一起修正：折叠平移会根据当前标题可见布局重新计算，包含文件夹名最多两行时的实际视觉高度，而不是用展开态 header 块高度硬算。
- 相关实现主要涉及 `ActivityHeader.kt` 和 `ThingsActivity.kt`。

验证状态：

- 已把崩溃日志对应到 `RecyclerView` attach 路径，并确认 `updateHeaderSpacerHeight()` 不再从滚动更新路径触发。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 当前工作区只检测到一台物理设备，没有默认 emulator；为避免擅自操作真机，本次没有执行 ADB 安装冒烟测试。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200220`，并回读远端 `latest.json` 确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200220.apk`，SHA-256 为 `47d0066c617659570aa5a86e1dcaa0951c20240098850a71d575b713d2dacc9f`。

## 2026-06-20 - 修正文件夹移动禁用态和文件夹内 header 显示
这次 debug update 继续修正文件夹移动 dialog 和进入文件夹后的 activity header 细节：

- 用户反馈：移动文件夹时，dialog 里源文件夹和它的子树已经不可选，但 icon 和文字仍然看起来像可选状态。现在这些行仍然可以展开查看层级，但文件夹 icon 和标题文字会使用 App Chrome 的 disabled 前景色，明确表达“不可选”；右侧展开/收缩按钮保持正常可点。
- 用户反馈：进入文件夹后，header 不需要显示完整路径，也不应该变成蓝色下划线链接。现在文件夹内 header 只显示当前文件夹名称，颜色和样式保持跟首页“正在进行”一致。
- 文件夹内 header 的副标题从“X项内容”改为直接显示“X个文件夹，Y件记事”，由 `ThingManager.getVisibleChildCountsForActivityHeader()` 按当前列表中的直接子文件夹和直接记事分别统计。
- 对较长文件夹名做了布局处理：展开状态下标题右侧会比右侧卡片更靠左；折叠到 action bar 区域时，标题最多显示两行，并且宽度会限制在搜索等 toolbar action 左侧；标题宽度会随现有 header 折叠进度变化。
- `ActivityHeader` 会根据真实测量高度刷新 RecyclerView 顶部的不可见 header spacer。文件夹名换成多行时，列表第一个可见卡片会跟着下移，避免文件夹信息和列表内容互相覆盖。
- 相关实现主要涉及 `MoveToThingFolderDialogFragment.kt`、`ActivityHeader.kt`、`ThingManager.kt`、`ThingsAdapter.kt`、`ThingsAdapterWrapper.kt` 和 `ThingsActivity.kt`。

验证状态：

- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606200208`，并回读远端 `latest.json` 确认 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200208.apk`，SHA-256 为 `ab98531bd6fb30a47c054d67916d73fafccac2d4f604729ec9f9c3e9f689f503`。

## 2026-06-20 - 修复文件夹移动、私密展开、暗色 overflow 和媒体计数颜色

这次 debug update 继续修正文件夹树、私密文件夹和卡片外观相关的细节：

- 移动文件夹时，“移动到文件夹”dialog 现在会显示源文件夹和它的整棵子树；这些行可以展开查看层级，但会以不可选状态显示，不能作为移动目标。
- 移动文件夹 dialog 的文件夹列表改为稳定预留上下分割线和底部按钮间距。展开后从不可滚动变为可滚动时，不再因为动态切换 margin 或 `GONE` 分割线导致 dialog 内容抽动、闪烁。
- 移动文件夹/记事 dialog 里展开私密文件夹使用本次 dialog 内的临时验证状态；dialog dismiss 后再次展开私密文件夹需要重新验证。当前已经处于已验证的私密文件夹路径内时，不会重复验证。
- Drawer 中有子文件夹的私密文件夹会继续显示展开/收缩按钮。点击展开按钮时需要密码或指纹验证；关闭 Drawer 后，这次展开验证会失效，并折叠不在当前私密路径内的私密子树。
- 暗色模式下，进入文件夹后右上角的 overflow icon 会使用 app accent 黄色。返回“正在进行”根目录时会刷新 options menu，让当前文件夹专用的 overflow 入口消失。
- 图片/视频位于记事卡片上方、下方、左侧或右侧时，覆盖在黑色背景条上的“X张图片,Y个视频”提示文本和图标保持偏白色。调整记事卡片外观并改变颜色时，不再把这组覆盖层计数实时改成跟随记事颜色的自适应前景色；只有内联媒体计数继续跟随卡片前景色。

验证状态：

- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过，结果为 `BUILD SUCCESSFUL`。
- 已使用 `:app:publishDebugUpdate` 发布 debug update `202606191645` 到 debug update channel，并已回读远端 `latest.json`，确认当前 APK 为 `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606191645.apk`，SHA-256 为 `dadbcec9b7b43faf4465f9c5e4bf9a973a68fc6f3955637bd32e6793e8ced849`。
