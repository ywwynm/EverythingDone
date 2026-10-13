# 卓易通第三轮：首尾交接与独立 Surface 恢复

用户已授权实施、发布；保留 SurfaceView，不用 ADB。前置实测见 [第二轮反馈](droitong-gl-feedback-2026-10-11.md)。

## 实现约束

- 默认 GL 组合的内容可见性已有用户确认；新候选处理阴影和副标题短暂变深、独立 Surface 区域短暂深灰，不修改粒子算法或统一降低半透明内容的 alpha。
- 新旧同包对照继续只在诊断页面启用，保留关键图像、用户观察和 ZIP 导出。
- `SurfaceSyncGroup.add(SurfaceView, ...)` 不在 API 36 公开 SDK 中，不使用反射或隐藏 API。本地 Android 36 源码确认 on-top SurfaceView 的 alpha 更新经 `applyTransactionOnVriDraw` 合入宿主绘制，可用于准备好缓冲后的可见性切换；卓易通是否完整遵循仍需用户验证。
- 首尾操作需保证取消、退出、超时和重复回调都能恢复原控件与 SurfaceView 状态；并发不满足排他条件时明确报告，不隐藏其他粒子会话。
- 独立 Surface 使用只覆盖其区域的自有快照，在宿主恢复时保留有效画面；记录表面生命周期、重绘完成、缓冲检查和覆盖移除，不能只等 Decor frame commit。

## 当前状态

两项候选及同包对照已实现，本地构建与 40 项相关单元测试通过，debug `202610111028` 已发布并核验公开产物。**用户随后反馈问题未解决且效果更差，候选未通过。** 已提供两段录像，见 [第三轮视频反馈](droitong-handoff-video-feedback-2026-10-11.md)；构建和桌面测试不能代替该设备的结果。

## 首尾交接

- `ParticleGlCardHandoff` 在诊断单卡片窗口取得独占权后，先把共享 SurfaceView 的公开 alpha 设为 0；真实控件此时仍可见。静态卡片和前景在隐藏表面内准备，等待 GL 交换成功与绑定缓冲的事务提交两个信号。
- `ParticleWindowFrameSwitch` 使用公开 `SurfaceSyncGroup.add(AttachedSurfaceControl, Runnable)` 暂停宿主取帧，将原控件隐藏和 SurfaceView 显示纳入同一次窗口更新。没有直接操作框架拥有的 SurfaceControl，也没有调用隐藏 API。
- 返回时同次窗口更新恢复原控件、隐藏 SurfaceView；在隐藏期间删除旧 GL 卡片及前景，并清空相关会话，再恢复空表面。`ParticleForegroundSurface.release` 的同步恢复分支立即关闭旧 GL 前景，避免原来的额外宿主帧等待拖延清理。
- `ParticleWindowSurface` 为此候选补充 Callback2，在真实 `eglSwapBuffers` 后报告重绘完成，不能等待最终呈现回调而阻塞正在等生产者的窗口同步。事务因 Surface 销毁而单独 apply、交换失败、退出和超时均不作为成功的 GL 准备信号。
- 同步切换和缓冲确认各有 1.2 秒上限，入口／返回有总时限。回调至多交付一次，关闭后的迟到回调不能重新隐藏真实控件；这些时间只用于异常清理，不是为消除闪烁而强制多等几帧。

## 独立 Surface 恢复

- `ParticleSurfaceRecovery` 从合并快照复制独立 Surface 的可见区域，恢复 Dialog 前加到该窗口的 overlay；只覆盖色块区域，不重复覆盖面板文字、圆角和阴影。
- 宿主恢复提交后，向应用自有色块生产者请求恢复新帧；在下一帧上绑定事务并通过 PixelCopy 验证橙蓝颜色。普通窗口和全部独立表面都通过后，在宿主窗口更新中撤盖，再释放粒子层。
- `ParticlePresentationColorSurface` 在新候选中实现 Callback2，并让 Surface 生命周期跟随附着；旧 GL 组合继续使用原 Callback 行为，便于同包比较。
- 报告新增表面生命周期、绘制、事务、缓冲验证、覆盖撤除等事件；缓冲检查超时、几何变化、重绘失败和窗口退出均有收尾。未接入重绘协议的生产者只能检查现有缓冲，明确记录 `freshFrame=false`，不代表视频等真实生产者已经完成恢复协议。

## 应用内验证

入口：设置 → 粒子动画设置 → 右上角菜单 → 显示兼容性检查。

默认选择“本轮两项修复／额外隐藏普通内容”。保留“上一版 GL 组合”，另有“本轮只修首尾交接”“本轮只修独立 Surface”，与旧组合保持相同 GL 及 Dialog 基线。仍仅诊断会话生效，普通首页不自动推广。

1. 卡片慢拖、回拉、完成，观察顶栏／卡片／FAB 阴影及副标题是否仍短暂变深；返回后选择“正常”或“阴影或文字变深”。
2. 独立 Surface 固定色块弹窗连续打开、关闭，观察橙蓝区域是否仍露出深灰；返回后选择“正常”或“Surface 露出灰块”。
3. 对存在差异的场景切到“上一版 GL 组合”再试；必要时比较单项候选。补一次动画中返回、切后台再回来，检查控件恢复。
4. 保存或分享诊断 ZIP。先判断画面，再关闭关键图像采集评估流畅度，避免采样开销影响判断。

## 本地证据与限制

- 本地构建成功，40 项单测通过：交接状态 4、GL 卡片替换 3、回调幂等 3、恢复条件汇合 5、事件保留 2、捕获路线 8、既有 UI 契约 15。日志为 `tmp/droitong-handoff-final-build.log`、`tmp/droitong-handoff-tests.log`。
- 实际桌面 GLSL 检查 8 项通过：两次绘制可复现阴影／文字加深，黑色样本 RGB 分别比单次绘制低 41、63；互斥可见的四阶段及色块覆盖／撤盖的像素最大误差均为 0。见 [脚本](diagnostics/droitong-2026-10-11/check_handoff_composition.py) 和 [结果](diagnostics/droitong-2026-10-11/handoff-composition-check.json)。
- 单测验证异步状态的行为，桌面验证给定层状态下的合成像素；均不模拟 Android RenderThread、卓易通合成器或最终屏幕。应用内提交和 PixelCopy 成功也不是最终画面成功，需用户反馈闭环。
- 本轮没有使用 ADB、连接物理设备或模拟器，也没有创建 Git 提交。

接口依据：[SurfaceSyncGroup](https://developer.android.com/reference/android/window/SurfaceSyncGroup)、[SurfaceView](https://developer.android.com/reference/android/view/SurfaceView)、[Callback2](https://developer.android.com/reference/android/view/SurfaceHolder.Callback2)，并对照本地 API 36 SDK 源码及公开 `android.jar`。

## 发布回执

- 构建：`202610111028`；发布时间：2026-10-11 18:28:43（北京时间）。
- [下载 APK](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610111028.apk)，33,261,341 字节。
- SHA-256：`911338c6999dc7d37589f8102ce7395e4fa95d2c2a209a55751469a9e5da9742`。
- 发布任务传入 [本轮独立日志](debug-updates/update-20261011182714.md)，同次任务 40 项单测通过。日志：`tmp/droitong-handoff-publish.log`。
- 公开 `latest.json` 的构建号、APK 地址、散列和更新说明与本地一致；重新下载的 APK 大小和 SHA-256 一致。APK 内更新构建号正确，新增窗口同步、Surface 恢复及色块生产者类和事件确实进入 DEX；无 `testOnly=true`，三个诊断 Activity 均 `exported=false`。
- 未安装到任何设备；用户继续通过应用内对照、肉眼记录和诊断 ZIP 验证卓易通最终画面。
