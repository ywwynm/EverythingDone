# 实现方案

决策见 `decisions.md`；术语见根 `CONTEXT.md`；"单一共享裁切"理由见 ADR-0008。

## 现状（关键位置）

- 偏好 `KEY_DRAWER_HEADER`（`Def.kt:48`）同时被消费：
  - `views/DrawerHeader.kt:60` `updateDrawerHeader()` — 抽屉头图，`decodeFileWithRequiredSize` 320dp×180dp(16:9) + ImageView centerCrop。
  - `activities/StatisticActivity.kt:123` `initHeaderUI()` — 统计头图，`mHeaderHeight = screenWidth*1080/1920`(16:9)，所有 FAB/顶栏几何从 `mHeaderHeight` 派生。
- 选图入口：`activities/SettingsActivity.kt:1224` `showChangeDrawerHeaderDialog()`(TwoOptions 默认/更多) → `startChooseImageAsDrawerHeader()` → `onActivityResult`(`:241`) 仅 `getLocalPathName`，null 即报 `error_cannot_add_from_network`。持久化在 `storeConfiguration()`(`:1475`)。
- 可复用：`views/ThingCardCropEditorView.kt`（平移/捏合/按比例画框，`setCropBitmap` + `getTargetAspectRatio/getCropCenterX/Y/getCropUserScale` + `setTargetAspectRatio`）；`fragments/MediaCropAppearanceDialogFragment.kt`（Host 壳）；`helpers/MediaCropTransformation.kt` + `MediaCropBitmapRenderer.kt`（裁切渲染，`Crop(centerX,centerY,userScale,sourceAspectRatio=null)`）。
- 渲染约定（照 DetailAttachment）：target = 实际显示框(已是所选比例)，`Crop` 不带 `sourceAspectRatio`（`adapters/ImageAttachmentAdapter.kt:327`）。

## 步骤

### 1. 模型 + Def 键
- `model/DrawerHeaderImageCrop.kt`：data class `{ ratio=16/9, centerX=0.5, centerY=0.5, scale=1.0 }`，`toJson()/fromJson()`，`normalized()`（ratio 夹 [0.5,2.708]，center 夹 [0,1]，scale ≥1）。`isDefault()`。
- `Def.Meta` 加 `KEY_DRAWER_HEADER_CROP = "drawer_header_crop"`。

### 2. 三项菜单控件
- `res/layout/fragment_three_action_picker.xml`：仿 `fragment_two_action_picker`，三格等权（width=0dp+weight）图标在上、文字在下；第三格可 GONE。
- `fragments/ThreeOptionsDialogFragment.kt`：仿 `TwoOptionsDialogFragment`，`setStartAction/setMiddleAction/setEndAction`（end 可空→该格 GONE）、`setAccentBackground`、accent/暗色 tint 复用。
- 字符串（base + zh-rCN/zh-rHK/zh-rTW）：`settings_drawer_header_choose_image`、`settings_drawer_header_adjust_crop`、`drawer_header_ratio`。
- 第三格图标用现成 `act_adjust_card_appearance`。

### 3. SettingsActivity
- `onActivityResult` 图片分支改为 `getLocalPathName` → null 时 `FileUtil.copyUriToFile`(MIME postfix) 兜底；仍 null 才报错。成功后存入待定字段 `mPendingDrawerHeaderPath` 并**自动打开裁切编辑器**（新图 crop 取默认）。
- `showChangeDrawerHeaderDialog()` 改用 `ThreeOptionsDialogFragment`：默认/选择图片/调整裁切（无自定义图时隐藏"调整裁切"）。
- 实现 `MediaCropAppearanceDialogFragment.Host`，`REQUEST_DRAWER_HEADER`：内容 = 标题 + `ThingCardCropEditorView`(setCropBitmap：解码待定图首帧 + 当前 crop) + 比例 SeekBar([0.5,2.708] 线性映射 setTargetAspectRatio) + 取消/确定。确定时读回 ratio/center/scale 存入 `mPendingDrawerHeaderCrop`。accent 用 `App.defaultAccentBackground`。
- 在 `MediaCropAppearanceDialogFragment` 加 `REQUEST_DRAWER_HEADER` 常量。
- `storeConfiguration()`：写 `KEY_DRAWER_HEADER`(待定路径或默认) + `KEY_DRAWER_HEADER_CROP`(JSON)；变化时 `setResult(RESULT_UPDATE_DRAWER_HEADER_DONE)`。`initUI` 读 prefs 初始化待定字段与 TextView。

### 4. 渲染端
- `helpers/DrawerHeaderHelper.kt`（新建，集中加载逻辑）：`load(iv, path, crop, targetW)`：default 或文件不存在 → 设内置图并回退；否则 `Glide.load(File).override(tw,th).transform(MediaCropTransformation(tw,th,Crop(cx,cy,scale))).into(iv)`，`th = (tw/ratio)`。动图自动播放。
- `DrawerHeader.updateDrawerHeader()`：targetW = 320dp，读 crop，调 helper；default 用 `R.drawable.drawer_header`。
- `StatisticActivity.initHeaderUI()`：读 crop，`mHeaderHeight = screenWidth / ratio`（default 时 ratio=16:9 维持现状），targetW=screenWidth，调 helper（default 用 `R.drawable.drawer_header_large`）；其余 FAB/顶栏几何不动。

### 5. 编译 + 发布
- `:app:assembleDebug`，修错。
- 阿里云发布：`debug-updates/update-<ts>.md` 中文日志 + 对应 gradle 任务；**不连本机设备**。

## 风险/回归点

- 抽屉头图喂入"已按比例裁好"的位图后，`wrap_content+adjustViewBounds` 下的实际宽度需与现状一致（仍以 320dp 基准）。
- 统计 `mHeaderHeight` 改由 ratio 派生后，default 必须仍等于 `screenWidth*0.5625`（16:9）以保证老界面像素级不变。
- 极端比例（如 0.5）在窄抽屉里会很高——已接受，由用户自行不选。
- Glide 替换手动 `decodeFileWithRequiredSize`：注意内存与首帧解码（编辑器源用 BitmapFactory 取首帧，符合 ADR-0007）。

## 验收

- 选本地相册图不再误报"网络图片"。
- 选图后可调比例+平移+缩放，确定后抽屉与统计头图一致呈现该裁切。
- 选 GIF/动态 WebP：两处头图逐帧播放，编辑器内静态。
- 老用户升级后未改动的头图观感不变。
