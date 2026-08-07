# Opus 调研 3/3：Android 端侧工程构件与可行性（2026-08-04）

## 结论速览

1. 真 3D 网格端侧成本无压力：~27 万顶点/54 万三角形每帧仅改 MVP；真实成本
   在 TBDR 顶点带宽 ~1.2GB/s（分块 ≤65,535 顶点 + uint16 索引 + 属性压缩解决）
   与微三角形（Adreno 建议 ≥4px/三角形 → 平坦区自适应降密度）。GLES2 两硬限：
   顶点纹理采样不可依赖（CPU 生成期烘焙 VBO，反而更好）、uint32 索引需扩展。
2. 没有可直接借鉴的维护中 Android 开源实现；权威工程参数 = Meta OSP（iPhone
   11 Pro 全流程 1098ms，网格化仅 11ms，产物 300-500KB chart 网格；Farbrausch
   补全网络仅 0.37M 参数——因为在 LDI 上补全，上下文天然按层分离）。
3. **烘焙视角路线否决**（Plenoptic Sampling：无鬼影需相邻视角 ~1px 视差 →
   ±20px 范围需 ~40 视角不可承受；Leia 4V 也是实时渲染非烘焙）。**烘层不烘
   视角**：3-4 层 RGBA+深度网格，连续视差、无鬼影、GLES2 普通 alpha 合成。
4. 端侧 3DGS：渲染已证明（Mobile-GS SD8G3 1600×1063@116FPS/4.6MB，代码未
   发布），前馈重建未就绪；Apple SHARP（单图前馈高斯，标准 GPU <1s）权重
   许可 LICENSE_MODEL 未核实 = 路线 C 一票否决项待查；GLES2 跑不了（需
   GLES3.1/Vulkan）。零成本试探法：桌面 SHARP 产 splat → WebView antimatter15/
   splat 看观感。
5. 补全升级唯一合规选项 = LaMa（45.6M/174MB float/Apache-2.0/512²，Carve
   ONNX 现成；FFC 的 irfftn 大概率回落 CPU，生成期秒级可接受）。**更省的
   顺序：先改补全条件（在背景层坐标系内、上下文显式排除前景），再考虑换
   网络**——Farbrausch 0.37M 够用即此理。
6. 传感器范式：GAME_ROTATION_VECTOR ✓ + remapCoordinateSystem（平板自然
   方向横屏必须验证）+ 相对姿态慢泄漏回中 + 1€ 滤波（切空间）+ 向光子时刻
   前向预测（Cardboard EKF 参考）+ 显式 5000µs 采样。
7. 三条路径：A 真 3D 网格+透视相机（保 GLES2 保资产；风险=透视暴露更多
   空洞）；B 分层网格 LDI + 深度感知补全升级（LaMa/条件改造；生成期数秒）；
   C 前馈 3DGS（每环缺一块，先做许可与 WebView 验证）。

## 必做前置验证

1. SHARP LICENSE_MODEL 条款（决定路线 C 存在性）
2. LaMa ONNX 双真机耗时实测（确认"数秒"非"数十秒"）
3. 平板 remapCoordinateSystem 分支验证

## 关键来源

Adreno 最佳实践 docs.qualcomm.com/doc/80-78185-2；OSP 项目页+arXiv 2008.12298；
Plenoptic Sampling dl.acm.org/10.1145/344779.344932；Mobile-GS（ICLR26）；
Apple SHARP apple.github.io/ml-sharp；LaMa aihub.qualcomm.com/models/lama_dilated
+ HF Carve/LaMa-ONNX；Cardboard github.com/googlevr/cardboard；1€ Filter
gery.casiez.net/1euro；Android sensors 官方文档；Filament（Apache-2.0）。
