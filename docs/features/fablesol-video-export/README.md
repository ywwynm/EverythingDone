# fablesol-video-export

把一个音频附件导出成一段 **Voice Waveform Video**：画面是该音频的 FableSol 水体，
自带该音频的声音，条件允许时是 HDR Media。产物由音频**重新渲染**得到，不是屏幕录制。

架构裁决见 [ADR-0018](../../adr/0018-fablesol-visualization-video-offline-render.md)；
术语见根目录 `CONTEXT.md` 的 **Voice Waveform Video** / **非实时驱动** /
**整曲前瞻分析** / **重力轨迹** / **导出画框**。

## 本目录文档

- `decisions.md` — 逐条决策与理由
- `plan.md` — 实施批次
- `sessions.md` / `followups.md` — 会话记录与遗留项（随工作推进新建）

## 范围

**做**：离线渲染引擎、HDR/SDR 编码降级阶梯、重力轨迹记录与回放、两个入口、
前台服务与通知、导出参数设置项。

**不做**：录音或播放时旁路屏上渲染实时编码（见 ADR-0018 的 Considered Options）；
整曲前瞻分析；带 alpha 的产物格式；9:16 等改变水体物理容器的画幅。

## 产物形态

| 项 | 取值 |
|---|---|
| 构图 | 与触发入口无关，恒用录音对话框那套紧凑构图：420dp 高、时钟贴顶 36dp、内容不做取景平移 |
| 画面内容 | 水体 + TimelyClockView 计时动画 + 圆角卡片；无按钮、无文件名、无进度条 |
| 画框 | 卡片四周 24dp padding，底色跟随 Appearance Mode，卡片带投影与发丝描边（描边随底色翻极性） |
| 宽高比 | 跟随对话框实测 dp 几何。宽度是 `max(280dp, 时钟固有宽 + 48dp)`，随用户选的数字字形在 280～383dp 间变化 |
| 像素尺寸 | dp 几何照抄，像素高度提到固定档，density 由此反推；最终画布按 codec 要求与 64px 分享边界共同向上对齐，只对称扩展画框 |
| 帧率 | 120fps 优先，编码器不支持降 60fps。120 与 `PHYSICS_DT = 1/120` 正好 1:1 |
| 视频编码 | HEVC Main10 优先；HDR 走 BT.2020/HLG，SDR 走 BT.709 10-bit；逐级降到 H.264 8-bit |
| 音频编码 | AAC-LC 192 kbps |
| 落地 | `Movies/EverythingDone/`，不自动挂成附件，完成后提供「分享」与「添加为附件」 |

## 两个入口，一个引擎

- **录音对话框**：停止录音后，对号 FAB 右侧多一个「保存并导出视频」FAB。它先照常保存
  WAV，再立刻对这份 WAV 执行导出。
- **音频附件播放对话框**：进度条右侧一个「导出视频」图标按钮。

两处共用补全左、上画框的 Material Symbols `video_frame_save` 图标；播放对话框里的图标
使用当前记事的完整颜色或渐变。

GLES 不可用（`WaveVisualizerFableSolHost` 走了 Canvas 回退）时两个入口都不出现。

## 与 FableSol 主体的关系

导出复用 `FableSolGlRenderer` 的**全部场景渲染代码**，差异只有三处：谁调用 `render()`、
dt 从哪来、present 到哪个 surface。因此送进 GL 之前的一切必然等价，验收不需要逐像素比对。

需要改动 FableSol 主体的只有一处：**场景缓冲 / 窗口呈现 / 导出呈现三层解耦**。目前
`hdrContentEnabled` 与 `sceneLinear` 跟 EGL 窗口是否 HDR 绑死，导致"屏幕 SDR 但导出 HDR"
无法表达。见 decisions.md D5。

## 重力轨迹

录音时把 `TYPE_GRAVITY`（回退 `TYPE_ACCELEROMETER`）的读数按音频时间记下来，
50Hz × 三个分量 × float32 = **600 B/秒 = 36 KB/分钟**，写进 WAV 的自定义 RIFF chunk
（chunk id `EDmo`，放在 `data` chunk 之后）。传感器本来就跑在 `FableSolTiltSensor`
独立线程上，记录动作与渲染路径无关。

本功能之前的历史录音没有轨迹，按竖直渲染。
