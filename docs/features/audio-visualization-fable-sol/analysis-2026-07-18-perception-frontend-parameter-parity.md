# FableSol“声音分析与灵敏度”（原“感知前端”）参数双端一致性排查（2026-07-18）

> 状态：诊断完成；用户已按 D171 裁决并完成双端接通，现行栏目名为“声音分析与灵敏度”。

## 结论

Python 参数面板原“感知前端”（现“声音分析与灵敏度”）的 3 项参数在 GUI 的
**实时分析模式**下都真实生效，不是死控件：

| 参数 | 作用 | 影响范围 |
|---|---|---|
| `agc_window_s`（自校准窗口） | 决定响度、低/中/高频与 spectral flux 使用的滚动统计窗口 | 改变响度/频段归一、onset 阈值及事件数量；短窗更快适应局部段落，长窗更能保留段落间强弱差异 |
| `silence_gate_db`（静音门） | 在跟踪底噪之上设置相对门限，并参与“可信有声”中心学习 | 值越高越保守，近底噪弱声更容易被完全归零；值越低越敏感，也更容易放行环境噪声 |
| `expander_amount`（动态扩展） | 以 0.5 为中心扩大 `loudness01` 的动态范围 | 只直接整形响度通道；通过 FeatureMapper 间接改变水位、主浪等以响度为主的视觉驱动 |

在 D171 收敛前，Android 已移植同一套算法和相同默认值 `24s / 6dB / 0.32`，但没有调参通道：

- `FableSolRealtimeAnalyzer` 的 `agcWindowS`、`gateDb`、`expander` 在生产中均参与计算；
- 三个字段只在声明处写入固定默认值，生产代码没有其它赋值；
- `FableSolParams` 虽注册了三个同名 key，但 Analyzer 由 `AudioRecorder` 独立持有，从未读取这份 Params；
- 设置内 `FableSolTuning` 只把参数送到渲染器/Mapper/Simulation 的共享 Params，无法到达音频分析线程。

因此当时 Android 不是缺少声音分析机制，而是**机制以固定默认值运行，三个 Params 注册本身无消费者，
调参 Dialog 也没有可到达 Analyzer 的桥**。D171 已用独立的音频分析调参目标与线程安全快照解决该问题。

## Python 热更新链

实时 GUI 中的完整链路为：

`ControlPanel` 滑杆 → `Params.set()` → listener → `AudioClient._push_tuning()` →
跨进程共享 tuning 数组 → `audio_process_main()` 每轮刷新 `RealtimeAnalyzer` 三个字段 → 下一批音频帧生效。

静态链路与单变量消融共同排除了“只注册未消费”和“修改后分析进程不更新”两种可能。

## 确定性单变量消融

使用 Python 正式 `RealtimeAnalyzer`，给每组对照喂入同一段确定性 1kHz 音频，只改变一个参数：

- `silence_gate_db=0` 时，约 `−50dBFS` 弱声在 0.5 秒后非静音比例为 `1.0000`、平均
  `loudness01=0.485053`；改为 `18` 后非静音比例为 `0`、平均响度为 `0`。
- `expander_amount=0` 时，学习强声中心后降 10dB 的弱段平均 `loudness01=0.068585`；改为 `1`
  后该段压到 `0`，两条输出的最大绝对差为 `0.268693`。
- `agc_window_s=3` 与 `30` 的强弱三段输入对照中，`loudness01` 最大绝对差为 `0.333960`，
  三频段最大差为 `0.240431`，onset 事件数分别为 `161 / 146`。

## 收敛前的模式边界

Python 的 3 项参数只接入 GUI `AudioClient` 的实时分析进程：

- “实时分析”下播放文件或使用麦克风：滑杆即时生效；
- “离线分析”下播放文件：画面由 `OfflineDirector` 的时间线驱动，离线进程没有接收 Params；其中
  因果 onset 子链也直接 `RealtimeAnalyzer()` 使用默认值，因此滑杆不改变当前离线结果；
- `--sim-audio` 等无 GUI 分析入口同样直接构造默认 Analyzer，不读取这 3 个 Params。

面板当前没有提示这项范围限制，也不会在切到离线模式后禁用该组。

D171 已让离线 worker、缓存键和 `--sim-audio` 接收同一参数快照；上述边界不再存在。

## 时间线

- Python 首个可追溯快照 `3a0e440`（2026-07-10 09:00）已含这 3 个参数和当时名为“感知前端”的栏目；
  当时栏目带有“M3 接入声音后生效”的提示，后来实时链接通后删除过时提示。
- Android 初次 FableSol 移植 `81aa81a`（2026-07-10 22:27）已带同构 Analyzer 字段、公式与
  Params 默认值。
- Android 调参 Dialog 的 D157（2026-07-17）明确把 `agc_window_s` 等归为“前端未消费键”而排除；
  这里的“未消费”指渲染 Params 无法到达 Analyzer，不代表分析算法没有使用对应固定值。
- D171（2026-07-18）将栏目统一改名为“声音分析与灵敏度”，接通 Android Analyzer 热更新，
  并让 Python 离线分析与 `--sim-audio` 接收三项参数。

## D171 收敛结果

- Android 通过 `FableSolTuning.Target.AUDIO_FRONT_END` 区分分析参数与渲染参数；Dialog 的持久化
  逻辑不变，预览值经 `AudioRecorder.setFableSolFrontEndTuning()` 写入 volatile 快照，并在每批 feed
  前应用到 Analyzer，当前录音会话内生效。
- Python 离线分析 worker 接收参数快照，缓存键包含归一化后的三项值；默认参数保持旧离线输出，
  非默认参数以因果 Analyzer 的调节前后差分修正离线响度、三频段、静音与 onset 驱动。GUI 用
  350ms 防抖避免拖动滑杆时连续重算；`--sim-audio` 走同一归一化入口。
- 20 秒确定性音频端到端对照中，`3s / 18dB / 1.0` 相对默认值的响度最大差为 `0.393100`，
  低/中/高频最大差分别为 `0.624800 / 0.354400 / 0.527600`，flow 最大差为 `0.209900`，
  共有 105 个静音帧改变，并生成两份不同缓存。
