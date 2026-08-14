# 空间图像 AI 模型的移动端通用加速路线调研

日期：2026-08-13

## 结论先行

**不采用“每颗 SoC 离线编译一份 context binary”的产品发布架构。** 可落地的通用路线是：

1. App 只分发一份与 SoC 无关的模型中间表示（ONNX、QNN DLC 或 TFLite）；
2. 支持 NPU 的设备在模型下载后的 `selfTest` 阶段做一次端上 JIT，并把生成的 context
   缓存在本机；
3. JIT 失败、内存不足、驱动不兼容或模型算子不能完整下沉时，自动切到 GPU；
4. GPU 也不可用时回落 CPU 或轻量模型。

这套方式不要求开发者提前知道用户手机的型号，也不要求用户手工编译。代价是每台设备在
首次安装、模型更新、NPU 运行时更新或系统更新后，可能重新编译一次。**通用分发并不会让
首次编译成本消失**；Big-LaMa 当前失败的根因仍需在模型图层面解决。

## 关键事实

### QNN 本身支持“统一模型 + 端上编译”

Qualcomm 官方把 QNN 产物分为三类：

| 产物 | SoC 无关 | 是否在手机上 graph prepare | 适合本项目 |
|---|---:|---:|---|
| QNN Model Library | 是 | 是 | 可用，但加载慢 |
| QNN DLC | 是 | 是 | 可用，适合统一分发 |
| QNN Context Binary | 否 | 否 | 启动最快，但会形成 SoC 矩阵 |

QNN DLC 与 TFLite QNN delegate 都会在目标设备上调用 Hexagon 编译器。ORT QNN EP 又提供
context cache，因此“第一次在用户手机上编译，之后直接复用”是官方支持的工作方式，而不是
项目自造机制。对首次编译时间敏感时，可以把
`htp_graph_finalization_optimization_mode` 先降为 `1`；QAIRT 2.49 及以后还提供
`enable_htp_graph_splitting`，可把大图拆成多个独立准备的子图，降低 graph prepare 的单次
压力。项目当前使用的 2.48 尚不能依赖后者。

资料：

- [Qualcomm AI Hub FAQ](https://workbench.aihub.qualcomm.com/docs/hub/faq.html)
- [Qualcomm AI Hub 编译格式示例](https://workbench.aihub.qualcomm.com/docs/hub/compile_examples.html)
- [ONNX Runtime QNN Execution Provider](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
- [新版 ORT QNN EP 文档](https://github.com/onnxruntime/onnxruntime-qnn/blob/main/docs/execution_providers/QNN-ExecutionProvider.md)

### LiteRT 也采用 AOT/JIT 双路线，但暂时不能取代现有 QNN 链路

LiteRT Next 的 NPU 接口支持 Qualcomm 与 MediaTek 的端上 JIT，并按模型、编译选项、厂商
插件和 Android build fingerprint 缓存编译结果。它还支持 CPU/GPU 部分回退，方向上很适合
长期做跨厂商统一入口。

但它的官方数据也说明：端上 JIT 对大模型可能需要数秒和数百 MB 至 1.5 GB 内存；缓存只会
改善第二次加载，不能挽救首次编译的内存峰值。当前 Qualcomm 官方支持列表也集中在
Snapdragon 8 Gen 1 及更新旗舰 SoC，覆盖面不足以作为唯一后端。因此本项目只适合先拿一个
模型做 PoC，不适合现在把已经跑通的 ORT+QNN 全部重写。

资料：

- [LiteRT NPU 加速与端上编译缓存](https://developers.google.com/edge/litert/next/npu)
- [LiteRT Qualcomm NPU 支持范围](https://developers.google.com/edge/litert/next/qualcomm)

### GPU 是必须存在的通用回退层

Vulkan/OpenCL 不需要为每颗 SoC 预编译模型，覆盖 Qualcomm Adreno、ARM Mali 等更广的
Android 设备。LiteRT GPU 可通过 `CompiledModel` 使用 GPU buffer、异步执行和零拷贝；
ncnn 提供 Android Vulkan、FP16/INT8 与不支持层的 CPU 回退；MNN 提供 OpenCL、Vulkan 与
ONNX 转换。三者都仍需逐模型验证算子覆盖、转换正确性和真实延迟，不能只凭后端名称判断
一定比 ORT CPU 快。

首选验证顺序：**LiteRT GPU 单模型 PoC → 若模型转换或算子覆盖不理想，再比较 ncnn
Vulkan 与 MNN OpenCL/Vulkan。** 不在 PoC 通过前迁移整条产品链路。

资料：

- [LiteRT GPU acceleration](https://developers.google.com/edge/litert/next/gpu)
- [ncnn 官方仓库](https://github.com/Tencent/ncnn)
- [ncnn Vulkan FAQ](https://github.com/Tencent/ncnn/wiki/FAQ-ncnn-vulkan)
- [MNN 官方仓库](https://github.com/alibaba/MNN)

## 各候选路线的裁定

| 路线 | 能否避免开发者维护 per-SoC 模型 | 首次成本 | 本项目裁定 |
|---|---:|---|---|
| ORT + QNN，端上 JIT/cache | 是 | 设备首次编译 | **主 NPU 路线，继续使用** |
| QNN DLC + 端上 JIT | 是 | 设备首次编译 | 可作为通用交付格式验证 |
| LiteRT NPU JIT | 是 | 设备首次编译，覆盖有限 | 单模型 PoC，暂不全量迁移 |
| LiteRT GPU / ncnn / MNN GPU | 是 | shader/图初始化，可缓存 | **通用回退路线** |
| 离线 QNN context binary | 否 | 开发侧按 SoC 编译 | 不作为主发布架构 |
| Flexible Context Binary | 否 | 把多份 SoC context 装进一个 DLC | 只隐藏矩阵，不消除矩阵 |
| ExecuTorch Qualcomm backend | 否 | 导出时仍指定 `soc_model` | 不解决本问题 |
| NNAPI | 表面上是 | 驱动差异大 | Android 15 已废弃，不采用 |
| MNN Hexagon backend | 是 | 运行时直接执行 HTP op | 仍是研究原型且要求较新 HTP，不进生产 |

ExecuTorch 的 Qualcomm 导出示例明确要求传入如 `QcomChipset.SM8650` 的
`soc_model`，生成的 `.pte` 仍是目标硬件特化产物。NNAPI 则已在 Android 15 废弃。

资料：

- [ExecuTorch Qualcomm backend](https://github.com/pytorch/executorch/blob/main/docs/source/backends-qualcomm.md)
- [Android NNAPI 迁移指南](https://developer.android.com/ndk/guides/neuralnetworks/migration-guide)
- [MNN Hexagon backend](https://github.com/alibaba/MNN/blob/master/source/backend/hexagon/README.md)

## Big-LaMa：不要再编译当前这张异常计算图

当前固定形状 Big-LaMa ONNX 约 208 MB，却有 17,480 个节点和 216 个 `Einsum`。原因不是
“LaMa 天生太大”，而是 Fourier/FFT 在导出后被展开为大量稠密矩阵运算。端上 graph
prepare 峰值约 2.5 GiB 并被 LMK/ANR 终止，继续调相同图的编译参数收益有限。

Qualcomm 已发布 LaMa-Dilated 的通用 ONNX、QNN DLC 和 TFLite 资产。当前官方数据中，
Snapdragon 8 Gen 2 的 Galaxy S23 上，ONNX/QNN/TFLite 分别约为 77.5/77.9/87.8 ms；
8 Gen 3 约 54.7～62.7 ms，8 Elite 约 42.8～59.3 ms，并且主要计算单元均为 NPU。这证明
“移动友好的 LaMa 图 + 统一模型包 + 端上 JIT”是成立的技术路线。

但官方默认权重是 `Dilated CelebAHQ`，训练域以人脸为主，不能直接假定适合空间照片中的
人物、家具、街景与复杂纹理。下一步先在项目现有九场景及更广的遮挡样本上做离线画质 A/B；
只有达到现有 Big-LaMa 的接受线才接入。如果不达标，应寻找或训练通用场景的
regular/dilated LaMa 权重，或者把 MI-GAN 作为低端设备的速度档，而不是继续硬编译当前
FFT 展开图。

资料：

- [Qualcomm LaMa-Dilated 模型卡与设备性能](https://aihub.qualcomm.com/models/lama_dilated)
- [Qualcomm LaMa-Dilated 通用资产与设备性能](https://huggingface.co/qualcomm/LaMa-Dilated)
- [LaMa 原项目](https://github.com/advimman/lama)
- [MI-GAN 官方实现](https://github.com/Picsart-AI-Research/MI-GAN)

保留 Fourier block 并通过 VkFFT 写自定义 GPU layer 在技术上可行，但需要自己维护算子、
内存布局、精度与 Qualcomm/Mali 驱动兼容，工程投入明显高于先验证移动友好的 LaMa 架构，
只列为后备研究项。资料：[VkFFT](https://github.com/DTolm/VkFFT)。

## MoGe-2：先按官方方法静态导出，不换模型

MoGe 官方 ONNX 文档已经给出固定输入形状和固定 `num_tokens` 的导出方法：继承
`MoGeModel`，把 `num_tokens` 从运行时输入改为模型常量，并设置 `dynamic_axes=None`。
这正好去掉当前 ONNX 的动态尺寸、运行时 token 数与控制流 `If`，是进入 QNN 的最短路径。

因此 MoGe-2 的顺序是：

1. 先按官方脚本导出一份固定 518×518、固定 token 数的 ViT-S；
2. 做 ONNX 对拍后，用现有 ORT+QNN 在手机上以低优化档 JIT，并缓存 context；
3. 若仍不能完整下沉或首次编译超预算，再测 GPU；
4. 只有前两条都失败才考虑换成 Depth Anything V2 等轻量深度模型。

Depth Anything V2 虽已有 Qualcomm NPU 优化版本，但只输出深度，不能等价替代 MoGe-2 的
point map、法线、相机内参与 metric scale；它只能作为兼容档或降级档。

资料：

- [MoGe 官方固定形状 ONNX 导出说明](https://github.com/microsoft/MoGe/blob/main/docs/onnx.md)
- [Qualcomm Depth Anything V2 模型卡](https://aihub.qualcomm.com/models/depth_anything_v2)

## 推荐的产品后端结构

```text
统一模型包
   │
   ├─ NPU 可用且模型受支持
   │    └─ 首次前台 JIT → 本机 context cache → 后续直接加载
   │
   ├─ NPU 编译/加载/执行失败
   │    └─ GPU（LiteRT / Vulkan / OpenCL）
   │
   └─ GPU 失败或低内存设备
        └─ CPU 或轻量模型
```

缓存至少绑定模型 hash、运行时版本、编译选项、SoC/HTP、Android build fingerprint；失效时
安全重编译。首次 JIT 必须放在可见界面或前台 Service 中，显示“正在为本机优化模型”，
允许取消，并在失败后无感回落，不能放在后台广播进程中。

## 最小验证计划

### P0：先证明两张关键模型图可用

1. **MoGe-2**：按官方固定形状/固定 token 脚本重导出，对拍后跑现有 ORT+QNN JIT/cache。
2. **Big-LaMa**：下载 Qualcomm LaMa-Dilated 的通用资产，只做桌面/云端画质 A/B；通过后
   才进端上时延测试。

### P1：补齐通用回退

选择 Big-LaMa 候选或 MoGe-2 静态版中的一个，做 LiteRT GPU 单模型 PoC；记录转换覆盖、
首次初始化、稳定推理、峰值内存与 Qualcomm/Mali 各一台设备的结果。失败后再测 ncnn/MNN，
不同时铺开三个运行时。

### P2：降低端上 NPU 首次编译压力

现有 QNN 链路先测 `htp_graph_finalization_optimization_mode=1`。等可用的 QAIRT 2.49+
Android runtime 发布后，再测 `enable_htp_graph_splitting=1`；它是增强项，不阻塞 P0。

## 不做的事

- 不收集所有用户 SoC 并维护离线 context 矩阵；
- 不把 Flexible Context Binary 当作通用编译方案；
- 不为解决兼容性而迁移到仍要求 `soc_model` 的 ExecuTorch QNN；
- 不采用已废弃的 NNAPI；
- 不继续让当前 FFT→Einsum 的 Big-LaMa ONNX 在手机上硬编译；
- 不在单模型 PoC 之前重写整条推理栈。
