# Python / Android 实时耦合 parity 审计（2026-07-21）

用户报告：同一次咳嗽（`20260721173307.wav` 的 14s、31s）在 Android 上进入
PEAK/CLIMAX、水位很高、浪很尖、退潮很慢，而 Python 端"跟想得差不多"，怀疑两端
已经不一致。本文记录审计方法、定位到的差异与处理结果。

## 方法：两端同源离线回放

无法用同一次麦克风输入同时驱动两端，因此改为**同一个 WAV 文件在两端各自跑一遍
完整实时链**，导出同列名 CSV 后逐帧比对。

- Android：`app/src/test/.../FableSolAudioTimelineProbe.kt`（JVM 单测，默认跳过）

  ```
  gradlew.bat :app:testDebugUnitTest --tests "*FableSolAudioTimelineProbe*" \
      -Dfablesol.probe.wav=<wav> -Dfablesol.probe.out=<csv> \
      -Dfablesol.probe.profile=capture
  ```

  自带 16bit PCM WAV 解析，走 `FableSolRealtimeAnalyzer` +
  `FableSolFeatureMapper` + `FableSolSimulation`，帧消费顺序与
  `FableSolGlRenderer.drainAudio` 一致（逐 hop 消费，事件按音频时间插回）。

- Python：`scratch/rt_probe.py`（走 `RealtimeAnalyzer` 实时链，不走 librosa
  离线时间线）。`--sr` 可指定采样率；`--hold` 模拟 GUI"每渲染帧只用最后一个 hop"。

- 比对：`scratch/compare_parity.py`、`scratch/frontend_diff.py`。

## 定位到的差异

### P1（主因，已修）Python 缺少采集启动抑制与底噪播种

`loud_db` 两端**逐帧完全相同**（−50.847 / −51.581 / −53.608 …），说明 FFT、
K/A 计权、频段划分、采集调理都在 parity。差异全部来自 `floor_db`：

| t | Android floor_db | Python floor_db | Android is_silent | Python is_silent |
|---|---|---|---|---|
| 6s | −53.494 | −58.934 | 0 | 0 |
| 8s | −56.014 | −58.579 | **1** | **0** |
| 12s | −56.854 | −57.957 | **1** | **0** |

Android 的 `suppressCaptureStartup` 在开头最多 4.5s 内不输出特征，并在窗口关闭时
把当时的电平当作环境底噪起点。Python 没有这段：底噪固定从 −60dB 起，且向上爬升
每秒最多 0.225dB（`(db−floor)·(1/FRAME_RATE)/20·prox` 的极值），于是 −51dB 的房间
底噪在几十秒内都高于静音门，被判成"有声音乐"——K01 冲到 0.83、L0 水流 130dp/s。

**处理**：把 `suppressCaptureStartup` / `startupSilentFrame` 逐行回移到
`src/wavesim/audio/features.py`（常数 `_STARTUP_QUIET_DB=-58`、
`_STARTUP_MAX_LOW_SHARE=0.55`、`_STARTUP_TRUST_S=0.30`、`_STARTUP_MAX_S=4.50`
与 Android 同值），只在采集域（`capture_profile` 非空）生效——磁盘母带没有 AGC
收敛与外壳轰鸣，开头不该被吞掉，而 Android 的分析器永远带
`PHONE_CAPTURE_V1`，行为等价。`reset` 与 `set_time_base` 都重新武装窗口，
与 Android 一致。离线缓存版本 43 → 44。新增
`tests/test_capture_startup_gate.py` 四项契约测试。

### P2（已知，保留）分析采样率两端不同

Android 采集 44.1kHz、`frameRate = sr / HOP = 86.13Hz`；Python 采集/解码统一
48kHz、`FRAME_RATE = SR / HOP = 93.75Hz` 是**模块级常量**，不随传入的 `sr` 变化。
生产路径（engine / offline / app）一律 48kHz，因此常量是对的；但把 Python
分析器构造成别的采样率时，所有以帧数表示的窗口会按比例偏差。已在
`RealtimeAnalyzer` 类文档里写明，并标注这是逐帧比对后**仅存的系统性差异**。

### P3（已知，保留）帧消费节奏不同

Android 逐个消费权威 hop（86Hz）；Python GUI 每个渲染帧只应用共享内存里的最后
一帧（丢约 30% hop）。解码器按音频时间积分，总时长一致，实测影响远小于 P1。

## 修复前后（`20260721173307.wav`，两端 44.1kHz 同口径）

| 指标 | 修复前 | 修复后 |
|---|---|---|
| 状态完全一致的帧 | 73.0% | **92.9%** |
| PEAK/CLIMAX 档一致 | 96.6% | 97.7% |
| `level` mean&#124;Δ&#124; | 10.52dp | **3.06dp** |
| `kinetic` mean&#124;Δ&#124; | 0.162 | **0.025** |
| `flow_l8` 相关 | 0.874 | **0.999** |
| 巨浪次数 | Android 1 / Python 2 | **1 / 1** |

（本轮五项视觉修改同步到 Android 之后复测为 86.6% / 96.8% / 3.06dp，差异集中在
32~40s、66~72s 的档位边界时刻，来源是 P2 的采样率差。）

## 结论

**耦合逻辑本身两端一致**：七境状态机、软时长解码器、因果证据、巨浪门、流速执行器、
连续通道弹簧逐行同构。用户看到的差异来自输入域——Python 端用笔记本麦克风、
Android 端用手机麦克风且带 `PHONE_CAPTURE_V1` 校正，加上 Python 少了启动抑制。
把同一个 WAV 喂给两端时，**Python 同样会因为那次咳嗽进入 CLIMAX**，所以"咳嗽推上
高潮、退潮慢、浪尖"不是 Android 独有的缺陷，而是本轮问题 3 与问题 5 的表现，两端
一并修正。
