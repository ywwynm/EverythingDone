# Android 音乐中人声存在检测调研

日期：2026-07-11

## 目标定义

目标不是现有 `music01` 的“音乐性/节拍证据”，也不是只识别说话的传统 VAD，而是输出连续、
因果的 `vocalPresence01`：当前混合音频中是否存在真实人声，范围包括唱歌、说唱、合唱以及音乐
中的 spoken word。`music01` 与 `vocalPresence01` 必须是可同时为高的两个轴，不得互斥。

当前 Android `FableSolRealtimeAnalyzer` 输入为 44.1kHz 单声道麦克风 PCM；最低 API 26。未来若
分析 app 自己播放的音乐，应在解码后、送入 `AudioTrack` 前直接分流 PCM；若捕获其它 app，Android
10/API 29 起的 `AudioPlaybackCapture` 需要 `MediaProjection` 用户授权，且播放方 usage/capture
policy 必须允许，不能作为普遍可用的默认输入。

## 关键结论

### 旧模拟器 YAMNet 实测（`E:\projects\audioVisualizerSimulator`）

用户指出旧模拟器已包含 `assets/models/yamnet.onnx` 与代码。实测模型为 14,935,918 bytes，
SHA-256 `97e095701a0d7849a087f7d6b70820f086eac2c353df3ca341722cfa2d5d16e5`，输入
`[1,1,96,64]` log-mel，输出 `[1,521]`。权重本身可正常工作；问题主要在旧接线：

- `YamnetOnnxClassifier.probabilities()` 已读取完整 521 维并计算分组最大值，但 UI/场景状态仍以
  `_top_label()` 的 `argmax` 驱动。Taylor Swift 全曲 613 个 0.48s-hop 窗中，top-1 有 605 个
  是 `Music`，所以界面看起来始终只识别为音乐。
- 唱声类别用字符串包含匹配：错误纳入 `Synthetic singing`（index 30）与 `Singing bowl`
  （index 209），漏掉 `Humming`（32）、`Vocal music`（249）、`A capella`（250）。正确的真人
  唱声初始集合应为 24~29、31、32、249、250，并明确排除 30/209。
- 每个 512-sample/16kHz 分析窗（32ms）都运行一次 YAMNet，即 31.25 次/秒；模型只需要约 2Hz，
  该调度在 Android 会造成约 15.6 倍无效推理。
- 最致命的融合是 `singing_probability=max(rule, yamnet_singing, yamnet_music*0.24)`：任何纯音乐
  都被硬塞约 0.24 唱声。前 90 秒实测，纯器乐 HOYO-MiX 的唱声 p50=0.239、最终 vocal p50=0.600；
  Taylor Swift 唱声 p50=0.308、vocal p50=0.985。旧 `voice_active` 阈值 0.36 会把纯器乐也判成人声。

去掉规则融合，只看修正后的真人声 YAMNet 子分数，再以 3 窗中值观察：

| 本地素材 | vocal raw p50 | p90 | `>0.005` 覆盖率 | top-1 |
|---|---:|---:|---:|---|
| HOYO-MiX 纯器乐 OST | 0.0002 | 0.0011 | 1.2% | 405/408 `Music` |
| Taylor Swift - Back To December | 0.0132 | 0.0423 | 78.3% | 605/613 `Music` |
| 姜育恒 - 再回首 | 0.0134 | 0.0667 | 70.7% | 511/533 `Music` |
| 洛依er - 浮生未歇 | 0.0116 | 0.0454 | 70.7% | 606/615 `Music` |

三首人声歌曲在约 13~17 秒开始形成长人声区间，器乐 OST 仅在约 57~59.5 秒出现短误报；直接
人声录音 `20260710234846.wav` 的 vocal raw p50=0.825。`20260710235706.wav`（麦克风录到的
音乐型素材）p50=0.0167，与三首人声歌曲同量级。以上不是有逐帧人工标注的正式准确率，但足以
证明：**现有权重含有可用的人声相对证据，旧 top-1、类别集合和融合公式把它破坏了。**

原始唱声分数只有约 0.005~0.05，官方又明确说明 YAMNet 分数未校准，因此不能把 0.005 固化为
Android 产品阈值。下一步仍应按 Phase V1 用这些完整 scores/logits 训练或校准小分类头。

### 1. 之前对 YAMNet 的使用很可能丢掉了多标签信息

YAMNet 不是只在 `speech` 与 `music` 中二选一。它对 0.96 秒音频窗输出 521 个独立 AudioSet
事件分数；AudioSet/YAMNet 词表包含 `Singing`、`Choir`、`Rapping`、`Vocal music`、`Speech`
等人声标签。因此 `Music` 与 `Singing` 可以同时成立。若调用层只取 `argmax/top-1`，一首有人声
的歌往往只显示 `Music`，这属于后处理损失，不是模型接口只能单标签。

限制同样明确：输入为 16kHz 单声道；固定移动版接受 15,600 样本（0.975 秒）；模型分数没有跨
类别校准，官方要求针对目标域重新校准/微调。YAMNet 的训练标签来自 10 秒 YouTube 片段，适合
粗事件识别，但人声进出边界天然会被约 1 秒上下文抹宽。原模型 3.7M 权重、每个 0.96 秒窗约
69.2M 次乘法。

结论：YAMNet 可立即建立低成本基线，也适合作为迁移学习骨干；不能直接把 top-1 或未经校准的
`Singing` 分数当最终产品判决。

### 2. 普通 VAD 不是音乐人声检测器

Singing Voice Detection（SVD）与 VAD 不同。伴奏会破坏能量、周期性、过零率等传统 VAD 证据，
音乐是 VAD 的困难干扰源；本项目旧试验中 Silero 对歌声+伴奏全程接近 0 与这一点一致。Silero
本身约 2MB、ONNX 可上移动端且对 speech 很强，但官方定位和质量集仍以 speech/noisy speech 为主，
并列出“音乐中类似人声的乐器”为持续问题。因此它可做纯说话辅助信号，不能承担目标任务。

### 3. 现成的 voice/instrumental 模型适合做原型基准，不宜直接随 app 发布

Essentia/MTG 提供精确对应任务的 `voice_instrumental-musicnn-msd-2`：约 3.24MB，16kHz，类别为
`instrumental/voice`，元数据报告在 1000 个内部曲目片段上的五折归一化准确率 0.98；官方还有
1.024 秒实时流示例。另有 YAMNet embedding 上的 voice/instrumental 小分类头。

但 MTG 明确声明其模型为 CC BY-NC-SA 4.0，商业发布需另购授权；Essentia 库本身为 AGPLv3 或
商业许可。其 0.98 还是小型内部片段交叉验证，不等于跨歌曲、跨扬声器、跨手机麦克风的帧级
准确率。适合在 Python 做对照基线，不作为当前 Android 生产依赖。

### 4. 源分离 + 检测准确率上限更高，但不适合第一版实时 Android

研究显示，先做 singing voice separation，再以 CRNN/LRCN 和时间滤波检测，能改善复杂复调、
合唱和弱人声；但源分离网络会显著增加模型、RAM、功耗和算法延迟。它适合未来“离线音乐文件
预分析”：整曲解码后提前生成 vocal timeline；不适合当前录音对话框的常驻实时线程。

## 候选方案比较

| 方案 | 音乐中歌声 | 边界能力 | Android 成本 | 结论 |
|---|---:|---:|---:|---|
| 当前 `music01`/YIN/4Hz 规则 | 差 | 差 | 极低 | 只能作辅助证据 |
| WebRTC/Silero VAD | 不稳定 | speech 时快 | 低 | 不作为主检测器 |
| YAMNet top-1 | 差 | 约 1 秒 | 中 | 后处理方式错误，淘汰 |
| YAMNet 多标签分数聚合+校准 | 中 | 约 0.5~1 秒 | 中 | 最快可落地基线 |
| 冻结 YAMNet + 自训练因果二分类头 | 中高 | 约 0.5~1 秒 | 中 | **推荐正式路线** |
| 专用轻量 log-mel CRNN/SVD | 高，依数据而定 | 可做到 0.2~0.5 秒 | 中 | 数据足够后可超越 YAMNet |
| 源分离 + SVD | 最高潜力 | 通常更慢 | 很高 | 仅离线/高端模式 |

## 推荐实施路线

### Phase V0：先验证 YAMNet 是否被错误用成 top-1

在 Python/桌面建立时间线探针，保存每个 0.975 秒窗的完整 521 维输出，不取 `argmax`。先构造：

- `singingEvidence = max(Singing, Choir, Rapping, Vocal music, Chant, Humming)`；
- `speechEvidence = max(Speech, Conversation, Narration, Whispering)`；
- `vocalRaw` 由两组证据与 `Music` 交互后经标定得到；明确排除 `Synthetic singing`；
- 输出 2Hz（0.48 秒 hop），进入阈值高、退出阈值低，并保留 0.6~1.0 秒 release。

这一步不是最终算法，只用于回答“完整多标签是否已经足够”。官方明确指出各类别分数未校准，
不得手调一个阈值后直接发布。

### Phase V1：冻结 YAMNet，自训练 `vocal_present` 头（推荐）

优先训练一个很小的二分类头，而不是重新训练 521 类模型：

1. 16kHz mono、0.96 秒窗、0.48 秒 hop；
2. 输入先尝试 521 维 YAMNet scores，经 logistic/两层 MLP 输出 `vocal_present`；
3. 若分数头上限不足，再改用 1024 维 embedding + 轻量 causal GRU/TCN，利用最近 2~4 秒连续性；
4. 标签语义覆盖 singing/rap/choir/spoken word，不把 `Music` 设为负类；
5. 使用歌曲级划分，严禁同一首歌的相邻窗口跨 train/test；
6. 将人声 stem 与伴奏按多种 SNR 混合预训练，再用真实混音微调。研究表明只用 speech+music
   合成存在域差异，必须加入真实歌声混音。

数据起点：Jamendo SVD Corpus 有 93 首、约 6 小时、voice/no-voice 区间标注；MedleyDB 提供
mix/stem 和 instrument activation，可导出人声活动，但音频为 CC BY-NC-SA，主要用于研究验证。
最终发布前应补自有/明确可商用许可曲库，并专门录制“手机麦克风听扬声器”的域数据。

### Phase V2：Android 接入

- 新增独立 `VocalPresenceAnalyzer`，消费现有 AudioRecord PCM 的只读副本；音频回调只写环形缓冲，
  重采样和推理全部在单独低优先级线程，绝不阻塞录音/UI/FableSol 60fps 路径。
- 使用官方 YAMNet TFLite/LiteRT 基线；项目通过阿里云分发且目标设备未必有 Google Play services，
  应默认打包 standalone LiteRT AAR，而不是依赖 Play services runtime。先用 CPU/XNNPACK；NNAPI
  性能依设备和算子分区而异，必须按机型实测后再启用。
- 输出 `vocalPresence01`、`vocalState` 和时间戳；FableSol 仅把它作为慢语义门，不替换 onset、
  loudness、pitch 等低延迟 DSP。约 1 秒的 ML 延迟不得阻塞 <50ms 装饰响应。
- 当前预留的 1Hz 语义接口对人声进出略粗，建议改为 2Hz；visual mapping 再做 attack/release，
  不要在 analyzer 与 mapper 各做一层长 EMA。
- 如果未来分析 app 自己选择的音乐文件，直接从解码 PCM 分流并允许离线预跑整曲；其它 app
  播放捕获只能作为 API 29+、需授权且受播放方策略限制的可选模式。

## 验收标准

不能只报总体 accuracy。至少分别报告：

- 歌曲级 macro precision/recall/F1 与 PR-AUC；
- instrumental false-positive seconds/minute（尤其萨克斯、电吉他、合成器、弦乐独奏）；
- lead vocal、backing vocal、choir、rap、低声哼唱的 recall；
- 人声进入/退出中位延迟与 P95；
- 直接 PCM、手机扬声器近场、远场/房间混响三种域；
- 低/中/高端 Android 单线程推理耗时、峰值 RAM、30 分钟电量和音频掉帧数。

建议第一版目标：帧级 macro F1 ≥0.90；纯器乐误报 ≤2 秒/分钟；进入中位延迟 ≤0.8 秒，退出
中位延迟 ≤1.2 秒；推理线程 P95 不超过 hop 的 25%，录音回调零等待。阈值必须在歌曲级验证集
上选，不能按少数示例目测。

## 主要资料

- [YAMNet 官方说明与模型结构](https://github.com/tensorflow/models/blob/master/research/audioset/yamnet/README.md)
- [Google Android YAMNet TFLite 模型说明](https://android.googlesource.com/platform/external/tensorflow/+/main/tensorflow/lite/g3doc/examples/audio_classification/overview.md)
- [AudioSet Singing ontology](https://research.google.com/audioset/ontology/singing.html)
- [AudioSet Vocal music ontology](https://research.google.com/audioset/ontology/vocal_music_1.html)
- [低延迟实时 SVD 论文](https://new.eurasip.org/Proceedings/Eusipco/Eusipco2015/papers/1570097385.pdf)
- [SVD 迁移学习与真实混音域差异](https://arxiv.org/abs/2008.04658)
- [源分离辅助 SVD](https://arxiv.org/abs/2004.04040)
- [Essentia voice/instrumental 模型元数据](https://essentia.upf.edu/models/classifiers/voice_instrumental/voice_instrumental-musicnn-msd-2.json)
- [Essentia 模型许可说明](https://essentia.upf.edu/models.html)
- [Jamendo SVD Corpus](https://zenodo.org/records/2585988)
- [MedleyDB 数据与标注](https://medleydb.weebly.com/)
- [Android Audio Playback Capture](https://developer.android.com/media/platform/av-capture)
- [Android LiteRT](https://developer.android.com/ai/custom)
