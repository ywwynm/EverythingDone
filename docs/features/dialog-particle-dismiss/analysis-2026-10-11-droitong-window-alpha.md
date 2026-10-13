# 鸿蒙 7／卓易通弹窗出现层级异常：静态分析

## 现象与本轮范围

- 用户在华为鸿蒙 7 设备上通过「卓易通」运行完事儿 APK：打开弹窗时完整 Dialog 直接显示，粒子在它下面播放；关闭弹窗的动画正常。
- 用户进一步确认：单层弹窗、弹窗之上再开弹窗均会发生，不能只归因于叠层判定遗漏。
- 用户要求先分析，不改应用代码。本轮未连接设备、未复现、未编译或发布；只核对当前源码、历史记录及 Android 官方接口说明。
- 分析基于仓库 `51b71df3c` 所在工作区。用户设备的 APK 版本、机型、卓易通版本和对外报告的 Android API 级别尚未核对；不能认定已证实卓易通内部缺陷。

## 已确认的代码事实

1. 普通弹窗的粒子使用 `ParticleWindowSurface`，是挂在 Activity decor 下、调用 `setZOrderOnTop(true)` 的透明 `SurfaceView`。它在宿主窗口之上，并不因此越过独立 Dialog 窗口。源码自身明确依赖这一顺序。
2. 打开通过 `ParticleDismissController.startCondense()` 调用 `appearContent(..., hideWindow = true)`。后者将 `window.attributes.alpha` 设为 0，保留真实 Dialog 和内容的绘制，再取快照、播放反向粒子；完成回调才恢复此前的窗口 alpha。这里没有同时把 decor 或普通内容 View 的 alpha 设为 0。
3. `BaseDialogFragment.onStart()` 在 `super.onStart()` 之后启动上述流程，且保留主题或子类设置的系统入场动画，预期这些动画在真实窗口透明期间结束。窗口首次显示、属性更新与原生动画因此都参与出现过程。
4. 关闭走不同的交接：`ParticleDismissStartGate` 等待覆盖层和粒子首帧两个条件，再调用 `GestureAnchoredDialog.completeParticleDismiss()`，由 `super.dismiss()` 真正移除窗口。因此关闭正常不能证明打开所依赖的窗口 alpha 正常。
5. 叠层弹窗会尝试使用 `ParticleElevatedHost` 的独立全屏透明窗口，解决下层仍保留的 Dialog 遮挡粒子；但真实目标 Dialog 的隐藏依然使用上述窗口 alpha。此承载窗口在卓易通里的实际排序尚未验证。
6. 出现模式下 `ParticleDismissOverlay.snapshotView` 为 null，不存在有意保留的完整静态截图盖住动画。准备阶段的 1100 ms 超时恢复与捕获成功通过同一 `handled` 状态互斥；超时后的迟到捕获会回收快照并返回，不能直接解释完整动画同时在后面继续播放。

关键源码：`ParticleWindowSurface.kt:34`、`ParticleDismissController.kt:476`、`:546`、`:562`、`:619`、`:689`，`BaseDialogFragment.kt:174`、`:455`、`:505`，`ParticleDismissOverlay.kt:123`；均位于 `app/src/main/java/com/ywwynm/everythingdone/` 的对应目录。

## 按可能性排序的假设

| 假设 | 解释与可区分信号 |
| --- | --- |
| 最高：窗口透明度在该运行环境没有正确作用于实际合成 | 程序请求 alpha=0，但完整 Dialog 仍可见；Activity 粒子处于下方的既有顺序随即暴露。若整个出现过程窗口属性持续为 0、画面仍完整显示，则支持这一解释。两种粒子样式共用此流程，预计均受影响。 |
| 次高：首次显示、入场动画或后续窗口更新使透明状态丢失／延后 | 当前流程在 Dialog 已 show 后设置透明度，又保留系统入场动画。若透明度起初有效、随后变回不透明，或只在首次打开／特定底部面板出现，则优先检查属性覆盖与动画合成顺序。静态检索未发现相关子类直接把窗口 alpha 写回 1，尚无代码证据认定是子类覆盖。 |
| 较低：真实窗口恢复与粒子实际呈现不同步 | 完成回调恢复 alpha，呈现判定包含驱动时间戳与无时间戳／超时放行路径。若设备实际呈现落后于这些回调，可能先显示完整弹窗、后面仍在播放排队帧。通常应表现为先正常凝聚、后段提前显示，比“一开始完整显示”更符合此假设。 |

仅由叠层判断遗漏引起的故障已降为低优先级，因为用户确认单层也发生。粒子轨迹或通用 GPU 计算失效也不是首要方向：出现仍有粒子、关闭正常，但尚不能据此排除所有驱动问题。

## 后续验证顺序（未执行）

1. 先核对同一 APK 在该设备中的版本与设置；对照流体／飘散，以及单层／叠层，确认是否共用故障。
2. 在用户决定进入诊断实施后，定向记录出现开始、窗口属性更新、首帧、完成回调时的 window alpha 与真实时间；结合一段完整录屏区分“从未隐藏”和“恢复过早”。只读取应用属性不等于证实屏幕已经透明。
3. 单变量比较窗口透明度与普通内容 View 隐藏；普通确认框先验证，录音／相机等独立 Surface 内容需另验证，不能直接把 `decor.alpha=0` 当成通用修复。
4. 若属性被覆盖，再检查窗口入场动画与后续更新；若恢复过早，再核对呈现时间戳和窗口交接。不要直接增大粒子 View 的 elevation，它不能解决真实 Dialog 未隐藏的问题，也不能普遍改变独立窗口排序。

## 外部依据与证据边界

- [Android：WindowManager.LayoutParams.alpha](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#alpha)：0 表示整个窗口完全透明，1 表示完全不透明。
- [Android：SurfaceView.setZOrderOnTop](https://developer.android.com/reference/android/view/SurfaceView#setZOrderOnTop(boolean))：定义相对于所属窗口的顺序，不承诺覆盖其它独立窗口。
- [卓易通官网介绍](https://www.droitong.com/AboutUs.html)：只确认产品定位；本轮查看的官方介绍不提供窗口透明度、Surface 合成或该问题的实现证据。因此本文是源码支持的优先假设，不是已确认的平台缺陷公告。

## 无 ADB 的兼容方案讨论（2026-10-11 补充，未实施）

用户反馈该卓易通环境无法通过 ADB 连接，并询问解决方法。本轮继续分析方案，不修改应用代码，也不把建立 ADB 连接作为验证前提。

- **优先验证内容绘制层隐藏。** 在保留现有粒子模型的前提下，比较窗口 alpha 与普通内容绘制层隐藏；覆盖弹窗背景、阴影，尽早在首帧前建立隐藏状态，动画结束再恢复。当前离屏快照可直接调用根 View 的 `draw`，但必须验证隐藏措施不会把快照也变成透明，不能简单隐藏所有子 View 后照常抓图。
- **独立 Surface 是单独的兼容边界。** 录音／相机等内容通过 `ParticleSnapshot` 分别抓取普通控件与 Surface 并合成，须协调表面可见性、首次缓冲和恢复时机；父 View 的 alpha 不足以证明所有独立 Surface 都已隐藏。未验证前不能声称一行 `decor.alpha=0` 覆盖全部弹窗。
- **较大改造备选：粒子与面板同窗口呈现。** 全屏透明 Dialog 内分别承载面板与粒子 TextureView，减少跨窗口交接与层级依赖；全屏范围用于容纳飞出面板的颗粒。需要适配位置、外部点击、键盘、暗层、叠层及独立 Surface，改造和回归范围明显更大，暂不作为首轮方案。只把 Activity 中的 SurfaceView 换成 TextureView，仍不能越过另一个 Dialog 窗口。
- **立即可用的规避设置。** 已核对 `SettingsActivity` 与简体中文资源：设置中的「对话框粒子动画效果」有「仅消失时」选项，可暂时保留用户确认正常的关闭动画。

建议将现有方式与兼容候选放进同一个可安装 debug 测试包，通过应用内入口对照固定普通弹窗、叠层弹窗、带独立 Surface 的弹窗。记录窗口 alpha、隐藏／恢复及首末帧回调的时间、应用可见的系统与渲染信息，支持复制或导出文本；最终画面仍由用户观察或录屏确认。日志里的属性为 0 不等于屏幕一定透明，应用内窗口抓图也不能替代跨窗口最终合成证据。上述测试入口与报告导出当前尚未实现，发布也未执行。

官方依据：[TextureView](https://developer.android.com/reference/android/view/TextureView) 参与普通 View 合成、支持 View alpha；[SurfaceView](https://developer.android.com/reference/android/view/SurfaceView) 使用独立 Surface，其透明度和合成顺序有额外语义，不能直接套用普通 View 的隐藏结论。

## 同设备首页前景消失的新证据

用户补充：粒子滑动记事时汉堡菜单、ActivityHeader、FAB、顶栏图标消失，动画结束恢复。静态核对发现这些控件正好由 `ParticleForegroundSurface` 临时隐藏并交给自有 SurfaceControl 层显示。因此需把兼容性排查扩大到前景 Surface 合成，不能只修改 Dialog 窗口 alpha 就认为全部问题已覆盖。

两种现象的具体机制仍须分别验证：首页优先对照已有 TextureView 兼容分支，弹窗继续对照内容绘制层隐藏。仅更换 Activity 的粒子后端不能自动隐藏独立 Dialog。详见 [首页前景分析](../swipe-complete-animation/analysis-2026-10-11-droitong-foreground.md)。

用户随后要求回查此前切换 SurfaceView 的原因。已核实切换确有性能与交接问题背景，Texture 只作为诊断对照，不预先确定为最终兼容方案，也不整体退回默认呈现后端。组合优化的收益归因、当前兼容路径保留的优化及仍存在的成本，见 [提交历史核对](../particle-animation-performance/surface-texture-history-2026-10-11.md)。

用户进一步确认先保留 SurfaceView，要求具体局部方案。已将显示前建立隐藏与 Decor 隐藏拆成单变量对照，补充独立 Surface 出现时优先窗口 PixelCopy 的配套约束，并设计应用内测试与导出入口。详见 [局部修复与无 ADB 验证方案](../particle-animation-performance/droitong-local-repair-plan-2026-10-11.md)。本轮仍未修改应用代码。
