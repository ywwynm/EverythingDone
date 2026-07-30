# fablesol-video-export 执行记录

当前画质提升工作按新版 [plan.md](plan.md) 的八个批次推进。2026-07-26 起的首轮六批实现及
后续修复已经完成，作为历史记录保留在本清单之后。

---

## 2026-07-28 画质提升执行清单（D62～D170）

当前状态：**设计与计划已完成，业务代码尚未开始修改**。

- [x] 逐项完成 D62～D163 的设计讨论并写入 [decisions.md](decisions.md)。
- [x] 将有效决定整理为新版 [plan.md](plan.md)。
- [x] 建立本执行清单。
- [x] 完成实现前审查并与用户逐项定案：新增 D164～D170、修订 D91/D108/D112/D131/D140/
  D145/D152/D154/D163 相应表述，验证结论见
  [plan-review-2026-07-28.md](plan-review-2026-07-28.md)。
- [x] 批次 1：请求模型、持久化迁移与候选解析（2026-07-28 完成）。
- [x] 批次 2：高精度呈现与通用 P010 质量核心（2026-07-29 完成）。
- [x] 批次 3：SDR 三种成片语义（2026-07-29 完成）。
- [x] 批次 4：PQ 母版意图、全片静态统计与 HDR10（2026-07-29 完成）。
- [x] 批次 5：HDR10+ 精确场景统计与 Profile B 曲线（2026-07-29 完成，D177 修订）。
- [x] 批次 6：HLG 与杜比视界 8.4（2026-07-29 完成）。
- [x] 批次 7：离线编码画质策略（2026-07-29 完成）。
- [x] 批次 8：集成验证与交付（2026-07-29，可自动化与本机可做的部分完成；跨设备抽查移交用户，
  见 [device-matrix-2026-07-29.md](device-matrix-2026-07-29.md)）。

### 全程护栏

以下项目不是某一批的局部验收项，任何实现都不得绕过：

- [ ] 只修改 `EverythingDone`，不在 `Everything-Android` 实现本功能。
- [ ] 保留用户工作区中的无关修改；每批开始前检查工作树，结束时检查实际 diff。
- [ ] 用户未授权时不使用 ADB 或操作物理设备/模拟器。
- [ ] 输出规格先于编码器族：不得为 HEVC 优先牺牲 HDR 格式、帧率、分辨率或位深。
- [ ] 显式 HDR、显式编码器族、严格 10-bit/8-bit 的语义保持严格；失败只在真实导出任务后提示。
- [ ] HDR 自动才允许跨格式并最终回到原生 SDR；能力探测不得弹失败 Dialog 或改写用户偏好。
- [ ] 同规格顺序固定为硬件 HEVC、硬件 AV1、硬件 AVC、软件 HEVC、软件 AV1、软件 AVC。
- [ ] HLG super-white 不可用只退名义范围 HLG；杜比视界 8.4 同理，不因此换 HDR 格式或 SDR。
- [ ] 完整编码并成功封装/提交后，不因附加结构诊断删除、重编或改报失败。
- [ ] 没有真实视频样本、封装失败、编码器真实报错仍属于候选失败，不能被“流程走完”掩盖。
- [ ] 所有完成信息、通知、文件名和诊断以实际产物为准，不沿用失败候选的申请标签。
- [ ] HDR Vivid、Eclipsa Video、APV/P210 与 ROI/QP map 不混入本轮。
- [ ] 性能数据使用 `FableSolExportPlan` 的实际画布，不假设存在 4K 输出。
- [ ] 每批新增事实、决定、遗留项与执行结果在发生时更新对应功能文档。

### 批次 1：请求模型、迁移与候选解析

预计主要触点：

- `FableSolExportOptions.kt`
- `FableSolTuning.kt`
- `FableSolExportAttemptPlan.kt`
- `FableSolExportCapabilityMatrix.kt`
- `FableSolHdrExportCapability.kt`
- `FableSolExportHdrFormat.kt`
- `FableSolExportSpecText.kt`

执行项：

- [x] 建立色彩模式、SDR 映射、SDR 位深、HLG 信号范围、PQ 白点模式和编码工具的类型化请求模型。
- [x] 建立完整的已解析候选/实际产物模型，停止由多个 UI/服务组件分别推测落点。
- [x] 用稳定标识替代新设置的 ordinal 持久化。
- [x] 迁移旧 HDR 开关、杜比视界 5/8.1、CBR、PQ 白点、码率与 CQ 自定义值。
- [x] 删除杜比视界 5/8.1 的正式候选，自动 HDR 顺序改为
  `HDR10+ → 杜比视界 8.4 → HDR10 → HLG`。
- [x] 实现规格优先、软硬件分层和编码器族平局顺序。
- [x] 实现显式格式严格失败、HDR 自动跨格式/原生 SDR 后备、SDR 严格位深语义。
- [x] 能力缓存改存结构化原始数据；本地化文本在展示时生成。
- [x] 保留兼容适配层，批次完成时正式像素输出不发生非预期改变。

验证项：

- [x] 旧设置迁移测试。
- [x] 候选字典序与全部约束组合测试。
- [x] 能力事实不写偏好、隐藏值不参与自动求值的测试。
- [x] 显式失败与自动后备测试。
- [x] 相关单测、全量 JVM 单测和 debug 编译通过。

完成记录（2026-07-28）：

新增三个文件把"想要什么"与"落到了什么"彻底分开：

| 文件 | 职责 |
|---|---|
| `FableSolExportRequest.kt` | 类型化请求枚举：色彩模式、SDR 映射、SDR 位深、HLG 信号范围、PQ 白点模式、码控模式与实际形态、输入路径、抖动策略。全部带 `stableId` |
| `FableSolExportResolvedCandidate.kt` | 只读的实际落点；文件名、完成态、诊断此后只读它一份 |
| `FableSolExportFailure.kt` | 结构化失败原因（稳定标识 + 厂商原文 + 可核对数值）与逐候选失败明细 |

关键改动与踩到的点：

- **持久化全部改稳定字符串，并加了版本化迁移**（`FableSolTuning.ensureExportMigration`）。
  迁移**只搬用户真的存过的键**：旧版缺省值与新默认一一对应，凭空写入会让"恢复默认"失去
  意义。旧 `export_hdr` / `export_hdr_format` / `export_codec` / `export_prefer_cq` /
  `export_quality` 五个键迁移后删除，版本号写入 `export_prefs_version`。
- **旧全局 CQ 原值改为延迟绑定**（审查第五节的建议）：迁移只把它搬进"待归属"槽位，第一次
  真正解析出候选时才归属给那一个签名（`bindLegacyQualityValue`），之后不再扩散。迁移路径
  上因此不需要任何同步能力探测。签名为
  `编码器名|格式稳定标识|位深|输入路径`，导出侧与设置页同源。
- **位深成为可行组合表的第四条轴。** D160 的严格 10-bit / 8-bit 要求表能分别回答，而
  "SDR 能用 HEVC"不蕴含"10-bit SDR 能用 HEVC"（三星 Z Fold4 上 8 位通过、10 位全灭）。
  `PROBE_CONTRACT_VERSION` 10→11。
- **D53 的修订必须同时改掉三处旧断言。** 旧规则是"自动档完全不使用软件编码器"，它散落在
  `CodecPreference.allowsSoftware`、`autoFormat` 的 `allowSoftware = false`、设置页的
  `formatEnabled`，以及两个 JVM 测试里。撤销后统一为：同规格内先穷尽硬件，再按同样的族顺序
  尝试软件。`candidatesForMode` 的排序键因此从"每个阶梯项内部各排一次"改成对整批候选排
  `(软件, 族, 阶梯序, 码控模式匹配)`——只有看得见全部阶梯项才排得出
  `硬件 HEVC → 硬件 AV1 → 硬件 AVC → 软件 HEVC → 软件 AV1 → 软件 AVC`。
- **显式 HDR + HDR 强度 1.0 的语义按 D106 修正。** 旧代码的 `wantHdr` 同时要求
  `strength > 1.0`，于是用户显式选了 HDR10 而强度为 1.0 时会**静默发布 SDR**——正是 D106
  禁止的那件事。HDR 强度是内容参数，不是能力：显式格式现在无论强度都产出用户点名的容器，
  自动档才保留"没有额外高光就不必上 HDR"这条捷径。
- **D82 撤销了按设备反推漫反射白的整套推导**，`FableSolExportDisplayLuminance` 的
  `recommend` / `constraintFormula` / `CONTENT_PEAK_ALLOWANCE` / `AUTO_WHITE_*` 一并删除，
  只留下"读设备声明值"与数字格式化。原先 7 个用例钉的是已作废的规则，改写为 3 个（不可信
  读数当未声明、数字格式、默认白锚是设备无关标准值）。
- **PQ 白锚滑杆档距 25→1 尼特。** 25 的栅格容不下标准的 203，滑杆会显示 200 而信息栏写
  203，两处对不上。范围与语义不变；D84 的"标准/自定义"控件属批次 4。
- 13 套语言：新增 `fablesol_export_estimate_white_standard` /
  `fablesol_export_estimate_white_custom`，删除随自动推导作废的
  `_auto_formula` / `_auto_fallback` / `_manual` 三条。
- **源码里不要写不可见控制字符。** 缓存分隔符原打算沿用 `"\u0001"` 这样的转义，但工具链
  几次往返后把它变成了真正的控制字节；改为 `1.toChar().toString()` 由码点构造，编码结果
  与旧版完全一致，源码保持纯 ASCII。

新增 JVM 测试 `FableSolExportRequestModelTest`（8 例）：稳定标识往返与唯一性、旧偏好迁移
（含杜比视界 5/8.1→8.4）、显式 HDR 不允许 SDR 结果、自动档忽略隐藏的名义范围、严格位深不
跨位深、CQ 签名区分每一条实质不同的路径、文件名来自实际候选、输入路径由格式决定。
`FableSolExportAttemptPlanTest` 扩到 8 例（补位深轴与显式格式不回落 SDR），
`FableSolExportCapabilityMatrixTest` 重写为 11 例（补位深轴与结构化失败往返）。

全量 `:app:testDebugUnitTest` 379 例、0 失败；`:app:assembleDebug` 通过。未使用 adb。

### 批次 2：高精度呈现与通用 P010

预计主要触点：

- `FableSolExportPresenter.kt`
- `FableSolExportP010Bridge.kt`
- `FableSolExportEgl.kt`
- `FableSolVideoExporter.kt`
- `FableSolExportEncoder.kt`
- `shared/fablesol/glsl/export_present.frag`
- `shared/fablesol/glsl/p010_luma.frag`
- `shared/fablesol/glsl/p010_chroma.frag`
- `shared/fablesol/glsl/p010_stats.frag`

执行项：

- [x] 建立 `RGBA16F` 呈现中间面，并以真实 framebuffer completeness 判定资格。
- [x] 建立 `RGB10_A2` 同格式兼容中间面，在正式渲染前选定实际路径。
- [x] 将 P010 颜色转换泛化到 BT.709 SDR、BT.2020 PQ、BT.2020 HLG。
- [x] 实现与码流声明一致的色度位置探测（HEVC SPS/VUI 与 AV1 序列头，D154/D170）与相位
  正确的低通降采样。
- [x] 实现 PQ、HLG 和 BT.1886 三类闭环亮度修正（闭式解 + 迭代 oracle）。
- [x] 生成并接入 64×64 蓝噪声阈值资源与码值域阈值舍入。
- [x] 完整处理 P010 stride、slice height、crop、plane stride/pixel stride。
- [x] 所有 10-bit 格式接入“应用 P010 优先、同格式 Surface 后备”；HDR10+ 保持 P010 必需。
- [x] 约束 GPU 回读与 ByteBuffer 数量，保持逐帧可取消和有界内存。

验证项：

- [x] 矩阵、limited range 与 P010 字节布局参考向量。
- [x] Type 2/其它声明位置/未声明 Type 0 的相位测试。
- [x] 闭环正式解与迭代 oracle 对照。
- [x] FP16 与 RGB10_A2 路径误差、统计一致性和失败后备测试。
- [x] 蓝噪声平均偏差、边界码值和静态时序测试。
- [x] 相关单测、全量 JVM 单测和 debug 编译通过。

完成记录（2026-07-28 CPU 侧与资源，2026-07-29 GPU 侧接入与输入通路）：

第一段（2026-07-28）的成果：

| 新增 | 内容 |
|---|---|
| `tools/generate_blue_noise.py` | void-and-cluster 生成器，固定种子；只参考 libplacebo 的策略，不拷贝其表数据或代码 |
| `shared/fablesol/bluenoise64.bin` | 4096 个小端 uint16 秩（0…4095），SHA-256 `3969515cb27553456c22ee67d4306eae7d70d881c775b0325527103536e1ea3a`；低频/高频能量比 0.0003（白噪声为 0.99） |
| `FableSolExportBlueNoise.kt` | 资源加载、排列自检、`R16UI` 纹理上传、Y′/Cb/Cr 三个固定相位偏移 |
| `FableSolExportP010Math.kt` | 三种输出定义的 NCL 矩阵与 limited range、H.273 六种色度位置及其降/升采样抽头、PQ/HLG/BT.1886 的 EOTF 与解析导数、单步闭式闭环亮度修正与二分 oracle、蓝噪声阈值舍入、P010 字节打包 |
| `FableSolExportChromaSiting.kt` | HEVC csd-0 的 SPS/VUI 解析（含防竞争字节剥离、profile_tier_level、scaling list、short-term ref pic set）与 AV1 av1C 的 `chroma_sample_position`；一切解析失败都落到 Type 0 兼容语义 |

几处实现要点：

- **秩而不是 8 位归一化值。** 蓝噪声表存 0…4095 的秩，阈值取 `(rank + 0.5) / 4096`。8 位
  阈值的量化台阶本身就接近一个 10-bit 目标码值，等于用比刻度还粗的尺子去量；实测无偏性
  在 1/4096 栅格上是**精确**的，不在栅格上时残余偏差不超过一个阈值台阶。
- **居中相位不能硬套三抽头。** `f0 = [1/8, 6/8, 1/8]` 只适用于共点相位；居中相位改用同族
  的对称四抽头 `[1,3,3,1]/8`，一阶矩正好落在 0.5。两者都归一，都不引入锐化或振铃。
- **闭环修正的三种目标域不能混**：PQ 比显示线性亮度，HLG 比场景线性亮度（播放端 OOTF 随
  显示条件变化，不能钉在某个峰值上），SDR 比经 BT.1886 参考显示得到的显示线性亮度。修正
  受四条门禁约束：斜率稳定、改变量 ≤ 24 码值、误差确实降低、不引入新的 R′G′B′ 非法值。
- **AV1 不必真解 OBU。** av1C 记录把序列头的 color_config 复制到固定位置，第 3 个字节的
  低 2 位就是 `chroma_sample_position`。HEVC 那边则绕不过完整 SPS 解析——测试因此自带一个
  按 H.265 语法写 SPS 的 writer，再读回来比对。

第二段（2026-07-29）把上述数学接进 GPU，并按 D158 改写输入通路：

| 新增 | 内容 |
|---|---|
| `FableSolExportPresentTarget.kt` | 呈现中间面：先试 `RGBA16F`，真的建附件并查 completeness，不成再退 `RGB10_A2`；两者都建不出才判候选失败 |
| `FableSolExportP010Layout.kt` | 编码器输入缓冲的实际排布——行距、平面高度、crop 原点、平面行距，全是纯算术，JVM 可测 |

改写的着色器与接线：

- **`p010_chroma.frag`**：2×2 box average 换成有相位的可分离低通（共点三抽头
  `[1,6,1]/8`、居中四抽头 `[1,3,3,1]/8`），矩阵改由 uniform 传入，量化改成码值域蓝噪声
  阈值舍入并钳到本次信号范围。
- **`p010_luma.frag`**：新增闭环亮度修正。它读的是**色度趟真正写出去的**量化码值，按同
  相位参考上采样后求解，四条门禁（斜率稳定、改变量 ≤ 24 码值、误差确实降低、不引入新的
  非法 R′G′B′）与 `FableSolExportP010Math.correctLuma` 一一对应；三种目标域由 `uTransfer`
  分支（PQ 显示线性、HLG 场景线性、SDR 经 BT.1886）。
- **三趟顺序固定为色度 → 亮度 → 统计**（D157 第 4 条）。统计趟只为 HDR10+ 存在，其余
  10-bit 档位省下一次全画布归约与回读。三趟共用同一个 `uSource`，统计与编码输入因此不可能
  来自不同精度的画面。
- **`FableSolExportTier.inputPath` 从计算属性变成真正的一条轴**：`candidatesForMode` 为
  10-bit 档位生成 `[应用 P010, 同格式 Surface]` 两个子候选，8-bit 只有 Surface，HDR10+ 只有
  P010（编码器不公开列出 `COLOR_FormatYUVP010` 时不生成候选）。排序键把它放在**最后**，
  因此永远不会把某个编码器的 Surface 提到另一个编码器的 P010 前面。

几处必须记下的判断：

- **EGL 色彩空间是输入通路的要求，不是格式的要求。** 原先按 `format.requiresEglColorSpace`
  在**格式**一级粗筛，D158 之后这会把"有 P010 编码能力、却没有 `EGL_EXT_gl_colorspace_*`
  窗口扩展"的设备整格式降到 SDR——应用自有 P010 画进自己的离屏 framebuffer，那张 1×1
  pbuffer 压根没打色彩空间属性。判据因此下沉到候选一级
  （`FableSolExportTier.requiresEglColorSpace`），导出与探测两处同源。这与 D30"广告能力位
  一律不作为门禁"是同一条原则。
- **色度相位必须在第一帧渲染之前定下来，而码流要到编码开始之后才有。** 解法是让短探测把
  码流声明存进可行组合表（`FableSolExportCombinationOutcome.chromaSitingId`，只存**真的
  声明了**的值），正式导出读**已经得出**的缓存（新增 `cachedMatrix`，绝不触发探测——一次
  完整探测要连续创建几十个 MediaCodec）。取不到就按 Type 0 兼容语义，这正是解码端在 VUI
  缺失时的解释方式。探测契约版本 11→12。
- **蓝噪声资源不可用时仍要绑一张完整纹理。** `usampler2D` 绑到不完整纹理是未定义行为，
  即便那条分支不会执行；因此资源缺失时上传一张 1×1 中性阈值纹理并把 `uNoiseEnabled` 置假，
  着色器走固定 0.5 阈值——也就是 D157 第 6 条要求的"退回普通四舍五入"。
- **不要 close 掉 `getInputImage()` 返回的 Image。** 紧接着的 `getInputBuffer(index)` 按
  MediaCodec 的契约本来就会让它失效，而部分 AOSP 版本的 `MediaImage.close()` 会去 free 底层
  直接缓冲——那正是我们马上要写入的那块内存。Image 只用来校正行距、并作为"半平面交错"的
  门禁：像素步长不是 (2, 4, 4) 就判本候选失败，退到同格式 Surface，而不是照写一帧花屏。

新增 JVM 测试：`FableSolExportP010PathTest`（9 例：排布的 0/缺失/crop/平面行距、兼容中间面
的额外误差不超过半个码值、闭环在整条降采样—量化—上采样序列上确实降低重建亮度误差且平坦区
一动不动、输出定义随传递函数走、名义信号范围端点）与 `FableSolExportP010ShaderParityTest`
（11 例：两个着色器的抽头表与传递函数常量逐项对上 Kotlin 侧、闭环四条门禁一条不少、三趟
顺序与同源取样、三条资源后备、输入通路子候选的排序位置、导出侧不触发探测）。

全量 `:app:testDebugUnitTest` 411 例、0 失败；`:app:assembleDebug` 通过。未使用 adb。

**本批结束时的正式像素状态**：10-bit 输出（含 10-bit SDR、HDR10、HLG、杜比视界 8.4、
HDR10+）全部改由应用自有 P010 产出，含相位正确的色度、闭环亮度修正与蓝噪声量化；8-bit
仍走 Surface 与既有三角哈希（D162 属批次 3）。**HLG 沿用旧输出变换**（无逆 OOTF、逐通道
软肩），D132 的修正在批次 6——按 plan.md 第五节，这期间不要拿 HLG 做画质 A/B 基线。

### 批次 3：SDR 原生、稳定映射与动态映射

预计主要触点：

- `FableSolExportOptions.kt`
- `FableSolExportPresenter.kt`
- `FableSolVideoExporter.kt`
- `FableSolTuningDialogFragment.kt`
- `dialog_fablesol_tuning.xml`
- `FableSolExportSpecText.kt`
- 13 套 `strings.xml`

执行项：

- [x] 原生 SDR 保持关闭 HDR 高光后的重新渲染。
- [x] 实现基于 HDR 强度、maxRGB 共同增益和 BT.2446 Method B 标定意图的稳定映射。
- [x] 实现仅统计 FableSol 超白内容的动态映射与 `0.08s/0.80s` 时间响应。
- [x] 保证 `0～1.0` 基础范围不随帧变化，不增加顶部去饱和或逐通道裁切。
- [x] 动态统计失败时从第 1 帧按稳定映射重启。
- [x] FP16 不可用时从第 1 帧按原生 SDR 重启。
- [x] 接入自动/严格 10-bit/严格 8-bit 位深。
- [x] 8-bit Surface 最终写出前接入静态蓝噪声，保留三角哈希作为资源失败后备。
- [x] 更新色彩模式选择器、条件控件、说明、文件名与完成态。
- [x] 补齐 13 套语言。

验证项：

- [x] SDR 曲线恒等边界、单调、连续、共同 RGB 比例和黑白端点测试。
- [x] 动态峰值序列与重启后备测试。
- [x] 位深严格/自动候选测试。
- [x] `SDR`/`SDR-TM`/`SDR-DTM` 实际命名与通知状态测试。
- [x] 固定渐变、暗水体、星芒素材回归。
- [x] 相关单测、全量 JVM 单测和 debug 编译通过。

完成记录（2026-07-29）：

三种 SDR 语义靠**一族两参数曲线**统一实现，而不是三条各写各的分支；曲线族与标定插值定案为
D171（D68～D72 只给了约束，不足以定出唯一曲线）。

| 新增 | 内容 |
|---|---|
| `FableSolExportSdrToneMap.kt` | 曲线族（膝点由参考白落点反解、超白段指数解算）、`W(强度)` 标定插值、快压慢放的 `PeakTracker`。纯数学，JVM 可测 |
| `FableSolExportScenePeak.kt` + `sdr_peak.frag` | 动态映射的逐帧超白峰值：一趟归约到 32×32、回读 4 KB、CPU 取最大值 |
| `FableSolExportSdrRender`（在 `FableSolExportRequest.kt`） | SDR 产物**实际采用**的成片语义，与请求侧的色彩模式/映射方式分开 |

几处必须记下的判断：

- **色调映射只作用于场景纹理，且必须在时钟合成之前。** padding、画框底色、投影、描边和时钟
  都是 SDR 图形元素，本来就在 `0～1` 之内；把它们一起压一遍只会平白变暗——膝点约 0.48，
  一块线性 0.8 的画框底色会被压到 0.70，那是原生 SDR 与保留高光 SDR 之间不该有的差别。
  D73 把这几样排除在**统计**之外，同一条理由同样适用于**变换**。恰好场景纹理就是卡片内容
  本身，所以归约趟直接读它即可，不需要额外的遮罩。
- **膝点不是独立参数。** 令 `K = 1 - e·(1-W)`，第二段的指数肩部就同时满足 `F(1) = W` 与
  `F'(1) = 1/e`；两个自由度压成一个，也就不存在"膝点与参考白落点互相打架"的参数组合。
  超白段的两个分支在 `m = 1` 处斜率同样是 `1/e`，整条曲线 C¹，没有随帧移动的折点。
- **动态映射够不到 1.0 时就不够。** 控制峰值太靠近 1.0 时，强行让峰值落到满幅需要 `p < 1`，
  那是**凸**曲线，等于在高光段放大对比度——与 D68"只压缩"直接冲突。改取 `p = 1` 的直线、
  峰值落在 `T < 1`；`T` 与整条曲线都随控制峰值连续，阈值附近不跳变。
- **两条运行时降级不消耗降级阶梯。** D77 与 D78 都要求"丢弃该次尝试并从第 1 帧重来"，但编码
  档位本身一切正常，失败的是渲染侧的内部质量工具。因此新增 `SdrDegradeRestart`，在**同一个**
  tier 上重来。两条降级的判据都在首帧之前就能得出（FP16 看 `isHdrContentEnabled()`，归约
  目标看 framebuffer completeness），所以"从第 1 帧"实际上不浪费任何渲染。
- **8-bit 抖动改为码值域阈值舍入**：`floor(v*255 + 阈值)`，R′G′B′ 共用同一个阈值。这与旧的
  "往编码值上加一层三角噪声"是两回事——前者的期望值精确等于原值，误差始终不足一个码值。
  三角哈希保留为蓝噪声资源不可用时的同格式后备。
- **能力探测多了一项 FP16。** `SDR（保留高光层次）` 的硬前提是 FP16 扩展显示线性（D78），
  结论由 `FableSolHdrExportCapability.linearSceneSupported()` 按进程缓存，设置页据此置灰。
  它与 D64"强度 1.0× 时仍可选"不是一回事：那是内容为空，这是能力缺失。
- 13 套语言新增 20 条；随色彩模式选择器改写而作废的 `fablesol_export_hdr_format_off` /
  `_hdr_desc_off` / `fablesol_param_export_hdr_format` 三条同步删除。

新增 JVM 测试 `FableSolExportSdrToneMapTest`（18 例：标定端点、膝点反解的两个约束、单调/
连续/只压缩、膝点与参考白两处 C¹、基础段不随控制峰值移动、超白段确实随之变化、凸曲线拒绝与
边界连续、共同增益、时间响应的两个时间常数与一次闪现的完整时序、控制量夹取、归约编解码、
固定素材落点）与 `FableSolExportSdrPipelineTest`（10 例：三种语义解析、自动档退原生 SDR、
显式 HDR 不发布 SDR、降级后文件名跟实际产物、稳定标识往返、8-bit 阈值舍入的无偏性与黑白
端点、着色器三段曲线对照、色调映射的位置、蓝噪声与后备、归约越界保护）。

全量 `:app:testDebugUnitTest` 441 例、0 失败、1 跳过；`:app:assembleDebug` 通过。

**真机验证（OPPO PLZ110 / Android 16，用户本轮明确授权 ADB）**：四次真实导出，7.13s 音频，
每次约 40 秒。

| 设置 | 完成态 | 文件名 | ffprobe |
|---|---|---|---|
| SDR（原生渲染）· 自动位深 | `SDR（原生渲染） · HEVC 10-bit（硬件编码），120 fps` | `…_SDR.mp4` | hevc Main 10 / yuv420p10le / bt709 三项齐全 |
| SDR（保留高光层次）· 稳定 | `SDR（保留高光层次）· 稳定映射 · HEVC 10-bit` | `…_SDR-TM.mp4` | 同上 |
| SDR（保留高光层次）· 动态 | `SDR（保留高光层次）· 动态映射 · HEVC 10-bit` | `…_SDR-DTM.mp4` | 同上 |
| SDR（原生渲染）· 严格 8-bit | `SDR（原生渲染） · HEVC 8-bit（硬件编码）` | `…_SDR.mp4` | hevc **Main** / **yuv420p** / bt709 |

逐帧亮度对照（每 120 帧取一帧的 YMAX / YAVG，10-bit 码值）：

- 原生 SDR 的 YAVG 为 `779 785 777 751 725 709 704 701`；
- 保留高光 SDR 为 `738 742 736 714 691 679 673 672`——高亮端确实被压下去了，且两种映射方式
  的 YAVG 完全一致，正是 D71 要求的"`0～1.0` 基础范围全片固定、两种方式共用"；
- 稳定与动态的差别只出现在 YMAX（`899/901/935/947` 对 `898/904/927/945`），说明动态曲线**在
  逐帧变化**、且变化被限制在超白段内。差别之所以小，是因为这段素材的星芒核心大多顶到 HDR
  强度上限，控制峰值本来就接近 9.6——这是内容属性，不是通路没生效；通路真失败时完成态会按
  D77 标成"稳定映射"，而它标的是动态映射。

**一处刻意没做的验证**：8-bit 产物的抖动频谱。取画框背景做残差谱之后，低/高频能量比被编码器
自身的量化主导，得不出蓝噪声与白噪声的区分度。抖动的正确性由 JVM 侧钉住（阈值表是 0…4095
的排列且低/高频能量比 0.0003、码值域无偏、真黑真白端点、着色器逐行对照），不把一个经过有损
编码后不可判定的量当作交付门禁。

**本批结束时的正式像素状态**：三种 SDR 语义与两种位深全部生效；HLG 仍沿用旧输出变换
（D132 的修正在批次 6），HDR10/HDR10+ 的静态与逐帧元数据仍是批次 2 的状态。

### 批次 4：PQ 静态母版、全片统计与 HDR10

预计主要触点：

- `FableSolVideoExporter.kt`
- `FableSolExportEncoder.kt`
- `FableSolExportDisplayLuminance.kt`
- `FableSolExportHdrFormat.kt`
- 新的静态亮度分析/缓存组件
- `FableSolTuningDialogFragment.kt`
- 完成信息与通知组件

执行项：

- [x] PQ 漫反射白改为“标准 203 尼特/自定义 200～800 尼特”。
- [x] 本机显示峰值只作为诊断或一次性采用值，不参与默认母版意图。
- [x] 实现 D65、P3-D65、0.0001 尼特与母版最大亮度规则。
- [x] 实现 HDR10/HDR10+ 全片确定性 MaxCLL/MaxFALL 预分析。
- [x] 建立完整渲染指纹和成功结果缓存。
- [x] 统计失败但渲染有效时使用理论 MaxCLL、未知 MaxFALL，并展示状态。
- [x] 在封装前生成和注入应用权威静态元数据。
- [x] 将短探测硬门禁、正式编码真实错误与成功产物后的非破坏性诊断分开。
- [x] 更新设置、完成 Dialog、通知和诊断。

验证项：

- [x] D65/P3-D65/母版亮度 25 字节字段测试。
- [x] MaxCLL/MaxFALL 合成帧与全片聚合测试。
- [x] 缓存命中、失效、取消和失败不写缓存测试。
- [x] 理论/未知回退展示测试。
- [x] 成功封装后附加解析失败不推翻结果的测试。
- [x] 相关单测、全量 JVM 单测和 debug 编译通过。

完成记录（2026-07-29）：

静态元数据从"按参数算"改成"按产物测"。核心是编码器 configure 之前多跑一遍全片的确定性
离线渲染，读最终可见合成的线性 BT.2020 画面。

| 新增 | 内容 |
|---|---|
| `FableSolExportLuminance.kt` | 归一化统计模型、D90 理论回退、完整渲染指纹与持久缓存 |
| `FableSolExportLuminanceReducer.kt` + `hdr_stats.frag` | 逐帧归约到 32×32（同时给块最大值与块均值），回读 4 KB，CPU 侧加权成 MaxCLL/MaxFALL |
| `FableSolExportStaticMetadataCheck.kt` | 25 字节描述符的逐字段回读核对，定位在短探测产物上（D166） |

几处必须记下的判断：

- **统计与编码必须共用同一条驱动。** 预分析要重跑一遍音频驱动、模拟与渲染；两条循环各写
  一份，迟早有一份被改动而另一份没有，统计描述的就不再是最终产物那段画面。因此把逐帧推进
  抽成 `FableSolVideoExporter.Drive`，两处共用，编码路径只多一个"把 PCM 交给音频编码器"的
  回调。
- **分析出口是同一条呈现流水线的另一个出口，不是另一条流水线。** `export_present.frag` 新增
  `uTransfer == 3`：只做 Rec.709→BT.2020 的线性转换，不套 OETF、不钳到 1.0。统计因此与最终
  可见合成逐像素一致，也自然包含画框、投影、描边与时钟——D86 的口径与 D73 为动态 SDR 排除
  外围图形的口径**不同**，前者是内容亮度元数据，后者是创作映射。
- **分析中间面必须是 FP16。** 线性值最高到 HDR 强度（9.6），`RGB10_A2` 会把所有高光一律截成
  1.0，MaxCLL 直接失真。因此 `FableSolExportPresentTarget` 新增 `createHighPrecision`，建不
  出来就按 D90 回退，而不是发布一个错误的实测值。
- **帧平均要按块内实际像素数加权。** 画布未必被 32 整除，边缘块不满；1024 个块均值直接算术
  平均会给边缘多算权重。权重表在 CPU 侧算，与着色器的 `break` 边界同源。
- **MaxFALL 不能拿漫反射白顶替。** 旧实现写的是漫反射白（203），那是 PQ 的缩放锚点，不保证
  是每帧平均 maxRGB 的上界——本轮实测下来两台设备分别是 164 与 140 尼特，都明显低于 203，
  旧值属于**高报**。统计不可用时按 D90 写 0（H.274 的"未提供"），不写一个看起来合理的数。
- **母版 primaries 改 P3-D65（D88），编码容器仍是 BT.2020。** 两者说的是不同的事：BT.2020
  决定码值怎么解释，P3-D65 描述承载创作意图的母版显示器。不做 Rec.709→P3 的创作扩色。
- **回读核对必须容忍容器写入器的换算。** 平台结构里最低母版亮度的单位是 0.0001 尼特，
  AOSP 的 MP4 写入器在一部分设备上把它当成尼特再乘 10000：OPPO PLZ110（Android 16）注入 1
  读回来是 10000，同机码流 SEI 里仍是 1；同为 Android 16 的 OPPO OPD2515 读回来就是 1。
  逐字节比较会让前一类设备全部误判失败，所以最低亮度这一项同时接受两种读数，其余字段严格
  相等。**容器完全没有携带描述符也不判失败**——旧系统上实际承载的是码流 SEI，这一层读不到。
- **核对读的是编码器自己那一份描述符**（`expectedStaticInfo()`），不在探测侧另算一遍。两处
  各算各的，只要有一处漏传参数，核对就会稳定地判所有设备失败，而那种错误看起来完全像是
  设备问题。

新增 JVM 测试 `FableSolExportStaticLuminanceTest`（11 例：P3-D65 与 D65 逐字段、母版亮度范围、
母版峰值覆盖实测 MaxCLL、实测值向上取整、D90 回退的理论/未知语义、归一化统计随漫反射白重新
缩放、边缘块加权、回读一致/已知换算容忍/各字段冲突/容器未携带）。

全量 `:app:testDebugUnitTest` 452 例、0 失败、1 跳过；`:app:assembleDebug` 通过。

**真机验证**：本轮换到 OPPO OPD2515 平板（Android 16，1680×2520 @ density 420，系统语言为
英文），记事「还有好多功能可以实现！」，音频 10.74 秒。

```
Specification: HDR10+ · HEVC 10-bit(hardware), 120 fps
Colour: diffuse white 203 nits, peak 1949 nits, highlights from 90%
Content light level: MaxCLL 1948 nits, MaxFALL 140 nits (measured across the whole clip)
```

- MDCV 读回 `red 0.680/0.320、green 0.265/0.690、blue 0.150/0.060、white 0.3127/0.3290`，
  正是 P3-D65 + D65；`max_luminance` 1949 尼特、`min_luminance` 0.0001 尼特。
- CLLI 读回 `max_content 1948、max_average 140`，与缓存里的归一化值
  `9.593701|0.687959` 乘 203 后逐位对上（1947.5→1948、139.65→140）。
- **缓存确实命中**：连续两次导出之后 `shared_prefs/fablesol_export_luminance.xml` 只有一条
  记录，第二次没有再跑预分析。
- 同一版本在 OPPO PLZ110 手机上（导出画布 1152×1472）测得 MaxCLL 1948、MaxFALL 164，两台
  设备的差异来自各自的录音内容，不是统计口径。

**驱动脚本的一处教训**：平板系统语言是英文，按中文 `content-desc` 找控件直接失败。凡是有
`resource-id` 的控件一律改按 id 定位，只有胶囊这种没有 id 的才按文案匹配，完成态则同时匹配
中英文两种写法。

**本批结束时的正式像素状态**：像素本身未变（静态元数据不改 PQ 像素）；HLG 仍沿用旧输出变换，
HDR10+ 的逐帧统计与曲线仍是批次 2 的状态，批次 5 处理。

### 批次 5：HDR10+ 精确统计与 Profile B

预计主要触点：

- `FableSolExportHdr10PlusMetadata.kt`
- `FableSolExportHdr10PlusCurve.kt`
- `FableSolHdr10PlusProbe.kt`
- `FableSolExportP010Bridge.kt`
- `FableSolExportEncoder.kt`
- HDR10+ 统计 shader/归约组件

执行项：

- [x] 实现 GLES 3.1 compute/SSBO 全分辨率统计。
- [x] 实现 GLES 3.0 RGBA8 精确打包/回读后端。
- [x] 修正 MaxSCL、AverageMaxRGB、九项 DistributionMaxRGB、完整 CFD 与 FBP。
- [x] 统一 nearest-rank、V8 优先源峰值及真实 CFD 高光起点；直方图桶按 D169 对齐
  载荷量化网格。
- [x] 全片固定 Profile B、Case 3 中性低峰值帧、9 anchors。
- [x] 实现 PQ 感知域目标、0.5 均匀先验、压缩-only 约束求解。
- [x] 实现固定 PQ 网格时间平滑和当前帧重新拟合。
- [x] 实现量化后单调/连续/端点/斜率门禁及无解行为。
- [x] 区分自动候选后备与显式 HDR10+ 真实导出失败。
- [x] 补充参考显示峰值滑杆、提示与实际失败导航。

验证项：

- [x] ApplicationVersion 1 载荷逐字段解码测试。
- [x] 两个统计后端与 CPU 参考结果一致性测试。
- [x] Case 3、压缩-only、量化门禁和 `S/T` 边界测试。
- [x] 闪现、渐亮、渐暗、重复脉冲和候选重置测试。
- [x] 自动/显式失败与不发布替代产物测试。
- [x] 普通代表样片轻量 A/B。
- [x] 相关单测、全量 JVM 单测和 debug 编译通过。

完成记录（2026-07-29）：

HDR10+ 从"32×32 块平均 + PQ 域近似"整体换成逐像素线性 BT.2020 统计与内容驱动的 Profile B 曲线。

| 新增/重写 | 内容 |
|---|---|
| `FableSolExportHdr10PlusHistogram.kt` | 100001 桶、桶宽 0.00001 的完整 CFD；nearest-rank 分位；MaxSCL/总和不过桶 |
| `FableSolExportHdr10PlusStats.kt` | 逐帧统计模型、V1/V2 保留值与 V8=99.98%、FBP 权重函数 |
| `FableSolExportHdr10PlusStatsBackend.kt` + `hdr10plus_stats.comp` / `hdr10plus_clear.comp` / `hdr10plus_pack.frag` / `hdr10plus_proxy.frag` | GLES 3.1 compute 与 GLES 3.0 回读两级后端，外加 5:1 代理帧 |
| `FableSolExportHdr10PlusCurve.kt`（重写） | Case 3 中性曲线、内容密度分配、斜率上限投影、固定绝对 PQ 网格上的时间平滑 |
| `FableSolGlComputeProgram.kt` | compute program 封装，按**真实** `GL_VERSION` 而不是请求的 EGL 版本门控 |

几处必须记下的判断：

- **统计源改走 MRT，不再从 PQ 反解。** `export_present.frag` 增加第二个附件，同一趟合成同时
  写出 PQ 编码的 R′G′B′（给 P010）与线性 BT.2020（给统计）。从 PQ 反解回线性在 1000 尼特
  附近要损失约 4 尼特（FP16 在 PQ 域的一个 ulp），远粗于 0.1 尼特的载荷网格；再画一遍呈现
  则冒着"两遍 uniform 不一致"的风险，而那种不一致在产物里看不出来。
- **`1.0 / 0.00001` 在双精度里是 99999.99999999999。** 桶下标改用乘以 100000，否则归一化
  值 1.0 会落进 99999 号桶，最高那一档永远空着——这种错误不会报任何症状。
- **"差分非增"是过强的约束。** 门禁要的是绝对斜率 ≤ 1，而 `B'` 是差分序列的贝塞尔（差分的
  凸组合），所以**只要每个差分不超过第一个差分**就够。先前按非增投影会把解压成唯一的解析
  形状，内容密度白算了；改成只压斜率上限之后，肩部才真正跟着 CFD 走。
- **旧的"二次缓入"回退形状本身不满足斜率门禁。** `S/T = 1.2` 时 `P1 ≈ 0.123`，而二次缓动的
  第二个差分是 0.184——比第一个还大，肩部一出膝点就加速到绝对斜率 1.07。旧实现没有门禁，
  这种曲线一直在发布。新的回退按"差分线性递减、二分求和为 1"构造，已知可行。
- **时间平滑的网格必须钉在绝对亮度上。** 网格原本按源峰值缩放，于是同一个数组下标在相邻两
  帧代表不同的绝对亮度，平滑出来的东西没有物理含义——两帧形状相似时平滑前后完全一样，等于
  没有时间稳定（实测就是这样：flashed 与 instantaneous 的 anchors 逐位相同）。
- **膝点的钳制范围只能受量化步长限制。** 原先钳在 `[0.02, 0.95]`，而 `Kx = k/S` 在
  `S/T = 9` 时可行值只有 0.012；被 0.02 托住之后 `Ky/Kx = S/T` 这条恒等关系直接破裂，
  斜率连续门禁必然失败。改成 `[1/4095, 4094/4095]`。
- **内容密度分配要带上限重新分配。** 密集区间按比例分配可能拿到比输入还大的输出跨度，等于
  放大局部对比度，与 D119 冲突。改为"触顶即封顶、溢出按剩余可调空间比例分给未触顶的段"，
  有界迭代，落点仍精确等于参考显示峰值。
- **十阶伯恩斯坦基的最小二乘会振荡。** 8 个自由控制点、约 30 个样本，无正则时差分在上限与
  近零之间来回跳。正则强度要与法方程量级相称（矩阵元约 0.3），1e-3 等于没加，改为 0.05；
  正则目标就是解析形状，它同时是 D120/D121 那条均匀先验在控制点空间里的对应物。

新增 JVM 测试 `FableSolExportHdr10PlusTest`（17 例：桶网格与载荷对齐、nearest-rank 不插值、
AverageMaxRGB 不从桶重建、V1/V2/V8 的 ApplicationVersion 1 语义、载荷逐字段解码、FBP 量化
下限、权重函数的全权重区、Case 3 绝对亮度恒等、压缩曲线单调且不提亮、`S > 10T` 拒绝候选、
膝点下移而不抬高目标、肩部随 CFD 变化、时间平滑滞后与慢释放、候选间重置、贝塞尔基端点、
保序回归、24 位打包往返）。旧的
`FableSolExportHdr10PlusCurveTest` / `FableSolHdr10PlusPayloadTest` /
`FableSolHdr10PlusPayloadDecodeTest` 三个文件随旧 API 一并删除。

全量 `:app:testDebugUnitTest` 458 例、0 失败、1 跳过；`:app:assembleDebug` 通过。

**真机验证（OPPO PLZ110）**：显式 HDR10+ 导出成功，ffprobe 逐字段核对：

```
targeted_system_display_maximum_luminance = 10000000  → 1000 尼特（用户参考显示峰值）
average_maxrgb = 1601/100000                          → 160 尼特（线性实测）
knee_point_x = 4095/4095, knee_point_y = 875/4095     → Case 3 中性曲线
num_bezier_curve_anchors = 9, anchors = 102/205/.../921（单调占位值）
```

对比批次 4 之前，`targeted_system_display_maximum_luminance` 从 19488001（母版峰值，
显然是错的）纠正为 10000000（参考显示峰值）。

**一个需要用户知情的结果**：这段素材的**每一帧**都落在 Case 3 中性曲线上。原因是 D113 规定
横轴优先用 V8（99.98% 分位），而 FableSol 的高光是刻意设计的稀疏点缀——MaxCLL 高达 1948
尼特，99.98% 分位却只有约 214 尼特，远低于参考显示峰值的下限 300 尼特。因此压缩分支在默认
参数下**不会触发**，动态元数据携带的是准确统计量，曲线则等价于恒等映射。

这正是 D113 明确接受的取舍（"避免最亮约 0.02% 的孤立像素拉伸整帧曲线"），实现没有偏离决策；
但它意味着这一整套曲线机制对本素材基本不产生画面影响。是否要为 FableSol 这类"稀疏高光"内容
改用 MaxSCL 优先（即 D95 的原顺序），属于需要用户裁定的产品问题，已记入 followups.md。

**补记（2026-07-29，参考显示峰值滑杆）**：批次 5 收尾时补上了这一项，用户裁定"取 V8 就行"，
D113 的横轴归一化不动。

- 新增 `FableSolExportReferencePeak.kt`：档距不均匀（`300～1000` 每 25、`1000～4000` 每 100、
  `4000～10000` 每 500，共 71 档），所以滑杆的 `progress` 是**档位下标**而不是尼特值，
  下标与尼特的换算只有这一处——它同时被滑杆、快捷值、"采用本机值"和持久化四条路径使用，
  错一档就会出现"界面显示的数与写进载荷的数不一致"。对齐取**最近档**而不是向下取整：
  本机声明值常落在两档之间，向下取整会让用户刚看到的数悄悄变小。
- 设置页新增「参考显示峰值」滑杆与「参考值」一行（400/600/1000/2000/4000 + `本机（N）`），
  两者只在 HDR10+ 下显示。`本机` 是一次性取值并保存为数字，不建立持续跟随关系（D94）。
- 信息栏补三条（D94、D116）：面板声明值不等于实际播放亮度；`≤ 400 尼特` 的低峰值取舍；
  `漫反射白 × HDR 强度 > 10 × 参考峰值` 的曲线风险预告。本机未声明 HDR10+ 时另提示本机播放
  可能退回 HDR10。
- **胶囊必须换行。** `makeExportChoiceRow` 把标签与胶囊挤在一行，六个胶囊会溢出到屏幕外——
  溢出的既点不到、也不在 uiautomator 的可见节点里，等于凭空消失（真机上 `本机（2000）`
  第一次就是这么"不见"的）。新增 `makeExportWrappingChoiceRow`，标签在上、胶囊按可用宽度
  换行在下。

新增 JVM 测试 `FableSolExportReferencePeakTest`（6 例：三段档距与端点、严格递增无死区、
下标与尼特互逆、取最近档而非向下取整、默认值与全部快捷值精确落在刻度上、范围与决策一致）。
全量 `:app:testDebugUnitTest` **464 例、0 失败**。

真机核对：滑杆显示 `标准（1000 尼特）`，点 400 变 `自定义（400 尼特）` 并触发低峰值提示，
点 `本机（2000）` 变 `自定义（2000 尼特）`——该机声明的期望内容峰值正是 2000 尼特。

**本批结束时的正式像素状态**：像素未变（HDR10+ 只改元数据）；HLG 仍沿用旧输出变换，D132 的
修正在批次 6。

### 批次 6：HLG 与杜比视界 8.4

预计主要触点：

- `FableSolExportPresenter.kt`
- `FableSolExportP010Bridge.kt`
- `FableSolExportHdrFormat.kt`
- `FableSolHdrExportCapability.kt`
- 新的 HLG 范围回环验证/缓存组件
- `FableSolTuningDialogFragment.kt`

执行项：

- [x] 实现显示参照 Rec.709→BT.2020、逆 OOTF、场景线性肩部与 HLG OETF。
- [x] 固定 75% HLG 参考白，不引入绝对亮度滑杆。
- [x] 实现逐像素颜色方向的 `q(u)`、共同 `2.0×` 起点和端点一致指数肩部。
- [x] 建立以 `C_n(u) = C_S(u)/q(u)` 为键的连续插值参数表与数值自检（D164），
  不做逐帧动态 HLG。
- [x] 实现真实 P010 编码—解码回环和 Y′/Cb/Cr 连续安全区间。
- [x] 按 D165 构建方向域 `W_device(u)` 查表（二分 + 固定步长整段检查）。
- [x] 实现自动增强/名义范围、未知时导出前验证和完整签名缓存。
- [x] 删除杜比视界 5/8.1 产品路径，只保留 8.4。
- [x] 杜比视界 8.4 复用 HLG P010、闭环与 super-white，保留名义范围同格式后备。
- [x] 更新条件控件、说明、完成态与诊断。

验证项：

- [x] 逆 OOTF/OETF 参考向量与 75% 参考白测试。
- [x] 颜色方向连续性、指数肩部端点/斜率与合法范围测试。
- [x] 回环样本解析、部分分量余量和无法验证时名义范围测试。
- [x] 自动档忽略隐藏历史值测试。
- [x] 杜比视界 8.4 同格式后备与成功后非破坏性诊断测试。
- [x] 相关单测、全量 JVM 单测和 debug 编译通过。

完成记录（2026-07-29）：

**接手时的状态**：上一轮会话已经写好三个纯数学文件（`FableSolExportHlgTransform`、
`FableSolExportHlgDeviceRange`、`FableSolExportHlgPlan`）并改写了 `export_present.frag` 的
HLG 分支，但工程处于**不可编译**状态，且那半条通路是坏的：

- `FableSolExportP010Math.SignalRange` 的 `chromaMinCode/chromaMaxCode` 已拆成 Cb/Cr 四个
  字段，两个测试没同步，`:app:compileDebugUnitTest` 直接失败。
- `FableSolExportPresenter.hlgPlan` 恒为 `null`，没有任何调用方传入。后果不是"沿用旧输出"，
  而是**肩部表为空**（`ξ = 0`，不压缩），高光落到着色器末端的 `clamp` 上——那正是**逐通道
  硬钳**，D131 明令禁止的写法。
- D139/D140 的回环验证完全没有，`W_device` 表因此永远建不出来。

本轮补完的部分：

| 新增 | 内容 |
|---|---|
| `FableSolExportHlgLoopback.kt` | 回环测试图与判定规则（纯算术，JVM 可测）：逐分量阶梯、色块几何、安全区间推导 |
| `FableSolExportHlgVerification.kt` | 真实编码→封装→解码→CPU 读回 P010 的完整链路、按 D138 完整签名缓存、诊断行 |
| `FableSolExportHlgRange`（在 `FableSolExportRequest.kt`） | 产物**实际**的信号范围。请求侧的 `自动增强` 是意图，不能当结论 |

几处必须记下的判断：

- **请求与产物必须是两套词汇。** `FableSolExportHlgSignalRange`（自动增强/名义范围）是意图，
  `FableSolExportHlgRange`（扩展/名义）是结论。合并成一个枚举，`自动增强` 就会以"结论"的身份
  出现在完成 Dialog 上——而那次验证可能根本没通过（D136）。
- **容差必须小于阶梯步长**（6 < 8）。这是本轮唯一一处真实的算法缺陷，由 JVM 测试逼出来：
  原先容差 10、步长 8，编码器把某一级钳住时下一级只差 8，误差仍落在容差内而蒙混过关，安全
  上限会比真实上限高整整一级。已写入 D172。
- **判定的主力是"与名义端点拉开距离"，不是误差。** 钳制的误差可以很小（945→940 只差 5），
  只有"整段有没有塌缩到同一个端点"才判得出来。
- **色彩标记缺失与冲突同样判未通过**（D139），这与 D166 允许静态元数据"容器没携带也不判失败"
  不同：那一条的实际承载者是码流 SEI，而信号范围没有第二个承载处。
- **验证必须在 `sink.tagFormat` 与 `createMuxer` 之前跑完**（D138）：它自己要建一个同名编码器，
  而信号范围一变，量化边界与肩部容量都变，渲染到一半再换等于把已编好的帧作废。
- **准备阶段是一个新的总线状态**，不是把 `Running(0, 0)` 复用一下：`Preparing(jobId, stageId)`
  带的是**稳定标识**，进度 Dialog 与通知各自按当前 locale 取字符串，旧版服务遇到新阶段时退回
  通用的"正在准备"，不会把内部代号摆给用户。
- **信号范围行的标签要能改写。** 同一个设置在普通 HLG 下叫「HLG 信号范围」、在杜比视界 8.4
  下叫「HLG 基层信号范围」（D137、D144）。造两行再互相显隐会让两份持久化状态并存，因此给
  `makeExportChoiceRow` 加了一个交出标签视图的口子。
- **显隐判据是"用户显式选了哪个格式"，不是解析出来的落点。** `format` 在"自动"下也会给出候选
  顺序的第一名，用它做判据会让自动档冒出一个自动档并不读取的设置（D137）。

新增 JVM 测试 `FableSolExportHlgTest`（21 例）：75% 参考白与逆 OOTF 齐次性、中性色不使用
super-white、三原色方向的 `103.30% / 100.77% / 107.65%`、压缩起点的 `4.92× / 5.50× / 3.32×`、
D164 的 `q` 反例、肩部三条边界条件与单调/不放大对比度、共同增益保色度、方向表连续无台阶、
固定步长采样的单调可行性、部分分量余量、阶梯塌缩与名义端点作废、Cb/Cr 各自区间、
杜比视界 8.4 的基层与后备语义、着色器与 Kotlin 的逐行对照。

全量 `:app:testDebugUnitTest` **492 例、0 失败、1 跳过**；`:app:assembleDebug` 通过。

**真机验证（OPPO PLZ110 / Android 16，用户本轮明确授权 ADB）**：记事「测试音频呀」，
音频 7.13 秒，三次导出。

| 设置 | 完成态 | ffprobe |
|---|---|---|
| HLG · 自动增强 | `HLG · HEVC 10-bit（硬件编码），120 fps`，**信号范围：HLG 扩展信号范围** | hevc Main 10 / yuv420p10le / bt2020nc / arib-std-b67 / bt2020 / tv |
| HLG · 名义范围 | 同上，**信号范围：HLG 名义范围** | 同上 |
| 杜比视界 8.4 · 自动增强 | `杜比视界 8.4 · HEVC 10-bit（硬件编码）`，**信号范围：HLG 名义范围** | `dv_profile=8`、`dv_level=11`、`rpu_present_flag=1`、`el_present_flag=0`，基层 bt2020nc / arib-std-b67 / tv |

回环缓存（`shared_prefs/fablesol_export_hlg_range.xml`）把两条通路的结论分得很清楚：

```
c2.qti.hevc.encoder | app-p010 | c2.qti.hevc.decoder → 1019,4,1019,4,1019 ; ok
c2.qti.dv.encoder   | app-p010 | c2.qti.dv.decoder   → nominal ; extended-codes-collapsed
```

HEVC 通路上三个分量的**完整**视频数据范围都被保留（`W_device` 因此恒为 `W_MAX`，查表退化为
常数）；同机杜比视界通路的阶梯塌缩，判为无法验证并按 D143 第 3 条导出名义范围基层——这不是
失败，产物仍是完整的杜比视界 8.4。

**super-white 对本素材的实际影响很小，但方向正确。** 两次 HLG 导出（自动增强 vs 名义范围）
逐帧比对：

- **前 123 帧逐位相同，第 124 帧起才发散**——那一帧正是内容首次越过 `2.0×` 膝点的位置。
  膝点以下两条路径本来就是同一个恒等映射，这条时间线本身就是"肩部真的接上了"的证据。
- 逐像素差**对称**（更亮 1.535 亿样本、更暗 1.527 亿，均值 0.01 码值，峰值 ±124）：一旦输入
  有差别，硬件编码器的量化决策就整体发散，逐像素差因此测不出系统性增益，只测得到发散噪声。
- 对编码噪声稳健的高光统计量才看得出方向。第 124 帧起的均值：99.9% 分位 `0.85693` 对
  `0.85650`、99.99% 分位 `0.93086` 对 `0.92930`、`> 0.95` 的像素数 `571.0` 对 `554.1`。
  三项一致偏高，但 99.99% 分位只高约 `0.0016` 信号（约 1.4 个码值）。

原因是内容属性而非通路失效：这段素材的高光以**星芒核心**为主，而星芒是白的，中性方向按 D134
本来就止于 100%，拿不到任何 super-white；能用上扩展色容积的是水体的高饱和高光，这部分像素
很少。若要看到明显差别，需要高饱和且明显越过 `2.0×` 膝点的素材。已记入 followups。

**本批结束时的正式像素状态**：SDR 三种语义、HDR10/HDR10+ 的元数据与 HLG/杜比视界 8.4 的输出
变换全部到位。**批次 2～5 期间"HLG 沿用旧输出变换"的中间态到此结束**，HLG 可以作为画质基线
使用。剩余批次 7（离线编码画质策略）与批次 8（集成验证与交付）。

### 批次 7：离线编码画质策略

预计主要触点：

- `FableSolExportOptions.kt`
- `FableSolExportEncoder.kt`
- `FableSolExportHdrFormat.kt`
- `FableSolExportCapabilityMatrix.kt`
- `FableSolTuningDialogFragment.kt`
- `FableSolExportBitrateText.kt`
- `FableSolExportSpecText.kt`

执行项：

- [x] 用户模式改为 CQ/VBR；CBR 只作 VBR 不可用时的实际内部后备。
- [x] CQ 默认实际候选 `qualityRange.upper`；默认纯 CQ 下发，失败按 D167 同模式兼容
  阶梯（CQ+码率提示）重试并在诊断记录实际形态。
- [x] 实现按实际像素率、族、位深和信号推导的 VBR 默认值及自定义状态。
- [x] 标定并版本化 HEVC/AV1/AVC 与 SDR/HDR 系数。
- [x] 增加默认关闭的 B 帧选项，适用时请求连续 B 帧上限 1。
- [x] 增加默认开启的高复杂度编码。
- [x] 增加 VBR 默认开启的复杂帧质量保护，适用时使用 QP max 40。
- [x] 所有导出请求 non-realtime priority，不设置 operating rate。
- [x] Profile 保持画质要求，Level/Tier 改为最低充分值；CQ 按 D168 只以尺寸与像素率
  定档。
- [x] 保留 0.5～10 秒关键帧滑杆和 2 秒默认值，不跨候选自动改写。
- [x] 更新设置说明、估算、进度、完成态和诊断。

验证项：

- [x] CQ/VBR/CBR 的 `MediaFormat` 键互斥与后备测试。
- [x] 自动码率、自定义迁移、合法范围夹取测试。
- [x] B 帧、复杂度、QP、priority、operating rate 正反组合测试。
- [x] AVC/HEVC/AV1 最低充分 Level/Tier 边界测试。
- [x] 关键帧 Float 下发与跨候选一致性测试（旧系统整数秒兼容分支已删除，不得实现）。
- [x] 相关单测、全量 JVM 单测和 debug 编译通过。

完成记录（2026-07-29）：

本批只改**申请给编码器的那份 `MediaFormat`**：不动一个像素，也不动任何元数据。正因如此
它错了不会有异常——只会让产物悄悄变小、变糊，或被只支持较低 Level 的设备直接拒收。

| 新增 | 内容 |
|---|---|
| `FableSolExportBitrateModel.kt` | D147 的乘积式自动码率：像素率 × 族系数 × 位深/信号系数，集中版本化常量 |
| `FableSolExportLevel.kt` | AVC/HEVC/AV1 三张标准 Level 表与"取刚好够用的一档"算法，含 HEVC Tier 与 DPB 约束 |

几处必须记下的判断：

- **码率必须在候选生成时解析，不能等到 configure。** D147 的自动值依赖对齐后的实际宽高、
  帧率、族与位深，而 D152 的 Level 又要拿解析后的码率算——两者都只有在 `collect()` 的
  那个循环里才齐备。因此 `bitrateBps` 成了 `FableSolExportTier` 的字段，探测与正式导出
  读的是同一个数，不可能算出两份。
- **`Level` 不是画质档位。** 旧代码 `advertised.maxByOrNull { it.level }` 是把它当画质档
  在用：申请更高的 Level 不会让同一 Profile 的画面更清晰，只会让本来放得动这段视频、却只
  支持较低 Level 的设备直接拒收。现在按 H.264 表 A-1、H.265 表 A.1/A.2 与 AV1 附录 A 算
  最低需求。"取最高档"只剩杜比视界那一处兜底，并由单测钉住"只此一处"。
- **High Tier 只在同档 Main Tier 装不下解析后的码率时才用**（D152）。编码器不广告该档
  High Tier 时不得凭空使用，改为升到下一档 Main Tier。
- **CQ 不为未知码率抬档**（D168）：只按尺寸与像素率取档、保持 Main Tier，接受实际码率名义
  超出该档标准上限。
- **码率滑杆的"自动"不是一个开关，而是"没保存过这个键"。** D147 明令不得为自动/自定义新增
  按钮、标签组或开关；把状态编码进"键在不在"之后，现有的"恢复默认"（删键）天然把滑杆送回
  自动值，一行额外代码都不需要。
- **关闭高复杂度要省略 `KEY_COMPLEXITY`，不是下发下限**（D149）。"不额外要求最高复杂度"与
  "主动要求最低画质"是两件事，后者会让厂商默认值失效。
- **三个编码工具字段记的是"本次真的写了那个键没有"**，不是用户开关的原样回声。开关打开但
  当前档位不适用时（AV1 没有 B 帧、编码器不公开复杂度区间、不声明 `FEATURE_QpBounds`），
  我们根本不会写，完成信息就该如实说没写。
- **源码契约测试不能按字面搜关键字。** `assertFalse(contains("isFeatureSupported("))` 与
  `assertFalse(contains("KEY_OPERATING_RATE"))` 都被自己的**解释性注释**判死了——那两条
  注释恰恰是在讲为什么不用它们。判据改为落在实际的调用/写入形态上，并把允许查询的能力位
  逐个点名（当前只有 `FEATURE_QpBounds`），多出一处就失败。

新增 JVM 测试 `FableSolExportEncodingStrategyTest`（14 例：自动码率随像素率线性、族与信号
系数序关系、两个标定锚点、自定义值不缩放、HEVC 最低充分档与 60/120 fps 差异、High Tier
的两条分支、CQ 不抬档、AVC 宏块计数与 High Profile 的 1.25 倍系数、AV1 无 Tier 轴、广告
档位不足时的兜底、DPB 与参考结构、四种码控形态的携带码率语义与稳定标识往返、可行组合表
的形态往返与换编码器不采纳、B 帧适用范围、`MediaFormat` 每个键的正反组合、探测与正式导出
同形态）。全量 `:app:testDebugUnitTest` **508 例、0 失败、1 跳过**；`:app:assembleDebug`
通过。

**真机验证（OPPO PLZ110）**：两次 HDR10 导出。

```
编码：目标码率（VBR） · 已申请高复杂度、无 B 帧      → 24.15 MB（27 Mbps）
编码：目标码率（VBR） · 已申请高复杂度、已申请 B 帧  → 23.95 MB（27 Mbps）
```

- `level=150`（HEVC Main Tier Level 5）正是本画布 `1152×1472@120`（2.03e8 样本/秒）的最低
  充分档：`MaxLumaPs` 到 L4 就够，`MaxLumaSr` 要 L5 才够。该机广告的最高档恰好也是 L5，
  所以这一项在**这台设备上**分不出新旧算法的差别，真正的区分要靠单测里的边界用例。
- 自动码率生效：同一段素材、同一画布，批次 6 用旧的固定 24 Mbps 目标产出 30.9 Mbps，本批
  按模型算出的 20.7 Mbps 目标产出 27.0 Mbps，两次的 VBR 上浮比例一致（约 30%）。
- **B 帧的键确实下发了，但这台编码器一帧 B 都没产出**（`has_b_frames=0`，前 240 帧全是
  `I` + `P`）。这正是 D148 写明的语义：`KEY_MAX_B_FRAMES` 是**上限**不是强制数量，因此
  完成文案用的是"已申请 B 帧"而不是"码流含 B 帧"。

**两项本设备上无法验证的**：

- **该机的 `c2.qti.hevc.encoder` 没有公开可用的 CQ 质量区间。** 用户偏好里没有
  `export_rate_control`（即默认的恒定质量），而完成态落在 VBR——`FableSolExportRateControlForm.resolve`
  只有"用户选了 CQ"与"档位有 `qualityRange`"两个输入，因此可以断定是后者为 null。D146 的
  最高质量默认值与 D167 的同模式兼容阶梯因此在这台机器上跑不到，只有单测覆盖。
- **复杂帧质量保护未生效**：该编码器不声明 `FEATURE_QpBounds`，按 D151 省略 QP 上限并继续
  导出，完成信息如实不列该项。

**本批结束时的正式像素状态**：像素与元数据均未改变；变的只是申请给编码器的配置。剩余批次 8
（集成验证与交付）。

### 批次 8：集成验证、文档与交付

执行项：

- [x] 全量 `:app:testDebugUnitTest` 通过。
- [x] `:app:assembleDebug` 通过。
- [x] `git diff --check` 通过，且没有无关文件被覆盖。
- [x] 检查 13 套语言的新增字符串及本地化诊断缓存。
- [x] 检查取消、重启候选、失败清理、成功提交、通知/分享失败的完整状态机。
- [x] 记录实际画布下全片预分析、P010 回读与 HDR10+ 统计的耗时与峰值内存
  （GLES 3.0 后端本机取不到，见设备矩阵第二节）。
- [x] 准备用户手动设备矩阵（[device-matrix-2026-07-29.md](device-matrix-2026-07-29.md)）。
- [x] 更新 README 当前能力表。
- [x] 更新本文件各批完成记录、[sessions.md](sessions.md) 与 [followups.md](followups.md)。
- [x] 用户要求 debug 发布时生成 feature debug 更新日志并执行仓库规定的发布任务
  （2026-07-29 完成，发布号 `202607291308`，日志见
  [update-20260729210641.md](debug-updates/update-20260729210641.md)）。

设备抽查项（PLZ110 已覆盖的打勾，其余移交用户）：

- [x] 原生 SDR、SDR-TM、SDR-DTM。
- [x] SDR 自动/严格 10-bit/严格 8-bit。
- [x] HDR10、HDR10+、HLG、杜比视界 8.4。
- [x] HLG/杜比视界 8.4 扩展信号范围与名义范围。
- [ ] GLES 3.1 与 GLES 3.0 HDR10+ 统计后端（本机只有 3.1）。
- [x] 应用 P010、同格式 Surface；[ ] 软件编码器（本机硬件通路齐全，跑不到软件回退）。
- [ ] CQ/VBR、B 帧、高复杂度与复杂帧质量保护（本机无 CQ 区间、不声明 `FEATURE_QpBounds`；
  VBR、B 帧键与高复杂度已验）。
- [ ] 系统相册格式标识、普通观看、分享平台二次转码。

完成记录（2026-07-29）：

本批只做"验证与交付"，一行业务逻辑都没改；但验证本身找出了三处真问题。

**新增两个 JVM 测试**：

| 新增 | 内容 |
|---|---|
| `FableSolExportLocalizationTest`（6 例） | 13 套语言的覆盖、格式参数一致性、裸 `%` 必须 `formatted="false"`、能力缓存不存本地化整句、本轮新增文案逐个点名、已作废文案确实删干净 |
| `FableSolExportStateMachineTest`（7 例） | 未提交产物必经同一个 `finally` 清理、同 tier 重启能重新建 MediaStore 行、提交结果被检查且不在 `finally` 里、D59 的三处收尾动作各自兜底、取消在每个阶段边界都查、静态元数据核对没被搬回正式路径、能力探测不写用户偏好 |

**测试当场找出的三处**：

- **`fablesol_export_hdr_format_name_dolby_vision_5` 与 `_81` 仍在四套语言里**，而 D141 早已
  把杜比视界收敛为 8.4、代码里一处引用都没有。留着只会让下一个人以为那两档还是产品能力。
  已从 `values` 与三套中文里删除，并加进"已作废文案"清单。
- **三套中文的 `fablesol_export_reference_peak_risk` 含裸 `%`（"99.98% 分位"）却没有
  `formatted="false"`。** aapt2 要两个以上裸 `%` 才报错，一个能过——于是它一直潜伏着，
  只要哪天这条字符串被送进 `String.format` 就会炸。已补上属性。
- **源码契约测试不能按字面搜关键字**（批次 7 已踩过一次，这里又踩两次）：
  `assertFalse(contains("isFeatureSupported("))` 与 `assertFalse(contains("KEY_OPERATING_RATE"))`
  都被**解释这些 API 为什么不用**的注释判死了。判据一律改成落在实际调用形态上，并把允许的
  用法逐个点名。

**状态机复核结论**（读代码 + 固化为测试，无需改动）：

- 取消、候选失败、SDR 语义重启与异常四条出口共用同一个 `finally { if (!published) discard() }`；
- `discard()` 会清空 `uri`，因此同 tier 重启时 `createMuxer()` 能重新插一行，既不会写进已删
  除的行，也不会在相册里留下孤儿 pending 记录；
- `commit()` 的返回值被检查，且**不在** `finally` 里——提交失败意味着产物仍挂在 `IS_PENDING`
  上、相册里看不见，此时报成功就是骗人；
- 通知的 `PendingIntent` 构建、分享按钮与 `notify()` 三处各自兜底（D59）。

**真机测量与确定性**：耗时、峰值内存与确定性结论见
[device-matrix-2026-07-29.md](device-matrix-2026-07-29.md) 第一节。两条最值得记住的：

- **画质通路的代价是导出时长增加两到五倍**：8-bit Surface 12.1 s，同素材的 HDR10+ 首次
  68.2 s。三项内部通路的单价分别是全片预分析 21 ms/帧、HDR10+ 逐帧统计 20 ms/帧、
  应用 P010 相对 Surface 27 ms/帧；P010 路径的峰值内存高约 95 MB。
- **产物字节不可复现，但我们这一侧是确定的**：同设置连导两次的文件大小相差数万字节，而全片
  预分析的统计值逐位复现（`9.593701456980474|0.8043879067632341`——对 856 帧每个像素做的
  归约）。进一步的证据是"一对产物前 166 帧逐位相同、另一对从第 1 帧就不同"：同样的渲染条件
  出现两种结果，发散源只能在硬件编码器的码控。批次 8 的门禁措辞是"相同预分析统计和确定性
  像素控制量"，不是"字节一致"，因此这一条通过。

### 回归修复：HDR10+ 亮度往复变化（2026-07-29，D176，后由 D177 取代）

用户报告批次 5 之后的 HDR10+ 产物亮度频繁往复变化。诊断结论见
[sessions.md](sessions.md) 的两条 2026-07-29 条目，决策见 decisions.md D176。像素通路一行
未动，改的全部在 `FableSolExportHdr10PlusCurve` 与它的接线。

| 改动 | 内容 |
|---|---|
| 横轴基准 | `s = 线性亮度 / M`，`M` = 声明母版峰值，全片常量；不再是逐帧 V8（修订 D113） |
| `M` 的来源 | 从 `hdr10StaticInfo` 抽出 `FableSolExportTransfer.masteringPeakNits`，与 MDCV 写入值同一处 |
| `M ≤ T` | 配置级判定，全片同一条 `Kx = 1、Ky = M/T` 中性曲线，逐位恒定 |
| `M > T` | `kneeX = k/M、kneeY = k/T`，`P1 = (M−k)/(10(T−k))`，膝点上限 `(10T−M)/9` 为全片常量 |
| `M > 10T` | 配置级拒绝，`unsupportedReason` 是唯一判据，构造函数与设置页共用（修订 D115） |
| `M = 10T` | 膝点退化为 0 → `P1 = 0/0` → NaN 通过全部比较型门禁 → 全黑；判据收紧到 12 位膝点的第一个非零档 |
| 时间平滑 | 中性分支不再 reset；膝点、肩部拟合覆盖上界补上快压慢放；删掉按样本数量切换的硬开关 |
| 完成信息 | 恒等配置下如实说明"画面与 HDR10 一致"，并不显示不生效的高光起点 |
| 设置页 | `fablesol_export_reference_peak_risk` → `_infeasible`，风险预告改为确定结论 + 两个数（13 套语言） |

`FableSolExportHdr10PlusTest` 从 17 例增至 20 例：三条随新语义改写（中性曲线、十倍拒绝、
膝点下移），新增膝点平滑、恒定横轴接收端与载荷稳定性三条。**恒定横轴接收端那条是本次的
验收核心**——它按 `out(L) = F(L/M)·T` 建模用户的播放链路，直接钉住失效模式。

全量 `:app:testDebugUnitTest` **524 例、0 失败**；`:app:assembleDebug` 通过。**未使用 adb，
未在真机验证**；产物侧核对方式见下。

产物侧验证（用户导出新样片后可执行）：

```powershell
& ffprobe -v error -select_streams v:0 -read_intervals "%+#853" -show_frames -of json <file>
```

解出逐帧 `knee_point_x/y`：`T ≥ M` 的素材应全片恒定为 `(4095, round(M/T × 4095))`，不得再
出现 `Ky` 随帧统计移动的序列。

### 正确修复：连续动画改用场景统计（2026-07-29，见 D177）

用户在已包含 D176 的 OPPO 样片上仍能看到星芒出现时水体变暗。通过 adb 只读拉取最近的
203 尼特、350 尼特 HDR10+ 样片，并逐帧解出 ST 2094-40 后确认：

- 203 尼特样片 853 帧的 `Kx`、`Ky` 与 9 个 anchors 已经逐位相同，D176 确实生效；剩余
  亮度变化不是曲线跳变；
- MaxSCL、V8 与 FBP 仍按帧剧烈变化，FBP 约为 `0.001～0.828`，最大单帧下降约 `0.479`，
  且与 MaxSCL 的相关系数约为 `-0.898`；
- ST 2094-40 把 MaxSCL、AverageMaxRGB、CFD 与 FBP 定义为场景量，接收端可以不依赖显式
  曲线而单独使用这些统计做全局亮度调整。旧实现把 120 fps 的每一帧当成一个新场景，才是
  D176 之后仍会呼吸的原因。

实现据此改为：

| 改动 | 内容 |
|---|---|
| 场景边界 | 当前完整 FableSol 连续动画固定为一个 HDR10+ 场景 |
| 预分析 | 每帧仍从最终线性 BT.2020 合成精确测量，但只作为场景累计的原始样本 |
| 场景统计 | MaxSCL 跨帧取最大；AverageMaxRGB/CFD 累计全部像素；FBP 取最亮代理帧；桶计数改为 64 位 |
| 曲线横轴 | 恢复规范坐标：场景 V8 优先、场景 MaxSCL 回退；不再使用 MDCV 母版峰值 |
| 曲线生成 | 预分析后只求解一次；正式编码循环每帧重复同一份完整载荷，不再统计或拟合 |
| 时间处理 | 删除发送端逐帧快压慢放；当前没有逐帧曲线状态需要平滑 |
| 可行性 | D115 继续使用，但输入改为实际场景 `S/T`；设置页不再用理论母版上界提前误报无解 |
| 完成文案 | 恒等判定来自实际场景曲线；13 套语言统一改为“整段场景统计/场景源峰值” |

新增场景累计、64 位桶计数、代理帧缺失时 FBP 写未计算、场景 V8/MaxSCL 横轴、203/350 尼特
水体稳定性、完整载荷逐位恒定与编码循环不得重算的回归测试。全量
`:app:testDebugUnitTest` **526 例、0 失败**
（1 例按既有条件跳过），`:app:assembleDebug` 通过；APK 位于
`app/build/outputs/apk/debug/app-debug.apk`，SHA-256 为
`174a7c4a14f0aaf9f259c6539f5a828865d40c4b7aaaade2ae05e2ee3610ff38`。本轮没有安装 APK，
也没有在设备上生成新样片，
因此最终观感仍需新版本在 OPPO 上各导出一段 203/350 尼特样片复核。

---

## 2026-07-26 首轮实现（批次 1～6 全部落地）

编译通过（`:app:assembleDebug`），全量 `:app:testDebugUnitTest` 无失败。**尚未在真机验证**。

### 批次 1：HDR 三层解耦

结论比计划里写的**小得多**——耦合本来就不在渲染器里，而在调用方。
`FableSolGlRenderer.initialize(hdrOutput)` 早已是一个参数化的入口，屏上传
`session.isHdrOutput`，导出传自己的探测结果即可。因此本批只做了三件事：

- `initialize(linearScene: Boolean)`：改名 + 补文档，说明"场景是否线性"由调用方决定，
  与任何显示器无关。
- 新增 `setOfflineTimebase(enabled)`：`render()` 里的 `now` 改由 frameTimeNanos 推出。
  **这一条是必需的**——`drainAndApply` 用 `now - lastAudioElapsed > IDLE_SILENCE_MS` 判静默，
  而导出的挂钟推进速度与音频时间无关，沿用挂钟会在两个音频 hop 之间误判成静默。
- 新增 `primeHdrForExport(strength)` 与 `FableSolHdrTransition.snapTo()`：把 headroom 钉在
  用户强度档、增益从第一帧就是满的，不走 0.36s 淡入。

另新增 `ScenePresenter` 接口与 `setScenePresenter()`：`drawFrame` 末尾在有 presenter 时
交给它，否则走原来的 `presentScene`。屏上路径因此**一个分支都没多**。

### 批次 2：确定性时钟

`TimelyClockView` 新增两个公开入口：

- `showTimeAtElapsed(millis)`：形变进度由 millis 解析求出（每秒的形变锚定在整秒边界、
  持续 `ANIM_DURATION_MS = 300`），不启动任何 ValueAnimator，同一个 millis 恒得同一画面。
- `breathingAlphaAtElapsed(...)`：录音态时钟呼吸的解析形式。

屏上路径未改动。

### 批次 3：重力轨迹

- `FableSolGravityTrack`：`Collector`（采集侧，按 50Hz 栅格零阶保持重采样）+ `readFrom`
  （解析侧，任何异常都当作"没有轨迹"）。chunk id `EDmo`，置于 `data` 之后。
- `AudioRecorder`：`startRecording()` 起算、`stopListening()` 收尾、`saveToWaveFile()` 写
  chunk 并把 RIFF 长度字段加上 chunk 体积。
- `AudioRecordDialogFragment.dispatchGravityToVisualizer()` 顺手投递一份给 recorder。
  记的是**送给可视化的那三个分量**（屏幕旋转补偿已做完），回放时直接喂 `setContainerGravity`。

**核实到一处与设计评审时的说法不同**：播放对话框**也有**倾斜传感器
（`AudioPlayDialogFragment` 同样注册 `TYPE_GRAVITY` 并 `dispatchGravityToVisualizer`）。
这不改变任何决策——导出一律离线，倾斜一律来自轨迹——但意味着"没有轨迹的历史录音按竖直
渲染"这条降级同时适用于两个入口。

### 批次 4：离线渲染引擎

新增六个文件：

| 文件 | 职责 |
|---|---|
| `FableSolExportSpec` | 画面规格与 `FableSolExportPlan`（卡片/画布/时钟几何、画框参数） |
| `FableSolExportEgl` | 编码器 input surface 上的 EGL 会话 + 建链前的能力探测 |
| `FableSolExportClock` | 未附着的 TimelyClockView 自绘成位图 |
| `FableSolExportPresenter` | 导出专用 present program |
| `FableSolExportAudioSource` | 流式解码成单声道 PCM |
| `FableSolVideoExporter` | 驱动循环 |

外加 shader `shared/fablesol/glsl/export_present.frag`。

**偏离计划之处（值得记下）：**

1. **画框 + 时钟 + 传递函数合并成一个 pass。** 原计划是"线性画布 FBO → 叠时钟 → 编码"三遍，
   实际发现全程在线性浮点里算、最后一行才套 OETF，一个 fragment shader 就够，时钟的
   alpha 混合照样是物理正确的。省掉一个全画布 FBO 和两次全屏绘制。
2. **像素高度定档 1296**（= 144 × 9，16 对齐）。density 由 `1296 / 420dp` 反推，**不取设备
   density**——否则物理容器宽度会跟着导出分辨率漂移。
3. **`uCanvasPx` 被删。** 它声明了却没在 shader 里用到，GLSL 编译器会把它优化掉，
   `FableSolGlProgram.uniform()` 的 `check(location >= 0)` 会当场抛。这类"声明未用的 uniform"
   在这个封装下是运行时崩溃，不是警告。

### 批次 5：前台服务与落地

- `FableSolVideoExportService`：`mediaProcessing` 类型前台服务，通知带滚动更新的 ETA
  与取消动作，单线程队列（第二次点击排队而非并发）。
- `FableSolExportSink`：API 29+ 走 MediaStore + `IS_PENDING`，老系统写公共 Movies 目录再
  扫描。**两条路都不做整份文件的二次拷贝**——编码直接写进最终位置，失败就删条目。
- 剩余空间检查放在 `FableSolVideoExporter` 里（那里才知道时长与码率），按预估体积 × 1.2 判。
- Manifest：`FOREGROUND_SERVICE_MEDIA_PROCESSING` 权限 + service 声明。

### 批次 6：入口、设置与文案

- 录音对话框停止态：`[重录][对号][导出][取消]`，导出 FAB 48dp、accent 圆形底、淡入。
- 播放对话框：进度条右侧 40dp 图标按钮（滑杆右边距相应从 20dp 改到 56dp）。
- 共用图标 `act_fablesol_export_video`；当前实现采用补全左、上画框的 Material
  `video_frame_save`（见 D25）。
- GLES 不可用（`WaveVisualizerFableSolHost.isGlActive()` 为假）时两处入口都 `GONE`。
- 调参 Dialog 新增「导出」分组：帧率上限（120/60 开关）、恒定质量、质量档、目标码率、
  关键帧间隔，外加一行推导结果（MB/分钟 · 耗时倍率）作为无预览参数的反馈回路。
  纳入「恢复默认」。
- 13 种语言文案。

---

## 2026-07-26 第二轮：真机反馈修正

编译通过，全量单测无失败。仍未真机验收。

### 录音对话框按钮行

原来重录/取消只改 `alpha`、**始终占位**，加进导出 FAB 后主按钮被挤得偏心。改为
**visibility 驱动**：准备与录音态整行只有主按钮（严格居中），停止态三个副按钮才
`VISIBLE` 并淡入；回到准备态时淡出后 `withEndAction` 收回占位。导出 FAB 尺寸从 48dp
改到 **56dp / padding 16dp**，与对号完全一致，两者都大于 40dp 的重录与取消。

### 播放对话框对齐与配色

- 进度条轨道左缘对齐时钟左缘：`marginStart` 16dp + `paddingStart` 8dp = 24dp（时钟的边距）。
  留 8dp 内边距是给滑块，直接把 padding 归零会让滑块在两端被裁掉。
- 导出按钮 icon 右缘对齐时钟右缘：`marginEnd` 16dp + `padding` 8dp = 24dp。**对齐的是
  icon 不是按钮区域**，所以边距要按 padding 折算。
- 图标着色与涟漪改为**跟随 App Chrome**（`app_chrome_control_unchecked` +
  `installAppChromeCircleRipple`），此前错误地跟随了记事 accent。这枚按钮属于 chrome，
  不属于记事——走带控件才跟记事颜色。

### 导出进度对话框

新增 `FableSolExportProgressDialogFragment` 与 `FableSolVideoExportBus`。

**关键语义**：导出**始终**在前台服务里跑、通知栏也始终有通知；对话框只是同一份状态的
另一个观察者。「在后台运行」只是关掉对话框，不改变任何执行路径——这一条纯粹是为了让
用户觉得直观。导出完成时对话框若还开着，就地换成完成态，给出「分享」与「添加为附件」。

「添加为附件」需要真实路径（本应用的附件模型是路径不是 URI），因此 `FableSolExportSink`
新增 `localPath()`：MediaStore 侧查 `MediaStore.Video.Media.DATA`，取不到就只提供分享。

通知标题改为「导出音频海浪动画视频」，完成后新增分享 action。

### 设置

- 入口文案「音频海浪动画参数调节」→「音频海浪动画设置」（13 语言）。
- 帧率与码率模式从 checkbox 改成**二选一的圆角标签**（60 fps / 120 fps，恒定质量 / 恒定码率）。
- 恒定质量档显示 **CQ 原值**（附设备区间），并隐藏目标码率；恒定码率档反之。
  设备完全不支持 CQ 时整个模式选择与质量行都不出现。
- 新增「导出 HDR 视频」开关，与 HDR 强度、设备能力构成三道门。
- 推导行加「约」字，并且**恒定质量档下不再给体积估算**。

**关于体积估算算错这件事**：用户实测一分多钟的视频只有二十几 MB，而面板按 24 Mbps 报
180 MB/分钟。原因不是把所有帧当成 I 帧算——公式 `bitrate × 60 / 8` 对真正的 24 Mbps 流是
对的——而是**默认走的恒定质量档里 `KEY_BIT_RATE` 只是提示**，实际码率由画面复杂度和
质量档决定，实测约 3 Mbps。所以正确的修法不是改公式，而是**只在恒定码率档给数字**，
恒定质量档改成「体积随画面复杂度变化」。

**关于「质量参数（CRF）/（QP）」的命名**：Android 的 `KEY_QUALITY` 既不是 x264 的 CRF、
也不是编码器内部的 QP，而是各厂商自行映射的一段区间（`getQualityRange()`），只保证
"越大越好"。因此界面上按**原值**显示并标注区间（如 `72  (0-100)`），标签写「质量参数（CQ）」
而不是 CRF 或 QP——后两者会让人以为可以照搬 x264 的经验值。

---

## 2026-07-26 第三轮：外部静态评审的十条，九条属实并修复

用户请 GPT 对导出链路做了一次静态评审。逐条核实后，**十条里九条是真缺陷**，一条（码率
范围）是注释与实现不符。全部已修，编译与全量单测通过，仍未真机验收。

| # | 问题 | 判定 | 修法 |
|---|---|---|---|
| 1 | `finish()` 一次 `drain()` 无产出就退出 | **属实** | 改为只受 30s 总时限约束，循环到两条轨都真的报 EOS；并 `check(muxing)` 防止无声无息产出畸形 MP4 |
| 2 | 离线时间轴用了未来音频；120fps 首帧步长错 | **属实** | 喂音频改到 `i/fps`（原为 `(i+1)/fps`，等于每帧前瞻一帧）；新增 `primeFrameTime()` 预置上一拍，首帧 dt 精确等于 1/fps |
| 3 | 离线分析器没套用户前端调参 | **属实** | 补 `FableSolFrontEndTuning` + `applyFrontEndStored` + `applyTo`，与录音、播放三处一致 |
| 4 | 能力探测后无真降级；`createEncoderByType` 未必是探到的那个编码器 | **属实** | `select()` 换成 `candidates()` 返回**带编码器名字**的有序候选，`createByCodecName` 创建；建编码器或建 EGL 失败就换下一档（含 120→60），全试完才失败，每次失败 `sink.discard()` 清残留 |
| 5 | 服务 `running` 竞态丢任务 | **属实** | 入队与"队列空即停工"放进同一把 `queueLock` |
| 6 | 锁屏后无 wake lock | **属实** | 加 `PARTIAL_WAKE_LOCK`（6 小时兜底超时），与 `mediaProcessing` 每日上限同量级 |
| 7 | 老系统 `Uri.fromFile` 外发会崩 | **属实** | 改走 `FileProvider.getUriForFile`，`external-path path="."` 已覆盖公共 Movies |
| 8 | "10-bit SDR" 实际经 8-bit 表面 | **属实** | EGL 位深改为跟随 `!eightBit` 而非 `hdr`；HEVC Main10 SDR 现在真拿 RGB10_A2 |
| 9 | "恒定码率"配成 VBR；码率范围注释不实 | **属实（一半）** | 支持 CBR 就用 CBR，否则退 VBR；码率下发前由 `tier.clampBitrate()` 夹到该编码器的 `bitrateRange`；注释改成"CQ 区间读设备、码率滑杆是通用范围+运行时夹取" |
| 10 | GLES 异步回退后入口不隐藏 | **属实** | 两处点击回调都再查一次 `isSupported`，不支持就地隐藏并返回 |

顺带自查发现并修掉的一条评审没提的：帧循环只喂到最后一帧的时间点，**音频尾巴（不足一帧
的那一段）从未进编码器**，音轨会比画面短一帧。收尾前补一次排空。

---

## 2026-07-26 第四轮：第二次外部评审，八条实质问题里七条属实

| # | 问题 | 判定 | 修法 |
|---|---|---|---|
| 1 | 120/60fps 物理子步不均匀 | **属实** | 帧时间戳是整数纳秒，`1e9/120` 截断后 dt 比 `PHYSICS_DT` 少约 0.33ns，累加器给出 `0,1,2` 序列——"120fps 每帧正好一步"从未成立。新增 `setOfflineFixedDt(1.0/fps)` 直接下发有理数步长 |
| 2 | 实时与离线初值不同，`max|Δ|=0` 判据不可达 | **属实** | 初值差异是有意设计（准备态就喂分析器、开始录音不重置），不改代码；**改判据**（见 followups.md）。代码侧只修掉预热门：离线调 `skipStartupGate()`——那道门拦的是麦克风冷启动，读文件没有这回事 |
| 3 | 队列竞态仍未彻底修复 | **属实** | 上一轮只堵了 poll/入队窗口，**收尾窗口没堵**：旧线程 finally 会释放新线程的 WakeLock、`quitSafely()` 新线程、`stopSelf()`。改为收尾前在锁内确认 `worker === thread && !running`；取消改为**按任务令牌**，不再 `queue.clear()` 误伤后来者 |
| 4 | 降级尝试中构造失败泄漏；`inputSurface` 未释放 | **属实** | 构造体移进 `configureCodecs()`，init 里 try/catch → `release()` 再抛；`release()` 补 `inputSurface.release()` |
| 5 | HDR 能力判定不完整；循环外失败不降级 | **属实** | API 34+ 加 `FEATURE_HlgEditing` 过滤；AV1 档限 API 34+（MediaMuxer 更早不支持 MP4 封 AV1）；`encoder.start()` 移进重试范围 |
| 6 | `commit()` 异常绕过失败处理 | **属实** | finally 里的 commit/discard 包 try/catch；服务侧 `runJob` 也包一层并向 Bus 报 Failed |
| 7 | Android 15+ 六小时 FGS 时限未处理 | **属实** | 覆写 `onTimeout(startId, fgsType)`：取消当前任务、报 Failed、收摊 |
| 8 | 重力轨迹开头用了未来采样 | **属实** | `Collector` 常驻记录最近读数，`start()` 把"此刻的姿态"落成 t=0 种子 |

零散项一并处理：GLES 回退新增 `onGlFallback` 回调，两处入口**立刻**隐藏而不是等下次点击；
候选编码器按用户所选的 CQ/CBR 模式排序，避免首个不支持就静默换模式；导出时钟补上录音开始
那 360ms 的淡入（0.36→1.0）再进呼吸，与屏上相位一致。

未处理并记为遗留：Bus 是全局单例，第二个请求会把第一个的状态重置为 Idle——当前一次只跑一个
导出、第二个排队，影响有限。

---

## 2026-07-26 第五轮：第三次外部评审，八条全部属实

| # | 问题 | 修法 |
|---|---|---|
| 1 | **P1** API 26–28 写不进公共 Movies | `WRITE_EXTERNAL_STORAGE` 在 Manifest 里带 `maxSdkVersion="28"`，但**运行时从未申请**（`PermissionUtil` 只申请读）。改为运行时查权限：没有就写应用自己的外部 Movies 目录（免权限、API 29 前同样可被扫描进相册），有就仍走公共目录 |
| 2 | **P1** Android 14 上 HDR 候选被全部误杀 | `FEATURE_HlgEditing` 是 **API 35** 才加入的能力位，我却从 34 起就拿它过滤——API 34 上一律 false，HDR 全军覆没并静默降 SDR。门槛改到 35，33–34 交给 configure/EGL 重试兜底 |
| 3 | **P1** 旧 worker 仍可能停掉新任务 | 上一轮把判决放进锁里，**执行仍在锁外**：判决后新 START 起了新线程，旧线程照样释放它的 WakeLock 并 `stopSelf()`。改为①WakeLock 由每个 worker 各自持有各自释放；②收尾用 `stopSelfResult(latestStartId)`——判决之后又来 START 的话 startId 已变，停止请求会被系统驳回 |
| 4 | **P2** 排队任务共用全局状态 | Bus 的每个 State 加 `jobId`，`Launcher` 铸 id 并同时下发给服务与对话框，对话框只消费自己那一个；新增 `Queued` 状态，排队中的对话框显示"准备中"而不是别人的进度 |
| 5 | **P2** MediaStore 提交失败仍报成功 | `commit()` 改为返回 `Boolean` 并检查 `resolver.update()` 的行数；调用点从 `finally` 移进**成功路径**，提交失败返回 `Failure` 并清理 |
| 6 | **P2** 录音/播放/导出的分析器起始状态不一致 | 统一**文件输入这条契约**：播放与导出都调 `skipStartupGate()`。"复现录音的预热状态"做不到（那段音频根本没被录下来），已在 followups.md 把 D15 ① 判据改写 |
| 7 | **P2** 降级尝试泄漏 EGL | `attemptEgl` 原是 try 内局部变量，`start()` 失败时 catch 够不着 → 上下文与 surface 随每档失败泄漏。提到 try 外并在 catch 里先于 encoder 释放 |
| 8 | **P2** 系统超时最终报成"用户取消" | `onTimeout` 先立 `timedOut` 旗再取消；结果映射与通知都把 `Cancelled` 翻译成超时失败 |

新增 JVM 门禁 `FableSolExportFixedDtTest`：钉住「有理数步长下 120fps 恒 1 子步、60fps 恒 2
子步」，并把当年那个整数纳秒截断写法的失败形态作为反例记录下来。

**仍未覆盖的测试**（GPT 列的清单里剩下的）：文件播放与导出的事件一致性、两个任务排队不串、
旧 worker 收尾不影响新 worker、commit/timeout/降级的失败路径、Android 14 的 HDR 候选。
这些都需要 MediaCodec / Service / Robolectric，项目目前没有这套环境；记在 followups.md。

**已知边界（未修，非缺陷）**：一次候选尝试只验证到 `encoder.start()`；`eglSwapBuffers`、
首次 `dequeueOutputBuffer`、`muxer.addTrack` 失败仍会终止整个导出而不降级——把它们纳入
重试意味着渲染到一半推倒重来。AV1-in-MP4 这个已知触发点已通过 API 34+ 门槛消除。

---

## 2026-07-26 第六轮：接手修复第四次评审发现的完整链路问题

本轮不再做局部补丁，把“候选编码档”和“前台服务任务”分别收成完整事务：

- `FableSolExportEncoder.finish()` 在返回前完成并检查 `MediaMuxer.stop()/release()`；
  `FableSolExportSink.commit()` 只能在其后调用，MP4 尚未写完时不可能解除 pending 或扫描。
- `FableSolVideoExporter` 每个候选从音频、muxer、codec、EGL、renderer 全新开始，任何渲染、
  首帧交换、输出格式、`addTrack()`、编码或封装错误都清理后尝试下一档。发布失败不重复渲染。
- 输出格式检查实际 profile、尺寸、完整 crop rectangle 和色彩标记；10-bit/HDR 静默降 profile 或 FP16 scene target
  回退 RGBA8 都不再报 HDR 成功。缺失但未冲突的色彩键会写入交给 muxer 的 track format。
- 候选尺寸按具体编码器要求与 64px 分享边界共同对齐，中性画框对称吸收补齐像素；补 H.264 Main/Baseline 和 profile+level。
- 编码器与音频源构造过程改为先登记资源所有权再 configure/start，构造异常同样能释放已创建
  的 codec、Surface、MediaExtractor 与 muxer。
- API 26–28 发起前请求写权限，旧版 sink 只写公共 Movies；文件原子占位、冲突自动改名，
  MediaScanner 回调确认后才算提交。文件名加入毫秒、jobId，并按 UTF-8 字节预留后缀空间。
- Service 改成主线程状态机 + 单工作线程：按任务取消，旧任务没有机会在锁外撤掉新任务的
  foreground；超时立即写失败终态。Bus 使用 `Map<jobId, State>`，终态拒绝旧回调覆盖。
- CQ 改为 64MB 实际空间保底并在编码中滚动复查；CBR 估算纳入 AAC 192kbps。
- 完成 Dialog 只显示可执行动作；排队态的取消与后台运行按钮也有明确监听器。

新增三组门禁：`FableSolExportStateRegistryTest`、`FableSolExportGeometryTest` 和
`FableSolExportPipelineSourceTest`。连同原有定步长测试，全量 `:app:testDebugUnitTest`
与 `:app:assembleDebug` 通过。全量 Lint 仍被项目原有基线挡住（488 errors / 1033 warnings，
首项是 `AutoNotifyReceiver.kt` 的旧 `MissingPermission`）；本功能筛选结果只剩既有
`FableSolExportClock` 的 KTX 风格提示。未使用 adb。已发布阿里云 debug 更新
`202607261246`，本地与远端元数据一致，APK SHA-256 为
`47c17e73260f24ae33c4388f8c373032692dc7920f1d5ed29afa5a9be811df85`。

---

## 2026-07-26 第七轮：双层水波、真实 HDR 能力门与播放结束态恢复

- `act_fablesol_export_video.xml` 继续保留圆角画框，内部改为两条从 Python 真实离线帧提取
  的开放贝塞尔轮廓：底层触及左右内框，上层从 x=9.2dp 开始；两者的峰谷位置、振幅不同。
- `FableSolHdrExportCapability` 在后台按 383dp 最大卡片画布实际编码一帧：依次验证 codec
  profile/尺寸/帧率、AAC+MP4 封装、RGB10_A2 HLG EGL surface、FP16 scene targets 和最终
  输出格式。探测期间或失败后 HDR 开关置灰，失败还会清除无效的 HDR 偏好。
- `FableSolExportAttemptPlan` 将 HDR 请求的尝试顺序固定为 HDR 120、HDR 60、SDR 120、
  SDR 60；设置页探测与正式导出复用 `candidatesForMode()`，不再发生界面与导出分叉。
- `FableSolAudioFilePlayer` 标记自然结束的线程；结束后 seek 会从原路径创建暂停的新线程并
  带入初始 seek，首个输出格式前先读取输入采样率建立进度基准。播放 Dialog 的
  `onPrepared` 不再把用户选定位置清零。
- 主播放/暂停按钮仍为 56dp touch ripple，padding 从 14dp 改为 12dp，可见图标由 28dp
  精确增加到 32dp。
- 新增 `FableSolExportAttemptPlanTest`、`FableSolPlaybackRestartPolicyTest` 并扩展
  `FableSolExportPipelineSourceTest`。完整 JVM 测试 298 项、0 失败、1 跳过；debug APK
  构建成功，未使用 adb。已发布阿里云 debug 更新 `202607261422`；本地与远端重新下载 APK
  的 SHA-256 均为
`ed068f54312b407855805fb919f260a41684d833d8e2bf3689fe2228228188ce`。目标三星设备上的
codec/EGL 结论与播放时序保留为真机验收项。

---

## 2026-07-26 第八轮：Material 图标、HDR 缓存与设置首帧减负

- `act_fablesol_export_video.xml` 改为 Google Material Symbols Outlined
  `video_frame_save`；在官方 path 后补顶部中央、左侧中央两段 80/960 viewport 宽的边框，
  播放三角、保存箭头和右侧 1dp 可见边缘补偿保持不变。
- `FableSolHdrExportCapability` 新增进程与 SharedPreferences 两级缓存。签名包含探测 contract、
  App 版本、Android API 和 `Build.FINGERPRINT`；成功结果长期有效，失败结果 24 小时过期。
- 设置页先尝试无 I/O 的进程缓存；未命中时延后 800ms，以
  `THREAD_PRIORITY_BACKGROUND` 读取持久化缓存或真实编码。探测固定使用默认 CBR，避免结论
  随用户 CQ 偏好漂移。
- 一帧探测仍覆盖实际 HDR codec、RGB10_A2 BT.2020/HLG EGL、AAC/MP4、EOS 与输出格式；
  移除与“编码能力”无关且最重的完整 `FableSolGlRenderer` shader/FBO 初始化。正式导出路径
  的 FP16 scene targets 验证未改。
- `settingsQualityRange()` 对 nullable 结果做进程缓存；不支持 CQ 的设备也不会重复枚举。
  HDR 不支持标签使用与顶部一致的 enabled 文本色 + `0.5` alpha，并追加相同本地化提示。
- 用户明确要求不跑测试，本轮未执行测试任务或 adb；发布任务构建成功。阿里云 debug 更新
  `202607261457`，本地、元数据与远端 APK SHA-256 均为
  `4b5c2f9cbd16c97e2d53911f2f34fb211140cf07651a963b478d8788101f20f5`。

---

## 2026-07-26 第九轮：22dp 导出图标与编码术语

- 录音 FAB / 附件播放导出按钮的容器仍为 56dp / 40dp，只把 padding 改为 17dp / 9dp，
  `video_frame_save` 可见尺寸统一为 22dp。
- `fablesol_param_export_bitrate_mode` 的展示语义由 Bitrate mode 改为 Encoding mode；
  `fablesol_export_estimate_quality` 明确加入 Video size。资源 key 保持不变以控制改动面，
  13 套语言展示文本同步更新。
- 未运行测试或 adb；发布任务构建成功。阿里云 debug 更新 `202607261510`，本地、元数据
  与远端 APK SHA-256 均为
  `499c59a302eba317885d288c68fa05b2d34462548fdeb59ffb0d0194bee5106e`。

---

## 2026-07-26/27 第十轮：PQ 通路、能力诊断与杜比视界检测（三次连续发布）

用户提出两台设备的疑问后要求 A/B/C 三项全做（见 D27）。

- `202607261547`——新增 `FableSolExportTransfer`（SDR / HLG / PQ）与
  `FableSolExportAttemptPlan.ordered(hdrTransfers, requestedFrameRate)`；
  `FableSolExportEgl` 支持 `EGL_EXT_gl_colorspace_bt2020_pq`；`export_present.frag` 增加
  PQ 分支与 `uSdrWhiteNits`；HDR10 写入 `KEY_HDR_STATIC_INFO`；设置新增「HDR 格式」。
- `202607261557`——诊断行永远显示“尚未探测”：文字在后台探测之前就已生成。改为
  `diagnostics()` 先调 `probe()`，并列出逐档失败原因。
- `202607261604`——仍然问不出原因：缓存条目只存布尔结论，命中缓存时 `probeInternal`
  不执行，`lastFailureReason` 恒为空，而否定结果 TTL 24 小时。改为诊断细节随
  `CachedResult` 一并持久化，`PROBE_CONTRACT_VERSION` 1→2 作废旧条目。SHA-256
  `fcbd2c2ca22d3d2f8faae609544889b15575522e0dab30adb8f2d3c59ae413d0`。

三份日志文件各写了多个 `## ` 小节，而发布任务只提取第一个，应用内更新说明因此被截断。

## 2026-07-27 第十一轮：色彩范围校验误杀整机 HDR

- 三星 S23 Ultra 的逐档失败原因全部是同一句
  `IllegalStateException: Encoder changed color-range from 2 to 1`——本项目自己的
  `preserveOrInstallColorKey` 抛的，与设备无关。按 D28 拆分权威归属，新增
  `FableSolExportColorRange.resolveForMuxer`：编码器报了就采纳，没报才补 limited。
- HDR 阶梯的档位名去掉写死的 `HLG`（改为 `HEVC Main10` / `AV1 Main10`），此前与独立的
  传递函数轴叠加后产生“HDR10 120fps HEVC Main10 HLG”这种自相矛盾的诊断行。
- 「DV 封装」在毫无 DV 编码器的三星上同样答“接受”，说明 `MediaMuxer.addTrack` 只认
  MIME、不构成证据；改为仅在存在 DV 编码器时显示，措辞改为“接受该轨类型”。
- `PROBE_CONTRACT_VERSION` 2→3 使被旧标准误判的机器立即重新探测。
- 新增 `FableSolExportColorRangeTest`（2 例）钉住回归。`:app:assembleDebug` 与
  `:app:testDebugUnitTest` 全绿（FableSolExport* 六个测试类共 25 例，0 失败）。未使用
  adb。阿里云 debug 更新 `202607261618`，APK SHA-256
  `2cefedc1b8c8b664d1d58f2a92b3abf3f82d4c9a6e10e5186526a5c5050d89a3`。

## 2026-07-27 第十二轮：四种 HDR 格式开放选择，杜比视界从"不做"翻案

两台机器都通了、默认都落 HDR10 之后，用户要求把 HDR 格式做成可选并在界面上说明区别，
且**必须实测能编才允许出现在界面上**；同时要求重新调研杜比视界与 HDR10+（见 D29）。

- 调研结论：Dolby 官方第三方样例 `DolbyLaboratories/dolby-vision-editor` 用标准
  `MediaCodec` + surface 输入编 profile 8.4，应用**不提供 RPU**——D27 里"没有公开接口所以
  做不了"的判断是没查证就下的，已在该条上标注推翻。反过来，HDR10+ 的动态元数据我们
  **确实提供不了**：`PARAMETER_KEY_HDR10_PLUS_INFO` 文档明确它不适用于 surface 输入模式。
- 新增 `FableSolExportHdrFormat`（HDR10 / HDR10+ / HLG / 杜比视界）与 `FableSolExportCodecEntry`；
  编码阶梯由格式自己持有，`FableSolExportTier` 增加 `hdrFormat` 字段，
  `candidatesForMode(format, …)` 取代 `candidatesForMode(transfer, …)`，
  `FableSolExportModeAttempt` 改为携带 `format`（null = SDR）。
- 杜比视界：MIME `video/dolby-vision` + `DolbyVisionProfileDvheSt` + HLG + BT.2020 +
  按像素率现算的 level（`dolbyVisionLevel`，阶梯照抄官方样例），且 level 在 64px 分享对齐
  **之后**才算。`FEATURE_HlgEditing` 的门从"transfer == HLG"收窄到"format == HLG"，
  否则 API 35+ 上 DV 会被这道门筛光。
- 静态母版元数据的条件从 `transfer == PQ` 改为 `hdrFormat.writesStaticMetadata`；
  HDR10+ 与杜比视界要求输出 profile 原样回报（`requiresExactProfile`），
  防止静默降级成 HDR10 却挂着别的名字。
- 能力探测改为**逐格式各走一遍真实编码 + 封装**，缓存改存通过的格式列表；每种失败格式
  只留第一条原因（同格式下各编码器报错通常一样，全列会把别的格式挤出可见范围）。
  `PROBE_CONTRACT_VERSION` 3→4。
- 删掉上一轮加的「DV 封装」探测：三星连 DV 编码器都没有却同样答"接受"，说明
  `MediaMuxer.addTrack` 只认 MIME，不构成证据；现在由真实编码探测取代。
- 设置页：`HDR 格式` 改为按实测结果动态生成的胶囊（贪心换行，320dp 里排不下一行），
  下方一段随选择变化的说明；选「自动」时直接写出本机会落到哪一种。存着的格式若本机
  编不出来，自动退回「自动」并同步改掉偏好。档位名带上格式，完成提示里也能看出用了哪种。
- 13 套语言新增 7 条文案。新增 `FableSolExportHdrFormatTest`（6 例）钉住自动档顺序、
  profile 严格性、DV 基层是 HLG、level 阶梯与竖幅小画布在 120fps 下必须解出非零 level。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607261657`，APK SHA-256
  `44826f5eeac6ce24d06bee1cb5a1639ef21c288a4d4ec4927e2e867008bb073c`。

## 2026-07-27 第十三轮：三台设备各一个"被自己人挡住"的问题（见 D30）

- **三星 HLG 缺失**：删掉 `FEATURE_HlgEditing` 那道筛（高通编码器一个都不广告它，API 35 上
  把 HLG 候选整批筛光）。`FableSolExportPipelineSourceTest` 相应改为
  `assertFalse(encoder.contains("isFeatureSupported("))`，钉住它不许回来；同时保留
  AV1-in-MP4 的 `SDK_INT < 34` 门。
- **华为平板整机 HDR 不可用**：`eglChooseConfig` 改为四级阶梯
  （`RGB10_A2+recordable` → `RGB10+recordable` → `RGB10_A2` → `RGB10`）。
  `FableSolExportEgl.Capability` 增 `tenBitWindowConfig`，诊断行新增「10-bit 表面」一项——
  广告了 PQ 扩展不等于建得起 10-bit 表面，此前两者被混为一谈。
- **HDR10+**：确认做不到，`Encoder changed profile 8192 to 2` 是编码器在说它只产 HDR10。
  动态元数据只能应用逐帧提供（surface 输入下被 Android 明确排除）或编码器自行生成
  （该机不做）。明确不伪造元数据。新增 `FableSolExportHdrFormat.downgradeHint`，把这类
  失败翻成人话而不是甩原始异常串；`formatFailures` 为空时的措辞也从"没有编码器广告支持
  这个 profile"改为"没有编码器通过候选筛选（profile / 尺寸 / 帧率）"，后者才是真的原因面。
- **HDR Vivid**：Android 官方支持格式页的 HDR 视频格式只有 HLG10 / HDR10 / HDR10+ /
  Dolby Vision 8.4 四种，通篇没有 HDR Vivid，无法做。我们已实现的四种正好就是这四种。
- **导出通知图标**：`FableSolVideoExportService` 的两处 `setSmallIcon` 用的是
  `act_create_white`——那是"新建"的加号，通知栏里显示出来就是个加号。改为
  `act_fablesol_export_video`。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿（309 例）。未使用 adb。
  阿里云 debug 更新 `202607262154`，APK SHA-256
  `dafd6f10d8de1ba8ab3255994e82927cf11a7deb7e14fa910e9edb48816f1f5f`。

## 2026-07-27 第十四轮：HDR10+ 字节缓冲通路的决定性探测（见 D31）

用户追问"surface 模式编不了 HDR10+，其它模式行不行"。查证结果：行——字节缓冲输入是
`PARAMETER_KEY_HDR10_PLUS_INFO` 唯一被允许的模式，也是 AOSP CTS `HDREncoderTestBase`
采用的模式。D30 里"HDR10+ 做不到"的范围写错了，只对当前 surface 链路成立。

- 新增 `FableSolHdr10PlusProbe`：与导出管线**完全隔离**（不建 EGL、不碰渲染器、不写文件），
  按 `COLOR_FormatYUVP010` 配置编码器、喂一帧平场 P010、读输出格式回报的 profile。
  分「裸通路」与「带元数据」两问，以区分通路不通与载荷写错。
- ST 2094-40 载荷按 `user_data_registered_itu_t_t35()` 逐位打包：单窗口、9 个百分位、
  `tone_mapping_flag = 0`，共 387 位 = 49 字节。`FableSolHdr10PlusPayloadTest`（2 例）
  钉住固定头 `B5 00 3C 00 01 04 01` 与总长。
- 诊断新增「HDR10+ 字节缓冲」一行，仅在 HDR10+ 当前不可用时才探（可用就没有这个问题）。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607262206`，APK SHA-256
  `6405abbd9721082296e589b1d79371bb688fd864037bec01646592c48995940e`。

## 2026-07-27 第十五轮：缓存签名、杜比视界 8.1、HDR10+ 判据（见 D32）

用户截图暴露了一个更根本的问题：**上一版看到的失败原因全是旧缓存**。

- `cacheSignature()` 改用 `R.string.debug_update_code`（发布任务生成的时间戳，每发一版必变）
  取代写死为 43 的 `BuildConfig.VERSION_CODE`；`peekCachedResult()` 相应改为需要 Context。
  源码契约测试新增 `assertTrue(capability.contains("R.string.debug_update_code"))`。
- `FableSolExportHdrFormat` 拆出 `DOLBY_VISION_81`（PQ 基层）与 `DOLBY_VISION_84`（HLG 基层），
  profile 常量相同、只差传递函数。`AUTO_ORDER` 按规格从高到低重排为
  8.1 → HDR10+ → HDR10 → 8.4 → HLG。`HdrFormatPreference` 新增项续在末尾以保持存储序号。
  新增 13 套语言的 8.1 说明文案。
- `FableSolHdr10PlusProbe` 的判据从"输出格式回报的 profile"改为**在输出字节里匹配 SEI 签名**
  `B5 00 3C 00 01`——HEVC 没有 HDR10+ 这个 profile，回报 2 只是在陈述码流真实 profile。
- `FableSolExportHdrFormatTest` 增至 8 例（自动档顺序、8.1/8.4 只差传递函数）。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿（312 例）。未使用 adb。阿里云 debug
  更新 `202607262220`，APK SHA-256
  `de094905d0bd9e907dadbf786164713dd3e1905c4d99b38214b8938b4bf44c10`。

## 2026-07-27 第十六轮：HDR10+ 字节缓冲通路落地；杜比视界补 profile 5（见 D33）

两台设备的实测把两个问题都定死了：三星「带元数据 码流带 HDR10+ SEI」= 通路可行；
OPPO 的 8.1 失败于 `Encoder changed color-transfer from 6 to 7`（PQ→HLG）= 该编码器只出 8.4。

- 新增 `FableSolExportP010Bridge` + `p010_luma.frag` / `p010_chroma.frag` / `p010_stats.frag`：
  离屏 RGB10_A2 呈现 → 两趟转出 P010 双平面 → 一趟归约成 32×32 亮度统计。输出目标一律
  RGBA8（ES 3.0 只保证这一组 glReadPixels 组合），一个 texel 装两个 16 位样本。
- 新增 `FableSolExportHdr10PlusMetadata`：ST 2094-40 载荷构造 + 从归约结果测统计量 +
  `containsSei()` 码流签名匹配。`FableSolHdr10PlusPayloadTest` 迁移到它上面。
- `FableSolExportEncoder` 增字节缓冲输入：`COLOR_FormatYUVP010`、`queueVideoFrame()`
  （`setParameters` 必须先于 `queueInputBuffer`）、`queueVideoEndOfStream()`、
  `hdr10PlusSeiSeen`（写样本时扫描确认）。`FableSolExportEgl` 增离屏 pbuffer 模式。
- `requiresExactProfile` 去掉 HDR10+，改以 SEI 为判据；探测与正式导出都按此。
- 新增 `DOLBY_VISION_5`（单层 PQ + IPT-PQ-c2，不向下兼容，界面说明写明取舍）。
  `AUTO_ORDER` 变为 5 → 8.1 → HDR10+ → HDR10 → 8.4 → HLG。13 套语言新增说明文案。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿（313 例）。未使用 adb。阿里云 debug
  更新 `202607262244`，APK SHA-256
  `459c5784361c7911da5fea2c77ec358afc997272cf75deadd52b16c9852b649f`。

## 2026-07-27 第十七轮：HDR10+ 仍显示为 HDR10——D33 只修了一半（见 D34）

用户反馈设置里的胶囊与自动档文本仍是 HDR10。根因是 `acceptsTenBitProfile` 的白名单没有
同步加上 `HEVCProfileMain10`：HDR10+ 申请 8192、编码器回报 2，`requiresExactProfile` 已放行
但白名单不认 2，照样判失败，于是永远进不了「实测通过」列表。

- 白名单加入 `HEVCProfileMain10` / `AV1ProfileMain10`；不影响 HDR10 / HLG（它们走相等判断）。
- `FableSolExportP010Bridge.writeInto` 返回规范帧长 `stride × sliceHeight × 3 / 2`。
- HDR10+ 的 codec entry 去掉重复格式词（曾出现「HDR10+ HEVC Main10 HDR10+」）。
- `FableSolExportHdrFormatTest` 增至 11 例，新增 profile 接受性两例。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270101`，APK SHA-256
  `41bd4ba0a8fa3801cd42779f8dfb470b4147380be5c9fd1292dd36932f1f1478`。

## 2026-07-27 第十八轮：OPPO 的 HDR10+ 与杜比视界 8.1 的定论（见 D35）

用户的 OPPO 截图来自 D34 修复**之前**的版本，其 HDR10+ 失败正是那个 bug；但同截图的独立
探测行 `带元数据 码流带 HDR10+ SEI` 不经过该校验，证明**两台设备都能编 HDR10+**。

- 杜比视界 8.1 定论为做不到：设备只广告 profile 8、明确把 PQ 改回 HLG，且 Dolby 官方样例
  发行说明只声称支持编码到 8.4。**不放松传递函数校验**——传递函数是我们画出来的像素的属性，
  放行会产出标着 HLG 而内容是 PQ 的文件。
- 新增 `vendorParameters()`：用 API 31 的 `getSupportedVendorParameters()` 直接向编码器查询
  私有参数（Qualcomm 文档站 JS 渲染抓不到，猜键名不可靠），按 dv/dolby/hdr/profile/color/
  transfer 过滤后进诊断行，作为 8.1 是否还有指望的唯一线索。
- `changed color-transfer` 类失败改为人话措辞，新增 `TRANSFER_DOWNGRADE_MARKER`。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270113`，APK SHA-256
  `d49d97c463dfb7d8429407809ab8691feb7199ca2212b3c6acb932349348cc2b`。

## 2026-07-27 第十九轮：HDR10+ 元数据性质与格式取舍（见 D36）

用户发现官方支持格式页把 HDR10+ 的元数据栏填成「静态」，追问到底是静态还是动态，以及对
本功能 HDR10+ 与杜比视界 8.4 哪个更好。

- 结论：**HDR10+ 是动态的**（ST 2094-40），官方那一格不可信；准确说法是静态母版 + 逐场景
  动态两份都有，我们的实现也是两份都写。
- 结论：**对本功能 HDR10+ 更好**，决定性因素是基层曲线（PQ 满余量 vs HLG 约 3.77 倍），
  而 FableSol 的高光正长在那一段。`AUTO_ORDER` 已是该顺序，无需改动。
- 修正 13 套语言里 HDR10+ 的说明：旧文案「动态元数据只能由设备自行生成，我们既设不了也
  验不了」在 D33 之后已与实现相反，改为「由我们逐帧从画面实测得出」。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270120`，APK SHA-256
  `895f3b6b031a7d85ad0aff401715a38cf2f0c4303b684e96c0d45ea7ff9a3a1d`。

## 2026-07-27 第二十轮：HDR 开关并入格式选择；产物带格式与真实码率（见 D37）

用户实测观察到杜比视界 8.4 有"高光出现时压暗背景"的动态适配，而 HDR10+ 没有——根因是我们
发的 ST 2094-40 里 `tone_mapping_flag = 0`。补曲线列为待办（可从已有实测统计诚实推出）。

- 删除单独的"导出 HDR 视频"开关（连带 `makeExportSwitchRow` / `ExportSwitchControl` /
  `probeHdrExportCapability` 等 106 行），并入「导出 HDR 视频格式」胶囊，首项为「关闭」。
- 指示性文字末尾追加最终格式；编码器诊断清单移到该行之后。
- `FableSolExportSink.displayName` 改为推导属性 + `tagFormat()`，文件名带格式后缀。
- `Result.Success` / `State.Done` 增 `formatLabel` 与 `frames`；新增 `State.Done.bitrateBps`
  （文件大小 ÷ 时长，CQ 档同样算得出）与共用的 `FableSolExportBitrateText`。
- 完成对话框与通知文案改为 5 参（格式 / fps / 体积 / 码率 / 位置），13 套语言同步；
  新增「关闭」选项与其说明文案。
- 源码契约测试改为钉住"只有一处 HDR 入口"。`:app:assembleDebug` 与 `:app:testDebugUnitTest`
  全绿（315 例）。未使用 adb。阿里云 debug 更新 `202607270150`，APK SHA-256
  `0baa96ccfbd6c9059b0daec6a7a01a77a9c9fcdb805c1c3774fcac66436eb4f5`。

## 2026-07-27 第二十一轮：HDR10+ 的色调映射曲线（见 D38）

用户授权做 D37 里列出的曲线。

- 新增 `FableSolExportHdr10PlusCurve`：膝点取该帧实测第 90 百分位，第一个锚点由斜率连续
  解出，其余二次缓入到 1；膝点做**快起慢落**的指数平滑（τ 0.08s / 0.80s），平滑的是意图，
  统计量仍是逐帧实测原值。目标峰值取 1000 尼特（取母版峰值等于说"不用压"，曲线就没信息量）。
- `FableSolExportHdr10PlusMetadata.payload` 改签名收曲线，写 `tone_mapping_flag = 1` 与
  膝点（/4095）、锚点个数、锚点（/1023）；载荷 49 → 64 字节。
- `FableSolHdr10PlusProbe` 自带的 ST 2094-40 写入器删除，统一用正式通路那一份。
- 新增 `FableSolExportHdr10PlusCurveTest`（4 例）钉住单调性、端点、膝点斜率连续、快起慢落；
  载荷测试增加"不带曲线仍为 49 字节"一例。13 套语言的 HDR10+ 说明同步更新。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270203`，APK SHA-256
  `6848727c15d6e6d771c370655abdebdec34f81cc2ab3b242ac0a4a2b85550012`。

## 2026-07-27 第二十二轮：漫反射白锚点改为可调（见 D39）

用户澄清观察后定位到真因：不是曲线，是 PQ 的漫反射白钉在 203 尼特——比手机自己的 SDR 白还暗，
而且低到屏幕从不需要在背景与高光间取舍，所以画面才是静的。

- `FableSolExportOptions.pqWhiteNits`（200–800 尼特，每档 25，默认 400）+
  `FableSolTuning.exportPqWhiteNits` 读写与恢复默认。
- `FableSolExportPresenter` 增 `whiteNits` 构造参数驱动 `uSdrWhiteNits`；
  `FableSolVideoExporter` 按 `tier.transfer == PQ` 选锚点，并用它算 `peakNits` 与
  HDR10+ 曲线的母版峰值——三处必须同源。
- 设置新增滑杆，**仅在选中 PQ 系格式（HDR10 / HDR10+ / DV 8.1）时显示**；
  `addHdrFormatBlock` 的回调改为携带解析出的格式。
- 13 套语言新增标签。`:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。
  阿里云 debug 更新 `202607270224`，APK SHA-256
  `4b3695a1fee2487486ff9458bed113b8e23910f577119bcfa44827bf38f8bc2a`。

## 2026-07-27 第二十三轮：修实时渲染的 ADPF 竞态崩溃；漫反射白默认值由屏幕推出（见 D40）

- **崩溃**（OPPO PMA110，`ArrayIndexOutOfBoundsException: length=0; index=0`）：
  `FableSolRowParallel.workerThreadIds()` 用的 `toIntArray()` 是"先读 size 再迭代"的两步操作，
  而列表正被 worker 并发写入。改为 `ArrayList(collection)` 原子快照后再转数组。
  **与本功能无关**，是实时渲染路径上一直存在的竞态。新增
  `FableSolRowParallelSnapshotTest` 用同样的并发形态钉住。
- **漫反射白自动默认值**：新增 `FableSolExportDisplayLuminance`，读
  `Display.HdrCapabilities.getDesiredMaxLuminance()`（自夹 300–10000 合理区间），
  默认漫反射白 = 峰值 ÷ 4，夹到 200–800；`FableSolTuning.exportPqWhiteNits` 在偏好未设置时
  返回该值。诊断新增「屏幕 HDR 峰值 · 自动漫反射白」一行。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270232`，APK SHA-256
  `4a84963ee294e98db0f1c88d348ff6f5d9176a48f122dc1426538ba65def7e94`。

## 2026-07-27 第二十四轮：高白点下曲线退化导致偏色（见 D41）

用户把漫反射白拉到 800 后，HDR10+ 产物在星芒出现时背景间歇性发青蓝；400 时不出现。

- 根因：肩部第一个控制点 `P[1] = (M − k)/(N(T − k))` 在 T 写死 1000、M = 7680 时远大于 1，
  被夹到 1 后所有控制点全为 1，肩部退化成断崖；三通道先后撞顶即偏色。
- `FableSolExportHdr10PlusCurve` 增 `targetNits` 构造参数与 `targetNitsFor()`：取屏幕声明峰值，
  下限 ≥ 漫反射白 × 2、上限 ≤ 母版峰值。
- 膝点上限追加 `(N·T − M)/(N − 1)`，由 `P[1] ≤ 1` 直接解出。
- `FableSolExportHdr10PlusCurveTest` 增两例：全滑杆 × 四档强度下肩部不得退化；
  目标峰值必须落在两倍漫反射白与母版峰值之间。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270248`，APK SHA-256
  `5ebbcaf901f5262c24de2673b0bc6e8d6b6d88e114b3a1a76f7d0a24ef109ec4`。

## 2026-07-27 第二十五轮：外部评审九条，八条修复（见 D42）

- `targeted_system_display_maximum_luminance` 改为 ×10000（单位 0.0001 尼特；27 位宽度即证据），
  新增 `FableSolHdr10PlusPayloadDecodeTest` 逐字段解码核对。
- 正式导出 commit 前加 `check(!byteBuffer || encoder.hdr10PlusSeiSeen)`。
- 字节缓冲模式强制 `COLOR_RANGE_LIMITED`（谁做的转换谁是权威；D28 只适用于 surface 模式）。
- `KEY_STRIDE` / `KEY_SLICE_HEIGHT` 回报 0 一律视为未知并退回画面尺寸。
- 新增 `requiresEglColorSpace`，HDR10+ 不再被 `anyHdrColorSpace` / 传递函数列表 / 10-bit
  pbuffer config 三处门禁拦截；离屏 EGL 不再要求 10-bit config。
- `probeInternal` 每轮开头重置 `lastSupportedFormats` / `lastCandidateFailures` /
  `lastFailureReason`。
- 恢复设置页 800ms 延后探测（D24），并随 Dialog 失效取消。
- `hdr10StaticInfo` 增 `frameAverageNits`，MaxFALL 跟随漫反射白而非写死 203。
- 分位点口径问题属实但需区分用途（定膝点 vs 写进元数据），列入遗留项未改。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270327`，APK SHA-256
  `c6da788dbeb55dd9a5b7234ee43abb9106b8eb537a2addfdf0984a56db1b2c08`。

## 2026-07-27 第二十六轮：进度条渐变、峰值可见、「高光起点」可调（见 D43）

- 新增 `DisplayUtil.setProgressBarBackground()`，导出进度条改用与调参滑杆同源的
  `SeekBarTrackDrawable` 渐变轨道，替换掉只取起点单色的 `progressTintList`。
- 指示行追加**峰值**（= 漫反射白 × HDR 强度）：掉饱和的根因是这个乘积超出屏幕能力，
  而两根滑杆各调各的，乘积不写出来用户看不见。
- 「膝点」改名「高光起点」并开放为参数（50–99%，默认 90），**仅在 HDR10+ 下显示**；
  `FableSolHdr10PlusStats.nitsAtPercent()` 在 9 个标准分位点之间线性插值。
- 13 套语言新增 3 条文案。`FableSolExportHdr10PlusCurveTest` 增一例覆盖插值与
  "起点调高则膝点上移"。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿（325 例）。未使用 adb。阿里云 debug
  更新 `202607270344`，APK SHA-256
  `e62440dcec364a7743667e80db136d9531e1e903e02361c0af828786d9273201`。

## 2026-07-27 第二十七轮：完成对话框与通知补上色彩规格

- 新增 `FableSolExportSpecText`：完成对话框与通知**共用同一处生成逻辑**，两边不会漂移成
  两个说法。
- `Result.Success` 与 `State.Done` 增 `pqWhiteNits` / `peakNits` / `highlightStartPercent`，
  由导出器按档位填——**只在真正生效时才带出去**：HLG 系没有绝对锚点，非 HDR10+ 没有曲线。
- 完成文案增第 6 个参数（色彩规格行，不适用时为空串），13 套语言同步；新增
  `fablesol_export_detail_hdr` / `fablesol_export_detail_highlight`。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270437`，APK SHA-256
  `b4f4c55d15890101f45d77f53a07fad902ca5fadc9b54447fc0f7e8ed1279d0f`。

## 2026-07-27 第二十八轮：重写设置中的 HDR 文案（13 套语言）

用户要求去掉口语与不专业表述（第一人称"我们"、破折号、"不用压""代价是"等）。重写 10 条
文案时连带发现并修正两处过期内容与一处真实缺陷：

- **过期**：HDR10 说明写死"203 尼特"，而漫反射白已是可调参数；HDR10+ 说明仍称「膝点」，
  该术语已改名「高光起点」。
- **缺陷**：Profile 5 说明里有 Markdown 粗体 `**不向下兼容**`——Android 字符串资源不解析
  Markdown，星号会原样显示给用户。
- 13 套语言全部重写；法语、意大利语撇号按资源规则转义；简繁分别用各自惯用术语。
- 顺带发现三条**死字符串**（`fablesol_export_started` 仍写着已废弃的"水体视频"、
  `fablesol_export_estimate`、`fablesol_param_export_hdr`），本轮未动，仅记录。
- `:app:assembleDebug` 与 `:app:testDebugUnitTest` 全绿。未使用 adb。阿里云 debug 更新
  `202607270448`，APK SHA-256
  `81e4558f66e6ab68e87edd31b6739a412f5af5aef6fd7c48d8f78840cbfc7aff`。

## 2026-07-27 第二十九轮：自动漫反射白纳入屏幕平均亮度与 HDR 强度（见 D45）

上一轮对 HDR10+ 200/500/800 尼特样片的逐像素分析确认：800 尼特产物的基础像素颜色比例
基本正确，偏白、偏浅主要来自高绝对白锚、高 APL 与 7680 尼特母版峰值共同触发显示端映射。
因此把旧的 `屏幕峰值÷4` 自动值改为设备与强度共同约束的保真默认：

```text
raw = min（所有可用约束：面板峰值×1.75÷HDR 强度，最大帧平均亮度，400）
autoWhite = floor(clamp(raw, 200, 400)÷25)×25
```

- `FableSolExportDisplayLuminance` 新增纯计算推荐结果，同时读取并校验 Android
  `desiredMaxLuminance` / `desiredMaxAverageLuminance`。缺一项只忽略该约束，两项都缺失才
  回退 400 尼特。
- `FableSolTuning` 保留“未存键即自动、存键即手动”的状态语义；手动范围在写入时再次夹到
  200～800 尼特。
- 设置 Dialog 的 HDR 强度拖动会即时重算自动白锚、更新白锚滑杆与
  `峰值=漫反射白×HDR强度`；手动档不联动。PQ 推导文字新增完整公式，手动档新增状态说明，
  共同步 13 套语言。
- 诊断文字新增屏幕最大帧平均亮度、当前 HDR 强度、自动结果及安全回退标记。
- 新增 `FableSolExportDisplayLuminanceTest` 7 个数值边界测试，并为设置联动补源码契约。
  定向测试与 `:app:assembleDebug` 通过；未使用 adb。阿里云 debug 更新 `202607270804`，
  APK SHA-256
  `ff8802fef0a1a07089ffe98479e130ae7102e4330c19d7b6ae5621a978eed06b`。

---

## 待办（首轮未做）

1. **D15 ① 的逐位门禁尚未建立。** 需要一个 JVM 单测：同一批 `FableSolFeatureFrame` +
   等间隔 1/120 合成时间戳，比对实时驱动与离线驱动逐帧的状态向量，要求 max|Δ| = 0。
   这是合入门禁，但它本身是一批独立工作。
2. **D15 ②③ 真机验收未做**（事件序列比对、观感并排比对）。
3. **码率与 CQ 默认值未标定**，当前用的是 decisions.md 里的推测值（120fps 24 Mbps）。
4. **`FableSolRealtimeAnalyzer` 预热门是否纯采样驱动尚未确认**（D13 遗留项）。
5. **HLG 上限的取舍需要真机复核**：HLG 在 SDR 参考白之上只有约 3.77 倍余量，而用户强度
   上限是 9.6。当前用线性域软肩（knee = 2.0，之上指数渐近）承接超出部分，而不是硬钳。
   这是实现时才浮现的约束，decisions.md 未记；观感需真机确认，若不可接受则改走 PQ。
