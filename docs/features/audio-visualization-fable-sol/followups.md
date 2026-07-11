# 待办 · audio-visualization-fable-sol

## 2026-07-10 迁移审查新增

- **后台与队列**：把 `pendingFrames` 改成单槽 latest frame，离散事件使用有上限队列；动画条件加入窗口可见性，
  并确认 Fragment 进入后台时是否继续占用麦克风。
- **感知流速真机校准**：双时间尺度表层事件率、75% subdivision 保底和 tempo 正向佐证已经在 Python/Android 同步实现。等待用户用阿里云 Debug 对 `20260710234846.wav`、`20260710235706.wav` 对应场景做真机主观确认；若长录音约 0.83 的中位 `flow01` 仍过快或偏慢，只调整表层密度标定/保底比例，不提高物理上限或全局 `flow_gain`。
- **差分回归测试**：把 Python 版输出固化为 fixture，覆盖 Analyzer、onset/beat、FeatureMapper 与 Simulation；
  当前已有第 0 层颜色策略、运行时容器几何、超声泵动近静音/稀疏可听脉冲、采集启动低频预热，
  以及 onset/模态目标不得直接重塑既有浪形的测试。

首版已可运行并发布，以下项待真机反馈后处理：

- **性能**：`WaveVisualizerFableSol.drawHighlights` 每层每帧分配多个长度约 216 的 DoubleArray，
  九层 × 60fps 的 GC 压力较大；物理为九层 × 216 点 × 120Hz 子步。若真机掉帧，可优先：
  高光渲染改用复用 scratch buffer、必要时降 N_POINTS 或 PHYSICS_HZ、减少参与高光的层数。
- **配色/环境天空观感**：环境天空以白为 base、记事色做极浅染色（`environment_tint`）。若与对话框
  观感冲突（例如白底过重），再讨论是否弱化天空或改 base。
- **静止/停止态**：无音频帧超过 200ms 调 `applySilence` 衰减；真机确认停止录音后水面收敛是否自然。
- **参数固化**：原版可调参数已按默认值硬编码，暂无调参入口；若需现场微调视觉，再考虑最小暴露方式。
