# fablesol-video-export 实施计划

分六批。排序原则：**先做零视觉变化、可独立验收的改造，再做新增功能**——批次 1～3 完成后
FableSol 的屏上表现应与现在逐位相同，任何差异都是回归。

决策依据见 [decisions.md](decisions.md)，架构裁决见
[ADR-0018](../../adr/0018-fablesol-visualization-video-offline-render.md)。

---

## 批次 1：HDR 三层解耦（零视觉变化）

把场景缓冲、窗口呈现、导出呈现拆开（D5）。

- `FableSolEglSession`：把 `verifyHalfFloatSceneTargetSupport()` 从 HDR 建链分支里提出来，
  改为独立的 GL 能力探测，与窗口是否 HDR 无关。
- `FableSolGlRenderer.initialize()`：`sceneLinear` / `hdrContentEnabled` 不再由
  `session.isHdrOutput` 单独决定；场景 FBO 在 GL 能力允许时恒为 `GL_RGBA16F`。
- 窗口 present 路径行为完全不变。

**验收**：屏上 HDR 与 SDR 两种设备各跑一遍，与改前逐帧对照 `max|Δ| = 0`；
`fablesol_hdr.log` 的档位诊断字符串语义不变。

---

## 批次 2：确定性时钟（零视觉变化）

给 vendored 的 `timelytextview` 加按形变进度直接求值的入口（D1）。

- `TimelyClockView` 新增 `setMorph(from, to, fraction)` 一类的包内入口，走现成的
  `TimelyEvaluator`；现有 `ValueAnimator` 路径改为调用它。
- 屏上行为不变——只是把"动画驱动求值"换成"求值 + 动画驱动"。

**验收**：录音对话框与播放对话框的时钟动画人眼无差异；新增单测钉住
"同一 fraction 恒得同一路径"。

---

## 批次 3：重力轨迹（录音侧，零渲染影响）

- `AudioRecorder` 的 RIFF 写入增加自定义 chunk（`EDmo`，置于 `data` 之后），
  格式：chunk id + 版本 + 采样率 + `float32 x/y/z` 定长数组（D13）。
- `AudioRecordDialogFragment.mTiltListener` 在派发给可视化的同时把读数按音频时间追加进
  缓冲；该动作发生在 `FableSolTiltSensor` 线程上，不碰渲染路径。
- 新增一个只读的轨迹解析器，供批次 4 使用；历史 WAV 无该 chunk 时返回空。

**验收**：录出的 WAV 在 MediaExtractor、系统播放器与至少一个第三方播放器里正常播放；
轨迹能原样读回；取消录音时轨迹随 WAV 一并删除（走那条单线程队列）。

---

## 批次 4：离线渲染引擎（无 UI）

本批最大，但完全不碰屏上路径。

- **驱动循环**：解码 WAV → 按音频时间喂 `FableSolRealtimeAnalyzer` → 固定 dt 推进 →
  调 `FableSolGlRenderer.render()`。虚拟化 `render()` 里那处
  `SystemClock.elapsedRealtime()`（D7）。
- **离屏 GL**：自建 EGL context + 按导出像素尺寸的场景 FBO，density 由 dp 几何反推（D3）。
- **导出 present program**：独立于屏上那条，负责 padding、画框底色、投影、发丝描边、
  按位深决定的抖动、以及 HDR/SDR 传递函数（D4、D9）。
- **编码**：能力探测 → 定档 → `MediaCodec` + `createInputSurface()` + `MediaMuxer`；
  音频并行编 AAC-LC 192 kbps（D9、D11）。
- **重力回放**：把轨迹按音频时间喂 `setContainerGravity()`；无轨迹则恒 `(0, 1, 0)`。

**验收**：D15 的 ①（逐位层）在本批建立并作为门禁；产出的 MP4 能在系统播放器、
支持 HDR 的播放器与桌面端正常播放，色域与传递函数标记正确。

---

## 批次 5：前台服务与通知

- `mediaProcessing` 类型前台服务，声明 `FOREGROUND_SERVICE_MEDIA_PROCESSING`。
- 通知：档位（HDR/SDR）· 预估体积 · 滚动更新的剩余时间 · 取消（D8、D14）。
- 导出前检查剩余空间（预估体积 × 1.2）；同时只跑一个，第二次点击排队（D16）。
- 落地 `Movies/EverythingDone/`，完成后提供「分享」与「添加为附件」（D12）。
- 失败即失败：删半成品、通知里如实报原因。

---

## 批次 6：入口、设置与文案

- 录音对话框停止态的「保存并导出视频」FAB，按钮行变为 `[重录][对号][导出][取消]`；
  播放对话框进度条右侧的「导出视频」按钮；共用补全左、上画框的 Material
  `video_frame_save` 图标（D14、D25）。
- GLES 不可用时两个入口都不出现（D14）。
- 调参 Dialog 新增「导出」分组：帧率上限、CQ 质量档、目标码率、关键帧间隔；
  范围从 `EncoderCapabilities` 读；面板实时显示 MB/分钟与耗时倍率；新增
  `Target.EXPORT` 且纳入「恢复默认」（D10）。
- 13 种语言文案。

---

## 收尾

- D15 的 ②（事件层）与 ③（观感层）真机验收。
- 真机标定码率默认值：用大面积缓变的深色渐变记事 + 静水这一最易暴露色带的场景（D10）。
- 确认 `FableSolRealtimeAnalyzer` 预热门是否纯采样驱动；若掺挂钟则改为采样驱动（D13）。
- 补 `sessions.md` 与 `followups.md`。
