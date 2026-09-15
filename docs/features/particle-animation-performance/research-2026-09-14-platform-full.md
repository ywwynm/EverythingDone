# Android 图形栈调研报告（2026-09-14）

说明三点取证约束，影响下文的证据强度标注：
- `developer.android.com/reference/**` 全部是 JS 渲染页，抓取只得导航壳。所有 javadoc 原文改从 AOSP 源码镜像 `raw.githubusercontent.com/aosp-mirror/platform_frameworks_base` 取（与 reference 页同源），部分用 Microsoft Learn 的 CC-BY 2.5 逐字镜像交叉核对。
- `registry.khronos.org` 对抓取返回 403，Khronos 规范改从 `KhronosGroup/EGL-Registry` 的 GitHub 镜像取。
- `docs.qualcomm.com` 全站 JS 渲染，Qualcomm 的 Adreno 最佳实践正文**未能取到**，下文凡涉及该来源的一律标为未验证。

---

## 1. EGL 上下文优先级

### 1.1 规范本体：它是提示，不是契约

`EGL_IMG_context_priority` 定义三级（`HIGH=0x3101` / `MEDIUM=0x3102` / `LOW=0x3103`，属性名 `EGL_CONTEXT_PRIORITY_LEVEL_IMG=0x3100`），默认 MEDIUM。规范原文：`"This extension allows an EGLContext to be created with a priority hint."`；不满足时**不报错**，静默降级，因此必须回读：`"Querying EGL_CONTEXT_PRIORITY_LEVEL_IMG returns the priority this context was actually created with."` 规范全文**没有任何一句**描述抢占或调度语义。
来源：https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/IMG/EGL_IMG_context_priority.txt
置信度：高。

`EGL_NV_context_priority_realtime`（`0x3357`）是唯一写明抢占语义的：`"Preempt any current context running on GPU on submission of commands"`。但它明确要求特权：`"unpriviledged third party app cannot acquire this capability"`（原文拼写如此），且 `"On Android such applications have to be registered in advance with the OS"`。**普通应用不可用。**
来源：https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/NV/EGL_NV_context_priority_realtime.txt
置信度：高。

同族的 Vulkan 扩展把语义说得最直白，可作为理解 EGL 侧实际行为的参照：`"The driver implementation will attempt to skew hardware resource allocation in favor of the higher-priority task"`，以及 `"there is no guarantee that queue with a higher priority will preempt a lower priority queue"`。
来源：https://raw.githubusercontent.com/KhronosGroup/Vulkan-Docs/main/appendices/VK_KHR_global_priority.adoc；https://github.com/KhronosGroup/Vulkan-Docs/issues/1398
置信度：高。

### 1.2 各 GPU 的支持情况

**Android EGL wrapper**：`frameworks/native` 的 `egl_platform_entries.cpp` 在 `gExtensionString` 里同时列出 `EGL_IMG_context_priority` 与 `EGL_NV_context_priority_realtime`，但 `eglCreateContextImpl` **自己不处理优先级**，属性原样透传给厂商驱动。也就是说平台层不做任何过滤或兜底，行为完全由 blob 决定。
来源：https://mirrors.aliyun.com/android.googlesource.com/frameworks/native/opengl/libs/EGL/egl_platform_entries.cpp
置信度：中高（AOSP 镜像，未逐字核对官方 git）。

**CDD 强制要求**：VR High Performance 设备 MUST 实现并暴露该扩展；Android 15 CDD [7.1.4.1/H-1-2] 要求 Media Performance Class V 的设备 `MUST support the EGL_IMG_context_priority`。现代旗舰 Adreno / Mali / Xclipse 基本都有。
来源：https://source.android.com/docs/compatibility/15/android-15-cdd；https://source.android.com/docs/compatibility/11/android-11-cdd
置信度：中高（CDD 页体积过大无法整页抓取，证据来自检索片段，两个版本互证）。

**反例必须知道**：即便 CDD 要求，也有设备不报告字符串。gvr-android-sdk issue #330 记录 Pixel XL 支持功能但扩展列表里没有。因此必须 `eglQueryString(dpy, EGL_EXTENSIONS)` 探测并接受缺失。
来源：https://github.com/googlevr/gvr-android-sdk/issues/330
置信度：中（社区 issue，无 Google 工程师回复）。

**Adreno（KGSL 内核侧，可查证）**：优先级是 per-context 的 4 位字段（`KGSL_CONTEXT_PRIORITY_MASK 0x0000F000`、`KGSL_CONTEXT_PRIORITY_SHIFT 12`），映射到 ring：

```c
#define KGSL_PRIORITY_MAX_RB_LEVELS 4
#define KGSL_LPAC_RB_ID		KGSL_PRIORITY_MAX_RB_LEVELS
static inline u32 adreno_get_level(struct kgsl_context *context)
{
	if (kgsl_context_is_lpac(context)) return KGSL_LPAC_RB_ID;
	level = context->priority / KGSL_PRIORITY_MAX_RB_LEVELS;
	return min_t(u32, level, KGSL_PRIORITY_MAX_RB_LEVELS - 1);
}
```

来源：https://raw.githubusercontent.com/qualcomm-linux/kgsl/gfx-kernel.le.0.0/adreno.h、`kgsl.h`、`include/uapi/linux/msm_kgsl.h`
置信度：高（内核源码）。但**闭源 userspace 把 EGL 的 LOW/MEDIUM/HIGH 映射到哪几个数值，未找到任何文档**，置信度：未验证。

**Mali**：上游 panthor 驱动是目前唯一可查证的 Mali 侧实现。它定义 `PANTHOR_CSG_PRIORITY_LOW / MEDIUM / HIGH / RT`，并新增 `DEV_QUERY_GROUP_PRIORITIES_INFO` 查询 `allowed_mask`，补丁说明直接写明动机：`"required to implement EGL_IMG_context_priority and EGL_NV_context_priority_realtime extensions properly"`。RT 的语义是 `"Real-time priority allows one to preempt scheduling of other non-real-time groups"`。
来源：https://www.mail-archive.com/dri-devel@lists.freedesktop.org/msg508658.html；https://raw.githubusercontent.com/torvalds/linux/master/drivers/gpu/drm/panthor/panthor_sched.c
置信度：高（对上游）。Arm 闭源 blob 的行为：未找到。

**PowerVR**：Imagination 自家文档确认支持（该扩展本就是 IMG 提出的）。
来源：https://docs.imgtec.com/reference-manuals/open-gl-es-extensions/html/topics/EGL_IMG/context-priority.html
置信度：高。

**Samsung Xclipse**：任何厂商级的 context priority / global priority 说明，**未找到**。

### 1.3 语义：LOW 的 compute 会被同进程 MEDIUM 的 HWUI 抢占吗

硬件层能查证的最权威描述是 msm 的抢占文档（描述的是 Adreno 硬件，上游驱动与下游 KGSL 是同一套 ring 架构）：

- `"When preemption is enabled 4 rings are initialized, corresponding to different priority levels."`
- `"Whenever a submission occurs the kernel finds the highest priority ring which isn't empty"`，并 preempt 过去；提交完成时也会切回低优先级 ring。
- 粒度三档：Level 0 `"Preemption only occurs at the submission level"`；Level 1 `"at either bin level, if using GMEM rendering, or draw level in the sysmem rendering case"`；Level 2 draw level。**msm 用 Level 1。**

来源：https://www.kernel.org/doc/Documentation/gpu/msm-preemption.rst
置信度：高。

由此推出三条结论，请注意后两条是推理而非文档原话：

1. **同进程不构成障碍。** KGSL 的优先级挂在 `kgsl_context` 上（`adreno_get_level(struct kgsl_context *)`），ring 是设备全局的，所以同一进程内两个 EGL context 只要拿到不同优先级就落在不同 ring，抢占与进程边界无关。置信度：中高（源码结构推理）。
2. **Level 1 的"bin/draw"是图形语义，对 compute 没有对应表述。** 全部检索未找到任何说明 compute dispatch 可被中途抢占的文档。能确定的最细粒度是**提交边界**。这意味着：1000 次 dispatch 如果打包在一次提交里，抢占窗口只有一个；拆成多次提交（每次 flush）才能给 HWUI 留出插队机会。置信度：中高（基于粒度定义的推理，标注为推理）。
3. **A7xx 的抢占在上游是 2024 年才实现的，且默认只在 A750 打开**，其他型号要 `enable_preemption` 模块参数，还要 userspace 传 `MSM_SUBMITQUEUE_ALLOW_PREEMPT`。这是上游状态，不代表 Qualcomm 出货 blob。
   来源：https://lwn.net/Articles/989090/
   置信度：高（对上游）。

另有一条硬件能力值得知道但**应用拿不到**：Adreno 6xx 起有独立的 LPAC（Low Priority Async Compute）微控制器与独立 ring（`KGSL_LPAC_RB_ID`），正是为"低优先级 compute 与图形并发"设计的；但 GLES 层无 API 暴露，上游 a7xx 的 LPAC 支持到 2026-07 仍是 RFC。
来源：https://ratatoskr.run/linux-arm-kernel/2026/07/17215679/t
置信度：中高。

另外两个与"长时间 GPU 占用"相关的内核常量，供风险评估：KGSL 的 `_fault_timer_interval = 200`（ms，仅比对寄存器是否变化，忙碌的 GPU 不会误判）与 `adreno_drawobj_timeout = 2000`（ms，单个提交的真实上限）。200–350 ms 的一次性 compute 不会触发挂死检测，但**单次提交不能逼近 2 秒**。
来源：https://raw.githubusercontent.com/ianmacd/gts6lwifi/master/drivers/gpu/msm/adreno_dispatch.c
置信度：高（下游 KGSL 源码，不同 SoC 版本可能微调）。

### 1.4 普通应用能否申请 HIGH

规范侧已给出限制口径：`"system policy limits access to high priority contexts to appropriate system privilege level"`。

- **Mali/panthor**：`group_priority_permit` 只允许 DRM master 或持有 `CAP_SYS_NICE` 的进程设置**高于 MEDIUM** 的优先级。普通应用只能拿到 MEDIUM 和 LOW。置信度：高。
- **Adreno/msm 上游**：`msm_submitqueue_create` 中**没有** `capable()` 检查，只做范围校验（`max_priority = (nr_rings * NR_SCHED_PRIORITIES) - 1`）。置信度：中（`msm_gpu_convert_priority` 在 `msm_gpu.h` 内，未逐字核实函数体）。
- **实操结论**：申请 HIGH 后必须 `eglQueryContext(EGL_CONTEXT_PRIORITY_LEVEL_IMG)` 回读实际值，不得假定成功。

### 1.5 AOSP HWUI 的 RenderThread 用的是什么优先级

`EglManager.cpp`：

```cpp
EglExtensions.contextPriority = extensions.has("EGL_IMG_context_priority");
if (Properties::contextPriority != 0 && EglExtensions.contextPriority) {
    contextAttributes.push_back(EGL_CONTEXT_PRIORITY_LEVEL_IMG);
    contextAttributes.push_back(Properties::contextPriority);
}
```

`Properties.cpp` 中 `int Properties::contextPriority = 0;`，且**不从任何 sysprop 读取**。唯一写入点是 JNI 的 `android_view_ThreadedRenderer_setContextPriority`，对应 Java 侧 `HardwareRenderer.setContextPriority(int)`，带 `@hide`，javadoc：`"Changes the OpenGL context priority if IMG_context_priority extension is available."`，且 `"Must be called before any OpenGL context is created."`

**结论：普通应用的 HWUI RenderThread 根本不传这个属性，落在驱动默认的 MEDIUM 上。** 因此你把粒子预计算上下文设为 LOW，确实构成一级差；设为 MEDIUM 则与 RenderThread 完全平级。
来源：https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/renderthread/EglManager.cpp；https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/Properties.cpp；https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/HardwareRenderer.java
置信度：高。

### 1.6 Chromium / ANGLE 的用法

`ui/gl/gl_context.h` 的注释是本次调研中最直接的工程指引，三句都值得逐字看：

- `"Default is Medium, and the attribute is ignored if the extension is missing."`
- `"'High' priority must only be used for special cases with strong realtime requirements"`
- `"Instead, consider using 'Low' priority for possibly-slow GL work such as user WebGL content."`

`gl_context_egl.cc` 只在非 Medium 时才 push 属性（`"Medium priority is the default, only set the attribute if a different priority is requested."`）。ANGLE 的 `GetContextPriority` 同样以 `EGL_CONTEXT_PRIORITY_MEDIUM_IMG` 为默认。原始 CL 的做法：扩展缺失就忽略请求；只有 VrShell 的私有原生上下文用 High；WebGL 保持 virtualized contexts 且不设优先级——因为上一版设了之后出现同步问题被 revert。
来源：https://raw.githubusercontent.com/chromium/chromium/main/ui/gl/gl_context.h；https://chromium.googlesource.com/chromium/src/+/refs/heads/main/ui/gl/gl_context_egl.cc；https://codereview.chromium.org/2902863002
置信度：高。

**Chromium 的用法与你的场景高度同构：把慢的、可延迟的 GL 工作放 Low，而不是把急的放 High。** 这与规范和各驱动的特权限制都一致。

### 1.7 同 share group 内跨上下文的 `glFenceSync` / `glWaitSync`

规范层可靠，但有三条硬约束（ARB_sync 是 ES 3.0 sync object 的来源规范）：

- `"Sync objects are shared by rendering contexts in the same fashion as texture objects"`
- 多上下文使用时责任在调用方：`"it is up to the programmer to ensure that ... race conditions and deadlocks are avoided."`
- **最容易踩的一条**：`"will not help when waiting for a fence command issued in another context's command stream"`——`SYNC_FLUSH_COMMANDS_BIT` 对跨上下文无效，**生产侧必须自己 `glFlush()`**，否则消费侧可能永久等待。
- `WaitSync` 的语义：`"WaitSync returns immediately, instead causing the GL server to block until <sync> is signaled"`，且 `flags` 必须为 0。

来源：https://developer.download.nvidia.cn/opengl/specs/GL_ARB_sync.txt
置信度：高。

工程实践侧有两条反向信号，需要正视：

- Chromium 在 Android 上**不直接**用 `glFenceSync` 跨上下文，而是走 `EGL_ANDROID_native_fence_sync`（`GLFenceAndroidNativeFenceSync`），并强制用 `gl::GLFence::CreateForGpuFence()` 工厂吃掉平台差异。
  来源：https://chromium.googlesource.com/chromium/src.git/+/master/docs/design/gpu_synchronization.md
- MediaPipe 的实测记录：`"Switching the active GL context on the same thread can be slow on some Android devices"`，因此每个 context 配专用线程。
  来源：https://arxiv.org/pdf/1906.08172
- Chromium 长期在 Android 使用 virtualized contexts，部分驱动（Imagination、旧 Vivante）的 share group 有崩溃与切换性能问题。
  来源：https://groups.google.com/a/chromium.org/g/graphics-dev/c/JnpUq1ralF8

置信度：规范部分高；工程实践部分中高。

**对本项目的可用性判断：有条件可用。** LOW 优先级的预计算上下文 + MEDIUM 的播放上下文，方向与 Chromium 的成熟用法一致，且 HWUI 确实是 MEDIUM，一级差真实存在；但抢占对 compute 的粒度最坏只到提交边界，所以**必须同时把 1000 次 dispatch 拆成多次提交**，只改优先级大概率收效有限。HIGH 不要用（Mali 上普通应用拿不到，且 Chromium 明确劝退）。跨上下文 fence 可靠，但生产侧的显式 `glFlush` 不可省。

---

## 2. SurfaceView 替代 TextureView 做透明全屏 GL 叠加层

### 2.1 官方对合成开销与延迟的表述

官方立场明确偏向 SurfaceView，理由全在合成路径上：

| 论点 | 原文 | 出处 |
|---|---|---|
| 额外拷贝 | `"TextureView contents must be copied, internally, from the underlying surface"` | TextureView javadoc |
| 非独立窗口 | `"Unlike SurfaceView, TextureView does not create a separate window"` | 同上 |
| 官方推荐 | `"SurfaceView is recommended as a more general solution"` | 同上 |
| 二次合成 | `"every visible pixel is composited twice"` | source.android.com `arch-tv` |
| API 24+ | `"In API 24 and higher, it's recommended to implement SurfaceView instead of TextureView."` | 同上 |
| TextureView 的优势 | `"TextureView has better alpha and rotation handling than SurfaceView."` | 同上 |
| 省掉离屏合成 | `"rendering with SurfaceView eliminates extra work"` | `arch-sv-glsv` |
| 功耗 | `"increasing total power draw during video playback by as much as 30%"` | exoplayer 文档 |

来源：https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/TextureView.java；https://source.android.com/docs/core/graphics/arch-tv；https://source.android.com/docs/core/graphics/arch-sv-glsv；https://developer.android.com/media/media3/exoplayer/battery-consumption
置信度：高。

**"TextureView 落后 N 帧"这一常见说法，官方来源未找到。** Khronos、AOSP、source.android.com、developer.android.com 均无对应表述；唯一量化说法出自 2014 年 Crosswalk wiki（第三方），不应作为决策依据。同样**未找到**的还有 2024–2026 年 AOSP 图形工程师的对比博文（`medium.com/androiddevelopers` 全站 403），以及 HWC overlay plane 数量上限与回退判据的官方说明。

### 2.2 增删 SurfaceView 是否仍闪

**最关键的一条是结构性的，来自源码而非文档：用 `setZOrderOnTop(true)` 时，SurfaceView 根本不打洞。**

```java
@Override public void draw(Canvas canvas) {
    if (mDrawFinished && !isAboveParent()) { ... clearSurfaceViewPort(canvas); }
    super.draw(canvas);
}
private boolean isAboveParent() { return mSubLayer >= 0; }
```

`setZOrderOnTop(true)` 把 `mSubLayer` 设为 `APPLICATION_PANEL_SUBLAYER`（= 1），`isAboveParent()` 恒真，`clearSurfaceViewPort()` 永不执行。**"一帧透明洞"在 zOrderOnTop 配置下不成立。** 置信度：高（源码推理，AOSP 无对应文档表述）。

即便走打洞路径，AOSP 也防了首帧：`mDrawFinished` 的注释是 `"Up until that point, we should be careful not to punch holes."`

**真正要小心的是像素格式**：默认是 `RGB_565`，不透明。源码注释：`"Set SurfaceView's format to 565 by default to maintain backward compatibility"`；且 `setFormat(PixelFormat.OPAQUE)` 会被改写成 565。半透明 overlay **必须**显式 `setFormat(PixelFormat.TRANSLUCENT)` 或 `RGBA_8888`——`gatherTransparentRegion()` 也按 `PixelFormat.formatHasAlpha(mRequestedFormat)` 判断。

**`SurfaceView.setUseAlpha()` 是 `@hide`，不可用。** 整层淡入淡出在 Android 14 起直接用 `setAlpha()`：javadoc `"SurfaceView will support arbitrary alpha blending. Prior platform versions ignored alpha values"`，同版本还声明 `"translating and scaling a SurfaceView on screen will not cause rendering artifacts"`。

同步机制：`SurfaceSyncGroup`（`"A way for data to be gathered so multiple surfaces can be synced."`）与 `AttachedSurfaceControl.applyTransactionOnDraw`（`"they will show up on-screen atomically synced"`）。另可用 `setSurfaceLifecycle(SURFACE_LIFECYCLE_FOLLOWS_ATTACHMENT)` 把 Surface 生命周期从"可见性"改成"attach"，避免频繁增删。

**无条件的"flicker-free"承诺：未找到。** AOSP 没有任何一处说创建/销毁 SurfaceView 不会闪。**AOSP issuetracker 上的黑闪 bug 也未找到**——`issuetracker.google.com` 对抓取只返回登录页。相邻的可验证证据：Media3 在 `PlayerSurface.kt` 中对 `SDK_INT == 34` 专门加了 SurfaceSyncGroup 绕行，注释 `"(only present on API 34, fixed on API 35)"`，但对应 issue androidx/media#1237 的症状是几何拉伸而非黑闪，置信度中。

来源：https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/SurfaceView.java；`SurfaceHolder.java`；`window/SurfaceSyncGroup.java`；`AttachedSurfaceControl.java`；https://github.com/androidx/media/issues/1237

### 2.3 Z 序

**`setZOrderOnTop(true)` 仍然在同 app 的 Dialog / PopupWindow / 输入法窗口之下。** javadoc 的措辞严格限定在自己的窗口：`"Control whether the surface view's surface is placed on top of its window."`；`setZOrderMediaOverlay` 把边界说得更明白：`"still behind the window itself"`。源码侧是决定性的——层级全部相对宿主窗口设置：

```java
final SurfaceControl viewRootControl = viewRoot.getSurfaceControl();
t.setRelativeLayer(mSurfaceControl, viewRootControl, mSubLayer);
```

SurfaceControl 被 parent 到 ViewRootImpl 的 bounds layer 上，整颗子树困在 app window 的 layer 内，无法越过 WindowManager 另行排序的兄弟顶层窗口。sublayer 取值：`APPLICATION_MEDIA_SUBLAYER = -2`（默认）、`APPLICATION_MEDIA_OVERLAY_SUBLAYER = -1`、`APPLICATION_PANEL_SUBLAYER = 1`。
置信度：中高（文档只承诺 "its window"，"其他 window 一定在上"这一步是推导，未取到明写 WindowManager 窗口排序规则的官方页；建议实测验证）。

**两个同窗口内都 `setZOrderOnTop(true)` 的 SurfaceView，相对顺序是未定义的。** 二者拿到同一个 `mSubLayer`，源码无任何 tiebreak。新增的 `setCompositionOrder(int)`（`@FlaggedApi(FLAG_SURFACE_VIEW_SET_COMPOSITION_ORDER)`）把这件事写进了文档：`"For SurfaceViews with the same composition order value, their relative order is undefined."`，并且 `"A non-negative composition order value indicates that the SurfaceView is composited on top of the parent window"`——与 `isAboveParent()` 的实现完全一致。该 API 的正式 API level **未找到**。
来源：https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/WindowManagerPolicyConstants.java
置信度：高。

### 2.4 如何得知某一帧真的上屏

四条路，能力差别很大：

**(1) `EGL_ANDROID_get_frame_timestamps`** 是唯一直达物理扫出时刻的路径。`EGL_DISPLAY_PRESENT_TIME_ANDROID` = `"The time at which this frame started to scan out to the physical display."` 但采集默认关闭（`EGL_TIMESTAMPS_ANDROID` 的 `"The initial value is false."`），且逐项可选：`"Not all implementations may support all of the above timestamp queries."`，必须先用 `eglGetFrameTimestampSupportedANDROID` 探测。未发生返回 `EGL_TIMESTAMP_INVALID_ANDROID`，可能还会发生返回 `EGL_TIMESTAMP_PENDING_ANDROID`（需轮询）。规范限定 window surface：`"If surface is not a window surface this has no effect"`。
来源：https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/ANDROID/EGL_ANDROID_get_frame_timestamps.txt；https://android.googlesource.com/platform/frameworks/native/+/master/opengl/specs/EGL_ANDROID_get_frame_timestamps.txt
置信度：高。

需要留意：TextureView 的生产者虽然也是 window surface（`Surface(SurfaceTexture)`），但其消费者是 HWUI 而非 SurfaceFlinger 的 layer，因此 present 类时间戳在 TextureView 上大概率拿不到有效值。**这一点未找到官方明文，属推断，请以 `eglGetFrameTimestampSupportedANDROID` 的实测结果为准。**

**(2) `SurfaceControl.Transaction#addTransactionCompletedListener`** 是 **API 35** 才有，且 latch ≠ present：latch 时间是"被 latch 并排进待呈现队列"，真正的上屏信号是 `getPresentFence()` 返回的 `SyncFence` signal。更早的 `addTransactionCommittedListener`（API 31）是 committed，比 latch 还早，不能当上屏用。
来源：https://developer.android.com/sdk/api_diff/35/changes/methods_index_additions；https://learn.microsoft.com/en-us/dotnet/api/android.views.surfacecontrol.transactionstats?view=net-android-35.0
置信度：中高（API 35 由两个独立来源互证；`getLatchTimeNanos` 等**确切方法名**置信度中，请以真机 SDK 为准）。

**(3) `AttachedSurfaceControl`（`View#getRootSurfaceControl()`）解决的是同步，不是回报**，对"这帧是否已 present"不提供信息。

**(4) `FrameMetrics` 不能用于本目的**：`TOTAL_DURATION` 止于 `"to render and be issued"`（交付给 display subsystem）。`DISPLAY_PRESENT_TIME = 21` 确实存在，但在 `@hide` 的内部 `Index` 接口里，公开 `@IntDef` 止于 `DEADLINE`，`getMetric()` 拿不到。
来源：https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/FrameMetrics.java
置信度：高。

另有一条 API 34 的公开路径：`setTrustedPresentationCallback(...)`，用于判断内容是否真的可见。置信度：中。

### 2.5 `eglPresentationTimeANDROID`

**它只是给缓冲区打标签，不延迟缓冲区，本身不使任何东西可见。** 规范原文：`"Setting the presentation time alone does not cause the color buffer to be made visible"`，而后随 post 时 `"may be passed along for the native window system to use"`；Issue #1 明说用途 `"are intentionally not specified in this extension"`。时钟：`"the time should correspond to the system monotonic up-time clock"`，单位纳秒（`EGLnsecsANDROID`）。

**"不被提前呈现"是 Android 平台层的行为承诺，不是 EGL 扩展的保证**——规范中没有 "no earlier than" 一类措辞。平台侧的说法来自 Frame Pacing 文档：Swappy 用这个扩展 `"so that frames are not presented early"`，另一半（长帧与 buffer stuffing）靠 `EGL_KHR_fence_sync` 注入等待。**只调 `eglPresentationTimeANDROID` 不足以做 frame pacing。** 针对 SurfaceView 的专门用法指引：未找到。
来源：https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/ANDROID/EGL_ANDROID_presentation_time.txt；https://developer.android.com/games/sdk/frame-pacing
置信度：高。

**对本项目的可用性判断：有条件可用，且有一处可能致命。** 合成开销、透明配置、present 回报三方面 SurfaceView 都优于 TextureView，`setZOrderOnTop(true)` 下也不存在打洞闪烁；但 SurfaceView 的 surface 被 parent 在 Activity window 的 layer 之内，**盖不住 Dialog 这类独立 window**。如果粒子必须绘制在弹窗之上，SurfaceView（乃至任何放在 Activity DecorView 里的方案）都做不到，只能让粒子自己成为一个独立窗口，或让弹窗内容改由粒子层自绘。这一条请优先实测确认。

---

## 3. 刷新率

### 3.1 模式切换的代价

官方承认会掉帧，部分面板会黑屏约一秒：`"may result in a frame drop during the transition"`，以及 `"others briefly show a black screen, usually lasting a second"`。框架的**默认策略就是不切模式**，正是为了规避这一点。CTS 给模式切换的宽限期是 4 秒（`FRAME_RATE_SWITCH_GRACE_PERIOD_SECONDS = 4`，再等 `STABLE_FRAME_RATE_WAIT_SECONDS = 1` 确认稳定），这是官方认可的上界。
来源：https://developer.android.com/media/optimize/performance/frame-rate；https://android.googlesource.com/platform/cts/+/master/tests/tests/graphics/src/android/graphics/cts/FrameRateCtsActivity.java
置信度：高。

seamless 的定义来自 `Display.Mode#getAlternativeRefreshRates()` 的 javadoc：`"A seamless switch is one without visual interruptions, such as a black screen"`；列表中出现不保证会切过去，但若切到列表内的刷新率则保证无缝。HAL 层由 `setActiveConfigWithConstraints` 的 `seamlessRequired` 参数与 `SEAMLESS_NOT_POSSIBLE` 返回值决定。
来源：https://source.android.com/docs/core/graphics/multiple-refresh-rate
置信度：高。

**`Display.Mode#isSyntheticMode` 在公开 SDK 中不存在**（API 34–36 成员表核实），不要使用。

`Surface.setFrameRate` 的两个策略常量（AOSP `Surface.java` javadoc 逐字）：`CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS` = `"Change the frame rate only if the transition is going to be seamless."`；`CHANGE_FRAME_RATE_ALWAYS` = `"even if the transition is going to be non-seamless, i.e. with visual interruptions"`。非无缝切换还需用户在系统设置里打开 Match content frame rate 才真正生效。
来源：https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/refs/heads/master/core/java/android/view/Surface.java
置信度：高。

### 3.2 关于你当前用的 `preferredRefreshRate`

**这里有一个与预期相反的发现。** `preferredRefreshRate` 的 javadoc 把它定义为 `Surface#setFrameRate(rate, FRAME_RATE_COMPATIBILITY_DEFAULT, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)` 的等价写法：`"Setting this value is the equivalent of calling Surface#setFrameRate"`。也就是说它**本身就是 seamless-only 的，不应触发非无缝模式切换**。另外两条：API 34 之前该值必须是 display 支持的刷新率之一；`"This value is ignored if preferredDisplayModeId is set."`；以及 `"preferredRefreshRate is ignored for Surfaces that use setFrameRate()"`。
来源：https://learn.microsoft.com/en-us/dotnet/api/android.views.windowmanagerlayoutparams.preferredrefreshrate?view=net-android-35.0
置信度：高（MS Learn 是 AOSP javadoc 的 CC-BY 2.5 逐字镜像；`WindowManager.java` 文件过大，抓取在到达该字段前被截断，未能直接核对源码）。

因此你观察到的 50–70 ms 长帧，**不太可能是"非无缝模式切换"本身**。更可能的两个来源，需要你实测区分：
- 设置 `LayoutParams` 必然经 `Window.setAttributes` → 一次完整的 window relayout 与 surface 重配置；
- 即便是无缝切换，vsync 周期在切换瞬间改变，跨越切换点的那一帧天然会变长。

这两条都是推断，**未找到**官方或第三方的量化数据支撑。

顺带纠正两处常见误传：官方 javadoc 中**不存在** "may result in a visible glitch/blank screen" 这一确切措辞，实际用的是 "visual interruptions"（javadoc）与 "black screen ... usually lasting a second"（指南页）。

### 3.3 Android 15+ 的 ARR 与 `View#setRequestedFrameRate`

ARR 在**同一个 display mode 内**用离散 VSync 步进改变刷新率，因此根本不发生 mode switch。AOSP 原文把 mode switch 直接点名为 jank 源：`"ARR eliminates the need for mode switching, which is a known cause for jank."`，且 `"a display can be configured as either MRR or ARR, but not both"`。开发者侧的复述：`"This reduces power consumption while eliminating the need for potentially jank-inducing mode-switching."`
来源：https://source.android.com/docs/core/graphics/arr；https://developer.android.com/about/versions/16/features
置信度：高。

`View#setRequestedFrameRate`（**API 35**）的五个常量为 `REQUESTED_FRAME_RATE_CATEGORY_` 的 `DEFAULT / NO_PREFERENCE / LOW / NORMAL / HIGH`，也可传正数 Hz。javadoc 的关键限定：`"the preference is valid as long as the View is invalidated"`——是随失效周期生效的投票，不是一次性设置；在 ViewGroup 上调用不传播到子 View。

**对你最要紧的一条**：ARR 的 View 级策略不会覆盖你在 SurfaceView/TextureView 上显式设置的帧率——`"Frame rates explicitly set for TextureView and SurfaceView are respected and applied accordingly."` 自渲染线程的正确入口仍是 `Surface.setFrameRate()`，不是 View API（ARR 指南页完全没提 `Surface.setFrameRate` 与 Choreographer，是纯 View/HWUI 视角）。

硬件检测 API 确实存在但都是 **API 36（Android 16）**：`Display#hasArrSupport()` 与 `Display#getSuggestedFrameRate(int)`，同时 Android 16 恢复了 `Display#getSupportedRefreshRates()`。指南页 Caution：`"To check device support, use the hasArrSupport() API."`
来源：https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate；https://developer.android.com/about/versions/16/features
置信度：高。

另有一条会影响你的行为变更：`"Android 15 sets a default 60Hz refresh rate for games to optimize power consumption."`（若应用被判定为游戏类）。

### 3.4 SurfaceFlinger 对"120 Hz 面板上的 60 Hz 表面"

**SF 优先选投票帧率的倍数，而不是等于它**：`"SurfaceFlinger tries to set the refresh rate to something that is a multiplier of that rate"`。60 Hz 内容投在 120 Hz 面板上，SF 完全可能保持 120 Hz 不做任何切换——这正是你想要的。开发者侧同一结论：`"the platform may switch to a multiple of the frame rate the app specified"`。
来源：https://source.android.com/docs/core/graphics/multiple-refresh-rate；https://developer.android.com/media/optimize/performance/frame-rate
置信度：高。

Judder 的官方口径：60 fps 跑在 **90 Hz** 上会 judder（`"the judder that would otherwise occur if the game ran at 60Hz"`），跑在 120 Hz 上不会（120/60 = 2）。**但必须做一件事**：面板跑在应用帧率的倍数上时，`"the app should specify presentation timestamps for each frame to avoid unnecessary judder"`。

Frame Rate Override 确实能让面板留在 120 而应用跑 60（`"the frame rate is a divisor of the display refresh rate"`），但它是 `GameManagerService` → SF 的 per-UID 系统干预，**不是应用可调用的 API**。

**`FRAME_RATE_COMPATIBILITY_FIXED_SOURCE` 不要用**：`"FRAME_RATE_COMPATIBILITY_FIXED_SOURCE is intended only for video apps. For non-video usage, use FRAME_RATE_COMPATIBILITY_DEFAULT."` 注意 `games/optimize/display-refresh-rate-change` 页的示例代码与此矛盾，**以 javadoc 与 frame-rate 指南为准**。

Google 的明确推荐：`"In general, use setFrameRate() instead of preferredDisplayModeId."`，以及 2020 年博客对 `preferredDisplayModeId` 的 `"This approach is discouraged starting with Android 11 since the platform doesn't know the rendering intention"`。调用频率约束：`"apps should avoid calling setFrameRate() every frame or multiple times per second"`。

### 3.5 关于 `ActiveModeFps`

**未找到。** 我在当前 AOSP `Scheduler/Scheduler.cpp` 与 `Scheduler/RefreshRateStats.h` 中都没有找到名为 `ActiveModeFps` 的 `SFTRACE_INT` / `ATRACE_INT` 计数器。实际找到的刷新率相关 trace 计数器是 `ExpiredIdleTimer`、`ExpiredKernelIdleTimer`、`TouchState`、`ExpiredDisplayPowerTimer`。该名称可能来自旧版本或厂商分支，请在真机 Perfetto trace 上按实际 track 名核对。
来源：https://android.googlesource.com/platform/frameworks/native/+/master/services/surfaceflinger/Scheduler/Scheduler.cpp
置信度：中高（负面结论，基于两处源码抓取）。

### 3.6 可量化的工程数据

**公开、可核实的"一次模式切换耗时 X ms"，未找到。** 能拿到的只有：CTS 的 4 秒宽限期、约 1 秒黑屏（主要见于电视/机顶盒面板）、"至少掉一帧"。Chromium 有一条一手工程证据但无数值：commit 8edd4998（bug 346732738，2024）原文 `"Local testing revealed that setting frame rate does nothing on some Android devices."`，为此加了 UMA 直方图记录实际帧间隔——**证明存在厂商设备直接忽略 `setFrameRate`**。Flutter #160952（Poco/Redmi/Infinix 被锁 60 Hz）只有视频无数据。AOSP issuetracker 与游戏引擎博客的切换耗时实测：未找到。
来源：https://chromium.googlesource.com/chromium/src/+/8edd4998130f58e5ca647810aabb4894264919bb^!/
置信度：高（对"存在设备忽略该 API"这一事实）。

**对本项目的可用性判断：当前做法应放弃。** 120 Hz 面板显示 60 Hz 内容本就是官方认定的无 judder 档位（120/60 = 2），不需要把面板降到 60；正确做法是让面板留在 120，对该 Surface 调一次 `setFrameRate(60f, FRAME_RATE_COMPATIBILITY_DEFAULT, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)` 声明意图，并**逐帧打 presentation timestamp**。这样既无模式切换代价，也无 judder。

---

## 4. RenderThread 驱动的动画

### 4.1 `View.animate()`（ViewPropertyAnimator）：不走 RenderThread

API 29–36 全程由 UI 线程的 `ValueAnimator` + `AnimationHandler`（Choreographer）驱动。`ViewPropertyAnimator.startAnimation()` 内是 `ValueAnimator animator = ValueAnimator.ofFloat(1.0f);`，全文件不出现 RenderThread / RenderNodeAnimator 字样；`withLayer()` 只做 `setLayerType(LAYER_TYPE_HARDWARE, null)`，与线程无关。

曾经存在的 RenderThread 后端 `ViewPropertyAnimatorRT`（类注释 `"This is a RenderThread driven backend for ViewPropertyAnimator."`）在 `lollipop/nougat/pie` 分支存在，在 `android10-release` 与 `master` 均 404——**在 API 29 之前已被删除**。
来源：https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/ViewPropertyAnimator.java；https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/pie-release/core/java/android/view/ViewPropertyAnimatorRT.java
置信度：高（删除的确切 commit 未找到，但分支对照足以界定窗口）。

### 4.2 `createCircularReveal`：走 RenderThread

`ViewAnimationUtils` 直接 `return new RevealAnimator(...)`，而 `public class RevealAnimator extends RenderNodeAnimator`。native 侧的推进链全部在 RenderThread 上：

- `AnimationContext::startFrame`：`mFrameTimeMs = ns2ms(mClock.latestVsync());`
- `BaseRenderNodeAnimator::animate`：`currentPlayTime = context.frameTimeMs() - mStartTime;` → `setValue(mTarget, mFromValue + (mDeltaValue * fraction));`
- `TreeInfo.h` 注释：`"Run only what can be done safely on RT thread. Currently this only means animators"`（`MODE_RT_ONLY`）
- `RenderThread.cpp` 里 `AChoreographer_create()` + `AChoreographer_postVsyncCallback(...)`——RenderThread 有**独立于 UI 线程的 vsync 源**；`CanvasContext::prepareTree` 末尾自我重排 `mRenderThread.postFrameCallback(this);`

来源：https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/Animator.cpp；`TreeInfo.h`；`renderthread/CanvasContext.cpp`；`renderthread/RenderThread.cpp`
置信度：高。

### 4.3 系统 `RippleDrawable`：默认在 RenderThread

官方最直接的一句是 `setForceSoftware` 的 javadoc：`"Sets whether to disable RenderThread animations for this ripple."`

两代实现都是 `RenderNodeAnimator` + `CanvasProperty`：
- API 29–30 走 `RippleForeground`（STYLE_SOLID）：`mPropPaint = CanvasProperty.createPaint(paint);`、`new RenderNodeAnimator(mPropPaint, RenderNodeAnimator.PAINT_ALPHA, ...)`、`c.drawCircle(mPropX, mPropY, mPropRadius, mPropPaint);`；判据是 `!mForceSoftware && c instanceof RecordingCanvas`，否则 `drawSoftware()` 用 `ObjectAnimator`。
- API 31+ 走 Android 12 新增的 `RippleAnimationSession` + `RippleShader`（`RippleAnimationSession.java` 在 `android11-release` 为 404，在 master 存在，确为 S 引入），退化判据：

```java
if (mForceSoftware) return false;
if (!canvas.isHardwareAccelerated()) return false;
if (hwCanvas.mNode == null || !hwCanvas.mNode.isAttached()) return false;
```

**一个关键细节**：`RenderNodeAnimator(CanvasProperty<Float>, float)` 构造里 `mUiThreadHandlesDelay = false;`，字段注释原文说明 ripple 的 startDelay 也交给 RenderThread 计时。而以 `RenderNode` 属性构造的 `RenderNodeAnimator` 默认 `mUiThreadHandlesDelay = true`——**带 startDelay 的 RT 动画，延迟阶段仍被主线程卡住。**
来源：https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/drawable/RippleDrawable.java；`RippleForeground.java`；`RippleAnimationSession.java`
置信度：高。

### 4.4 `ViewGroupOverlay.add(View)` 的布局语义

四条，全部来自源码：

1. **不会自动 measure/layout。** `OverlayViewGroup.onLayout` 是空实现，注释 `// Noop: children are positioned absolutely`，且无 `onMeasure` 覆写。`add(View)` 只做跨容器坐标平移后 `super.addView(child)`。新建的 View 加进去宽高为 0，**必须自己 `measure()` + `layout()`**；从真实父布局搬过去的 View 因为已带尺寸才"看起来自动定位"。
2. **不触发宿主 requestLayout。** `ViewOverlay.java` 中不出现 `requestLayout`；`OverlayViewGroup` 从未 `assignParent`，`mParent` 为 null，传播链断在 overlay 自身。它只回传失效：`invalidate()` → `mHostView.invalidate(...)`。
3. **画在宿主全部子 View 之上，压过任何 elevation/Z。** javadoc：`"drawn after all other content in that view (including the view group's children)"`；`dispatchDraw` 自带 `canvas.enableZ() / disableZ()`，Z 排序只在 overlay 内部生效。overlay 内部顺序是先 View 后 Drawable。调用点在 `View.draw()` 的 `dispatchDraw` 之后、`onDrawForeground` 之前（即在 foreground drawable 之下），这一条置信度中，未取到一手源码引用。
4. **不接收触摸事件。** javadoc：`"Views in the overlay are visual-only; they do not receive input events"`。

来源：https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/ViewOverlay.java；`ViewGroupOverlay.java`
置信度：高（第 3 点的绘制调用点为中）。

### 4.5 直接回答：换成 RenderThread 动画能否免疫 60–80 ms 主线程卡顿

**能免疫"动画开始之后"的卡顿，不能免疫"动画开始之前"的卡顿。** 只要动画已 start 并完成过一次 UI 线程 sync，RenderThread 就能用上一次同步来的 DisplayList 独立出帧。

必须同时成立的前提（任一不满足即退回 UI 线程节奏）：
1. **已 start 且已 sync 过一次。** `doStart()` 末尾 `// Kick off a frame to start the process` → `invalidateParent(false)`，animator 需经 `DrawFrameTask::syncFrameState` → `prepareTree(MODE_FULL)` → `AnimatorManager::pushStaging()` 才进入 RT。若卡顿从 `ACTION_DOWN` 派发前就开始，触摸事件本身也在 UI 线程，ripple 根本发不起来。
2. 目标 RenderNode 已 attach；无 animation handle 时 `pushStaging()` 直接 `ALOGW("Trying to start new animators ... without an animation handle!")` 返回。
3. 硬件加速的 `RecordingCanvas`。
4. `requiresUiRedraw` 不被置位。`TreeInfo::Out` 注释：`"This is set to true if there is an animation that RenderThread cannot animate itself"`；一旦置位，`CanvasContext::prepareTree` 不再 `postFrameCallback(this)`（注释 `"we will just end up fighting the UI thread"`），RT 自驱循环断掉。

仍留在 UI 线程、必须计入设计的部分：
- **内容变化不行。** RT-only 帧重放的是上一次 sync 的 DisplayList，只替换 animator 推进的属性值。任何需要重跑 `onDraw` 的改动都要等 UI 线程。
- **能动的量有限**：`RenderNode` 属性（TRANSLATION/SCALE/ROTATION/ALPHA 等）与 `CanvasProperty<Float>` / `CanvasProperty<Paint>`（`PAINT_ALPHA`、`PAINT_STROKE_WIDTH`）。自绘涟漪若依赖逐帧改路径，改不成 RT。
- **startDelay 是陷阱**（见 4.3）。
- **结束回调迟到**：`callOnFinished` → `animator.mHandler.post(animator::onFinished)`，`onAnimationEnd` 在 UI 线程，卡顿期间视觉已走完但收尾逻辑延后。

**对本项目的可用性判断：可用，且 `RippleDrawable` 是最省心的选项。** 把自绘 ValueAnimator 涟漪换成系统 `RippleDrawable`（或直接用 `CanvasProperty` + `RenderNodeAnimator` 自建），能免疫动画开始后的主线程停顿；它的 `CanvasProperty` animator 连 startDelay 都由 RT 计时，是全链路唯一不依赖 UI 线程计时的现成实现。但**触摸到动画 start 那一段仍在 UI 线程**——如果 60–80 ms 的 View 充气发生在 `ACTION_DOWN` 之后、`start()` 之前，换成 RT 动画不解决问题，必须把充气挪出这个窗口。

---

## 5. Choreographer 与帧节拍

### 5.1 `doFrame(long frameTimeNanos)` 的语义

时基是 `System.nanoTime()`（javadoc：`"in the {@link System#nanoTime()} timebase"`，并说明除以 1000000 转 `SystemClock.uptimeMillis()` 时基）。语义是**这一帧被调度开始渲染的那个 vsync 的时间**：`"the frame time is fixed at the time the frame was scheduled to start"`。

**但文档的字面承诺只在迟到量小于一个 vsync 周期时成立。** AOSP 实现中，一旦 `jitterNanos = System.nanoTime() - frameTimeNanos >= frameIntervalNanos`，Choreographer 会把 frameTimeNanos **重新吸附到离现在最近的 vsync 边界**：

```java
final long jitterNanos = startNanos - frameTimeNanos;
if (jitterNanos >= frameIntervalNanos) {
    frameTimeNanos = startNanos;
    long lastFrameOffset = jitterNanos % frameIntervalNanos;
    frameTimeNanos = frameTimeNanos - lastFrameOffset;
    ...
    if (mBufferStuffingData.isRecovering) {
        frameTimeNanos -= frameIntervalNanos;   // 检测到 buffer stuffing，再往前挪一帧
    }
}
```

**对 120 Hz 的直接含义**：`frameIntervalNanos ≈ 8.33 ms`。任何一次回调迟到超过 8.33 ms，相位就被静默重同步，只在 `skippedFrames >= SKIPPED_FRAME_WARNING_LIMIT` 时打一条 log。**若你的隔帧提交靠 frameTimeNanos 差值判断"现在在第几个 vsync"，一次迟到就会把相位整体挪掉。**
来源：https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/Choreographer.java
置信度：高（源码），但须注明**这是实现行为，公开 javadoc 从未描述它**。

### 5.2 `postVsyncCallback` / `FrameData` / `FrameTimeline`（API 33）

Java 侧：`Choreographer#postVsyncCallback(VsyncCallback)`、`VsyncCallback#onVsync(FrameData)`、`FrameData#getFrameTimelines()`（`"The possible frame timelines, sorted chronologically."`）、`#getPreferredFrameTimeline()`、`FrameTimeline#getVsyncId()` / `#getExpectedPresentationTimeNanos()`（`"The time in System#nanoTime() timebase which this frame is expected to be presented."`）/ `#getDeadlineNanos()`。

**NDK 文档说得比 Java 侧明确得多，且这一句直接改变用法**：动画时钟应当用 expected presentation time 推进，**不是** frameTimeNanos——NDK 对 `getFrameTimeNanos` 明写 `"should not be used to advance animation clocks"`，对 expected presentation time 明写 `"This time should be used to advance any animation clocks."`

半速率场景如何选 timeline，官方只给判据不给配方：`"depending on the frame deadline they can meet when rendering the frame and their desired presentation time"`，以及 `"for apps that do not choose a frame timeline, their frame would be presented at the earliest possible timeline."`

**一个必须指出的公开 API 空洞**：把选中的 vsyncId 交回 SurfaceFlinger 的接口**只有 NDK 有**（`ASurfaceTransaction_setFrameTimeline(transaction, AVsyncId)`，API 33）。**未找到**公开的 Java 对应物。因此对纯 Java/Kotlin + EGL 自渲染线程的应用，`FrameData` 的 timelines 实际上只是只读排程信息，真正的呈现时刻只能靠 `eglPresentationTimeANDROID` 去钉。

另有一个坑：`onVsync` 的 `data` 不能带出回调（`"data is not valid outside of onVsync"`，每个 getter 里都有 `checkInCallback()`），要缓存必须当场把 long 拷出来。
来源：https://developer.android.com/ndk/reference/group/choreographer；https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/Choreographer.java
置信度：API 名与 javadoc 高；"半速率如何选 timeline" 中高（官方只给判据）；Java 侧缺 `setFrameTimeline` 中高。

### 5.3 官方 frame pacing 指引与 `glFinish` 的立场

Swappy 做的三件事：用 `EGL_ANDROID_presentation_time` / `VK_GOOGLE_display_timing` 使 `"frames are not presented early"`；用 sync fence `"to inject waits into the application that allow the display pipeline to catch up, rather than allowing back pressure to build up"`；节拍取自 NDK Choreographer（不可用时回退 Java Choreographer）。`SwappyGL_swap()` 的文档更直白：`"Swappy will wait for the previous frame's buffer to be processed by the GPU"`——**等的是上一帧的 fence，不是排空当前帧**，与 `glFinish()` 是相反的两件事。

**关于 `glFinish`：Android 官方文档中未找到任何明文建议。** 在 `developer.android.com` / `source.android.com` / Android Developers Blog 三域定向检索均无结果。**不应宣称 Google 官方明确反对 `glFinish`。** 但官方用另一种方式表达了同一立场：frame-pacing 页把"靠阻塞产生背压"单列为 Non-Optimal Solution，`"This approach ties a game to variable SurfaceFlinger activity and introduces an extra frame of latency."`

厂商侧则是点名的。Arm：`"glFinish(): explicitly request a pipeline drain."`、`"It is almost impossible to make these API calls fast due to their pipeline draining semantics"`。Samsung Galaxy GameDev：`"Avoid API calls that require one to block and wait for the other."` Qualcomm/Adreno 的对应原文：**未找到**（页面 JS 渲染）。

swap interval：AOSP 的 `"SurfaceFlinger component already supports throttling the FPS of an application as long as the frame rate is a divisor of the display refresh rate."`；Swappy 在 60+90+120 Hz 设备上的可用档位是 120/90/60/45/40/30 FPS；官方推荐的起步配置是把 auto 全关，显式 `swappySwapIntervalNS(1000000000L / preferredHz)`。
来源：https://developer.android.com/topic/performance/frame-pacing；https://developer.android.com/games/sdk/frame-pacing；https://source.android.com/docs/core/graphics/frame-pacing；https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/mali-performance-1-checking-the-pipeline
置信度：Swappy 行为高；"Android 官方无 glFinish 明文" 中高。

### 5.4 120 Hz 想跑 60 Hz：官方推荐

**官方没有给出单一推荐**，而是一套分层答案，其中一层还否定了一个常见选项：

- Choreographer 单用不够：`"There are still subtleties as to when this tick is delivered relative to the actual hardware VSYNC"`，且 `"Buffer-stuffing may still occur for long frames."`
- `setFrameRate` 只是提示：面板可能仍停在 120。
- `FRAME_RATE_COMPATIBILITY_FIXED_SOURCE` 对非视频应用是官方点名的错用（见 3.4）；正例是 `"a game that intends to not run higher than 60Hz ... can call Surface.setFrameRate(60, FRAME_RATE_COMPATIBILITY_DEFAULT)"`。
- 面板停在倍数上时的补救就是逐帧打呈现时间戳（三条机制：`ASurfaceTransaction_setDesiredPresentTime()`、`EGL_ANDROID_presentation_time`、`VK_GOOGLE_display_timing`）。
- C/C++ 一句话：用 Swappy（`"The Frame Pacing library uses Android Choreographer for synchronization and deals with the variability in the tick delivery for you."`）。

**对 Java/Kotlin + 自有 EGL 线程可落地的组合**（这一层是综合，非某页原文）：`Choreographer.postFrameCallback` 隔帧提交 + `EGLExt.eglPresentationTimeANDROID(display, surface, presentNs)`（API 18 起 Java 侧即有）+ 一次性 `setFrameRate(60f, FRAME_RATE_COMPATIBILITY_DEFAULT, CHANGE_FRAME_RATE_ONLY_IF_SEAMLESS)`。Google 自家的参照实现是 Grafika 的 `ScheduledSwapActivity`：实现 `Choreographer.FrameCallback`，在回调里算 `presentNs = frameTimeNs + mRefreshPeriodNs * framesAhead` 再 `setPresentationTime(presentNs)`，用 `"32"` 这类 pulldown 模式跳 vsync——与你的隔帧提交是同一套路。它的注释还给了一条提示：`"SurfaceFlinger provides for some amount of slop."`
来源：https://github.com/google/grafika/blob/master/app/src/main/java/com/android/grafika/ScheduledSwapActivity.java
置信度：高（各条均有官方原文，"唯一推荐"确实不存在）。

### 5.5 GL 线程阻塞与 Choreographer 回调的关系

`eglSwapBuffers` 确实会阻塞，官方逐字：`"If the BufferQueue is full, the eglSwapBuffers() call will wait until a buffer is available."`，且 `"Because this happens on VSYNC, your draw loop timing will match the refresh rate. Mostly."`——官方自己加了保留，紧接着列出两个问题：被绑死在 SurfaceFlinger 活动上；队列未填满时前几次 swap 瞬间返回，`"The computed time between frames will be near zero"`。

队列深度：`"The display pipeline contains a queue of frames, typically of size 2"`，填满后 `"the game loop (or at least the rendering thread) is blocked by an OpenGL or Vulkan call."`，这就是 buffer stuffing，代价是 `"introduces an extra frame of latency"`。

**官方要求应用自己丢帧**：`"the fact that you get a callback after every VSYNC does not guarantee that your callback will be executed in a timely fashion"`、`"Your app will need to detect situations where it's falling behind and drop frames manually."` 官方给的判据是"当前时间比 vsync 时间晚 N 毫秒就丢帧"，60 Hz 举例用 15 ms——**120 Hz 上必须按 8.33 ms 重算，不能照抄 15。**

一条推论（非文档原文）：Choreographer 回调在其 attach 的 Looper 线程上分发。若你的 GL 线程自带 Looper 并在该线程 `postFrameCallback`，GL 线程在 `eglSwapBuffers` 里阻塞会直接推迟回调分发 → `jitterNanos` 增大 → 触发 5.1 的相位重吸附。把 Choreographer 挂在主线程 Looper、只向 GL 线程投递"该画了"的信号，可以切断这条耦合。置信度：中。

`source.android.com/docs/core/graphics/architecture` 与 `arch-bq-gralloc` 中关于 producer 阻塞与背压的可引用原文：**未找到**（这两页现在只剩概述与 `dequeueBuffer`/`queueBuffer` 流程）。这部分的权威来源现在是 `developer.android.com`。
来源：https://developer.android.com/games/develop/gameloops；https://developer.android.com/topic/performance/frame-pacing
置信度：高。

**对本项目的可用性判断：有条件可用。** 隔帧提交的思路本身是对的（Grafika 就是这么做的），但三处必须补：不要用 frameTimeNanos 差值判断跳了几个 vsync（120 Hz 上一次 8.33 ms 的迟到就静默改相位）；动画时钟用 expected presentation time 推进而非 frameTimeNanos；隔帧提交必须配 `eglPresentationTimeANDROID` 钉住呈现时刻，否则 SF 仍可能早一个 vsync 就 latch，产生 8.33 ms 抖动。

---

## 全部来源清单

**Khronos 规范**
- https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/IMG/EGL_IMG_context_priority.txt
- https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/NV/EGL_NV_context_priority_realtime.txt
- https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/ANDROID/EGL_ANDROID_get_frame_timestamps.txt
- https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/ANDROID/EGL_ANDROID_presentation_time.txt
- https://android.googlesource.com/platform/frameworks/native/+/master/opengl/specs/EGL_ANDROID_get_frame_timestamps.txt
- https://developer.download.nvidia.cn/opengl/specs/GL_ARB_sync.txt
- https://raw.githubusercontent.com/KhronosGroup/Vulkan-Docs/main/appendices/VK_KHR_global_priority.adoc
- https://github.com/KhronosGroup/Vulkan-Docs/issues/1398
- https://docs.imgtec.com/reference-manuals/open-gl-es-extensions/html/topics/EGL_IMG/context-priority.html

**AOSP 源码**
- https://android.googlesource.com/platform/frameworks/base/+/master/libs/hwui/renderthread/EglManager.cpp
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/Properties.cpp
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/Properties.h
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/HardwareRenderer.java
- https://mirrors.aliyun.com/android.googlesource.com/frameworks/native/opengl/libs/EGL/egl_platform_entries.cpp
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/SurfaceView.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/SurfaceHolder.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/TextureView.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/WindowManagerPolicyConstants.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/window/SurfaceSyncGroup.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/AttachedSurfaceControl.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/FrameMetrics.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/Choreographer.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/refs/heads/master/core/java/android/view/Surface.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/ViewPropertyAnimator.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/ViewAnimationUtils.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/RenderNodeAnimator.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/ViewOverlay.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/view/ViewGroupOverlay.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/animation/RevealAnimator.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/animation/AnimationHandler.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/animation/RenderNodeAnimator.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/drawable/RippleDrawable.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/drawable/RippleForeground.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/drawable/RippleAnimationSession.java
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/Animator.cpp
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/AnimatorManager.cpp
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/AnimationContext.cpp
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/TreeInfo.h
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/renderthread/CanvasContext.cpp
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/renderthread/DrawFrameTask.cpp
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/renderthread/RenderThread.cpp
- https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/pie-release/core/java/android/view/ViewPropertyAnimatorRT.java
- https://android.googlesource.com/platform/frameworks/native/+/master/services/surfaceflinger/Scheduler/Scheduler.cpp
- https://android.googlesource.com/platform/cts/+/master/tests/tests/graphics/src/android/graphics/cts/FrameRateCtsActivity.java

**Android 官方文档**
- https://source.android.com/docs/core/graphics/arch-sv-glsv
- https://source.android.com/docs/core/graphics/arch-tv
- https://source.android.com/docs/core/graphics/arch-egl-opengl
- https://source.android.com/docs/core/graphics/arch-sf-hwc
- https://source.android.com/docs/core/graphics/architecture
- https://source.android.com/docs/core/graphics/multiple-refresh-rate
- https://source.android.com/docs/core/graphics/arr
- https://source.android.com/docs/core/graphics/frame-pacing
- https://source.android.com/docs/core/perf/performance-hint-api
- https://source.android.com/docs/compatibility/15/android-15-cdd
- https://source.android.com/docs/compatibility/11/android-11-cdd
- https://developer.android.com/media/optimize/performance/frame-rate
- https://developer.android.com/develop/ui/views/animations/adaptive-refresh-rate
- https://developer.android.com/about/versions/16/features
- https://developer.android.com/about/versions/15/features
- https://developer.android.com/about/versions/15/behavior-changes-all
- https://developer.android.com/topic/performance/frame-pacing
- https://developer.android.com/games/sdk/frame-pacing
- https://developer.android.com/games/sdk/frame-pacing/opengl/add-functions
- https://developer.android.com/games/sdk/reference/frame-pacing/group/swappy-g-l
- https://developer.android.com/games/develop/gameloops
- https://developer.android.com/games/optimize/display-refresh-rate-change
- https://developer.android.com/ndk/reference/group/choreographer
- https://developer.android.com/ndk/reference/group/native-activity
- https://developer.android.com/ndk/reference/group/a-performance-hint
- https://developer.android.com/media/media3/ui/surface
- https://developer.android.com/media/media3/exoplayer/battery-consumption
- https://developer.android.com/sdk/api_diff/35/changes/methods_index_additions
- https://android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html

**驱动与内核**
- https://www.kernel.org/doc/Documentation/gpu/msm-preemption.rst
- https://raw.githubusercontent.com/qualcomm-linux/kgsl/gfx-kernel.le.0.0/adreno.h
- https://raw.githubusercontent.com/qualcomm-linux/kgsl/gfx-kernel.le.0.0/kgsl.h
- https://raw.githubusercontent.com/qualcomm-linux/kgsl/gfx-kernel.le.0.0/include/uapi/linux/msm_kgsl.h
- https://raw.githubusercontent.com/ianmacd/gts6lwifi/master/drivers/gpu/msm/adreno_dispatch.c
- https://codebrowser.dev/linux/linux/drivers/gpu/drm/msm/msm_submitqueue.c.html
- https://raw.githubusercontent.com/torvalds/linux/master/drivers/gpu/drm/panthor/panthor_sched.c
- https://www.mail-archive.com/dri-devel@lists.freedesktop.org/msg508658.html
- https://lwn.net/Articles/989090/
- https://ratatoskr.run/linux-arm-kernel/2026/07/17215679/t

**一线工程实践**
- https://raw.githubusercontent.com/chromium/chromium/main/ui/gl/gl_context.h
- https://chromium.googlesource.com/chromium/src/+/refs/heads/main/ui/gl/gl_context_egl.cc
- https://chromium.googlesource.com/chromium/src.git/+/master/docs/design/gpu_synchronization.md
- https://chromium.googlesource.com/chromium/src/+/8edd4998130f58e5ca647810aabb4894264919bb^!/
- https://codereview.chromium.org/2902863002
- https://groups.google.com/a/chromium.org/g/graphics-dev/c/JnpUq1ralF8
- https://github.com/google/grafika/blob/master/app/src/main/java/com/android/grafika/ScheduledSwapActivity.java
- https://github.com/googlevr/gvr-android-sdk/issues/330
- https://github.com/androidx/media/issues/1237
- https://raw.githubusercontent.com/androidx/media/release/libraries/ui_compose/src/main/java/androidx/media3/ui/compose/PlayerSurface.kt
- https://github.com/flutter/flutter/issues/160952
- https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/mali-performance-1-checking-the-pipeline
- https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/mali-performance-5-an-application-s-performance-responsibilities
- https://developer.samsung.com/galaxy-gamedev/resources/articles/opengl.html
- https://arxiv.org/pdf/1906.08172

**MS Learn（AOSP javadoc 的 CC-BY 2.5 逐字镜像，用于替代 JS 渲染的 reference 页）**
- https://learn.microsoft.com/en-us/dotnet/api/android.views.windowmanagerlayoutparams.preferredrefreshrate?view=net-android-35.0
- https://learn.microsoft.com/en-us/dotnet/api/android.views.windowmanagerlayoutparams.preferreddisplaymodeid?view=net-android-35.0
- https://learn.microsoft.com/en-us/dotnet/api/android.views.display.mode?view=net-android-35.0
- https://learn.microsoft.com/en-us/dotnet/api/android.views.display.mode.getalternativerefreshrates?view=net-android-35.0
- https://learn.microsoft.com/en-us/dotnet/api/android.views.view.requestedframerate?view=net-android-36.0
- https://learn.microsoft.com/en-us/dotnet/api/android.views.surfacecontrol.transaction?view=net-android-35.0
- https://learn.microsoft.com/en-us/dotnet/api/android.views.surfacecontrol.transactionstats?view=net-android-35.0

---

## 明确标注为"未找到"的条目汇总

1. Adreno 闭源 userspace 把 EGL 优先级映射到哪个 KGSL priority 数值。
2. Arm 闭源 Mali blob、Samsung Xclipse 的 context priority 官方说明。
3. 任何说明 compute dispatch 可被中途抢占的驱动文档（Adreno/Mali 均无）。
4. Qualcomm 关于 compute 与图形混合提交、`glFinish` 的可引用原文（`docs.qualcomm.com` 全站 JS 渲染）。
5. "TextureView 比 SurfaceView 落后 N 帧"的任何官方来源。
6. HWC overlay plane 数量上限与回退判据的官方说明。
7. AOSP issuetracker 上关于 SurfaceView 增删黑闪的 bug（站点有登录墙）。
8. 2024–2026 年 AOSP 图形工程师的 SurfaceView/TextureView 对比博文。
9. `Display.Mode#isSyntheticMode`（公开 SDK 中不存在）。
10. `SurfaceView.setCompositionOrder` 的正式 API level。
11. 一次刷新率模式切换耗时的公开量化实测数据。
12. 当前 AOSP SurfaceFlinger 中名为 `ActiveModeFps` 的 trace 计数器。
13. Android 官方文档中关于 `glFinish` 的明文立场。
14. `Surface.setFrameRate` 的 vsyncId 回传在 Java 侧的公开对应 API。
15. AGI 的 "Detecting Jank" / "Vertical synchronization" 专页（404，疑已下线）。
