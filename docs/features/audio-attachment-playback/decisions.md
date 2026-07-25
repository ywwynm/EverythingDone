# 设计决定 / 音频附件播放对话框

## 2026-07-25

### D1 卡片与按钮拆成两个入口

点音频附件**卡片本身**进入播放对话框；卡片**右侧的播放/暂停按钮**保持原来的就地播放。
可编辑与不可编辑两种详情态一致（不可编辑态右侧只有一个播放按钮，行为不变）。

打开对话框前先 `stopPlaying()` 并刷新那张卡片：两套播放不能同时出声。

### D2 水体沿用实时分析链，不引入离线分析

用户明确要求"跟录音保持一致"。实现上也是唯一合理的选择：Android 侧根本没有离线分析的
组件储备（没有 librosa / MusiCNN 等价物，只有因果的 `FableSolBeatTracker` /
`FableSolNoveltyDetector`），Python 模拟器的离线路径逐帧量本来也是实时链自己产的
（ADR-0019 的 parity 契约），离线独有的只有段落、拍网格与可 seek 重放。

### D3 喂入进度以 AudioTrack 的播放头为准，不是解码进度

`AudioTrack` 缓冲里通常压着上百毫秒的音频。按解码进度喂分析器，水面会整体早于声音
一个缓冲那么多。因此已写入但尚未播出的单声道样本先存进环形缓冲，
每轮按 `playbackHeadPosition` 把"已经出声的那一段"喂给分析器。
播放位置（计时器/滑杆）同样取 `flushBase + playbackHeadPosition`，与水面同一个时钟。

### D4 自建 MediaExtractor + MediaCodec + AudioTrack

`MediaPlayer` + `android.media.audiofx.Visualizer` 拿不到 FableSol 需要的输入：
Visualizer 只给 8-bit、1024 点的波形快照，刷新率远低于 512 样本 hop，而且要 `RECORD_AUDIO` 权限。
项目没有 ExoPlayer/media3 依赖，也不为此引入。自建解码链同时换来精确 seek 与 A/V 同步。

### D5 采集域标定沿用 PHONE_CAPTURE_V1

音频附件绝大多数是本 App 自己录的手机录音，与录音对话框用同一套标定才能"表现一致"。
外来音乐文件会被按采集域（+18dB 低架 / trim）校正，属已知取舍，记入 followups。

### D6 走带在列表边界不循环

第一首禁用「上一曲」、最后一首禁用「下一曲」（不可点 + alpha 0.24）。
最后一首播完停在结尾并切回播放图标；此时再点播放键从**当前这首**重新开始。

### D7 seek 不重建分析器，换曲才重建

分析器的滚动窗口在 1~2s 内自适应；跳转造成的谱通量突变本身就是真实的音频不连续，
允许它产生一个 onset。换曲必须新建（采样率/声道数可能不同）。

### D8 对话框不可见即暂停，不自动续播

`onPause` 暂停播放，回到前台由用户决定是否继续。`onDismiss` 释放解码器与 AudioTrack。

### D9 视觉与录音对话框同源

同一个 `WaveVisualizerFableSolHost`、同样的重力倾斜 / 方向锁 / 高刷申请 /
debug 性能仪表；计时器同为 `TimelyClockView.MODE_FULL`，每秒动一次。

### D10 对话框 450dp 高，水线用取景平移按回原位

比录音对话框高 30dp，多出来的高度给进度滑杆。

但水体的模拟容器是**写死的 420dp**（`FableSolSpec.HEIGHT_DP`，只有宽度是运行期实测），
而顶点坐标以**视口中心**对齐（`water.vert`: `screen = rotated + uViewportPx * 0.5`）。
容器高 450dp 时水线会整体上抬 (450−420)/2 = 15dp，相当于凭空多了 15dp 水位。
因此调 `setContentVerticalOffsetDp(+15f)` 把取景下移同样多，水线与录音对话框逐 dp 一致，
30dp 全部落在上方给滑杆让位。填充下界 `hG/2 + FILL_EXTRA_DP` = 290dp 仍远超视口下缘的
225dp，底部不会露白。Canvas 回退路径不支持取景平移，退化为水线高 15dp。

### D11 走带控件是裸 icon 按钮，不是 FAB；颜色一律"按记事明暗取黑白"

三个键都没有悬浮面、没有 elevation，只有圆形涟漪。压在流动水体上的元素都不用记事色本身
（同色系会糊在一起），一律走 `isLight(记事背景)` 决定黑或白：

| 元素 | 取值 |
|---|---|
| 图标 | `BackgroundUtil.onColor(accentBg, 0.92f)` |
| 涟漪 | `BackgroundUtil.installCircleRipple(view, adaptiveRippleColor(accentBg))` |

录音对话框那层半透明圆形衬底（D8）随之不再需要。尺寸：主键 56dp（图标 28dp），
上一曲/下一曲 48dp（图标 24dp）；列表首尾不可用时整体 alpha 压到 0.24 且不可点。

**但滑杆未播段是例外，极性必须按主题而不是按记事颜色。** 它压在水面**上方的天空**上，
而天空 = 主题的 `colorBackground` 与记事色的高度白化版（72%~84% 向白）混成
（`FableSolGlRenderer.environmentBase`）——**亮色主题的天空恒为浅色、暗色主题恒为深色，
与记事颜色深浅无关**。第一版按记事色取极性（`onColor`），深色记事在亮色主题下被判成"该用白"，
白线画在浅色天空上直接消失（用户实测"淡得根本看不到"）。现在沿用
`app_chrome_on_surface_hint` 的黑白极性、只把 alpha 乘 0.6（亮色 26%→16%、暗色 40%→24%；
极性修正后先试 0.75，用户仍嫌深，再降到 0.6）。

`DisplayUtil.setSeekBarBackground` 为此加了第三个可选参数 `inactiveTrackColor`
（默认 null = 原行为），只有本对话框传值，其余滑杆不受影响。

### D12 收尾靠"播放头停滞"判定，不死等播放头追平写入量

末尾不足一个 HAL 缓冲的残帧未必会被播出并计入 `playbackHeadPosition`，在结尾附近暂停过一次
之后尤其明显。原实现死等 `playedFrames >= ringWrite`，于是播放永远不结束、按钮一直停在
「暂停」图标，只有反复点播放/暂停才被踢动几帧（用户实测）。改为：播放头在非暂停状态下连续
320ms 没有前进就按放完处理，并把环形缓冲里剩下的样本一次性补给分析器（水面不会在最后一截断掉）。
暂停与 seek 都会重置这个计时器——暂停期间播放头本来就不动，不清零会让恢复的那一刻直接误判成放完。

同时把「非暂停 ⇒ AudioTrack 必须处于 PLAYING」做成每轮检查的不变式，依据是 `playState` 硬件
状态而不是自己的 `mPlaying` 记账：记账一旦与硬件不同步，写入会永远返回 0、线程空转，
表现同样是"按钮停在暂停图标但没有声音"。

### D13 已在结尾时按播放键 = 从头重放当前这条

拖到结尾（或自然播完最后一条）后点播放，解码器一起播就立刻 EOS，用户只看到播放图标闪一下，
还得再点一次才有反应。现在 `togglePlay` 先判 `mFinished || 位置 ≥ 时长 − 250ms`，命中就重开当前这条。
自动续播仍只发生在**真正播到结尾**时（由播放器回调触发），不受这条影响。
