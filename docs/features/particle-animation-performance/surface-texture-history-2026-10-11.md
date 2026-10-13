# TextureView／SurfaceView 替换历史与卓易通兼容方案边界

2026-10-11：用户要求回查提交记录，确认此前从 TextureView 切换到 SurfaceView 的原因，再评估鸿蒙 7／卓易通的兼容建议。本轮读取 Git 提交正文、相关差异、性能记录及现行两条呈现分支，未修改应用代码或运行设备。

## 已核实的提交顺序

| 日期与提交 | 实际变化 | 当时要解决的问题 |
| --- | --- | --- |
| 2026-09-15，`6c583e34229ca8a48657d17fb7f4f4533b044f8f` | 粒子整体改成每窗口共享 SurfaceView，同时引入常驻 GL 线程／EGL 上下文、独立节拍、缓存复用、栅栏及 Surface 刷新率请求 | 原 TextureView 销毁的 `nDetachSurfaceTexture` 与 RenderThread 同步造成主线程停顿；粒子节拍受主线程阻塞；每帧 `glFinish` 和逐次准备增加等待；窗口 60 Hz 请求影响 120 Hz 涟漪。此提交包含多项优化，不能把所有收益都归因于 SurfaceView。 |
| 2026-09-17，`2ee940e3dc1f46ea67d88f982535a03821e4ff2a` | 仅卡片滑动／回聚路径重新使用同窗口 TextureView | 含原生阴影的快照与真实卡片需要同帧交接，解决原位阴影残留、双份半透明阴影及闪烁；普通弹窗没有整体退回。 |
| 2026-09-17，`2ff2430b6d93ddbf1b4f37b60877d450db23b050` | Android 14+ 卡片恢复共享 SurfaceView；窗口与 Surface 帧通过公开事务同步；旧系统保留 TextureView | 保留共享 Surface 的性能方向，同时解决卡片／阴影交接与 FAB 遮挡。新增 FAB 自有 SurfaceControl 子层，原 View 保留输入，绘制临时交接。 |
| 2026-09-18，`cff0e95443936fbc0791a8c0bbe70a593245310b` | FAB 前景接管推广到顶栏、标题、阴影等首页控件；覆盖左滑、回聚与选择移除 | 保持首页前景对粒子的遮挡关系。TextureView 分支则插在列表之后、原生前景之前。这一步与用户目前反馈的“整批控件动画期间消失、结束恢复”直接相关。 |
| 2026-10-07，`8fa04ace53e05567c35b253cd4395f831340611a` | 首页外观面板复用 Surface 同步交接 | 修复粒子撤下后真实面板未及时显示的空档。说明现行交接还承载后续功能修复，不能整体撤销这条路径。 |

## 性能证据应如何解释

- 9 月 15 日第二批记录：每次动画管线准备从 8.6–10.9 ms 降至 0.1–0.4 ms，主要对应程序、纹理和 GL 上下文常驻复用；不能说是 SurfaceView 单独省下这些时间。
- 同批“绘制完成到可提交”由约 8.8–15 ms 降至 0.3–2.1 ms，与去掉每帧 `glFinish`、改栅栏有关。前者含等待、后者主要是 CPU 提交，不能当作实际 GPU 运算量降低的倍数。
- 旧版主线程采样中的 `nDetachSurfaceTexture` 在改为共享 Surface 后不再出现，这才是与 Texture 生命周期直接对应的证据。
- 9 月 17 日同包小样本：Texture 按下至首个粒子提交为 160.3／115.7 ms，Surface 为 128.2／120.3 ms；两条路径的窗口帧耗时与间隔尾部波动明显。提交正文明确没有据此证明普遍帧率或功耗收益，不能宣称 Surface 在任何设备一定更快，也不能宣称两者没有代价差异。
- 以上均是历史设备／历史版本结果；当前飘散、流体、高刷新率实现已经继续演进，卓易通环境还没有同口径数据。

依据：[第二批结果](batch2-results-2026-09-15.md)、[Surface 同步与同包对照](../swipe-complete-animation/shadow-surface-2026-09-17.md)、[FAB 交接诊断](../swipe-complete-animation/fab-occlusion-2026-09-17.md)。

## 当前兼容分支并不等于旧版整体恢复

现行 `ParticleWindowSurface` 的 TextureView 与 SurfaceView 共用 `ParticleGl`、模型、状态缓存及绘制提交逻辑；两者都走栅栏而非恢复旧版每帧 `glFinish`。强制卡片 TextureView 不需要撤销这些优化。

但 TextureView 路径仍需窗口内合成，且其视图／表面随卡片动画创建和销毁；这些成本与原性能问题相关，必须重新测量。前景控件无需再转交独立 Surface 的优势，只能作为兼容性取舍，不能视为无代价的最终修复。

## 对本次建议的修正

1. 保留正常环境的共享 SurfaceView 默认路径及此前性能优化。
2. 优先通过应用内诊断核对卓易通的前景捕获图像、实际选择分支、SurfaceControl 层可见性和窗口透明度，将“内容生成错误”与“提交后不可见”分开；继续寻找能保留 SurfaceView 的局部修正。
3. TextureView 用作同包对照，验证绕开独立前景接管后现象是否消失；即使正常，也只定位到这条路径，不直接证明应永久切换。
4. 只有在该环境确实不能可靠运行所需 Surface 交接时，再评估针对该环境的可选择兼容路径，并验证连续滑动、回聚、首帧延迟和流畅度；当前没有批准或实施默认后端切换。
5. 弹窗真实窗口 alpha 异常仍需独立处理，卡片切 TextureView 不自动修复 Dialog 隐藏。

关联：[卓易通首页前景分析](../swipe-complete-animation/analysis-2026-10-11-droitong-foreground.md)、[卓易通弹窗分析](../dialog-particle-dismiss/analysis-2026-10-11-droitong-window-alpha.md)。
