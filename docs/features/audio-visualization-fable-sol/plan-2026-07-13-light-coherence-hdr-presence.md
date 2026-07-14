# 计划 · 光照相干化与 HDR 存在感（2026-07-13 grill 方向）

本文件是 2026-07-13 一次 `/grill-with-docs` 交流收敛出的**工作方向**，主题是让水体
更晶莹剔透 / 有流动感 / 更美 / 更真实，并让 HDR 更有存在感（当前只有镜面闪点的 HDR 易见）。

> 说明：用户明确表示既有 D 决策不一定是最终结论、可以被重新审视。以下条目是**待真机
> 验收的方向**，不等同已刻定的 D 号；真机对照后再回填 decisions.md。

## 用户目标

- 水体对比不够、需要更晶莹剔透（已确认 = 底子对比 + 高光两者都要，重心在光）。
- 高光反射不够亮、HDR 只有闪点看得见。
- 有流动感、更美、更真实。

## 根因诊断（来自代码核对）

1. **水面本身没进 HDR 预算**：`water.frag` 用 `clamp(color+dither,0,1)` 把水面颜色（含
   Fresnel 天空反射、纵向受光、SSS）硬压在 SDR。真正能让远处水面"像镜子发亮"的部分根本
   超不过 reference white，只有独立 optical pass 能超白。
2. **只有三类光学实体能超白**，且中间两档天生看不见：闪点 mode3（2.0×、近中性白，唯一显眼）；
   反射带 mode4（仅 1.4×，被 `facing*(0.65+0.35crest)`+`smoothstep(0.28,0.82)` 双重收窄）；
   透射 mode8（仅 1.2×）。后两者还要再除低 alpha、乘 `coverage^1.35`、被 D74 景深阶梯在
   5~6 层后清零。
3. **打光法线挂错几何**：渲染高度是 `worldEta`（各层轮廓 + 二维方向场），但打光法线 `aSlope`
   只来自 `eta`（纯二维方向场，不含各层波形）。→ 水面按"仿佛只有缓慢翻滚"打光，眼睛看到的
   主波形没参与光照。光学高光又是第三套逐层基准。**"光"活在两套错位几何上，都不是真渲染面。**
4. **画布对比低**：`body_light=0.36` 是九层平铺中间调抬升（对比负项，且开关无感=感知惰性）；
   `depth_scattering=0.21` 的几何体积（对比正项）太弱。
5. **系统性泛白**：一堆宽而弱的半透明光层互相抵消，单个都看不见（当初"雾"的成因）。

## 采纳的原则

- **定位 A**：物理纪律 / 安静兼容。不做音频耦合 HDR（保留 D67）、不做泡沫、光线角度固定
  （时间驱动太阳/月亮延后）。
- **存在感靠面积，不靠峰值**（BT.2408）：大面积要柔（≤~1.3~1.5×），小点可以猛（闪点 2.0×）。
- **少数几个敢下手的效果 > 一堆互相抵消的弱效果。**
- **把光统一到真连续水面**：反射（映天空）+ 透射（透水体）都由水面自己在 HDR 里做；
  optical pass 只留真正离散的实体（闪点、流光）。
- **统一太阳模型**：一份 scene-linear "带太阳的天空"；掠射光泽（低频）+ 闪点 + sun-glitter
  （高频）是同一份"反射到的太阳辐射"的两面。太阳颜色保持近中性以尊重 D69/D77 身份纪律。
- **SDR 输出逐字节不变**，HDR 只加受控超白（沿用 D56 模型）。

## 分步计划（每步独立 Debug + 真机验收，保可归因）

- **Step A — 光照法线统一到 `worldEta`**：法线改从真渲染面求；各层轮廓分段线性会在层锚点
  产生坡度跳变，需顺带把跨层混合改平滑（原 Canvas 用 Catmull-Rom）以防接缝。body 打光先迁到
  新法线，单独验收"光终于贴着波走"。逐层离散闪点暂不动。
- **Step B — 画布对比（SDR）**：`body_light 0.36→0`；`depth_scattering 0.21→` 一次给到能看见
  （可能 0.5+，或把 deep 色本身压更深）；`back_shade` 先不动（D78 精神）。验收对比/剔透，
  不抬 APL（D73）。deep 用 OKLab 保身份色，波谷不发灰。
- **Step C — HDR 掠射 Fresnel 光泽**：引入 scene-linear "带太阳的天空"（基线≈1.0 + 朝太阳柔亮瓣
  + 地平线抬亮）；水面 Fresnel 反射它、在 HDR 路径越过 1.0；大面积柔，峰值 ~1.2~1.4×。
- **Step D — HDR 背光透射亮边**：对称做法，水面 SSS 在 HDR 路径越过 1.0；保身份色；小面积可更亮
  ~1.2~1.5×；弱化 mode8 独立透射带。
- **Step E — sun-glitter 组织**：闪点出生密度朝太阳路径偏置、**跨整个连续面组织**（不逐层拼），
  沿深度轴轻微拉长；复用 D70 闪点生命周期；不接音频。
- **流动感 / 凝胶感（独立"运动线程"，排在光之后）**：用户报"期望"，但确有轻微凝胶感，声音驱动时
  也在，且调小薄峰透光后仍残留。诊断（按可能性）：① **几何只在长波起伏**——ambient 波长 420~58dp
  （最长比水塘还宽，最短 58dp 振幅才 0.42dp），Gerstner 陡度低而圆润，**没有快 / 细 / 去相关的
  小尺度运动进入形状**→整片像低频圆润相关晃动的弹性体（凝胶知觉核心）；② **细节运动读不出**——
  唯一细尺度只在微法线（0.11 慢速）且挂错位法线；③ **宽软 SSS = 蜡 / 翡翠半透明固体**标志，加重胶感
  （调小有用非根）。**修正：光不会修好凝胶感**（运动相关，光只让它更清楚）；但 Step A 相干法线后把
  沿流向微法线调快调活，可让细快运动经流动高光"读出来"（补第②条）。修复顺序（便宜→贵）：
  ① 相干法线后调活微法线；② 往场里加**细 / 去相关 / 保均值**小涟漪 + 审计相位去相关（不违反无尖峰 /
  无液位抖动）；③ 最后才 Ma Yuan 线条（研究 TOP10 第 10 项）。改的是 simulation 运动、非 shader，
  单独一步、单独验收。

## 延后（B 档 / 以后）

音频耦合 HDR、常亮 glitter 光柱、时间驱动太阳/月亮 + 色温 + 夜空、折射（D61）、泡沫。

## 验证要点

- Step A 的跨层接缝；真机 HDR 透传；false-color + APL/峰值指标；混合 UI 变暗观察（D68 先不预压）。

## 实现进度

- **Step D 已实现（2026-07-14，待真机验收）**：真实水面复用既有朝阳 SSS 掩码，仅在 scene-linear HDR 分支加入 `(1-Fresnel)` 约束的身份色透射差量；近层峰值 `1.45×`，到中远层归回 `1.0`，受实时 headroom 与统一 HDR gain 封顶。SDR 路径不变，独立 mode 8 收为 `1.08/1.06/1.04/1.02/1.0…` 的弱肩部。数值回归确认启用 `1.45` 会产生正向 HDR 差量，而 `1.0/1.45` 两档的 SDR 输出逐字节一致。

- **Step E 已实现（2026-07-14，待真机验收）**：Android GLES 将各层未匹配闪点锚点先汇入同一个候选池，再由一个总出生额度跨连续面选择；出生分数按固定太阳方位的连续路径加权，近宽远窄、路径外保留 `0.12` 概率底。闪点沿相邻深度行方向轻微展开（近 `2.6dp`、远 `1.3dp`），但数量上限、亮度、D70 生命周期与音频映射不变。Python ModernGL/QPainter 已同构同步，画布仍为 `320dp/640px`。

- **Step A 已实现（2026-07-13，待真机验收）**：全部落在 `FableSolContinuousSurface.kt`：
  ① `composeLayerField` 跨层由线性改 **Catmull-Rom**（锚点行 q=0/1 仍精确穿过各层轮廓，
  `anchorRowsPreserveEveryLayersOwnContour` 等测试全绿）；② `sample()` **先合成 `worldEta` 再从它
  求 `slopeX/slopeZ`**（原来只从二维方向场 `eta` 求），使打光法线来自真渲染面；③ 行间 Catmull-Rom
  权重按固定 `z01[r]` 在 `init` 预计算，稳态零分配。shader、renderer、optical 均未改。
  `:app:assembleDebug` 通过、fablesol 单测 12/12 绿。真机看点：光是否终于贴着看得见的波峰走、
  层锚点处（每 3 行）有无横向接缝、倾斜/发热正常。

  **真机反馈（Debug 202607131120）**：光确实贴着波走了、"更真实"，但**暴露出脏**——一条灰银/灰青带
  + 偏灰黑。诊断：Step A 的真法线放大了 `water.vert` 里 `relativeLongitudinalLight` 的掠射 Fresnel
  天空反射，而它反射的是近白的 `uHorizonColor`、且 SDR 钳位，于是身份色被糊成中性灰 wash（挨着品红
  读成灰青，同时对比）。**这条脏带正是 Step C 要提进 HDR、变成亮银光泽的那块反射——脏是因为它还没变亮。**

- **Step B 已实现（2026-07-13，待真机验收）**：目标"止脏 + 拉对比"，原则=打光始终待在身份色轴上。
  ① `water.vert relativeLongitudinalLight`：掠射天空反射项乘 `skyReflect=0.2` 压弱（灭灰青 wash），
  保留全强度 N·L 漫反射（"光贴着波走"不丢）与 `0.14*sqrt(darkness)` 保色暗化；亮银光泽留给 Step C 的
  HDR。② `body_light_strength 0.36→0`（平铺中间调抬升、真机开关无感，砍）。③ `depth_scattering_strength
  0.21→0.45`（几何 deep/subsurface 顶替体光，波谷压深、浪峰提亮=对比与保色阴影主来源）。
  同步更新 3 个写死默认值/验证体光的测试。`:app:assembleDebug` 通过、全量单测 106/0/0。真机看点：
  灰青带是否消失、波谷是否更深/浪峰更亮（对比↑、更晶莹）、身份色是否更干净、APL 自然略降是否可接受。

  **真机反馈（Debug 202607131143）**：灰青 wash 好转，但**远处/中远波背的灰黑更严重**。根因确认：
  deep/subsurface 是从**已被 `lighten_far` 混白的层色**派生的（`renderer` 先 `mixOklab(base,WHITE,lighten)`
  再 `derive`），所以**乳白远层被加深只会变成灰**（白+黑=灰）。Step A 给远层真法线（有了明暗）、
  Step B 又把 `depth_scattering` 提到 0.45，远处灰黑因此更重。

- **Step B 补丁（2026-07-13，待真机验收）**：`water.vert` 新增 `nearShadingWeight(depth01) =
  mix(1.0, 0.15, smoothstep(0.15,0.70,depth01))`，把 `depthScattering` 强度与 `relativeLongitudinalLight`
  的整体偏离量都乘上它——**打光/加深随水层混白而衰减**：饱和的近层保留全额对比与"光贴波走"，
  乳白的远层几乎不再被加深，交给水层景深阶梯保持干净柔和。parity 关键串（`linearBase + fullLight -
  referenceLight`、`0.14*sqrt(darkness)*depthScale`）保留，106/0/0。真机看点：远处灰黑是否消失、
  近层对比是否保住、中层过渡是否自然。

- **Step C 已实现（2026-07-13，待真机验收）**：水面首次进 HDR——`water.frag` 新增 `grazingSheenExcess()`
  与 `uHdrGain`/`uHdrHeadroom` uniform。掠射 Fresnel（`f0=0.020373`、`pow(1-NdV,5)`，与 Crest/既有一致）
  × 朝太阳反射瓣（`reflect(-viewDir,normal)·lightDir`，近瓣紧远瓣宽 `mix(6,2,depth01)`=空气透视）×
  深度衰减峰值（`mix(1.40,1.0,smoothstep(0,0.62,depth01))`，对齐 litCrestPeaks/D74，服从 `uHdrHeadroom`）。
  只在 `uSceneLinear && vFrontFill==0 && uHdrGain>0 && uHdrHeadroom>1` 时叠加**超白差量** `max(peak-1,0)`，
  SDR 分支逐字节不变。颜色近中性白 + 一丝身份色（`mix(vec3(1),vColor/maxC,0.14)`，D69）。renderer 把
  `hdrGain`/`hdrHeadroom` 也喂给水面。新增守卫测试；107/0/0。**v1 只做宏观法线的大面积柔光泽**，
  未接微法线/闪点瓣（留给借鉴点 2 与 Step E）。真机看点：受光陡波面是否出现发亮的银泽、是否只在录音态、
  远层是否仍克制、暗色 Thing 上是否也能看到、太阳侧是否正确（若反了是 `reflect` 符号问题，易调）。

## Crest 调研结论（2026-07-13）

抓取 `OceanEmission.hlsl` / `OceanReflection.hlsl` / `OceanNormalMapping.hlsl` / `Ocean.shader` 并逐项
对照。**结论：完全印证当前方向，无冲突。** Crest 的仿真/纹理机制（FFT、cubemap/planar 纹理、mips、
泡沫、屏幕空间折射）我们已排除且用不了；真正可借的是它的**着色模型**。

- **统领印证**：Crest 把太阳镜面并进天空辐射，再用**同一个 Fresnel `lerp(body, sky, R)`** 一次分配能量。
  这正是我们统一太阳模型 + Step C。掠射行 `R` 大→偏 sky（银泽/超白），正视行 `R≈f0≈0.02`→留体色。
- **Step C（反射进 HDR）公式**：我们的 `f0=0.020373`、`pow(1-NdV,5)` 已等于 Crest 的 Schlick R₀/power，
  不用改。把 cubemap 换成解析 `skyRadiance(refl,sun)`：`refl.y=max(refl.y,0)` 地平线钳制 + 身份天空三色 +
  太阳项 `0.35*pow(c,8)`(宽柔光泽瓣) + `6.0*pow(c,220)`(窄亮闪点)。HDR 只加超白差量
  `outLinear += R * max(skyRadiance-1,0) * softGain`，`softGain` 绑 `litCrestPeaks`；SDR 分支不进。
  "面柔/点亮"由**瓣宽**自动分配，不靠音频、不靠乘增益。
- **Step D（透射进 HDR）**：我们的 `addSunriseSubsurface` 与 Crest `ScatterColour` 的 SSS 瓣逐项对应
  （`towardsSun`、falloff、base+sun、crest 厚度、身份派生色）——已对齐。只需把它放行到 HDR 超白
  `outLinear += sss * backlit * (transmissionPeak-1)`，因 `sss` 是身份派生色，只越亮度不变色相→天然保身份。
  附：Crest 深水偏冷来自 `_DepthFogDensity` 红通道吸收最快——为"最深路径 ≤2° 冷移"提供物理背书。

**C/D/E 之外值得纳入的 3 个正交小借鉴（成本各一两行、风险低）**：
1. **闪点/光泽瓣锐度随深度轴变**（Crest `_DirectionalLightVaryRoughness`）：`fallOff = mix(220.,60.,depth01)`
   → 近清晰碎钻、远宽柔银泽，一行 `mix` 给出空气透视，与 `skyRadiance` 共用 `sunCos`，零额外成本。★最高性价比
2. **微法线 RMS → 反射瓣宽（解析粗糙化）**：用已算的 `microSlope` 长度做 `glossPow=mix(8,3,rough)`，
   让陡面/远行反射更模糊，防 Step A 真法线后 HDR 银泽碎成 aliasing 闪烁——是 Step C 的时域稳定伴侣。
3. **C 与 D 共享一份 Fresnel 预算**（Crest `ApplyReflectionUnderwater` 能量守恒）：反射超白乘 `R`、
   透射超白乘 `(1-R)` → 掠射自动偏银(反射)、正视自动偏身份(透射)，"角度一变材质就切换"的高级感，
   且 APL 不失控。把 C/D 焊成一个统一模型，属架构级而非第四个效果。

## 待办

- **Python 模拟器同构（D43）**：`audioVisualizerSimulatorFable` 的连续面 moderngl 后端也应把打光坡度
  从 `eta` 改到 `worldEta` + Catmull-Rom，保持双端视觉一致。不阻塞 Android debug。
