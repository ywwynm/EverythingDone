# Dialog 粒子消散桌面验证

## 入口

**渲染 + 验收**（唯一入口）：

```powershell
& 'C:\Users\ywwynm\miniconda3\envs\everythingdone\python.exe' `
  tmp\particle-dismiss-tuning\render_curtain_model.py
```

它直接从 `ParticleDismissRenderer.kt` 抽取消散用的四段 GLSL 离屏渲染，
并对四个方向／种子跑一遍验收门限。产物写入 `frames-curtain/`：
`primary-frames/`（61 帧，严格 1.000 s）、`primary.mp4`、`contact.png`、
`layers.png`（合成／仅完整表面／仅粒子三行）、`metrics.json`。

**三列对照视频**：

```powershell
& 'C:\Users\ywwynm\miniconda3\envs\everythingdone\python.exe' `
  tmp\particle-dismiss-tuning\make_reference_comparison.py
```

把参考原片的 `3.28–8.38 s` 窗口、桌面 canonical、真机录制统一重采样到同一个
真实秒，输出 `cloth-motion-prototype/{reference,model,device}-normalized.mp4`
和并排的 `comparison.mp4`。真机窗口写在
`device-recordings/latest.json`，换录制只需改这个文件。

**桌面播放器**：`cloth-motion-prototype/physical.html`。三列同步播放，
空格播放／暂停，左右键单帧步进，滑块可逐帧对照。页面用 `fetch` + Blob URL
挂载视频——`python -m http.server` 不支持 Range 请求，直接用 `src` 会导致
无法 seek。

## 两端一致靠什么保证

桌面**不维护第二份 GLSL，也不维护第二份参数**。释放场、风场包络、寿命分布、
粒径比例等全部是 `DISMISS_FIELD_GLSL` / `DISMISS_VERTEX_SHADER` 里的
`const float`；真正的 uniform 只剩几何（origin/snapshot/grid/cell/viewport）、
时间、种子和飞行方向。因此同一种子下两端逐位一致，桌面上看到的就是真机上的。

释放核也在 Shader 内由 `uHashSeed` 与 `uSweepDir` 生成，没有 CPU 侧模型，
不存在需要同步的第二处实现。

## 当前模型（2026-08-30 复测后重建）

- **静止层**：`uTime >= curtainReleaseTime(cell)` 时整格 `discard`，否则原样
  绘制快照。没有 alpha 渐隐、孔洞噪声或相位滤波；交界的柔和只来自逐 cell 抖动。
- **释放场**：主核到卡片最远角的距离定义相位 1（自归一化，最大相位与种子、
  长宽比无关），两个次核只做局部提前、永不抬高最大值。值域映射到动画的
  `0 → 0.60`。
- **粒子层**：每 cell 一个粒子，`v = W(t)·d·s + k`；`W` 线性升到 `n≈0.50`
  后走平，位移取 `S(t) − S(birth)`；`k` 是固定不变的随机踢。粒径恒定，
  寿命 `lifeMax·(1 − √u)`，其生存函数即实测的 `(1 − age/lifeMax)²`；8% 的
  粒子取更长的 `lifeMax`，对应参考末段的稀疏尘埃。

依据与实测数值见
`docs/features/dialog-particle-dismiss/research-2026-08-30-reference-remeasure.md`。

## 历史脚本

`probe_*.py`、`analyze_*.py`、`cloth_motion_model.py`、`physical_release_field.py`
等属于 PBD 薄面时期的调参历史。那套模型已在 2026-08-30 删除（连同
`ParticleDismissClothModel.kt` 与 `ParticleDismissReleaseField.kt`），
这些脚本**不能**再作为当前版本的通过结论；保留仅供追溯。
