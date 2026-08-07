# RF-DETR Seg Nano 端侧实例分割 PoC（2026-08-02）

## 结论

RF-DETR Seg Nano 通过真实 checkpoint、官方 ONNX 导出、桌面 CPU ORT 数值运行和 12 张
跨场景图像检查，适合成为**可选的自动实例 ownership provider**。它能把相邻人物、车辆、
动物和常见物体分成独立身份，从表示层上避免“所有主体共用一个位移”以及脸部逐像素拉伸。
它不负责未知类别、透明物体、深度层序、发丝 alpha 或隐藏背景生成，因此不能单独解决全部
空间照片伪影。

本 PoC 的准入不是“模型越多越好”：RF-DETR 只接管实例身份；MODNet 仅细化人物软边缘；
深度模型决定层序；MI-GAN/AOT-GAN 只生成实际显露的隐藏区域。各信号职责互斥，失败时均
可回退，避免不同模型重复改写原图。

## 上游与许可

- 官方仓库：<https://github.com/roboflow/rf-detr>
- 审计 commit：`f50258b07d51efc23771a9418dc13f20de71866b`（2026-07-31）
- 变体：RF-DETR Seg Nano，ICLR 2026；官方配置输入 312×312、100 queries。
- 代码和权重分发许可：Apache-2.0；归档许可文件 SHA-256：
  `44c30d89285a2a173b72470a2bdbadb031b03624d2fc1a8a366f962de9744020`。
- 原始 checkpoint：`rf-detr-seg-nano.pt`，134,545,398 bytes；MD5
  `9995497791d0ff1664a1d9ddee9cfd20`。

EdgeTAM 的官方仓库为 <https://github.com/facebookresearch/EdgeTAM>，审计 commit
`7711e012a30a2402c4eaab637bdb00a521302c91`（Apache-2.0）。它不承担自动实例发现；后续
PoC 只使用 RF-DETR 已发现实例的 box 做少 prompt 边界细化，避免 automatic mask grid。

## 导出 ABI

使用 RF-DETR 官方导出路径生成：

- 文件：`rfdetr_seg_nano_312.onnx`
- 大小：122,831,761 bytes（117.14 MiB）
- SHA-256：`e126db3d03364ddad43299cdb354e0e85a12719a695e1ded3f271012b0d4fa97`
- opset：17
- 输入：`float32[1,3,312,312]`，RGB、ImageNet mean/std、直接双线性缩放
- 输出：`dets[1,100,4]`、`labels[1,100,91]`、`masks[1,100,78,78]`
- 分类输出按 COCO 稀疏 category ID 直接落槽：slot 0 保留，slot 1..90 均为前景类别；
  使用逐类 sigmoid/focal 分类，没有独立 no-object 槽。后处理扫描 1..90。

导出报告位于本地构建目录
`build/spatial-segmentation-poc/export/export-report.json`。模型没有进入 `app/src`、assets
或 APK；发布脚本只把不可变对象和许可上传到阿里云模型目录。

## 数值与性能 PoC

Windows 桌面 CPU、ONNX Runtime CPUExecutionProvider 的结果：

- session 创建：246.1 ms；
- warm inference 中位数：59.7 ms（5 次：57.8～62.5 ms）；
- session RSS 增量：约 138.1 MiB；首轮推理后总 RSS 增量：约 237.2 MiB；
- 12 张图均完成有限值输出，包含两张现有故障探针，以及室内、办公室、交通、静物、山景、
  雕像、食物、动漫人物、餐厅和村庄场景。

上述数据只证明模型图、预处理和后处理契约可运行，不冒充 Android 真机性能。App 按
总 RAM ≥6 GiB、当前可用内存 ≥1 GiB 准入，并在模型安装后用 r6 Runtime 创建真实 session
做自检；目标手机的冷启动、P50/P95、峰值 PSS、温升和取消恢复仍须用户包复测。

## 跨场景质量结论

PoC 使用 0.45 置信度检查，同时在 App 后处理中加入以下通用约束：

1. 最多 12 个互斥实例，人物/车辆/动物优先；
2. 餐桌、床、沙发等支撑面覆盖超过 18% 时不成为刚性层；
3. 非主要类别覆盖超过 52% 时回退连续表面；
4. 竞争后面积低于 `max(32, 像素数/2000)` 的残片不落盘；
5. 未分割区域保持深度表面，不能为了“层数多”而强制归类。

观察结果：两张人物探针能保持不同人物身份；交通场景能区分多车；室内和山景不会强行
把整幅画面拆层；食物图中覆盖大部分画面的餐桌/托板不会作为整体刚性层。RF-DETR 的
78×78 mask 边界不足以直接承担发丝，因此人物边界继续由 MODNet soft alpha 扩展，并按
最近人物 label 分配；非人物实例不会被人物 matte 吞并。

## 类别 ABI 纠错与 attachment 审计

官方源码复核证明初版文档把 slot 90 误写为 no-object。代码已改为忽略 slot 0、保留
slot 1..90，并加入两端槽位回归。随后用 ZipDepth、DAV2、DA3 分别复算 12 张样本的实例
中位深度、八邻域接触和布局：只把满足类别白名单、接触、相对面积、深度邻近与人物布局
约束的手持／穿戴／身体部件合入人物父运动层；人与人、仅轮廓接触的前景物继续独立。
三个深度模型下结论一致，没有使用两张故障图的坐标特判。

## EdgeTAM 少 prompt 边界细化 PoC

官方 `edgetam.pt`（56,116,523 bytes，SHA-256
`ed2d4850b8792c239689b043c47046ec239b6e808a3d9b6ae676c803fd8780df`）拆为三图：

- `edgetam_image_encoder_1024.onnx`：19,755,129 bytes；
- `edgetam_box_prompt_encoder.onnx`：52,939 bytes；
- `edgetam_mask_decoder.onnx`：16,384,875 bytes。

三图均为 opset 17，PyTorch／ORT 最大绝对误差低于 `2.8e-5`。以 RF-DETR box 提示 12 张、
71 个实例：encoder 中位约 102 ms，每实例 decoder 中位约 11.6 ms，RF/Edge IoU 中位
0.798。原始 Edge mask 的边界梯度对齐大多更好，但会在脸和衣物内部打孔，因此最终融合
只允许它在一个 RF mask cell 对应的轮廓窄带内选边，锁定 RF 内部并限制最大扩张；同时用
predicted IoU、RF/Edge IoU 和面积比做逐实例质量门。62/71 个实例被接纳，融合后 59/71
边界对齐提升，中位提升比 1.296，内部孔洞消失。完整桌面进程峰值 RSS 约 758 MiB。

新增算子仅为 opset-17 的 `ArgMax`、`ConvTranspose`、`Flatten`、`GreaterOrEqual`、`Pow`
和优化器生成的 `com.microsoft.FusedGemm`；已纳入十图并集的 reduced ORT `1.28.0-r6`。
四 ABI 完整构建及逐包 hash/ELF/入口校验通过，stable catalog `20260801183734`、EdgeTAM
bundle、许可和 r6 已发布，公网 catalog 与本地逐字节一致，Range 续传返回 206。该数据仍
不能替代 Android 真机验收，因此 EdgeTAM 只按总 RAM ≥8 GiB、当前可用 ≥2 GiB 的可选增强
接入，任何失败逐实例或整模型回退 RF-DETR。

本地证据位于 `build/spatial-segmentation-poc/edgetam-onnx-export/`、
`edgetam-refinement-benchmark/` 与 `benchmark-app-rules-r2/`，均不进入 APK。

## 已知边界与后续验收

- COCO 是闭集：建筑结构、复杂家具部件、抽象图案和未知类别可能留在连续表面；这是安全
  回退，不应以低置信度 mask 强行补齐。
- 每个实例当前使用单一代表深度，适合人物和多数独立物体；大型斜面、地面、墙面继续使用
  连续/平面几何，不能刚性化。
- 透明玻璃、反射、运动模糊和极细发丝仍需要 alpha/confidence 与可见性链处理，分割模型
  本身不会生成正确隐藏内容。
- 需要在真实陀螺仪大视角下比较“关闭实例分层 / 开启 RF-DETR”，覆盖人物、多人物、手持物、
  大型支撑面、未知物体和复杂室内；不得只用两张探针调阈值。
- EdgeTAM 静态少 prompt 桌面 PoC 已通过；仍需 Android 时延、峰值 PSS、取消和低内存回退
  验收。Spatial Video tracking 是独立后续任务，不能由静态逐帧 decoder 结果代替。
