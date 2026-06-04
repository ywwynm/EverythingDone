# 自定义卡片外观视频裁切预览反馈修复

本次 debug update 回应用户在“裁切封面视频”dialog 里的反馈：

- 双指缩放、拖动视频区域实际上会改变裁切参数；
- 但预览画面没有跟着展示缩放程度和裁切位置，用户不知道当前裁切区域选到了哪里。

诊断结论：

- 当前 `ThingCardVideoCropEditorView` 使用真实 `TextureView` + `MediaPlayer` 预览视频。
- 之前的实现把 `TextureView` 固定布局在整张预览区域里，再通过 `TextureView.setTransform(...)` 尝试模拟最终裁切矩阵。
- 在暂停帧、首帧尚未输出、或者部分设备没有及时重绘 TextureView 的情况下，手势会更新 `centerX` / `centerY` / `userScale`，但画面不会给出可靠反馈。
- loading 状态还会隐藏裁切 overlay，因此即使手势有效，用户也看不到裁切框和遮罩。

实现修改：

- `ThingCardVideoCropEditorView` 不再依赖固定大小 `TextureView` 的内容 transform。
- 视频裁切 view 现在直接把 `TextureView` 本身布局到当前缩放后的源媒体矩形位置：
  `imageLeft` / `imageTop` / `scaled source width` / `scaled source height`。
- 这样暂停时拖动和双指缩放会立即改变屏幕上的视频几何位置，和图片裁切编辑器的预览逻辑一致。
- 打开 dialog 时已经解码出的那张视频帧现在会传入 `ThingCardVideoCropEditorView`，作为首帧前的 fallback 预览图。
- loading 或首帧回调缺失时，fallback 预览图会显示在同一个裁切 overlay 下，裁切框和非选区遮罩保持可见，用户仍能看见缩放比例和裁切中心。
- 如果设备没有触发首帧 `onSurfaceTextureUpdated`，fallback timeout 不再切到空白 TextureView；它只去掉 loading spinner，继续保留 fallback 预览，直到真正有视频帧输出。
- 更新 `docs/plans/THING_CARD_APPEARANCE_EXECUTION.md` 和 `memory/decisions.md`。

验证：

- `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 已通过。
- `git diff --check` 已通过，仅有仓库既有的 LF/CRLF 提示。
- source guard 确认 `FallbackFrameView`、`showFallbackPreviewOnly()`、fallback bitmap 传入、以及 `TextureView` 动态布局路径已接入。
- 尚未进行真机/模拟器视频裁切 smoke test；需要用户在设备上确认暂停、缩放、拖动、loading/首帧前的裁切预览反馈。

发布状态：

- 本次构建使用
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  发布到 debug update 通道。
