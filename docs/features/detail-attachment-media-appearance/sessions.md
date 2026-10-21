# Detail Attachment Media Appearance Sessions

## 2026-10-21 — 详情附件外观解析结果复用

外观工厂接入按完整 JSON 匹配的有界缓存，来源和呈现集合不可修改。裁剪、比例、选帧及导入重写继续创建新值，实际外观修改后重新进入可读到最新数据，三机小组件后台并发读取回归通过。随 debug `202610210308` 发布并安装三机，完整语义、像素及数据保护证据见 [详情转场解析优化](../thing-detail-transition/appearance-parsing-2026-10-21.md)。没有 Git 提交。

## 2026-10-15 — 用户确认提交附件统一配色修订

- 用户确认提交，将附件共同配色实现、JVM 测试、debug 探针及脚本、功能文档、发布日志和验证归档一并纳入提交；提交说明使用英文在前的中英双语。
- 沿用已通过的 37 项 JVM 回归和两台设备横竖屏 140 项检查，本次只整理并提交已验证的改动，没有重新运行测试或重复发布。下方“未提交 Git”描述此前发布时的状态。

## 2026-10-15 — 同一附件上的标识与按钮统一黑白

- 用户反馈独立配色显得杂乱，要求 GIF 等标识与图标按钮统一颜色。按附件汇总各元素局部样点，每个元素等权，保留原有透明度、镂空和文字/图形各自的参考对比度。
- 共同评分继续使用静态封面；新增标识先继承附件当前颜色，避免异步检测期间混色。不同附件保留各自结果，顶部按钮既有共同配色测试继续通过。
- 37 项 JVM 回归及两台授权设备横竖屏共 140 项检查通过；8 条已有记事不变，32 条临时记事清理完成，旋转恢复。视觉核验范围与限制见[实施记录](unified-overlay-2026-10-15.md)。
- debug `202610150857` 已发布，线上元数据及 APK 哈希、大小均校验一致；16:58:31 覆盖安装到两台指定设备。本轮改动留在工作区，未提交 Git。

## 2026-10-11 — 用户确认提交附件自适应配色

- 用户要求代码、测试和相关文档一起提交，并用中英文写清改动与验证结果。将生产实现、debug 回归入口与 runner、JVM 测试、六份最终实机结果、性能汇总、设计决定和独立发布说明纳入本次功能提交。
- 提交说明记录静态封面局部对比度规则、后台计算与有界缓存、Flyme 兼容，以及 11 项 JVM 回归、三机 238 个检查项、19 条已有记事数据校验和性能读数；保留 GPT-6 共同作者信息。
- 本次只整理和提交已验证的改动，没有重新运行设备测试或重复发布。下方“未创建 Git 提交”描述此前发布时的状态。

## 2026-10-11 — 实现静态封面局部配色并验证速度

- 用户接受局部对比度算法，要求开始实现、保证速度，并授权三台设备覆盖已有记事。各图标和标记按实际绘制区域独立选择半透明黑白，保留形状、透明度、镂空与记事色波纹；比例、裁剪、选帧及布局变化后更新，播放期间固定。
- 小封面并行后台准备，静态显示位图先就绪时直接取小副本；点集、评分和位图着色后台执行，有界缓存复用。适配魅族强制深色改写画笔／颜色滤镜的问题。
- 六组横竖屏共 238 个检查项通过，覆盖 19 条已有记事；整行指纹保持一致，42 条最终测试临时记事已清理，系统旋转已恢复。11 项定向 JVM 回归通过。
- 配色主线程开销合计中位数 0.419 ms、P95 1.861 ms，最大 5.162 ms；缓存重开颜色就绪 2–10 ms。首次媒体解码另计，测量条件和原始结果见[实施记录](implementation-overlay-contrast-2026-10-11.md)。未创建 Git 提交。
- 已发布阿里云 debug `202610101718`，公开元数据、说明、APK 大小和完整 SHA-256 均与本地产物一致；最终发布包已在三机覆盖安装，[独立发布说明](debug-updates/update-20261011011704.md)已归档。

## 2026-10-11 — 补充黑白选择的算法说明

- 用户要求明确具体判定规则。核对发现通用明暗函数使用固定阈值 150，未处理照片局部纹理与透明度；拟采用半透明黑白各自合成后的相对亮度对比度。
- 建议优先比较达到参考对比度的局部覆盖比例，同分再比较整体对比度，并对白黑相同的情况规定确定性结果。该规则仍是建议，不将其记录成用户已接受的决定。
- 查阅 W3C 原始定义并用现有外观图标 `#C4` 不透明度计算深灰、中灰、浅灰示例；来源、公式和适用边界已写入设计记录。本轮仍未修改应用代码或进行设备验证。

## 2026-10-11 — 确定附件标记与按钮的自适应配色设计

- 用户报告半透明白色元素在浅色媒体内容上难以辨认，通过 `grill-me` 确认三项规则：逐个元素独立选择黑白；只依据静态封面及当前裁剪，播放期间固定；不增加描边、阴影或衬底。
- 保留原有透明度和 HDR／GIF 镂空文字，比例、裁剪、视频选帧或布局变化后更新局部判断。
- 核对附件布局、静态裁剪、动态播放和异步标记路径，整理静态封面独立取样、几何变化、视图复用和缓存要求。详见[设计记录](adaptive-overlay-contrast-2026-10-11.md)。
- 本轮仅修改功能文档，尚未实现、构建、发布或连接设备，未创建 Git 提交。

## 2026-10-11 — 修复首次比例滑条及横屏弹窗滚动

- 用户反馈首次打开外观弹窗无法拖动比例，缓存重开正常。实机确认加载前边保存状态边禁用，导致组合控件内部 SeekBar 的临时禁用状态被误记为原值；改为完整保存后再禁用，加载完成正确恢复。
- 扩展检查发现横屏多附件或视频内容超过窗口，滑条和按钮被裁掉。用户确认一并修复，并要求参考应用语言弹窗：固定标题/操作区，仅中间内容滚动，分割线按上下方向是否仍有内容显示。
- 滚动视口按剩余窗口高度收缩，布局与滚动后刷新指示线；单独裁剪滚动区域，避免外层为波纹允许越界绘制时，媒体和滑条覆盖固定区。
- 新增 debug 比例回归套件，覆盖三机横竖屏、图片/视频、单媒体/多媒体首项、首次/缓存重开。最终 48 次比例拖动、48 次裁剪手势、18 次可滚动窗口真实纵向手势均通过；固定区、动态指示线和取消/确认检查通过，19 项单测通过。详细证据见[诊断记录](diagnosis-2026-10-11-first-open-slider.md)。
- 六轮最终验证清理 24 条临时记事并恢复原系统旋转。用户在发布后确认提交，修复代码、debug 回归入口、原始验证结果和相关文档一并纳入本次提交；使用详细中英双语说明，并注明 GPT-6 为共同作者。
- 已发布阿里云 debug `202610101618`，线上元数据、发布说明及下载 APK 的字节数和 SHA-256 与本地产物一致；[独立发布日志](debug-updates/update-20261011001749.md)已归档。

## 2026-10-10 — 修复单媒体横屏默认比例

- 用户反馈未设置外观的单图片/视频可能过宽，打开外观直接确认后恢复默认比例。已复现：旧横屏尺寸分支给出 `3:1`，而确认后按模型 `4:3` 显示，手机和平板均受影响，与源图比例无关。
- `AttachmentHelper.calculateImageSize()` 优先处理单媒体，从外观模型读取默认比例，消除横竖屏与确认前后的分歧；不自动写入外观，也不改变多附件旧网格与已保存比例。
- 增加 debug 专用 `DetailMediaGeometryProbe` 及可重复 runner，真实 Adapter 矩阵修复前失败、修复后通过；三台授权设备横竖屏 × 横图/竖图/方图/超宽图/视频共 30 个真实详情场景通过，16 项相关单测通过。测试行清理、旋转恢复。
- 已发布阿里云 debug `202610101550`，线上版本、说明、APK 字节数和完整 SHA-256 均校验通过。证据与边界见 [诊断记录](diagnosis-2026-10-10-default-ratio.md)。用户随后明确要求提交，代码、回归入口、原始验证结果和相关文档一并纳入本次修复提交。

## 2026-10-10 — 用户确认提交

- 将已发布并完成定向验证的分阶段预览、元数据/位图缓存与取消处理、加载中控件状态及记事渐变进度纳入详情性能专项提交。前述记录中的“未创建 Git 提交”描述发布当时状态。

## 2026-10-10 — 根据尺寸提前显示弹窗

- 已随阿里云 debug `202610100704` 发布，线上元数据、发布说明及 APK 完整校验通过；包含后续追加的渐变进度条，未创建 Git 提交。
- 用户补充加载反馈要求后，图片进度条复用首页绘制与 28 dp 尺寸，颜色使用当前记事的纯色或完整渐变。三机纯色/渐变加载中重建通过，实机截图确认加载环；缓存命中仍直接显示图片。
- 用户确认触摸 ripple 已及时响应，要求继续缩短弹窗出现等待。现先后台读取图片/视频尺寸、方向及时长，按原几何显示窗口并播放既有动画，完整预览异步填入。
- 原图精度和预览像素不变，加载前后窗口尺寸一致；加载中可取消、禁止提前确认，缓存命中直接显示完整图片。正常新窗口重建保留尺寸并继续异步准备。
- 三机附件操作、取消及图片/视频 × 粒子开关 × 加载中重建通过；指定大图首次预绘制约 0.20–0.27 秒，完整预览仍需约 1.2–2.0 秒。详见 [本轮证据](../detail-checklist-performance/staged-preview-2026-10-10.md)。

## 2026-10-10 — 优化打开外观编辑器时的主线程阻塞

- 已随阿里云 debug 更新 `202610100543` 发布，线上安装包和发布说明校验通过。
- 真实大图复现同步解码约 0.9 秒阻塞主线程，导致按钮 ripple 停顿。常规点击已改为后台解码/视频取帧及读取时长，保留原预览精度、控件外观与动画。
- 每个详情页只保留最近一次有界预览缓存，按文件状态及选帧失效；后续附件不构建固定隐藏的比例滑杆。再次打开约 40–76 ms，首开总等待仍有波动，未宣称首次稳定加速。
- 三机通过加载器取消/缓存回归与附件外观完整操作组，魅族补查等待中返回、主题关闭与退出；同一真实图片的同步/异步预览像素一致，原有业务数据未变。
- 详细数据、首滑独立诊断及发布归档见 `../detail-checklist-performance/latency-2026-10-10.md`。

## 2026-06-24 - 单媒体默认 full-span 比例修正

- 根据用户确认的产品规则，区分 Detail 附件的运行时默认展示和用户确认过的外观属性：只有一个图片或视频时默认满宽 `4:3`，但不自动写入 `Detail Attachment Media Appearance`；增加第二个媒体后，未确认设置的默认行为回退到普通网格 `1:1`。
- `DetailAttachmentMediaAppearance.defaultTargetAspectRatio(...)` 现在对 `grid` 返回 `1:1`，对 `fullSpan` 返回 `4:3`。用户在单媒体状态下打开外观设置并确认后，当前 full-span 和比例才作为用户设置持久化。
- 已随同 Widget 媒体几何修正通过 `:app:assembleDebug`，并发布 debug update `202606241314` 到阿里云 debug 通道。

## 2026-06-17 - Bake customized attachment crops before display

- Migrated customized Detail attachment thumbnails away from
  `ImageView.imageMatrix`.
- `ImageAttachmentAdapter` now includes the crop fingerprint in the customized
  load key, disables hardware bitmap decoding for customized requests, bakes
  the requested crop into a target-sized bitmap with `MediaCropBitmapRenderer`,
  and sets that bitmap directly on the attachment `ImageView`.
- Legacy non-customized attachment grids remain on Glide's normal
  `centerCrop()` path, preserving the upgrade behavior for Things without saved
  Detail Attachment Media Appearance settings.
- Verified as part of debug update `202606171256`.

## 2026-06-08 - Product and architecture grilling

- Created the Detail Attachment Media Appearance feature documentation area.
- Clarified that Detail attachment media presentation is separate from Thing
  Card Appearance persistence while reusing the same crop and target-ratio
  concepts.
- Recorded decisions for per-attachment source keys, separate `grid` and
  `fullSpan` presentations, source-level `fullSpanEnabled`, video frame
  selection, legacy layout preservation, reorder behavior, deletion cleanup,
  import/export, and non-Detail surface isolation.
- Added ADR 0004 to document the storage boundary and per-source presentation
  model.
- Added implementation and verification plans for the future build.

## 2026-06-08 - Initial implementation

- Added the `detail_attachment_media_appearance` Thing-owned JSON model and
  database v14 column.
- Wired the new field through `Thing`, parceling, cursor loading, DAO create
  and update paths, Detail no-update comparison, and backup/restore-by-database
  behavior.
- Updated `DetailActivity` and `ImageAttachmentAdapter` so legacy attachment
  layout is preserved until a current media source has saved Detail appearance.
- Added full-span first-media rendering, 1:1 grid crop rendering, crop matrix
  projection, source-level video frame thumbnails, attachment-tile edit entry,
  and undo/redo support.
- Added a Detail attachment appearance dialog that reuses the existing crop
  editor views, ratio ticks, App Chrome styling, video frame controls, and
  confirm/cancel semantics.
- Verified with `.\gradlew.bat :app:assembleDebug`; the final run completed
  successfully.

## 2026-06-08 - Debug update publish

- Updated `memory/debug-update-notes.md` with the Detail attachment media
  appearance implementation, product corrections, verification state, and
  testing focus.
- Published debug update `202606081117` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.
- Remote debug metadata:
  `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

## 2026-06-08 - Width UI and crop-center correction

- Replaced the editor's temporary checkbox-style full-span control with a
  Thing Card Appearance-style pill row labelled `图片显示宽度`, with `正常` and
  `宽` choices.
- Changed the visible ratio-control label to `图片显示比例` and kept the
  technical `fullSpan` term out of visible UI.
- Updated the editor title to use the current Thing background/accent text
  treatment, and kept the dialog surface and text colors on App Chrome
  resources so dark mode remains resource-driven.
- Fixed Detail thumbnail crop rendering by handling Glide's custom-mode
  `onResourceReady` path inside `ImageAttachmentAdapter`, setting the drawable
  and applying the crop matrix before returning `true`.
- Verified the correction with
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606081231` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-08 - Crop persistence and render-request correction

- Increased the `图片显示宽度` label column to keep the prompt on one line on
  the user's device.
- Compared Detail attachment crop editing with the existing Thing Card
  Appearance crop pipeline.
- Fixed a persistence bug where Detail attachment appearance edits were written
  into `mThing.detailAttachmentMediaAppearance` before the save/no-update
  comparison, making a crop-only edit look unchanged and preventing the
  database update.
- Kept Detail attachment appearance as an independent draft until
  `createThing(...)` or `updateThing(...)` normalizes and writes the final value
  into `mThing`.
- Updated `ImageAttachmentAdapter` to mirror the card thumbnail render-request
  pattern: store load key, target dimensions, and crop on the `ImageView`, apply
  crop after Glide's drawable update, and reapply crop immediately when only the
  crop changes for an already-loaded source.
- Verified with
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606081259` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-08 - Auto-save draft persistence fix

- Confirmed a bug in `DetailActivity.saveAfterOnPause()`: the auto-save path
  wrote title, content, attachment, card span, placement, and color directly
  into `mThing`, then called `ThingManager.create/update`, but did not copy the
  Detail Attachment Media Appearance draft into
  `mThing.detailAttachmentMediaAppearance`.
- Added `applyDetailAttachmentMediaAppearanceDraftToThing(attachment)` so
  auto-save, `createThing(...)`, and `updateThing(...)` share the same
  normalize-and-write-back step before persistence.
- This keeps confirmed crop, full-span, ratio, and video-frame edits from being
  lost when the user backgrounds the app before an explicit save.
- Verified with
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606081353` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-08 - Video width-toggle loading fix

- Diagnosed a video editor loading-state bug when switching the Detail
  attachment presentation between `正常` and `宽`.
- The Detail editor reused the same `ThingCardVideoCropEditorView` and called
  `setCropVideo(...)` for the alternate presentation. The view turned loading
  on, but `preparePlayer(...)` returned early because a `MediaPlayer` already
  existed, so no prepared/seek/first-frame callback turned loading off.
- Updated `ThingCardVideoCropEditorView.setCropVideo(...)` to release and
  re-prepare only when the source path changes. For an already-prepared player
  on the same source, it now updates ratio/crop/frame, hides loading, and seeks
  without restarting preparation.
- Verified with
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.
- Published debug update `202606081448` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-14 - Media-specific dialog wording and spacing polish

- User requested that the Detail attachment appearance dialog no longer use the
  generic `附件外观` title. Image attachments now use `图片外观`; video
  attachments now use `视频外观`.
- The full-span ratio label now follows the media type: images use
  `图片显示比例`, while videos use `视频显示比例`.
- Increased the generated dialog title margin model to match the larger
  dialog convention: title start/end margins now use `24dp`, and the title top
  margin now uses `24dp` instead of a root-level `18dp` top padding.
- Tightened preview spacing: preview top margin changed from `16dp` to `10dp`,
  video-frame controls top margin from `12dp` to `8dp`, ratio controls top
  margin from `12dp` to `8dp`, and the action-row top margin from `20dp` to
  `16dp`.
- Added localized strings for the new image/video-specific title and ratio
  labels.
- Verification: the first
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  run exposed an Android string-resource apostrophe escaping issue in French;
  after escaping `l'image` as `l\'image`, the same assemble passed.
- Published debug update `202606141538` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-14 - Dialog spacing and video width label follow-up

- User reported that the Detail attachment appearance dialog preview still sat
  too far from nearby controls, and requested the home Thing Card Appearance
  precise crop dialog use the same spacing values.
- Confirmed the two dialogs are not the same dialog class. `DetailActivity`
  builds the Detail attachment appearance dialog, while `ThingsActivity` builds
  the Thing Card precise crop dialog. They do share
  `ThingCardCropEditorView` and `ThingCardVideoCropEditorView`.
- Tightened both dialogs' preview-adjacent spacing to the same values: preview
  top margin `6dp`, video-frame-controls top margin `6dp`, ratio-controls top
  margin `6dp`, title horizontal/top margins `24dp`, content horizontal margin
  `24dp`, and action-row top margin `16dp`.
- Changed the Detail video width prompt to `视频显示宽度` while keeping image
  attachments on `图片显示宽度`.
- Changed both dialogs' ratio prompt text colour from
  `app_chrome_on_surface_secondary` to `app_chrome_on_surface_hint` so ratio
  prompts match the width prompt weight in light and dark mode.
- Verification: `git diff --check` passed with CRLF warnings only, and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  completed successfully.
- Published debug update `202606150235` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.
- Published debug update `202606141559` with
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`.

## 2026-06-15 - Shared media crop DialogFragment refactor

- User pointed out that the Detail attachment appearance editor should not be
  backed by a raw Activity-created `android.app.Dialog`, because the project
  prefers custom `DialogFragment` implementations under `fragments`.
- Replaced the initial multi-wrapper idea with a single
  `MediaCropAppearanceDialogFragment` shared by Detail attachment appearance
  and the home Thing Card precise crop editor.
- Kept the Detail-specific editor content and save callbacks in
  `DetailActivity`, but moved the dialog lifecycle, tag, width, and cleanup
  boundary to the shared custom DialogFragment path.
- Verification: `git diff --check` passed with CRLF warnings only, and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  completed successfully.
