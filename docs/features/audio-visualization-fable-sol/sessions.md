# 会话记录 · audio-visualization-fable-sol

## 2026-07-16 用 C1 深度基线修复用户确认的中远层小尖峰

用户最终只确认 14.0 秒左上区域的两条轮廓与 112.0 秒上方中间轮廓。按单层几何反投影建立三个正样本后，逐项关闭
`AmbientSet`、Hero 高频、固定连续面高频、`DynamicWave`、二维 packet 和轨道，并比较 196/392 列。消融证明采样列、
抗锯齿和音频高频都不是共同原因；真正的共同点是锚行处于整数深度节点，`orbit_z` 跨节点时会遇到九层均值分段线性
插值的左右斜率跳变。

Python 新增纯 NumPy 等距 Fritsch–Carlson/PCHIP 深度剖面并由 GL、QPainter 共用；Android 新增零分配
`FableSolDepthBaseline` 并由 GLES、Canvas fallback 共用。四条路径都只替换 base-height 深度插值，保留完整二维波包、
Gerstner 轨道、音乐响应、HDR/SDR、196 列、4x MSAA 与光学 shader。同步撤回上一轮基于错误指标而加宽 L7/L8
`ambient_len_dp` 的修改，恢复 `84/72dp`。

指定音乐在最终 Python 代码中的三个正样本为 `0.000797 / 0.001322 / 0.000936dp⁻²`，三个对照区段也全部低于
`0.00145dp⁻²`。指定蓝色的 GL 与 QPainter 14/112 秒离屏图均复核平滑；Python 177 项测试、compileall、Android
完整 `:app:testDebugUnitTest` 与两端 diff check 通过。Python 19,012 点 PCHIP 实测约 `0.227ms/帧`，比旧线性
插值增加约 `0.186ms/帧`；Android 实现逐帧零分配。未使用 ADB。最终发布阿里云 debug `202607160253`，远端
`latest.json`、APK 大小与本地 SHA-256 已核对；SHA-256 为
`8e5507688c06501bb31f0d08100f0304333a2aa4c787e5689f8cc1530c6a6709`。此前同分钟的 `202607160252` 因发布说明只
截取了首个二级标题而立即由本版本取代，最终远端说明已确认包含定因、PCHIP 修复和验证结果。

## 2026-07-16 使用指定音乐复现中远层曲线不平滑并制作 mask

用户指出上一轮只加宽最远两层环境波并未解决实际观感，要求先用
`HOYO-MiX - 银花玉鉴逐人来 Where Tender Night Embraceth Thee.flac` 对齐视觉问题，不先改实现。诊断使用 Python
现有 `--sim-audio` 正式链，从歌曲 0 秒开始按固定 60Hz 完整推进
`RealtimeAnalyzer → FeatureMapper → Simulation → shared GL`，而非 demo、静默或裁切 seek。全曲 196.714 秒以 10Hz
扫描 L3～L8，稳定找到连续小波峰、轻度分段台阶和孤立硬拐点三类现象。

14.0 秒与 112.0 秒的 mask 直接读取同帧 196 列真实几何轮廓，沿异常 x 区间画窄带；99.3 秒与 142.7 秒另圈出两个
肉眼可见的孤立折点。112.0 秒同类问题出现时 `band_high≈0.0007`、`rel_high≈0.0099`，已足以否定“全部由当前高频
响应造成”，但在用户确认 mask 前不裁定具体修复范围。产物位于 Python 仓库
`scratch/diagnose_curve_smoothness_hoyo_20260716/`；本阶段没有修改 Python/Android 产品实现、没有构建发布或使用 ADB。

用户随后纠正：99.3 秒与 142.7 秒的两个紫圈只是两层水体正常交叉，并非单条轮廓冒尖；首版自动残差 mask 也因红紫
渐变和阈值过宽而难以核对。诊断删除这两处误判，改用 Python 配色列表第二项蓝色“深海”重新走完整音频链，并将
14.0 秒、112.0 秒分别以未标注原图/人工复核窄带并排输出。新版只标单条边界自身可见的连续小峰与轻台阶，不再标
任何层间相交点。用户最终确认的正样本只有 14.0 秒左上区域和 112.0 秒上方中间区域；其余标注全部作废。几何反投影
进一步把 14 秒的可见红线拆为 `L6 x=-150..-35dp` 与交叉后的 `L3 x=-25..+5dp`，112 秒红线为
`L7 x=+65..+150dp`。采用单层 0.5dp 等距重采样、`σ=0.75dp` 预滤波及 95% 截尾三阶空间导数后，三个正样本分别为
`0.003745 / 0.001932 / 0.001938dp⁻²`；三个用户否定区段为 `0.001038 / 0.001225 / 0.001038dp⁻²`，据此把
`0.00145dp⁻²` 作为本轮区分阈值。后续只用这三个单层区段做正回归，不再使用全层 RMS 或截图颜色边缘自动判定。

## 2026-07-16 撤销 Python 表面反射软带、扩充配色并修复远景峰群

用户最终否决表面反射软带，要求移除效果及控制项；同时要求 Python 加入指定配色、清理无效参数，并修复 Python/Android
最远两层偶发的连续小波峰。Python 已删除 QPainter/ModernGL 的 `surface_strip` 发射链、HDR excess 路由和四个对应参数，
保留独立 flow streak；Android 此前未同步该试验，因此不改既有 mode 4。

配色注册表扩展为 29 项：保留原 12 项及其索引，加入 10 个 Thing 纯色和用户指定的 7 个其余配色；每项绑定默认方向。
审查同时修复方向 3 的三处旧语义，使主体、界面肩、流光在 QPainter 与 ModernGL 都一致为“左上→右下”。参数消费者审计
删除 `pearl_shift_deg`、`crest_width_dp`、`hue_temp_deg`、`analytic_halo_strength`、`color_breath`、
`hero_punch`、`hero_punch_decay_s` 七个死参数及 punch 空状态，并把 `lighten_far` 最大值收窄到有效上限 `0.864`；
旧 JSON 预设会忽略删除键，`crest_on` 因仍控制 QPainter fallback 而保留。

连续小波峰在静默输入也能复现。当时依据静默 L7/L8 全层指标，把根因判断为 `ambient_len_dp=84/72dp` 过短，并将
Python 与 Android 同步改为 `120/132dp`；该改动确实降低了当时量到的全层高频峰谷差，但后来由指定音乐和用户确认
证明并未解决真实问题。该段结论只保留为历史记录，不再视为有效定因或验收依据。

Python 最终 175 项 unittest、compileall 与 `git diff --check` 通过；Android 完整 `:app:testDebugUnitTest`、发布所含
`:app:assembleDebug` 与 `git diff --check` 通过。未使用 ADB。阿里云 debug 版本 `202607151746` 发布成功，远端
`latest.json` 已核对，APK SHA-256 为 `c4ddbae778633689f797b37ad3c1a1830d844f8d65fb21b99fd2d981e95cc089`。

## 2026-07-16 按历史 FP16 标尺降低 Python 软带 HDR 核心

用户指出此前离屏图都是 SDR，不能用于判断 HDR 核心亮度；真 HDR 下当前表面反射软带过亮。诊断先检查参考 PNG，确认其
为不含 cICP/ICC/gAMA/sRGB/cHRM 的 8-bit RGB 截屏，无法恢复绝对峰值；随后沿 7 月 13 日发布记录与 Git 历史重建
mode 4 策略。7 月 13 日软带近层峰值为 `1.40×`、全局余量 `2.0×`，当前 `3.20×` 软带曲线与 `3.6×` 全局余量均是
7 月 14 日后引入。composer 只做统一 scRGB 缩放，不是选择性增亮软带的根因。

固定目标青蓝渐变、仅 L0 软带、4× MSAA 的同帧消融复现：当前画面峰值 `3.010×`、软带 ΔY 峰 `2.391`；只换历史
软带曲线后为 `1.214×`、ΔY `0.596`，空间支持几乎不变。另发现既有“峰值强度”只控制 SDR alpha，gain 从 1.2
降到 0.05 时 HDR ΔY 仍保留约 92%。本轮先写历史曲线、uniform 上传与 FP16 场景红测，再让 gain 以 1.2 为满额线性
缩放 HDR excess；默认 1.2 与半强度 0.6 的 HDR 增量比例约 `1:0.5`，SDR 字节不受 HDR 峰值影响。

Python 完整 180 项 unittest、compileall 与 `git diff --check` 通过。640×840、FP16、4× MSAA、含线性读回的两轮
交叉计时中，gain-aware 路径中位 `14.36/13.89ms`（约 `69.7/72.0fps`），静态 uniform 基线为
`14.52/13.88ms`；差异落在测量噪声内。Android 未修改，未使用 ADB，等待用户在 Python 真 HDR 窗口目测。

## 2026-07-16 增加 Python 软带控制组并修复关闭连续水面后的 QPainter 卡死

用户要求为 Python 动态表面反射增加开关/调参位置，并报告关闭“连续水面”后 HDR fallback 在
`_draw_flow_streaks()` 抛出 `QRadialGradient` NameError，随后出现 QPaintDevice 错误。Git 核对确认既有
`surface_strip_gain` 由 `be72b42` 引入、提交态默认 1.0；上一轮只把同一参数默认值改为 1.2，不是另一项特效。

默认参数、固定 60Hz 的真实 legacy 离屏反馈环 3/3 都在第 35 帧、`t=0.583333s` 复现用户调用链。根因是
`dd92c90` 删除局部 `QRadialGradient` import；单独恢复符号或关闭 flow streak 均可让 120 帧通过。故障注入同时证明
`_legacy_srgb()` 异常后 painter 仍为 active，QPaintDevice 是未释放绘制设备造成的级联错误，不存在模式切换重入或
GL/QPainter 同时占用。

先新增真实第 35 帧渲染、异常清理、参数默认、动态门控调参、专用面板组以及“关白带不关流光”的 GL/QPainter 红测，再恢复
`QRadialGradient` 顶层 import、给所有相关 painter 作用域加 `finally`，并把 QPainter flow streak 从软带函数拆出。
面板新增“表面反射软带”组，包含启用开关、既有峰值强度、迎光出现门槛和波峰曲率增强，自动参与 JSON 预设。

红测由 1 failure + 4 errors 转为全绿；连续 40 帧 → legacy 80 帧 → 连续 40 帧的 160 帧真实切换通过，相关 88 项测试、
完整 177 项 unittest、compileall 和 `git diff --check` 均通过。Android 未修改，未使用 ADB。

## 2026-07-16 Python 表面反射软带改为随波面开合的动态物理响应

用户选定 `100% 中性白 / surface_strip_gain=1.2`，但指出此前恢复结果在每层近似常亮，要求白带能随水流和波形变化。
固定高能大浪复现确认根因：D83 恢复的 `1.2dp` 基础宽度未被局部 alpha 门控，L0 在 9 个采样时刻的可见覆盖和最长连通段
均为 100%，约 86% 几何落在实际迎光区域之外。先写回归测试稳定复现 gain、GL 标量 alpha 和 QPainter 无 opacity 三项旧行为，
再把门控改为“主光侧有符号坡向 smoothstep × 软曲率增强”，同时作用于带宽和 alpha，并移除表面带独立 pink breath。

正式生产路径 8 帧直渲显示白带沿迎光坡连续移动、缩短、消退并在新坡重新出现；L0 平静帧可见覆盖约 21.9%，动态峰值
约 46.9%，活跃/平静 P95 亮度比约 1.71。两次独立重建的逐帧哈希完全相同，无随机闪烁。隔离增量中可见负亮度像素为 0；
SDR 峰值为 `1.0× reference white`，HDR 峰值约 `3.008×`、超白覆盖约 0.295%，低于 `3.6×` 内容余量。

抗锯齿专项 3 项通过，完整 Python unittest 171 项通过，`git diff --check` 通过。640×840、FP16、4× MSAA 的 ModernGL
完整帧中位约 11.37ms（约 87.9fps），旧整条常亮基线约 13.12ms；QPainter 中位约 29.61ms，优于旧基线 37.78ms，
但 legacy fallback 本身仍未达到 60fps。Android 未修改，未使用 ADB，等待用户对 Python 动态观感的裁决。

## 2026-07-15 指定青蓝渐变亮度复测并撤回 crest-edge 遗留

用户指出原截图目标区域比 46% 中性白候选更亮，要求改用 `#00FFF6 → #4B7ADB` 左下→右上渐变复测；同时确认
`crest edge` 是忘记撤回的独立实验，要求完整移除。诊断保持同一个 `51dp / 77px` DynamicWave 大浪相位，先单变量渲染
`60% / 72% / 84% / 100%` 中性白，再交叉比较 `84% / 100%` 与 `surface_strip_gain 1.0 / 1.2`。84% 仍偏弱，
`100% + 1.2` 的局部 sRGB 亮度提升 `max≈0.181、p95≈0.157`，最接近原截图代表性约 `0.10～0.21` 的带内亮度差；
折中建议为约 `92% + 1.2`。这些仍只存在于诊断图，没有写入正式 ModernGL/QPainter 颜色路径。

crest-edge 审计确认 Android 与共享 shader 零实现，Python 遗留则包括五个参数、五个无效 uniform 上传、front-fill 顶边
坡度/sheens/曲率载荷、错误测试合同，以及同批 `thin_glow_gain 0.38→0.55`。先恢复 intended tests，复现默认值与 front-fill
合同两项失败，再删除全部遗留并把 thin glow 恢复为 `0.38`；两项转绿。最终 `src/`、`tests/` 中零 crest-edge 引用，九项重点
测试、三个相关模块共 62 项测试、全套 167 项 unittest 均通过，`git diff --check` 通过。Android 未修改，未使用 ADB。

## 2026-07-15 Python 连续表面反射软带几何试验与颜色分档

用户确认诊断后同意先在 Python 试验。按单变量反馈环，只修改 Python 的 `clean_surface_band_width_dp(s)`，把 D86 的双门控窄带
恢复为 D83 的 `1.2～9.4dp` 连续内展几何；Android 与共享 GLSL 均未修改。标量/向量历史锚点先失败后通过，相关颜色与
QPainter 合同共 29 项 unittest 通过；共享 optical RGSS 与 4x MSAA 超采样参考两项测试通过。

新增隔离诊断脚本 `scratch/diagnose_surface_strip_restore_20260715/render_ab.py`，在同一固定 129 BPM 蓝青中高能场景渲染
关闭、D86 修改前和恢复候选，并消融 thin glow。除 mode 4 外的 optical 顶点字节哈希三路完全一致，证明没有连带改变
glint、thin glow、back shade 等实体。恢复候选相对关闭表面带没有显著负亮度像素，负/正亮度能量约 `0.000005`；SDR 峰值
严格不超过 reference white。HDR 峰值约 `1.513×`，`>1.0` 覆盖约 `0.0268%`，`>1.29` 覆盖约 `0.0082%`，仍是波峰局部，
没有把整条基础带推成超白。mode 4 顶点约 `9360`，远低于 `64000` 上限。

ModernGL 640×840、FP16、4x MSAA 的交叉计时中位约 `8.1～8.2ms`，恢复前后差异落在噪声内；QPainter 完整 fallback 本来约
`36.7ms`，恢复后约 `37.6ms`，净增约 `0.9ms`，没有数量级退化，但该 legacy 路径本来就不满足 60fps，不能据此宣称
两后端均达到 60fps。颜色单变量进一步输出 `0% / 18% / 32% / 46%` 中性白推进对照；约 `32%` 较接近历史截图，当前仍只存在于
诊断脚本，等待用户目测后再决定是否写入 ModernGL 与 QPainter。未构建或发布 Android，未使用 ADB。

用户指出首轮联系图水面过于平静，无法判断目标。量化确认首轮所谓“中高能”场景只有慢变 `hero` 背景，前景峰谷约 `15.6px`，
并没有真实音头波包；问题在反馈环而非材质参数。诊断改为走正式 `DynamicWave` 注入路径，固定注入宽 `320dp`、幅 `60dp` 的前景
宽波包并选取稳定相位，前景峰谷达到约 `51dp / 77px`，脚本增加 `<45dp` 直接失败的浪幅门槛。新场景下候选相对关闭表面带的
负亮度仍为零，SDR 峰值仍不超过 reference white；HDR 峰值约 `3.063×`，`>1.0` 覆盖约 `0.329%`、`>2.0` 覆盖约
`0.154%`，仍低于 `3.6×` 内容余量上限。大浪原尺寸裁剪现已能清楚区分 `0% / 18% / 32% / 46%` 偏白档位。

## 2026-07-15 识别 7 月 13 日截图中的连续表面反射软带

用户先用遮罩确认目标是最前层波浪上边界下方、沿迎光坡向水内展开的乳白—浅青柔光带，并要求
先只说明效果身份，不直接实现。Android 历史代码、Python 同步记录与 7 月 13 日 21:26～21:49
Debug 时间线交叉确认：主体是 `surface_strip` / `surfaceBand`，即 optical mode 4 的连续表面
反射软带；旧 D83 公式在近层保留 `1.2dp` 基础宽度、迎光波峰最大约 `9.4dp`，从轮廓下方
`0.2dp` 单侧展开并使用半正弦软剖面。薄峰透射和正向纵向受光只在局部叠加，不是主体；7 月 15 日
删除的层内 `continuous sheen` 也不是该效果。

主弱化来自 7 月 14 日 D86：Android `9f8de815` 与 Python `655b159` 将表面带改为迎光和波峰
双门控、未命中宽度为 0、近层最大约 `3dp`；7 月 15 日又把偏白环境混色改为当前位置水色的小幅
提亮，所以当前并未删除该 pass，但视觉上接近消失。截图里的脏暗块属于当时独立的
`relativeLongitudinalLight` 正负宽域明暗链；恢复目标不需要恢复 `blackMix`、灰黑阴影、微法线
暗纹、深度散射或天空反射。本轮没有修改 Android/Python 实现、没有构建、发布或使用 ADB，等待
用户确认诊断与后续恢复方向。

## 2026-07-15 光学实体形状 RGSS 超采样并裁定亮 glint 保持锐利

用户在 4x MSAA（D140）真机上确认波浪界线好多了，但发现 glint 附近仍有锯齿，担心其它特效缺少抗锯齿。
Python 离屏诊断把残余分成两类。其一是真实缺口：glint/streak/surface reflection/halo/transmission 的
形状是 `optical.frag` 里 `smoothstep`/`sin` 逐像素程序化算出来的，MSAA 只多采样几何覆盖、不重算片元
着色，所以完全不抗这类形状边缘的锯齿。其二经隔离确认**不是缺少抗锯齿**：准备态 SDR 反射很淡、平滑
无锯齿，只有录音态 HDR 核心变亮（峰值约 `2.3× reference white`）才出现台阶——薄亮 glint 线性梯度陡，
SDR 可见边本质亚像素宽，clip 又在着色下游的显示/截图 SDR 映射，`avg(线性)` 再 clip 任何采样数都抗不掉，
真机 HDR 屏更柔和、只在 SDR 截图明显（用 filmic tonemap 与 headroom clip 对照均验证）。

针对第一类，共享 `optical.frag` 对光学 pass 单独做 4x 旋转网格超采样（RGSS）：覆盖抽成
`opticalCoverage(vec2 uv,...)`，单样本预乘输出抽成 `shadeOpticalSample(vec2 uv)`，`main()` 用
`dFdx/dFdy(vLocalUv)` 把四个子样本放在四个不同 x/y 子位置再平均。实体较大时导数极小、四点几乎重合，
输出与逐像素一次着色一致，不改变既定 glint 尺寸、剖面、峰值与观感；只用 GLES 3.0 核心片元导数，无需
sample shading。两端共享同一 shader，一次覆盖 Android 与 Python。针对第二类，用户裁决（AskUserQuestion）
保持 glint 锐利/亮度，接受固有 SDR 台阶，不展宽/柔化/压暗最亮核心，维持 D103～D118 的 glint 合同。

`FableSolGlShaderParityTest`、`test_gl_backend` 的 `optical.frag` 结构断言同步为 `uv.y` 并新增
`opticalCoverage`/`shadeOpticalSample`/`dFdx`/`dFdy`/4 次子样本调用的守卫。Python 167 项 unittest、
Android 全量 `:app:testDebugUnitTest` 与 `:app:assembleDebug` 通过，两仓 `git diff --check` 通过；
未使用 ADB。已发布阿里云 Debug `202607150755`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，
SHA-256 为 `c0c250d8c0df69a767f24e84ca9afae33ff17952d1da3403eab3205a3d26fa80`。远端 `latest.json` 的
`releaseNotes` 已回读，远端 SHA-256 与本地 APK 一致。发布说明见 `debug-updates/update-20260715155420.md`。
Android 真机放大观感与帧率由用户验收。

## 2026-07-15 场景 4x MSAA 消除放大后的波浪边缘锯齿

用户反馈 D139（原生 DPI + 196 列 C1 + 边界 coverage）后，Android 放大观察波浪边缘仍有锯齿与
颗粒，让 GPT 改过一版变化不大。按功能既定顺序先在 Python 离屏复现与调试：`366686` 深蓝与
`B6BF8F` 浅灰绿在 1.5 DPR 下裁剪放大九层弯曲界线，确认是清晰的横向阶梯锯齿；2x2 超采样与真实
4x MSAA 对照都能直接抹平台阶，证明是纯采样不足，`waterEdgeCoverage` 的方向盲 1px 平滑不足以
解决。诊断同时确认 D139 的原生 DPI 修复只作用于 Python——Android `uRasterScale` 恒为 1，早已按
surface 实体分辨率渲染，所以那轮对 Android 锯齿基本无效。

两端场景离屏改用 4x MSAA：几何画进多重采样 renderbuffer，再 resolve 进单采样 `sceneTexture`，
折射与 present 继续采样已 resolve 的纹理；`pre-water` 折射背景保持单采样。采样数按内部格式查询
取 `min(4, 支持值)`，与场景同格式（SDR RGBA8 / HDR RGBA16F），维持 D134 的 FP16 精度语义；不支持
或建立失败时原子回退单采样，保留 FP16→RGBA8 目标回退与不改写输出颜色空间的契约。Android 用可
移植的 `glRenderbufferStorageMultisample` + `glBlitFramebuffer` resolve（GLES3.0 保证），未依赖无
Java 绑定的 `GL_EXT_multisampled_render_to_texture`；pre-water 仍两目标各画一次环境，不做 Blit。
MSAA 只对几何覆盖多采样，主体色、界面、光学、SSS、折射、Beer 与 HDR 材质仍逐像素只计算一次；
九层界线是几何边（每组三角带远边压在其后一组之上），逐像素一次着色的 MSAA 即可修复。
`waterEdgeCoverage`、196 列 C1、glint 数量与逐层 HDR 峰值等既定合同全部保持不变。

验证：Python 离屏 SSAA 与 4x MSAA 均消除九层界线台阶、内部深水无颗粒；18 色 FP16 回归的远/近
响应比、相邻主体色差与超白覆盖同无 MSAA 基线一致；新增 Python MSE 测试确认 MSAA 比无 AA 明显更
接近超采样真值（`mse_msaa < 0.75 × mse_none`）。桌面 GPU 960×1260 FP16 完成时间由约 `10.94ms` 到
`11.10ms`（约 +1.4%）。新增 Android `场景使用同格式多重采样并在不支持时原子回退单采样` 源结构测试
与 Python 三项 MSAA 测试；`FableSolGlRenderTargetSourceTest`、`FableSolHdrPipelineSourceTest` 的两目标
折射契约断言同步更新为 MSAA 结构。Python 167 项 unittest、Android 全量 `:app:testDebugUnitTest` 与
`:app:assembleDebug` 通过，两个仓库 `git diff --check` 通过；未使用 ADB。

已发布阿里云 Debug `202607150701`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，SHA-256 为
`a4887087e5a3017e65838309ae2f01314d2804683231dafaf313ff02252013bf`。远端 `latest.json` 的完整
`releaseNotes` 已回读，远端 SHA-256 与本地 APK 完全一致。发布说明见
`debug-updates/update-20260715145903.md`。Android 真机放大观感与准备态/HDR 录音态帧率由用户验收。

## 2026-07-15 放大波浪精细化与原生像素渲染优化

用户反馈放大观察时，Python 与 Android 的波浪边缘都有明显像素颗粒和折线感，同时要求在效果不退化的
前提下继续提高帧率。诊断确认 `N_POINTS=216` 是覆盖画外区域、海绵边界和注入区域的完整物理网格，屏幕内
通常只能看到约 96～126 个物理节点；直接提高它会同时改变 `DX_DP`、CFL 上限、传播速度和既有波形，因此
没有把视觉采样问题转嫁给物理模拟。两端改为固定 196 列的显示重建网格：高度和横向坡度使用带节点导数的
Hermite C1 重建，轨道与纵深坡度使用 Catmull–Rom C1 重建；轨道被限制到 ±10dp 时同步把导数归零，避免
钳制后的边界继续产生假峰。相对 640 列参考，正常帧轮廓误差为 `p95≤0.035px、max≤0.117px`，强波压力帧
仍为 `p95≤0.186px、p99≤0.350px、max≤0.521px`。

共享 shader 新增 `uRasterScale`，只把设计空间顶点映射到实体像素，不改变微法线、渐变和材质计算；Python
的普通 GL、D3D11/scRGB HDR、Composer 与 swapchain 均按 `logical size × devicePixelRatio` 建立目标，
消除了 640×840 画面被系统拉伸到 960×1260 所产生的主要颗粒。录像继续输出固定 640×840，但直接复用原生
DPI 场景，由 GPU 在线性域缩小并编码 RGBA8；开始录制不再让窗口退回低分辨率。九条可见边界增加严格限制在
一个实体像素内的解析 coverage，并在线性域用已合成邻层颜色完成不透明覆盖，避免浅色环境泄漏成白点、粗边，
也避免 SDR 固定功能 sRGB 混合产生暗脏边；没有增加身份色描边、界面肩或新的层内光学图案。

性能侧同步删除 Python 无消费者的微表面数组，把二维相位三角函数拆成可分离一维运算，缓存三次重建权重并
使用无复制 VBO 上传；两端的七次坡度平滑改为双缓冲交换、仅最终复制一次；Android 额外缓存静态 Thing 材质和
环境 uniform，并把稳定后的 `glGetError` 改为每 129 帧抽检。Android 保留适合 tile GPU 的双目标极简环境绘制，
不照搬桌面端 FP16 framebuffer copy。Python `build_gl_frame` 中位耗时由约 4.26ms 降到约 3.33～3.55ms；
960×1260 完整 HDR 链为 `p50 14.54ms、p95 15.24ms、p99 16.19ms`，加入 GPU 录像取帧后为
`p50 15.30ms、p95 16.47ms、p99 17.32ms`，P95 仍约 60.71 FPS。

18 组真实/内置颜色的 FP16 回归保持既定局部 HDR：峰值中位数约 `1.710× reference white`；相邻层渲染色差
中位数约 `0.0419`，浅灰绿色有一处约 `0.0195`，接近既有 `0.02` 观察线，因此没有为单一颜色增加特殊分支。
Python 164 项测试与 Android 完整 `gradlew test` 均通过，`git diff --check` 通过；未使用 ADB，Android 的
原生 DPI、HDR 动态画面和帧时间仍按 D139 交由真机验收。最终已发布阿里云 Debug `202607150614`；APK
大小为 20,776,849 字节，SHA-256 为 `dd2e6ad45a91921e7596511aba52cfb268e1eb0f2aa7d78523b5161563cf05ab`。

## 2026-07-15 双仓提交前收口

按用户要求对 Android 与 Python 工作区进行逐文件审计，为本轮长周期视觉迭代建立成对提交边界。
Android 只纳入 FableSol 主代码、测试、共享 GLSL、功能文档、发布日志及本轮形成的工具规则；明确
排除 `Everything-Android/` 与 `tmp/`。Python 只纳入 16 个既有源码/测试改动、长期维护的 18 色
FP16/scRGB 回归工具和分层水体合同测试；明确排除录音素材、全部生成结果，以及服务于已否决
continuous-sheen 候选的一次性诊断脚本。

提交前领域文档审计发现两处陈旧表述：`CONTEXT.md` 一面禁止向白点混色，一面又以 D135 的静态
OKLab `lighten_far` 作为现行主体色板；`followups.md` 仍把 D135 的 `3/2/1/0…` 闪点收缩和 D137
微面片写成当前方案。现已把 **水层景深阶梯** 收紧为不绑定具体颜色算法的结果术语，明确当前
允许统一向白点提亮；把“随层保色界面肩”标为已退出的历史术语；并将待办统一到 D136 的
`4/4/3/3/2/2/1/1/0` glint 合同与 D138 的无层内 continuous sheen 基线。Python 提交正文明确
本轮基于并保留 Claude Opus 4.8 已实现的 D3D11 FP16/scRGB 窗口 HDR 呈现链，不把既有贡献误写为
本次新增，并按用户要求保留对应 Co-authored-by trailer。

## 2026-07-15 精确对照 HDR 前最终版与首个 HDR 版

用户认为当前版是近期最好版本，但回顾迭代感觉走了弯路，要求比较 Android 刚加入 HDR 的版本与
HDR 前最终版本。本轮只读审计锁定相邻 Debug `202607130749` 与 `202607130828`；两版没有独立
Git commit，不能用混入 SurfaceView、SDR 收敛与 HDR 后实验的 `8eb23c04…c03b4f711` 代替。

直接比较本地两个 APK 的 2014 个 ZIP 条目、五份 shader 和 DEX 反汇编后确认：九层主体配色、
全部材质/颜色策略、参数、连续水面与 `water.vert` 均相同，首次 HDR 没有改第二、第三层基色。
实际差异是 FP16/linear-scRGB 输出、半透明光学由 sRGB 编码域改为线性混合，以及录音态为 glint、
surface/crest 与 transmission 开启局部 HDR excess。首版 `darkCompensation`、中性白 tint 和
`excess/opticalAlpha` 使暗色低 alpha 光学在第 1/2 层也能整片越过 reference white，是录音后
提亮、降饱和和乳白的首要代码原因；原有 `presentation alpha 0.16→1.0` 同时发生但不是 HDR 新增。

当前版保留正确的线性 HDR 管线，已删除暗色与 inverse-alpha 补偿，缩小中性白比例、覆盖面积，
并移除 halo 与层内 continuous sheen。历史对照说明峰值本身不是根因，后续应分别冻结主体色、
线性半透明混合和 HDR excess。完整证据见
`analysis-2026-07-15-hdr-first-version-diff.md`；本轮未修改产品代码、未构建、未发布、未使用 ADB。

## 2026-07-15 按目测反馈移除层内斜短光点与 continuous sheen

用户查看 D137 联系表后指出，各层内部新增了大量斜短光点；确认它们不是边缘 glint，并要求直接
删除。代码与 `water_only` 对照一致证明这些点就是稳定微面片版 continuous sheen，而不是其它
optical 实体。新增 GPU 回归先在旧实现上稳定失败：129 BPM、适中水位、`EmptyOptics` 的四时相
中，sheen 开关最大像素差达到 `1.50098× reference white`。

随后从共享 shader 完整删除固定微面片、连续 GGX SDR/HDR 反射和配套暗斑；Android/Python 同步
删除 uniform 上传、峰值/覆盖率表以及两个 Python 面板参数。预滤波坡度继续只供折射、Beer、SSS
与 HDR transmission 使用，边缘 glint、surface/streak、透射和既有逐层峰值不变。更新后的
`full/no_glint/water_only` 动态联系表中，三路均已没有层内斜短点；只有独立 optical 路径仍在
真实轮廓附近出现高光。

最终共享 shader hash 为
`c2d7b3ccfcd435b951eedfafc4f95afbdd146730b856a9c3b6a3c661d77430f6`。18 色 FP16
离屏回归中，完整效果峰值最小/中位/最大仍为 `2.629/2.934/3.314× reference white`，说明边缘
glint 与其它独立 optical 峰值没有被削弱；`water_only` 峰值不超过 `1.000×`，去 glint 后 HDR
覆盖率中位归零，符合“不要再用另一种层内连续图案补回 HDR”的裁决。九层主体最弱相邻色差的
18 色最小/中位/最大为 `0.0210/0.0430/0.0628`，均不低于既有 `0.02` 验收线。

129 BPM 动态三联画已重渲染，确认 `full/no_glint/water_only` 均无层内斜点、扩缩光带或环形替代物。
640×840、FP16、RTX 5090 基准中，GPU 完成但不读回的中位/P95/P99 为
`12.293/12.896/13.487ms`（约 `81.35fps`）；FP16 读回为
`17.477/18.875/20.131ms`（约 `57.22fps`），删除该路径后没有性能回退。Python 160 项
unittest 与 Android 140 项 Debug JVM 测试全部通过，未使用 ADB。最终已发布阿里云 Debug
`202607150348`（versionCode 43 / 2.0.0），APK 大小
`20776849` 字节，SHA-256 为
`d64026f0da0d91d043dd51d7cdc1b9f3923c03ec37e41df37940fca8c6c6ad32`。远端
`latest.json`、完整中文说明和重新下载 APK 已回读；元数据、本地及远端 APK 的大小与哈希完全
一致。包内 `water.frag` 不含 continuous-sheen 禁止符号，折射、Beer 与 transmission 必需符号
仍在。

## 2026-07-15 定位并移除连续 GGX 扩缩光带

按用户补充反馈，把动态夹具从过度激昂调整为 129 BPM 中高能量、适中主浪：第 0 层不再抬高到
遮挡中远层，同时九层仍有足够运动用于判断。`full/no_glint/water_only/no_sheen/no_surface`
逐项消融确认，油漆感弯月光带在关闭 glint、全部 optical 和表面带后仍存在，只有关闭连续 sheen
才消失；因此正式排除 glint，根因锁定为 `water.frag` 的动态 GGX 响应窄等值线。

共享 shader 已改为稳定播种的有界各向异性微面片：空间存在与 GGX 能量分离，时间只平移坐标，
连续坐标解析导数负责抗锯齿；同源暗面改用相同空间门控，前景填充走 uniform 提前返回。Python
与 Android 都直接使用同一份 GLSL，并分别增加 GPU 集成回归和源码合同测试。16 时相最大亮连通
域由旧实现中位 `2302px`、最大 `3151px` 收到中位 `93px`、最大 `105px`；负亮度残差在
`ΔY<-0.003` 下中位为零、最大仅 `14px`，不再跨层。动态三联对照未见规则扩缩圆环或整条暗带。

当前 shader hash 为 `850455a9f8b0a486d2185b065437be59309c859d187aecdd422e03f9da60a8dd`。
最终全量测试、Android 构建、Debug 发布及远端校验继续补入本节。

## 2026-07-15 完成 D136 逐层特效恢复、同源亮暗与 HDR 双轨验收

按照最新真机反馈，保留静态 `lighten_far`、零界面肩和录音不改主体色，恢复此前已经确认的
闪点容量 `4/4/3/3/2/2/1/1/0`、出生率、长度、核心 alpha、逐层连续响应及五组 HDR 峰值。
中远层不再因修复圆环而被整体清零。闪点改为贴住真实轮廓、出生尺寸固定、向水内单调衰减的
实心短光迹；删除解析 halo、周期尺寸呼吸和 `216×129` 低频椭圆银斑。连续高光与保色暗面由
同一法线和太阳方向驱动，暗面只降低当前线性色的曝光，不混黑、灰或最终 HDR 像素。

共享 shader 同时修复三项合成错误：Beer/折射现在从未包含当前层的 `behindColor` 出发，当前层
alpha 只应用一次；体光 `mode 8` 接入实时 HDR eligibility；没有正向覆盖的第 8 层不再保留
孤立暗面。反射身份色改取当前层 `materialColor`，HDR 峰值与 coverage 改为独立控制；光学实体
使用预乘 SDR 颜色加独立 HDR excess，presentation 按设备 headroom 有界输出。Python 与 Android
GL/Canvas 同构同步，并增加逐层合同、动态出生、mode 3 剖面和 shader 合成回归。

18 色离屏验收明确区分 SDR 与 HDR：联系表 PNG 为 8-bit RGB 固定曝光图，只检查颜色、层界、
浑浊感和形状；18 份原始帧为 `(840,640,4)` FP16 线性 scRGB，全部有限。完整效果峰值在 18 色
中的最小/中位/最大值为 `2.629/2.934/3.314× reference white`，去闪点后为
`1.621/1.898/2.266×`；完整效果 `>1.0/>1.29/>2.0` 的水体 pooled 覆盖率为
`0.636%/0.346%/0.091%`，证明连续响应和小面积超白均真实存在。最弱相邻主体色差的跨色中位数
为 `0.0429`，18 色均未低于 `0.02`，界面肩宽度保持为零。

Python 使用 `everythingdone` Conda 环境完成 158 项 unittest；640×840、3.6× HDR、FP16 读回
的 72 帧基准中位 `15.82ms`、P95 `16.48ms`，约 `63.2fps`。Android 139 项 Debug JVM 测试和
`:app:assembleDebug` 通过，两个仓库 `git diff --check` 无错误；未使用 ADB。最终动态 HDR、系统
tone mapping、浅色洁净度和中远层稀疏光迹仍由用户真机验收。

已发布阿里云 Debug `202607150216`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，
SHA-256 为 `108df976f5cc8c63a69e38a4e36237c5bfe14e5acfc511b874524edff251e682`。远端
`latest.json`、完整中文说明和重新下载 APK 已回读；元数据、文件大小与哈希均与本地产物一致。
发布说明见 `debug-updates/update-20260715101350.md`。

## 2026-07-15 审计逐层特效/HDR 回归并研究高光—暗面耦合

根据最新真机反馈，对当前工作区与上一提交 `72d9c853` 做只读差异审计。确认 D135 不只恢复了
`lighten_far`：离散闪点容量从 `4/4/3/3/2/2/1/1/0` 收到 `3/2/1/0…`，连续太阳反射又乘
`.72/.49/.21/0…`，使第 3～8 层的主要连续 SDR/HDR 反射一起归零。连续银泽近层峰值也由
`2.7/2.4/2.1…` 降为 `2.3/2.1/1.9…`，随后再受相同逐层门控；`optical.frag` 同时取消
inverse-alpha 补偿并收紧 HDR mask。因此全局 headroom 虽仍为 `3.6×`，最终画面 HDR 覆盖和
可达峰值都显著降低，用户看到“除第 0～2 层外几乎无特效、HDR 变暗”与代码和离屏数据一致。

当前解析 halo 实际已经关闭。可见圆环/扩缩主要来自三部分叠加：`mode 3` 在真实轮廓处为零、
进入水体后成峰的空心单侧剖面；track 尺寸以 `0.30s` 持续追随候选半高宽；以及共享水体 shader
中的 `216×129` 移动低频 GGX patch 等值区。旧亮银斑与当前光带并非完全不同的机制；旧版本的
高峰值中性核心遮住了下层曲带，当前核心降峰、着色、收窄后暴露了塑料感明显的空间轮廓。

调研确认，高光应由满足太阳—视线反射条件的局部坡面产生，并与同一法线场的 `N·L`、Smith
masking-shadowing 和透射能量变化保持一致。正确修复不是给光带添加黑色暗环，而是恢复既定逐层
数量和 HDR 峰值，只重做形状、覆盖率、最终合成与同源亮暗：小面积高峰银白核心、保身份色肩部、
沿波峰切线的破碎短光迹、无径向尺寸呼吸，以及从当前水色保色变深的单侧宽缓暗面。详细依据与
建议消融见 `research-2026-07-15-highlight-shadow-coupling.md`。本轮未修改 Android/Python 渲染
代码、未构建、未发布，也未使用 ADB。

## 2026-07-15 恢复 lighten_far 混白并清理中远层圆环光斑

根据真机复测，撤销 D132 固定 hue 的亮向色域阶梯：该方案虽减少第二、第三层乳白，却令主体
灰暗、发脏，层级分界也弱于旧 `lighten_far`。Python 与 Android 现统一使用
`mixOklab(identityColor, white, depth01 × clamp(lighten_far, 0, 0.864))`；第 0 层保持记事色，
第 8 层最多混白 `86.4%`，纯色与渐变四停靠点同构处理。录音、HDR、mood、color breath 和
界面肩均不再改写主体色板，第二、第三层没有逐色或压暗特殊控制。

离屏消融确认过量圆环来自九层离散闪点的尺寸呼吸/解析外晕，以及连续太阳反射斑。离散闪点现
仅保留第 0～2 层，容量为 `3/2/1`，删除周期尺寸呼吸、强度尺寸耦合和解析外晕；连续太阳反射
权重收为 `.72/.49/.21/0/0/0/0/0/0`。折射与 Beer–Lambert 背景混合上限由 `.049` 收到
`.016`，保留非零介质响应而减轻第二、第三层洗灰。18 色离屏结果中，648 个停靠点逐码符合静态
混白公式；第 3～8 层 180 帧离散闪点和 `full-no_glint` 图像差均严格为零，第二、第三层跨色
中位色度保留率约为 `98.2%/96.0%`，没有整体压暗。

Python 149 项 unittest、Android 全量 Debug JVM 测试和 `:app:assembleDebug` 全部通过，两个
仓库的 `git diff --check` 均无错误；未使用 ADB。已发布阿里云 Debug `202607150057`
（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，SHA-256 为
`271573bebdac51fcbe211ba92c34c05c4f8a5bc143b407684e3fe8f2b7185945`。远端 `latest.json`、完整
中文说明和重新下载 APK 已回读，大小与哈希和本地产物一致；发布说明见
`debug-updates/update-20260715085611.md`。Android HDR 运动观感仍由用户实机验收。

## 2026-07-15 第二轮材质回归：完成连续微表面、折射吸收与双端同步

针对用户复测指出的四类问题——九层边界变弱且偶发身份色粗边、跨层光影出现直线马赛克、最近
三层以外缺少材质事件、现实海面高光与局部 HDR 覆盖不足——先在 Python 离屏链完成诊断和视觉
调试，再同步 Android。调研覆盖 Cox–Munk 海面坡度统计、GGX/Trowbridge–Reitz、height-correlated
Smith、Schlick Fresnel、逐片元导数抗锯齿、Snell 折射和 Beer–Lambert 体积吸收；资料与实现边界
记录在 `research-2026-07-15-water-material-second-pass.md`。

九层主体采用 D132：第 0 层保持 Thing 身份色，第 1～8 层沿固定 hue、最大可用 chroma 的亮向
OKLCH 色域边界前进，以端点弦长分配八条相邻 ΔE；最远端不超过 `L0+0.864×(1-L0)`，录音、
mood 与 color breath 不再改变主体。界面肩只补 8-bit 量化损失，当前 18 组夹具全部归零，不再
生成身份色粗描边。共享水体 shader 改为连续 GGX/Smith/Schlick 微表面，导数在分支前计算，
SDR 反射、SSS 与同色阴影分别受限；HDR 只开放局部银泽、背光透射和闪点 excess。

Python 与 Android GL 都增加独立 `pre-water` 与 `scene` 离屏目标，环境先写入两张纹理，水体在
scene 上绘制时只从 texture unit 1 的不可变背景取样；FP16 任一目标失败即成对回退 RGBA8。
折射率为 `1.333`，屏幕空间折射按 Snell 定律偏移；Beer–Lambert 只作用于透射分瓣，并以相对
彩度和感知明度保护浅灰绿、浅粉、浅黄等输入。连续网格从 25 行提高到 97 行，闪点纵深跨度同步
保持为每层三分之一；核心与解析光晕合并成一次 `6～32` 段曲面绘制。九层都有闪点容量，远层
微法线、SSS、连续银泽与透射不再硬截止。

最终 18 色 FP16 基线中，最弱相邻主体 ΔE 为 `0.01365`、跨颜色中位数为 `0.01973`；全部样本的
界面肩宽度为零；去掉离散闪点后仍有中位 `0.442%` 的局部超白覆盖。稳态动态帧保留 23 个闪点和
6 条流光；精确 RGB 色变换稀疏缓存加入色板失效与 65,536 色上限后，中位帧耗时 `15.52ms`
（约 `64.4fps`），P95 `18.86ms`。Python 151 项 unittest、Android 149 项 JVM 测试和
`:app:assembleDebug` 全部通过，两个共享 shader 均由 ModernGL 实际编译；未使用 ADB。Android
实机 HDR 画面、系统 tone mapping 与运动观感按用户要求留给用户最终验收。

已发布阿里云 Debug `202607141929`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，
SHA-256 为 `327b4fd21c5555b723649386a93cadcb05241d8215210a0de6236e22627536c5`。远端
`latest.json` 已回读完整中文说明；重新下载 APK 与本地产物的大小、哈希完全一致，包内共享
`water.frag`/`optical.frag` 已确认包含 GGX、独立背景折射、Beer–Lambert 与合并光晕实现。发布
说明见 `debug-updates/update-20260715032853.md`。

## 2026-07-15 完成晶莹水体完整材质链重构并同步 Android/Python

针对“第 0 层以外偏灰、乳白、浑浊、暗淡，录音开启 HDR 后第 1/2 层又整体突亮”的反馈，本轮
没有只替换九层配色，而是按 D117～D131 重构完整水体材质链。第 0 层继续承载 Thing 身份色；
第 1～8 层改为固定 hue、尽量保持绝对 chroma 的 bright-only OKLab/OKLCH 路径，八个相邻边界
分别验收，色域耗尽后的分离缺口由 `7～14dp`、零端点半正弦的随层保色界面肩补足。

录音仍是 HDR 状态入口，但 HDR gain 不再进入主体色板。连续银泽删除全域中性白基线，改为由
方向、Fresnel、坡度/波峰和低频 patch 共同门控的局部响应；反射、薄峰透射、SSS、闪点分别受
headroom 和逐层能量约束。波背暗带从当前层、当前渐变位置派生同色深色，宏观遮挡只削弱直射
亮瓣，微法线不再负向乘暗主体；合成顺序统一为
`interface → backShade → body/thin → veil → surface/streak → glint`，HDR excess 取消
inverse-alpha 预补偿。

任意 Thing 纯色与四停靠点渐变均进入同一合同：表面反射带先采样当前位置主体渐变再固定 hue
提亮，SSS 从每层最终四个停靠点派生；纯黑、近黑、纯白、近白、浅粉、浅黄、浅青、深蓝和互补
渐变均有回归。Android GLES/Canvas 与 Python ModernGL/QPainter 已同步；真实折射及
Beer–Lambert 体积吸收仍因缺少独立背景采样和可定义光程而暂缓，不再用压暗主体伪造。

Android FableSol `137` 项单元测试与 `:app:assembleDebug` 通过；Python 全量 `142` 项 unittest 通过，
共享 GLSL 已由 ModernGL 实际编译，两个仓库 `git diff --check` 均无错误。未使用 ADB。剩余工作是
在真实 HDR 设备对比准备态/录音态，重点验收近三层边界、浅色阴影洁净度、深色形体和局部超白
覆盖率。

已发布阿里云 Debug `202607141655`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，
SHA-256 为 `18fe081447db645eec7fca4f0d65915ebf84f3539f1119d53fe88698886955a1`。远端
`latest.json`、完整中文说明和重新下载 APK 均已回读；本地、远端元数据与下载文件的大小和哈希
完全一致。发布说明见 `debug-updates/update-20260715005249.md`。

## 2026-07-14 实现九层材质存在度、闪点最高质量几何并发布 Debug

按 D95～D116 完成 Android 与 Python 同构实现。近三层保持主视觉，第 3～7 层接入逐层递减的
连续坡面明暗、微法线/SDR SSS、局部反射、保色波背阴影、闪点/解析光晕及受限的薄雾/顺流银线；
第 8 层退出局部和 HDR 效果。闪点容量为 `4/4/3/3/2/2/1/1/0`，改用跨层真实候选池、太阳路径
权重、软配额和额度回流；最终遗漏审计又把 Android Canvas 回退从旧的逐层独立出生改成同一逻辑，
并补齐连续水面纵深展开。

闪点核心/光晕共用 `12～32` 段、目标段长 `≤3.2dp` 的曲率自适应边界，核心 alpha 上限为
`0.9129`，解析光晕使用 `1/1024` 发射阈值，HDR mode 3 共用实体时间 visibility。全局 HDR
headroom 提到 `3.6× reference white`，闪点核心、局部反射、连续银泽和透射使用独立逐层峰值。
共享 shader 把四组逐片元曲线移到顶点采样，对无贡献远层提前退出；静态 uniform 只在程序初始化
上传一次，deep/subsurface 身份色缓存，确认二者仍有实际消费者而非死代码。

Python 保持 `320dp×420dp`（`640×840px`），并把自适应边界、四边形装配和表面带宽度改为等价
NumPy 批处理。相同高活动基准中 CPU 网格中位耗时 `26.114→11.484ms`，ModernGL 含同步读回
`26.862→12.114ms`，QPainter `17.114→15.760ms`；最大光学顶点数优化前后同为 `9,972`，
未通过降质换性能。FP16 压测达到 `3.6` 可用 headroom、实际峰值约 `2.406× reference white`，
第 0～7 层均出现过真实候选和出生，第 8 层为零。

Android 全量单元测试与 `:app:assembleDebug` 通过，Python 115 项 unittest、共享 shader 实际编译、
跨仓数组逐项一致性、动态出生/退场、共享细分、HDR eligibility、顶点预算及 `git diff --check`
全部通过。未使用 ADB。已发布阿里云 Debug `202607141022`（versionCode 43 / 2.0.0），APK 大小
`20776849` 字节，SHA-256 为
`372676a9e97b20b942118a210c77cfe6926f97ddbc4e0b632163a8c437d0baaa`；远端元数据、完整中文说明、
重新下载 APK 与本地 APK 的大小和哈希均已核对一致。发布说明见
`debug-updates/update-20260714182154.md`。

## 2026-07-14 完成九层水体材质存在度调研与逐项裁决

审计 Android 当前九层连续表面、局部轮廓、离散实体与 HDR 路径，量化确认闪点虽然配置到中层，
但被 `depthGain=max(0,1-depth/0.42)`、候选门槛和全局竞争共同压到实际不可见。结合海面多尺度
过滤、microfacet/specular AA、Cox–Munk sunglint 与 HDR reference-white/headroom 资料，确定采用
“公共层级锚点 + 四个效果族”，而不是继续增加硬截止或让所有效果共用一个乘数。

通过逐项问答完成 D95～D116：确定公共存在度、连续表面明暗/微法线/SSS、局部表面反射、波背
阴影、薄峰透射、薄雾、顺流银线、闪点容量与出生权重、几何、`0.9129` 核心 alpha、解析光晕、
3.6× 全局 HDR headroom 及各 HDR 通道逐层峰值。闪点继续作为最高质量的流动材质；HDR mode 3
补乘现有时间 visibility，避免预补偿绕过淡出，但不重复改写 D70/D92 已解决的生命周期。第 8 层
退出局部/HDR 效果，第 7 层仅保留极低覆盖尾部；Python 宽度继续固定 `320dp×420dp`。

本轮只修改分析、偏好、决策、待办与会话文档，没有改动 Android 或 Python 渲染代码，也未使用
ADB。所有视觉参数已经裁决完成，下一步为 Android/共享 GLSL/Python 同构实现、自动化数值与动态
回归、固定帧/x 方向取样以及 Android HDR 真机验证。

## 2026-07-14 实现 Step D 背光 HDR 透射与 Step E 连续太阳碎光

按既有光照相干化计划继续实现 D/E。共享 `water.frag` 把现有朝阳 SSS 掩码拆为可复用函数，在 HDR 分支新增受 `(1-Fresnel)` 约束的身份色透射差量，近层峰值 `1.45×`、随深度归回 SDR，并把独立 mode 8 峰值压低为弱肩部；SDR 公式不变。Android 新增 `FableSolSunGlitterPolicy`，`FableSolGlOptics` 改用跨层候选池和单一出生额度，把既有闪点的出生位置偏向连续太阳路径，并沿真实深度行方向轻微展开；D70 生命周期、单点亮度、数量上限和音频映射均未改变。

同构实现已同步到 Python ModernGL 与 QPainter，Python 仍为 `320dp/640px`。新增策略、全局出生组织、深度轴几何、HDR/SDR 分支和共享 shader 守卫测试。Android `:app:testDebugUnitTest --rerun-tasks` 强制重跑 118 项全部通过，`:app:assembleDebug` 成功；Python `compileall` 与全量 111 项 unittest 通过，共享 shader 已由 ModernGL 实际编译并完成离屏渲染。GL/QPainter 的 `640×840` 固定帧均正常，未使用 adb。

已发布阿里云 Debug `202607140529`（versionCode 43 / 2.0.0），APK 大小 `20776849` 字节，SHA-256 为 `4092970efd1134750aac3a9d2ac0909a4a32b11884685090a30adac5afa10317`。远端 `latest.json` 的更新码、完整中文说明、大小和哈希已回读；重新下载的 APK 与本地 APK 完全一致，包内 `assets/fablesol/glsl/water.frag` 已确认包含 `backlitTransmissionExcess`、`uHdrTransmissionPeak` 与 `(1.0-fresnel)` 预算。

## 2026-07-14 修复 HDR 银泽网格分片与闪点突消，清理失活散射链

按 `diagnose` 流程先用 Python ModernGL 直接编译 Android 共享 shader 建立 FP16 数值基线。当前银泽
水体峰值约为 `1.17× reference white`；HDR 增量在水面网格对角线上的二阶跳变约为单元内部
`1.54` 倍，确认矩形/三角拼片感来自原始网格坡度经过强非线性 Fresnel 后暴露，而不是色阶量化。
闪点的 `intensity < 0.04` 会把前一帧仍约 `7% alpha` 的亮芯直接裁掉；两帧间隔 2 秒时，原
`0.80s` release 还会在单帧把强度乘到约 `0.082`。同时确认只有 strength 恒为 0 的
`depthScattering` 分支失活；`deep` 仍供 D91 阴影，`subsurface` 仍供日出 SSS。

Android 按 D92 修改：水面顶点由 6 项扩为 8 项，新增只供 HDR 银泽的低通坡度；原坡度与几何、
微法线和阴影不变。银泽近层目标 headroom 改为 `2.0`，响应拓宽为 `0.70` 次幂。闪点加入
`0.018～0.060` 低强度软门，track 在 `0.015` 退休前已不可感知，并把闪点单帧跟踪步长封顶为
`1/15s`。删除 `uDepthScatteringStrength`、参数、函数和两处调用，保留仍有消费者的 deep/
subsurface 颜色链。

共享 shader 定稿后一次性同步 Python ModernGL、QPainter 闪点生命周期、网格数据与测试；Python
容器仍为 320dp、输出宽度仍为 640px。复测水体峰值约 `1.28× reference white`，网格对角线/内部跳变比
降到约 `1.14`，专用坡度总变化量下降约 `43%`。Android 114 项单测与 `:app:assembleDebug`
通过，Python 105 项 unittest 通过；未使用 adb，未发布阿里云，等待真 HDR 设备目测。

## 2026-07-14 将局部保色坡面阴影同步 Android

用户确认 Python 试验方向后要求同步 Android 并发布。Android GLES 共享 `water.vert` 新增
`uMacroShadowLumaCap`，默认 `0.018`；保留 D87 正向同色提亮，背坡使用 `0.08～0.18` 的负向
相对 `N·L` 门、`0.35～0.70` 深度退出和 crest 局部性，只朝未混白身份色派生的 `deepColor`
移动，并按最终 linear RGB 亮度损失封顶。Canvas 回退同步计算 deep 目标与 crest 收敛度，且光照
策略保持逐顶点零临时数组分配。

Python ModernGL 已改为直接复用 Android 共享阴影函数，只在桌面编译时继续关闭深度散射，避免
重复注入；QPainter 保持同构。`lighten_far=0.864`、Python 320dp、深度散射默认值、微法线、HDR、
SSS 和其它局部光学均未改。Android FableSol 全量测试经 `--rerun-tasks` 重新编译通过，Python
104 项 `unittest` 全部通过，ModernGL 已实际编译并渲染新共享 shader。未使用 ADB。

Android FableSol 108 项测试与 `:app:assembleDebug` 通过。已发布阿里云 Debug
`202607140331`（versionCode 43 / 2.0.0），APK SHA-256 为
`41abcc87643b6685c3ca432df8b77edcac8eac8260e0fb91cf42e6db5fd08da4`，大小 `20776849`
字节。首次回读发现多级 `##` 使发布说明只取首节，已将子节改为 `###` 并在同一更新码重新覆盖；
最终远端 `latest.json` 包含实现、验证和真机观察全文。本地 APK、重新下载的远端 APK 与元数据
哈希和大小三方一致。

## 2026-07-14 在 Python 实现保色封顶的局部坡面阴影

用户认可干净立体阴影调研方向并要求实现。本轮只修改
`E:\projects\audioVisualizerSimulatorFable`，Python 保持 320dp，Android 产品代码、共享 shader
和阿里云版本均未修改。

新增面板参数 `macro_shadow_luma_cap=0.018`，归零精确回到 D87。ModernGL 在内存包装的
`water.vert` 中保留正向同色坡面光，再对强负向相对 `N·L` 使用 `0.08～0.18` 门、
`0.35～0.70` 深度淡出与 crest 局部性；QPainter 同构计算。阴影目标为未混白身份色派生
`deepColor`，在 linear RGB 中按最终亮度损失反解混色量，不混黑、不用微法线、不压远层。

首轮调研门控在真实三角形插值后仍有约 `133px` 连续暗段，因此按最终像素收紧。最终 `t=4s`
固定帧中，达到 `0.5%` 亮度差的最长连续段为 ModernGL `73px`、QPainter `49px`，最大 8-bit
实测损失约 `1.42%`；十个运动时间点最坏连续段 `135px`，但对应帧最大损失仅约 `0.92%`。
104 项 Python 测试全部通过，`compileall` 与 `git diff --check` 通过；RTX 5090 离屏基准中
ModernGL 默认阴影 mean/P95 约 `12.52/13.35ms`，QPainter 约 `14.76/15.84ms`。新增回归覆盖
亮度封顶、死区、深度归零、crest 局部性、远层不变、双后端路由和固定帧连续暗段上限。

## 2026-07-14 调研干净但有立体感的水体阴影

用户在 D87 只保留同色正向坡面光后仍感到水体偏平，希望重新加入更符合真实光输运、但不让水体显脏的
阴影。本轮只调研与定量诊断，未修改 Python/Android 产品代码，也未构建或发布。

PBRT、NVIDIA GPU Gems 与微软实时海水论文共同支持把环境/水体体积底色、Fresnel 反射和太阳直射项
分开；阴影应主要削弱直射太阳贡献，不能把整块水体、环境反射和透射一起乘暗。PBRT 还指出高估微表面
masking-shadowing 会产生不希望出现的暗区，与此前微法线/宽域暗化的脏灰现象一致。

在 Python 当前 `lighten_far=0.864`、默认光方位、`t=4s` 固定帧上按 X 方向计算：直接镜像 D87
负向公式会让可感知暗区连续跨约 `101～202px`；加入强背坡阈值、近中层深度衰减和 crest 局部性后，
L0～L3 可收敛到约 `61/81/82/36px`，L4 以后低于 `0.5%` 相对亮度门槛。建议下一步只在 Python
试验“受限直射光亏损”：朝身份色 `deepColor` 变化、最终线性亮度损失封顶 `1.8%`、不使用黑色、
微法线或远层压暗。完整依据与参数见 `research-2026-07-14-clean-form-shadow.md`。

## 2026-07-14 将 lighten_far 从 0.96 收到 0.864 并发布 Debug

用户在 `0.96` 对照版后要求把 `lighten_far` 改为 `0.864`。Android 与 Python 默认值已同步；
静态九层混白步进由每层约 `12%` 收到 `10.8%`，仍高于原 `0.60` 基线的 `7.5%`。其它颜色
公式、D87 坡面光、alpha、环境色、局部光学和水面几何均未修改，Python 仍保持 320dp。

Android FableSol 105 项、Python 99 项测试和 `:app:assembleDebug` 均通过。已发布阿里云 Debug
`202607140235`（versionCode 43 / 2.0.0），APK SHA-256 为
`eea03b4b477196413ca411fc8be418e522ca9b7727b225b179a118385308500f`；远端 `latest.json`
的 URL、大小、哈希和完整发布说明已回读一致。未使用 ADB。

## 2026-07-14 将 lighten_far 提到 0.96 并发布 Debug

用户希望九层水体更加分明，要求把 `lighten_far` 调到 `0.96` 进行真机对照。本轮只把 Android
与 Python 的默认值从 `0.60` 提到参数上限 `0.96`，静态混白步进由每层约 `7.5%` 增至约
`12%`；第 0 层仍保持 Thing 原色，中远层通过更大的混白阶梯拉开。D87 正向坡面光、九层
alpha、环境色、表面反射、闪点、HDR 银泽、SSS、薄峰透射和几何均未改变，Python 仍为 320dp。

Android FableSol 105 项、Python 99 项测试和 `:app:assembleDebug` 均通过。已发布阿里云 Debug
`202607140224`（versionCode 43 / 2.0.0），APK SHA-256 为
`b9150d62e264f60d19a8aad7c818ac6ad44910bb28003452cfdd3a0be6ef6db2`；远端 `latest.json`
的 URL、大小、哈希和完整发布说明已回读一致。未使用 ADB。

## 2026-07-14 恢复封顶的同色正向坡面光并发布 Debug

用户确认 D86 干净水体版本控制住宽域阴影后，水体基色因逐层平色而显得过平。固定帧先比较
1%、1.5%、2% 封顶值和多档响应，最终采用 D87：只取纵向法线相对参考法线的正向 N·L 差，
按原 RGB 同比例抬亮；响应 `0.12`、近层封顶 `1.5%`，深度权重从 `1.0` 平滑降到 `0.45`。
没有恢复 Fresnel 天空色候选、`blackMix` 或任何负向坡面压暗。

Android GL/Canvas 与 Python ModernGL/QPainter 已使用同一公式，Python 继续保持 320dp 宽度。
最终 640×840 固定帧中，第 1/2/4/5/7 层的 61px 宽域跨度约为
`1.49/1.50/1.54/1.76/1.02`，各层最小差均为 0；共享 GLSL 相对平色基线有 37,527 个像素
正向变化。诊断中还修正了 ModernGL 回归测试的上下文使用方式：两个独立上下文必须依次
创建、渲染、关闭，避免在非当前上下文上读取造成假阴性。

验证结果：Android FableSol 105 项和 Python 99 项测试全部通过，`:app:assembleDebug` 通过。
已发布阿里云 Debug `202607140217`（versionCode 43 / 2.0.0），APK SHA-256 为
`6be0ad89a80ae54342c333f5b7d15c4fdc80e2baac0165e3c1c83c114b2d4863`，远端
`latest.json` 的 URL、大小、哈希和完整发布说明均已回读核对；未使用 ADB。

## 2026-07-14 同步 Python 干净水体策略并发布 Android Debug

用户确认 Python 第一轮限制纵向受光后近中层仍有大范围阴影。固定恒色帧按 X 方向取样与逐项消融
把宽域来源收敛为 `depth_scattering` 的真实压暗、纵向长波受光的宽域抬亮，以及无波峰仍保留
`1.2dp` 基础宽度的 `surface_strip`。Python 最终版在默认全效果下把第 1/2/4/5/7 层的 61px
宽域亮度跨度压到不超过 0.06，用户随后要求同步 Android 并发布。

Android 按 D86 收口：深度散射默认归零；`water.vert` 与 Canvas 回退不再让纵向法线调制整层
填充色；表面反射改为迎光与波峰双门控、近层最大约 `3dp`，HDR eligibility 同步局部化。
`water.frag` 的 HDR 掠射青灰银泽和其它局部光学保持不变。同步收口此前诊断实验遗留的参数断言，
没有使用 ADB，也没有修改或安装到物理设备。

验证结果：Android FableSol 105 项单元测试通过，Python 共享 shader/ModernGL 6 项实际编译渲染
测试通过，`:app:assembleDebug` 通过。已发布阿里云 Debug `202607140110`（versionCode 43 / 2.0.0），
APK SHA-256 为 `ee2fcd44559f3f54a1e968cfc8767a0193e821fbbb8620fa5452ec3d0f37dbe5`，远端
`latest.json` 与完整 `releaseNotes` 已回读核对。

## 2026-07-13 Crest 调研 + 实现 Step B 补丁与 Step C（水面首次进 HDR）

Step B 真机反馈中远处灰黑更重，根因确认 deep 从已混白层色派生（乳白被加深=灰）；Step B 补丁在
`water.vert` 加 `nearShadingWeight(depth01)` 让加深/打光随水层混白衰减，近层保对比、远层交给景深阶梯
（Debug `202607131208`，好转）。重开的 Crest 调研完成：**完全印证方向、无冲突**——`lerp(body,sky,R)` 就是
统一太阳模型，我们 Fresnel/SSS 公式已与 Crest 对齐，另给 3 个正交小借鉴（深度变锐度、RMS 粗糙化、C/D
共享 Fresnel），全部记入 plan 的 Crest 节。随后实现 **Step C**：`water.frag` 新增 `grazingSheenExcess()`，
把掠射 Fresnel 天空/太阳反射在 scene-linear 录音态提成超白银泽（SDR 逐字节不变、深度衰减峰值、近中性白、
不接音频），renderer 补喂 `hdrGain`/`hdrHeadroom`，加守卫测试，107 项 0 失败（Debug `202607131220`）。

## 2026-07-13 实现 Step B：止脏 + 拉对比（打光待在身份色轴上）

Step A 真机反馈"更真实但脏"（灰青带 + 偏灰黑）。诊断为 Step A 的真法线放大了 `water.vert`
`relativeLongitudinalLight` 的掠射 Fresnel 天空反射，反射近白 `uHorizonColor` 且 SDR 钳位，身份色糊成
中性灰。Step B：① 掠射天空反射乘 `skyReflect=0.2` 压弱（灭灰青），保留全强度 N·L 漫反射与保色暗化；
② `body_light 0.36→0`；③ `depth_scattering 0.21→0.45`（几何体积顶替体光、波谷压深浪峰提亮=对比）。
那块灰青反射留给 Step C 的 HDR 变亮银。更新 3 个测试；106 项 0 失败；发布阿里云 Debug `202607131143`。

## 2026-07-13 grill 收敛"光照相干化与 HDR 存在感"计划并实现 Step A

`/grill-with-docs` 走完设计树，收敛出提升水体晶莹/流动/真实与 HDR 存在感的方向，落到
`plan-2026-07-13-light-coherence-hdr-presence.md`（定位 A 物理纪律、存在感靠面积不靠峰值、
反射+透射由水面自己在 HDR 里做、统一太阳模型；分步 A 法线统一 → B 画布对比 → C 掠射光泽 →
D 透射亮边 → E sun-glitter 组织；音频耦合 HDR / glitter 光柱 / 时间太阳 / 折射 / 泡沫延后）。

随后实现 **Step A**：诊断出打光法线 `aSlope` 原只来自二维方向场 `eta`、不含各层波形轮廓，
光不贴看得见的波走（光学高光又是逐层第三套基准）。改动全在 `FableSolContinuousSurface.kt`——
`sample()` 先合成真渲染面 `worldEta` 再从它求 `slopeX/slopeZ`；`composeLayerField` 跨层由线性改
Catmull-Rom 防层锚点坡度接缝、锚点行仍精确穿过各层轮廓；行间权重按固定 `z01[r]` 在 init 预计算
保持零分配。shader/renderer/optical 未改，`:app:assembleDebug` 通过、fablesol 单测 12/12 绿。
待真机验收"光贴着波走 + 每 3 行无横向接缝"。Python 模拟器同构（D43）列为跨仓待办。

## 2026-07-13 将 Python 模拟器首轮同步到当前 Android FableSol

用户明确 Python 保持 `320dp×420dp`，其余差异可以更新。本轮在
`E:\projects\audioVisualizerSimulatorFable` 完成首轮回同步：默认参数改为 Android 当前材质值，
移除珍珠斑与猫爪主链，颜色改为 Thing 身份色到中性白，加入全局确定性 1/f 对环境波、二维远浪
出生和闪点出生的调制；横滚扩为完整 `−180°~180°`，`0°/180°` 共用水平边界语义。

新增 ModernGL 后端，直接编译 EverythingDone 的 7 个共享 shader；Python 侧同构构建连续水面、
双深度散射、crest pinch、微法线、镜面足迹抗锯齿、朝阳 SSS，以及表面反射、体光、薄峰透射、
波背阴影、流光、闪点/解析光晕、波冠轻纱和远层羽化，并按 Android 的逐层顺序交错绘制。
默认 `auto` 优先 GL、失败回退 QPainter；present 同步 16dp 圆角和准备态 0.16/播放录音态 1.0
的 360ms 过渡，demo/无头回归保持全强度。旧 A 基线及文件输入、面板和视频导出保留。

HDR 数值链同步了线性 `RGBA16F` 场景、2.0× 上限、逐层峰值和 360ms 录音态过渡；Qt readback
单独转成 sRGB 作为 SDR 预览。实测线性场景峰值 `1.77×` SDR 白点。RTX 5090 上 640×840 完整
GL 帧（含 NumPy 网格、光学层、GPU 和 readback）均值 `13.23ms`、P95 `13.71ms`；`−180°` 到
`180°` 九档横滚扫角全部成功。新增回归后 Python 94 项 `unittest` 全部通过，`compileall`、GL/旧
A 基线无头截图和两个仓库 `git diff --check` 均通过；未使用 adb，也未修改 Android 产品代码。

## 2026-07-13 分析 Python 模拟器与当前 Android FableSol 的差异

用户希望在连续水面迁移后反向更新 `E:\projects\audioVisualizerSimulatorFable`，先要求只分析两端
当前差异。本轮以 Android 迁移提交 `f9ad7215…`、Android 当前提交 `c03b4f7…` 和 Python 当前提交
`44ad68bd…` 为边界，对照了提交历史、调用链、参数、映射、连续曲面、光学几何、GLES 材质、HDR、
容器尺寸、倾斜范围和帧调度。结论是宏观波面物理仍大体同源，主要差距来自 Android 后续统一
GLES、逐像素材质与 FP16/scRGB HDR；另有 280dp/320dp、珍珠/猫爪删除、颜色身份轴、全局 1/f 和
实时调度差异。完整结果写入 `analysis-2026-07-13-python-android-parity.md`；初步曾把同宽模式列为
候选，用户随后明确 Python 必须保持 320dp，D84 已据此修订比较口径和实施顺序。分析阶段未修改
两端产品实现。Python 80 项 `unittest` 全部通过，两个仓库 `git diff --check` 通过；未使用 adb。

## 2026-07-13 三项表层光学效果恢复到 Debug 202607130749

用户要求将表面反射、薄峰透射和闪点外围光晕重新恢复为 Debug 更新码
`202607130749` 对应的版本。本轮精确恢复表面反射近层最大约 `9.4dp`、薄峰透射
最大约 `11dp` 且 `thin_glow_gain=0.38`，以及解析光晕
`analytic_halo_strength=0.10`、长度 `1.18×`、厚度 `2.25×`、alpha 系数 `0.18`、
`exp2(-6.5*r²)` 衰减和 0.72 边界软出。

后来单独恢复的 `body_light_strength=0.36` 继续保留；HDR 管线、分层峰值、颜色归一、
更多窄闪点、远层景深阶梯和自动 SDR 回退不变。完整 106 项 JVM 单测 0 失败，
四组 GLES 程序通过 `glslangValidator` 链接，`:app:assembleDebug` 成功，APK 内 shader
内容与目标值一致，`git diff --check` 无空白错误。已发布阿里云 Debug `202607130907`，
并回读远端 `latest.json` 与 APK HTTP 头，确认更新码、URL、SHA-256 和文件大小一致。
未使用 adb；等待真机对照。

## 2026-07-13 单独收窄 HDR 薄峰透射

用户在四项宽材质恢复版 Debug `202607130840` 上，要求薄峰透射再收窄一些。
本次只把 `FableSolMaterialPolicy.thinGlowThicknessDp()` 由
`(3 + 20*signal)*sqrt(signal)` 改为 `(3 + 15*signal)*sqrt(signal)`，最大厚度从约
`23dp` 降到 `18dp`。`thin_glow_gain=0.55`、颜色、曲率/海拔门、分层范围、
HDR eligibility 与峰值不变；表面反射、外围光晕和 `body light` 也保持恢复值。

完整 106 项 JVM 单测 0 失败，四组 GLES 程序通过 `glslangValidator` 链接，
`:app:assembleDebug` 成功，`git diff --check` 通过。Canvas 诊断回退与 GLES 共用同一
几何策略。已发布阿里云 Debug `202607130849`，并回读远端 metadata、核对本地/远端
SHA-256 与文件大小。未使用 adb；待用户真机对照。

## 2026-07-13 在 HDR 上恢复去雾前四项宽材质

用户真机确认 Debug `202607130828` 已成功进入 HDR，并要求在保留 HDR 的前提下，
把表面反射、薄峰透射、闪点外围光晕恢复到第一阶段收紧前，并重新加回
`body light` 做真机对照。

现已按收紧前代码的精确值恢复：`body_light_strength=0.36`；表面反射近层最大
约 `16.7dp`；薄峰透射最大约 `23dp` 且 `thin_glow_gain=0.55`；解析光晕恢复
`analytic_halo_strength=0.21`、长度 `1.38×`、厚度 `4.2×`、alpha 系数 `0.24`、
`exp2(-4.6*r²)` 衰减和 0.82 边界软出。`crest_veil_strength=0.14`、更多窄闪点、
Thing 色到中性白的色轴、环境、阴影、远层阶梯、波形与顺流流光不变。

HDR 管线、分层峰值和能力回退不变。`body light` 与 analytic halo 的顶点
HDR eligibility 仍为零；恢复宽度后的表面反射与薄峰透射继续沿用现有条件和峰值。
相关回归已更新；完整 106 项 JVM 单测 0 失败，四组 GLES 程序通过
`glslangValidator` 链接，`:app:assembleDebug` 成功，APK 包含恢复后的 `optical.frag`。
已发布阿里云 Debug `202607130840`，并回读远端 `latest.json` 核对更新码、APK 地址、
SHA-256、大小和中文说明。未使用 adb；待用户真机对照宽材质与 HDR 叠加后的观感。

## 2026-07-13 完成第二阶段 FP16/scRGB HDR 管线

用户确认第一阶段 SDR 材质方向后要求继续 HDR。实现延续 D56～D80：API 34+
只在显示器的 HDR 状态和实时 `hdrSdrRatio` 可用时尝试 float component EGL config、
linear scRGB window surface 和 `GL_RGBA16F` 线性 scene framebuffer；任一环节不可用即在
同一 `SurfaceView` 自动回退 SDR。没有切换 Dialog Window 的 color mode，也没有因录音
状态变化重建 surface。

录音态通过 `FableSolHdrTransition` 以 0.36 秒启停局部超白增益，API 35+ 同步为
`SurfaceView` 请求最多 `2.0×` desired headroom；显示器 headroom 下降时立即收紧。近层
闪点核心分配 `1.75～2.0×`，中层递减到 `1.2～1.5×`，第 6～8 层回到 SDR；
受光窄浪峰和少量薄峰透射使用更低峰值。顺流流光、环境、光晕、轻纱、阴影和
远层不生成 HDR excess；不引入音量/onset/beat 乘数、tone mapping 或全局曝光。

新增 HDR 能力、峰值阶梯、headroom 收放、状态过渡、EGL/renderer 源码约束和光学
eligibility 回归。完整 106 项 JVM 单测 0 失败，四组 GLES 程序已通过
`glslangValidator` 链接，`:app:assembleDebug` 成功，APK 已确认包含新 HDR 着色器。
已发布阿里云 Debug `202607130828`，并回读远端 `latest.json` 核对更新码、APK 地址、
SHA-256、大小和中文说明。未使用 adb；待用户在真 HDR 设备上核对超白峰值、分层与自动回退。

## 2026-07-13 完成 HDR 前的第一阶段 SDR 材质基线

用户通过 `grill-with-docs` 逐项确认：保留远层偏白、透明与柔化形成的水层景深阶梯，首轮锁定
environment、`lighten_far`、九层 alpha、远层羽化、阴影与波形几何；“整体更亮”指局部峰值和
主观晶亮感，不要求提高 APL。实施顺序拆为两个 Debug：先验收共享 SDR 材质，再启用 HDR。

第一阶段现已取消九层独立 `body light`；把连续表面反射的最大宽度由约 16.7dp 收至 9.4dp、
薄峰透射由约 23dp 收至 11dp，并把 `thin_glow_gain` 从 0.55 调为 0.38、
`crest_veil_strength` 从 0.32 调为 0.14。解析闪点柔边强度从 0.21 调为 0.10，几何由核心厚度
约 4.2 倍收至 2.25 倍，shader 衰减同步收紧；镜面闪点容量由近层 3/中层 2 提到近层 4/中层
3，最小间距从 46dp 降到 34dp，并扩展到第 5 层，以增加窄片段而非扩大光晕。顺流流光仍保持
SDR 语义和原有几何、寿命、alpha。

固定 165°/220°/150° 派生色、约 ±6° 周期高光摆色和约 +3° 固定偏移均已移除；反射、透射与
轻纱改为沿 Thing 身份色到中性白的 OKLab 轴变化，深水与次表面只改明度/彩度、不主动改色相。
亮度呼吸、闪点出生频率、环境、远层分层、`back_shade_gain=0.80` 与声音/倾斜动画保持不变。

新增材质能量、颜色轴、深度色相与光学网格回归。完整 96 项单测 0 失败，7 个共享 GLSL 均通过
`glslangValidator`，`:app:assembleDebug` 通过；本阶段仍使用 RGBA8 SDR，未加入 FP16/scRGB 或
任何 `>1.0` 输出。已发布阿里云 Debug `202607130749`，并回读远端 `latest.json` 核对更新码、
APK 地址、SHA-256、文件大小和中文说明；等待用户真机判断通透度、层级、高光数量和 Thing 色
保持情况。

## 2026-07-13 改用 SurfaceView 承载统一 GLES 渲染器

用户在 HDR 调研中确认：既然 Android 官方将 SurfaceView 标为完整 HDR 支持、TextureView 仅为
Android T+ 有限支持，就将 FableSol 改成 SurfaceView。现已把 `WaveVisualizerFableSolGl` 从
TextureView 生命周期迁移为 `SurfaceHolder.Callback`，`FableSolEglSession` 直接接收
Surface；所有 API 26+ 继续共用同一 GLES 路径，没有增加旧系统 TextureView 分支。

为补偿 SurfaceView 在 API 34 前不支持任意 View alpha、也不具备 TextureView 复杂裁切语义，
新增 RGBA8 scene framebuffer 与 `present.frag` 最终合成 pass：保持原水体绘制不变，集中完成
准备/录音态 `0.16 ↔ 1.0` presentation alpha、16dp 圆角和预乘 alpha 输出。SurfaceView 保持在
窗口下方，普通录音文字和按钮继续覆盖其上；Canvas 诊断回退仍使用普通 View alpha。

已通过完整 91 项单测、`glslangValidator` 离线片元着色器编译和 `:app:assembleDebug`，APK 已确认
包含 `assets/fablesol/glsl/present.frag`。已按用户要求发布阿里云 Debug `202607130639`，并回读
远端 `latest.json` 核对更新码、APK 地址、SHA-256、大小和发布说明。未使用 adb；API 26～33、
34 与 35+ 的圆角漏边、层级、淡入淡出和 surface 重建仍需用户真机验收。

用户安装 `202607130639` 后确认当前真机没有问题，SurfaceView 迁移的首轮观感与交互验收通过；
该结果解除 HDR 主线的容器阻塞，但不代替后续 API 26～33、34 与 35+ 的兼容矩阵覆盖。

## 2026-07-13 微调解析光晕强度

用户要求把上一批默认值中唯一的 0.22 调为 0.21。已将 `analytic_halo_strength` 从 0.22 改为
0.21；1/f 呼吸、风梳微法线、朝阳 SSS、光晕几何与衰减公式均不改变。参数回归与 Debug
构建通过，已发布阿里云 Debug `202607130443` 并回读远端 `latest.json` 确认；未使用 adb。

## 2026-07-13 合批接入四项持续质感优化

用户明确要求同时实现 1/f 呼吸、微法线风梳纹理、朝阳 SSS 与解析光晕。现已增加确定性无状态
1/f 策略：同一慢呼吸进入一维/二维环境波幅，另以独立种子调制稀有波包间隔和仅新生闪点的频率；
已有闪点身份与已成形音频浪不受逐帧重塑。水体片元 shader 增加三倍频 IQ 风格解析导数值噪声，
沿水面航向形成风梳结构，并按远近行足迹通过既有镜面 AA 强度带限。朝阳 SSS 使用 6 次方向瓣、
浪峰收拢和近层掩码，不读取音频瞬态。镜面闪点先绘制曲面内侧数学衰减光晕，再绘制原双色亮芯。
四项均可独立归零。新增慢变化边界、零强度、shader 契约、光晕生成和容量回归；FableSol
专项 87 项单测与 Debug 构建通过，APK 已确认包含新版 GLSL。已发布阿里云 Debug
`202607130406`，远端 `latest.json` 的版本号、APK 地址和 SHA-256 已回读确认；未使用 adb，等待
真机观感与倾斜稳定性验证。

## 2026-07-13 微调双色散射并开始解析镜面抗锯齿

用户要求把双色深度散射轻量档从 0.22 改为 0.21，并继续下一项；其余散射规则保持不变。
代码审计确认当前镜面闪点由 `FableSolGlOptics` 的坡度高斯选峰驱动，没有可直接调整的 shader
高光指数。现已在 `FableSolOpticalWaveSet` 根据实际列足迹带限不足 2～4 样本/波的毛细分量，
把移除的解析坡度方差卷积回闪点高斯宽度并做积分能量归一，曲率方差以统计 RMS 补回；未过滤
坡度仍供体积光等既有路径使用。
新增独立 `specular_aa_strength`，0 可严格恢复原样。新增足迹、方差、展宽、零强度和实际消费者
回归；84 项单测与 Debug 构建通过，等待发布后真机验证。
已发布阿里云 Debug `202607130347`。

## 2026-07-13 开始 Stage 2-2 双色深度散射

用户确认高光压缩回退版正常并要求继续下一项。新增同一记事色派生的 deep/subsurface 色板，
以 OKLab 明度/彩度调整和不超过 8°/4° 的有界色相偏移保持颜色身份，超出 sRGB 时沿色相方向
压缩彩度。连续水面顶点增加由 Gerstner 横向轨道收拢计算的 `crestPinch`；`water.vert` 用
远近视角、浪峰收拢和固定光向控制双色混合，独立强度为 0.22。未改变高光、光学覆盖、运动或
音频映射，也未新增离屏 pass。新增色板、色相边界、收拢掩码、网格布局和 shader 契约回归；
79 项单测与 Debug 构建通过，等待发布后真机单项验收。
已发布阿里云 Debug `202607130337`。

## 2026-07-13 整项回退 Stage 2-1 高光压缩

用户真机复测后认为高光不够亮、观感不如 Stage 1，要求回退。已删除 PBR Neutral 色调映射
shader、RGBA8 离屏 framebuffer/纹理、最终合成 pass、独立参数和全部专项回归，恢复 Stage 1
默认 framebuffer 直接输出路径；Stage 1 的颜色、光照、光学和 EGL 生命周期修复保持不变。
本轮只做回退，不引入下一项质感优化。73 项单测与 Debug 构建通过，APK 已确认不再包含
`tone_map.frag`；完成发布后等待真机确认高光亮度恢复。
已发布阿里云 Debug `202607130324`。

## 2026-07-13 Stage 1 观感验收通过并开始高光压缩

用户确认修复迁移差异后 GLES 与 Canvas 观感一致，并要求继续质感优化。Stage 1 视觉复刻由此通过；
Stage 2 按既定顺序从色相保持高光压缩开始，本轮不同时改变散射、微法线、光学实体或运动。新增
RGBA8 离屏最终合成与 `tone_map.frag`；在线性 Rec.709 中只处理最大通道超过 0.76 的高光，普通
中间色和低亮饱和记事色严格恒等。曲线采用 Khronos PBR Neutral 官方 F90/Ks/Kd，并在高光区补回
F90 后压缩，再转回 sRGB；`pbr_neutral_strength` 为独立开关。新增数学、shader 契约和 EGL
重建后离屏目标重新分配回归；79 项单测与 Debug 构建通过，APK 已确认包含新 shader。
已发布阿里云 Debug `202607130145`，等待真机单项验收。

## 2026-07-13 核验并修复 GLES 迁移问题清单

用户要求撤销表面软带/远层羽化的 GLES 感知缩放，恢复 Canvas 参数，并独立核验 A1、B1～B9。
已将表面软带宽度与 alpha、远层羽化 alpha 全部恢复为 Canvas 的 100%。A1 经实际生命周期状态
回归确认：EBO 删除后旧列数未失效，同尺寸 EGL 重建会跳过索引上传；回归先失败后修复。

颜色与光学审计确认 B1/B2/B3/B5/B6/B7/B8 准确，B4 的抖动覆盖/双倍幅度准确但 alpha 论述在
当前不透明目标上无实际影响，B9 的参数与颜色退化准确但完整椭圆受既有水面边界裁决限制。现已恢复
Canvas 相对纵向受光、每层四停靠点渐变、天空/前景抖动、分离 alpha 混合、九层体积光带、近三层
波冠轻纱、Fresnel 闪点、1/f 呼吸、闪点增益/阈值/空气透视及弯曲双色彩晕。新增 EGL 状态、shader
契约、光学层范围、实际顶点 alpha/容量等回归；73 项单测与 Debug 构建通过；已发布阿里云 Debug
`202607130124`，等待真机复测。

## 2026-07-13 继续降低最远两层羽化亮度

用户要求继续降低远层羽化亮度。保持其宽度、颜色和其他所有光效不变，仅将 GLES 羽化 alpha
由原 Canvas 参数的 30% 再减半到 15%。实际顶点 alpha 回归先失败后转绿，完整单测与 Debug
构建通过；已发布阿里云 Debug `202607122336`，等待真机复测。

## 2026-07-13 分离表面软带与最远两层羽化校准

用户复测 `202607122313` 后要求表面软带宽度回调但更加透明，并指出最远两层上方的白带此前
几乎没有变化。代码路径核对确认第 0～6 层使用表面软带，第 7、8 层使用独立远层羽化，前两轮
只调前者，因此最远两层保持明显。新增两条真实网格回归并先观察到共同失败；随后将表面软带
调整为 Canvas 宽度的 62%、峰值透明度的 34%，将远层羽化 alpha 缩放到原值的 30% 而保持其
柔化宽度。两条回归转绿，完整单测与 Debug 构建通过；已发布阿里云 Debug `202607122331`，
等待真机复测。

## 2026-07-13 第二次收窄并淡化 GLES 表面软带

用户复测 `202607121602` 后要求表面软带继续变窄、变透明。保持颜色和其余光效不变，将 GLES
表面软带相对 Canvas 的峰值透明度从 68% 降至 48%、宽度从 72% 降至 52%，即相对上一版分别
再降低约 29% 和 28%。真实网格回归先按新目标失败，修改常量后转绿；完整单测与 Debug 构建通过；
已发布阿里云 Debug `202607122313`，等待真机复测。

## 2026-07-13 对 GLES 表面软带增加独立感知补偿

用户复测 `202607121554` 后确认白边仍比旧 Canvas 更厚、更白。源码差分进一步证明表面软带的
带宽公式、颜色、基础 alpha 与半正弦剖面均已和 Canvas 一致，剩余差异属于真机 GLES 栅格化与
混合后的感知差异。新增真实网格生成回归，先稳定复现“未补偿”失败，再仅将 GLES 表面软带的
峰值透明度缩放到 Canvas 的 68%、带宽缩放到 72%；薄峰透光、波背阴影和远层羽化不变。完整
单元测试与 Debug 构建通过；已发布阿里云 Debug `202607121602`，等待真机复测。

## 2026-07-12 修复 GLES 多层表面边带过厚过白

用户安装 `202607121547` 后截图反馈多层连续白边明显比 Canvas 更厚、更白，并要求区分厚度、颜色
或二者共同影响。逐项对照确认表面软带的几何宽度公式、环境地平色、高光色和 0.42 颜色混合均与
Canvas 一致；差异来自带内透明度：Canvas 为 `0.66×sin(πr)`，GLES 却在 14%～72% 深度保持
接近 1.0 的宽平台。相同几何因此拥有更高峰值和更多积分光量，视觉上同时变白、变厚。

新增峰值与 10000 点数值积分回归，旧 GLES 模型稳定失败。修复后顶点 alpha 乘 Canvas 峰值 0.66，
shader 使用归一化半正弦，实际峰值和积分严格回到 Canvas。未调整宽度和颜色，避免多变量试错。
完整 65 项单测与 Debug 构建通过；已发布阿里云 Debug `202607121554`，等待真机复测。

## 2026-07-12 移除珍珠/猫爪并迁移四类 GLES 表面效果

用户明确砍掉珍珠斑与猫爪暗纹，并确认下一步为表面软带、薄峰透光、波背阴影和远层羽化。珍珠
跟踪从 Canvas/GLES 删除；猫爪绘制、Mapper 生成入口、Simulation 五槽阵风 FIFO 与逐帧推进、
阴影颜色策略及对应测试一并删除，不再为不可见效果消耗 CPU。

`FableSolGlOptics` 新增四类分层曲面带：0～6 层表面软带、0～4 层薄峰透光、0～5 层波背阴影、
7～8 层环境色羽化。前三项沿轮廓只向水内展开，羽化按既有语义跨轮廓溶解；全部与水层穿插绘制，
保持近层遮挡。固定顶点容量扩大到 20000，仍无稳态扩容。新增四类效果存在性和层范围回归；完整
64 项单测与 Debug 构建通过，APK 确认含六份共享 GLSL；已发布阿里云 Debug `202607121547`，
等待真机目测。

## 2026-07-12 第二张截图确认目标是镜面闪点而非顺流流光

用户安装 `202607121525` 后再次截图，指出同一条右下/中部的亮白长斜高光仍露在波面外，并质疑
是否一直在说同一个东西。重新按亮度、长度和运动通道对照后确认：此前两轮修的是低 alpha 的
`streak`，截图目标实际是最高 alpha 约 0.92 的 `glint`。连续误认来自两者共用斜椭圆外观，但代码
已有独立跟踪列表，应该从一开始按通道建立回归。

新增 glint 专属顶点范围，实际生成路径回归在旧完整直椭圆上同时命中负法向和曲面越界。修复仅
替换 glint 几何：保留受光峰跟踪与呼吸，把每个闪点改为 10 段曲面软带、只向水内展开，并收窄
亮芯平顶。streak、珍珠和猫爪不再改动。完整 64 项单测与 Debug 构建通过；已发布阿里云 Debug
`202607121533`，等待真机复测。

## 2026-07-12 根据截图纠正流光越界诊断并改为曲面软带

用户提供截图并明确指出右下方的长斜锐利高光。截图证明问题不只是完整椭圆在中心切线短轴两侧
各露一半：上一修复虽然去掉负法向半侧，但流光长轴仍是中心点切线上的直线，波峰本身是曲线，
所以长流光两端仍会穿出轮廓。

在实际生成路径回归中增加逐顶点“`vertexY >= contourY(vertexX)`”约束，上一版半椭圆稳定失败。
现将每条流光拆成 10 段，逐段采样当前波峰高度，构成只向水内展开的弯曲带；片元着色器分别对
长度两端、轮廓入口和水内下缘软化。修复后法向与曲面边界两组约束均通过，完整 63 项单测和
Debug 构建通过；已发布阿里云 Debug `202607121525`，等待真机复测。

## 2026-07-12 修复 GLES 顺流流光越出波峰轮廓

用户确认顺流移动、沿波面切线倾斜的高光就是流光，并指出部分线条会滑到波峰外。确定性网格回归
证明旧 GLES 实现使用完整对称椭圆，局部法向坐标同时包含 `-1` 与 `+1`，因此一半几何天然位于
空气侧；中心向水内的少量偏移无法在宽流光和陡坡上可靠掩盖该问题。

修复后流光改为从轮廓开始、只沿水体内法向展开的半椭圆，新增单侧标记供片元着色器在轮廓后的
0～14% 厚度内透明软入，避免硬裁切亮边。失败回归先稳定复现，再在修复后通过；完整 63 项单测与
Debug 构建通过；已发布阿里云 Debug `202607121517`，等待真机复测。

## 2026-07-12 Stage 1 GLES 光学实体首个切片

倾斜性能通过真机验收后，继续 Stage 1 一比一视觉复刻。新增 `FableSolGlOptics`，把闪点、珍珠、
流光的持久身份跟踪以及猫爪阵风消费迁入 GLES 后端：CPU 只处理峰值锚点、平滑跟随、寿命和少量
椭圆网格；新 `optical.vert/optical.frag` 在 GPU 上完成旋转、软边和 alpha 合成。

光学绘制按 8→0 层穿插在水体区间之间，保持近层覆盖远层装饰的遮挡语义；最多 64 个椭圆、固定
顶点缓冲，不恢复 Canvas 的大量 Path/Gradient 提交。新增固定容量/有限数值/层范围回归，以及
闪点、珍珠、流光跨帧身份与数量上限回归。完整 62 项单测通过，Debug 构建通过并确认 APK 含
六份共享 GLSL；已发布阿里云 Debug `202607121505`，等待真机目测。

## 2026-07-12 GLES 倾斜性能真机验收通过并继续 Stage 1

用户安装 Debug `202607121451` 后确认倾斜已经不卡，Stage 1 的 GLES 基础水面、重力响应与本轮
边界剖面摊销通过真机验收。继续执行 Stage 1 一比一视觉复刻，下一步迁移尚未进入 GLES 的闪点、
珍珠、流光、猫爪等光学实体，并随后补齐表面软带、薄峰透光、波背阴影与羽化。

## 2026-07-12 修复 GLES 倾斜期间的物理尖峰

用户确认 GL 正常启用，但真机倾斜仍有卡顿。回传日志显示 Window/GPU 路径稳定：GL draw、swap
均低于 1ms，GPU P50 约 2.2ms；卡顿集中在 `sim.update()`，physics P50 从约 5.8ms 上升到
12.5ms，P95 约 16ms。代码核对后将根因收敛到倾斜变化触发的九层边界剖面集中重建，而非 GLES。

本轮把边界剖面改为显示帧级预算：首次初始化后每帧最多重建 5 层，慢帧补跑多个物理子步也不会
扩大预算；同时利用 `abs(u)` 对称性只算半幅并镜像写入。单次倾斜帧的边界采样点上限由
9×216 降到 5×108，约减少 72%，120Hz 波方程和传感器响应保持不变。新增每帧工作上限与
左右严格对称回归，并把物理日志拆成子步、边界层数、边界、波方程、二维表面和轮廓合成六项。
完整 60 项单测与 `:app:assembleDebug` 通过；已发布阿里云 Debug `202607121451`，等待真机复测。

## 2026-07-12 修复 Stage 1 TextureView 充气崩溃并加入红天空 Canvas 回退

用户安装 `202607121429` 后打开录音 Dialog 立即闪退，并要求 OpenGL 未正常启用、回退
Canvas 时把天空改成红色。崩溃栈精确指向 View 构造阶段：XML 给 TextureView 设置
`android:background="@android:color/transparent"`，OPPO Android 16 的
`TextureView.setBackgroundDrawable()` 明确抛出 `UnsupportedOperationException`；此时 EGL
尚未开始。进一步审查发现 GL View 在 surface available 与 fatal 回调中调用
`setBackgroundColor()`，即使删除 XML 属性也会再次走同一禁用 API。

现改为 `WaveVisualizerFableSolHost` FrameLayout 宿主：XML 不再直接给 TextureView 设置任何
background；GL View 内清除全部 `setBackground*`。宿主正常只让 GL 接收音频/重力和运行，
旧 Canvas 保持 GONE；任一 EGL/GLSL/draw/swap fatal 回调后，宿主隐藏 GL、启动 Canvas、恢复
当前 Thing 背景和最新重力，并通过 `setGlFallbackDiagnostic(true)` 把 Canvas 环境天空固定为
纯红色。性能探针也随活动后端切换，避免正常时双份 Simulation 消耗。

崩溃属于真实 Android TextureView 构造约束，当前 JVM 环境没有可执行的 framework inflation
seam；回归检查以崩溃栈同一调用链为准，已静态确认 XML、GL View 与宿主均不存在
`android:background`/`setBackground*`，完整单测与 `:app:assembleDebug` 通过。未使用 adb。
已发布修复版 `202607121437`。

## 2026-07-12 Stage 1 首个 GLES 纵向切片

用户确认进入 GLES Stage。完成第一条可发布纵向切片：仓库新增
`shared/fablesol/glsl/` 单一 GLSL 事实源，Android `sourceSets.main.assets` 直接打包，后续
桌面 moderngl 读取同一目录；新增 `WaveVisualizerFableSolGl` TextureView 宿主、
`FableSolEglSession` ES 3.0 上下文、`FableSolGlRenderThread` 独立 HandlerThread 与 latest-frame
合并、`FableSolGlRenderer` 模拟/采样/上传/绘制管线，以及可单测的静态网格索引布局。

录音 Dialog 已切换到 GL View；UI 线程只以 Choreographer 投递锁 60Hz 的 frameTimeNanos，
音频队列、latest-value 重力、Simulation、25×最多 120 列连续网格构建与全部 GL 调用均在
`FableSolGles` 线程。第一批共享 shader 覆盖环境天空、九层从远到近的 alpha 合成、Thing
纯色/八向渐变、纵向坡度受光、Fresnel、三角抖动和近层深水填充；Canvas 实现继续保留作
源码对照，但不再是本诊断版本的运行路径。EGL/GL 失败时显示静态 Thing 色并写
`[DEBUG-FABLESOL-GL]`，性能日志新增 `glFrame drain/physics/build/draw/swap` 分位数。

该切片尚未迁移持久闪点、珍珠、流光、猫爪、表面软带、薄峰透光、波背阴影与羽化，视觉会比
Canvas 基线简洁；本轮目的是先验证 EGL 生命周期、透明 TextureView/圆角/出入场、基础几何、
音频/倾斜响应和线程帧预算，再按真机结果逐批补齐光学。新增网格布局测试；完整单测与
`:app:assembleDebug` 通过，APK 已确认包含四份 `assets/fablesol/glsl/*`；未使用 adb。
已发布首个 Stage 1 真机诊断版 `202607121429`。

## 2026-07-12 真机帧数据确认 GLES 必要性，并修复 Stage 0 平滑回归

用户在恢复帧循环的 `202607121357` 上反馈比迁移前更卡，并回传第二份
`fablesol_frame_perf.log`。稳定段 `onDraw` 中位数为 drain 0.05ms、physics 6.53ms、
sample 0.71ms、color 11.84ms、mesh 0.07ms、submit_optics 13.62ms，合计约 32.8ms；
Window total 后段 P50 约 59~65ms、P95 约 75ms。与此同时 GPU P50 约 4ms、P95
约 5.4ms，SYNC/COMMAND 也低，明确证明当前是 CPU/UI 线程瓶颈，位图 atlas 不应再
阻塞 Stage 1。即使完全消除 submit_optics，physics+color 也已约 18.4ms，超过 60fps
的 16.6ms 预算；D41 的 GLES 独立渲染线程 + GLSL 配色路线由真机数据进一步确认。

“比以前更卡”另含一处 Stage 0 自身回归：DoubleArray 池化时重写 `smoothSignal()`，把
Hann 权重 `cos()` 放进了每个采样点×每个核点的内循环，导致每帧大量重复超越函数。
新增缓冲 Hann 数值回归，先确认 `smoothHannInto` 不存在时失败；随后在 `FableSolMath`
启动时缓存半径 3~6 的归一化核，帧内只做 edge-clamp 乘加。四个半径与旧
padEdge+convolveValid 路径在 1e-12 内一致。完整单测与 `:app:assembleDebug` 通过，
未使用 adb。已发布阿里云 Debug `202607121403`；修复后再取一轮日志作为 Canvas 最终
基线，主线随后直接进入 Stage 1。

## 2026-07-12 修复 Stage 0 帧循环未在首次布局后启动

用户真机安装 `202607121348` 后反馈录音仍进行，但水面、波浪和倾斜响应全部静止，并回传
`fablesol_frame_perf.log`。日志中 Dialog Window 的 FrameMetrics 持续输出，但两段会话均没有
每 120 次水面绘制才会出现的 `onDraw[...]` 汇总，证明水面只有布局触发的初始帧，新的
Choreographer 循环从未进入持续绘制。

根因是 Android View 生命周期顺序：`onAttachedToWindow()` 常发生在首次 layout 之前，彼时
`width/height == 0`，`ensureAnimating()` 按设计拒绝启动；Stage 0 改动删除了音频和传感器回调
中的逐次 invalidate，但 `onSizeChanged()` 得到有效尺寸后没有补启动，因而不存在第二次机会。
现已在 `onSizeChanged()` 更新物理容器宽度后调用 `ensureAnimating()`；无论 attach 与 layout
先后顺序如何，二者中后发生的一方都会启动循环。完整单测与 `:app:assembleDebug` 通过；
Android View attach/layout 缺少可在当前 JVM 测试环境运行的真实 seam，修复验收继续使用同一
真机日志信号：应恢复持续动画并开始出现 `onDraw[...]`。未使用 adb。
最终发布阿里云 Debug `202607121357`。

## 2026-07-12 Stage 0 首轮：锁 60Hz、零分配、传感器合并与真机帧仪表

用户确认按 `plan-2026-07-12-gles-migration.md` 开始执行。依照诊断流程先建立三个
可重复反馈环：`FableSolFramePacerTest` 验证 120Hz vsync 被稳定约束到 60 次渲染，
`FableSolGravityInboxTest` 验证多次传感器写入只消费最后样本，`FableSolMetricWindowTest`
验证滚动 P50/P95/P99；测试先因实现不存在而编译失败，再随实现转绿。

首轮 Stage 0 已完成：`WaveVisualizerFableSol` 改由 Choreographer 固定 60Hz 自节流并以
frameTimeNanos 推进；录音 Dialog Window 请求 60Hz，API 35+ 同时做 View 级请求；重力传感器
注册到独立 HandlerThread，通过无分配 latest-value 信箱在渲染帧消费，不再让传感器回调直接
修改 Simulation；渲染光学路径所有帧内 `DoubleArray` 改用 128 槽、按锚层释放的 scratch 池，
`gradient`、`convolveSame` 与 optical sample 新增 caller-buffer 入口；新增临时 debug 性能探针，
把对话框 Window 的 FrameMetrics 和 onDraw 六段耗时写到
`debug_logs/fablesol_frame_perf.log`，统一标记 `[DEBUG-FABLESOL-PERF]`，验收后删除。

位图 atlas 本轮不单独实现：Stage 1 会删除 AGSL 软带上传路径，首轮真机 FrameMetrics 若显示
`SYNC/COMMAND_ISSUE` 仍为显著瓶颈再补。API 26–28 `drawVertices` 行为继续留到兼容设备核实，
GLES 落地后根治。完整 `:app:testDebugUnitTest` 与 `:app:assembleDebug` 均通过；最终发布阿里云
Debug `202607121348`。首次上传 `202607121346` 因发布任务只嵌入第一个 `##` 小节而缺失完整
改动说明；`202607121347` 后又把重力信箱读端从短暂自旋收紧为单次非阻塞读取，均由最终版本
替代。未使用 adb。

## 2026-07-12 grill：性能与质感升级调研 + GLES 迁移定案

用户要求先了解连续 2.5D 水面的现状与决策史，再充分上网调研"如何进一步提升水体/
波浪/光线的质感与美感 + 如何优化性能（是否上 OpenGL ES）"，随后逐题 grill 定案。
三个并行调研代理产出三份报告（render-architecture / perf-jank-diagnosis /
water-visual-quality，均已归档本目录，日期 2026-07-12）。

诊断共识：瓶颈在 UI 线程 CPU（物理+采样+3000 逐顶点配色全在 onDraw），GPU 负载
很小；三个放大器为 120Hz 面板使帧预算减半、光学路径每帧 DoubleArray 分配引发
GC 尖刺、软带位图每帧 10~30 次微上传。另发现 drawVertices 硬件加速自 API 29
才开始，26–28 存在既有覆盖洞。

grill 裁决链（详见 decisions.md D38~D45 与 ADR-0016）：验收=View 锁 60Hz 含倾斜
全程稳定（D38）；允许临时帧仪表（D39）；新路径须 API 26+ 严格像素级一致（D40，
排除 AGSL/Mesh）；架构定为 OpenGL ES TextureView+EGL 统一渲染器（D41，推翻
plan-2026-07-11"不迁 GL"与 D20 AGSL 路线）；验收后删除全部 Canvas 水体渲染
（D42）；模拟器新增 moderngl 后端共享同一份 GLSL（D43）；GL 计划整体完成后再
解冻 A6（D44）；Stage 2 视觉升级"全部都要"——首波五项 + 二波六项（D45）。
执行计划见 plan-2026-07-12-gles-migration.md。本轮未改产品代码、未使用 adb。

## 2026-07-12 将俯仰与颜色策略回同步到 Python 模拟器

用户要求把 Android 本轮改动同步到 `E:\projects\audioVisualizerSimulatorFable`，并补充指出此前颜色相关调整也必须包含。目标仓库已有连续水面原型及大量未提交工作，本轮在原文件上做局部追加，没有覆盖或清理其余改动。

新增 `core/pitch_policy.py`，与 Android `FableSolPitchPolicy` 同构：完整保留 `−90°~90°` 原始俯仰，惯性目标软压缩到 `±55°`，观察角独立软压缩到 `14°~68°`；模拟器状态新增 `motion_pitch_deg`，面板与 `--pitch` 扩展到 `±90°`。新增 `ui/color_policy.py`，同步波背 18%/猫爪 24% 当前层色混黑、`max(0.05,(1−depth)²)` 远层淡出，以及纵向光照“正向候选保留、负向最多 14% 基色混黑”的规则。`canvas.py` 三条实际绘制路径均已接入。

新增俯仰与颜色策略回归；Conda `everythingdone` 环境下完整 80 项 Python 单元测试通过，既有 `scratch/test_water_rendering.py` 基线通过，连续水面 `+70°/−70°` 离屏出图均成功。同步更新 README、连续水面原型说明与 Android 移植笔记；Android 与 Python 模拟器两边均已提交 Git。

## 2026-07-12 前后俯仰改为完整输入软压缩

用户澄清上一轮所说的 Z 轴旋转实际是手机前后俯仰，并确认 `55°` 并非必须，可以优化。诊断发现旧实现存在两级硬平台：传感器入口、模拟器和连续曲面把输入截为 `±55°`；渲染观察角又以 `38°` 为基准截在 `14°~68°`，导致静态透视实际在前俯 `+30°`、后仰 `−24°` 后就停止变化。

先增加“输入 `55°→70°→90°` 时目标必须继续前进”的回归，旧实现稳定失败于第一段。新增 `FableSolPitchPolicy`，将 `pitchDeg` 改为保存完整 `−90°~90°` 手机角度，并以归一化 `atan` 曲线分别生成 `motionPitchDeg` 和观察角：水体惯性仍安全收敛到 `±55°`，透视仍位于 `14°~68°`，但二者只在手机达到 `±90°` 时到端点，全区间严格单调。

专项回归覆盖安全端点、逐度单调性、越过旧 55° 边界继续变化、异常角度防御以及欠阻尼弹簧初段不反向。完整 48 项单元测试与 Debug 构建通过，未使用 adb。已发布阿里云 Debug 更新码 `202607121026`，发布见 `debug-updates/update-20260712182608.md`。

## 2026-07-12 核对手机 Z 轴滚转是否存在临界回退

用户要求检查当前手机端 Z 轴旋转是否会在超过某个临界角后回退。代码链核对覆盖 `TYPE_GRAVITY`/加速度计输入、锁定屏幕方向后的坐标映射、`setContainerGravity()`、`FableSolSimulation.setTilt()`、物理更新和 Canvas 旋转。Android 滚转角由 `atan2(x,y)` 得到；虽然原始值位于 `[-180°,180°]`，`setTilt()` 会选择相对当前角度最近的 360° 等价角，因此 `179°→−179°` 实际展开为 `181°`，没有滚转限幅或回退。Python 模拟器的 `set_tilt()` 仍有 `[-90°,90°]` 限幅，但该限制没有迁移到手机端。

现有 `FableSolContainerGeometryTest` 通过；另以 0.05° 步长双向扫描 `0°→360°→−360°`，前后向非单调次数均为 0，最大数值误差约 `8.5×10⁻¹⁴°`。代码中的 8° `WALL_ON_DEG` 只控制硬墙物理混合，`±0.02rad` 只限制单步浪形惯性注入，均不修改渲染角。前后俯仰另有 `±55°` 限幅及视角 `14°~68°` 限幅，不属于 Z 轴滚转。

发现的真实边界是重力可观测性：手机接近平放时，屏幕内重力投影 `hypot(x,y)` 趋近于零，仅靠重力传感器无法稳定确定绕屏幕法线的角度；当前没有低投影门控，传感器噪声可能表现为跳转或反向。这不是固定角度阈值后的程序回退，本轮仅检查、未改代码、未使用 adb。

## 2026-07-12 修正纵向光照产生的灰黑暗部

用户进一步确认不自然的灰黑区域并非波背或猫爪两种显式阴影，而是与光线有关。按 diagnose 流程核对纵向法线光照后，定位到 `applyLongitudinalLight()` 的负向差值分支：原实现逐 RGB 通道执行 `base + fullLight - referenceLight`，天空反射色参与相减后会让暗部偏离当前记事颜色，形成大块灰黑。

新增 `FableSolLightColorPolicy` 统一处理纵向光照颜色。正向受光与高光候选色原样保留；仅当候选亮度低于基础水色时，提取相对暗度，并将结果约束到“当前层记事颜色混黑”的色轴。近处最大混黑量为 14%，随暗度平方根变化；深度衰减使用 `max(0.05,(1-depth)²)`，因此远方负向暗部很淡。原有法线、Fresnel、透射、天空反射与受光强度计算均保留。

新增 3 项颜色策略回归，覆盖五种代表色、正向光照不变以及远处暗部近乎消失；完整 42 项单元测试与 Debug 构建通过，未使用 adb。已发布阿里云 Debug 更新码 `202607121007`，发布见 `debug-updates/update-20260712180652.md`。

## 2026-07-12 远层阴影改为平方淡出

用户明确远方黑色需要“很淡很淡”。上一版 `max(0.35,1−0.85×depth)` 在远端仍保留
35% 基础黑度，不符合该语义。先把五种代表色、四档深度的期望更新为平方曲线并确认旧
实现失败，再改为 `max(0.05,(1−depth)²)`。

波背混黑从近层 18% 降到第 5 层约 2.5%、最远约 0.9%；猫爪到第 2 层约 13.5%。
阴影来源、几何、alpha 和空气透视不变。完整 39 项单测及 Debug 构建通过，未使用 adb。
发布见 `debug-updates/update-20260712180027.md`；阿里云 Debug 更新码 `202607121000`。

## 2026-07-12 阴影混黑量改为随层深递减

用户指出上一版固定混黑比例仍会让已经提亮的远层水体出现更显眼黑斑。为波背和猫爪颜色
入口补入 `depth01`，先以 0/0.25/0.625/1.0 四档深度和五种代表记事色建立失败回归；
固定 18%/24% 实现稳定失败。

有效混黑量现统一乘 `max(0.35, 1−0.85×depth01)`：近层保持原值，波背到第 5 层约
18%→8.4%，猫爪到第 2 层约 24%→18.9%。已有层 alpha、空气透视和阴影几何不变。
完整 39 项单测及 Debug 构建通过，未使用 adb。发布见
`debug-updates/update-20260712173923.md`；阿里云 Debug 更新码 `202607120939`。

## 2026-07-12 阴影改为当前记事颜色混黑

用户真机反馈浪面经常出现灰黑阴影，观感很差，希望暗部是更深的记事颜色。按 diagnose
流程核对三条暗化路径，锁定两条显式阴影：猫爪暗纹混入固定蓝黑 `#080C16`；波背阴影
固定降低 OKLab 明度后又额外冷偏。纵向法线和深度吸收从当前水体基色计算，不是固定
灰黑来源，本轮保持不变。

先无行为重构出 `FableSolShadowColorPolicy`，再为红、绿、蓝、黄、紫代表色建立“输出必须
等于当前层色混黑”的失败回归；旧波背/猫爪公式两项均失败。随后改为波背 OKLab 混黑
18%、猫爪混黑 24%，取消固定蓝黑与冷偏，几何强度/透明度/触发条件不变。颜色回归和
完整 38 项单测、Debug 构建通过，未使用 adb。发布见
`debug-updates/update-20260712172845.md`；阿里云 Debug 更新码 `202607120929`。

## 2026-07-12 优化倾斜手机时的专项卡顿

用户进一步确认正常播放不卡，卡顿主要发生在倾斜手机期间。按 diagnose 流程建立固定姿态
与连续倾斜的 1200 帧物理差分基准，并记录不同滚转角的连续安全窗口列数。宽 276dp 时，
原始列数从水平 110 增至 30°/45°/60° 的 172/186/190，导致 25 行网格、顶点着色与九层
光学最高增加约 73% 工作量。物理基准中倾斜额外开销约 7%～47%；边界重建计数进一步
锁定持续倾斜 2 秒执行了 227 次 `rebuildBc()`，接近 120Hz 求解频率。

连续渲染现固定为最多 120 个等距插值列，完整保留投影安全窗口首尾；轨道、轮廓、坡度和
波冠轻纱共享相同源索引/分数。物理仍使用 216 点、25 行与 120Hz。边界阻尼剖面限为
30Hz 重建，最大滞后 33ms，不降低传感器输入或倾斜激励频率。优化后姿态列数为
110/120/120/120/120，倾斜物理额外开销约 12%。

新增列预算/首尾映射和边界重建频率回归；完整 36 项单测与 Debug 构建通过。临时计时探针
已删除，未使用 adb。已发布阿里云 Debug `202607120918`；发布与真机复测见
`debug-updates/update-20260712171751.md`。

## 2026-07-12 修复连续水面翻滚露白与卡顿

用户真机反馈 Z 轴翻滚时远处水体两侧偶发空白，且整体比较卡。按 diagnose 流程建立
两个确定性反馈信号：几何回归稳定复现宽度 276dp、滚转 −90° 时最远保证覆盖只有
179.99dp、需求 210dp；静态渲染工作量为每帧 24 次 cubic ribbon、408 个颜色停靠点、
约 18.36 万次重复渐变几何点访问，另用临时计时探针测得连续场采样约 1.555ms/帧。

根因一是连续路径复用旧九层未投影采样窗，没有反推远行透视收缩和 ±10dp 轨道位移；
根因二是逐 ribbon Path/Gradient 和逐 Z×X 重复 `sin/cos/exp`。新增
`continuousRenderInfo()` 投影安全窗口并让索引向外取整；24 条 ribbon 改为按九层区间
合并的 8 次三角网格提交；九层渐变几何预计算，颜色/网格缓冲与 sRGB LUT 复用；方向波
改等距相位递推，波包高斯拆为 X/Z 可分离包络。25 行纵深、九层角色和完整锚线光学保留。

新增覆盖与批量计划回归，完整 34 项单测和 Debug 构建通过。优化后同一纯数学采样探针约
0.392ms/帧，约快 4 倍；临时计时探针已删除，未使用 adb。已发布阿里云 Debug
`202607120850`；发布与真机复测详情见 `debug-updates/update-20260712164938.md`。

## 2026-07-12 连续 2.5D 水面迁移到 Android

用户要求把 `audioVisualizerSimulatorFable` 最新一轮 FableSol 更新迁移到 Android。
核对确认 7 月 11 日已提交的表达/材质/AGSL 批次已经迁移，本轮蓝本是模拟器相对
`be72b42` 的未提交连续水面工作区。新增 `FableSolContinuousSurface`，同构迁移 25 行
方向波场、有限相干波包、深水色散、Gerstner X/Z 轨道、随波数增强的耗散和前后俯仰
惯性；九层原轮廓继续作为深度、颜色和声音角色锚点，二维场只贡献去均值后的相对翻滚。

Android 默认改走连续 ribbon：第一层是唯一前景剪影，九层固定 alpha 与 ThingBackground
八向渐变按远到近累计，纵向解析法线只添加相对旧路径的受光差，九条锚线继续承载完整
表面带、高光、珍珠、猫爪、波冠轻纱和远端羽化。手机三维重力新增法向俯仰输入；普通
onset 保持前景即时响应，只有 incoming 远浪和段落事件注入纵向波包。旧九层填充保留为
内部回退。AGSL 软带同步改为连续半正弦剖面。

新增 `FableSolContinuousSurfaceTest` 4 项约束；完整 `:app:testDebugUnitTest` 与
`:app:assembleDebug` 通过，未使用 adb。最终发布阿里云 Debug `202607120739`；首次上传的
`202607120737` 因更新说明只嵌入了“用户请求”小节，已由完整说明版本替代。待真机验证
连续曲面观感、姿态方向、ribbon 接缝、帧率与发热。发布详情见
`debug-updates/update-20260712153653.md`。

## 2026-07-11 录音完成后的文件名行与计时器等宽

- 用户反馈：录音结束后，上方文件名重命名区域的 `EditText` 与 `.wav` 后缀总长度短于下方
  `TimelyClockView`。
- 诊断确认停止态动画只改变纵向位置；可见宽度差异来自文件名行内部固定的 `192dp + 40dp`
  分配，其中 `.wav` 不会填满固定的 `40dp`。
- 修改 [fragment_record_audio.xml](../../../app/src/main/res/layout/fragment_record_audio.xml)：文件名行与
  计时器统一使用 `match_parent` 和两侧 `24dp` margin；输入框使用权重填充剩余空间，后缀改为
  `wrap_content`。
- XML 结构检查、`git diff --check` 与 `:app:assembleDebug --console=plain --no-configuration-cache`
  均通过。
- 已发布阿里云 debug 版本：code **202607111505**，APK
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202607111505.apk`，SHA-256
  `ab10414ca4e00f03d878e09bf339b1e873b1e233d80e0c79d79a6dff6dd586c7`；远端 `latest.json`
  已核验。未使用 adb，未安装设备；本次按用户要求提交。

## 2026-07-11 根治浪包突变（长期顽疾定位）+ 移除焦散

焦散两轮修形仍被否，按宁少勿烂整体移除（shader/参数/相位/聚焦场；吸收保留）。
突变顽疾定位（用户排除水位因素后收敛）：注入 Hann 包（支撑=全宽）的画外出生
不存在硬保证——主因是 injectLayer 的 uLimit 向内钳位在共鸣档塌缩
（melodic/loud→resonance01→1→wallBlend≥0.35>0.3→画外余量 140→12dp），每次注入
的半个包体被压进可见区、120ms 隆起几十 dp，完美解释"频繁、幅度大、随声音活跃、
改别处治不好"；次因是 jitter/pan/frac 统计尾部越界（A6 宽度增长放大）。物理核验：
共鸣档墙外 cScale=0.65，画外包可穿墙进入，向内钳位非必需。修复=画外全支撑硬保证
（need=可见半宽+半包宽+8dp 只向外推；超网格先收窄包宽，仍放不下丢弃）。
发布 `202607111427`。**待办：Python 蓝本的 inject_layer 几乎必然有同一 uLimit
塌缩缺陷，需同构回植。**

## 2026-07-11 修复焦散悬浮感 + 偶发闪烁（真机反馈第二轮）

焦散被否为"悬浮在浪前的羽毛"：根因是噪声纵坐标用屏幕 y——浪动纹不动。改水深
锚定（v=d，光纹随浪升降）+ 上方浪峰曲率聚焦调制（轮廓曲率场入数据纹理 b 通道，
0.30+0.70·crest）+ 阈值稀疏化 0.64。偶发颜色闪烁：轮廓位图池跨帧复用与
RenderThread 在飞显示列表竞争（HW 管线不快照位图内容），改三帧轮换池。
发布 `202607111405`（本次起发布恒传 -PdebugUpdateNotesFile，应用内日志恢复）。
若焦散仍不达标准，按宁少勿烂整体砍除（caustic_gain 归零）。

## 2026-07-11 阶段 C 收官：红屏诊断 → 纹理返工 → 优雅档定稿

真机诊断链：C3 无变化 → 诊断版（shader 失败=天空纯色 + C3 夸张档）→ 红屏确诊
AGSL 禁 uniform 数组动态索引（C2/C3 一直静默回退，C1 存活）→ 返工为 RGBA_F16
216×1 位图纹理采样（帧内位图池防显示列表别名）→ 真机确认三 shader 全部存活 →
回优雅档（absorption 0.35 / caustic 0.5）、移除诊断，发布 `202607111354`。
另修复应用内更新日志缺失：publishDebugUpdate 需传 `-PdebugUpdateNotesFile`，
此前四次发布未传致 latest.json 无 releaseNotes；调用规范已固化进
`.claude/rules/gradle.md`。阶段 C 定稿（C1 抖动/C2 软带逐像素/C3 吸收+焦散），
折射视差暂缓待用户裁决。

## 2026-07-11 C3 实装：深度吸收 + 焦散（AGSL 层填充光学）

真机确认 C1+C2 后用户圈定 C3。`layerFill` shader 链在已抖动渐变上，逐像素以层轮廓
求水深：深度吸收（Beer–Lambert 近似、保色相、下限 0.72、全九层）与焦散（1.5~36dp
深度包络、双倍频值噪声亮脉、阈值稀疏化、相位随层流累积漂移、近三层、焦散色由本层
色派生）。两参数 `absorption_gain`/`caustic_gain` 独立归零（A5.5 教训）。折射视差
暂缓（第 0 层不透明、真折射无语义，用户看完 C3 再定）。构建单测绿，发布 Debug
`202607111334`（SHA 2227c465…4db7）。与薄峰透光构成同一物理叙事：浅处透光、
深处沉降——水读作介质而非色带。

## 2026-07-11 阶段 C 实装：C1 渐变抖动 + C2 软带逐像素（AGSL）

用户确认真机 Android 16、圈定 C1+C2（C3 单 pass 统一光学另行立项）。新增
`FableSolAgsl`：两个 RuntimeShader 运行时编译，API<33 或编译失败自动回退既有
Canvas 路径（D20 预案；minSdk 26 双路径长期共存）。C1 抖动——环境天空与九层
填充渐变经 `dithered()` 包三角分布噪声（±1/255×alpha），根治 D20 暂存至今的
OLED banding，设计零变化。C2 软带——`drawOneSidedBand` fade 优先走逐像素
shader：轮廓 top/th ≤216 列作 uniform float 数组，逐像素插值求连续钟形剖面
（平台 0.14/0.48/0.72 同 CPU 子带、smoothstep 过渡），全部软带受益且光栅移到
GPU；uniform 缓冲复用零分配。已知风险：SkSL 对 uniform 数组动态索引的支持在
个别驱动上可能拒编——构造期 try/catch 会整体回退，真机若画面与上一版完全一致
（含 banding）即回退被触发，需回报。测试与构建绿；发布阿里云 Debug
`202607111322`（SHA a35d2464…f621d）。待真机验收：banding 消失、软带更柔、
帧率发热。流程注记：阶段 C 起渲染保真度验收以 Android 真机为准（Python 蓝本
仍是设计与物理真理）。

## 2026-07-11 复核用户手工完成的 Hero 包络平流修复（Claude 审查）

阶段 B 移植中 B1/B2 代理因会话限额中断，剩余集成由用户手工完成并提交（729482cf）、
发布（update-20260711200915）。Claude 复核未提交工作树（Hero 三频段空间能量包络：
上游画外出生 + 半拉格朗日随流平流，可见区禁止逐帧音频改形——"连续你好上抬变形"的根治）：
平流回溯方向/边界/双缓冲/零分配正确，源区门控与 FLOW_DIR 两向语义一致，常量
（GROUP_SPEED 0.45、SOURCE_GAP 48dp）合理；强化后的连续性测试（可见区 rms<0.10 +
上游传播 rms>0.15）与全套 :app:testDebugUnitTest、:app:assembleDebug 均绿。
设计注记：主浪响应自此存在 ~1-2s 的传输入场延迟（能量从上游涌入），属有意行为。
无问题，无需返工。

## 2026-07-11 验证旧模拟器 YAMNet 权重与人声时间线

用户指出 `E:\projects\audioVisualizerSimulator` 已含 YAMNet 权重和实现。只读检查与离线回放确认
`yamnet.onnx` 为 14.9MB、输入 `[1,1,96,64]`、输出 `[1,521]`，模型可正常推理。旧实现并非完全
只算 top-1：内部也取了 speech/singing/music 分组最大值；但场景控制仍由 top-1 驱动，类别字符串
匹配错误包含 `Synthetic singing`/`Singing bowl`、漏掉 `Humming/Vocal music/A capella`，并且
每 32ms 推理一次（31.25Hz）。最终融合还把 `yamnet_music*0.24` 直接并入 singing，使纯器乐也
天然具有约 0.24 唱声概率。

前 90 秒完整旧管线中，HOYO-MiX 纯器乐唱声 p50=0.239、vocal p50=0.600，Taylor Swift 分别为
0.308/0.985，无法可靠分段。去掉旧规则融合、修正真人声 index 后，HOYO-MiX raw vocal p50
=0.0002、`>0.005` 覆盖 1.2%；Taylor/姜育恒/洛依er p50≈0.0116~0.0134、覆盖 70.7%~78.3%，
长人声区间从约 13~17 秒出现。直接人声录音 p50=0.825。结论：权重含有有用的相对证据，失败
来自接线和未校准；0.005 量级分数不能直接固化阈值，下一步应训练 521-score 校准头。本轮未改
旧模拟器或 Android 代码、未构建、未发布。完整数据见 `research-2026-07-11-android-vocal-presence.md`。

## 2026-07-11 调研 Android 音乐中人声存在检测

用户将关注点转向人声，希望准确检测普通说话以及音乐播放过程中人声何时存在/消失，并指出此前
YAMNet 似乎只能把声音判成 speech 或 music。核对官方接口后确认：YAMNet 实际输出 521 个独立
事件分数，`Music` 与 `Singing/Choir/Rapping/Vocal music/Speech` 可同时成立；此前现象很可能来自
调用层只取 top-1。YAMNet 仍受 0.96 秒窗口、AudioSet 10 秒弱标签与未校准跨类分数限制，不能
直接作为精确分段器。

对比普通 VAD、YAMNet 多标签、Essentia voice/instrumental、专用 CRNN 与源分离后，推荐先验证
完整 YAMNet 时间线，再冻结 YAMNet 训练 `vocal_present` 小分类头，必要时升级 embedding + causal
GRU/TCN。Silero/WebRTC 只适合 speech 辅助；Essentia 模型因 CC BY-NC-SA/AGPL 仅作研究基准；源分离
留给未来离线音乐文件预分析。Android 建议 2Hz、独立线程、standalone LiteRT（阿里云分发设备
未必有 GMS），结果只进慢语义门，不阻塞现有低延迟 DSP。详细调研与验收指标见
`research-2026-07-11-android-vocal-presence.md`。本轮未修改代码、未构建、未发布。

## 2026-07-11 诊断连续“你好”时既有浪偶发上抬变形

用户在 Android 真机发现连续说“你好你好你好”时，偶尔有一条已经可见的浪突然向上变形。
按 diagnose 流程建立合成浊音核、升降调与擦音音头的确定性反馈环，并将几何测量限制在运行时
容器可见跨度内。逐项对照结果：`Prominence` 单独开启与无事件基线几乎一致；关闭张力相干化
无变化；注入渐入 120→400ms 和强制完整波包先在画外出生只能降低新波的边界峰值，不能解释
无事件时的轮廓分叉。

决定性差分：两套初始状态、流速、物理时间完全相同的 Simulation 不接收任何离散事件，只让
一套接收“你好”式响度/音高调制帧、另一套接收恒定平均帧，可见轮廓仍分叉最高约 0.91dp；
`hero_gain=0` 后差异严格为 0。根因锁定为 `FeatureMapper.applyFrame()` 持续改写
`heroTargetDp/heroBandTargetDp`：`HeroWave` 是覆盖整片水面的连续解析波，目标包络即使经过
0.28s/0.85s 等低通，也会缩放已经显示的峰谷，结构上违反 D12“已形成浪不得被音频重塑”。

继续通读 Python `CONTEXT.md`、ADR-0002/0006/0008/0009/0011、Android D12/D14~D20、执行计划
与音画调研后修正了最初的根治提案：把 Hero 退为完全固定背景、全部声音几何改走 DynamicWave，
会与 ADR-0006“保留六模态常驻宽缓基底”、D12“Hero 表达慢声音背景”、D16 旋律层职责和 A3/A4
音高/境映射冲突；取消 `Prominence` 几何也与 D19 和 A3 的旋律层重音峰击冲突；“每音节只准一个
几何事件”比 D18 的“每事件 ≤3 层”更严格，并非既有决定。

文档自身存在需要收敛的张力：较早的 D12/ADR-0006 允许 Hero 用慢标量包络跟随声音，而更晚的
D17、计划红线与研究 P2b 明确要求包络只在出生处生效、绝不乘性调制已有几何；A3/A4 实施又把
音高和境乘子接回全局 Hero 标量。设计兼容的根治方向应保留 Hero 的六模态、慢声音背景和旋律层
职责，但把每模态的全局标量振幅改为空间传播的能量包络/波包：新目标只从上游边界写入并随该
模态传播，不能瞬时作用于整片可见水面。宏观节奏波包/远浪/Prominence 继续作为显式新能量，
仅约束其出生与传播；装饰层 Dab 保留 <50ms 即时通道。水位继续只做慢速均匀潮位。

已在 Python 与 Android 同构实施：每层增加低/中/高三条空间包络场，沿固定流向以背景流与
Hero 群速度的组合平流；原攻击/释放只平滑画外源，不再乘整屏几何。`HeroWave.sample()` 改为
逐点读取包络场，同时保留六模态色散与受限 Gerstner 聚峰。新增双向回归：修复前不同逐帧帧值
在 0.2s 内造成最高 `0.541dp RMS` 可见轮廓分叉；修复后传播到达前低于 `0.10dp`，约 1.7s 后
又必须高于 `0.15dp`，证明声音响应只是改为物理进入而非被关闭。Python 全量 55 项、Android
完整 `:app:testDebugUnitTest` 通过。已发布阿里云 Debug `202607111211`，APK SHA-256 为
`1120b8db1177bf3148585e9027b8ce4d95d7cf1195fe544fdbe1659fc397977e`；待用户复测连续
“你好你好你好”。

## 2026-07-11 FableSol 表达与材质升级迁移到 Android

承接 `audioVisualizerSimulatorFable` 当日未提交蓝本和 Android 工作区内已经开始的部分迁移，
继续完成 FableSol A1～A6、B1 与视觉批次 2 的 Kotlin 接线。保留已有映射、物理和渲染改动，
补齐此前缺失而导致编译失败的音频前端与事件链：新增 K/A 双计权、400ms/3s 响度窗、白化
SuperFlux、节拍证据 music gate、4Hz 波动强度、YIN/相对音高、音节率与 `Prominence` 事件、
HNR/arousal/looming/impulse 输出；`FableSolFeatureFrame`、`FableSolEvent`、
`WaveVisualizerFableSol` 同步扩展，并保留 Android 独有的采集启动低频暂态保护。

已有部分迁移覆盖双 register、乐队角色分层、反克隆三层注入、波群/猫爪、六境连续权重、
持久闪点与珍珠斑、表面带、薄峰透光、流光条纹、轨道微摆、波背自阴影、空气透视、冷暖
微偏和 1/f 慢调制；本轮修正 Kotlin 随机整数接口并完成 `Prominence` 分发。接触阴影继续按
用户裁决保持移除，未恢复 A5.5。

验证：源模拟器 A1～A6/视觉相关 7 个测试文件共 42 项通过；Android 新增
`FableSolExpressionUpgradeTest`，覆盖 K 计权 44.1kHz 响应、韵律/F0/音节率、HNR、有限值
和张力升降；完整 `:app:testDebugUnitTest` 与 `:app:assembleDebug` 通过。已发布阿里云 Debug
`202607111133`，APK SHA-256 为 `d7b29809505c0de0029e54ac21c4b507f51f131fbe02e97208a86e1c85c4b15e`。
未使用 adb，未安装设备，未创建 Git commit。

## 2026-07-11 A6 表达批实装（Python）

按 plan A6 落地四映射一试验：HNR→清澈度（(1−CMNDF)×voiced τ2.5s；rough×(1−0.42h)、
capillary×(1−0.28h)——干净人声让水更清、闪光更锐、轻纱更丝）；arousal 复合（响度动态+
F0 IQR+音节率，τ4s）→境阈值偏置（基准 0.35=平淡语音典型值，保证平淡语音不受影响）；
looming（loud_s 导数升 τ0.5 降 τ1.1）→注入/波群/重音浪的生长（宽随幅长、峰锐度封顶，
守不尖窄红线）；冲击性（onset 包络 1.2s 峰度）→注入底座更宽+峰更立；张力=相位试验
（D18/D20 可砍标签）：证据下限 0.35 的持续渐强×门控充能，sim 波速 c+=0.6·tension·
(c_mean−c)，注入用原生 c 绝不同步化，静息回失谐。onset 事件附带 impulse01/loom01。
新增 tests/test_a6_expression.py 6 项（渐强 vs 平稳、纯音 vs 噪声、脉冲串峰度、arousal
偏置方向、张力升降、setter 钳位），全套 54 项绿。真实样本探针：speech onsets=46 与
A6 前完全一致（前端零漂移）、境分布不变；张力语音 med 0.16、平稳音乐 ≈0。
待用户 GUI 目测（重点：渐强段落的浪群生长、清澈人声 vs 噪声的水质差、张力试验去留）。

## 2026-07-11 视觉批次 2 实装：自阴影 + 空气透视 + 冷暖 + 1/f（用户圈选）

用户圈选扩展清单第一批+第三批+第二批之 4（glitter path 暂缓），批后进 A6。实装四参数：
`back_shade_gain` 波背自阴影——背光坡×脊线邻近场，阴影色=本层色 OKLab 降 L 0.085 保色相
再冷偏（不发灰，接替已移除的灰接触阴影），与闪点共享 light_azimuth_deg 光源，层 0~5；
`aerial_contrast` 空气透视压缩——k_air=1−c·depth01 乘入闪点/体光/薄峰透光/表面带/自阴影
（羽化除外），远层安静近层承载细节；`hue_temp_deg` 冷暖微偏——高光母色 +0.6Δ 暖、阴影
−Δ 冷；`pink_mod` 1/f 慢调制——确定性四尺度值噪声 `_pink01`，调制闪点活跃度/表面带亮度/
流光可见度（seed 去相关）；统一速度场收尾：猫爪渲染位置叠加轨道回摆。新增 7 项纯逻辑测试
（自阴影场只在背光坡、光向翻转换侧、1/f 有界连续确定、降 L 保色相），全套 48 项绿。
离屏 A/B（scratch/batch2_ab.py + b2_diff.py）：差异集中于浪面与背光坡（max 36 软过渡），
右侧大浪背坡沉暗、方向与光源一致；帧时间 10.52ms mean / 12.3ms p99，60fps 余量保持。
待用户 GUI 动态目测四滑杆；通过后解冻 A6。

## 2026-07-11 移除接触阴影 + 渲染性能优化（用户 GUI 反馈）

用户裁决：接触阴影"一直出现、偏灰、每层都有，很难看"——整体移除（代码+参数+天际线
门控），宁少勿烂；其立体感职责由候选清单第 1 项"波背自阴影"（层内受光/背光明暗转折）
接替，待用户圈选后实施。同报渲染帧率下降。cProfile 定位：每帧 102 次 drawPath 占 40%
（fade 四子带 ×30 次调用所致）、每帧 122 次 _mix_oklab 颜色换算占 15%。优化三项：
①OKLab 混色/色相旋转加 lru_cache（系数量化 1/1024、角度 0.25°，色差不可见）；
②fade 软带裁剪到非零厚度跨度（珍珠/透光/猫爪等局部带光栅面积大减）；③子带自适应：
alpha<40 的带单次填充（硬下缘每通道 ≤6 不可辨），子带绘制期关闭抗锯齿。离屏基准
（音乐、640×840 逐帧 grab）：27.66ms → 9.88ms（p99 11.6ms），加物理 1.2ms/特征链
0.5ms 后 60fps 余量充足。41 项测试绿；默认观感与全关复现场景均无回归。
另确认并行会话把 paintEvent 重构为 _paint_scene + 视频捕获路径（frameRendered 信号），
与本轮改动兼容，未触碰。

## 2026-07-11 修复：表面带割裂下缘 + 天空悬浮灰带（用户 GUI 实测报告）

用户全关外观参数并抬高 0/1 层水位后报告：浪面白光带有明显割裂下边缘；修复后又报告
浪峰上方悬着难看的暗带。三个根因：①表面带无开关参数，且 `environment_tint=0` 时地平色
退化为纯背景白，带变成白光；②`_draw_one_sided_band` 的 fade 用全局 y 百分位锚定单条竖直
渐变，浪高（轮廓 y 跨度大）时带的局部段落落在渐变强区中段，几何下边缘以 ~0.4·alpha 直接
截断成割裂亮边（此前浪矮+天空染色+高光掩饰所以从未暴露）；③层间接触阴影投在"身后水面"，
但浪峰高出远层天际线后上方是天空，无承影面，灰带悬空——且 fade 修复解除了旧渐变对阴影的
半遮掩，使其完整显形。修复：新增 `surface_strip_gain` / `contact_shadow_gain` 两参数；
fade 改为跟随带几何的四子带剖面（上沿 0.14 软入→中段 0.72 聚光→下缘 0.07 羽化，alpha 只依
赖带内相对深度，与绝对 y 无关）；paintEvent 维护逐列天际线 `sky_ys`，阴影厚度
clip(ys−sky_ys, 0, sh) 后横向平滑，峰顶越线处归零。复现脚本 scratch/strip_edge_repro.py
before/after 对照确认割裂与悬浮灰带均消失，默认参数观感无回归，41 项测试绿。
fade 子带使每条软带的路径构造 ×4，Python 端渲染帧时间未重新基准（用户中断了计时脚本），
GUI 左下 FPS 可直接观察；Kotlin 侧同构成本更低。另：`tests/test_video_recorder.py`
（2 项）为本轮期间外部（用户/其他会话）新增，非 Claude 所写，未改动。

## 2026-07-11 B1 实装：薄峰透光 + 流光条纹 + 轨道微摆（Python）

按第二轮调研排序实施 ①②③，全部叠加在 A5 基线上、独立参数可归零 A/B：
`thin_glow_gain`（0.55）薄峰透光内辉——绝对海拔门(4~14dp)×上凸薄度，hc 混白
0.24 后向 165° 有界旋转 ≤±24°，层 0~4，透射族的第一个证据；`flow_streak_gain`
（0.70）浪顶平面流光——持久实体顺 `ls.flow_dps` 平流、软入软出、facing 门控，
层 0~2 cap 3/2/2；`orbital_sway_dp`（13）轨道微摆——ξ=(1/k)∂η/∂x 恒等式，
闪点/珍珠/流光渲染位叠加 ±8dp 内回摆，实测 5~6dp。新增 `_thin_glow_field` /
`_orbital_shift` / `_step_streaks`（纯函数可测）+ `_draw_flow_streaks` +
`_hue_toward`。tests/test_volumetric_roll.py 8 项新测试，全套 39 项绿。
scratch/roll_ab.py 逐帧 grab 驱动 A/B（吸取快照不推进实体的教训），初版
"画了但过于克制"（像素差 max 32），定向提升 alpha/宽度/混白后 max 48 且软过渡，
裁切目测：透光贴峰、流光滑行、无硬边无生灭突变。待用户 GUI 动态目测三滑杆。
同步完成第三轮扩展美学调研（代理 15 次检索），结论见
`research-2026-07-11-aesthetic-extensions.md`：下一批推荐波背自阴影、空气透视
对比度压缩、glitter path 光柱、运动统计包、OKLab 色温微偏移。未改 Android。

## 2026-07-11 第二轮调研：翻滚感与光学质感（Claude）

用户提出强化水体 z 轴翻滚/翻涌与真实 3D 材质光效，并说明此前 `material-depth-direction`
调研为 GPT 所作、其 A5.5 组合已实测否决，要求降权（已在该文档头部加状态注记）。本轮自行
上网调研海洋波轨道运动（形体滑行、物质原地旋转，`u ≈ ω·η`）、Gerstner choppiness、海洋
绘画透光浪画法（峰薄水透青绿、谷最暗五区结构）、游戏 fake SSS 与 flow map 纹理平流，形成
`research-2026-07-11-orbital-roll-and-light.md`。结论：表面带只是翻滚感的"朝向"一半，缺
"物质运动"一半；排序方案为 ①波峰透光内辉 ②表面带流光平流 ③光斑轨道微摆 ④低幅 Gerstner
位移（尾序、触红线边缘），逐项与 A5 直接 A/B。本轮未改代码，等用户选择起点。

## 2026-07-11 回退 A5.5 材质纵深方案

用户动态目测反馈 A5.5“不如之前好看”，要求回退。已仅撤销本轮新增的三段光学合成、组内层降权、
统一表面场重构、向上展开的大面积浪顶平面、深度吸收、折射视差、5 个 A/B 参数及对应测试/脚本/
素材/移植说明；保留此前 A1~A5 的声音、物理、持久光斑、珍珠斑、原表面带和逐层接触阴影实现。
恢复后 31 项正式测试与水体光学探针通过；重新渲染的 `a5_speech_5s.png` 与回退前保存的 A5 基线
SHA-256 完全一致，确认逐像素恢复。旧 `test_water_rendering.py` 仍有一条早于本轮、与持久光斑实现
不一致的调用次数断言，本轮不借回退修改既有测试语义。未改 Android、未发布、未提交。

## 2026-07-11 A5.5 完成：统一材质、三段纵深、深度吸收与折射视差（Python）

按 D24 只改 Python 渲染，未调整声音分析/映射、未改 Android。九层物理改按前景 0~2、中景 3~6、
地平 7~8 三段光学合成，0/3/7 承担主轮廓，组内次要层降权；新增统一表面场，使表面平面、镜面、
峰肩透光、珍珠斑和折射共享宏观坡度/曲率、微法线、`N·L`、Fresnel 与镜面对准度。浪顶平面改向
地平线方向展开；0/3 主水体叠主题色派生深度吸收；前景主层加入最大约 3.2dp 的平滑有界折射视差；
接触阴影只保留三段主层，避免平行暗边。

新增 5 个视觉 A/B 参数、`tests/test_material_depth_a55.py` 3 项约束、A5.5 离屏脚本与帧预算脚本；
旧渲染探针同步改验新契约。34 项正式测试、`test_water_optics.py`、`test_water_rendering.py` 全部通过。
640×840 离屏完整帧约 16.36ms/61.1fps，材质关闭约 15.14ms/66fps。真实语音连续帧与视频输出为
`scratch/a55_speech_contact.png`、`scratch/a55_speech_material.mp4`，等待用户动态目测；未移植、未发布。

## 2026-07-11 当前重点切换为纯视觉材质提升

用户明确当前先不继续讨论或扩展声音驱动水体的方式，重点转为提升立体、真实、优雅、梦幻与质感。
后续冻结 A1~A4 的声音分析/映射现状，暂停 A6 声音修饰与“张力=相位”等项目，先推进统一光照、
表面法线、厚度、遮挡、反射、透光、折射和纵深合成等视觉材质研究。本轮未改运行代码。

## 2026-07-11 复核 Claude 进展并补充材质纵深方向调研

通读 Python 模拟器工作树、A1~A5 测试、移植笔记和 EverythingDone 功能文档，并复核最新离屏帧。
确认当前 Python 侧已完成 A1~A5 主体，A6 与本轮 Android 移植尚未开始；31 项回归全部通过，
点击基准 TOTAL 可见响应 p50 29.7ms、p90 38ms（另加真机输入块与最多一帧 vsync）。当前主要短板
不是响应速度或分析稳定性，而是九层仍易读作半透明色带，材质与空间表达尚未形成统一光学因果。

结合液体运动感知、镜面形状感知、Sea of Thieves 风格化水体、音高跨模态对应、Android AGSL
官方约束与 2026 Apple Design Awards 案例，形成 `research-2026-07-11-material-depth-direction.md`。
核心建议是在 A6 前插入待用户确认的 A5.5 材质纵深研究：统一法线/光场、九层物理三段光学合成、
深度吸收与峰肩透光、受限折射/视差；声音继续作为外力，不随声音改变水的基本材质常数。本轮未改
运行代码。

## 2026-07-11 A5 立体感：表面带 + 接触阴影（回应"正剖面纸片感"）

用户指出珍珠斑不明显只是表象，真正诉求是打破"正剖面看纸片"的平面感。诊断：缺"浪顶
平面"这一视觉事实。新增**表面带**（层 0~6）：38° 俯角下透视压扁的水面平面，迎光坡宽、
背坡窄、随浪开合，颜色=地平天空倒影混层高光色——轮廓线成为平面近边；**层间接触阴影**：
近水在远水上的软暗边（≤20 alpha）给出前后厚度。珍珠斑语义顺位为表面带在圆峰的聚亮。
离屏复核：大浪顶已现开合的浅色平面、层间分离增强、无生硬边缘；31 测试全绿。
待用户 GUI 动态评估立体感与梦幻度，反馈后进 A6。

## 2026-07-11 A5 修订：光斑实体化（用户否决初版）

用户目测否决初版闪点/珍珠斑："闪点像锯齿一样闪烁；珍珠斑面积小、一闪而过——应该持续
跟着波浪、面积形状随起伏变化，符合物理规律"。根因：格子 hash 独立生灭 + 每帧重新选峰、
无时间状态。修订为**持久实体跟踪**：场局部极大→锚点（含半宽=面积信号），实体位置
τ≈0.1s 跟随锚点、强度攻击 0.3s/释放 0.8~1.1s、无匹配软消散、新生渐入；闪点带 2.6~4s
个体慢呼吸（cap 3/2/层），珍珠斑左右不对称双西格玛固定于实体、面积由跟踪半宽驱动
（cap 2/层）。逐帧 paint 的序列验证：实体连续在位、贴峰滑行、无生灭突变；随后按
"面积偏小"再放大一档（σ 上限 46dp、核心 alpha 150）。31 项测试全绿。教训入档：
测试装置必须逐帧 paint（快照式低估实体攻击过程）。待用户 GUI 动态复验。

## 2026-07-11 阶段 A5 完成（Python：渲染六手法，离屏自审质感）

按"最高视觉质量、不得生硬"的要求实施渲染批（31 项测试全绿）：Sim 增 sparkle/calm/
resonance 渲染场与猫爪阵风系统（流速平流+软包络）；镜面连续带改**离散闪点**（8dp 格
确定性 hash 生灭、Hann 软包络、重生换位、染色近白、预算随活跃度）——明灭而非行驶；
**波峰珍珠斑**（圆润门+受光肩偏移+37dp 长波咬边+pearl 色相边，尖峰无斑）接管波峰聚光
（体积带系数 0.66→0.30）；**猫爪暗纹**（OKLab 向黑软斑顺流消散）；**顶边羽化**（calm×
深度门，远两层溶入地平色）；**驻波呼吸**试验（melodic 时 wall_blend 下限 0.35·resonance）。
用离屏渲染 PNG 自审三轮：驱动值探针（glint≈1.0/score≈1.0）定位"画了但过于克制"，
两轮定向增益后音乐态有稀疏微闪带、语音大峰有软鹅卵石光泽、沉降定格远层溶入天际、
无生硬边缘。待用户 GUI 动态目测（闪点节奏、猫爪、驻波）后按感受微调；A6 修饰批
（HNR/arousal/looming/冲击性+张力试验）为 Python 阶段最后一步。

## 2026-07-11 深层沉降修复 + 阶段 A4 完成（Python：境状态机）

用户报告停止后第 8/9 层拖几十秒才归静、否决"余韵沉降"：修复为深层长积分只在有声期间
生效，apply_silence 里 deep 能量/流速以 τ=2s 与全场一同快速归静（录音内短静默仍按 30s
保留历史；D16/D17 已加修订注记）。随后实施 A4（31 项测试全绿）：mapper 内境状态机——
六锚点（idle/silence/quiet/active/melodic/loud，文档注《水图》对照）各持 hero/swell/chop/
group/breath 五乘子，证据选境 + 不对称 EMA（升 0.8s/降 2.2s）连续插值，永无换挡感；
乘子接入 hero 总量、水位、材质、波群幅度与呼吸。三种静默落地：句中悬停（<0.6s 且语调
未落 → 涌浪衰减冻结）、句末沉降（3s 半衰期）、结束尾声（快速归静）。climax 瞬态境
按计划留待渲染批。新增 test_states_a4.py；porting-notes 增 A4 增量。待用户目测后进 A5
渲染六手法。

## 2026-07-11 沉降调速 + 阶段 A3 完成（Python：语音韵律批）

用户目测反馈 A2 沉降过慢：`swell_halflife_s` 默认 6→3s（范围 1.5~10s，D17 已修订）。
随后实施 A3（28 项测试全绿，两处踩坑已修：_smoothstep 不支持反向区间导致 voiced 恒 0；
音节检测误把 A 计权帧值与 K 计权中值混标尺）：YIN 音高（4:1 抽取 ACF 近似，150Hz 合成误差
<1Hz）+ voiced01 + 说话人基线（τ15s）→ 旋律层 hero 高度 ×(0.74+0.62·pitch)；因果音节率
（K 标尺峰谷检测）以 (1−music_gate) 融入表层速度；重音 prominence 事件（能量突出×音高偏移，
≥0.45、0.35s 间隔）→ 旋律层单层宽涌峰击（app/main_window 分发已接）；4Hz 波动强度 →
sim.breath01 → 环境波振幅呼吸（浅层强、深层无）。porting-notes 增 A3 增量。
待用户 GUI 目测（语调抬落、重音峰击、音节呼吸）后进入 A4 境状态机。

## 2026-07-11 阶段 A2 完成（Python：双 register + 波群 + 反克隆 + 乐队分层）

继 A1 后同会话实施 A2（D16/D17/D18 落地，25 项测试全绿，等待用户 GUI 目测）：

- **乐队分层**：spec 增 `DEEP_LAYER_START=7`；角色常量装饰(0,1)/旋律(2,3)/织体(4,5,6)/深(7,8)，
  role 权重新表（深两层置零）。深层全部驱动（水位/hero/材质/流速）只来自 30s 长积分，
  不吃逐帧频段、onset 毛细、节拍脉冲与感知流速（simulation 增 `flow01_deep`）。
- **双 register**：`_swell_energy`（升 0.9s/释放半衰期 6s 可调）承载短语记忆并接管层 0~6
  水位；apply_silence 不再清零水位——涌浪按半衰期沉降（"细浪漂漂"尾声的本体）、
  hero/毛细即时清零（chop 快死）、深层近冻结缓释。
- **波群 + 反克隆**：九层齐发 rhythm wave 废除，改为波群调度器（组内 2~4 包按
  0.62/1.0/0.78/0.55 包络在轮转织体层出生、相邻包不同层、组间间歇 ≥1.35×包间隔，
  包络只作用于出生幅度）；装饰层点击 dab（单层轮转、限速 0.18s、最小宽 96dp）；
  远浪改固定三层计划（旋律 1.0 + 装饰 0.52@+85ms + 织体 0.34@+170ms）；三通道互斥，
  单事件恒 ≤3 层；一切注入幅度 ≤0.09×宽度（不尖窄红线）。
- **真实素材验收**（2 语音 + 2 歌曲 + 1 录音）：max_layers=3、深层零注入、
  dab≈92%/波群≈15%/远浪≈8%；onset 物理响应覆盖率 11%→61%；延迟 TOTAL p50 29.7ms/
  p90 38ms（较 A1 的 48ms 再降）。新测试 `test_orchestra_dual_register.py`（5 项）；
  porting-notes 增 A2 移植增量；CONTEXT.md 增"波群/装饰层点击/深层见证"术语。
  待用户运行模拟器目测三类素材后进入 A3。

## 2026-07-11 阶段 A1 完成（Python：前端升级 + 延迟尺子）

按 plan-2026-07-11-expression-upgrade.md 在 `audioVisualizerSimulatorFable` 实施 A1 四项，
全部经真实素材标定与回归（Conda everythingdone；20 项单测全绿，未动 EverythingDone 代码）：

- **双计权响度**：K 计权（BS.1770-4，`_rebilinear` 采样率参数化，48kHz 处按构造精确对表）
  负责 loud01 + 新增 momentary(400ms)/short-term(3s)；**门控保留 A 计权**——标定发现 K 用于
  门控会把 D9 近静音防线打穿（假 onset 3→28），A 在 60Hz 的 26dB 衰减是防线的隐性组成。
  修复 momentary 环浮点消减负均值 → NaN 污染 EMA 的边界 bug（均值钳位 ≥0）。
- **白化 SuperFlux onset**：逐带运行峰值白化（半衰期 3s、地板 −72dBFS）+ ±1 带最大值滤波
  参考；novelty 仍用未白化 logb。真实音乐假 onset −14%（322→277），近静音 3→2，啦啦啦
  41→46（弱音节浮出，方向符合 D13）。已知限度：32 带粒度下小颤音（±0.5 半音）不跨带。
- **节拍证据门**：Scheirer–Slaney 三特征被真实素材否决两条（流行乐拍率调制使 4Hz 比反超
  语音；啦啦啦 flux CV/flatness 稳定性落在音乐区间），唯一强分离是节拍置信（音乐 0.80~1.00
  vs 语音 ≤0.20）→ 单证据慢积分门（升 4s/降 6s + 0.45/0.75 迟滞）。效果：全部语音样本
  节拍视觉关死，四首音乐 music01 0.81~0.94 平滑放行。fluct4hz01 保留为特征输出（A3 用）。
- **延迟尺子** `tools/latency_report.py`（孪生模拟隔离注入波）：TOTAL p50≈30ms / p90≈48ms /
  max 55ms（另加真机 vsync 与音频输入延迟），在 Michotte <50ms 因果预算边缘内；光学通道
  21ms 即生效。注入率仅 11~36%（节奏波包限流）——A2 反克隆/角色层重构的输入事实。

新增 `tests/test_frontend_a1.py`（10 项）、`docs/adr/0012`、porting-notes A1 移植增量、
CONTEXT.md 术语（节拍证据门/双计权分职）。对照脚本 `scratch/a1_compare.py` 的指标表即
Kotlin 移植 fixture 基准。Python 仓库改动未提交（等用户指示）。

## 2026-07-11 grill 问答定稿表达力升级计划（D14~D23）

以 /grill-me 对两轮调研逐题拷问，11 个问题全部落定并写入决策 D14~D23：美学章程取
"克制的抒情"（表达力由阶梯分辨率承担，未来将有离线音乐文件模式）；境状态机为锚点
配方+连续插值+迟滞（境是查询表不是旅程，代码功能命名、文档保留马远题名）；乐队分层
（深两层"无动于衷"改长积分）；三层时间记忆+波群包络（只作用于浪的出生处，D12 延伸）；
注入反克隆纪律（用户报告九层克隆缺陷，定位到全层循环注入）；音频前端 11 项全采纳；
YAMNet 暂缓条件触发；渲染首批六手法（闪光带离散化否决"轨道车厢"式实现、新增波峰
珍珠斑=crest glow+pearl 合并升级、驻波呼吸挂试验标签）、AGSL 推迟；环境天空升级为
明暗模式感知+时段色温（用户顺带报告暗色模式天空翻转 bug，已定位成因、后置处理）；
验收装置只建 Python 侧；reduced-motion 变体经用户明确否决。新增质量红线入
preferences（不尖窄浪、水位不颤动、宁少勿烂）。执行计划定稿于
`plan-2026-07-11-expression-upgrade.md`，下一步从阶段 A1（Python 前端第一批+延迟
尺子）开始。本轮只改文档，未改运行代码、未发布。

## 2026-07-11 第二轮调研：听觉感知全景、水的美学本体、音画语法

用户反馈第一轮偏实现，要求全面覆盖人对声音的感知维度，并深挖水体如何更真实、更有质感、
更优雅地反映声音（美学设计与音画结合本身优先于实现）。三个并行检索任务完成后综合成
`research-2026-07-11-perception-aesthetics.md`：（1）感知全景——4Hz 音节波动强度、逼近偏差、
谐波性清澈度轴、粗糙度、纹理颗粒度、发声努力等被典型可视化忽视的维度，及水声愉悦性的
1/f/去相关/宽带平滑启示；（2）水美学——波群包络、涌浪+风浪双 register、蒲福外观阶梯、猫爪纹、
闪光带、驻波、马远《水图》×蒲福交叉的"七境表达量表"与"大师会删掉什么"清单；（3）音画语法——
费辛格/惠特尼/Chion/迪士尼/拉班的十五条操作原则、声音事件类→视觉手势类映射语法表、直译
同步边界；综合为七条设计论点（双 register 记忆、乐队分层、张力=相位、七境状态机、快触发慢
展开、休止语法、单色相多光学）并更新讨论问题。未改运行代码、未发布。

## 2026-07-11 视觉质量与音频相关性升级调研

用户要求以设计/物理/计算机科学视角充分调研如何提升 FableSol 的视觉质量与音频相关性（大小、
音高、节奏、节拍、情感），可考虑高级特征与轻量端侧 ML，先调研讨论、不实现。通读当前实现后，
五个并行检索任务分别覆盖实时音频特征、端侧 ML、Android 图形、设计参考、跨模态映射文献，
综合成 `research-2026-07-11-quality-upgrade.md`：四阶段提案（P1 速赢：SuperFlux+白化、K 计权
响度、speech/music 门控、渲染零分配、延迟测量；P2 语音表达力：YIN 音高→旋律浪、音节率、
重音事件、HNR→清澈度、arousal→mood；P3 可选 YAMNet 语义层 +5.7MB 与节拍编排；P4 AGSL
单 pass 渲染上限），并列出明确不做清单（valence/SER/学习型节拍/GPL 库/ONNX 第二运行时等）
与 7 个待讨论决策点。本轮未改任何运行代码、未发布。

用户确认按两段 Android 真机录音的研究结论实施。Python 蓝本与 Android/Kotlin 同步把连续流速改为双时间尺度表层事件率：1 秒快速通道以 72% 权重参与上升，3 秒通道负责稳定值与释放；已通过听觉门的原始 subdivision 在显著度加权之外固定保留 75%，不再随 beat confidence 升高而消失。tempo/phase 继续驱动节拍，只以最高 12% 的正向余量补充连续流速，不再以凸组合向下覆盖高事件密度。分析侧速度攻击由 0.65 秒缩短为 0.35 秒，Simulation 流速执行平滑由 0.72 秒缩短为 0.48 秒，释放仍由 3 秒保持通道和 1.10 秒分析侧释放约束。

真实 WAV 回放中，`20260710234846.wav` 的有声帧 `flow01` 中位数由约 0.49 提高到 0.65，第二个 onset 后约 0.92 秒越过 0.5，第 0 层中位流速约 128dp/s；`20260710235706.wav` 的中位 `flow01` 由约 0.51 提高到 0.83，第 0 层中位流速约 173dp/s，仍低于 213.6dp/s 物理上限。近静音样本 `20260710215433.wav` 仍仅检测到 3 个 onset，97.0% 帧保持静音，平均响度约 0.007，没有复发噪声驱动高速水流。

新增 Python `PerceivedSpeedTest`、`RealtimeSpeedResponseTest` 与 Kotlin `FableSolSpeedTest`、`FableSolRealtimeSpeedResponseTest`，覆盖可靠节拍不删除 subdivision、tempo 不下拉高密度、快速/慢速窗口职责、默认攻击响应和真实 Analyzer 密集脉冲提速；Python 10 项相关测试与完整 Android 单测通过。未使用 adb。已发布阿里云 Debug `202607101619`，APK SHA-256 为 `a49ece45531e35ae9dc64e4146fa7dc7e0ca192d551c34d2a1c3431b3851e4e1`，等待真机确认主观速度。

## 2026-07-11 结合两段真机录音研究感知流速模型

用户补充 91.347 秒 Android 录音 `20260710235706.wav`，反馈其对应水流同样偏慢，并要求结合此前快速“啦啦啦”录音 `20260710234846.wav` 与公开研究，判断如何让流速更贴近人的感知。本轮继续使用 Conda `everythingdone` 环境按当前 Python 实时链路回放，只做诊断、研究和方案设计，未修改运行代码、未发布。

短录音每秒检测到约 5.0 个 onset，稳定段 3 秒原始密度约 5.0~6.0 次/秒，但显著度加权后约 2.4~3.1 次/秒；当前 `flow01` 峰值约 0.54，第 0 层峰值约 107dp/s。3 秒固定分母还造成启动期系统性低估：从第二个 onset 起，当前流速约 2.45 秒才越过 0.4、约 3.74 秒才越过 0.5。长录音每秒检测到约 6.6 个 onset，有声帧的原始密度中位数约 6.67 次/秒，但显著密度中位数仅约 3.69 次/秒；节拍器长期锁定约 110 BPM 且置信度接近 1，使 `effectiveEventRate` 几乎只保留显著密度。其有声帧 `flow01` 中位数约 0.51，第 0 层中位流速约 103dp/s；物理上限 213.6dp/s 仍未成为约束。

研究复核显示，主观速度不是 beat tempo 的同义词：音乐实验与感知速度建模均把 tempo、不同类别的 onset/note density、spectral flux 等作为并列特征；语音实验也确认 syllable rate 是主要速度线索，segment rate/音节复杂度还能提供额外线索。当前实现与此相冲突的地方有两处：一是 beat confidence 越高，表层 subdivision 越容易被删除；二是以凸组合融合 tempo 与 density，约 110 BPM 的中等拍速会反向拉低已经很高的表层事件密度。

建议下一轮以 Python/Android 同构方式试做双通道模型：表层事件率负责连续流速，采用约 1 秒快速估计与 3 秒保持估计，消除固定 3 秒分母的启动偏差；beat tempo/phase 继续负责节拍脉冲与少量正向佐证，不再删除 subdivision，也不再向下覆盖表层速度。onset 显著度主要控制波高、注入能量和材质，不应决定一个已通过绝对可听度/SNR 门的事件是否计入流速。候选范围为原始 subdivision 至少保留 70%~85%、分析侧攻击约 0.25~0.35 秒、物理执行侧平滑约 0.4~0.5 秒、释放约 0.8~1.1 秒；最终系数需用真机 A/B 主观标注校准，不能仅凭两段样本一次定死。

## 2026-07-10 诊断快速“啦啦啦”未产生预期高速水流

用户提供 8.382 秒 Android 录音 `20260710234846.wav`，反馈快速连续发“啦啦啦”时水流不够快，要求判断
是物理速度上限不足，还是音频没有触发高速驱动。Python 分析继续使用 Conda `everythingdone` 环境；
另用临时 JVM 回放探针核对 Kotlin 原生链，探针验证后已删除。

两端均检测到 40 个 onset，说明音节没有漏检。3 秒窗口的原始 onset 密度最高约 5.67 次/秒，但多数
onset 强度为 0.3~0.5；经过显著度加权及可靠节拍下的 subdivision 降权后，有效事件率峰值约
4.08 次/秒。即时速度目标最高约 0.553，0.65 秒攻击平滑后 Python `flow01` 峰值约 0.504，Kotlin
峰值约 0.509；平滑只贡献约 0.04~0.05 的差值，不是主要限制。

Kotlin Simulation 最近层实际峰值约 99.1dp/s，而参数允许的理论上限为 213.6dp/s，仅使用约 46%；
Simulation 基本跟随输入，没有触顶。结论：问题在感知速度映射把“密集但单个不尖锐的元音音节”解释为
中快，不在水流物理上限。若要让此类快速发声更快，应优先提高弱 onset/原始 subdivision 在
`effective_event_rate` 中的保底权重；其次才是略缩短速度攻击。直接提高 `flow_gain` 或物理上限会让所有
同等 `flow01` 的声音一起加速，针对性较差。本轮只诊断，未修改运行代码、未发布。

## 2026-07-10 抑制采集启动低频暂态并禁止既有浪形被音头重塑

用户提供 Android 录音 `20260710231609.wav`：打开录音 Dialog 后，即使环境安静，水位仍会先升后降；
约第 7 秒的拖鞋拍地声还会让几层已经成形的浪生硬改形。Python 分析与测试统一使用 Conda 环境
`everythingdone`。

录音前 0~3 秒的 PCM 实际约为 −20~−23dBFS，约 70%~90% 的 A 加权能量位于 250Hz 以下，
到约 4.5 秒才降到 −55dBFS 以下。这不是 Analyzer 凭空产生；当前应用显式关闭 AGC/NS/AEC，无法仅凭
WAV 判断是 HAL/硬件启动暂态还是拿放手机的机械低频。Android Analyzer 新增采集会话预热门：稳定静音或
可信非低频内容持续 0.3 秒即可开放，最长 4.5 秒；样本原生 Kotlin 回放中前 4 秒最大视觉响度和 onset
均为 0，首个非静音视觉帧在 5.759 秒。门只影响可视化，不修改保存的 WAV。

样本拍击 onset 位于约 6.30、6.76、7.15、7.26 秒。禁用 DynamicWave 注入后，旧 onset 仍会通过
HeroWave punch 在 6 帧内让既有轮廓变化最高约 0.56dp RMS；极端频段目标单帧可变化约 3.97dp RMS。
Python 与 Kotlin 同步取消 onset 对 HeroWave 的直接改写，快速事件只进入物理波包；HeroWave 攻击改为
0.85 秒，各模态最短约 0.72 秒；几何粗糙度另以 1.2 秒慢追，快速材质只影响光学。修复后无物理注入的
onset 轮廓变化严格为 0，极端目标单帧约 0.39dp RMS；真实拍击窗口峰值由旧回放约 0.54 降到约
0.34dp RMS/帧，且不再由远层同步突变主导。

新增 Python 与 Kotlin 回归测试；Python 5 项测试、FableSol JVM 测试、完整 Android 单测和 Debug 构建
均通过。最终发布阿里云 Debug `202607101544`，未使用 adb。

## 2026-07-10 修复完全倒置时水体未转到 Dialog 顶部

用户真机发现完全倒置手机时，FableSol 水面和波浪没有像迁移前的 Opus 一样来到录音 Dialog 顶部，
并要求核对 Dialog 存续期间是否禁止 Activity 自动旋转。

以 180° 重力输入应产生 ±180° 渲染角作为确定性复现信号，新增几何回归测试后确认
`FableSolSimulation.setTilt()` 沿用了 Python 桌面滑块的 `[-90°, 90°]` 限制，把完全倒置稳定截成
侧向 90°。修复为完整圆周角度，并对 179°↔−179° 做最近等价角展开，避免跨边界时反向旋转 358°；
墙面过渡改按偏离水平面的角度计算，使 0° 和 180° 都具有正确的水平水面边界语义。

检查迁移提交前后的 `AudioRecordDialogFragment` diff，确认方向锁定代码没有变化：打开时保存
`requestedOrientation` 和屏幕 rotation 后设置 `SCREEN_ORIENTATION_LOCKED`，在 `onDestroyView()` 或
`onDismiss()` 恢复，重力传感器到屏幕坐标的映射也与 Opus 相同。新增测试修复前 2 项失败、修复后
全部通过；完整 `:app:testDebugUnitTest` 与 `:app:assembleDebug` 通过。已发布阿里云 Debug
`202607101512`，未使用 adb。

## 2026-07-10 修复 Android 近静音噪声被识别为高响度/高活跃度

用户提供 Android 录音 `20260710215433.wav` 及另一会话的分析。复核确认文件在人耳意义上接近安静，
但包含 18.3kHz 强干扰、约 100Hz 嗡声和 5~10 秒的非平稳增益泵动。旧 Python 前端在前 5 秒输出
37 个 onset、平均响度 0.449，5~10 秒再输出 38 个 onset、平均响度 0.600；整段共 90 个假 onset。

诊断确认 `reduceat` 最后一带越过 12kHz 是代码 Bug，但单独修复并不会把前 5 秒 onset 从 37 降低；
直接根因是 A 加权总能量仍累计到 Nyquist，18.3kHz 干扰因而持续撑开静音门。限制总能量到 16kHz 后，
前 5 秒才降至约 6 个 onset；5~10 秒仍有约 40 个，证明还需绝对可听度门抑制 AGC 泵动。

Python 与 Kotlin 同步实现 D9：16kHz 听觉分析上限、12kHz 严格 flux 边界、静音启动、
−66~−54dBFS smoothstep 可听度置信、所有帧进入 flux 基线。新增两端回归测试，覆盖超声调制噪声
保持近静音、频带不越界和明确可听稀疏脉冲仍可检测。修复后真实 WAV 在 Python 路径为 3 个 onset、
平均响度约 0.004；Kotlin 原生 44.1kHz 路径为 4 个 onset、平均响度约 0.008。
完整 Android 单测与 Debug 构建通过；已发布阿里云 Debug `202607101456`，未使用 adb。

## 2026-07-10 物理容器改用 View 最终实测宽度

用户确认两项产品语义：PREPARED/STOPPED 持续监听并驱动水面属于设计；物理容器宽度应使用 Dialog
布局完成后 `WaveVisualizerFableSol` 的最终实测宽度，而不是 XML 的 280dp，也不是固定 320dp。

新增 `FableSolContainerGeometryTest`，先稳定复现水平跨度错误（320dp，而实测宽度为 280dp）和 30° 倾斜
跨度错误（487.128129dp，而实测宽度应为 452.487113dp），再实现修复。View 通过 `onSizeChanged(w, ...)`
传入 `w / density`；Simulation 的容器跨度、沿重力方向尺寸、体积守恒水位、墙面和注入中心改用运行时宽度；
FeatureMapper 的段落 surge 宽度改为运行时宽度的 75%。320dp 仅保留为网格采样间距和测量前回退。

修复后原始几何复现信号与新增测试均通过；完整 `:app:testDebugUnitTest`、`:app:assembleDebug` 通过，未使用 adb。
最终发布阿里云 Debug `202607101423`；首次上传的 `202607101422` 因更新说明小节不完整，已由最终版本替代。

## 2026-07-10 Python → Android 迁移审查

逐项对照实时音频特征、速度/节拍/Foote 事件、FeatureMapper、九层物理、Ambient/Hero/Optical 与 Canvas
渲染，未发现高严重度公式移植错误。使用同一段 44.1kHz、20 秒合成音频做 Python/Kotlin 差分烟测，双方均输出
1719 帧与 34 个 onset，时间戳一致；聚合 loudness/flow 的小差异来自 D3 的动态 frame rate 适配。

审查当时发现四项差异：Android 录音开始/停止不采用 Python 的 analyzer reset/time-base/gating 语义；不可见期间
feature frame 队列与动画/麦克风生命周期可能造成内存、GC 或后台占用；首版 View 实测宽度与 320dp 物理容器
不一致；核心迁移缺少 Python golden fixture 回归测试。其后录音状态被确认为设计，容器宽度已由 D8 修复。完整结论见
`migration-review-2026-07-10.md`。`./gradlew :app:testDebugUnitTest` 通过，未使用 adb。

## 2026-07-10 首次移植并接入

把桌面模拟器 `audioVisualizerSimulatorFable`（PySide6 + numpy，约 2500 行纯数学核心 + 渲染）
的**实时分析 → 九层水体物理 → 渲染**一比一移植为 Kotlin，替换录音对话框可视化（Opus 保留）。

- **新建包** `app/src/main/java/com/ywwynm/everythingdone/views/recording/fablesol/`：
  Spec/Params/FeatureFrame/Event/Color/Math/Rng/Speed/Fft、DynamicWave/WaveSets/Simulation/FeatureMapper、
  RingStat/BeatTracker/NoveltyDetector/RealtimeAnalyzer、FrameReceiver、WaveVisualizerFableSol。
- **接入**：`AudioRecorder.linkFableSol`（采集线程 PCM→float→feed→分发 frames/events）；
  `AudioRecordDialogFragment` + `fragment_record_audio.xml` 切换到 `WaveVisualizerFableSol`，
  对话框高度 360→420dp。
- **首版决策**：配色接 `ThingBackground`（纯色+渐变+8 向，见 D1）；物理最初固定 320×420dp 逻辑坐标、
  只改高度（D2，宽度部分后来由 D8 修订）；采样率复用 44100Hz（D3）；移植实时 Foote 段落检测（D4）；音频线程产帧、
  UI 线程消费的无锁线程模型（D5）。
- **编译**：`:app:assembleDebug` 通过（修掉 `FableSolPending` 可见性、补 `WaveVisualizerFableSol.onMeasure`
  固有尺寸避免撑大对话框）。
- **发布**：阿里云 debug `202607101136`，待真机验证视觉/物理/性能/重力符号/配色。

关键说明：RNG 用 `java.util.Random`（非 numpy PCG64），逐值不同但分布/层间差异一致，满足"行为
一比一"而非"逐帧像素一致"。

## 2026-07-10 修复水体透明 + 透明度闪烁

真机反馈水体一直透明且逐帧闪烁。根因：渲染复用单个 `Paint`，高光带 `setColor` 把 Paint.alpha 改小
并泄漏到下一层水面渐变填充（Android 会用 Paint.alpha 调制 shader），近层不透明主体被压成半透明、
透出浅天空 → 显透明；泄漏 alpha 随高光每帧变 → 闪烁。修复：拆 `fillPaint`（渐变填充，alpha 恒 255）
/ `bandPaint`（纯色带）。附带 View alpha 0.16→1.0 对齐 Python 不透明。发布 debug `202607101241`。
详见 `debug-updates/update-20260710201205.md`。

## 2026-07-10 诊断最近层水面比 Thing 本色浅灰

真机反馈：正常录音态下，距离屏幕最近的第 0 层水面明显比当前 Thing 背景色更浅、更灰。
通过对照 `WaveVisualizerFableSol`、原始 `audioVisualizerSimulatorFable/canvas.py` 与旧
`WaveVisualizerOpus` 的颜色链路确认：这不是 Paint alpha、View 录音态透明度或 OKLab 移植错误，
而是移植时沿用了原模拟器的 palette 语义——纯色背景会先生成一个向白混合 45% 的 `c2Base`，
第 0 层再把 `c1Base → c2Base` 画成覆盖整个水面以下区域的竖直渐变；同时 `color_breath` 与
`moodBright` 对第 0 层也继续向白混色。第 0 层自身 alpha 为 1.0，因此最终可见的主体正是这层
已经被提亮的渐变。

旧 Opus 的产品语义则是最近层直接使用 Thing 的纯色或原始渐变，只让远层提亮。建议 FableSol
恢复这一语义：纯色背景的两个基础端点都使用 `background.color`，并让空气透视/声音明度变化
随 `depth01` 生效，使第 0 层混白量恒为 0；远层、高光与物理逻辑继续保留。本轮只完成诊断，
尚未修改渲染代码。

## 2026-07-10 修复第 0 层纯色与渐变偏浅

用户确认纯色和渐变 Thing 都应让第 0 层直接保持记事颜色。新增可独立单测的
`FableSolLayerColorPolicy`：纯色基础色的 start/end 都复制 `background.color`，不再生成向白混合
45% 的第二端色；渐变继续保留原始 `color`、`endColor` 与方向。`lighten_far`、`moodBright`、
`color_breath` 的合成混白量统一乘以 `depth01`，保证第 0 层恒为 0，远层仍保留空气透视和声音明度变化。

回归测试先在旧规则下稳定出现 3 项失败，再应用修复；随后新增的 4 项颜色策略测试与完整
`:app:testDebugUnitTest` 均通过，`:app:assembleDebug` 通过。未使用 adb；视觉效果待用户通过阿里云
Debug 版本真机确认。已发布阿里云 Debug `202607101341`。
## 2026-07-15 第二轮水体材质诊断与 Python 性能修复

根据真机反馈开始第二轮分层、光影连续性、中远景效果和 HDR 高光迭代。Python 固定 HDR 场景复现约 2.5 FPS 卡顿；分段计时证实 GPU 不是瓶颈，主要成本来自光学带逐列执行 OKLCH 色域二分。完成颜色场向量化、256 级材质状态缓存、静态索引拓扑缓存和帧数组所有权修复后，无读回场景中位数约 16.3 ms，HDR 离屏读回中位数约 22.0 ms；67 项相关回归通过。

建立覆盖 10 个内置纯色与 8 组真实记事色/渐变的 HDR 离屏诊断。发现同时创建多个 standalone OpenGL context 会使非 current renderer 的消融帧只剩环境背景，立即作废该批错误基线并改为逐变体 create→render→close。物理与实现调研、真实性边界及分阶段方案见 `research-2026-07-15-water-material-second-pass.md`。

## 2026-07-16（晚）质感提升批 Python 落地（D151）

按双调研交集实施三改动（统一光场 / 厚度透光 / 闪点统计化），共享 GLSL 以零默认
uniform 门控进入公共区，parity(0) 四配色逐位一致。评审产物：模拟器
`tmp/uplift-review/`（base/step1/step2/step3/all 各含 4 SDR + 2 组 HDR 诊断；
`compare/` 为前|后|灰度差异三联与 HDR −1EV 视图）。数值摘要：
step1 掠射光泽 P99≈8.4（t45）；step2 厚度透光 P99 13.9–26.0，HDR 超白
244→255（t45）/714→750（t70）、透射 excess 首次真实触发；step3 风力展宽
t45 超白 244→269（数量↑）、t70 峰值 1.85→1.48（单体收敛，容量表封顶数量）；
全开 t45 P99 22.1。基准：全开 15.33ms vs 基线 15.28ms。手动契约断言通过
（water.vert 无历史包裹符号）；env 缺 pytest，正式回归待补。

**Android 移植清单（待用户目测认可后执行）**：
1. `FableSolParams` 增加 uplift_sheen / uplift_anchor / uplift_thick_glow /
   uplift_glow_boost / uplift_glint_wind（默认 0 / 0 / 0 / 1.35 / 0）；
2. `FableSolGlRenderer` 上传 `uGrazingSheenStrength`、`uThicknessGlowStrength`、
   `uGlowDerivedBoost`、`uLayerMeanYPx[9]`（锚层轮廓均值 y，物理 px 未旋转）、
   `uThicknessRangePx`（22dp×density）；environment 程序上传
   `uSunAnchorStrength`、`uSunAnchorQ`（x=0.5+tan(az±55°)·0.14，q=0.42）；
3. `FableSolGlOptics` 在 σ 处并入 widen（U=5·wind·sparkle01，阻尼 0.5，
   peak_normalization 锚定 base·widen）与 conserve=widen^-0.6（乘 track 强度）；
4. 网格构建侧计算 9 锚层均值 y 传入（对应模拟器 gl_scene 的
   `layer_mean_y_px = y[::ROWS_PER_LAYER].mean(axis=1)`）；
5. 真机验收：18 色回归 + HDR FP16 原值读数 + 60fps 锁定；
   QPainter/Canvas 回退路径保持归零行为。

## 2026-07-16（深夜）目测反馈落地（D152）

厚度透光定档 1.29/1.6（新默认 vs 旧现状：SDR P99 21.8–32.8，HDR 超白
244→266/714→758）；薄峰透光 deprecated 归零；掠射光泽砍除；日出光锚改
晨曦染色（差异 mean 0.5→6.95）；闪点风力补出生率联动并定性"静帧受容量表
约束、价值在动态端"；新增暂停冻结（freeze_probe，离屏单测通过：冻结期
sim.t 不变、恢复无跳变）。验证渲染 tmp/uplift-review/{defaults2,anchor1,wind12}，
拼图在 compare/。Android 移植清单相应更新：不移植掠射光泽；
uSunAnchorColor 需在 Kotlin 侧用 FableSolColor.mixOklab 派生。

## 2026-07-16（夜末）终裁落地与 Android 发布（D153）

砍除日出光锚与闪点风力（全链路）；freeze_tick 修复暂停后播放按钮失效；
Android 接线厚度透光（参数/均值/四 uniform），契约测试更新后 146 项全绿；
assembleDebug 通过；阿里云 debug 发布 202607161231（含日志，BOM 已修）。
Python 侧冒烟：params 仅剩 uplift_thick_glow/uplift_glow_boost、
freeze_tick 单测通过、GL 渲染正常。

## 2026-07-16（次日）厚度透光层级重分布 + 第 0 层可见（D154/D155，Python 侧）

用户复测指出厚度透光只在 1~4 层可感。落地：①独立权重表
THICKNESS_GLOW_WEIGHTS（4~8 层上提为 0.56/0.49/0.42/0.36/0.27）经新
uniform `uThicknessGlowWeights[9]` 上传，shader 回退 SDR_SSS 保旧接线；
②近层覆盖偏置 nearBias=0.45×clamp(1−depth×4,0,1)；③验证方法论修正
（差异面板 P95 定标、前后同走 --hdr-debug 线性管线）。

第 0 层仍完全不可见（可见主体 = front fill，片元早退）→ 用户裁决适当推翻
D6（D155）。三版迭代：v1 fill 参与透光但顶点端 clamp 插值把衰减拉伸到整块
填充（差异 mean 4.52，等于全面推翻，废）；v2 顶点传原始代理、片元 clamp
（衰减止于均值线+20px 的水平直线，mean 0.63）；用户复看后追加裁决"面积
不小于第 1 层、突破固定水位线、遵循自然物理"→ v3 终版：入射量 = 水面处
波峰门（vThicknessSurface，fill 的本列水面 y 借闲置 aSlope.y 传入）×
Beer–Lambert 深度衰减（λ=2×范围），透光柱逐列跟随水面轮廓指数淡出
（隔离差异 mean 2.42/P99 21.3@t70；HDR 超白与峰值逐位不变；深海/浅灰绿
配色无脏感）。评审图 tmp/uplift-review/compare/layer0v3-*、cumulv3-*。

Python 契约测试同步：7 项陈旧断言更新（5 项为上轮双参掩码/薄峰透光
deprecated/Android 默认值对照表遗留——上轮只跑了 Android 侧；2 项为本轮
fill 网格新契约），sheen-absent 测试补 uplift_thick_glow=0 关断（HDR
excess 与厚度透光同源）。全套 177 项测试全绿。

v4（用户追加裁决"第 0 层透光没第 1 层亮"）：数值归因 = fill 立即衰减拉低
整团平均 + 第 0 层身份色无混白、目标色绝对亮度天然更低。修复：①近表亮环
0.35×范围内全入射、λ 放缓到 2.5×范围；②fill 透光目标额外 ×1.35（浅色被
1.0 钳制自然封顶）。实测水线下提亮 8.45→14.81；HDR 超白/峰值仍逐位不变；
深海/浅灰绿无过冲。评审图 compare/layer0v4-*。渲染期间遇 wgl 上下文持续
创建失败（Auto HDR 显示模式切换期），轮询 ~2 分钟自愈，非代码问题。

用户确认 v4 后完成 Android 移植：FableSolMaterialPolicy 新增
THICKNESS_GLOW_WEIGHTS（含契约测试断言）；FableSolGlRenderer 静态上传
uThicknessGlowWeights[9]，front fill 顶点 aSlope.y（slopeZ 分量）改运本列
水面 y、顶边继承 row 0 sheen slope；共享 shader 无需再改（vert/frag 已
就绪，parity 断言全部仍成立）。testDebugUnitTest 161 项全绿，
assembleDebug 通过，APK 内 water.frag/vert 已含 thicknessThin/
vThicknessSurface。随后按用户要求发布阿里云 Debug **202607161438**
（SHA-256 bfd52b95…d3343，releaseNotes 已核对；发布号+SHA 已回填
memory/debug-update-notes.md 顶部，并顺手把上轮 202607161231 误插进
2026-06-28 条目列表中的回填迁回正位）。真机验收要点：18 色下第 0 层
水线透光观感、HDR 透射仍限逆光波冠、60fps。

本批（调研→实施→迭代→双端落地→两次发布）随后双仓提交收尾：
EverythingDone `ca351032`（13 文件）、audioVisualizerSimulatorFable
`b16f7a8`（11 文件），调研产物/Everything-Android/tmp/scratch 按用户
要求不入库，提交日志英文前中文后双语详述。

## 2026-07-16（夜·二）波峰银边（D156，Python 侧首轮）

用户圈选 GPT 效果图的波唇白线（"银丝游动"）。视觉确认（compare/1、3
放大）：贴上轮廓 1~2px 亮芯 + 内侧柔晕、沿边不均、近层强。物理调研
定性为剪影掠射镜面线（掠射菲涅耳→1 全反射 + 高光沿最小曲率成线 +
半角对准/Cox–Munk 涨落），据此选确定性 shader 实现而非拉长 glint。

实现：params `uplift_crest_rim`（默认 1.0）与 `glint_capacity_gain`
（默认 0，试验期闪点归零，容量表不动）；material_policy
CREST_RIM_WEIGHTS；water.vert/frag crestRimProfile/Energy/Color +
uCrestRimStrength/uCrestRimWidthPx/uCrestRimWeights[9]，波浪带与
fill 各对自身剪影生效，SDR 入参考白钳制、HDR 峰值 1+1.4×weight
（近层 2.4）录音门控（fill 首次获得银边专属 HDR 通道）。

渲染 rimbase/rimv1（SDR+HDR，4 配色）：t70 隔离差异 mean 0.30/P99
6.9，银边超白 117px 峰值 1.091，−1EV 下银丝仍亮；基线证实旧超白几乎
全由闪点承担。修一处自伤：excess 调用拆行破坏双端契约字符串（改回
单行）；glint 测试×2 显式开数量门、sheen-absent 补关银边。177 项全绿。
评审图 compare/rim1-*（含 1:1 zoom）。待用户 GUI 动态目测定档后移植
Android。教训重申：跨进程传 JSON 引号必坏——render_review.ps1 要在
会话内 `&` 调用，勿嵌套 powershell -Command。

## 2026-07-17（凌晨）波峰银边 v2："山舞银蛇"修订

用户裁决 v1 不够亮/太短/频率低/水体内部有可见截止边界。四处定位与
修复：①晕尾硬截止（残留 4.6% 台阶）→ 平滑窗归零，实测尾部台阶
8.86→1.93/255；②迎光门掺了风梳微法线把线切碎 → 波浪带与 fill 统一
改用平滑 sheen 坡度；③半角门放宽（0.55+0.9·s·n 不乘方）+ 峰锐度门
提前饱和（pinch×1.6 开方）+ 权重中层上提；④SDR 系数 →1.0、HDR 峰值
系数 →2.0（第 0 层峰值 3.0）。v2 数据（t70）：P99 6.9→22.4、超白
117→1415px、峰值 1.658、银线峰值增量 +36%；t45/深海/浅灰绿形态均呈
长段银蛇。直射分瓣单调隔离测试×2 补关银边（bounded 峰值归一破坏
隔离前提），177 项全绿。评审图 compare/rim2-*（含 v1 对比 zoom）。

## 2026-07-17（凌晨·续）波峰银边 v3~v6：亮度对齐闪点核心、长度×数倍

用户再裁决：亮度要与闪点核心同档、长度比 v2 再长几倍。v3 提 HDR 峰值
到 3.6 档并试峰锐度 smoothstep——覆盖列数反而降（399/1280）；随即实测
pinch 沿轮廓分布（P50=0、P99≈0.002~0.07、层 0 全零），确认**任何 pinch
乘法门都是长度杀手**，整项删除（物理：剪影掠射反射沿整条轮廓存在），
"不常出现"改由音频活跃度承担（0.30+0.70×sparkle01 乘进强度，D67 合规，
GlFrameData 新增 crest_rim_activity）。v4（方向门加 0.35 底）成全轮廓
描边、自否；v5 实测纯线性方向门在平缓 sheen 坡度下永不熄灭仍描边；
v6 方向门 smoothstep(0.40,0.82) 制造真实熄灭区，银蛇=受光坡段（数百
px、两端收尖、同屏约 10 条、近粗远细）。t70：SDR P99 94.9、超白
12410px、诊断峰值顶 2.0 天花板。MSAA 几何 AA 回归补关银边（细亮线
是逐像素着色、不在 MSAA 语义内），177 项全绿。评审图 compare/rim6-*、
rim6-vs-v2-t70.png。

v7（用户裁决：粗了一点、中远层太明显）：亮芯 1.2dp→1.0dp、晕幅
0.35→0.30；权重中远层下调一档；片元线宽乘 0.55+0.45×weight 做空气
透视变细（远层银丝更纤细）。t70：mean 2.19/P99 79.6、超白 7682px
（中远层回落、近层不变）。177 项全绿。评审图 compare/rim7-*、
rim7-vs-v6-t70.png。v8：晕幅 0.30→0.16（用户直接定档），光晕渗出
收敛、银丝本体更干净。评审图 compare/rim8-*、rim8-vs-v7-t70.png。

## 2026-07-17（晨）波峰银边 v8 定稿：移植 Android 并发布

用户确认 v8 后移植：FableSolParams 两参数 + setForTest 测试入口；
FableSolMaterialPolicy CREST_RIM_WEIGHTS；FableSolGlRenderer 权重表
静态上传 + 每帧 uCrestRimStrength（×活跃度）/uCrestRimWidthPx；
FableSolGlOptics 闪点容量门（与 Python 同咽喉点，Canvas 回退不动）；
共享 shader 零改动。测试三处契约更新（曲线表、闪点测试×2 开数量门、
parity bodyBlock 断言起点后移——fill 银边 HDR excess 为唯一许可例外），
149 项全绿；assembleDebug 通过，APK 核对含 crestRimShape/0.16 晕幅。
阿里云 debug 发布 202607170010（SHA-256 3bd6c606…c8f4），发布号+SHA
已回填 memory/debug-update-notes.md 顶部。

## 2026-07-17（晨·续）银丝 v9/v10 细化与参数化（Python 侧）

发布后用户续调：v9 亮芯 0.8dp + 权重/空气透视加陡；v10 亮芯 0.6dp +
中远层权重按用户指定 0.42/0.27/0.16/0.10/0.05/0.0129。亮度 vs glint
定量：SDR 核心双双纯白、HDR 银丝峰值（2.0 顶死）> glint 1.869——感知
差为点/线能量集中度。GUI 新增银丝三控制项（粗细/光晕/峰值亮度，
uCrestRimHaloAmp、uCrestRimPeakBoost 两个新 uniform，宽度改每帧参数
驱动）。177 项全绿。评审图 compare/rim10-*、rim10-vs-v9-t70.png。

v11 银丝滑动：按用户要求复刻 glint"逆流跑"视差。物理 = 深水群速为
相速一半、镜面包络相对波峰后滑；实现 = 低频值噪声调制场以半流速沿
+x 滑动（相位沿 sim 时间积分、冻结静止；λ≈160dp、各层 seed 异步），
第四个参数"银丝滑动"（深度 0.65×、0 关闭）。相位探针单调正向、
177 项全绿。评审图 rim11-vs-v10-t70.png；滑动感待 GUI 动态确认后
同步 Android 并重发。

v12（用户"完全没看到滑动"，探针定位双根因）：①GPU sin 大参数精度使
调制噪声整行偏平（on/off 比值恒 1）→ 亮结改正弦承载 + 小输入噪声
抖动，调制幅度实测 0.26~0.52；②相位跨帧积分在单帧渲染路径恒 0 →
改 sim.t 纯函数（恒速 55dp/s、λ=240dp 取模、无跨帧状态、冻结静止）。
同 sim 强制双相位差 712px 确认 shader 消费相位。GlFrameData 撤销
crest_rim_flow01（流速耦合放弃）。177 项全绿。教训：GLSL 噪声输入
保持小数值；跨帧渲染器状态在单帧截图路径必然失效，优先纯函数。

## 2026-07-17（午前）银丝 v9~v12 同步 Android 并发布

移植：FableSolParams 四控制项（width 0.6 / halo 0.16 / peak 3.6 /
slide 1.0）；FableSolMaterialPolicy 权重表 v10 用户定值（含测试曲线
更新）；FableSolGlRenderer 六个 uniform 每帧上传（宽度参数化、光晕、
峰值增量、滑动相位 = (55×sim.t)%240×density 纯函数、尺度、深度）；
共享 shader 零改动。Android 149 项全绿、assembleDebug 通过、APK 核对
含 uCrestRimSlidePhase 与正弦亮结。阿里云 debug 发布 **202607170158**
（SHA-256 e461a14d…6107），发布号+SHA 已回填 memory 顶部。

随后用户定档滑速 55→**64dp/s**（两端同改），发布 **202607170223**
（SHA-256 89acbbe0…4f89）。

## 2026-07-17（午后）银丝 v13/v14：波峰全覆盖 + 顶点辉光（Python 侧）

用户两连裁决：①银丝只挂波峰右侧、错过顶点——v13 方向门降级为倾斜、
主门改局部凸性（dFdx(sheen 坡度)>0×宽坡度窗；"高出均值"对宽缓涌包
失效 t45 实测消失；底 0.30→0.55 二渲回调防长翼跑者变暗）；②顶点应
显著最亮——v14 顶点辉光：3.2 倍宽 bloom 光球（SDR 靠面积）、闪光
驱动 0.45+0.55×亮结、HDR 超驱后被 headroom 钳死 3.6 = 全场最亮
（真机 3.6 屏顶满，诊断链只显示 2.0 上限）。经历 GLSL 保留字 flat
编译错误一轮 + 陈旧 SKIP 图一轮 + **凸性符号错误一轮**（vSheenSlope
为高度向上的物理坡度、波峰 = 坡度导数为负；首渲取 +dFdx 辉光整场点
在波谷，用户目测抓出，v14b 取 −dFdx 修正——教训：涉及 y 向下屏幕系
与高度向上物理系并存时，先写明每个量的坐标约定再定符号）。修正后
t70：P99 46.5、超白 3651 集中于波峰顶点；t45 超白 1794（平缓圆顶也
有亮帽）。用户再裁决：独立亮球与银丝"明显是两个东西"→ v15 连续
塑形——删除叠加式 bloom/flash，同一剖面随顶点度连续变化（线宽
×1~1.45、晕幅 ×1~3.2、晕铺展 3.2→4.6 倍、能量 SDR ×1~2.1、HDR
+2.2·apex），亮结滑到顶点的冲顶由乘积自然涌现。t70 P99 47.8/超白
3428、t45 超白 1184。177 项全绿。评审图 compare/rim15-*、
rim15-apex-zoom.png（顶点区 1.4:1 过渡自然）。

用户确认后同步 Android：v13~v15 全为共享 shader 改动、Kotlin 零改动
（重打包即同步）；Android 149 项 --rerun 强制重跑全绿（教训：parity
测试运行时读共享 shader、Gradle 不追踪其为任务输入，shader 改动后必须
--rerun 防陈旧绿）。APK 核对含 crestRimApexMask/连续塑形/凸性负号。
阿里云 debug 发布 **202607170325**（SHA-256 a1cc6e37…275f），发布号+
SHA 已回填 memory 顶部。

发布后用户裁决 v15 顶点"打结"光斑不好看 → v16 终形：剖面粗细全程
恒定，顶点强调只走亮度（SDR ×1~2.1、HDR +2.2·apex01，顶端最亮向两翼
平滑衰减）。t70 P99 29.7/超白 2122、t45 超白 598。177 项全绿。评审图
compare/rim16-*、rim16-vs-v15-apex.png（同位置对比：结消失、线等粗、
顶点仅更亮）。用户确认后打包发布（按指示跳过 Android 测试）：阿里云
debug **202607170339**（SHA-256 b99f85a0…4838），发布号+SHA 已回填
memory 顶部。

## 2026-07-17（午后·二）银丝 v17：太阳柱限定顶点高亮（Python 侧）

用户真机反馈：每个相对高的波峰都有高亮区 → 银丝断断续续，希望每层
只有 1~2 处。物理定性：顶点高亮是光滑波唇的宏观镜面点（每凸段至多
一个、需太阳柱内坡度可达、无微面片兜底），比闪点更严格集中于柱内。
实现：apex01 × 太阳柱包络（柱心同构 sun_glitter_policy、柱半宽更窄
0.15→0.07；新 uniform SpanX0/Span 换算 x01；GlFrameData 加 row 0
跨度字段）；银丝本体（天空宽光源）沿峰连续不受限。t70 超白
2122→1675、t45 598→342（高亮凝聚入柱）。177 项全绿。评审图
compare/rim17-vs-v16-t45/t70.png。

同步 Android：FableSolGlRenderer 新增 crestRimX0Px/crestRimSpanPx
字段（buildFrame 取 row 0 首末列 x）+ 两个 uniform 上传；149 项
--rerun 全绿、assembleDebug 通过、APK 核对含 crestRimSunColumn。
阿里云 debug 发布 **202607170358**（SHA-256 3ee3b644…54e6），
发布号+SHA 已回填 memory 顶部。

## 2026-07-17（傍晚）银丝 v18：全平滑过渡（Python 侧，三轮细化）

用户真机截图裁决：高亮区边界生硬、第 1 层 4 处高亮。四项根因逐一
移除：①dFdx 逐三角形阶跃（顶点/覆盖判据改坡度近零×显著度平滑场）；
②太阳柱收窄 0.11/0.055；③亮结 λ=360dp + 过渡带 0.24~0.78 + 深度
0.60（相位取模同步 360，取模必须等于波长）；④覆盖门 wings 坡度窗
删除（陡坡空间压缩成硬边、与显著度重复），方向倾斜 0.80+0.20、顶点
SDR 增益 0.9。沿丝追踪 P90 过渡落差 30→22/255；第 1 层右坡生硬终止
消失。177 项全绿。评审图 rim18-*、rim18c-layer1-zoom.png。

用户确认后同步 Android（Kotlin 三常量：取模 360、尺度 1/360、深度
0.60；按指示跳过 Android 测试）并发布：阿里云 debug
**202607170459**（SHA-256 443007de…9f2a），发布号+SHA 已回填 memory
顶部。

## 2026-07-17（下午）设置内调参 Dialog：全量参数 + HDR 开关 + 实时预览

设置界面新增"音频海浪动画参数调节"入口（RECORD_AUDIO 权限门控）。
Dialog 顶部固定 240dp 与录音界面同源的 GLES 预览（实时录音驱动、
重力倾斜、HDR、App 默认强调色渐变），下方滚动区列出 82 个实际生效的
标量参数（9 组，标签/范围/步长与 Python params.py 同源）+ HDR 开关
（默认开、设备不支持置灰）。调节经渲染线程 drain 实时生效并失效静态
材质色缓存，松手持久化到独立 prefs（只存偏离项），各渲染器构造时
套用；录音 Dialog 的 HDR 激活改读开关。顺带补注册漏移植的
swell_halflife_s / deep_integral_s（按实效值 0.5/1.0 保观感，与
Python 3.0/30.0 的差异入 followups）。新文件 FableSolTuning /
FableSolTuningDialogFragment / dialog_fablesol_tuning.xml；字符串
13 语言补齐。161 项单元测试全绿、assembleDebug 通过。决策 D157。
## 2026-07-17（傍晚）调参 Dialog 二轮：沉浸预览 + 换色涌入 + 暂停 + 样式统一

用户九项裁决落地（D158）：去标题、预览贴顶满宽（present.frag 底角
半径增量 uniform 切直下两角）；取景上移 36dp（旋转前 R^{-1} 补偿，
第 0 层波谷不再贴边）；右上角新增暂停/换色按钮——换色走 water.frag
揭示门（gl_FragCoord.x smoothstep）+ 渲染端双遍绘制：主遍 OKLab 插值
配色、第二遍目标配色 SRC_ALPHA 混合，新色波浪从右缘涌入 1600ms，
UI 全部强调色元素（组头/滑杆/复选框/涟漪/确定按钮）12 档同步渐变；
颜色池 = accent 渐变 + 内置 10 色 + 用户记事背景（后台去重）。HDR 行
复选框换渐变勾选、行涟漪换 GradientRippleDrawable，确定按钮换
AlertDialog 同款 accent，按钮行上方加滚动指示分隔线；"质感提升
（试验）"更名"质感"、行距增大。滑动卡顿确认根因是窗口
preferredRefreshRate=60 锁刷新率，按用户指示留待下轮。两端输出行
合同断言同步更新（colorRevealAlpha）；Android 149 项 + Python 177 项
全绿、assembleDebug 通过。
## 2026-07-17（晚）调参 Dialog 三轮反馈（D159）

角标按钮去圆形衬底、图标降不透明度（亮色不再实黑）；表面亮带默认
归零（Python 已整项移除，视觉对齐，合同测试显式开启）；删"系统"组；
换色卡顿修复——每档只更新视口内可见滑杆（82 条全量重建的 requestLayout
风暴是根因），动画结束全量补齐；参数列表 overScrollMode=never（12+ 的
stretch RenderEffect 拖垮预览帧率）。149 项全绿、assembleDebug 通过。
## 2026-07-17（晚 II）调参 Dialog 四轮反馈：表面亮带整项移除（D160）

HDR 勾选框涟漪补渐变并随换色更新；按钮行恢复标准 dimen 间距；表面
亮带按 Python D147 先例整项移除（发射端/权重/策略类/参数/目录全删，
canvas 流光解耦保留、与 GL 独立性对齐，mode 4 合同反转），测试
149→143 全绿。发布阿里云 Debug 202607170734。
## 2026-07-17（晚 III）暂停语义重做 + 按钮行 divided 间距（D161）

暂停从"停帧循环"改为"冻结模拟与音频泵、渲染照跑"（对齐 Python
freeze_probe），冻结画面上调参/换色/HDR 切换实时可见；按钮行上边距
换 divided_action_row_margin_top（参照选语言 Dialog）。143 项全绿。
## 2026-07-17（晚 IV）调参目录多语言化

FableSolTuning.Spec 标签改 @StringRes；8 组名 + 79 参数名 ×13 语言
（1131 条，脚本一次性插入；英/德组名的 & 需 &amp; 转义）。中文文案
与 Python GUI 保持一致。143 项全绿，发布阿里云 Debug 202607170801。