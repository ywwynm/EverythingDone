# dialog-particle-dismiss 会话记录

## 2026-08-26 技术路线调研

- 目标：把鸿蒙 OS7 删除通知的粒子消散动画引入完事儿，作为所有 Dialog dismiss 动画，
  粒子颜色须反映 Dialog 内容颜色。
- 完成项目侦察：minSdk 26 / targetSdk 36；全部 28 个 DialogFragment 继承
  BaseDialogFragment，无裸 AlertDialog.Builder，onCreateDialog 统一返回
  GestureAnchoredDialog——全局接入只需改基类两处。项目已有两套 GL 基础设施
  （FableSol 自建 EGL 线程、SpatialPhotoView 的 GLSurfaceView）。
- 产出 [research-2026-08-26-technical-approach.md](research-2026-08-26-technical-approach.md)：
  推荐"DecorView 快照 → GL_POINTS 无状态点粒子（波前溶解）→ 全屏透明 TextureView
  挂 Activity DecorView"路线；不延迟真实 dismiss，动画为纯装饰层。排除 Canvas /
  AGSL / RenderNode 碎块三条备选。工程量估计中等（框架 2–3 天 + 调优多轮）。
- 未开始写代码，等待用户对路线的确认。

## 2026-08-26 样板实现（AlertDialogFragment 试点）

用户确认路线后完成首版实现，`:app:assembleDebug` 通过，待真机目验观感。

- 新增 `views/particledismiss/` 三个文件：
  - `ParticleDismissRenderer.kt`：GL 渲染线程。RGBA8888/ES 3.0 自建 EGL（照
    FableSolEglSession 的 SDR 路径，但**不调 eglTerminate**——display 为进程级
    共享，terminate 会波及并存的 FableSol/Spatial 会话）；粒子无状态，属性全由
    gl_VertexID 派生（PCG 整数 hash 出随机数，浮点 sin-hash 在 15 万粒子量级
    随机位不足会条带），每帧只更新 uTime，单 GL_POINTS draw call；varying 两侧
    显式 highp（Maleoon 规约）。
  - `ParticleDismissOverlay.kt`：全屏叠加层（dim 补偿 → TextureView → 快照
    ImageView 三层），快照层遮蔽 EGL 建链延迟并充当 GL 失败降级载体；超时兜底
    强制收尾；触摸穿透。
  - `ParticleDismissController.kt`：入口检查（动画开关/Activity 状态/主线程）、
    DecorView 软件 draw 抓图 + CPU 圆角遮罩、坐标换算（双 getLocationOnScreen
    差值，分屏安全）、粒子步长 1.5dp 自适应上限 15 万。
- `BaseDialogFragment.kt`：`useParticleDismiss()` 开关（默认 false）；
  `GestureAnchoredDialog.dismiss()` 为全路径唯一拦截点（代码 dismiss、back、
  点外部经 cancel 都汇入 Dialog.dismiss），动画接管后置空 windowExitAnimation
  防双影；dispatchTouchEvent 记录最近触点作为波前起点。
- `AlertDialogFragment.kt`：override 开关 = true（样板）。
- 已知取舍：快照无 elevation 阴影（软件 draw 特性，dim 同时淡出下几乎不可察）；
  含 SurfaceView/TextureView 的 Dialog 检测到即跳过动画（待 PixelCopy 路径）。
- 初版观感参数集中在 Renderer companion：SPREAD_TIME 0.30s / DELAY_JITTER
  0.06s / LIFETIME 0.55s / 湍流 1.3 cell / 漂移 110dp（×0.55–1.45 随机）。

## 2026-08-26 第二版：curl 流场烟云化（用户第一轮观感反馈）

用户真机反馈：基础链路认可，但"炸开方向太固定、太均匀，要华为那种遁入烟云的
轻盈优雅"。诊断：初版是逐粒子独立白噪声方向（统计均匀 = 整块上升的幕布），
烟云感的本质是**空间连贯流场**（相邻粒子被同一股气流带动、成团成缕）。

- 建立桌面观感蓝本 `tmp/particle-dismiss-tuning/render_frames.py`（moderngl
  离屏渲染 3×3 时刻网格 + 单帧，conda env everythingdone），新旧模型 A/B。
  另有 index.html WebGL 蓝本，但本会话 Browser pane 不显示、无法合成帧截图，
  弃用；序列帧蓝本是有效的观感验证通道，GLSL 与 Android 版手工同步
  （300 es ↔ 330 头部差异）。
- 新运动模型（两轮蓝本迭代定稿）：value-noise fbm 的 curl 场（无散度旋涡，
  e=0.12 差分）+ 低频升力调制 + 流场采样点随主位移移动（路径弯曲）+ 场随
  时间演化；ease 从 easeOutQuad 反转为 pow(tl,1.35) 前慢后快（运动中消失
  才轻盈）；alpha 加低频浓淡调制（静止段不参与）+ smoothstep(0.15,0.95)^1.7
  长尾；触点径向 36dp 吹散推力；主方向每次随机 ±18° 倾斜。
- 第一轮蓝本问题与第二轮修正：位移太小不够"飘走"（DRIFT 130→210dp）、矩形
  轮廓保持太久（SWIRL 0.55→0.75、NOISE_SCALE 96→120dp）、半卡半云过渡期
  太短（SPREAD 0.30→0.40s，LIFETIME 0.62→0.58）。
- 代码结构：新增 ParticleDismissSpec 承载全部动画输入，Controller 组装、
  Overlay 透传、Renderer 消费。
- 发布 `202608260337`，待用户第二轮真机反馈。

## 2026-08-26 第三版：闪黑、触点方向、宽度截断感（用户第二轮反馈）

三项反馈的诊断与修复（均先在蓝本验证再移植）：

1. **动画开始时屏幕先变黑一瞬**：根因是双 dim 叠乘——WMS 对被移除 window 的
   dim 层自带淡出过渡（并非设计时假设的"即时消失"），与 Overlay 的满值补偿
   dim 层叠加。修复：删除补偿层，背景亮度过渡完全交给系统；Overlay 回到
   快照 + TextureView 两层结构。教训：初版为"假设中的生硬瞬断"加的补偿层，
   恰是症状的制造者。
2. **触摸外部 dismiss 应体现触摸位置**：driftDir 从恒向上改为"触点指向快照
   中心"（normalize(center - touch)，点下方向上飞、点上方向下飞、点侧面向
   对侧飞），距中心 <40dp 或无触点（back 键）退回向上；随机 ±18° 倾斜叠加在
   计算方向上。waveOriginUv 不再 clamp 进快照，允许 [-0.5, 1.5]——波前从
   真实触点方向的边缘先咬入。
3. **消散云宽度被 Dialog 宽度"截断"**：蓝本（无任何窗口裁剪）可复现 → 判定
   为观感问题而非渲染裁剪。修复：气流项拆出独立缓动 flowEase=pow(tl,0.8)
   （比主位移 pow 1.35 更早起效，直边立刻失形）、SWIRL 0.75→1.0、
   DELAY_JITTER 0.06→0.10、RADIAL_PUSH 36→44dp。
- 蓝本新增 touch_below / touch_above 两个触点场景网格图，验证方向逻辑与
  波前出快照起点的形态（向下飞的形态亦自然）。
- 发布 `202608260505`，待用户第三轮真机反馈。

## 2026-08-26 第四版：方向语义反转（用户第三轮反馈）

用户裁定："触点方向和消失方向反向了"——粒子应**朝按下的位置飞**，不是被
触点推开。改动：

- Controller：driftDir = normalize(触点 - 快照中心)（原为 中心 - 触点）；
- shader：radial 从"背离触点"反转为"向触点汇聚"，与主方向同语义（像被
  指尖收走）；
- 规则统一的结果：点确认/取消按钮时粒子朝按钮方向（向下）飘散，与外部
  点击同一规则；back 键仍默认向上。已在发布日志中向用户说明。
- 蓝本第四轮验证：按钮点击场景（向下偏 12°）与 touch_below 场景，向下
  翻卷的云被流场带走、无坠重感；汇聚吸力未造成漏斗状收束。
- 发布 `202608260637`。

## 2026-08-26 第五版：逐粒子指向虚拟远触点（用户第四轮反馈）

用户反馈：点左上方时右侧粒子"也是往左上，但没有那么左上"——全局统一方向
模型的固有缺陷，用户的心理模型是每个粒子朝触点飞（方向随位置渐变）。

- 方案：逐粒子主方向 = normalize(虚拟远触点 − 粒子位置)。虚拟远触点 =
  快照中心 + (含 ±18° 随机倾角的触点方向单位向量) × 1.1 × 快照对角线。
  纯逐粒子指向真实触点会在触点贴近快照（点按钮）时两侧对冲成漏斗；推远
  到对角线尺度后方向渐变平滑、无对冲。VIRTUAL_TOUCH_FACTOR 越小汇聚感
  越强，可调。
- radial 汇聚项功能被吸收，删除（shader 少一个 uniform，运动构成简化为
  主方向 + 流场 + 抖动三项）。
- Spec：driftDirX/Y、radialPushPx → virtualTouchXPx/virtualTouchYPx。
- 蓝本第五轮：touch_upper_left 场景确认右下角后碎的粒子沿更偏水平的路径
  汇入左上主流；按钮场景无退化、无漏斗收束。
- 发布 `202608260646`。

## 2026-08-26 第六版：方向整形去混沌（用户第五轮反馈）

用户反馈：粒子"往四面八方都移动"、太混沌太随机。根因：第三轮为消宽度
截断感做的两项加强（SWIRL 1.0、flowEase pow0.8 抢跑）使粒子激活初期被
无方向偏好的旋涡场主导。修正三项：

- SWIRL 1.0 → 0.6：流场从驱动力降回扰动量级；
- flowEase pow(tl,0.8) → pow(tl,1.15)：早期运动以主方向为主，缕状摆动
  中后期才加入；
- **流场方向整形**（新增）：flowShaped = 垂直主方向的横摆分量（全保留，
  烟缕与云宽靠它）+ 顺向分量 × 0.4，逆主方向分量剔除——没有粒子往回跑，
  整团烟保持朝触点的一致流动。
- 蓝本第六轮验证：按钮与左上两场景整团流向清晰一致，边缘羽化、浓淡
  非均匀、方向随位置渐变均保留，未回退到第一版的均匀幕布感。
- 发布 `202608260655`。

## 2026-08-26 第七版：静止层原图 + 低频弯曲场（用户第六轮反馈）

两项反馈：

1. **动画瞬间内容变模糊**：原理性缺陷——"静止粒子拼回原图"本质是按
   cellPx（约 4px）网格的重采样点阵，文字笔画 2–3px 直接糊掉，GL 接管
   瞬间从 ImageView 逐像素原图突变为点阵。修复：新增**静止层 pass**——
   快照矩形 quad 以逐像素原图绘制，fragment 按与粒子完全同款的
   cell/PCG hash/波前公式逐格 discard（擦除边界与粒子激活精确对齐）；
   粒子 vertex 对未激活粒子早退移出裁剪（省 fill 与纹理采样）。动画全程
   未消散区域与真实弹窗逐像素一致——华为效果第 2 帧左半文字清晰可读的
   真实结构正是如此。
2. **太规整、像线性 warp**：虚拟远触点的方向场是线性收敛场，整团近似
   线性变形，两侧边界是向远点收敛的直线。修复：**低频弯曲场**——
   bend = (大尺度 vnoise(basePx/216dp) − 0.5) × 1.4 rad + 逐粒子 ±0.075，
   乘 smoothstep(tl) 随时间平滑增长；主方向旋转 bend（起飞朝触点、随后
   弯出螺线弧），流场方向整形改以 dirBent 为参照。空间连贯的大尺度弯
   （左右区域弯向不同）打破对称直边，不回到第五轮的混沌。
- 蓝本第七轮验证（按钮 + 左上两场景）：未消散文字全程清晰；云上下边缘
  不再平行、尾部弯出弧形垂尾；整体方向感保留。
- 发布 `202608260722`。

## 2026-08-26 第八版：Genie 漏斗收拢（用户第七轮反馈）

用户点名 macOS 窗口收进 Dock 的 Genie 动画——播放中窗口上下宽度明显不一致，
希望消散云有同类形变。

- 实现：pinch 位移项 = −(粒子相对"过触点、沿主方向 dir 的轴线"的横向偏移)
  × PINCH × ease，直接加进 drift。触点即"漏斗口"，语义与 Dock 图标位置
  对应。无需新的时序机制——既有波前天然配合：近触点侧先激活、飞行久、
  横向收得多；远侧后激活仍保持全宽，整团自成锥形。
- PINCH = 0.55（1 = 完全收拢到轴线），是收拢强度的单一旋钮。
- 蓝本第八轮验证：touch_below 上宽下窄漏斗 + 底部中央收束尾；
  touch_upper_left 斜向锥形、左上先导流收成窄尖。弧线弯曲、烟缕、浓淡
  等既有特性不受影响。
- 发布 `202608260731`。

## 2026-08-26 第九版：PINCH 0.91（用户指定）

- 用户看过 0.55 后指定调大到 0.91。蓝本确认漏斗嘴更细更尖、无挤线退化。
- 发布 `202608260738`。

## 2026-08-26 第十版：不规则锋线 + 即刻起沙（用户第九轮反馈）

用户：PINCH 调 0.96；"还没移动的粒子组成的 dialog 太规整、有点怪"，要求
对照华为原效果细节优化。重读华为帧 2 定位两处差异：

1. **锋线形态**：华为的溶解边界是弯曲参差的不规则线（纸张烧蚀的火线），
   我们是从触点扩散的光滑圆弧（逐格 jitter 只是弧线上撒盐，几何仍完美）。
   修复：delay += (vnoise(basePx / (NOISE_SCALE×0.6)) − 0.5) × WAVE_WARP
   (0.14s)。静止层 fragment 加同款函数与公式，保持擦除对齐；
   TOTAL_DURATION 补 + WARP/2。
2. **"点阵化但原地不动"的假 dialog 带**（用户主诉的直接来源）：粒子激活
   后 ease 慢启动（位移≈0）× fade 前段满值 × 方点无缝拼图 = 锋线后跟着
   一条已粒子化却静止的带子，方点网格规整而怪异。华为的粒子一旦脱离实体
   立即离位。修复三点：ease += KICK(0.02) × smoothstep(0, 0.06, tl)（几帧
   内滑出约 4dp）；fade 起点 0.15 → 0.02（激活即衰减）；vActivation
   0.12 → 0.05（几乎立即圆点化）。
- PINCH 0.91 → 0.96（用户指定）。
- 蓝本第十轮：残留实体轮廓呈咬缺状、碎化区直接是离位散沙、清晰文字区不受
  影响。蓝本删除旧模型 A/B 渲染（锋线扭曲依赖 vnoise，共享 shader 块不再
  兼容；对比使命已完成）。
- 发布 `202608260756`。

## 2026-08-26 第十一版：pinch 双层调制（用户第十轮反馈）

用户：点下方 dismiss 时粒子群两侧边缘的倾斜"很平整，像一把刀切下去"。

- 根因：pinch 是**均匀线性横向缩放**——收缩系数只依赖 tl，对全体粒子
  一致，快照左右直边的粒子收缩后仍然共线，云的侧边 = 一条随时间变陡的
  直线；PINCH 提到 0.96 后该效应主导位移，直边极明显。
- 修复：收拢量双层调制。pinchMod = (0.55 + 0.9 × vnoise(basePx / 84dp))
  ×(0.75 + 0.5 × h3)——大尺度空间波浪（区段收多收少，侧边起伏）+ 逐粒子
  毛糙羽化；均值 ≈1 保持用户指定的漏斗力度。pinchAmount 封顶 0.98 防止
  高调制值过冲穿过中轴。
- 蓝本第十一轮（touch_below）：左右轮廓起伏不对称、外缘飘稀疏粒子羽、
  中央收束尾保留。
- 发布 `202608260809`。

## 2026-08-26 第十二版：大小/寿命/疏密不均（用户第十一轮反馈）

用户："还是太规整，加大噪声和随机"；点出华为的粒子"大小不一样或不同区域
疏密/分布不一样，更灵动轻盈"。一揽子去规整化：

- 新增第 4/5 随机数（h4/h5 = 黄金比组合，PCG 30bit 已用满）；
- **尺寸不均**：sizeMod = (0.7+0.6·vnoise(60dp)) × (0.55+0.95·h4²)——区域
  粗细差 × 逐粒子平方偏斜（多数小、偶有大颗粒）。静止层接管拼图后粒子
  尺寸已无无缝约束，可自由放开；
- **寿命不均**：lifeMod ∈ [0.55, 1.25]（区域+逐粒子各半）——疏密随时间
  演化不均：有的区域早稀、有的成余缕。TOTAL_DURATION 按 ×1.25 计，尾段
  +0.15s；
- **浓淡加强**：density 0.55–1.0 → 0.4–1.0；
- 湍流 0.8→1.4 cell 且幅度 ×(0.5+h4)；SWIRL 0.6→0.75（方向整形兜底不
  混沌）；WAVE_WARP 0.14→0.18；DELAY_JITTER 0.10→0.12；bend 主项
  1.4→1.6、逐粒子 0.15→0.3；riseMod 与 speedJitter 方差均扩大。
- 蓝本第十二轮（touch_below）：起沙区颗粒大小混杂、云体浓淡斑驳（浓白/
  灰/近透明稀纱共存）、边缘破碎，t=0.3 帧质感已非常接近华为参考。
- 发布 `202608260833`。

## 2026-08-26 第十三版：整数 hash + 外扩羽流（用户第十二轮反馈）

用户报告**蓝本与真机表现不一致**（蓝本边缘不规整、真机仍规整）——关键
线索。定位根因：

- **sin-hash 在移动 GPU 退化**：hash21 = fract(sin(dot(p, ...)) × 43758)
  的 dot 输出达 1e3 弧度量级；桌面 GL 的 sin 有精确参数缩减，移动 GPU
  （Adreno/Mali）为低阶多项式近似，大参数严重失真 → vnoise 质量塌陷 →
  锋线扭曲、pinch 双层调制、尺寸/寿命/浓淡等**全部下游调制在真机上
  大打折扣**。第七至十二轮蓝本验证的"去规整"效果，真机从未完整呈现。
- 修复：hash21 换整数 hash（uvec2 乘大素数异或再乘），粒子层与静止层
  同步替换。跨平台逐位一致，蓝本从此忠实预测真机。
- 教训已有 memory（cross-language-degenerate-guards）：双端不一致时先
  比对两端数学实现；本次新变体是 GPU 内建函数精度差异。
- **外扩羽流**（用户要求放开云宽）：flare = 归一化(vnoise(basePx/96dp)
  − 0.55)，约 45% 区段 > 0；flareDrift = 横向向外 × flare × 90dp ×
  flowEase × (0.5+h5)；外扩区 pinchAmount × (1 − 0.7·flare)。主体收束
  （漏斗）+ 边缘流苏外逸并存，云宽可超原 Dialog 约 90dp。
- 蓝本第十三轮（touch_below）：云宽超出卡片轮廓、右上羽流明显越界、
  中部收束尾保留。
- 发布 `202608260845`。真机若与蓝本仍有差异需继续反馈（sin 假说的最终
  验证依赖本版真机表现）。

## 2026-08-26 铺开全部 Dialog（用户认可第十三版）

用户对第十三版观感认可（"感觉还行"——整数 hash 修复后真机首次完整呈现
噪声效果），要求扩展到颜色更丰富的其他 Dialog。

- BaseDialogFragment.useParticleDismiss() 默认 false → true；
  AlertDialogFragment 的样板 override 删除。全部 28 个 Dialog 生效。
- 已核实无子类 override onCreateDialog，拦截点唯一性成立。
- 含 SurfaceView 的 Dialog 运行时自动降级；PixelCopy 路径记入
  [followups.md](followups.md)。
- 发布 `202608260857`。sin-hash 教训已写入全局 memory
  （gpu-builtin-precision-divergence）。

## 2026-08-26 第十五版：burst 先散后聚 + 收拢封顶（用户第十四轮反馈）

铺开后用户反馈：宽 Dialog 上"宽度受限更明显"；且"有一些粒子应该先往两侧
跑一跑，而不是所有粒子从一开始就直接往最终方向运动"。

- **宽 Dialog 勒窄的根因**：pinch 按比例收缩初始横向偏移，Dialog 越宽
  边缘的绝对收拢位移越大（全宽面板边缘被一口气拉向中轴），固定 90dp 的
  flare 抵不过。修复：pinchVec 模长封顶 PINCH_MAX_DP(240dp)。
- **两段式运动（burst）**：burstDir = lateralDir（按所在侧向外）+ 0.35
  随机单位向量（中轴附近纯随机乱散）；幅度 = BURST_DP(55) × (0.25+1.1h4)
  逐粒子强随机 × (0.6+0.8·vnoise) 区域调制；burstEase =
  smoothstep(0, 0.30, tl) 前 30% 寿命完成。关键设计：pinch 作用于**初始**
  lateral（basePx 派生），burst 的额外散开不被收拢回收——先散出去的宽度
  保持到被主流带走。
- 蓝本第十四轮：新增 make_snapshot 参数化 + 560dp 宽面板场景（视口
  1400→1960）。宽面板碎化区两侧先散、云超轮廓蓬松下沉；标准卡片
  无过散退化。
- 发布 `202608260912`。

## 2026-08-26 第十六版：方向渐变替换爆散段（用户第十五轮反馈）

用户否定 burst 三点：太散、Dialog 中部形成空区、两段式结构显急；约束
**不得加大动画时长**。

- 空洞根因：burstDir 以 lateralDir 为主——左右半粒子集体背离中轴、无人
  补位。急促根因：burst 是独立快速位移段（前 30% 寿命硬跑 50–100dp），
  有速度尖峰。
- 修复：删除 burst 位移段，改**方向插值**——moveDir 从 earlyDir（随机
  为主 + 轻微偏侧）在前 55% 寿命内平滑转到 dirBent，主位移曲线（ease）
  完全不变。轨迹成"先斜出再拐向主流"的连续弧线：无速度尖峰、总时长
  不变；早期 ease 小、散开量天然温和；中部粒子早期方向纯随机、四散又
  归拢，不塌空。earlySum/moveSum 两处 normalize 零向量保护。
- BURST_DP/burstPx/uBurstPx 全链删除；PINCH_MAX_DP 封顶保留。
- 蓝本确认：云身连续密实、中部无空洞、起始温和贴实体渐散。
- 发布 `202608260924`。

## 2026-08-26 第十七版：粒子缩小约 17%（用户第十六轮反馈）

- 用户认可方向渐变版（"还可以"），反馈"粒子稍微大了点"。尺寸曲线
  mix(cell×1.2, 0.4) → mix(cell×1.0, 0.34)，sizeMod 随机分布不动。
- 发布 `202608260929`。

## 2026-08-26 第十八版：dim 与粒子同步恢复（用户第十七轮需求）

需求：背景 dim 的恢复要有与粒子动画等长的渐变——白 Dialog 消散进白背景
时粒子不可见，dim 保持期提供暗背景衬托亮色粒子。

- 与第三版闪黑的关系：当时的叠乘发生在"window 移除路径"（WMS 对 exiting
  window 的 dim 自带淡出 × 满值补偿层）。本版在真实 dismiss **之前**
  `clearFlags(FLAG_DIM_BEHIND)`——活 window 的属性修改即时生效、无 WMS
  过渡（生态中逐帧 dim 动画均依赖此行为），overlay 的 dim 接力层同帧同
  浓度接管；window 随后移除时已无 dim，叠乘源不存在。
- dim 接力层从 onGlFirstFrame 起淡出，时长 = TOTAL_DURATION × scale
  （与粒子严格同步），插值 AccelerateInterpolator(1.3)：前慢后快，粒子
  浓密的前中段背景保暗。
- GL 失败降级：整层 fallback 淡出天然包含 dim（子 View）。
- 发布 `202608260939`。

## 2026-08-26 第十九版：先就位后揭开（用户反馈仍闪）

第十八版的"同帧交接"假设不成立：接力 dim 在 activity window 里、要等
下一次 traversal + 合成才上屏；clearFlags 与 removeViewImmediate 是同步
IPC、可早一帧生效——跨窗口渲染管线不同步，出现一帧"系统 dim 已消失、
接力层未画出"的亮空窗。

- 修复：**先就位、后揭开**。Controller.start 改为回调式
  （onOverlayShown：首个 preDraw 后经 Choreographer 下一帧确认已提交合成，
  100ms 兜底幂等 fire）；GestureAnchoredDialog 在回调中才 clearFlags +
  super.dismiss()（真实 dismiss 延迟 1–2 帧 ≈ 33ms，无感知）。
- 时序不变式：画面每一刻恰有一层等浓度暗——接力层先在 dialog window 底下
  就位（被挡、无视觉变化）→ 清 flag（底层暗层以相同浓度顶上）→ 移除
  window（无 dim 可淡出）。任何一步跨帧都亮度连续。
- 延迟期间 dialog window 原样显示充当遮蔽层（fragment 路径的 view 不会被
  提前摘除——它挂在 dialog decorView 而非 fragment container）；重入时
  直接 super.dismiss()，回调侧 runCatching + Dialog.dismiss 幂等兜底。
- GL 首帧若早于揭开 ≤50ms，粒子进度提前 ≤4%，视觉可忽略，未做起播门控。
- 发布 `202608260946`。

## 2026-08-26 第二十版：dim 全程接管（用户提议，闪烁终局方案）

第十九版"先就位后揭开"仍闪——结论：部分 ROM 的 WMS 对 dim 值变化本身
带不受控过渡动画（clearFlags 的 0.6→0 跳变可能被 WMS 插值 200ms），
"系统 dim 与应用暗层并存或交接"的任何结构都存在闪烁路径。用户提议由
应用全程接管背景暗层，定案实施：

- 主题 EverythingDoneTheme.Dialog 增加 `android:backgroundDimEnabled=false`
  ——系统 dim 从源头不存在，无需运行时 clearFlags（避免 generateLayout
  时序问题）。
- 新增 [DialogDimLayer](../../app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/DialogDimLayer.kt)：
  挂宿主 Activity DecorView（Dialog window 之下），show 时 220ms 淡入
  （浓度常量 0.6 = 平台默认），dismiss 时幂等 fadeOutAndDetach——粒子
  路径与动画等长 + Accelerate(1.3)（粒子浓密期保暗），普通路径 220ms。
- GestureAnchoredDialog 持 dimLayer（fragment onStart 注入）；dismiss
  拦截各分支分别触发对应淡出。Overlay 的 dim 层删除；"先就位后揭开"
  回调保留（保证内容层快照无缝，虽与 dim 已无关）。
- 副产物改进：Dialog 出现时背景从系统的瞬时变暗改为平滑淡入。
- 多 Dialog 叠放时各自一层，叠加变深与系统行为一致。
- 发布 `202608260955`。同轮用户提出"出现动画是否也做类似形式"的讨论
  （见对话），暂未实施。

## 2026-08-26 第二十一版：凝聚出现动画（用户选定 B 方案）+ dim 双保险

用户拍板 B（快速凝聚出现），并报 show 期 dim 比 dismiss 起点更黑——诊断
为系统 dim 疑似未被主题声明禁净、与接管暗层叠加（dismiss 拦截后系统 dim
随 window 消失、只剩单层所以变浅）。

- **dim 修复**：onStart 里运行时 `clearFlags(FLAG_DIM_BEHIND)` 双保险
  （主题生效时为 no-op），全程统一为 DIM_AMOUNT 0.6（平台默认）单层。
- **凝聚出现动画**（消散管线时间倒放）：
  - Spec：condenseFromT = 0.42 × TOTAL_DURATION（起点粒子距原位约 40dp、
    部分区域已擦除）、condenseDurationS = 0.32s；
  - Renderer：时钟分支 t = condenseFromT × (1 − elapsed/duration)，末帧
    t=0（静止层逐格显现的终点 = 逐像素完整原图）；
  - Overlay：凝聚模式无快照遮蔽层（从散开态入场）、onDone 回调经
    fireDone 幂等覆盖正常/降级/超时/detach 全部退出路径；
  - Controller.startCondense：decor 置 INVISIBLE → preDraw（布局完成）后
    抓图起播 → onDone 恢复 VISIBLE；150ms 兜底恢复；SurfaceView/尺寸/
    activity 状态检查与 dismiss 同款；
  - BaseDialogFragment.onStart 触发，与消散共用 useParticleDismiss()
    开关；重建恢复（savedInstanceState != null）不重播；
    condenseAttempted 保证每 dialog 一次。
- GL ready 前面板区域短暂空白（30–50ms，从无到散云属出现动画语义内）。
- 发布 `202608261005`。

## 2026-08-26 第二十二版：凝聚收尾先显后撤（用户报结束闪烁）

凝聚结束时闪：恢复面板（dialog window）与移除 overlay（activity window）
跨窗口管线不同步——overlay 先消失、面板未上屏的一帧空白。第三次同类
教训后总结出**跨窗口切换不变式：先就位、后切换**（切换前后内容逐像素
一致的层必须先上屏，确认后才移除旧层）：

- Overlay 凝聚模式 onGlFinished 只 fireDone、不自移除，保持显示末帧
  （与真实面板逐像素相同）；新增 release() 供宿主收尾。
- Controller onDone：decor VISIBLE → runAfterShown(decor)（preDraw +
  Choreographer 下帧 + 100ms 兜底）→ overlay.release()。
- runAfterShown 抽为公共函数，dismiss 端"先就位后揭开"改为复用。
- 至此三处跨窗口衔接（dismiss 揭开、dim 全程接管、凝聚收尾）全部遵循
  同一不变式。
- 发布 `202608261014`。

## 2026-08-26 "对话框粒子动画效果"设置项（用户需求 + 首次授权 adb 验证）

- 设置 → UI 组（Timer digit style 与 Spatial photo effect 之间）新增四档
  选择项：无 / 仅出现时 / 仅消失时 / 出现与消失时（默认）。
  Def.Meta.KEY_DIALOG_PARTICLE_ANIMATION，位编码 bit0=出现、bit1=消失；
  ChooserDialogFragment confirm 即时 putInt（不等设置页退出的
  storeConfiguration）；BaseDialogFragment companion 的
  particleAnimationMode() 在 show（凝聚触发）与 dismiss（拦截）时实时读，
  已打开的 Dialog 也按新档表现（chooser 自身成为即时预览）。
- 13 语言翻译（python 脚本按锚点批量插入，UTF-8 显式编码）。两个坑：
  it/fr 的撇号必须 `\'` 转义（AAPT 报 "Invalid unicode escape sequence"）；
  ChooserDialogFragment.setItems 签名是 MutableList<String?>。
- 真机验证（用户明确授权 adb）：
  - OPD2515（9018f404，**横屏 2520×1680**——先查 wm size 再定坐标，竖屏
    坐标全部无效曾白滚 40 次）：四档矩阵逐档截图——档3消散（粒子朝触点、
    静止层文字清晰、dim 同步）、档0双向普通过渡、档1凝聚粒子雾+普通关、
    档2普通开+从 CONFIRM 按钮起碎的消散；副标题实时更新；run-as 核对
    `<int name="dialog_particle_animation" value="3"/>`。
  - R5CW20BLNKL（三星）：screenrecord 两轮完整开关，486 帧 ffmpeg
    signalstats 逐帧 YAVG **零突变**（阈值 25 与 15 均零，最大帧间 8.3，
    范围 99.7–220.5）——黑屏/闪烁客观排除。
  - 设备事实：OPD2515 的 screenrecord 全路径 Permission denied（ROM 禁），
    录屏验收只能用三星；uiautomator dump 属性序 text 在 resource-id 前。
- 发布 `202608261050`。

## 2026-08-26 四项修复（用户四条反馈，双真机迭代验证）

1. **凝聚重做**（"从四周聚中间有空洞、太规律"）：根因是严格倒放下波前
   起点（中心）最后成形。Spec 参数化 spreadTime/delayJitter/waveWarp，
   凝聚专用 0.06/0.20/0.30（噪声主导凝实顺序）+ driftPx×0.55（就近
   飘入）+ fromT 0.55（起点近乎无、烟缕浮现）。蓝本 grid_condense 确认
   斑块状随机凝实、无中心空洞。
2. **show 后闪烁**：window 的主题 enter 动画（淡入+缩放）在首帧被藏时
   挂起、凝聚收尾切真面板瞬间才播——面板 alpha 0 起步渐入。修复：
   startCondense 成功即 setWindowAnimations(0)。三星录屏验证 show 结束
   时刻零跳变。
3. **凝固**（检查更新 loading + Toast 场景）：凝聚未收尾即 dismiss 时，
   window 移除令收尾的 preDraw 探测失效，且 View.postDelayed 兜底对
   detached view 进 RunQueue 永不执行——凝聚层带末帧永久残留。修复：
   runAfterShown 兜底改挂全局 mainHandler（必达）；dismiss 拦截先
   release condenseOverlay（Base 侧经 startCondense 的 onOverlayCreated
   回调持有引用）。
4. **组件变形**（圆形渐变方向按钮呈方形）：软件 draw 不执行子 View 的
   clipToOutline。第一次尝试 HardwareRenderer 离屏重渲染（API 29+）在
   三星实测返回**空 bitmap**（无异常）——消散全程不可见、消散开始出现
   dY≈31 的空窗跳变（对比修复前 486 帧零跳变，回归实锤）；已弃用并派
   联网调研代理查根因（结论待回）。改用 **PixelCopy.request(window)**：
   窗口合成结果逐像素拷贝、机制性保真；start() 改异步（PixelCopy 回调
   后建 overlay，复用"先就位后揭开"延迟链，失败路径 fire onOverlayShown
   放行真实 dismiss）。凝聚场景 window 首帧无内容不能 PixelCopy，暂回
   软件 draw（瑕疵仅凝实末帧 <100ms 可见）。
- 三星复测：688 帧逐帧亮度零跳变（max dY 10.2）；消散粒子云恢复（含
  颜色继承）；凝聚急停无残留。
- 发布 `202608261131`。

## 2026-08-26 调研结论落地：凝聚快照升级加固版硬件渲染

联网调研代理（AOSP ScrollCaptureViewSupport / androidx
CanvasBufferedRenderer / 官方 HardwareRenderer+ImageReader 样例）结论：

- 跨 renderer 引用 RenderNode 合法（AOSP 长截图同款技术），但**可见 view
  走"引用既有 displayList"分支、首帧前 INVISIBLE 走"全新录制"分支**——
  精确解释三星"消散空图、凝聚正常"的分布；空图三嫌疑：syncAndDraw 返回
  码未检查（静默不产帧）、acquireLatestImage 拿错缓冲、**无 GPU 完成
  栅栏**（可见 dialog 自身在渲染、GPU 忙时读到未写完的缓冲）。
- 消散用 PixelCopy 是正确终态（调研背书）；凝聚场景 PixelCopy 不可用
  （ERROR_SOURCE_NO_DATA），离屏硬件渲染是唯一保真路径且恰在其甜区。
- 落地：captureViaHardwareRenderer 加固版（返回码校验、acquireNextImage、
  API 33+ image.fence.await、setOpaque(false)、光源 geometry/alpha、
  官方释放顺序），失败回退软件 draw；PixelCopy 补 srcRect
  （getLocationInWindow）。凝聚的圆按钮保真补齐，全链路保真统一。
- 三星复核：257 帧零跳变（max dY 9.7）。调研报告全文见对话；方案要点
  沉淀至全局 memory（android-view-snapshot-fidelity）。
- 发布 `202608261146`。

## 2026-08-26 回归修复：不可变位图 / dismiss 重入 / PixelCopy 超时（用户报障）

用户报 1146 两回归：凝聚完全不播；按钮 dismiss 无消散（三星上弹窗甚至
卡住不关）。教训：1146 的复核只验了"亮度平滑"没验"动画在播"——凝聚
失败的兜底路径同样平滑，回归漏网。探针（Log.i + externalFilesDir 文件
双通道，OPD2515 一轮命中）：

1. **凝聚不播**：`copy(ARGB_8888, false)` 产出**不可变**位图，
   applyRoundedCornerMask 的 `Canvas(bitmap)` 抛
   "Immutable bitmap passed to Canvas constructor" → 外层 catch → 快照
   null → restore 兜底（无动画显示）。修复：isMutable = true。
2. **按钮 dismiss 无消散**：fragment 关闭链两次调 Dialog.dismiss()
   （dismissInternal 与 onDestroyView 各一次）。PixelCopy 异步化后第一次
   拦截发起抓图即 return，重入的第二次走"已尝试"分支**立即
   super.dismiss()** 移除 window——18ms 后回调 decorAttached=false，
   动画弃播。（同步抓图时代第二次重入恰好无害，异步化暴露了它。）
   修复：particleFlowPending 标志，流程接管期间重入直接 return，真实
   dismiss 统一由回调执行。
3. **三星弹窗不关**：其上 PixelCopy 回调可能不来且无第二次 dismiss 兜底
   → 永久卡住。修复：PIXEL_COPY_TIMEOUT_MS 500ms 幂等超时，放行真实
   dismiss（该次无动画但绝不卡死）。
- 双真机复核：OPD2515 探针全绿（hardware snapshot ok；PixelCopy SUCCESS
  且 decorAttached=true）+ 凝聚轻烟起点帧 + CONFIRM 起碎帧；三星消散
  恢复、2.5s 后弹窗确认关闭。探针移除后发布。
- 流程教训：动画类改动的复核必须包含"动画确实发生"的直接证据（中间帧
  目检），亮度平滑性不能替代。
- 发布 `202608261221`。

## 2026-08-26 每次动画图案随机化（用户需求"出现动画别每次一样"）

诊断：动画的全部"随机性"来自确定性来源——逐粒子 PCG hash 的输入是
gl_VertexID、噪声场的输入是 basePx，同一弹窗在相同位置尺寸下出现/消失
的斑块图案、烟缕走向、凝实顺序每次完全复现；唯一逐次变量 ±18° 倾斜在
凝聚的小位移下不可辨。

- 方案：每场动画生成双随机种子——uNoiseSeed（vec2，vnoise 内部
  `p += uNoiseSeed` 平移噪声域，锋线扭曲/pinch 调制/尺寸/寿命/浓淡/
  流场全部下游图案随之全新）+ uHashSeed（uint，异或进 PCG 输入，逐粒子
  延迟/方向/大小重洗）。**静止层与粒子层共用同一组种子**（Spec 字段
  noiseSeedX/Y、hashSeed，Controller 两处组装 Math.random 生成）——
  擦除衔接的对齐前提是两个 program 的 hash/噪声逐位一致，种子必须同源。
- 静止层 PCG 首步 `(id ^ uHashSeed) * 747796405u + 2891336453u` 与粒子层
  数学等价。蓝本 render_frames.py 同步加 uniform（固定 0 值，保持调参
  对比的可复现性）。
- 三星验证：消散两轮同触点中间帧图案明显不同（一次右上孤立消散斑、一次
  中上大片）；凝聚 320ms 撞帧不可行（0s 太早 0.4s 太晚），改 screenrecord
  两轮 + 逐帧提取 + 与末帧 MAD 曲线对齐进度，同进度帧对（f034/f042）
  凝实顺序与斑块分布完全不同、擦除衔接无错位。OPPO 冒烟：Adreno 上新
  uniform 编译正常、消散照常播放（跨 GPU 编译回归排除）。
- 发布 `202608261240`。

## 2026-08-26 凝聚收尾闪烁：alpha 隐藏替代 window INVISIBLE（用户报障）

用户报改记事颜色 bottomSheet 上"凝聚结束后面板消失一瞬再出现"。

- 根因链：凝聚以 `decor.visibility = INVISIBLE` 隐藏首帧 → 窗口从未
  显示过 → WMS 把窗口 enter 动画挂起到恢复 VISIBLE 的瞬间才播。该
  bottomSheet 的 enter 是 `bottom_panel_slide_in`（190ms、
  fromYDelta=100%p）——恢复瞬间面板从屏外滑入，而 runAfterShown 的
  preDraw + Choreographer 只能确认 app 侧帧提交、感知不到 WMS 侧的
  窗口动画，overlay 末帧撤走时面板还在屏外。第二十一版的防护
  `setWindowAnimations(0)` 防不住：子类 onStart 在 super **之后**
  `setWindowAnimations(R.style.EverythingDoneAnimationBottomPanel)`
  重设即覆盖（ThingBackgroundEditorBottomSheet:117）。
- 修复：隐藏方式改 **`decor.alpha = 0`**。窗口全程正常显示（无 WMS
  可见性事务），enter 动画在透明期内照常播完（全透明内容上屏无痕，
  190ms < 凝聚 320ms），凝聚结束恢复 alpha 只是一帧普通属性重绘——
  上屏确认因此可靠。快照不受影响：`View.draw` 渲染的是内容，自身
  alpha 由父级/RenderNode 合成时才应用（软件与离屏硬件两条路径同理）。
- Base 侧 `if (started) setWindowAnimations(0)` 删除（不再需要），顺带
  修复档位"仅出现时"下窗口 exit 动画被凝聚吃掉的隐性回归；dismiss
  粒子拦截路径的置零保留（防真实 dismiss 的窗口移除动画与粒子叠影）。
- 三星验证：bottomSheet 凝聚两轮录屏，面板区与末帧 MAD 曲线单调收敛
  至 0、无"收敛后回升"尖峰，收尾四连帧面板全程在位（修复直接证据）；
  bottomSheet 消散与设置 chooser 凝聚回归：全帧帧间 MAD 无 >25 突跳，
  且动画中间帧确认在播（消散粒子云含色相条颜色、凝聚曲线峰值后收敛）。
  OPPO：0.25s 撞中凝聚中段（色钮半凝实、快照不透明），终态完整。
- 发布 `202608261312`。
