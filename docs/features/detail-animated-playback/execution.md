# 实现 / 验证清单 / 详情页动态内容播放（Detail Animated Playback）

设计见 [plan.md](plan.md)，决策见 [decisions.md](decisions.md)。验收编号 1–16 对应 plan.md 第四节。

**状态（2026-07-25）**：P1–P4 已实现、`:app:assembleDebug` 通过、debug 202607251240 已发布；P5 的真机实测未做。

## P1 设置项与模型

- [x] `Def.Meta` 新增 `KEY_AUTOPLAY_DETAIL_DYNAMIC = "autoplay_detail_dynamic"`（注释标 2026/7/25）
- [x] `FrequentSettings` 新增 `getInt(key, defValue)` 与 `getIntFromSp`；`loadFromSharedPreferences` 预载该键
- [x] 新增 `model/DetailAutoplayMode.kt`：四个常量 + `fromValue()` + `labelResOf()`；集中"哪些档位需要播放""哪些档位允许长按"的判断，不让调用方散着写 `if (mode == ...)`
- [x] `activity_settings.xml`：在 `rl_autoplay_cover_dynamic_as_bt` 之后插入 `rl_autoplay_detail_dynamic_as_bt`，用 `rl_doing_digit_style_as_bt` 的"标题 + 右侧灰字值"样式
- [x] `SettingsActivity`：`f()` 取值 TextView、`initUiUserInterface` 回填当前档位文案、行点击弹 `ChooserDialogFragment`、确认后立即写 SharedPreferences + `FrequentSettings.put`（**不**走 `saveSettings` 的批量路径，与语言项一致）
- [x] strings：`settings_autoplay_detail_dynamic` + `detail_autoplay_modes` string-array（中/英/繁体必须齐，其余语种沿用英文回退）

**验证**：验收 2、3。

## P2 可见性追踪

- [x] `DetailActivity` 持有 `DetailAttachmentPlaybackController`（新类，放 `helpers/`），构造时拿到 `sv_detail` 与 `rv_image_attachment`
- [x] 行级可见性计算：RV 在 `NestedScrollView` 中的 top + 行高 + `scrollY` + 视口高 → 每行可见面积比
- [x] 滞回：≥0.6 进入、≤0.3 离开，中间保持
- [x] 触发点接齐：滚动监听、RV 全局布局完成、`onResume`、附件增删/重排、`setTakingScreenshot`
- [x] 控制器把"某位置进入/离开视口"回调给 `ImageAttachmentAdapter`

**验证**：日志打点确认滚动时进入/离开事件不抖动（验收 7 的前半）。

## P3 详情播放调度

- [x] `ImageAttachmentAdapter` 增加 `playbackMode` 与 `onVisibilityChanged(position, visible)`；绑定时不再无条件走动图分支，改为"先绑静态代表帧，再按可见性与档位决定是否起播"
- [x] `ALL_LOOP`：`GifDrawable.setLoopCount(LOOP_FOREVER)`；离开视口 `stop()`，进入 `start()`
- [x] `ALL_ONCE` / `ONE_BY_ONE` / 长按：`setLoopCount(1)` + `registerAnimationCallback` 收 `onAnimationEnd` → 回静态代表帧（`ONE_BY_ONE` 再推进队列）
- [x] `ONE_BY_ONE` 队列：按索引升序的有序集合；进入插入、离开移除、播完出队；正在播的项离开视口立即让位
- [x] 静态代表帧：GIF → `dontAnimate()` 首帧；视频 → `frameOf(videoFrameMs)`；Motion Photo → 主图（**必须显式切回，不能停在派生 GIF 尾帧**）
- [x] 视频接入：`VideoCoverPreviewManager.getReadyPreview` / `requestPreview`，只在视口内且档位非 `OFF` 时请求；就绪回调复用既有的 `isImageViewUsable` + position/item 双重守卫
- [x] Motion Photo 现有的无条件 `requestGif` 改为受档位与可见性管控
- [x] 长按：`ImageViewHolder` 上加长按监听，**返回 false** 让 `ItemTouchHelper` 照常接管拖拽（D14/D15）
- [x] `mTakingScreenshot` 置位时立即全部冻结

**验证**：验收 4–10。

## P4 全屏视频播放

- [x] `ImageViewerActivity` 把 Motion Photo 那套播放器泛化：新增"视频页"分支，`setDataSource(path)` 而非 `(fd, offset, length)`
- [x] 自动播放：翻页停稳 ~360ms 后触发，`seekTo(videoFrameMs)` → 播 3 秒（用 `postDelayed` 或 `setOnSeekCompleteListener` + 定时停）→ `stopPlayback()` 回静帧；带触感
- [x] 播放头：Activity 级 `mVideoResumePosMs`，长按第一次用 0、之后用上次松手位置；翻页/退出重置
- [x] 长按：复用现有 `mMotionGesture` 的 `onLongPress`，按当前页类型分流
- [x] 播放期间 `iv_video_signal` 隐藏，`stopPlayback` 恢复
- [x] 音频焦点：新增小工具（`requestTransientDuck` / `abandon`），**Motion Photo 与视频两条路径都接上**
- [x] 防抖：`ViewPager` 页面 settled 后才起播；起播前若页面已变则放弃

**验证**：验收 11–15。

## P5 编译与实测

- [x] `:app:assembleDebug` 通过
- [x] 写 `debug-updates/update-<时间戳>.md`，`:app:publishDebugUpdate` 传 `-PdebugUpdateNotesFile`
- [x] 回填 `memory/debug-update-notes.md`（发布号 + APK SHA-256）
- [ ] 真机实测：默认档下 6–8 个动态附件的滚动帧率（验收 16）；掉帧则按 D13 加并发上限 6 并记进 decisions.md
- [ ] 真机走一遍验收 1–15

## 已知风险

- **滞回阈值 0.6/0.3 是拍的**，实测手感不对就调；调整记进 decisions.md。
- **`ONE_BY_ONE` 可能被长 GIF 阻塞**（D6 决定不封顶）。若实测难受，候选封顶 12 秒。
- **默认档是最重的一档**，性能护栏留到实测后（D13）。
- **全屏 3 秒定时停**：`MediaPlayer` 没有"播到某时刻停"的原生能力，用 `postDelayed` 实现，需在翻页/退出/长按接管时取消，否则会误停后续播放。
