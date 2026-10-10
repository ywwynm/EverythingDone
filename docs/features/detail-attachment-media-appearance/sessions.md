# Detail Attachment Media Appearance Sessions

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
