# 调研 · FableSol 材质、纵深与音画映射的下一步方向（2026-07-11）

> **状态注记（2026-07-11，用户口述）**：本文档由 GPT 生成。用户按其建议实现过一版
> （即 A5.5 组合方案），实测观感不如 A5，已完整回退。本文结论权重降低，仅作
> "已试过且失败"的参照，后续调研与方案以新文档为准。

## 本轮判断

Python 工作树已完成 A1~A5 的主体实现，声音感知、反克隆注入、时间记忆、境锚点和浪形连续性
已有较完整骨架。当前离屏帧的主要短板不再是“波不够多”或“反应不够强”，而是九层仍容易被读成
多张半透明色带：局部高光、表面带和接触阴影尚未形成统一、可持续的光学因果。

因此下一步不宜继续增加独立装饰物。建议先插入一个 **A5.5 材质纵深研究**，让全部视觉细节服从
同一套表面法线、光源方向、深度吸收和遮挡关系；通过后再进入 A6 修饰批。

## 调研依据

- 液体即使没有明确形状，仅凭运动向量也能被识别；局部运动速度强烈影响黏度判断，运动场的空间
  平滑性影响“像不像液体”。这支持继续把连续光流和浪形时间连续性放在粒子、泡沫之前。
  [Kawabe 等，Seeing liquids from visual motion](https://pubmed.ncbi.nlm.nih.gov/25102388/)
- 镜面结构会显著影响表面凸度和材质判断；高光的位置、方向、形状必须与表面几何及环境光一致，
  不能作为独立贴花。当前实体跟踪方向正确，但还需要共享法线与光场。
  [The Effect of Material Properties on the Perceived Shape of Three-Dimensional Objects](https://pmc.ncbi.nlm.nih.gov/articles/PMC7768321/)
- Rare 的风格化海水并非靠任意装饰，而是用视角、太阳方向、波峰掩码在深水色与次表面色间混合，
  再加入受控的面积镜面反射；这说明“风格化”和统一光学结构并不冲突。
  [The Technical Art of Sea of Thieves](https://history.siggraph.org/wp-content/uploads/2022/09/2018-Talks-Ang_The-Technical-Art-of-Sea-of-Thieves.pdf)
- 跨模态研究中，音高与视觉高度的对应比音高与亮度、尖锐度、大小等对应更稳。FableSol 应继续优先
  把 F0/语调用于旋律层高度与上下行方向，不应为了“高音明显”把浪变尖。
  [Competition between audiovisual correspondences](https://www.frontiersin.org/journals/cognition/articles/10.3389/fcogn.2023.1170422/full)
- Android 13+ 的 AGSL 可在 Canvas 图形管线内工作；`RuntimeShader#setInputBuffer` 可把 Bitmap 当作
  不经色彩管理的 heightmap/normal/material 原始缓冲。九层高度场最终适合在单 pass 内做一致的
  吸收、反射、遮挡和折射；Canvas 保留为回退路径。
  [Android AGSL](https://developer.android.com/develop/ui/views/graphics/agsl)、
  [AGSL Quick Reference](https://developer.android.com/develop/ui/views/graphics/agsl/agsl-quick-reference)
- 2026 Apple Design Awards 对 Tide Guide 的评价集中在自定义动画、水主题与 sky-matching palette 的
  一致性，说明获奖感更多来自完整视觉系统，而不是效果数量。
  [Apple Design Awards 2026](https://www.apple.com/newsroom/2026/06/apple-reveals-winners-of-the-2026-apple-design-awards/)

## 建议的 A5.5 四个材料试验

每项都先在 Python 中单独启用、录制连续片段并做开关对照；任何一项单独看不够好就删除。

1. **统一表面法线与光场**：从最终高度场的一阶导数和 OpticalWaveSet 微法线合成同一法线；表面带、
   珍珠斑、闪点、波峰透光全部使用同一屏幕固定主光方向。手机倾斜时水体改变法线，高光应沿坡面
   连续滑动，不随对象索引或帧随机跳变。
2. **三段材质而非九张纸**：保留九层物理，但光学上合成为前景水体（0~2）、中景波群（3~6）、
   地平慢涌（7~8）三段深度。前景主要靠遮挡和镜面，中景靠深度吸收，地平靠空气透视；避免九条
   近似等权轮廓同时可见。
3. **深度吸收 + 波峰透光**：以可见水厚度近似 Beer–Lambert 吸收；深水更沉、薄峰肩更亮。只从
   Thing Background 派生深水色和次表面色，保持第 0 层身份色与单色相纪律。先做克制版本，不加白沫。
4. **受限折射/视差**：近景水面按法线微量偏移其后的中景/天空采样，让水真正读作透明介质；位移
   必须低幅、低频、连续，避免果冻感。Python 先验证审美，Android 最终由 AGSL 单 pass 承担。

## 音频到视觉的建议映射

| 声音维度 | 建议视觉职责 | 不建议 |
|---|---|---|
| momentary / short-term 响度 | 即时风力 / 短语涌浪能量 | 快速抖动水位 |
| F0 与语调轮廓 | 旋律层高度、上下行、受光位置 | 高音直接变尖、变窄 |
| 音节率 / onset 密度 | 表层流速、波群出生频率 | 每个音节九层齐发 |
| prominence | 单个旋律层的新生宽浪 | 改写既有浪形 |
| HNR / 非周期度 | 微法线粗糙度、镜面瓣宽度、毛细纹密度 | 改变水的黏度和基本材质 |
| voiced / harmonic coherence | 长波相干、清澈镜面 | 直接提高全场亮度 |
| beat confidence（音乐门后） | 稀疏的光学强调或相位佐证 | 每拍全屏脉冲、全层变形 |
| looming / crescendo | 新浪的增长率、能量库上升 | 水位瞬时跃迁 |
| 静默 / 停止 | 快速收束到仍有微动的平静面 | 数十秒深层余韵 |

关键约束：**声音应被解释为作用于水面的外力，不应不断改变水本身的材质常数。** 这能让不同声音
改变水的状态，同时仍让观者相信它始终是同一种水。

## 美学建议

- “梦幻”主要来自连续的环境反射、低频光晕和深度透光，不来自随机粒子或第二色相。
- 把高亮预算集中在少数真正对准主光的坡面；平静时仍有极低幅慢流，但视觉焦点让给录音任务。
- 暂不优先做时段冷暖色。先把天空与 Thing Background 的同色系关系、暗色模式连续性和材质光场做好；
  时段色温是额外语义维度，收益小于统一光学。
- 先做动态 A/B，再看静帧。液体材质主要由时间连续的运动统计决定，单张截图只能验收构图、层次和配色。

## 建议执行顺序

1. 用户先动态目测当前 A5“表面带 + 接触阴影”版本，确认其真实贡献。
2. 做 A5.5 的四个独立材料试验，先统一法线与三段光学合成，再评估吸收和折射。
3. 通过后进入 A6；HNR 首先接微法线粗糙度，arousal/looming 只控制外力与出生参数。
4. Python 表达层收敛后一次性移植 Kotlin；API 33+ 用 AGSL，Canvas 作为风格一致的简化回退。
