# 鸿蒙 7／卓易通：滑动粒子期间首页前景消失

## 用户反馈与范围

- 用户在前述鸿蒙 7／卓易通运行环境补充：在首页记事列表使用粒子滑动动画时，汉堡菜单、ActivityHeader、新建 FAB、actionbar 图标等卡片之外的界面元素消失。
- 追问确认：**松手并等待粒子动画结束后，这些控件自动恢复。** 当前没有动画结束后永久丢失的证据。
- 延续本次会话的分析范围，不修改应用代码；设备无法通过 ADB 连接的限制沿用。未复现、未连接设备、未编译、未发布。

## 静态代码对应

1. `ParticleHomeForeground.views()` 对首页列表明确收集 `view_status_bar_scrim`、`actionbar`、`rl_header`、`actionbar_shadow`、`view_status_bar`、`fab_create`、`rl_contextual_toolbar`。用户列举的顶栏图标、标题和 FAB 与这组前景范围高度吻合。
2. `ParticleCardSurfaceHandoff.supported()` 当前主要按 `Build.VERSION.SDK_INT >= 34` 判断支持，另有仅供 debug 探针使用的 `forceTextureForTests` 开关。尚未核对卓易通报告的 API 级别和实际选择的分支，不能把“存在接口”当作该环境合成正确的证据。
3. 进入这一分支时，`ParticleForegroundSurface` 将各前景控件绘制到小位图，再用 HardwareRenderer／ImageReader 生成硬件缓冲，提交到粒子 SurfaceView 之下创建的自有 SurfaceControl 子层；子层设正 layer 值，预期在粒子之上显示。
4. `ParticleForegroundSurface.enter()` 在第 143 行设置 `source.transitionAlpha = 0f`，停止原控件的常规绘制；同一事务提交替代前景缓冲。这样避免原控件与替代图层重复绘制，尤其避免半透明阴影加深。
5. `release()` 第 153 行与 `closeNow()` 第 315 行恢复原 `transitionAlpha`。因此“动画期间消失、结束恢复”恰好符合原控件被正常隐藏／恢复，而替代图层未正确呈现的故障模式。
6. 当前准备成功信号是已取得硬件图像，交接按事务回调推进；异常和资源失败有回收路径，但“调用成功、图层没有实际可见像素”的情况不一定触发失败。不能把收到缓冲或 committed 回调视为最终屏幕显示正确。

主要源码在 `app/src/main/java/com/ywwynm/everythingdone/views/particledismiss/`：`ParticleHomeForeground.kt:19`、`ParticleForegroundSurface.kt:93`、`:136`、`:148`、`:263`、`ParticleCardSurfaceHandoff.kt:158`。

## 优先假设与区分方法

| 优先级 | 假设 | 可区分的结果 |
| --- | --- | --- |
| 最高 | 卓易通环境中自有前景 Surface 的父子关系、buffer 提交或显示事务未按预期合成；原 View 的隐藏已经生效 | 绕过 SurfaceControl 前景接管、改用现有 TextureView 卡片分支后，顶栏与 FAB 在动画全程保留。此结果定位到整条接管路径，仍不能单独证明其中哪个 API 出错。 |
| 次高 | 前景捕获／HardwareRenderer 输出本身是空白或错误透明度 | 在应用内预览替代前景捕获结果，若内容已缺失，需先查绘制和缓冲生成；CPU 位图正确也不能证明随后硬件缓冲及最终合成正确。 |
| 较低 | 替代前景的坐标、裁剪或 alpha 计算错误，使图层存在但落在可见范围之外 | 诊断数据出现异常矩形或 alpha；固定可辨认色块在同一路径的显示结果可进一步区分内容与位置问题。 |

若实际运行的是 API 34 以下／TextureView 分支，则第 4 项的主动隐藏链不应执行，必须改查普通 View 遮挡及该分支的纹理透明合成，不可沿用未经核对的前景接管结论。

## 与弹窗问题的关系及兼容建议

- 弹窗出现的问题优先怀疑真实 Dialog 的窗口 alpha 隐藏未生效；本问题优先怀疑原控件已隐藏、替代前景没有显示。两者共同指向窗口／Surface 的呈现兼容性，但不能声称同一个 API 缺陷或一个修复必然同时解决。
- 首页卡片已有 TextureView 兼容分支：`ParticleDismissOverlay` 在不支持原子交接时选择 `ParticleWindowSurface.inWindow()`；`ParticleOverlayHost.attach()` 把动画层插在列表之后、真实顶栏／FAB 之前，保持普通 View 的叠放关系，不进入前景 Surface 接管。
- 该现有分支可用于定位前景接管问题，不能直接确定为最终修复。用户要求回查替换历史后，已确认原切换包含 Texture 销毁停顿、窗口内合成及阴影交接等实际问题；正常环境继续保留共享 SurfaceView，并优先寻找局部兼容修正。Texture 对照仍须保留手指回拖、松手回位、完成后业务回调及阴影／裁剪，其合成和生命周期成本需在目标环境验证。详见 [历史与方案边界](../particle-animation-performance/surface-texture-history-2026-10-11.md)。
- 普通弹窗仍需独立验证内容绘制层隐藏；仅将 Activity 的粒子表面替换为 TextureView 不能解决独立 Dialog 的层级与隐藏问题。
- 无 ADB 的测试包应在应用内提供原路径／TextureView 对照入口，显示实际使用分支和 API 级别，记录前景控件的隐藏／恢复、图层准备与回调时间。现有 `forceTextureForTests` 只是内部探针标志，当前普通设置页没有此开关；应用内入口尚未实现。

用户尚未要求实施；现阶段只更新分析与待办。关联：[弹窗窗口透明度分析](../dialog-particle-dismiss/analysis-2026-10-11-droitong-window-alpha.md)、[原 FAB 遮挡交接设计与验证](fab-occlusion-2026-09-17.md)。

## 平台依据

2026-10-11 后续：用户已确认保留 SurfaceView，本轮测试方案改为分别检查捕获输出、原父节点与窗口根节点挂载，以及同步交接。上文 Texture 对照是先前讨论，不再是本轮首选。见 [具体局部修复与无 ADB 验证方案](../particle-animation-performance/droitong-local-repair-plan-2026-10-11.md)。

- [SurfaceView.getSurfaceControl](https://developer.android.com/reference/android/view/SurfaceView#getSurfaceControl())：公开保证的是将其作为应用自有 SurfaceControl 层级的父节点。本项目采用这一用途，尚无证据表明代码调用方式本身违反该接口约定。
- [SurfaceControl.Transaction](https://developer.android.com/reference/android/view/SurfaceControl.Transaction)：committed 回调表示事务已应用并准备呈现，与 completed／presented 回调的语义不同；即使事务已呈现，仍需确认缓冲有像素且没有被裁剪或遮挡。
