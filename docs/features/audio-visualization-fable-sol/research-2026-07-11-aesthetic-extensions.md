# 调研 · 立体/质感/优雅/流动/真实的扩展手法清单（2026-07-11 第三轮，Claude 代理汇总）

> 背景：用户要求在"薄峰透光、流光平流、轨道微摆"三手法之外继续扩大调研面，
> 不限于用户点名的方向。本轮由检索代理完成 15 次检索，覆盖风格化游戏渲染
> （Journey/Sea of Thieves/Wind Waker/GRIS）、海景绘画（Earp/Gurney/教学体系）、
> 光学物理（Cox-Munk/Fresnel）、液体运动知觉研究、动画电影（Ponyo/新海诚）五个领域。
> 全部候选均已按本项目红线（不改浪形、无粒子、单色相纪律、O(N) 每层每帧、
> 逐项 A/B）过滤。**已明确排除**：泡沫/lace 纹理、细亮线 caustic 网、任何整体重构。

## 高价值候选（按建议实施顺序）

### 1. 波背自阴影（under-crest dark accent）——立体感最大单项缺口

海景绘画的核心明度结构：**最亮的浪脊紧贴着最暗的背光窝**，明暗在脊线两侧直接
相接，这是波读作"体积在滚动"的根本原因；渲染文献同样确认 wave self-shadowing
给波三维形体。当前已有的接触阴影是"层与层之间"的，此项是"**层内**每个波包
自己的明暗转折"——与薄峰透光（迎光/透光侧）恰好互补成完整受光模型。
实现：暗化项 ∝ max(0, −slope·光源方向)，低通平滑后叠进该层填充顶端；
须防止明暗交界形成硬线。代码量最小、收益最大。
（[Samuel Earp · How to Paint an Ocean Wave](https://samuelearp.com/blog/how-to-paint-an-ocean-wave/)、
[Russell Collection](https://russell-collection.com/how-to-paint-ocean-waves/)）

### 2. 空气透视对比度压缩（逐层 k 系数）——一切装饰的音量控制器

绘画的空气透视不只是"远处混白"：距离增加 → **值对比下降、边缘变软、内部细节
消失**。实现为每层一个标量 k(layer)∈(0.4..1]，把该层所有装饰（闪点亮度、阴影
深度、猫爪、流光、透光）的振幅向该层基调收缩。远层安静、近层承载细节，视线
自动落在前景——"宁少勿烂"的构图学版本。与被否决的三段式重构不同：不动结构，
只做既有装饰的逐层增益表。
（[Aerial perspective](https://en.wikipedia.org/wiki/Aerial_perspective)、
[Draw Paint Academy](https://drawpaintacademy.com/how-to-use-atmospheric-perspective/)）

### 3. Glitter Path 光柱统计——把九层缝成"同一光源照亮的同一片水"

光在水面的反射形成一条从远到近的竖直光带（glitter path），位置在光源—眼睛
竖直平面与水面的交线上，宽度由波面斜率方差决定（Cox-Munk 统计，风速/能量
参数化）。实现：全局光柱中心 x₀ + 每层宽度 w(layer)，既有闪点的出生概率与
亮度乘 exp(−(x−x₀)²/w²)，柱外降权不禁止；x₀ 可 1/f 极慢漂移。零新元素、
每闪点 O(1)，是"单层好看"升维到"整幅成画"的构图装置（月光海意象）。
配套细化（Journey 式）：闪点出生率与斜率场方差挂钩——音频能量大 → 碎光更密。
（[Atmospheric Optics · Glitter Paths](https://atoptics.co.uk/blog/glitter-paths/)、
[Cox-Munk](https://www.oceanopticsbook.info/view/surfaces/cox-munk-sea-surface-slope-statistics)、
[Journey Sand Shader](https://www.alanzucconi.com/2019/10/08/journey-sand-shader-5/)）

### 4. 运动统计包：统一速度场 + 双谱海况 + 1/f 慢调制——液体感的知觉学地基

视觉科学结论（Kawabe 等）：人仅凭运动即可识别液体，关键是 **optical flow 的
空间平滑性**；透明液体知觉要求**匀速直线平移成分少**；局部速度慢 → 读作更
黏稠厚重（"缓慢的贵重感"）。三条工程化推论：
- 同层所有装饰（流光/闪点/回摆/猫爪）必须共享同一个空间平滑、随时间缓变的
  速度场，各自独立匀速会杀死液体感；
- 真实海况是长周期涌浪(8~14s)+短周期风浪(2~4s)的双谱叠加，短波局部振幅可由
  猫爪场调制——"看得见的风"；
- 总振幅、闪点出生率、光柱中心等慢变量用 1/f（几个不同时间常数的平滑随机
  游走求和）调制，免于机械循环感——梦幻感最便宜的来源。
（[Seeing liquids from visual motion](https://www.sciencedirect.com/science/article/pii/S0042698914001540)、
[Ocean-Wave Spectra](https://www.wikiwaves.org/index.php/Ocean-Wave_Spectra)、
[1/f Pink Noise 合成](https://people.computing.clemson.edu/~sjoerg/docs/Duchowski15_PinkNoise.pdf)）

### 5. OKLab 色温微偏移（±4~8°）——"画出来的水"与"调出来的水"的最后距离

画家共识：背光面偏冷、透光/受光面偏暖，纯明度渐变的单色画面读作"灰、塑料"。
实现：用第 1 项的受光场做 hue 旋转 ±4~8°（受光暖、背光冷），chroma 夹紧，
一切仍由主题色派生。这是最接近单色纪律红线的一项，必须独立开关交用户裁决。
（[Gurney Color & Light](https://www.artistsnetwork.com/art-techniques/color-mixing/painting-essentials-color-light-from-james-gurney/)、
[Earp 透光浪配色](https://samuelearp.com/blog/translucent-ocean-waves/)）

## 备选梯队（前五落地后再议）

- **Lost-and-found 边缘**：顶边羽化强度沿 x 由受光场门控——受光浪脊清晰、
  背光/谷处渐失；打破均匀边缘的 CG 感（依赖第 1 项的受光场）。
- **英雄高光层级（新海诚式）**：同屏最亮闪点限 1~3 个，按亮度排序做全局增益
  表；hero 闪点加 2~3 tap 横向微拖影——摄影感。
- **Ponyo 波浪手势**：一分钟一两次的长波包缓缓穿场（高斯包络×长正弦，物理
  叠加，红线允许），可绑定语句结束/音量峰值，海"深吸一口气"。
- **值域纪律审计**：最亮 L 只属于闪点与珍珠，其余系统 L 上限压一档
  （一次性校准，非新功能）。
- **双体色状态混合（Sea of Thieves）**：deep↔subsurface 全局混合比接 1/f
  平滑后的音频包络——安静时深沉、说话时向透光色苏醒。
- **Fresnel 远层提亮**：方向必须是"远层向天空亮色靠"（空气透视同向），
  谨防做成已被否决的近层深度吸收。
- **水彩湿边**：仅猫爪暗带一处试点 |∇f|·f 的极弱边缘积色；过量显脏。

## 教训沉淀（Wind Waker 原则）

完全放弃反射、纯设计驱动的海反而成为最具辨识度的水。每加一个物理装置前先问
"它是否服务于既定的画面设计"——与"宁少勿烂"一致：物理是手段，画面是裁判。
