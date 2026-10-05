# 粒子弹窗当前状态核对（2026-09-26）

用户要求先回顾其他会话后的进展，等待后续任务。本次只核对 Git、当前源码和功能记录，没有编译、发布、连接设备或修改实现。

## 相对 9 月 13 日的主要变化

- 渲染结构已经重写：原 `ParticleDismissRenderer` 与 `ParticlePlaybackClock` 删除，由常驻 `ParticleGl`、逐步推进的 `ParticleSession`、`ParticleClock` 和按宿主共享的 `ParticleWindowSurface` 承担。普通并发弹窗合成进同一 SurfaceView，每拍交换一次；需要窗口内原子交接的卡片等路径另有 TextureView。出帧和预计算可使用同线程、同共享组的两个优先级上下文，能力不满足时回退。
- 出现仍倒放共同消散轨迹、默认从下方扇区进入，基础播放时长 0.6 秒；现默认跳过倒放开头几乎无粒子的尾段。新建记事可提前预算和核对预测素材，这项不能直接当作任意弹窗都已提前算好。
- 后续修复涵盖硬件离屏抓图、独立 Surface 合成、FableSol SDR/HDR 切换交接、透明 Activity 首帧暗层尺寸，以及叠层弹窗被下层独立窗口遮挡。叠层用 `ParticleElevatedHost`；连续 A 关闭、B 打开仍保留共同表面交接。
- 性能专项移到 [particle-animation-performance](../particle-animation-performance/README.md)：逐片绘制记录、压力活动区域、异步预计算、真实 vsync 节拍、屏障范围、共享内存和工作组变体等均有独立取舍。源码目前默认采纳五项第二阶段优化；不是研究文档里列出的每个候选都打开。
- GPU 兼容有两项关键修复：顶点 SSBO 改为实例化属性，解决 Mali/Maleoon 链接失败；压力累积改用标量数组原子操作，修正 Maleoon 上贡献错误落进第零格、导致压力投影实际为空。修正前的高帧率不能与修正后直接等价比较。

## 当前设置与复用边界

- `ParticleTuning` 的四项全局设置为粒子大小、流动精细度、颜色精度、性能自动调节。未显式改粒径时沿用规则下限，流动精细度默认 100，颜色默认 RGBA16F，性能自动调节开关默认关闭。自动调节是按呈现帧数判定的可选降档，不是已启用的普遍机型降档。
- 新建记事、左右滑动／取消回位、列表选择操作移除都已复用粒子管线；滑动消逝起点还会固定到最初触点。后续修改共同代码时，须按实际影响复核这些入口。
- 9 月 24–26 日碰一碰加入 `ParticleSink`、`SINK_FIELD`、逐片终点／颜色接手及延迟提供真实前景等能力。`ParticleDismissSpec.sink` 默认 null，普通弹窗不启用吸入终点，也不编译该着色器分支。碰一碰参数在 `shared/tap-transfer/particles.properties`，不能当作普通弹窗参数。
- 与本任务此前提交 `5920b3174` 相比，`shared/particle-dismiss/model.json`、`rules.properties` 和三份共同释放／流场资源没有变化；外围调度、着色器实现与可选路径已显著变化，不能按旧渲染器继续推断。

## 验证边界与后续阅读

弱 GPU 的默认高质量档仍然较重。性能专项记录中，华为使用者自选的 2.0 dp／20 次与默认档表现差异很大；自动调节虽已实现，当前默认关闭。性能自比较、跨端一致和共同模型一致，也不等于与华为参考视频达到近像素一致。本次未重新测量任何设备，9 月 13 日启动时间只是历史数据。

后续入口：

- [性能专项最新结果](../particle-animation-performance/per-frame-phase2-results-2026-09-18.md) 与 [待办](../particle-animation-performance/followups.md)，注意区分已被后续结果取代的历史条目。
- [叠层弹窗](stacked-dialog-z-2026-09-16.md) 与 [透明 Activity 暗层](activity-host-dim-2026-09-15.md)。
- [碰一碰动画设计](../thing-transfer/design-2026-09-25-particle-motion.md) 与 [移植记录](../thing-transfer/plan-2026-09-25-particle-port.md)。

本次检查时，共同粒子引擎、共享粒子资源及上述两个粒子功能目录没有未提交实现改动；工作区另有碰一碰界面、空间照片等未提交工作，应保留。
