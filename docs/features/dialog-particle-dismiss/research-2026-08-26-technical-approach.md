# Dialog 粒子消散动画：技术路线调研

日期：2026-08-26
状态：调研完成，未开始实现
参考效果：华为鸿蒙 OS7 删除通知动画（控件化为粒子后飘散消失）

## 一、目标效果拆解

从鸿蒙 OS7 四帧截图逐帧分析：

1. 通知完整显示，用户发起删除；
2. 通知右侧约 2/3 已化为蓝白色粒子并向上扩散，左侧内容仍完整可读；
3. 仅左端图标附近保留实体，粒子整体向左上方移动并逐渐稀疏；
4. 只余少量粒子残迹，随后完全消失。

关键观察：

- **粒子颜色取自原内容像素**：粒子主体呈蓝白色（通知底色），图标区域析出黄色粒子（中国移动图标色）。
- **波前推进式溶解**：不是整体同时溶解。波前未扫到的区域完全保持原样，说明粒子在
  t=0 时静止且不透明地无缝拼成原图（或等价地：原图按波前遮罩擦除 + 粒子按波前激活）。
- **运动构成**：初速度（向上为主 / 沿手势方向）+ 湍流扰动 + alpha 衰减 + 粒子尺寸微缩。
- **粒子极小且密**（亚 dp 级），数量在数万量级，不是碎块飞散。

## 二、推荐路线：快照 → GPU 无状态点粒子

### 1. 抓快照（颜色需求在这一步天然满足）

dismiss 触发瞬间把 Dialog 的 **DecorView**（不是 contentView，要带上圆角背景）同步绘制
到 Bitmap（`decorView.draw(Canvas(bitmap))`）。粒子颜色直接从这张快照采样，
"粒子颜色反映内容颜色"不需要任何额外机制。圆角外是透明像素，采样 alpha 乘进粒子后
自然不可见。

特殊情况：`AudioPlayDialogFragment` / `AudioRecordDialogFragment` /
`FableSolTuningDialogFragment` 内嵌 FableSol 的 SurfaceView，软件 draw 抓不到其内容
（会是空洞）。minSdk 26 恰好是 `PixelCopy.request(window, ...)` 的最低版本，可整窗
连 GL 层一起抓；PixelCopy 异步但通常 1–2 帧内回调，需在 window 存活时发起。策略：
默认走同步 `view.draw`，检测到子树含 SurfaceView/TextureView 时改走 PixelCopy，
失败则降级为普通淡出。

### 2. 粒子化（CPU 侧近零准备）

以固定物理步长（3–4 px，约 1.5 dp）网格化快照，每格一个粒子。中等 Dialog
（约 900×1100 px）约 6–8 万粒子。逐粒子属性全部免上传：

- 网格坐标：由 `gl_VertexID` 推导（uniform 传网格宽度）；
- 颜色：快照整张作为纹理，vertex shader 里按网格 UV 做 vertex texture fetch
  （ES 3.0 起支持，minSdk 26 设备必有 ES 3.0+）；
- 随机种子：`hash(gl_VertexID)`。

### 3. 运动模型（无状态，闭式时间函数）

位置 = f(初始位置, seed, t)。一次性动画不需要 compute shader / transform feedback /
粒子状态缓冲，每帧只更新一个 uniform time，CPU 每帧零负载：

- **波前**：`tLocal = clamp((tGlobal - delay(x, y)) / lifetime, 0, 1)`，delay 由触发点
  径向距离（有按钮触点时）或自一侧线性推进（无触点：back 键、点击外部）决定。
  tLocal = 0 的粒子留在原位、完全不透明——全体静止时逐像素拼回原图，因此抓图后
  Dialog 本体可立即消失，视觉无断裂。截图第 2 帧"左侧完整、右侧已飘散"即此机制。
- **位移**：初速度（向上为主 + 随机偏向）× ease-out + curl/simplex 噪声湍流项，均随
  tLocal 增长。
- **alpha**：(1 − tLocal) 的缓动，前段保持后段快速衰减；`gl_PointSize` 随 tLocal 微缩。

### 4. 渲染载体与时序

- 载体：全屏透明 **TextureView**（`setOpaque(false)`）临时挂到 **Activity 的
  DecorView**。不能放在 Dialog 自己的 window 里——window 只有面板大小，粒子飘出
  边界会被裁剪。SurfaceView 有 Z 序与合成问题，不适合做透明叠加层。
- EGL 线程管理直接复用 FableSol 的模式（`FableSolEglSession` /
  `FableSolGlRenderThread` 那套自建 EGL 环境）；渲染就是一个 `GL_POINTS` draw call。
- **时序（关键决策）：不延迟真实 dismiss**。dismiss 发起 → 同步抓图 → 真实 dismiss
  照常执行（fragment 事务、window 移除、dim 由系统淡出）→ 同帧在 DecorView 上开始
  粒子动画（600–900 ms，触摸穿透，纯装饰层）。好处：完全不碰 DialogFragment 生命
  周期时序、不拖慢用户操作节奏、无 window 泄漏风险。dim 先于粒子恢复，与鸿蒙
  观感一致（其背景也是即时恢复的）。
- 配套：Dialog 主题的 `windowExitAnimation` 需置空，否则系统默认淡出与粒子动画
  叠加出双重影像。

### 5. 接入点（项目现状使这一步成本极低）

侦察结论（2026-08-26）：

- 全部 **28 个** DialogFragment 都继承
  [BaseDialogFragment.kt](../../../app/src/main/java/com/ywwynm/everythingdone/fragments/BaseDialogFragment.kt)；
- 项目内**没有**任何裸用 `AlertDialog.Builder` / `MaterialAlertDialogBuilder` 的调用；
- `onCreateDialog` 统一返回自定义的 `GestureAnchoredDialog`。

两条 dismiss 路径与拦截点：

| 路径 | 走向 | 拦截点 |
|------|------|--------|
| 代码调用 `dismiss()` | `BaseDialogFragment.dismiss()`（已统一 override） | 基类一处 |
| back 键 / 点击外部 | `Dialog.cancel()` → `Dialog.dismiss()`，不经过 fragment 的 `dismiss()` | `GestureAnchoredDialog.dismiss()` 一处（cancel 最终也汇入 Dialog.dismiss） |

即：**改动 BaseDialogFragment + GestureAnchoredDialog 两处即可覆盖全部 28 个 Dialog**，
无需触碰任何子类。需跳过动画直接走 super 的场景：Activity 正在 finish/销毁、配置
变更（旋转）触发的 dismiss、fragment 状态恢复期、动画正在播放中的重入调用。

## 三、备选路线与排除理由

| 路线 | 说明 | 结论 |
|------|------|------|
| Canvas 粒子（自定义 View + ValueAnimator） | `drawPoints` 一批只能一种颜色，逐粒子 `drawCircle` 数千即掉帧；效果的细腻感来自数万级小粒子，Canvas 承载不了 | 仅作低端机降级候选 |
| AGSL RuntimeShader（RenderEffect） | API 33+ 才可用，覆盖不了 minSdk 26；且 fragment shader 是 gather 模型，大位移飘散需反查"哪些粒子落到本像素"，邻域采样开销不可行，只适合就地溶解 | 排除 |
| RenderNode 碎块（Bitmap 切数百块各自动画） | 呈现为碎块飞散而非细粒子消散，观感不符 | 排除 |

**已验证的同类先例**：Telegram Android 的消息删除动画（社区称 Thanos snap effect）
与目标效果同款，其实现同样是 TextureView + GLES 粒子，证明了该方案在海量低中端
Android 设备上的可行性。注意 Telegram 为 GPL v2 许可，只参考架构思路，不搬代码；
且我们的场景更简单——一次性动画用无状态闭式运动即可，不需要其粒子状态更新机制。

## 四、难点与风险（按大小排序）

1. **观感参数调优是最大工作量**（而非技术可行性）：波前速度与方向、噪声场参数、
   alpha 曲线、粒子尺寸/密度，需真机反复迭代。目标效果的品质在参数，不在架构。
2. **含 SurfaceView 的 Dialog 抓图**：需 PixelCopy 路径 + 异步时序处理 + 抓取失败
   降级（普通淡出）。
3. **窗口退出动画与 dim 的协调**：exit 动画置空、dim 淡出与粒子起始的衔接，调不好
   会有断裂感，需真机目检。
4. **GL 层生命周期**：EGL context 创建数十 ms，首帧延迟需遮蔽（快照先以普通 View
   显示 1–2 帧再无缝切到 GL 层，或常驻复用 context）；Activity 旋转/finish 时跳过。
5. **设备兼容**：华为 Maleoon 跨阶段精度问题（项目已有规约：共享 uniform 两侧显式
   同精度）；`gl_PointSize` 各 GPU 上限不同（通常 ≥ 64，本效果只需 3–8 px，无风险）；
   三星/OPPO 真机矩阵验证。
6. **系统动画开关与无障碍**：animator duration scale = 0 或系统"移除动画"设置时，
   直接普通 dismiss。

## 五、工程量估计

- 框架搭建（抓图 + TextureView/EGL 层 + shader + 两处基类接入）：2–3 天；
- PixelCopy 路径 + edge case 加固：约 1 天；
- 真机观感调优：1–2 天起步，按以往动画类功能经验可能多轮迭代。

总体：**中等难度**。技术链路无未验证环节（Telegram 先例 + 项目自有 GL 设施），
风险集中在观感调优周期与 edge case 打磨。

## 六、建议推进方式

1. 先做单点样板：挑一个结构简单的 Dialog（如 AlertDialogFragment）单独接入，跑通
   抓图 → 粒子 → 调参闭环（遵循"先验证再铺开"）；
2. 观感满意后把开关移入 BaseDialogFragment / GestureAnchoredDialog 铺开全部 28 个；
3. 可选：仿照 FableSol 的 shared GLSL 双端共享模式先在桌面蓝本调参数（迭代快），
   但本效果强依赖真实 Dialog 内容与整机时序，桌面蓝本价值中等。
