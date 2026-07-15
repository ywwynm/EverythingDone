# FableSol 首版 HDR 与 HDR 前最终版精确对照（2026-07-15）

## 对照边界

必须以相邻发布 APK 为准，不能直接用 Git 提交近似：

| 语义 | Debug 更新码 | 发布时间（Asia/Shanghai） | SHA-256 |
|---|---|---|---|
| HDR 前最终 SDR 材质版 | `202607130749` | 2026-07-13 15:49:26 | `d61651838fd702eb5422cf19ed66d9316337b532a9bf33d0a28d0bdb77690f62` |
| 首个 FP16/linear-scRGB HDR 版 | `202607130828` | 2026-07-13 16:28:59 | `5c7b55816b450f7d7adafa44fbf50a8c0927f008a9a15bc9f9ccac8a35ffb1a1` |

两版 APK 位于 `app/build/outputs/update-debug-apk/`。它们没有各自独立的 Git commit；后来连同
SurfaceView 迁移、HDR 前材质收敛和 HDR 后多轮宽材质实验一起合入 `c03b4f711…`。其父提交
`8eb23c04…` 仍早于 SurfaceView 与 `202607130749`，所以 `8eb23c04…c03b4f711` 不是纯 HDR
差异。

## APK 与字节码证据

两个 APK 的 2014 个 ZIP 条目中只有 8 个变化：

- `assets/fablesol/glsl/environment.frag`
- `assets/fablesol/glsl/water.frag`
- `assets/fablesol/glsl/optical.vert`
- `assets/fablesol/glsl/optical.frag`
- `assets/fablesol/glsl/present.frag`
- `classes10.dex`
- `classes21.dex`
- `resources.arsc`

后两项只包含构建/更新码变化。对 `classes10.dex` 反汇编并归一化地址与索引后，以下类在两版中
逐指令一致：`FableSolParams`、`FableSolMaterialPolicy`、`FableSolOpticalColorPolicy`、
`FableSolDepthScatteringPolicy`、`FableSolLayerColorPolicy`、`FableSolLightColorPolicy`、
`FableSolShadowColorPolicy` 与 `FableSolContinuousSurface`。

因此两版之间没有修改 `lighten_far=0.60`、九层 alpha、环境颜色、主体色阶梯、
`body_light_strength=0`、`thin_glow_gain=0.38`、`crest_veil_strength=0.14`、表面/薄峰几何、
闪点容量、波形或声音映射。`water.vert` 也逐字节不变。首次 HDR 没有改九层主体色板。

## 真正新增的 HDR 链路

`202607130828` 在已经验收的同一 `SurfaceView` 上增加：

1. API 34+ HDR 显示器与实时 `hdrSdrRatio` 能力门；失败时回退 RGBA8 SDR。
2. float component EGL config、linear scRGB window surface 和 `GL_RGBA16F` scene framebuffer。
3. `FableSolHdrPolicy` 与 `FableSolHdrTransition`，录音态在 `0.36s` 内收放 HDR gain。
4. API 35+ 录音态为该 `SurfaceView` 请求最多 `2.0×` desired headroom。
5. 光学顶点增加逐点 HDR eligibility；glint core、受光表面带与薄峰透射获得独立峰值。

点击开始录音不会重建 surface。HDR-capable surface 在弹窗打开时已经建立；PREPARED/STOPPED
只是 `hdrGain=0`，RECORDING 才把 gain 拉到 1。Dialog/Activity 没有切 `COLOR_MODE_HDR`，首版也
没有 app tone mapping、soft shoulder、全局曝光或全局压暗。

## 为什么观感仍然发生明显变化

### 1. 半透明光学从编码域混合改为线性混合

`202607130749` 在 RGBA8 scene 中把 sRGB 编码值直接做 alpha blend；`202607130828` 将 environment、
water 和 optical 先转为线性值，再在 RGBA16F scene 中混合。主体水体是不透明填充，往返转换基本
等价；所有半透明、偏白表面带则不再等价。

例如编码值 `0.2` 的底色上，以 alpha `0.2` 混入编码值 `0.9` 的高光：旧编码域结果为 `0.34`；
线性混合再换算到显示编码约为 `0.466`。这是物理上更正确的混合，但如果沿用为旧编码域调出的
alpha、宽度和中性白比例，视觉上会明显更亮、更宽、更低饱和。即使 `hdrGain=0`，HDR-capable
surface 上的半透明光学也不能与旧版严格像素相同。

### 2. 首版 HDR 对暗色与低 alpha 做了过强补偿

第 1/2 层（用户所说第二、第三层）的首版目标峰值为：

| 效果 | 第 1 层 | 第 2 层 |
|---|---:|---:|
| glint core | `1.90×` | `1.75×` |
| surface/crest | `1.36×` | `1.30×` |
| thin transmission | `1.17×` | `1.14×` |

首版 `optical.frag` 的 `hdrTint()` 先把线性色按最大通道归一，再向中性白混合；glint、surface、
transmission 的中性白比例分别为 `0.90/0.45/0.25`。surface 与 transmission 又使用连续覆盖 mask，
并非只有闪点核心超白。

更关键的是 `darkCompensation` 与 `excess / opticalAlpha`：它先给深色最多补 `0.45` 线性能量，
再除以原光学 alpha；经过 `SRC_ALPHA` 合成后，补偿近似不再受低 alpha 抑制。它能保证暗色窄带
越过 reference white，却也会同时抬高弱通道，使第二、第三层在录音启动后出现“提亮、去彩、
乳白”的组合。这是首版 HDR 最主要的观感风险，不是主体色板变化。

### 3. 录音开始还保留原有全画面 presentation 淡入

PREPARED 到 RECORDING 的 `presentation alpha 0.16→1.0` 早于 HDR 已存在；首版 HDR 只把同一语义
放进 `present.frag`。因此点击后整幅画面显著显现不能全部归因于 HDR，但它与同为 `0.36s` 的 HDR
gain 同时发生，主观上很难分开。API 35+ 同时请求更高 desired headroom，系统/OEM 的混合 SDR/HDR
合成还可能带来设备相关的二次亮度变化。

## 对当前版本的解释

当前版本仍保留 FP16/linear-scRGB 与线性混合，因为这是正确的 HDR 架构；但已删除首版最危险的
`darkCompensation` 和 `excess / opticalAlpha`，改为预乘 SDR 颜色加独立、有界 HDR excess；
中性白比例也由 `0.90/0.45/0.25` 收到 `0.72/0.34/0.18`。同时 `body_light_strength=0`、
`thin_glow_gain=0.38`、`crest_veil_strength=0.14`，解析 halo 与层内 continuous sheen 已归零，
表面反射由旧版最高约 `9.4dp` 收到局部约 `3dp`。

当前名义 HDR headroom 与 glint 峰值反而高于首版，却获得更干净的观感，说明问题不在“HDR 数字
太高”，而在覆盖面积、中性化程度、混合域和低 alpha 补偿的乘积。后续应把这四项分别回归，
不再通过改主体色板或全局峰值来补偿局部光学问题。

## 历史弯路的起点

`202607130749→202607130828` 本身是相对克制的两阶段设计，但首版低估了线性混合对既有半透明
材质的影响，也采用了过强的暗色/inverse-alpha HDR 补偿。真正明显扩张的实验从后续 Debug
`202607130840` 开始：重新加回 `body_light_strength=0.36`，把表面反射、薄峰透射和解析 halo
恢复为宽版本；之后又在这些互相叠加的变量上多轮收窄、恢复和重做。未来应保留精确 APK 基线，
每轮只比较一个可见变量，并分别记录 SDR 主体、半透明线性混合与 HDR excess。
