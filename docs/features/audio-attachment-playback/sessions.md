# 会话记录 / 音频附件播放对话框

## 2026-07-25 — 首版实现

用户要求：详情页点音频附件卡片本身（不是右侧播放/暂停按钮）改为进入 FableSol 动画对话框，
布局参照录音对话框（水体 + 计时器 + 文件名 + 进度滑杆 + 上一曲/播放暂停/下一曲），
播完自动播下一个，动画用实时分析。

先摸清了现状：`AudioAttachmentAdapter` 的卡片点击与按钮点击都走同一个 `togglePlay()`（就地 `MediaPlayer`）；
FableSol 在 Android 上的唯一数据源是 `AudioRecorder.RecordingThread` 的麦克风 PCM，
没有任何文件输入接口；项目没有 ExoPlayer 依赖。据此定下 [decisions.md](decisions.md) 的 D1–D9。

落地内容：

1. **播放引擎** `views/recording/FableSolAudioFilePlayer.kt`（新）——
   `MediaExtractor` + `MediaCodec` 解码 → `AudioTrack`（`WRITE_NON_BLOCKING`，暂停/退出可即时响应）
   → 单声道样本进环形缓冲 → 按 `playbackHeadPosition` 以 512 样本一批喂
   `FableSolRealtimeAnalyzer(sr, PHONE_CAPTURE_V1)`，产出的 frames/events 直接分发给
   `FableSolFrameReceiver`（与 `AudioRecorder.linkFableSol` 同一套接口）。
   seek 走 `AudioTrack.flush` + `MediaCodec.flush` + `extractor.seekTo`，
   基准帧数取解封装器实际落点（`sampleTime`）而不是请求值。
2. **对话框** `fragments/AudioPlayDialogFragment.kt` + `res/layout/fragment_play_audio.xml`（新）——
   骨架与 `fragment_record_audio.xml` 同源；新增文件名 TextView、进度 SeekBar，
   录音键位换成「上一曲 / 播放暂停 / 下一曲」；新增两个矢量图标
   `act_fablesol_previous.xml` / `act_fablesol_next.xml`。
3. **入口** `AudioAttachmentAdapter`：卡片点击改为 `openFableSolPlayer(position)`，
   先停就地播放再开对话框；按空洞项对齐后的索引传给对话框（`mItems` 理论上可含 null）。
   `DetailActivity` 的外观变更关闭清单里加上新 TAG。
4. **文案**：`cd_play_previous_audio_attachment` / `cd_play_next_audio_attachment` /
   `cd_seek_audio_attachment` / `error_play_audio_attachment`，13 个语种全量补齐。
5. **CONTEXT.md**：**Voice Waveform** 原定义写着"只出现在实时录音界面 / 已保存音频的播放是另一套 UI"，
   与本功能直接冲突，已改写为两处共用同一条实时链并注明播放侧按已播出采样位置对齐；
   HDR 不变式从"只有录音态"扩展到"录音或播放音频附件"；末尾补一条"点音频附件"的歧义解析。

`:app:assembleDebug` 通过。**未真机验证**，验收点见 [followups.md](followups.md)。

### 同日返工：高度与走带按钮样式

用户看过实现后提了两点，均已落地（[decisions.md](decisions.md) D10 / D11）：

- 对话框 420dp → **450dp**，多出来的 30dp 给进度滑杆。查代码发现水体的模拟容器高度是
  `FableSolSpec.HEIGHT_DP` 写死的 420dp（只有宽度走运行期实测），且 `water.vert` 把顶点
  按 `uViewportPx * 0.5` 对齐视口中心——直接加高会让水线凭空上抬 15dp。
  用现成的 `setContentVerticalOffsetDp(+15f)` 补偿回来。
- 三个走带键**不用 FAB**：去掉悬浮面与 elevation，改成裸 icon 按钮 + 圆形涟漪，
  图标色用 `BackgroundUtil.onColor(accentBg, 0.92f)`（按记事颜色明暗取黑/白），
  录音对话框那层圆形衬底一并去掉。

## 2026-07-25（三）真机首轮反馈：两个播放态 bug + 两处配色

发布 202607251430 后用户真机试用，报了四条，均已修（[decisions.md](decisions.md) D11–D13）：

1. **涟漪不要用记事色**，改为按记事明暗取黑/白 → `installCircleRipple` +
   `adaptiveRippleColor`，`GradientRippleDrawable` 撤下。
2. **结尾附近暂停再播放会卡住**：播放不结束、按钮持续停在暂停图标，反复点播放/暂停才被踢动。
   根因是收尾死等 `playbackHeadPosition` 追平写入量，而末尾不足一个 HAL 缓冲的残帧未必被计入。
   改为播放头停滞 320ms 即判定放完（暂停/seek 重置计时器），并补上「非暂停 ⇒ track 必须
   PLAYING」的硬件态不变式。
3. **拖到结尾再点播放只闪一下**：一起播就 EOS。改为已在结尾（或已播完）时按播放键
   从头重放当前这条。
4. **滑杆条形部分偏深**：未播段原用 `app_chrome_on_surface_hint`（App Chrome 面的色），
   压在水体上偏深。给 `DisplayUtil.setSeekBarBackground` 加可选第三参 `inactiveTrackColor`
   （默认 null = 原行为，其余滑杆零影响），本对话框传 `onColor(accentBg, 0.18f)`。
