# 落地进度

## 清单

- [x] 1. `model/DrawerHeaderImageCrop.kt` + `Def.Meta.KEY_DRAWER_HEADER_CROP`
- [x] 2. `ThreeOptionsDialogFragment` + `fragment_three_action_picker.xml` + 字符串（base/zh-rCN/zh-rHK/zh-rTW）
- [x] 3. SettingsActivity：稳健选图 + 三项菜单 + 编辑器宿主 + 持久化
- [x] 4. `DrawerHeaderHelper` + DrawerHeader/StatisticActivity 渲染
- [x] 5. `:app:assembleDebug` 通过，产出 app-debug.apk（17.2 MB）
- [x] 6. 阿里云发布 `202606242151`（UTC）+ debug-updates 日志；未用 adb

## 笔记

- **稳健选图**：`SettingsActivity.onActivityResult` 图片分支改为 `getLocalPathName` → null 时 `FileUtil.copyUriToFile(getPostfixFromMimeType)` 兜底，仍 null 才报错；音频分支保持原样。`isSupportedFilePostfix` 的 IMAGE 分支现已无人走（保留无害）。
- **渲染**：新建 `helpers/DrawerHeaderHelper`，`resolve()` 统一默认/缺文件/无权限回退；`loadCustomInto()` 用 `Glide.load(File).override(tw,th).transform(MediaCropTransformation(tw,th,Crop(cx,cy,scale)))`，`th = tw / ratio`。静态烤一帧、动图逐帧播放（ADR-0007）。DrawerHeader 用 320dp 基准，Statistic 用屏宽。
- **统计几何**：`StatisticActivity.mHeaderHeight` 改由 `DrawerHeaderHelper.targetHeight(screenWidth, crop)` 派生；default 时 ratio=16:9 → `screenWidth*0.5625`，与旧 `screenWidth*1080/1920` 像素一致，FAB/顶栏渐隐无需改动。先算 `mHeaderHeight` 再装 inset 监听，避免回调读到旧值。
- **编辑器**：`SettingsActivity` 实现 `MediaCropAppearanceDialogFragment.Host`（`REQUEST_DRAWER_HEADER`），内容 = 标题 + `ThingCardCropEditorView` + 连续比例 `SeekBar`（线性映射 [0.5,2.708]，slider max 1000）+ 取消/确定；accent 用 `App.defaultAccentBackground`。`Crop` 不带 `sourceAspectRatio`，target=显示框，与 DetailAttachment 约定一致，保证编辑器与显示所见即所得。
- **三项菜单**：`ThreeOptionsDialogFragment` + `fragment_three_action_picker.xml`，第三格（调整裁切）仅在已设自定义图时出现。
- **持久化/迁移**：crop 存 `KEY_DRAWER_HEADER_CROP` JSON，缺键取默认（老头图观感零变化）；`storeConfiguration` 在路径或 crop 变化时 `setResult(RESULT_UPDATE_DRAWER_HEADER_DONE)`。
- **遗留小项**：`StatisticActivity` 的 `Bitmap`/`BitmapUtil`/`File` 与 `SettingsActivity` 的 `TwoOptionsDialogFragment` 等 import 现已未用（仅警告，不影响构建），可后续 lint 清理。

## 远程测试反馈修复（发布 202606242212）

- **三项菜单回退原色**：去掉 `showChangeDrawerHeaderDialog` 里的 `setAccentBackground`，图标/文字不再 accent 渐变。
- **第三格尺寸对齐**：旧两项图标是 ~100dp PNG，`act_adjust_card_appearance` 只有 24dp 矢量，导致第三格过小。新增 100dp 矢量 `act_adjust_drawer_header`（标准 crop 图标，viewport 24 放大到 100dp），并把 `fragment_three_action_picker.xml` 改回与 `fragment_two_action_picker.xml` 完全一致的样式（仅 `paddingBottom=16dp`，无额外内边距），三格同构。
- **比例滑块带刻度**：把裁切编辑器里的裸 `SeekBar` 换成 `ThingCardRatioTicksView` + `SeekBar` 的 `FrameLayout`，复刻 DetailActivity 的刻度与吸附（presets `[0.5, 1, 4/3, 3/2, 16/9, 2, 65/24]`、范围 `[0.5, 65/24]`、吸附距离 28、slider max 1000）。新增 `snapDrawerHeaderRatio` / `getSnappedDrawerHeaderRatioForSeekBar`。
