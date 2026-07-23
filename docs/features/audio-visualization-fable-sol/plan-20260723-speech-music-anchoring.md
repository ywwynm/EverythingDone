# 方案：说话/音乐双锚定与感知三维重映射（2026-07-23 讨论定稿）

对应决策 D191–D194 与 preferences 2026-07-23 各条。目标：让动画忠实反映
"小声/偏小声/正常/偏大声/大声 × 语速快慢"的说话差异，同时保持五个音乐巨浪
回归用例与音乐观感不变。波形（尖锐度）本轮不改。

## 0. 边界与不变量

- **raw 特征链冻结**：K 计权响度、kineticDrive、gradeDrive 证据的计算全部不动；
  巨浪门继续只读 raw。改动只发生在 (a) display 轨，(b) 巨浪门的资格逻辑与
  浪体振幅入参，(c) 静息流速参数。
- **ADR-0015/0016 合规**：不引入曲内自适应分位归一；所有新刻度都是固定设备域
  参数（capture 档新增常量），说话锚定的电平项以固定 dB 刻度 + 底噪参照
  （已有 aboveFloorDb）为基，不做滚动排名。
- **无神经网络**（D172）；全因果、逐 hop 流式。
- **波形不改**：不新增"强度→尖锐"映射；浪形组、峰形公式、红线保持。
- **验收唯一路径**：巨浪用 tools/grand_wave_audio_validation.py（实时全 hop）；
  OfflineDirector 路径已知低估巨浪（followups 2026-07-23），不得用于巨浪判定。

## 1. 新特征（features.py / FableSolRealtimeAnalyzer，raw 只增不改）

1. **raw 域发声用力特征**（capture 档才启用；复用已有的 raw FFT，零新增变换）：
   - `rawRelLow/Mid/High`、`rawTilt`：未加 +18dB 低架的频段占比与倾斜——
     现有 conditioned 谱在说话上 tilt 恒饱和 1.0、centroid 恒 0，必须用 raw 谱
     才能拿到喊叫的"谱变平、中高频上抬"证据（AGC 不可抹除）。
   - `effortSpectral01`：由 rawTilt 变平量 + 1–4kHz 占比上抬 + F0 相对基线抬升
     （pitchRel 已有）合成，0..1。
2. **`voiceDominance01` 人声主导度**（连续 0..1，慢门 + 滞回）：
   - 正证据：fluct4hz01（音节率包络调制占比，已有）、voiced01×音节率存在感；
   - 负证据：music01（节拍置信，已有）、持续和声/低频运动；
   - 时间常数：升 ~2s、降 ~3s，中带滞回，杜绝边界抖动。
3. **`speechEffort01` 说话用力档**（AGC 鲁棒，固定刻度）：
   - 电平项：capture 域未 trim 的 K 短时响度按固定刻度映射（初值按实测：
     活动电平 -36.6/-31.8/-23.3/-16.5 dBFS 对应小声→偏大声；刻度锚点在
     Python 端按 20260723140911 分段目测定稿）；
   - 动态项：momentary 峰（p95 级）相对 short-term 的超出量（现有 punchLu 思路）
     ——恢复被 AGC 压平的"偏大声 vs 大声"差异（p95 阶梯 -21.0/-13.1/-10.2 完好）；
   - 用力项：effortSpectral01；
   - 融合：三项加权 + 上升快/下降慢包络 → 0..1，再过五档锚定曲线。

## 2. display 轨重构（calibration.py / FableSolPerceptualCalibrator）

1. **说话梯子**：speechEffort01 → 水位锚点（D191 表）：
   小声 0.10–0.22 / 偏小声 0.25–0.38 / 正常 0.42–0.58 / 偏大声 0.62–0.78 /
   大声 0.80–1.00（可进 PEAK/CLIMAX）。
2. **音乐刻度**：保持现状；音乐录音（capture）的 display 参数微调向母带观感
   靠拢（实测录音 0.79/0.83 vs 母带 0.71，次要目标，允许后置）。
3. **路由**：`displayWater = mix(musicWater, speechLadderWater, voiceDominance01)`；
   显示七境证据（displayStateEvidence 已存在）自然消费新 displayWater；
   另在 display 证据里为 musicArousal 增加 music01 门控（防说话把 arousal 读高，
   raw 不动）。
4. **display 动能（新）`displayKinetic01`**（D194 双驱动）：
   - 主项：语速/事件密度（音节率、onset 密度的固定曲线）；
   - 辅项：speechEffort/水位（大声也要快一些）；
   - 音乐主导时退回 raw kineticDrive（音乐观感不变）；
   - 可见流速通道（ContinuousStateChannels.flow 目标）改读 displayKinetic01；
     巨浪门保持读 raw kineticDrive01。
   - 分段目标（20260723140911，L0 dp/s）：小声 40–70、正常 90–120、
     大声(54s) 140–165、快速正常(64s) 140–170、快速喊单字(80s) 150–180、
     段间安静回落。

## 3. 巨浪（grand_wave_gate / grand_wave / simulation）

1. **说话域资格**（D192/D193）：voiceDominance 高时，现有音乐分支需追加音乐
   证据要求（抑制当前 33.2/47.9/109.5s 类假阳性）；新增"转变分支"：
   - 触发 = effort 在 ~0.5–1.5s 窗内从低档（≤偏小声）跃至偏大声/大声档
     且带攻击证据（onsetEnv/punchLu）；
   - 重武装 = 必须先回落安静/低档 ≥2s；另设 ~8–10s 最小间隔。平台期不连发。
2. **分级振幅**：`trigger()` 增加 strength01 入参 → `amplitudeDp = lerp(低级≈80dp,
   全高 144dp, strength01)`；说话分支：偏大声档≈低级、大声档≈高级；
   **音乐分支恒 144dp 不变**（五用例视觉现状保持）。低振幅永不违反侧翼陡峭度
   上限；backgroundKeep 已按振幅比例自适应。
3. **验收窗**（新增说话用例进验收脚本）：
   - 正样本：42–45s（低级）、54–57s（高级）、107–110s（低级）；
   - 负样本：~33s、~80s 及其余全部；
   - 音乐五用例窗口全部维持现状（master 四窗/短录音两窗/两全录音同母带/银花 2:23）。

## 4. 静息流速（params / flow_policy）

- idle_flow_ratio 0.16 上调（候选 0.20–0.24）与/或低端锚点上调，Python 目测定稿；
  历史轨迹 0.24→0.18→0→0.10→0.16，本次方向为"再快一点"（用户 2026-07-23）。

## 5. 验收清单

1. 音乐：grand_wave_audio_validation.py 五用例全绿（时间窗不变）。
2. 说话分档（新工具，基于 speech_levels_report 思路转正式）：
   - 分段 displayWater 中位落在锚定区间，且严格单调：小声<偏小声<正常<偏大声<大声；
   - 分段 L0 目标流速中位落在 §2.4 目标带；快速段 > 对应正常语速段；
   - 巨浪命中 §3.3 正负样本；
   - 状态占比：小声以 CALM 为主，正常以 GROOVE 为主，大声可见 PEAK/CLIMAX。
3. 混合路由回归：五个音乐素材上 voiceDominance 不得把 display 水位/状态拉离
   现状带（rap 段负样本检查保留）。
4. 波形：Python 全量回归 + Android JVM 全量（浪形组参数与峰形公式零改动）。
5. 真机：debug 包目测（说话五档 + 音乐外放 + 静息流速观感）。

## 6. 实施顺序

P1 raw 用力特征 + voiceDominance（features.py）
P2 speechEffort + 说话梯子 + display 路由（calibration.py）
P3 displayKinetic 双驱动 + 可见流速接线（speed.py / states.py）
P4 巨浪说话分支 + 分级振幅（grand_wave_gate.py / grand_wave.py）
P5 静息流速 + 音乐录音 display 微调（params.py / flow_policy.py / input_calibration.py）
P6 说话验收工具 + 全量回归 + 目测迭代
P7 Android 同构移植（Analyzer/Calibrator/StateChannels/Gate/GrandWave/Params）
   + JVM 测试 + 真机 debug 发布验证

## 7. 风险与开放项

- 设备差异：说话电平刻度按当前测试机（-19dBFS AGC 平台）定标；其他设备的
  AGC 平台不同，刻度可能整体偏移——底噪参照项（aboveFloorDb）可部分吸收，
  彻底解决需多设备语料（followups 已有同类条目）。
- 清唱/哼唱落在说话锚定侧：因说话梯子无上限，预期可接受；目测阶段专门试。
- OfflineDirector 巨浪路径偏差已记 followup，不阻塞本方案。

## 8. 与七境的关系（2026-07-23 用户问询后补写）

**不新增抽象层，不新增状态机。** 七境仍是唯一的状态抽象；本方案全部改动都落在
既有层的既有槽位里：

- effortSpectral01 / voiceDominance01 / speechEffort01 是**标量特征**，与
  music01、musicArousal01 同级同址（Analyzer/Calibrator 内部）；
- "说话五档梯子 + 主导度混合"是 **displayWaterDrive01 的新算式**——display 轨
  是 D185 已有的，今天它就有一个固定混合公式（pivot -26.6/ratio 1.76/blend 0.65），
  本方案只是把这个固定公式换成内容感知的公式，不是加一层包装；
- displayKinetic01 是 display 轨**加一个字段**（与 displayWaterDrive01 对称）；
- 可见七境状态机今天就消费 display 证据（fillPerceptualInput 里 displayOrRaw），
  机制、解码器、waveMultiplier、弹簧全部不动——五档说话是"喂给七境的输入变准了"，
  CALM/GROOVE/PEAK 的落位由原有阈值自然涌现，**五档不是第二套状态系统**；
- 巨浪门是既有模块加一条资格分支和一个振幅入参。

状态机数量维持两台（可见 + gate），证据轨维持两条（raw + display），均为 D185
现状；本方案为零新增。

## 9. 追加工作项 P0：七境执行表调整（D195，2026-07-23）

先于 P1 落地、两端同步的常数表变更（详见 D195）：SPREAD 的 LIFT/PEAK/CLIMAX 改
1.08/1.20/1.29；RIM 与 CAP 统一为 0/0/0.24/0.72/0.90/1.00/1.00；waveScale 表不变。
既有 ×centroid（rim）、×punch（cap）、×expression_gain 调制不动。相关断言测试
（两端）随值更新。
