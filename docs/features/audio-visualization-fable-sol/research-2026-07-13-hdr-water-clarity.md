# FableSol HDR 与水体通透感调研（2026-07-13）

## 结论摘要

用户感到当前水体、波浪和光线“偏冷、雾多、过于柔和”，有直接的实现依据，不是单纯由屏幕亮度不足造成：

1. 当前链路实际上仍是 SDR：EGL 使用 RGBA8，未声明输出色彩空间；窗口未请求 HDR；主要 shader 会把 RGB 限制在 `0..1`。即使屏幕支持 HDR，FableSol 也没有可送入 HDR headroom 的高光能量。
2. 远层水色默认向白色混合 60%，动态极值约为 37.5%～93.5%；环境色又先把记事色混白 72%～84%，再以较低权重混入主题背景。在浅色主题中，环境接近白底，只保留很少的身份色。
3. 深水、次表面和细光带分别被固定拉向 220°、150°、165°。这些绝对色相目标会跨记事颜色持续加入蓝绿倾向，形成用户感知到的冷感。
4. 表面光带、体光、浪峰 veil、thin glow、远层 edge feather、闪点 halo 等多组宽而软的半透明亮层叠加。每一层都不强，但覆盖面积和叠加次数较大，把有限的 SDR 亮度铺成了低频白雾。
5. 当前高光与水体并未共享一个线性光照能量预算。光学层直接以已编码 RGB 做 alpha 合成，随后又受 8-bit SDR 上限约束；结果是中间调被反复抬高，真正能形成晶亮感的少量峰值却无法超过 SDR 白。

因此，正确方向不是全局提高 RGB，也不是再次给最终画面套一个 tone-mapping shoulder。应先恢复水体明暗结构与材质关系，再建立保留 `>1.0` 线性辐射值的 HDR 输出路径，只把额外亮度分配给稀疏的镜面闪点、窄浪峰高光和少量逆光透射。

## 当前实现证据

### 1. 输出链路没有 HDR

- `FableSolEglSession.kt` 只选择 `8/8/8/8` EGL config，并以 `EGL_NONE` 创建 window surface；没有 FP16、`EGL_COLOR_COMPONENT_TYPE_FLOAT_EXT` 或 `EGL_GL_COLORSPACE_SCRGB_LINEAR_EXT`。
- `AudioRecordDialogFragment.kt` 只设置 60 Hz 偏好，没有设置 `Window.colorMode = COLOR_MODE_HDR`，也没有在 API 35+ 设置 `desiredHdrHeadroom`。
- `shared/fablesol/glsl/environment.frag` 在最终输出前执行 `clamp(..., 0.0, 1.0)`。
- `shared/fablesol/glsl/water.frag` 的 `linearToSrgbChannel()` 先把线性值限制到 `0..1`，最终再次执行 `clamp`。当前 sunrise SSS 或微法线即使产生更高线性能量，也会在提交 framebuffer 前丢失。

这也解释了此前“PBR Neutral 高光压缩”为什么会变暗：它压缩的是已经被限制在 SDR 范围内的结果，并没有先创造可供压缩和显示的 HDR 能量。该失败不能作为反对 HDR 的证据。

### 2. 画面中确实存在系统性的混白

- `FableSolParams.kt` 的 `lighten_far = 0.6`；`FableSolLayerColorPolicy.kt` 又加入 mood 与 breath。最远层的混白量在合法状态范围内约为 0.375～0.935。
- `FableSolGlRenderer.buildColors()` 对每层颜色执行 `mixOklab(base, WHITE, lighten)`。
- 同一方法构造环境色时，先把身份色向白混合 72%、78%、84%，再分别以 `environment_tint × 0.55`、`environment_tint`、`environment_tint × 0.42` 混入主题背景。以默认 `environment_tint = 0.16` 计算，环境 top、horizon、bottom 对原身份色的有效贡献约只有 2.5%、3.5%、1.1%。
- 最远层还可能用接近环境 horizon 的 `edge feather` 进一步软化轮廓。

这套策略原本服务于纵深分层，但目前同时提高了远处水面、空气背景和边缘过渡的白度，视觉结果更接近空气透视或乳白介质，而不是清澈水体。

用户进一步明确：远层逐级偏白、透明的水层景深阶梯本身必须保留，因为它承担九层水体的前后
分离；目标也不是完全消除雾感。因而问题应进一步定位为“景深阶梯与近白环境、宽柔光叠加后的
总泛白过量”，不能把远层混白单独当成需要删除的错误。

### 3. 冷色倾向是代码中的绝对偏色

- `FableSolDepthScatteringPolicy.kt` 把 deep 色相最多向 220°移动 8°，把 subsurface 色相最多向 150°移动 4°。
- `FableSolGlOptics.buildThinGlow()` 把已经混白 24% 的高光再最多向 165°移动 24°。
- 这些偏移并不根据水中传播距离、记事色语义或环境光色温决定；即使强度较低，多层叠加后仍会形成稳定的青蓝/蓝绿气质。

纯水对不同波长的吸收确实不同，但这是一种随传播路径增长的体吸收，而不是所有表面光效统一向冷色偏移。Pope 与 Fry 的测量还显示，可见光范围内纯水在蓝光区域的吸收比过去估计得更低，418 nm 附近为吸收最低点。对 FableSol 这种小尺度、抽象化水面，更合适的表达是“深度越大才逐步改变透射色”，而不是给所有浪峰与浅层薄光固定加青色。

### 4. 软光层数量与覆盖面积共同制造了“雾”

当前至少存在以下并行亮层：

- `surface band`：近层厚度约从 2.2 dp 到 16.7 dp，颜色含 40% 混白高光，并再与近白环境 horizon 混合；
- `body light`：最多约 12 dp 的体光；
- `crest veil`：高光再混白 32%；
- `thin glow`：3～23 dp 的宽度，混白后再向 165°偏色；
- `edge feather`：用环境 horizon 覆盖远层边缘；
- `glint halo`：解析高斯式宽光晕，核心与 halo 还分别再向白混合；
- 其他 flow streak、surface strip 与 SSS 继续占用同一 SDR 亮度范围。

这些效果的单项参数并不极端，问题在于它们都以低频、半透明、偏白的方式表达“光”。当画面缺少能显著高于中间调的窄峰值时，叠加结果会变成“亮但不晶、柔但不透”。

需要保留一个重要区分：轮廓的非刚性变形与适度柔边有助于液体识别；感知研究表明，动态变形且缺少刚性离散轮廓的区域更容易被判断为液体。因此不应把所有柔边都删除。需要收紧的是水体内部多层宽白 veil，而不是把水面轮廓改成生硬锐边。

## 互联网调研结论

### Android HDR 的平台边界

1. `COLOR_MODE_HDR` 虽然在 API 26 已存在，但 Android 官方明确建议 UI toolkit 在 API 34 以前不要使用；API 34 才是 UI HDR 的正式支持边界。
2. API 34 可通过 `Display.isHdrSdrRatioAvailable()`、`getHdrSdrRatio()` 和变化监听器读取当前可用 HDR/SDR 亮度比。这个值会随屏幕、亮度、环境与系统状态变化，不是固定设备常量。
3. API 35 增加 `Window.setDesiredHdrHeadroom()`。官方建议以 SDR 为主的混合界面通常约 1.5～2×，而全屏 HDR 内容才可能使用 5～8×。录音弹窗显然属于“多数为 SDR、局部为 HDR”的界面。
4. Android 官方明确反对把 SDR 内容整体提亮。HDR 出现时，系统可能为了保持 SDR 的感知亮度而调暗周边 SDR layer；若 HDR 面积或峰值过大，文本与其他 UI 会显得灰、淡、暗。
5. SDR/HDR 混合策略含 OEM 配置与硬件差异，可能增加 GPU 合成、功耗和烧屏风险，也可能出现 black crush。AOSP 明确说明其最终视觉质量依赖设备，无法通过统一 CTS/GTS 保证。
6. `TextureView` 的官方能力表只标记为 Android T+ 的“有限 HDR 支持”，而 `SurfaceView` 是“完整 HDR 支持”。用户已据此裁决改用 SurfaceView；HDR 能力探针仍然需要，但不再用 TextureView 的有限透传能力作为正式架构前提。
7. SurfaceView 默认位于宿主窗口下方，普通兄弟 View 可以叠在其上；Android N 起其平移与缩放会与 View 渲染同步。项目 minSdk 26 已覆盖这一同步边界，录音控件层级和弹窗位移动画不要求保留 TextureView。
8. SurfaceView 到 Android 14/API 34 才支持任意中间 alpha；旧版本会忽略 `0 < alpha < 1`。当前录音界面确实用 Host 的 `0.16 ↔ 1.0` alpha 表达准备/录音状态，所以迁移后必须把这项透明度变成 GL 内部的 presentation alpha，而不是继续调用 View alpha。
9. SurfaceView 不提供 TextureView 的复杂裁切语义；`SurfaceHolder` 支持带 alpha 的 `RGBA_F16`，但 HDR surface 的透明圆角仍需通过 GL 输出与真机合成共同验证，不能只依赖父 View 的 outline。

结论：FableSol 统一改用 SurfaceView + 自管 EGL。正式 HDR 档以 API 34+、HDR display、当前 headroom 可用、所需 EGL 扩展与 SurfaceView FP16/scRGB 原型通过为共同条件；API 26～33 在同一 SurfaceView 上保持 SDR。不能把“API 26 有常量”误解为“API 26 可可靠显示 UI HDR”。

### 适合实时 UI 的像素表示

Android 与 Khronos 的资料共同支持以下候选链路：

- 选择 FP16 RGBA EGL config，并要求 `EGL_EXT_pixel_format_float`；
- window surface 使用 `EGL_EXT_gl_colorspace_scrgb_linear` 对应的线性 scRGB colorspace；
- shader 与 blending 全程在线性空间工作，以 `1.0` 表示 SDR/reference white，允许少量像素达到 `1.0` 以上；
- 由 Android 合成器依据当前显示 headroom 映射到面板，而不是在 app 内固定编码为某台屏幕的绝对 PQ 亮度。

Display P3 只扩大色域，不提高动态范围。它可以作为 API 26+ 的独立 WCG 档，但不能命名为 HDR，也不能解决当前高光被限制在 `1.0` 的问题。

### HDR 亮度应如何分配

ITU-R BT.2408 把 HDR reference/diffuse/graphics white 定义在 203 cd/m²，并明确要求为镜面高光保留更高信号空间。Android 对混合 UI 的建议同样是限制 headroom，而不是抬升整个 SDR 基线。

据此，FableSol 应采用以下分配原则：

- 水体基色、环境、文本和弹窗其他 UI 保持在 SDR/reference white 以内；
- `>1.0` 用于分级的受控高光：少量 glint core 使用较高峰值，更多受光 crest specular 与薄层
  逆光透射使用较低增益，使正常观看时能明确辨认 HDR 差异；
- 已确认的三级目标为：核心与最强浪峰 `1.6～2.0×`，较多受光浪峰与窄反射带 `1.15～1.4×`，
  少量薄层透射 `1.05～1.2×`；环境、水体基色和宽光晕不超过 `1.0×`；
- halo 必须弱于 core，且宽度增加时总能量不能同步增加；
- 可用 headroom 下降时，先压缩 `>1.0` 的峰值，不改变水体中间调和身份色；
- SDR fallback 只对超出 `1.0` 的部分做 roll-off，而不是再次压缩整个 `0..1` 画面。

感知研究显示，亮度直方图的正偏度与光泽感相关。换成 FableSol 的工程语言，就是保留大部分有层次的中暗水体，同时制造少量非常亮的高光峰值；把大面积像素一起向白抬高会降低这种分布差异，反而更不显晶亮。

### 清澈水体的材质关系

PBRT 对 rough dielectric 的描述表明，水和玻璃类介质必须同时处理反射与透射，并由 Fresnel 项在二者之间分配能量；微表面粗糙度决定高光的集中程度。Epic 的 Single Layer Water 资料进一步把水体外观拆成散射、吸收、反射与折射，并明确指出低散射介质更清澈。NVIDIA 的实时水体资料也使用逐像素法线、Fresnel、深度相关透明度以及反射/折射组合。

对应到 FableSol，建议把目前多个彼此独立的白色 overlay 收敛成一个轻量的“耦合介电水材质”：

1. 用连续水面的宏观斜率与现有 micro normal 得到统一法线。
2. 用水的近似 `F0 ≈ 0.02` 和 Schlick Fresnel 分配表面反射与水体透射/体色，而不是让 surface band、body light、veil 各自增加亮度。
3. 用路径长度近似控制吸收；浅处保持透明与身份色，深处才逐渐降低透射并产生温和色相变化。
4. 将 scattering 默认值明显降低，只保留浪峰逆光时的小范围 SSS；不要让散射覆盖所有层。
5. 降低 roughness 或收窄高光核，同时让微法线决定高光出现位置；保留少量随机性，但避免大面积均匀泛白。

用户已确认视觉权重以表面反射、受光浪峰和镜面闪点为主，较窄的浪峰内部透射为辅；高光可以在
材质上更集中，但不得改变现有柔和浪形。body light 与 crest veil 不再承担大面积整体内发光。

柔雾只保留一个独立来源：`crest veil` 作为低能量、SDR 范围内的波冠柔化；九层 `body light`
并入统一透射/体色计算，`thin glow` 只表达较窄浪峰透射，三者不再重复叠加。

这不要求把 FableSol 变成写实海面，也不要求重新引入已被否决的焦散。它只是用一致的光学关系约束现有抽象语言，使“身份色、深浅、反射、透射与高光”不再互相争夺亮度。

用户已确认首轮不加入折射、填充视差或焦散。只有环境/内部光学层隔离、统一反射/透射和 HDR
主路径完成后仍缺少透明介质感，才把轻微折射作为独立、小幅、可归零的 A/B 项；焦散不恢复。

## 推荐的技术路线

### 阶段 A：先修正 SDR 材质，不等待 HDR

目标是让所有设备先获得更通透、更有对比的水体，并建立可信的 SDR 基线：

1. 已确认去除 deep/subsurface/thin glow 的绝对冷色目标；表面与透射保持 ThingBackground 同色系，
   仅最深传播路径允许相对身份色不超过约 2°的轻微冷移。
2. 首轮锁定现有水层景深阶梯，只降低近白环境与内部宽柔光的累计泛白；若后续仍需调整远层
   混白，必须保持越远越亮/透的单调关系和相邻层可辨识度。
   已确认首轮同时锁定 `lighten_far`、九层 alpha，以及远层 edge feather 的强度和宽度；只修改
   环境底色与 body light、crest veil、thin glow、halo 等内部光学层。
3. 对 surface band、body light、crest veil、thin glow、edge feather、analytic halo 做能量审计。优先合并表达同一物理现象的层，而不是逐项继续加亮。
4. 增强局部明暗差：压低背光坡面与水体中段，但阴影继续使用身份色派生色，不能变成脏灰或纯黑。
5. 保留液体轮廓的柔性；只收紧内部 veil 和 halo，并让核心高光更窄、更稀疏。
6. 把主要光照和光学合成迁入场景线性空间，建立可输出 `>1.0` 的统一辐射结果。

### 阶段 B：建立单一场景线性结果与双输出

- HDR 输出：FP16 linear scRGB，水体基色与环境保持 `<=1.0`；少量核心高光可到约 1.5～2.0，
  更多受光浪峰与薄层透射使用较低的超白增益；上限随实际 `getHdrSdrRatio()` 变化。
- SDR 输出：同一场景线性结果经过只针对超白部分的 highlight roll-off，输出到当前 RGBA8 sRGB 路径。
- 不允许 HDR 与 SDR 使用两套独立材质参数；否则会重新产生不可维护的视觉分叉。

### 阶段 C：Android 能力分级

| 条件 | 推荐输出 |
|---|---|
| API 26～33 | SDR；可另行启用 Display P3，但不称为 HDR |
| API 34、HDR ratio 可用、EGL/SurfaceView 原型通过 | HDR 三级亮度按 D57 分配，并服从实际 ratio |
| API 35+、同上 | 请求约 1.5～2.0× `desiredHdrHeadroom`，并根据实时 ratio 自适应 |
| 扩展缺失、ratio=1、SurfaceView HDR 链路失败或省电/显示条件不允许 | 在同一 SurfaceView 自动回退 SDR，不改变几何、身份色与动画语义 |

这里的“一致”应定义为几何、动画、材质语义、身份色和 SDR 基线一致；HDR 设备只在 D57 约束的
局部受光区域获得分级额外亮度。若仍要求 API 26+ 最终像素亮度严格相同，则逻辑上无法同时获得
真正 HDR。

由 D65 裁决，以上分级全部自动执行，不增加正式用户开关。Debug 版可以显示能力判定、运行时
ratio、HDR 像素占比与强制 SDR 对照；HDR 探针仍必须服从真实 EGL/surface 能力，不能以调试开关
伪造不可用的输出链路。

由 D66 继续限定内容状态：SurfaceView/EGL surface 在 Dialog 生命周期内保持稳定，只有
`RECORDING` 状态允许 scene-linear 内容超过 `1.0`；`PREPARED` 与 `STOPPED` 始终提交 SDR 内容。
HDR 增益随既有水面展示动画平滑开合，而不是通过重建 surface 切换。

由 D67 限定声音映射：不建立音量/onset/节拍到 HDR gain 的直接通道。声音只通过既有几何、
微表面和光学实体条件间接改变超白像素的时空分布；单个高光的辐射强度仍由材质与光照决定。

由 D69 限定 HDR 色彩阶梯：最高镜面核心随亮度趋近中性白但保留轻微身份色，中等受光浪峰
明显保留 ThingBackground 色相，薄层透射保留最多身份色。不能用统一青白/蓝白代替中性反射光。

由 D70 限定时间语义：HDR 闪点复用现有持久 Track 的约 `0.30s` attack、`0.80s` release 与慢
呼吸，不增加第二套快速闪光、余辉或节拍包络。HDR 只改变符合条件的空间核心亮度。

由 D71 限定 SDR 回退：能力或状态不可用时直接令内容 headroom 为 `1.0`、HDR excess 为零，
不在应用内增加会修改 `<=1.0` 基线的 tone-mapping shoulder、自动曝光或中间调压暗。材质去雾
与配色重组仍由 SDR/HDR 共享。

由 D72 修订去雾实验顺序：第一步锁定 environment top/horizon/bottom，只取消独立九层 body
light、收窄 thin glow 并保留低能量 crest veil；若仍过雾，第二步才单独调整环境。景深阶梯与
远层羽化在两步中都继续服从 D59～D60。

由 D73 界定亮度目标：优化不以提高平均画面亮度（APL）为目标。移除宽范围 body light 后允许
APL 自然小幅下降，并且不抬高环境、水体基色、九层 alpha 或宽范围补光进行补偿；主观亮度和
晶莹感应由更集中的表面反射、受光浪峰和镜面闪点提升。验证时同时记录 APL 与局部峰值，前者用于
发现意外全局明暗漂移，后者才是 HDR 亮度提升的主要指标。

由 D74 增加景深能量阶梯：近层 `0～2` 可使用约 `1.6～2.0×` 的完整镜面核心，中层 `3～5`
将超白峰值限制在约 `1.2～1.5×`，远层 `6～8` 基本不生成 `>1.0` 增量。远层继续依靠既有
偏白、透明和柔化表达距离；HDR 不能把不同深度的轮廓统一漂成同一档中性白。

由 D75 明确中等覆盖的取得方式：先收紧或合并现有宽 `analytic halo`、`thin glow` 与
`crest veil` 的重叠能量，再适度增加近、中层窄高光片段的数量、连续性和浪峰占用率。每个片段
保留清晰核心和低能量窄柔边，不扩大低频光晕，也不形成贯穿整段浪峰的连续白描边。

由 D76 排除顺流流光的超白分配：现有 `flow streak` 单条约持续 `5～9s`，按背景流平移，属于
低 alpha 的运动线索。它继续保持 Thing 色系和 SDR 亮度；HDR excess 只进入满足表面朝向条件的
反射、受光浪峰和镜面闪点，避免长亮带形成 HDR 拖尾。

由 D77 取消剩余的高光色相漂移：移除 `pearl_shift_deg` 带来的约 `±6°` 周期旋转，以及
`hue_temp_deg×0.6` 的固定偏移。高光、受光浪峰和顺流流光只沿当前位置的 ThingBackground
本地颜色到中性白改变彩度和亮度；亮度呼吸与出现频率仍保留，最深路径仅保留 D58 的微冷上限。

由 D78 限定首轮阴影：不提高 `back_shade_gain`，不额外压暗波背、深水、Thing 基色或中间调；
只观察移除 body light 和收紧重复柔光带来的自然对比变化。若仍扁平，再把阴影加深作为第二轮
独立 A/B，避免首轮同时“去亮”和“加暗”而失去归因。

由 D79 确定交付顺序：先发布仅含共享 SDR 材质重组、色相归一和窄高光分布调整的 Debug，真机
确认后再发布 FP16/scRGB HDR 与分层超白峰值。首轮不启用 HDR excess，确保材质清晰度和显示
动态范围能够分别验收。

## 验证方案

### 自动化与调试视图

- 为 scene-linear buffer 增加 false-color：区分 `0..1`、`1..1.5`、`>1.5` 区域。
- 统计每帧 `>1.0` 像素占比、峰值、平均画面亮度、亮度直方图偏度与各光学项能量。
- 建立 SDR/HDR 同源输出的截图回归；几何、色相与 `<=1.0` 基线必须一致。
- 建立 EGL 能力与回退测试：FP16、scRGB linear、HDR ratio、窗口生命周期和 surface 重建均不得导致黑屏或颜色跳变。
- 建立 SurfaceView 合成回归：API 26～33 的 presentation alpha、透明圆角、窗口/视图动画、控件覆盖和恢复前后台均不得出现矩形漏边、错位、闪烁或层级倒置。

### 真机验收

- 必须在至少一台 API 34 和一台 API 35+ 的 HDR 手机上目视；SurfaceView 的 FP16/scRGB 透传、透明圆角与 OEM dimming 不能只靠单元测试确认，并补一台 API 26～33 设备验证 SDR 合成兼容性。
- 分别检查低/中/高系统亮度、明暗环境、省电模式、浅色/深色主题与不同 ThingBackground 颜色。
- 检查弹窗内文字、按钮和背景是否因 HDR 激活而显灰、显暗或黑位压死。
- 系统截图通常会把 HDR tone-map 成 SDR，只适合检查构图与色偏，不能证明峰值亮度正确。最终亮度仍需真机观察和运行时 ratio/false-color 数据共同判断。

## 风险与待裁决

1. 已由 D56～D57 裁决：D40 的一致性不再包含兼容设备上受控局部高光的 HDR 额外亮度；几何、动画、材质语义、身份色与 SDR 基线仍必须一致。
2. 已由 D64 裁决：ADR-0016 的容器从 TextureView 改为全版本统一 SurfaceView。剩余风险不是容器二选一，而是透明圆角、旧系统 presentation alpha 与 surface 生命周期的兼容验收；若 HDR 能力探测失败，只回退同一 SurfaceView 的 SDR EGL config，不恢复 TextureView。
3. HDR 可能影响同一 Dialog 内的 SDR UI，但 D68 已裁决不据此预先限制水面视觉上限。首轮按
   D57 完整实现并把文字/按钮亮度作为真机观察项；只有实际观感出现问题时再独立诊断，不静默
   压缩已验收的 HDR 基线。关闭弹窗时仍必须恢复所有窗口/surface HDR 状态。
4. 更高亮度可能增加功耗和 OLED 长期高亮风险。高亮面积、持续时间与峰值都需要限制，不能让整条浪峰长期处于 HDR 峰值。

## 主要资料

### Android、AOSP 与 Khronos

- [Android Developers Blog：HDR and User Interfaces](https://android-developers.googleblog.com/2025/09/hdr-and-user-interfaces.html)
- [Android `ActivityInfo.COLOR_MODE_HDR`](https://developer.android.com/reference/android/content/pm/ActivityInfo#COLOR_MODE_HDR)
- [Android `Window.setDesiredHdrHeadroom`](https://developer.android.com/reference/android/view/Window#setDesiredHdrHeadroom(float))
- [Android `Display`：HDR/SDR ratio](https://developer.android.com/reference/android/view/Display)
- [Android `TextureView` 与 `SurfaceView` 能力对比](https://developer.android.com/reference/android/view/TextureView)
- [Android `SurfaceView`：层级、同步、alpha 与 desired HDR headroom](https://developer.android.com/reference/android/view/SurfaceView)
- [Android `PixelFormat.RGBA_F16`](https://developer.android.com/reference/android/graphics/PixelFormat#RGBA_F16)
- [Android：OpenGL 广色域与 EGL color space](https://developer.android.com/training/wide-color-gamut)
- [AOSP：颜色管理与 FP16/scRGB linear](https://source.android.com/docs/core/display/color-mgmt)
- [AOSP：SDR/HDR 混合合成](https://source.android.com/docs/core/display/mixed-sdr-hdr)
- [Khronos：EGL_EXT_gl_colorspace_scrgb_linear](https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_gl_colorspace_scrgb_linear.txt)
- [Khronos：EGL_EXT_pixel_format_float](https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_pixel_format_float.txt)

### HDR 标准与材质/感知研究

- [ITU-R BT.2408-8：HDR reference white 与高光余量](https://www.itu.int/dms_pub/itu-r/opb/rep/R-REP-BT.2408-8-2024-PDF-E.pdf)
- [ITU-R BT.2100-3](https://www.itu.int/rec/R-REC-BT.2100-3-202502-I/en)
- [PBRT：Rough Dielectric BSDF](https://www.pbr-book.org/4ed/Reflection_Models/Rough_Dielectric_BSDF)
- [Epic：Single Layer Water Shading Model](https://dev.epicgames.com/documentation/en-us/unreal-engine/single-layer-water-shading-model-in-unreal-engine)
- [NVIDIA GPU Gems：Effective Water Simulation from Physical Models](https://developer.nvidia.com/gpugems/gpugems/part-i-natural-effects/chapter-1-effective-water-simulation-physical-models)
- [NVIDIA GPU Gems 2：Generic Refraction Simulation](https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-19-generic-refraction-simulation)
- [Pope & Fry：纯水可见光吸收谱](https://pubmed.ncbi.nlm.nih.gov/18264420/)
- [Motoyoshi 等：亮度统计与表面光泽感知](https://www.nature.com/articles/nature05724)
- [Kawabe：动态轮廓与液体感知](https://pmc.ncbi.nlm.nih.gov/articles/PMC5471326/)
