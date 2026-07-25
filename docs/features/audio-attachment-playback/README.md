# 音频附件播放对话框（Audio Attachment Playback）

在记事详情页点音频附件卡片本身，进入一个带 **Voice Waveform**（FableSol 水体）的播放对话框：
上方是文件名 + `TimelyClockView` 计时器 + 进度滑杆，下方是「上一曲 / 播放暂停 / 下一曲」，
当前音频播完按附件顺序自动播下一个。

## 背景

此前详情页的音频附件只有一种交互：点卡片、点右侧按钮，都是就地起一个 `MediaPlayer` 出声，
没有任何画面，也没有进度与跳转。而 FableSol 水体已经在录音对话框里成熟运行，
只差一路"PCM 来自文件而不是麦克风"的输入。

## 结论

**入口拆成两个**：点卡片本身 → 播放对话框（本功能）；点右侧的播放/暂停按钮 → 仍是原来的就地播放。
两者互斥，打开对话框前先停掉就地播放。

**水体沿用实时分析链**，不引入任何离线/整曲分析：解码出的 PCM 一边写进 `AudioTrack`，
一边按**已播出的采样位置**喂给同一个 `FableSolRealtimeAnalyzer`（512 样本一批，采集域标定与录音相同）。

设计决定与理由见 [decisions.md](decisions.md)，术语与不变式见根目录 [CONTEXT.md](../../../CONTEXT.md)
（**Voice Waveform** 定义已同步扩展到本对话框）。

## 状态

首版实现完成（2026-07-25），`:app:assembleDebug` 通过，**尚未真机验证**。
待验收项见 [followups.md](followups.md)。
