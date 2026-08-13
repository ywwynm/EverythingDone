# 调研：2026 年怎么解"剪影处的半透明条带"（2026-08-11）

用户在 00 的 328° 又看到人物右侧的玻璃条带，且"效果还不如产品档"，要求上网查更好的思路。

## 一句话结论

**这一整条线在 2026 年的答案是：不要在补全里修，要在表示里修。**
两篇独立的 2026 工作——一篇从立体转换那侧来、一篇从影视工业那侧来——
**收敛到了同一个结构**：在软边界处**联合预测 α + 分层颜色 + 分层深度**，
用分层 warp 出图，补全退化成下游的收尾。我们现在做的事全部在"带里填什么颜色"这一层，
而它们说问题出在"边界本身是二值的"。

## 两篇对位的工作

### αDepth（arXiv [2606.00386](https://arxiv.org/html/2606.00386v1)，2026-05）

只在**软边界的局部**，一次前向同时输出：α（用 Circular Alpha Representation 编码，
规避多目标场景的 alpha valley）、分层颜色 `I_FG / I_BG`、分层深度 `D_FG / D_BG`。
然后做 **layered warping**：只在软边界区用分层量替换，不透明区原样保留。
它明确宣称消除的就是 **background bleeding 与 aliasing artifacts**。

- 用**深度图当引导输入**，不需要 trimap 或用户标注 → 场景级、无交互；
- **补全仍然需要**：warp 完交给"scene painter"补洞、"color fuser"融合；
- **没有放出代码和权重**（本轮复核，仍然没有）；训练 6 天 / A6000，
  参数量与推理耗时论文未给。

### HairGuard（arXiv [2601.03362](https://arxiv.org/abs/2601.03362)，2026-01，ETH + DisneyResearch|Studios）

同一个问题的工业侧版本，结构更完整，**而且有几处直接对上我们的痛点**：

1. **Depth fixer**：自动识别软边界区域，用 gated residual 只在那里修深度、
   不动全局深度，**可插在现成深度模型之上**——也就是说可以挂在 MoGe-2 后面，
   不必换几何模型；
2. **深度前向 warp**保留高保真纹理（与我们同构）；
3. **Generative scene painter**：补 disocclusion，**并且明确"消除软边界内多余的背景
   artifact"**——就是我们看到的那条半透明副本；
4. **Color fuser**：**自适应地融合 warp 结果与补全结果**。

第 4 条是关键：这正是我们 D178 那个"逐条带在两档之间挑"的**学习版**，
而且它作用在**渲染出来的图**上，不是烘焙好的资产上。

训练数据的配方也给了：**用图像抠图（matting）数据集**造软边界监督。
同样**没有放出权重**（Disney Research 页面只有论文与视频）。

## 立体转换那一侧：范式已经从"warp + 补一层"换成"条件生成"

| 工作 | 做法 | 相对我们的意义 |
|---|---|---|
| [M2SVid](https://m2svid.github.io/)（2025-05） | 把**左视图 + warp 过的右视图 + disocclusion 掩膜**一起当条件喂进 SVD，注意力层对 disoccluded 像素开全注意力 | 比 StereoCrafter **快 6×**、用户研究里被选为最好的次数是第二名的 **2.6×**。它批评前人"只在 disocclusion 掩膜内补"，正是我们的做法 |
| [SplatDiff](https://arxiv.org/pdf/2502.12752)（2025-02） | splatting 引导的扩散，对齐合成 | 在合成质量与一致性上优于 StereoCrafter |
| [Eye2Eye](https://arxiv.org/html/2505.00135)（2025-05） | 直接单目转立体，**不走 warp-and-inpaint** | 说明"先 warp 再补洞"并非唯一解 |
| [StereoPilot](https://arxiv.org/pdf/2512.16915)（2025-12） | 统一的生成先验 | 把方法分成多阶段管线 vs 端到端两族 |

**共同点：它们都不把补全结果当成一层烘焙下来，而是让生成器同时看到原图、warp 结果
和洞的掩膜。** 我们受"必须烘焙成资产"的约束（渲染期只能光栅化），拿不到这条路——
但**color fuser 那一步是可以烘焙的**：它融合的是两份已有的图，不是逐视角生成。

## 对我们的可执行结论（按代价从低到高）

1. **先承认问题的层级**：现在的缺陷是**表示**问题不是**补全**问题。
   继续调掩膜／换补全模型／调 Poisson，天花板就在那里。
2. **把软边界从 0.99% 提到全剪影**（不需要新模型）：
   D164 的 α 是反解的，要求 |F−B| > 17 级才算得出来。改用**抠图模型沿每条断边跑
   trimap**（断边两侧各 k 像素当未知区）拿 α，覆盖率能到全部剪影。
   本地已有 BiRefNet_lite 的引用，抠图数据集正是 HairGuard 的训练配方来源。
3. **深度侧的 depth fixer**：HairGuard 说它是 plug-and-play 的。我们没有权重，
   但"只在软边界区修深度、不动全局"这个约束本身可以手工实现——
   注意 D162 已经证明**任何把断崖变缓的平滑都更差**，所以必须是"分层"而不是"平滑"。
4. **color fuser 的手工版**：我们已经有 D178 的逐条带判定，它是硬选择 + 羽化。
   可以改成按**局部置信度**加权融合（例如按"补全区域内该处离真实像素多远"），
   而不是二选一。
5. **降视差仍然是唯一零成本的手段**（D157/D169）：带宽 ≡ 视差 ≡ 需要凭空生成的面积。

## 一条必须记下的现实

**αDepth 与 HairGuard 都没有放权重。** 这条路要么自己训（数据配方两篇都给了：
合成抠图前景 + 真实背景），要么用手工版逼近。**不能指望下载一个模型就解决。**

## 来源

- αDepth（2026-05）— https://arxiv.org/html/2606.00386v1
- HairGuard / Guardians of the Hair（2026-01，ETH + Disney）— https://arxiv.org/abs/2601.03362
  ／ https://studios.disneyresearch.com/2026/05/31/guardians-of-the-hair-rescuing-soft-boundaries-in-depth-stereo-and-novel-views/
- M2SVid（2025-05）— https://m2svid.github.io/
- SplatDiff（2025-02）— https://arxiv.org/pdf/2502.12752
- Eye2Eye（2025-05）— https://arxiv.org/html/2505.00135
- StereoPilot（2025-12）— https://arxiv.org/pdf/2512.16915
- SLIDE（ICCV 2021，两篇 2026 工作共同的思想源头）— https://ar5iv.labs.arxiv.org/html/2109.01068
- Referring Layer Decomposition（ICLR 2026）— https://arxiv.org/pdf/2602.19358
- Object-level Scene Deocclusion — https://arxiv.org/pdf/2406.07706

---

# 补充：要不要直接换成 NVS（2026-08-11 用户提问）

## 找到一个几乎完全对位的工作：SHARP（Apple）

[Sharp Monocular View Synthesis in Less Than a Second](https://arxiv.org/html/2512.10685v1)，
代码与权重在 **[github.com/apple/ml-sharp](https://github.com/apple/ml-sharp)**。

| | SHARP | 我们现在 |
|---|---|---|
| 输入 | 单张 1536² RGB | 单张照片 |
| 输出 | **一次前向出 3D Gaussian**（768² 网格 × **2 层**，约 120 万高斯） | MoGe 深度 + 遮挡带第二层（颜色+深度） |
| 遮挡内容 | **不用单独的补全模型**。第二层由 CVAE 式的 depth adjustment 模块直接预测，代表"被遮挡区域与视角相关效果"；训练时用 view frustum masking 只监督原视角可见处，用 Gram 矩阵项鼓励合理的"补全" | SAM3 分割 + 两遍补全（Moebius/Big-LaMa）+ 梯度域 + 逐条带判定 |
| 目标视角范围 | 明确是"**nearby views**，支持 AR/VR 头显里的自然姿态位移"，**不追求大幅移动** | 4.5cm 小幅圆周 |
| 规模/速度 | 702M 参数（340M 可训），Depth Pro 编码器 + DPT 解码器；<1s / A100，渲染 **100+ FPS** | MoGe-2 ViT-L + SAM 3（3.44GB）+ 两个补全模型 |
| 产物 | `.ply` 3DGS，通用渲染器可读 | 自研两层点云 + 一堆渲染期后处理 |

**它的目标视角范围、烘焙一次 + 实时渲染的形态、两层表示，与我们逐条对得上；
而"遮挡内容不用单独补全模型"这一条，正好绕开我们卡了一整轮的所有问题**——
掩膜怎么挖、用哪个补全模型、Poisson、逐条带选择，在它那里都不存在。

## 但有三件事必须先查清楚，不能直接下结论

1. **许可证**：仓库有单独的 `LICENSE` 与 `LICENSE_MODEL`，抓取到的页面没有写明具体条款
   与是否允许商用。Apple ML 的模型许可**常常是仅研究用途**。这一条不查清，后面都白谈。
2. **端侧可行性**：702M 参数、1536² 输入、Depth Pro 编码器；没有任何 ONNX/CoreML/
   移动端的说明。我们现在整条链也不轻（MoGe-2 + SAM 3），但 SAM 3 本来就只是桌面验证，
   端侧另有小模型计划；SHARP 是**一个不可拆的大模型**，换不了小的。
3. **质量是否真的更好**：论文没有与 SLIDE / 3D Photography 直接比，只比了 TMPI。
   我们自己的痛点（发丝、玻璃、毛绒边缘的半透明条带）它有没有解决，**必须自己拿
   00/01/05 跑一遍才知道**——它的第二层是学出来的，未必比我们的显式方案更干净。

## 建议的验证顺序（代价从低到高）

1. 读 `LICENSE_MODEL`，确认能不能商用。不能 → 只能当**上界参照**，用来判断
   "我们离一个端到端方案还差多少"；
2. 在桌面上拿我们九个场景跑一遍 SHARP，出 `.ply`，在同一套圆周巡检下与当前管线逐块对比。
   **这是唯一能回答"该不该换"的实验**，而且不需要改我们任何代码；
3. 若质量确实更好但端上跑不动 → 把它当**教师**：用它的输出监督一个小模型，
   或者只借它的"第二层由网络直接预测、不做显式补全"这个结构。

## 顺带：其它值得记的 NVS 线索

- [GenWarp](https://arxiv.org/html/2405.17251)：不做显式 warp，而是在扩散的自注意力里加
  **跨视角注意力**、以 warp 信号为条件——大视角变化下质量更好；
- [WarpGAN](https://arxiv.org/html/2511.08178)：3D GAN inversion + 基于风格的新视角补全；
- [SplatDiff](https://arxiv.org/pdf/2502.12752) / [M2SVid](https://m2svid.github.io/)：
  已在上一节记过，共同点是**让生成器同时看到原图、warp 结果和洞掩膜**。
