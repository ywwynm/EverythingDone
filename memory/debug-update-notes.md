# Current Debug Update Notes

用户反馈：在“自定义卡片外观”面板里拖动封面图片比例或左右侧封面宽度时，记事卡片预览中的封面图片容易闪烁，短暂露出背后的 Thing Background 纯色或渐变背景。用户认可复用现有 drawable 并按新目标矩阵重算、作为 Glide placeholder 的修复方向，并要求修复后发布 debug update。

诊断结果：普通封面图片路径 `BaseThingsAdapter.loadThingCardImage()` 在 `override(imageW, imageH)` 随比例/宽度拖动变化时会生成新的 load key，旧实现会先 `Glide.clear()` 当前 `ImageView`，新 drawable 和 crop matrix 准备好前就会露出卡片背景。此前 media-background 路径已经有同源 drawable 复用和 placeholder 保护，但普通缩略图/左右侧封面没有同样处理。左右侧封面的 post-measure 校正 token 也只包含 Thing id、placement 和 media source，拖动中旧 post 可能继续参与当前 holder。

本次修改：更新 `app/src/main/java/com/ywwynm/everythingdone/adapters/BaseThingsAdapter.kt`，让普通封面图片在同一媒体源、同一视频帧但目标尺寸变化时复用当前 drawable，立即按新目标宽高和当前裁切矩阵重算显示，并把该 drawable 交给 Glide 作为 placeholder；只有没有可复用 drawable 时才清空目标。同源复用时也不再显示 loading spinner，避免拖动时每个新目标尺寸都闪出进度控件。保留原有 exact load-key 快路径和 render-request tag 保护，避免旧 Glide 回调覆盖当前裁切请求。左右侧封面的 bind token 现在包含投影后的封面宽高、裁切值和视频帧，避免旧的 post-measure 校正触发过期加载。

文档：已更新 `docs/features/thing-card-media-target-geometry/sessions.md`，记录这次分析和修复。

验证与发布：第一次 sandbox 内 `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache` 在编译 `swirl` 模块时因 Javac 无法创建输出目录失败；按规则使用提升权限重跑后通过。随后尝试运行 `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`，但安全审核拦截了向外部服务器上传 APK、`latest.json` 和更新说明的发布操作。用户在了解该外部上传风险后明确批准继续发布；发布任务已通过，debug update `202606060912` 已发布到 `http://120.25.194.207/everythingdone-updates/debug/latest.json`。
