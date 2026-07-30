# FableSol 视频导出画质提升实现提示词（交给实现会话使用）

本文件是启动实现会话的标准提示词，可跨会话重复使用；进度权威是
[execution.md](execution.md)，因此同一份提示词既能启动首个批次，也能续作后续批次。

---

# 任务：实现 FableSol 视频导出画质提升（D62～D170）

设计已经完成并通过实现前审查与逐项定案，你只负责实现，不重新设计。

## 开始前按顺序读以下文档

1. `docs/features/fablesol-video-export/plan.md`——唯一实施计划，共八个批次；其中
   「决策解析规则」一节定义决策间的覆盖关系，必须最先读。
2. `docs/features/fablesol-video-export/plan-review-2026-07-28.md`——实现前审查结论：
   第一节是已复核无误的关键数学；各条目的「处理结果」是已定案的修正；第五节是实现期间
   的成本与风险提示（含批次中间态说明、蓝噪声资源、迁移延迟绑定等）。
3. `docs/features/fablesol-video-export/decisions.md`——决策原文（D1～D170）。文件很长，
   不必一次读完：D50～D61 全程生效，先完整读一遍；其余按 plan.md 的批次覆盖表，做哪个
   批次读哪个范围。后出的决策修正先出的，尤其 D111 取代 D96～D99、D132 改写 HLG 源语义、
   D141 收敛杜比视界为 8.4、D142/D166 定验收边界、D164～D170 是审查定案的产物。
4. `docs/features/fablesol-video-export/preferences.md`——用户偏好，全部有效。
5. `docs/features/fablesol-video-export/execution.md`——执行清单与全程护栏。以它为进度
   权威：从第一个未完成的批次开始；每完成一批立即勾选并写完成记录。

## 实施纪律

- 严格按批次 1→8 顺序推进。每批结束时：代码可编译、全量 JVM 单测通过、正式像素输出处于
  该批定义的一致状态（例如批次 2～5 期间 HLG 沿用旧输出变换属预期，不拿它做画质基线）。
- 每批验证至少执行 `:app:testDebugUnitTest` 与 `:app:assembleDebug`（调用方式见
  `.claude/rules/gradle.md`；默认编译任务就是 assembleDebug）。
- 新增用户可见文案必须覆盖全部 13 套语言；能力缓存只存结构化原始数据，本地化文本在展示
  时生成。
- 实现与设计冲突、决策之间发现新的矛盾、或某决策在实际约束下不可行时：**停下来向用户
  说明并等待定案**，不得静默偏离；定案后把修正以新增编号条目写回 decisions.md 并同步
  plan.md。
- 每批完成后更新 execution.md 的完成记录与 sessions.md；可行但延后的事项记入
  followups.md。执行中学到的新事实按仓库规则随时更新对应文档，不等收尾。
- 不使用 adb、不操作任何设备或模拟器；真机验收由用户执行。
- 未经要求不 git commit、不发布 debug 更新。
- 只修改 EverythingDone 工程，不动 Everything-Android；保留工作区中已有的无关修改。
- 任务量大，不必强求一次会话完成八批：每批收尾后如上下文所剩不多，如实告知进度并建议
  开新会话续作（execution.md 支撑无缝续作）。

## 易错处提醒（均已定案，实现时勿再走回旧写法）

- HLG 肩部一维参数表以 `C_n(u) = C_S(u)/q(u)` 为查表键（D164），不得以 `q(u)` 作键；
  `W_device(u)` 按 D165 的方向域网格加二分构建，可行性检查按固定 W 步长覆盖整段
  super-white 区间。
- 静态元数据回读核对只在短探测产物上执行；configure 阶段的 `KEY_HDR_STATIC_INFO` 注入
  必须保留（D166）。完整编码并成功封装后的一切附加解析只作诊断，不推翻成功（D142）。
- CQ 下发走同模式兼容阶梯：纯 CQ → CQ+码率提示（D167），探测与正式导出同形态；CQ 模式
  的 Level 只按尺寸与像素率定档，不为未知码率抬档（D168）。
- HDR10+ 统计直方图桶对齐载荷 `0.00001` 量化网格，MaxSCL/AverageMaxRGB 不过桶（D169）；
  GLES 3.1 与 3.0 两个统计后端结果必须逐项相等。
- D101 的 `V1=0.00000`、`V2=0.00255`、`J8→99.98%` 已对照 ST 2094-40:2020 原文逐字验证，
  decisions.md 内引用了原文措辞，逐字段解码测试以其为准；D108 权重函数注意 `ε < 1/255`
  是全权重区。
- AV1 应用 P010 的色度位置按序列头 `chroma_sample_position` 解析匹配（D170）；HEVC 读
  SPS/VUI（D154）。
- `KEY_I_FRAME_INTERVAL` 一律 `setFloat`；不要实现任何"旧系统整数秒兼容"分支（D163 已
  删除该分支）。
- 蓝噪声 64×64 阈值表自行离线生成（如 void-and-cluster）并入 assets；libplacebo 是
  LGPL，只参考其策略，不拷贝其数据或代码。
- 性能测量与统计资源一律使用 `FableSolExportPlan` 的实际画布尺寸，不假设 4K 输出。

## 开始

读完上述文档后，从 execution.md 第一个未完成的批次开始实现。
