# 空间照片效果：2026-08-10 重新调研

用户在 vNext11 基线（HEAD 提交版）上仍看到明显畸变，裁定此前所有改动都不如提交版，
要求重新上网调研，重新判断路线。本文只记录调研所得与由此得出的判断，不含未经验证的方案。

## 一、Apple 在产品里到底怎么做的

**iOS 26 Spatial Scenes（锁屏／相册，iPhone 12+，不需要 Apple Intelligence）**
端上 Neural Engine 跑单目模型，把照片分成 **前景／中景／背景** 若干层，倾斜设备时产生
**幅度很小**的视差。多家报道一致描述为 "small changes"、"subtle"。

**visionOS 2 的 2D→空间照片转换**
关键差异：转换**不是加一张深度图**，而是**直接生成左右眼两张图**
（"The conversion process does not add a depth map to the image. Rather, the conversion
generates and adds a pair of left- and right-eye images"）。即 Apple 在产品级走的是
**生成新视角**，不是按深度 warp。

## 二、Apple 的对应研究：SHARP（2025-12）

`Sharp Monocular View Synthesis in Less Than a Second`，arXiv 2512.10685，
代码 github.com/apple/ml-sharp。与本课题高度同构，值得逐条对照：

| 维度 | SHARP | 我们当前（vNext11 / 桌面双层） |
|---|---|---|
| 表示 | **两层** 768×768 网格上的 ~120 万个 3D 高斯，每个 14 属性 | 单深度标量驱动的网格 warp（+ 断边 splat） |
| 遮挡区 | **学出来的第二层** + 感知损失鼓励合理补全，无显式 inpainting 模块 | 显式 inpainting（SDXL / AOT-GAN）填隐藏背景 |
| 深度来源 | 深度解码器**随视图合成损失端到端训练**，另有 Depth Adjustment Module 学一张 scale map 化解深度歧义 | 现成单目深度（DA3）**冻结**，只拿来 warp |
| 目标视角 | 明确**只优化近视角**（头动、姿态微调），远视角承认会变糊 | 同样是近视角 |
| 规模 | 702M 参数、1536² 输入、A100 上 <1s | 端侧几十 MB |
| 许可 | **Apple ML Research Model License：仅研究用途，明令禁止商用** | — |

SHARP 对失败原因的判断（原文）：**"Monocular depth estimation is fundamentally
ill-posed, as multiple 3D configurations can produce the same 2D image."**
传统 warping 方法**假定深度是准的**；SHARP 的做法是不冻结深度、让视图合成损失反过来
改深度。

**因此 SHARP 不能作为组件使用（许可禁止商用，且 702M/A100 远超端侧），但它给出了明确
的诊断：把一个"为深度精度训练"的模型输出，当成"为视图合成服务"的几何来用，是错的。**

## 三、由此得到的、可在本项目验证的诊断

我们的位移场（`generate_assets.py:340-352, 894`）：

```
depth      = DA3(image)                      # 相对深度
disparity  = 1 / depth                       # 1/Z，物理上正确
normalized = (disparity - p05) / (p95 - p05) # 稳健归一化
flow       = depth_sign * (normalized - median) * depth_scale_px * direction
```

逐条核对物理：相机横移 t 时像点位移 Δx = f·t/Z ∝ 1/Z。所以

- **减常数无害**：`1/Z` 减一个常量等价于整幅平移，只是重新取景，不产生形变；
- **`depth_scale_px` 按目标跨度反算**也只是幅度选择，不是形变来源；
- **真正的形变来源只剩一个：`1/Z` 本身不准。** 深度对了，横移产生的就是**物理正确的
  透视变化**（正面平面刚性移动、斜面按透视改变），根本不会读作"橡皮形变"。深度错了，
  错的就是相对透视——**在人脸这种深度模型本来就起伏不实的地方最明显**，正是用户反复
  指出的位置。

这条诊断与 SHARP 的表述一致，且解释了为什么在两层表示上调任何参数（D126–D141）都无效：
**问题不在位移怎么算，在深度本身不是可用于重投影的几何。**

## 四、可商用的现成组件（本轮查到的）

| 组件 | 许可 | 规模 | 输出 |
|---|---|---|---|
| **MoGe-2**（microsoft/MoGe） | 代码 **MIT**；权重页标注 **mit**（可商用） | ViT-S 35M / ViT-B 104M / ViT-L 326M | **度量尺度点图**（H,W,3）+ 深度 + **法线** + 有效掩膜 + **归一化相机内参** |
| SHARP（apple/ml-sharp） | **仅研究，禁止商用** | 702M | 两层 3D 高斯 |
| gsplat | Apache-2.0 | 库 | 可微高斯光栅化 |

MoGe-2 是本轮最有价值的发现：它给的不是"相对视差"，而是**带内参的度量点图 + 法线**，
ViT-S 只有 35M——端侧可行。有了点图与内参，就能做**真正的透视重投影**，而不是拿一张
归一化标量乘一个手调幅度去近似视差。

## 五、判断

1. **不要再在"归一化相对视差 + 二维位移场"上调参数。** 物理上它只有在深度准确时才成立，
   而 DA3 的相对深度不足以支撑重投影；这是 D126–D141 全部落空的共同原因。
2. **下一步应验证的是几何本身，不是渲染。** 用 MoGe-2 的度量点图 + 内参做一次真正的
   相机横移重投影，看人脸／四角／玻璃杯的形变是否消失。这个实验只需要桌面，不需要训练，
   不需要改渲染器，成本很低，且**结论是二值的**：形变消失 → 路线换成"正确几何 + 重投影"；
   形变仍在 → 说明单目几何精度根本不够，必须走生成式新视角，那就意味着要么训练、要么等
   可商用的前馈模型出现。
3. **补全（显露区）与几何是两个独立问题，不要再混在一起判。** SHARP 用学出来的第二层，
   我们用 inpainting；在几何问题解决前，补全的任何改进都会被形变掩盖。

## 来源

- Apple Machine Learning Research, *Sharp Monocular View Synthesis in Less Than a Second*
  — https://machinelearning.apple.com/research/sharp-monocular-view
- arXiv:2512.10685（含方法细节与限制）— https://arxiv.org/html/2512.10685v1
- apple/ml-sharp（含 LICENSE_MODEL：仅研究用途）— https://github.com/apple/ml-sharp
- Apple Newsroom, visionOS 2（2D→空间照片生成左右眼图，而非深度图）
  — https://www.apple.com/newsroom/2024/06/visionos-2-brings-new-spatial-computing-experiences-to-apple-vision-pro/
- TechCrunch, visionOS 2 spatial photos — https://techcrunch.com/2024/06/10/visionos-can-now-make-spatial-photos-out-of-3d-images/
- microsoft/MoGe（MIT）— https://github.com/microsoft/MoGe
- MoGe-2 权重（mit）— https://huggingface.co/Ruicheng/moge-2-vitl-normal
- 9to5Mac, Apple SHARP 开源模型报道 — https://9to5mac.com/2025/12/17/apple-sharp-ai-model-turns-2d-photos-into-3d-views/
- TECHi, iOS 26 Spatial Scenes 机制说明 — https://www.techi.com/ios-26-spatial-scenes-3d-photo-ai-feature-explained/
