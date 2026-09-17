# SurfaceView 阴影同步交接

## 约束与基线

- 用户要求恢复 SurfaceView 的播放与合成路径，阴影仍必须与卡片共同消散、回聚，不保留原位完整轮廓。
- 本轮只测试魅族 `392QBFED2226B`，Android 15 / API 35，1368×3192，560dpi。OPPO 已拔掉；其它在线设备不操作。
- 已安装前版 `202609161815`，虚构页面的 `surface-base-texture-cancel` 通过，静止前后上缘阴影均为 3.117647，回位交接采样差为 0。此数据属于 TextureView；改用 SurfaceView 后 Window PixelCopy 不包含独立图层，必须改用最终显示合成画面验证。

## 假设与验证顺序

1. 同步窗口和 SurfaceView 的缓冲提交可以消除双影，但 SDK 中 `SurfaceSyncGroup.add(SurfaceView, ...)` 与 `syncNextFrame` 为隐藏 API，不能用于生产实现。
2. 只在交接时使用原卡片快照的 SurfaceControl 图层：与真实 View 的交接用 `AttachedSurfaceControl.applyTransactionOnDraw`，与共享粒子 SurfaceView 的交接用 `SurfaceView.applyTransactionToFrame`。每一步只更换同一份半透明内容的持有方，播放期间仍走原共享 SurfaceView，不经 HWUI 合成粒子纹理。
3. 若同步请求失败、窗口退出或手势迅速反向，清理必须恢复真实卡片、移除临时图层，并且不触发重复业务动作。超时只用于异常回收，不能用固定延时决定正常交接。

## 平台接口边界

- `applyTransactionToFrame` 从 API 34 公开，必须由 GL 提交线程在对应的下一帧交换之前调用，避免绑定到不确定的帧。
- `applyTransactionOnDraw` 从 API 31 公开，事务与窗口下一次绘制原子提交，需要主动 invalidate。
- 不能直接改 SurfaceView 自己的 SurfaceControl 属性；官方只保证它作为自有子层父节点的用途。本轮临时层自行创建并管理。
- 验证后再决定旧系统的兼容分支，不能因接口缺失将未经验证的跨 Surface 延时当作同步保证。

## 首轮同步诊断

- `surface-sync-02/03/04` 初次粒子可见约需 1 秒，曾怀疑窗口事务未回调、依赖 1000ms 异常兜底。
- `surface-sync-05-diagnostic` 定向日志推翻该假设：进入窗口提交 6ms、事务 committed / presented 54ms；退出窗口提交 38ms、committed 44ms。1000ms 日志只是未取消的诊断任务，并未决定交接。
- 后续按准备、建材、提交分段测量，与同一 APK 的 TextureView 分支对照，不能将全部启动耗时归因于 Surface 同步。

## 同包性能对照

关闭 PixelCopy 和录屏，固定虚构卡片、手势与种子 `42017`，两条路径交替运行。第一次 Surface 冷启动从按下到首个提交 313.6ms，其中首次管线准备 87.3ms；不纳入热运行对比。

| 运行 | 按下至首个粒子提交 | 提交间隔中位数 / P95 | 窗口帧总耗时中位数 / P95 |
| --- | --- | --- | --- |
| ab-texture-01 | 160.3ms | 17.00 / 32.09ms | 20.67 / 30.75ms |
| ab-surface-01 | 128.2ms | 16.79 / 48.61ms | 21.79 / 32.91ms |
| ab-texture-02 | 115.7ms | 17.36 / 32.85ms | 20.60 / 31.43ms |
| ab-surface-02 | 120.3ms | 16.79 / 33.97ms | 20.76 / 43.23ms |

- 当前可确认没有增加固定的 1 秒交接等待；窗口耗时与尾部间隔存在波动，不能据此宣称帧率或功耗有明显提升。
- 此处均使用 `eglSwapBuffers` 返回后的提交口径；Surface 的实际呈现时间与 Texture 的窗口通知不是同一口径，不直接相减比较。
- 持续播放只保留原共享 SurfaceView。每卡片一次硬件快照拷贝及交接事务是新增成本，普通播放帧不创建空事务。

## 真机回归进展

- `surface-final-short-01`：短滑保持、回弹，最终合成录屏未见完整轮廓残留或双影；上缘中部的录屏相邻变化最大约 0.56 灰度级（录屏有损，不能当作逐像素相等）。
- `surface-final-return-01`、`surface-final-fling-01`：完成恰好一次，换 holder 后沿生产回聚入口恢复；未见中途完整卡片闪回。
- `surface-final-reverse-01`：跨原点回拖最终 alpha=1、translationX=0、z=28px。
- `surface-final-tap-01`：连续点按只预热就取消，阴影采样未发生变化。`surface-final-fast-repeat-01`：快速连续两次滑动后恢复正常。
- 已检查 SurfaceFlinger 图层列表，结束后无 `ParticleCardHandoff` 自有临时层残留；崩溃缓冲为空。
- 单测首次只有旧源码字符串断言失败：它把“预算种子优先”绑定到了随机数回退的完整写法。实际优先级未变，断言收窄到它应保护的 `prepared?.seed ?:`。
- `surface-final-cancel-event-01` 与回弹途中退出虚构页面均通过；Texture 兼容分支 `texture-final-return-01` 回聚交接差为 0。

## 发布核验

- 完整 JVM 检查 1045 项：1044 通过、1 跳过，无失败。临时诊断日志已移除。
- debug 更新 `202609170254` 已发布并安装到魅族。本地发布产物、远端完整 APK 与手机安装包 SHA-256 一致：`b654a1ccc04c9e363ecb0cbcc1cf1669ccc10acdcb3e9353fbdb5dc52503be42`，26,285,004 字节。
- 发布包 `surface-published-fling-01` 消散 28 帧、回聚 34 帧，两段末帧进度均为 1，完成回调一次；`surface-published-short-01` 短滑保持和回弹通过。最终 alpha=1、translationX=0、z=28px，阴影恢复初值。未把超时恢复当作回聚成功。
- 虚构测试页已退出，临时交接层无残留，crash 缓冲为空。本轮没有改动用户记事或偏好，没有创建 Git 提交。

## 用户实测补充：FAB 遮挡

- 用户确认真实首页中，原本被 FAB 遮挡的卡片在交接时会覆盖 FAB。自有快照层采用 `Int.MAX_VALUE - 1`，快照不包含 FAB；共享粒子 SurfaceView 也位于窗口内容上方。
- 上面的无双影、无残留结论仅针对无 FAB 的虚构卡片测试，不能覆盖首页既有控件之间的遮挡连续性。
- 产品建议：FAB 是独立的首页主操作入口，卡片完成动画保持原有前后关系，包含临时快照、阴影、消散及回聚粒子。单独在交接帧出现层级变化应作为缺陷；该建议尚未由用户确定，也未修改应用实现。

后续用户已确认上述建议。修正实现、带 FAB 的实测及波纹兼容处理记录在 [FAB 遮挡诊断](fab-occlusion-2026-09-17.md)，上段保留为提出建议时的历史状态。
