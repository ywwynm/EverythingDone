# Matting PoC · 第一轮（桌面 alpha 质量）

日期：2026-08-01。目标：为发丝级边缘提供 alpha 通道，与深度切割融合（LDI 边缘
羽化 + MPI alpha），消除深度模型无法表达的条缕级混合像素。候选：MODNet 优先
（上游 Apache-2.0，移动端人像 matting），BiRefNet（MIT）备选。

## 环境与产物

- 模型：`build/spatial-matting-poc/modnet_photographic.onnx`（25.9 MB，取自
  hf-mirror 的 Xenova/modnet ONNX 导出，MODNet photographic portrait matting）。
- 脚本：`run_modnet.py`（输入 (x-0.5)/0.5、REF 512 对齐 32；输出经双线性回原尺寸）；
  产物在 `out/`（alpha16/overlay/sheet + modnet_report.json）。
- 测试图：深度 PoC 同款发丝弱光人像 test1.jpg（1080×1440，推理输入 512×384）。

## 第一轮结果

| 项目 | 数值 | 说明 |
|---|---|---|
| 桌面 CPU 推理 | **0.05 s** @512×384 | 25.9 MB 模型，比任何深度模型都快一个量级 |
| 发丝左缘 alpha 过渡（10%→90%） | **中位数 5 px / P90 8.2 px** | 深度侧最好成绩为 DA3 的 9/43 px，且 alpha 是真混合系数而非硬指派 |
| 头发/面部区域 | 干净贴丝，外缘软过渡 | 正是融合需要的条缕级信号 |
| **躯干区域** | **不可靠** | 暗毛衣对暗背景时 alpha 大面积塌为 0，酒杯/手部部分丢失 |

目检（`out/test1_modnet_sheet.png`）：头部剪影完整、发丝外缘 alpha 梯度贴合条缕；
胸口以下被暗背景吃掉大块。**结论：alpha 只能做发缘局部细化信号，不能当全局前景
分割用**——融合设计必须以深度为主导，alpha 仅在高 |∇alpha| 的边缘带内修正深度
指派/羽化显示。

## 算子门槛（预检）

MODNet ONNX 为 **opset 11**，算子全部标准（19 种），但相对 r3 清单：
- 需要新增 `ai.onnx;11;...` 行（r3 只有 17/18 两行）；即便版本升转到 18，
  仍需补 `InstanceNormalization`、`GlobalAveragePool`（18 行现缺）等。
- **结论：MODNet 上真机必须随 r4 Runtime 重建**（构建管线已被 r3 全流程验证，
  增量成本约 20 分钟/单 ABI + 四 ABI 全量）。

## 第二轮门槛

1. 融合算法设计与桌面仿真：深度（DA3 闭合后）× alpha 的边缘带融合——LDI 侧
   cut 位置/羽化宽度由 alpha 梯度带决定；MPI 侧 alpha 直接进层 alpha。先在
   Python 蓝本上做拼图验收（发丝左缘放大）。
2. BiRefNet（MIT）对照：躯干稳健性与发缘质量是否两全；若仍偏科，维持
   「深度主导 + alpha 局部」设计不变。
3. 固定尺寸导出（对齐 32 的输入协议需与引擎预处理协商）、数值核对、
   `generate-ort-required-operators.py` 并入五+1 模型清单 → r4 重建。
4. 真机耗时/内存与全链路发丝验收（放大 + 方向矩阵），按 D7/D12/D13 发布。

## 第二轮进展（2026-08-01）

### 融合公式定案与蓝本仿真（`run_fusion_sim.py`）

公式（深度主导 + alpha 仅边缘带）：`depthMask = 闭合视差 > 0.5`；边缘带
B = dilate(mask, k) ∧ ¬erode(mask, k)（k=9 px）；带内 finalAlpha = MODNet alpha，
带外 finalAlpha = depthMask。仿真（DA3 真管线视差 + 简化双层平移合成 40 px，
`out/fusion_sheet.png` 与 `fusion_hair_zoom.png` / `fusion_mesh_zoom.png`）：

- **躯干安全性成立**：全图 alpha 合成（反例列）在暗衣区透背景；融合列不受影响
  ——「深度主导」正确挡住了 MODNet 的塌零区。
- **发缘差异在蓝本尺度不显著**：闭合后的深度掩码边界落在可见发丝之外的真背景
  像素上，量化阶梯与羽化差异被同色背景掩盖。设备端的阶梯/碎屑由「剥边 + 显露带
  补图纹理」机制放大，蓝本未建模该机制——**结论：融合的决定性收益必须在真实
  渲染器里验证（alpha 以全分辨率纹理接管断边显示），蓝本止步于公式正确性与
  躯干安全性**。
- 附带发现：闭合后 DA3 掩码把发丝混合带整体划入前景，混合像素随前景移动的
  「脏边」在蓝本合成中已不明显——与真机 D53 复验观感一致，说明当前无 matting
  的 D53 状态本身已接近蓝本可demonstrable的上限；matting 的边际收益集中在
  斜向细条缕与半透明纱状区，需真机放大取证。

### BiRefNet_lite 对照 — 完成，维持 MODNet 选型

同口径评估（onnx-community BiRefNet_lite，opset 17，固定 1024²，输出 logit 加
sigmoid；`out/test1_birefnet_alpha16.png` 与 `matting_compare_sheet.png`）：

| 项目 | MODNet | BiRefNet_lite |
|---|---|---|
| 模型体积 / 桌面 CPU | 25.9 MB / **0.05 s** @512 | 224 MB / 3.99 s @1024 |
| 发丝左缘过渡（中位/P90） | **5 / 8.2 px** | 6 / 14.3 px |
| 暗衣躯干 | 大面积塌零 | **稳健**（仅臂身间隙一处疑似真实镂空） |
| 许可 | 上游 Apache-2.0 | MIT |

结论：融合设计的全局掩码本就由深度主导（深度对暗衣躯干天然稳健），BiRefNet 的
躯干优势不改变设计；边缘带内 MODNet 的过渡更细、成本低两个量级。**维持
「MODNet 边缘带 + 深度主导」**；BiRefNet 记为备选（若未来发现 MODNet 在其它
题材照片上头部塌零，再评估以 BiRefNet 掩码替换带外部分，代价是模型体积与耗时）。

### 第二轮剩余（移交第三轮）

1. MODNet 固定尺寸导出协议（对齐 32 与引擎 letterbox 预处理的协商）、数值核对。
2. 算子并入清单（opset 11 缺口）→ **r4 Runtime 重建**（管线已验证）。
3. App 端融合实现：LDI 断边处以全分辨率 alpha 纹理接管显示（几何仍网格级），
   MPI 层 alpha 直乘；生成链新增 matting 推理段与派生 schema 扩展（alpha 通道
   落盘）。
4. 真机耗时/内存 + 全链路发丝验收（放大 + 方向矩阵）→ 按 D7/D12/D13 发布。
