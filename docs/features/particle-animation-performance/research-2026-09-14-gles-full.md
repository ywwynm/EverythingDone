# 移动 GPU compute 调研报告（2026-09-14）

**来源可达性说明**：`registry.khronos.org`、`developer.arm.com`、`docs.qualcomm.com/bundle/...` 三个站点对本工具返回 403 或只返回 JS 壳。解决办法：Khronos 规范改从 KhronosGroup 官方 GitHub 镜像取（内容与 registry 一致）；Qualcomm 文档改走 `docs.qualcomm.com/doc/80-78185-2/topic/*.html` 这条静态路径（可达）；Arm 文档部分取自官方引用与社区博客。凡未能核到一手原文的，均标注「未找到」。

---

## 1. dispatch / barrier 开销

### 1.1 厂商的正面表述

**Qualcomm 说得最直接**，而且正好命中本项目的结构（[Adreno GPU on Mobile: Best Practices](https://docs.qualcomm.com/doc/80-78185-2/topic/mobile_best_practices.html)）：

> "compute shaders require kernel output to be written to memory before the next kernel can begin execute"

这句话就是「逐步 dispatch + barrier」被串行化的机制说明：下一个 kernel 不是等前一个的部分结果，而是等它**整个写回内存**。同页另外三条：

- 「Avoid synchronizing compute threads if possible」，必须同步时优先用 local group 之间的 atomic 而不是 shader barrier；
- 「Separate graphics submits and compute dispatches as much as possible」；
- `glDispatchIndirect()` 工作组尺寸小于 64 时，驱动会把 indirect buffer 映射到更大的工作组，导致 **CPU 等 GPU**。本项目 Jacobi 用 16×16=256，不触发这一条。

**Arm 的表述**（[Arm GPU Best Practices Developer Guide 101897](https://developer.arm.com/documentation/101897/0304/Compute-shading/Workgroup-sizes)，经官方引用与 [azhirnov 整理稿](https://github.com/azhirnov/cpu-gpu-arch/blob/main/gpu/ARM-Mali_Guide.md) 核对）：

- 「Do not assume that barriers with small workgroups are free from performance costs」；
- 工作组越小，barrier 越便宜；基线工作组 64，**不要超过 64 线程**；
- 一旦用了 barrier 或 shared memory，GPU 就不能拆分/合并工作组，整组 work item 必须并发驻留在 shader core 里。

**Khronos/Arm 的 Vulkan 样例**给出了粗粒度 barrier 的代价（[pipeline_barriers 样例](https://docs.vulkan.org/samples/latest/samples/performance/pipeline_barriers/README.html)）：`ALL_COMMANDS → ALL_COMMANDS` 这类 barrier「will often cause a full drain of the pipeline」，改成精确阶段后 frame time 改善 13%。

### 1.2 `GL_SHADER_STORAGE_BARRIER_BIT` 单独 vs 组合多个 bit

**在开源 Adreno 驱动上：没有任何差别。** Mesa/freedreno 当前代码（[freedreno_context.c](https://raw.githubusercontent.com/chaotic-cx/mesa-mirror/main/src/gallium/drivers/freedreno/freedreno_context.c)）：

```c
static void
fd_memory_barrier(struct pipe_context *pctx, unsigned flags)
{
   if (!(flags & ~PIPE_BARRIER_UPDATE))
      return;

   fd_context_flush(pctx, NULL, 0);
}
```

除 `PIPE_BARRIER_UPDATE` 以外的**任意** bit 组合，走的都是同一条 `fd_context_flush`——整批命令流提交。Qualcomm 专有驱动未必逐字相同，但这说明 barrier 在 Adreno 上的硬件映射本身就是粗粒度的，指望「少设几个 bit 就便宜一些」没有依据。

Arm 侧的机制描述一致（[Arm OpenGL ES SDK: compute intro](https://arm-software.github.io/opengl-es-sdk-for-android/compute_intro.html)）：为了保证可见性，`glMemoryBarrier()` 在 tiler 上等于「flush out our scene to memory」——正是要避免的操作。Mali 的 cache 维护没有细粒度（[Arm: Vulkan synchronization on Mali GPUs](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/vulkan-synchronization-how-pipeline-barriers-map-to-mali-gpu-hardware)）：「Cache maintenance operates at the whole-cache level」。

**一处潜在正确性问题**：项目用 `glDispatchComputeIndirect` 读取由 compute shader 写入的 SSBO。按 [ARB_compute_shader 规范](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/ARB/ARB_compute_shader.txt)，`COMMAND_BARRIER_BIT` 覆盖的是「Draw\*Indirect and DispatchComputeIndirect commands」所读的 `DISPATCH_INDIRECT_BUFFER`。只发 `GL_SHADER_STORAGE_BARRIER_BIT` 在规范上不足以让 indirect 参数可见，需要 `GL_COMMAND_BARRIER_BIT`。同理，投影写 RGBA32F image 后若被采样，需要 `GL_SHADER_IMAGE_ACCESS_BARRIER_BIT` / `GL_TEXTURE_FETCH_BARRIER_BIT`（[glMemoryBarrier ES3 参考](https://docs.gl/es3/glMemoryBarrier)）。当前能跑通大概率是靠 freedreno 式的「全 flush」兜底，换驱动可能暴露。

### 1.3 timer query 为什么小于墙钟

[EXT_disjoint_timer_query 规范](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/EXT/EXT_disjoint_timer_query.txt)的计时定义：计时器在 Begin/End 命令「前序所有命令的效果被完全实现」时启停。推论很明确：

- **逐 dispatch 各开一个 query 再求和**，测到的只是每个 query 区间内的 GPU 执行；两次 query 之间的 CPU 提交、驱动 flush、内核排队、GPU 队列排空，全部不在任何一个区间里。求和小于墙钟是**预期行为，不是测量错误**。
- 反之，用**一个** query 包住全部 960 次 dispatch，则 idle 会被算进去——规范同时警告 CPU 计时器与渲染管线不同步，会给出「wildly inaccurate results」。
- `GPU_DISJOINT_EXT` 必须查：一旦发生 disjoint（移动端最常见的就是 DVFS 变频），上次检查以来的所有时间值按规范都是 undefined。0.6 秒的突发负载恰好落在 Adreno devfreq governor 的爬频窗口里，这本身也会拉大墙钟。

Mali 侧有对应的直接量化手段（[Mali-G78 Performance Counter Reference](https://arm-software.github.io/libGPUCounters/mali-g78_counter_reference.html)）：`Non-fragment queue job dependency stalls` 计的正是「有 non-fragment 工作排队但依赖未完成而不能跑」的周期，`Non-fragment queue job issue stalls` 计资源占满导致不能发射的周期。这两个计数器能把「barrier 造成的空转」和「算力不够」分开。

### 1.4 可用于估算的微秒级公开数字

| 数字 | 平台 | 来源 |
|---|---|---|
| 提交开销 **10–20 µs**；**同步之后 GPU 队列排空，再提交额外 50–100 µs** | Snapdragon 8 Gen 3 / Adreno 750 | [HeteroLLM, arXiv:2501.14794](https://arxiv.org/html/2501.14794v1) §3.1「High-cost Synchronization」 |
| 连续 dispatch 摊销 **24–36 µs**（Vulkan 后端）；单次测量 497 µs 中 450 µs 其实是同步 | 桌面四厂商，WebGPU 栈，**无移动 GPU** | [arXiv:2608.08730](https://arxiv.org/html/2608.08730) |
| 命令缓冲提交占 CPU 时间约 40%，**12.9 µs/dispatch** | 同上 | 同上 |
| dummy kernel **230 µs**（OpenCL） | Adreno 640 | 见 [HotMobile 2021](https://dl.acm.org/doi/10.1145/3446382.3448606) 一系及其转述 |
| dispatch 数从 876 降到 564，吞吐 **+53%** | RTX 5090 | [arXiv:2608.08730](https://arxiv.org/html/2608.08730) |

**HeteroLLM 那条 50–100 µs 最贴合本项目**：它描述的就是「同步 → 队列空 → 重新提交」这个循环，与 `dispatch + glMemoryBarrier` 的结构同构。960 × 50–100 µs = **48–96 ms**，与实测 170–230 ms 同一量级。

**「每次 dispatch+barrier 固定开销」的直接公开数据：未找到。** Qualcomm 与 Arm 都不发布这个数字。上表是最接近的代理量。

### 对本项目的可用性判断

960 次 dispatch+barrier 极可能是墙钟的主要构成，而非着色器本身。反推值 ≈180–240 µs/次，比 HeteroLLM 在 Adreno 750 上测到的 50–100 µs 还高一倍，说明除了队列排空还有别的成本（可能是 freedreno 式的整批 flush，或 DVFS 未爬满频）。**先做一个 30 分钟的判别实验再决定优化方向**：把 960 次积分步改成「无依赖、不发 barrier」的空转版本（结果错误无所谓），只测墙钟。若墙钟掉到 50 ms 以下，瓶颈确认在 barrier，合并 dispatch 才值得重开；若仍在 150 ms 以上，瓶颈在着色器，应该去优化 curl 噪声与 3D 纹理采样。同时立刻补上 `GL_COMMAND_BARRIER_BIT`——这是正确性问题，与性能无关。

---

## 2. 着色器数值确定性

### 2.1 `precise` 在 GLSL ES 3.10 里不是现成的

这是本节最重要的一条。**GLSL ES 3.10 本体只是把 `precise` 列入保留关键字**（3.10 把保留字表对齐到 GLSL 4.40），功能来自扩展 [GL_EXT_gpu_shader5](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/EXT/EXT_gpu_shader5.txt)，该扩展的 Dependencies 明确写 requires「OpenGL ES 3.1 and OpenGL ES Shading Language 3.10」。使用前必须查扩展字符串并写 `#extension GL_EXT_gpu_shader5 : require`。

该规范对 `precise` 的语义表述：保证「operations contributing to a variable's value are performed in the order and with the precision specified in the source code」，并且禁止「the arbitrary use of fused multiply-add operations if the intermediate multiply result is kept at a higher precision」。同时 `fma()` 在 precise 变量里算**一个** operation，而 `a*b + c` 算**两个**。

即：`precise` 同时禁止重排（reassociation）与隐式收缩（contraction），但它不是「关闭 fast-math」的总开关——它是逐变量的。

### 2.2 编译器确实会做 fma 收缩，而且 Adreno 上后果显著

最有力的一手证据是 [SPIRV-Tools issue #5658](https://github.com/KhronosGroup/SPIRV-Tools/issues/5658)：`spirv-opt` 的 `simplify-instructions` 把 mul+add 序列折成 FMA，在 **所有 Adreno 设备**上造成大幅性能退化（指令数 868 → 1077，32-bit ALU 52 → 200）；同样的改动对 Arm 无影响、PowerVR 略有提升、AMD/Samsung 略有退化。修复在 SPIRV-Tools v2024.3：「Do not fold mul and adds to generate fmas」。

这条既说明**收缩确实会被工具链自动施加**，也说明在 Adreno 上 FMA 与 mul+add 走的是**不同的硬件路径**——路径不同，结果自然不逐位相同。

规范层面兜底的一句（[GLSL ES 3.20 规范](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html)）：highp 变量在逻辑上按 IEEE 754 单精度编码，但运算「are not necessarily performed as required by IEEE 754」。

Denormal 方面（[Arm: Benchmarking floating point precision in mobile GPUs](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/benchmarking-floating-point-precision-in-mobile-gpus---part-iii)）：Arm 自己说支持 subnormal「is rare」，Mali Midgard 系列是唯一提供支持的移动 GPU 家族。其余一律 flush-to-zero。curl 噪声在接近零的区间上，这一点可能真实影响结果。

### 2.3 有没有关掉 fast-math 的 `#pragma` 或扩展

**GLES 上没有可靠手段。**

- `#pragma optimize(off)` 确实写在 GLSL 规范里，但规范同时规定实现**可以忽略无法识别的 pragma**，实践中普遍无效（[GLSL 4.60 规范](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.pdf)、多方工程反馈）。
- Vulkan 有真正的开关：[VK_KHR_shader_float_controls](https://docs.vulkan.org/refpages/latest/refpages/source/VK_KHR_shader_float_controls.html)（Vulkan 1.2 核心）提供 `DenormPreserve` / `DenormFlushToZero` / `SignedZeroInfNanPreserve` / `RoundingModeRTE` / `RoundingModeRTZ`，配合 SPIR-V 的 `NoContraction` decoration（glslang 正是把 GLSL 的 `precise` 映射成 `NoContraction`，见 [GLSL spirvmappings](https://github.com/KhronosGroup/GLSL/blob/main/chapters/spirvmappings.adoc)）。
- **OpenGL ES 无等价物**。`GL_EXT_spirv_intrinsics` 只在「GL + SPIR-V」路径可用，GLES 的 GLSL 源码路径用不了。**未找到**任何 GLES 扩展提供 float controls。

### 2.4 寄存器保存 vs SSBO 往返，会不会引入差别

需要把两件事分开：

1. **SSBO 往返本身是精确的。** highp float 是 32 位，std430 里 float 占 4 字节，store/load 就是 32 位搬运，不引入舍入。
2. **差别来自往返被移除之后编译器获得的自由度。** 逐步版本里，每步结束必须把状态写回内存——这是一道**优化屏障**，编译器无法跨步重结合或收缩。合并进一个循环后，编译器（尤其在展开之后）可以跨迭代做 FMA 收缩、重结合、公共子表达式提取，以及把中间量留在可能更宽的寄存器里而不 round 回 fp32。这正是 2.1 里 EXT_gpu_shader5 说的「intermediate multiply result is kept at a higher precision」。
3. 次要因素：denormal 在两条路径上的处理可能不同（寄存器路径 flush、内存路径保留，或反之）。

### 2.5 工程上怎么做

**路线 A：坚持逐位相同（可行但要付出代价）**

按屏障强度从弱到强叠加：

1. 状态变量声明为 `precise`（需 `GL_EXT_gpu_shader5`）；
2. 把所有 `a*b + c` **显式**写成 `fma(a,b,c)` 或显式写成两步，把收缩与否的选择权从编译器手里拿回来；
3. 在合并后的 dispatch 里**保留每个子步的 SSBO 往返**（写回再读出），并把该 SSBO 变量加上 `volatile` 限定符——规范语义是编译器必须假定内容随时可变，不得缓存或跨访问重排（[Khronos GLSL 文档](https://docs.vulkan.org/glsl/latest/chapters/variables.html)）。这样既省掉 960 次 API 级 barrier，又保住每步的数值边界；
4. 逐步收紧、每加一条就用位比较验一次，找到最小充分集。

**注意第 3 条正是关键**：合并 dispatch 的收益来自省掉 API 级 `glMemoryBarrier` 和驱动提交，而**不是**来自省掉内存往返。保留往返几乎不损失收益，却能挡住绝大部分数值漂移。

**路线 B：放弃逐位相同，改用误差门禁**

- 数值侧：对整条 0.6 秒轨迹逐帧统计**最大相对误差**与**相对 L2**，并对关键量记 ULP 距离。粒子动力学文献中，平均误差量级 1e-7、扩散 1e-6（即 < 0.001%）被视为视觉不可分。
- 视觉侧：用 [ꟻLIP](https://github.com/NVlabs/flip)（NVIDIA 的 alternating-image 差异评估器，`pip install flip-evaluator`）而不是 PSNR/SSIM——它就是为「来回切换两张渲染图时人眼能否察觉」设计的，会输出逐像素误差图，便于定位是整体漂移还是局部爆炸。
- 门禁必须显式挡 NaN：全用 `<`/`>` 的阈值对 NaN 失明。

**路线 C：定点/整数**。如果确定性是硬需求（例如要做轨迹缓存复用），整数运算在任何 GPU 上都逐位一致。代价是要重做整套数值。

### 对本项目的可用性判断

先确认目标：需要的是「与旧版逐位相同」还是「跨运行可复现」？如果只是后者，同一台设备同一驱动下逐步版本已经满足，合并后只要过误差门禁即可，**当初的回退决定其实可以撤销**。如果确实要前者，路线 A 第 3 条（合并 dispatch 但保留 SSBO 往返）性价比最高——它拿到了合并的全部性能收益，同时保留了逐步版本的数值边界，值得优先试。另外无论走哪条路，都应先查 `GL_EXT_gpu_shader5` 是否在两台目标设备上可用；如不可用，`precise` 根本写不了，路线 A 要打折。

---

## 3. 零拷贝纹理

### 3.1 调用链与扩展要求

三步（[Android NDK: Native Hardware Buffer](https://developer.android.com/ndk/reference/group/a-hardware-buffer) 直接给出这条链路，并声明所有 AHardwareBuffer 操作是 zero-copy）：

1. `eglGetNativeClientBufferANDROID(const AHardwareBuffer*)` → `EGLClientBuffer`（[EGL_ANDROID_get_native_client_buffer](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/ANDROID/EGL_ANDROID_get_native_client_buffer.txt)）；
2. `eglCreateImageKHR(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, clientBuffer, attrs)`。token `EGL_NATIVE_BUFFER_ANDROID = 0x3140` 定义在 [EGL_ANDROID_image_native_buffer](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/ANDROID/EGL_ANDROID_image_native_buffer.txt)，该规范同时写明「attributes other than EGL_IMAGE_PRESERVED_KHR are ignored」。**`EGL_IMAGE_PRESERVED_KHR` 必须显式设为 TRUE**，否则按 [EGL_KHR_image_base](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/KHR/EGL_KHR_image_base.txt) 像素值 undefined；
3. `glEGLImageTargetTexture2DOES(target, image)`，[OES_EGL_image](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/OES/OES_EGL_image.txt) 规定 target 当前只能是 `TEXTURE_2D`。

Filament 的真实实现可作模板（[PlatformEGLAndroid.cpp](https://raw.githubusercontent.com/google/filament/main/filament/backend/src/opengl/platforms/PlatformEGLAndroid.cpp)），注意它 ctx 传 `EGL_NO_CONTEXT`、attrs 首两项是 `EGL_IMAGE_PRESERVED_KHR, EGL_TRUE`。

扩展要求：`EGL_ANDROID_get_native_client_buffer` 与 `EGL_ANDROID_image_native_buffer` 均 requires EGL 1.2 + `EGL_KHR_image_base`；`GL_OES_EGL_image` requires GLES 1.1 或 2.0。API level：`AHardwareBuffer` 自 **26** 起；`eglGetNativeClientBufferANDROID` 是扩展函数，需 `eglGetProcAddress` 取址，门槛是运行时扩展字符串而非编译期 API level。

### 3.2 JNI 是必须的

**结论：必须 JNI，现代 Android 不存在纯 Java 路径。** 逐层堵死：

- `AHardwareBuffer_fromHardwareBuffer` 的第一个参数就是 `JNIEnv*`（API 26）。
- `android.opengl.EGLExt`（[AOSP 源码](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/opengl/java/android/opengl/EGLExt.java)）全部成员里**没有** `eglGetNativeClientBufferANDROID`，**没有** `EGL_NATIVE_BUFFER_ANDROID`。
- `EGL15.eglCreateImage` 的 buffer 参数是裸 `long`，而 `android.hardware.HardwareBuffer` 的 `mNativeObject` 是 **private**，没有任何公开方法暴露它——拿不到那个 long。
- 即便拿到，`GLES11Ext.glEGLImageTargetTexture2DOES(int, java.nio.Buffer)` 的参数类型与 `EGL15` 返回的 `EGLImage` **不兼容**，两个 API 在 Java 层拼不起来。

**纯 Java 的替代是 `SurfaceTexture`，不是 `ImageReader`**（`Image.getHardwareBuffer()` 给你的仍是 `HardwareBuffer`，绑 GL 还是要 JNI）。SurfaceTexture 的代价全部有官方依据：着色器必须改用 `samplerExternalOES` 并加 `#extension GL_OES_EGL_image_external : require`；纹理坐标必须过 `getTransformMatrix`；`GenerateMipmap` 报 `INVALID_ENUM`；min filter 只接受 NEAREST/LINEAR；wrap 只能 CLAMP_TO_EDGE；`updateTexImage` 只能在拥有该纹理的 GL 上下文当前时调用（[OES_EGL_image_external](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/OES/OES_EGL_image_external.txt)、[AOSP SurfaceTexture.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/SurfaceTexture.java)）。

### 3.3 usage 标志与格式

- `AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE` 即「buffer will be read from by the GPU as a texture」。CPU 标志只服务于 `AHardwareBuffer_lock`，**GPU 采样不需要 CPU 标志**。
- **`ImageReader` 必须用 5 参数的 `newInstance(w,h,format,maxImages,usage)` 显式传 `USAGE_GPU_SAMPLED_IMAGE`**；4 参数版默认是 `USAGE_CPU_READ_OFTEN`（[AOSP ImageReader.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/media/java/android/media/ImageReader.java)）。这是容易漏的一步。
- **没有强制格式清单。** EGL 规范自己说格式集合是 implementation-specific；Vulkan 的 AHB 扩展同样要求运行时查询。**未找到**任何官方文档声明 `R8G8B8A8_UNORM` + `GPU_SAMPLED_IMAGE` 必然可用。唯一正确做法是 `AHardwareBuffer_isSupported`（API 29）/ `HardwareBuffer.isSupported`（纯 Java 可调）先探测。

### 3.4 Adreno / Mali 上的限制

- **sRGB**：AHardwareBuffer 格式枚举中**无任何 sRGB 变体**，`R8G8B8A8_UNORM` 对应 `GL_RGBA8`（线性），采样不做 sRGB 解码。唯一控制手段是 [EGL_EXT_image_gl_colorspace](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/EXT/EGL_EXT_image_gl_colorspace.txt)，Filament 正是靠它传 `EGL_GL_COLORSPACE_SRGB`。**隐患**：`EGL_ANDROID_image_native_buffer` 说「其他属性被忽略」，所以在不支持该扩展的驱动上这个属性可能被**静默忽略**，表现为颜色偏亮/偏暗而非报错——必须先查 `eglQueryString(dpy, EGL_EXTENSIONS)`。
- **Mipmap**：`glEGLImageTargetTexture2DOES` 这条路走不通。`OES_EGL_image` 规定一旦重新指定（含 `GenerateMipmapOES`）纹理就不再是 EGLImage target，零拷贝作废。正确路径是另一套 API：`GPU_MIPMAP_COMPLETE` 要求用 [EXT_EGL_image_storage](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/EXT/EXT_EGL_image_storage.txt) 的 `EGLImageTargetTexStorageEXT`，**这把 GLES 下限抬到 3.0**。Filament 还留了一条实测注释：mipmapped external texture 不能用 `GL_TEXTURE_EXTERNAL_OES`，驱动只对 `GL_TEXTURE_2D` 导入报告 levels > 1。
- **Tiling**：是厂商 tiled 而非线性。Vulkan 的 AHB 扩展明说实现不要求支持 linear-tiled 的 AHB 图像。这对采样**有利**——与驱动自己分配的纹理同布局，不存在「导入的所以更慢」。**未找到**Arm/Qualcomm 官方文档说明「申请 CPU 标志会迫使 gralloc 退回线性/关闭 AFBC/UBWC」，业界常说但无一手来源，不作断言。
- **target 选择**：RGBA8 用 `GL_TEXTURE_2D`；YUV 格式（`Y8Cb8Cr8_420` 等）NDK 文档明确写只能经 external sampler 访问。
- **驱动缺陷**：已知记录都相当陈旧（Chromium 在 Mali-400 上全面禁用 `glEGLImageTargetTexture2DOES`；Mozilla Bug 1264505 记录 libGLES_mali.so 在该调用上崩溃）。**未找到**针对现代 Adreno 6xx/7xx 或 Valhall Mali 的一手缺陷报告。**仍然真实存在的约束是 FD 上限**：每个 hardware bitmap 占一个文件描述符，进程上限约 1024（[Glide 文档](https://bumptech.github.io/glide/doc/hardwarebitmaps.html)）。

### 3.5 `Bitmap.copyPixelsToBuffer` 的字节序 —— 可以免转换

**结论：ARGB_8888 位图 `copyPixelsToBuffer` 的输出在内存中就是 R,G,B,A，可以原样喂给 `glTexImage2D(..., GL_RGBA, GL_UNSIGNED_BYTE, buf)`，零 swizzle。**

五条独立证据构成闭环：

1. [AOSP Bitmap.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/Bitmap.java) 的 ARGB_8888 javadoc 给出打包公式，并明确说这是 `copyPixelsToBuffer` 场景用的：`(A&0xff)<<24 | (B&0xff)<<16 | (G&0xff)<<8 | (R&0xff)`。R 在 bit 7..0，小端机上就是第一个字节。**名字叫 ARGB，内存里是 RGBA。**
2. [Skia SkColorType.h](https://raw.githubusercontent.com/google/skia/main/include/core/SkColorType.h) 对 `kRGBA_8888` 的位布局注释与上式逐位相同。
3. AOSP 类型映射链：`ARGB_8888` → `kN32_SkColorType` → `ANDROID_BITMAP_FORMAT_RGBA_8888`。
4. NDK 文档对该格式的描述就是 Red/Green/Blue/Alpha 各 8 位。
5. [AOSP Bitmap.cpp](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/jni/Bitmap.cpp) 的拷贝实现是纯 `memcpy`，无任何字节交换。

而 `GLUtils.texImage2D` 的 native 实现（[util.cpp](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/jni/android/opengl/util.cpp)）对该格式返回的正是 `GL_RGBA` + `GL_UNSIGNED_BYTE`，且**不含任何 swizzle 逻辑**。两条路径上传的是**完全相同的字节、完全相同的 format/type**。

三个必须记住的细节：

- **Endianness 不影响本次拷贝**（实现是 memcpy，`ByteBuffer.order()` 不参与写入）；它只影响你之后按 int/short 读取时的解释。
- **`copyPixelsToBuffer` 会推进 position**，直接交给 `glTexImage2D` 会上传到错误偏移——务必先 `rewind()`。
- **预乘 alpha 被原样拷贝**（javadoc: 内容 as-is 拷入）。ARGB_8888 默认预乘，GL 侧必须用 `glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA)`，否则半透明边缘发暗。`GLUtils.texImage2D` 在这点上没有区别。
- `Config.HARDWARE` 位图调 `copyPixelsToBuffer` 抛 `IllegalStateException`。

### 对本项目的可用性判断

3.5 是好消息，可以立刻用：3D 纹理数据若源自 ARGB_8888 Bitmap，上传路径不需要任何通道转换。AHardwareBuffer 零拷贝路径技术上成立，但要付出一层 JNI，且 sRGB 与 mipmap 都有坑；本项目的 3D 噪声纹理是一次性预计算后反复采样，零拷贝省下的只是一次上传，**收益与复杂度不匹配，不建议现在做**。真正需要它的场景是每帧从相机/视频流拿新纹理。

---

## 4. 多上下文与同步

> 本节由我直接核对 Khronos 一手规范得出。另有一路并行调研仍在进行中，结论以本节为准。

### 4.1 共享对象与可见性

`eglCreateContext` 的 `share_context` 参数（[Khronos EGL refpage](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/sdk/docs/man/html/eglCreateContext.xhtml)）：共享的是「由当前 client API 规范定义的所有可共享数据」，并且共享具有传递性——新上下文与 `share_context` 已共享的所有上下文一并共享。

**明确写出的唯一硬性限制是地址空间**：所有共享数据的上下文必须处于同一地址空间，即同一进程。**该 refpage 未包含 ERRORS 章节**，因此「是否必须同 EGLConfig」**未能从一手规范核实**（EGL 1.5 spec 只有 PDF，registry 403）。实践上 Buffer/Texture/Renderbuffer/Shader/Program/Sync 共享，而 VAO、Framebuffer、Program Pipeline、Query、Transform Feedback **不共享**——这条是 GLES 规范附录的通例，但本次同样未能核到原文，标注为待验证。

### 4.2 fence 的正确用法，以及那个会死锁的坑

**Sync 对象是共享的。** [ARB_sync 规范](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/ARB/ARB_sync.txt) Issue 1 的结论是 YES，sync 对象命名空间共享，共享的 sync 可以在任何与之共享的上下文里被阻塞等待或删除。

**最关键的一条**，ARB_sync 规范的脚注：

> "The simple flushing behavior defined by SYNC_FLUSH_COMMANDS_BIT will not help when waiting for a fence command issued in another context's command stream"

即：`glClientWaitSync` 的 `GL_SYNC_FLUSH_COMMANDS_BIT` 只 flush **调用它的那个上下文**（当前上下文），对**产生 fence 的那个上下文**毫无作用。规范接着要求应用「must take additional steps」确保发出 fence 的上下文已经把该命令 flush 进管线。

EGL 侧的表述完全一致（[EGL_KHR_fence_sync](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/KHR/EGL_KHR_fence_sync.txt)）：`EGL_SYNC_FLUSH_COMMANDS_BIT_KHR` 执行的是「当前 API 上下文」的 Flush。

**因此正确的跨上下文交接序列是：**

```
线程 A（预计算）:
  glDispatchCompute(...)
  glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)   // 先让写对后续 GL 命令可见
  sync = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
  glFlush()                                        // ← 必须。否则 B 可能永远等不到
  把 sync 交给线程 B

线程 B（播放）:
  glWaitSync(sync, 0, GL_TIMEOUT_IGNORED)          // GPU 侧等待，不阻塞 CPU
  ... 读 SSBO ...
  glDeleteSync(sync)
```

`glWaitSync` **本身不做任何 flush**（规范只给 `ClientWaitSync` 定义了 flush 行为），所以 A 侧那句 `glFlush()` 是不可省的。漏掉它的症状是间歇性挂死，且在不同驱动上表现不同——这是这套结构最常见的故障模式。

`glMemoryBarrier` 要放在 fence **之前**：fence 保证的是「命令完成」，barrier 保证的是「写入对后续读取可见」，两者不能互相替代。

### 4.3 轮询 vs 阻塞

- `glWaitSync` 是 GPU 侧等待：命令入队后 CPU 立即返回，GPU 自己在流里等。**在本项目这种「A 产 B 消」的结构里这是首选**，CPU 完全不参与。
- `glClientWaitSync` 是 CPU 侧阻塞。若必须在 CPU 上等，用非零 timeout 的一次阻塞调用，而不是 `timeout=0` 的自旋循环。自旋在移动端直接体现为功耗与发热，且会与 GPU 抢内存带宽。
- **Android 官方对 `glClientWaitSync` 轮询 vs 阻塞的具体建议：未找到。** 但 AOSP 把 `EGL_KHR_wait_sync` 列为 GPU 厂商必须支持的强制扩展（[AOSP: Implement OpenGL ES and EGL](https://source.android.com/docs/core/graphics/implement-opengl-es)），说明 GPU 侧等待在 Android 上是被当作基础设施看待的，可以放心用。
- 若需要把同步扩展到 GL 之外（例如交给 SurfaceFlinger 或另一个 API），用 `EGL_ANDROID_native_fence_sync` 拿到一个 fd。本项目两端都在 GL 内，用不上。

### 对本项目的可用性判断

结构本身成立，且是标准做法。三条必须落实：**(a)** 生产端 `glMemoryBarrier` → `glFenceSync` → **`glFlush()`** 顺序一条都不能少，漏 flush 会间歇性挂死且极难复现；**(b)** 消费端优先 `glWaitSync` 而非轮询；**(c)** 两个上下文必须同进程。同 EGLConfig 是否强制我没能核到规范原文，保守起见两端用同一个 config 创建，这样无论规范怎么写都不会踩到。

---

## 5. Jacobi 分块

### 5.1 硬件上限（实测值）

| GPU | shared memory 上限 | 最大工作组 invocations | 来源 |
|---|---|---|---|
| **规范下限** | 至少 32 KB | — | [Android: Migrate to GLES 3.1](https://developer.android.com/guide/topics/renderscript/migrate/migrate-gles)；[ARB_compute_shader](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/ARB/ARB_compute_shader.txt) 同为 32768 / 1024 |
| **Adreno**（730 实测） | **32 KB / SP**（全芯片 256 KB，8 个 SP） | — | [chipsandcheese: Snapdragon 8+ Gen 1 iGPU](https://chipsandcheese.com/p/inside-snapdragon-8-gen-1s-igpu-adreno-gets-big) |
| **Adreno**（驱动侧） | `cs_shared_mem_size = 32 * 1024` | — | [Mesa freedreno commit](https://www.mail-archive.com/mesa-commit@lists.freedesktop.org/msg152334.html) |
| **Mali-G720-Immortalis** | **32768** | **1024** | [vulkaninfo log](https://gist.github.com/nyanmisaka/ce03b5f61cb02389a97094b5172f5bf0) |
| **Xclipse 530/540/550/920/930A/940/950** | **32768** | — | [llama.cpp PR #28531](https://github.com/ggml-org/llama.cpp/pull/28531) |
| **Xclipse 960** | **65536** | — | 同上 |

wave/warp：Adreno 730 是 wave64（Adreno 640 为 wave128）；Mali Valhall 是 16 线程 warp，G52/G76 为 8，Bifrost 按 quad(4) 锁步。

**16 KB 是需要防御的下限**：[Vulkan 移动优化文档](https://vulkan.lunarg.com/doc/view/latest/windows/antora/tutorial/latest/Advanced_Vulkan_Compute/12_Mobile_and_Embedded_Compute/03_mobile_optimization.html) 提醒 `maxComputeSharedMemorySize` 在某些移动设备上「can be 16 KB」、`maxComputeWorkGroupInvocations`「can be 128」。

### 5.2 当前配置的冗余率

16×16 tile + 8 格边带 → 加载 32×32，8 次迭代每次收缩一格：

```
总计算量 = 30² + 28² + 26² + 24² + 22² + 20² + 18² + 16² = 4400
有效输出 = 8 × 16² = 2048
冗余因子 = 4400 / 2048 ≈ 2.15×   （即 115% 的浪费）
shared memory = 32×32×4 = 4 KB   （上限的 12.5%）
```

**shared memory 用了 32 KB 里的 4 KB，而冗余率高达 115%——这是明显的配置失衡。** 换成 32×32 tile + 8 halo：

```
加载 48×48
总计算量 = 46² + 44² + … + 32² = 12336
有效输出 = 8 × 32² = 8192
冗余因子 = 12336 / 8192 ≈ 1.51×   （50% 浪费，比现在少一半多）
shared memory = 48×48×4 = 9 KB    （仍在 32 KB 内，也在 16 KB 防御线内）
```

同时 dispatch 数量降到约 1/4。代价是工作组变成 1024 线程。

### 5.3 但 Adreno 和 Mali 的最优点不一样

- **Adreno**：256 线程工作组历史上就是甜点（Adreno 530 实测 256 最佳，且更偏好指令级并行）。1024 线程可能因活跃寄存器过多而掉 wave128、降占用率——**需要实测，不能直接上**。Adreno 的 shared memory 是真片上，`k` 次迭代留在 shared 里是纯赚。
- **Mali**：Arm 的建议是「基线 64，不要超过 64 线程/工作组」，本项目的 **256 已经是建议上限的 4 倍**，改 1024 就是 16 倍。更根本的是 Arm 说 Mali **没有专用片上 shared memory**，shared 就是走 load/store cache 的系统 RAM，并且明确「Do not copy data from global memory to shared memory on Arm GPUs」——**整套 shared-memory 分块 Jacobi 的前提在 Mali 上不成立**。同时由于用了 barrier 和 shared memory，Mali 无法拆分/合并工作组，256 个 work item 必须并发驻留。

### 5.4 `k` 的取舍

文献上的共识与本项目当前的 k=8 一致：温度分块（temporal blocking）的收益随 k 增长而边际递减，因为 halo 冗余随 k 线性扩张；AN5D 一类自动化框架报告性能大约在 TB degree 10 附近饱和（[AN5D, arXiv:2001.01473](https://arxiv.org/pdf/2001.01473)；[Revisiting Temporal Blocking, arXiv:2305.07390](https://arxiv.org/pdf/2305.07390)）。**k=8 处在合理区间，不是问题所在；tile 尺寸才是。**

**关于「halo 冗余计算 vs barrier 减少」的定量权衡数据：未找到针对 Adreno/Mali 的公开测量。** 上述文献均基于 NVIDIA/AMD 桌面 GPU，其 shared memory 是真片上且带宽远高于移动端，结论不能直接外推——尤其不能外推到 Mali。

### 对本项目的可用性判断

**shared memory 远未用满而冗余率高达 115%，这是当前最明确、最便宜的优化点。** 在 Adreno 上把 tile 从 16×16 提到 32×32（shared 4 KB → 9 KB，冗余 115% → 50%，dispatch 数降到 1/4）值得优先试，但必须实测 1024 线程工作组是否掉占用率——如果掉，退到 24×24（shared 40×40×4 = 6.25 KB，冗余 ≈75%，576 线程）。**Mali 上要单独做一套参数**，甚至要重新评估分块本身：Arm 明说不要把 global 拷进 shared，而现有实现的全部收益都建立在这个动作上。这也可能正是 Adreno 740（290–350 ms）比 Adreno 829（170–230 ms）慢的原因之外，需要单独关注 Mali 设备的另一个理由。另外把 shared memory 分配按 16 KB 设防，别按 32 KB。

---

## 全部来源

**Qualcomm / Adreno**
- [Adreno GPU on Mobile: Best Practices](https://docs.qualcomm.com/doc/80-78185-2/topic/mobile_best_practices.html)
- [Adreno GPU Best Practices (PC)](https://docs.qualcomm.com/doc/80-78185-2/topic/pc_best_practices.html)
- [Game Developer Guide: GPU](https://docs.qualcomm.com/doc/80-78185-2/topic/gpu.html)
- [chipsandcheese: Inside Snapdragon 8+ Gen 1's iGPU](https://chipsandcheese.com/p/inside-snapdragon-8-gen-1s-igpu-adreno-gets-big)
- [chipsandcheese: Inside Qualcomm's Adreno 530](https://chipsandcheese.com/p/inside-qualcomms-adreno-530-a-small-mobile-igpu)
- [Mesa freedreno: CS shared memory size configurable](https://www.mail-archive.com/mesa-commit@lists.freedesktop.org/msg152334.html)
- [Mesa freedreno_context.c（fd_memory_barrier）](https://raw.githubusercontent.com/chaotic-cx/mesa-mirror/main/src/gallium/drivers/freedreno/freedreno_context.c)

**Arm / Mali**
- [Arm GPU Best Practices Developer Guide 101897 — Compute shading](https://developer.arm.com/documentation/101897/0304/Compute-shading/Workgroup-sizes)
- [Arm GPU Best Practices — Shared memory](https://developer.arm.com/documentation/101897/latest/Compute-shading/Shared-memory)
- [azhirnov/cpu-gpu-arch ARM-Mali_Guide.md](https://github.com/azhirnov/cpu-gpu-arch/blob/main/gpu/ARM-Mali_Guide.md)
- [Arm: Vulkan synchronization on Mali GPUs](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/vulkan-synchronization-how-pipeline-barriers-map-to-mali-gpu-hardware)
- [Arm: Using asynchronous compute on Mali GPUs](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/using-asynchronous-compute-on-arm-mali-gpus)
- [Arm OpenGL ES SDK: Introduction to compute shaders](https://arm-software.github.io/opengl-es-sdk-for-android/compute_intro.html)
- [Mali-G78 Performance Counter Reference](https://arm-software.github.io/libGPUCounters/mali-g78_counter_reference.html)
- [Arm: Benchmarking floating point precision in mobile GPUs (III)](https://developer.arm.com/community/arm-community-blogs/b/mobile-graphics-and-gaming-blog/posts/benchmarking-floating-point-precision-in-mobile-gpus---part-iii)
- [Mali-G720-Immortalis vulkaninfo](https://gist.github.com/nyanmisaka/ce03b5f61cb02389a97094b5172f5bf0)

**Khronos 规范与扩展**
- [EXT_disjoint_timer_query](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/EXT/EXT_disjoint_timer_query.txt)
- [EXT_gpu_shader5](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/EXT/EXT_gpu_shader5.txt)
- [ARB_compute_shader](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/ARB/ARB_compute_shader.txt)
- [ARB_sync](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/ARB/ARB_sync.txt)
- [OES_EGL_image](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/OES/OES_EGL_image.txt) · [OES_EGL_image_external](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/OES/OES_EGL_image_external.txt) · [EXT_EGL_image_storage](https://raw.githubusercontent.com/KhronosGroup/OpenGL-Registry/main/extensions/EXT/EXT_EGL_image_storage.txt)
- [EGL_KHR_fence_sync](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/KHR/EGL_KHR_fence_sync.txt) · [EGL_KHR_image_base](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/KHR/EGL_KHR_image_base.txt)
- [EGL_ANDROID_get_native_client_buffer](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/ANDROID/EGL_ANDROID_get_native_client_buffer.txt) · [EGL_ANDROID_image_native_buffer](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/ANDROID/EGL_ANDROID_image_native_buffer.txt) · [EGL_EXT_image_gl_colorspace](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/extensions/EXT/EGL_EXT_image_gl_colorspace.txt)
- [eglCreateContext refpage](https://raw.githubusercontent.com/KhronosGroup/EGL-Registry/main/sdk/docs/man/html/eglCreateContext.xhtml)
- [GLSL ES 3.20 规范](https://registry.khronos.org/OpenGL/specs/es/3.2/GLSL_ES_Specification_3.20.html) · [GLSL ES 3.10 规范](https://registry.khronos.org/OpenGL/specs/es/3.1/GLSL_ES_Specification_3.10.withchanges.pdf) · [GLSL 4.60 规范](https://registry.khronos.org/OpenGL/specs/gl/GLSLangSpec.4.60.pdf)
- [GLSL spirvmappings.adoc](https://github.com/KhronosGroup/GLSL/blob/main/chapters/spirvmappings.adoc) · [VK_KHR_shader_float_controls](https://docs.vulkan.org/refpages/latest/refpages/source/VK_KHR_shader_float_controls.html)
- [VK_ANDROID_external_memory_android_hardware_buffer](https://raw.githubusercontent.com/KhronosGroup/Vulkan-Docs/main/appendices/VK_ANDROID_external_memory_android_hardware_buffer.adoc)
- [Vulkan-Samples: pipeline_barriers](https://docs.vulkan.org/samples/latest/samples/performance/pipeline_barriers/README.html) · [async_compute](https://raw.githubusercontent.com/KhronosGroup/Vulkan-Samples/main/samples/performance/async_compute/README.adoc)
- [Vulkan 文档：Optimizing for Mobile GPUs](https://vulkan.lunarg.com/doc/view/latest/windows/antora/tutorial/latest/Advanced_Vulkan_Compute/12_Mobile_and_Embedded_Compute/03_mobile_optimization.html)
- [glMemoryBarrier (ES3)](https://docs.gl/es3/glMemoryBarrier)
- [SPIRV-Tools issue #5658](https://github.com/KhronosGroup/SPIRV-Tools/issues/5658)

**Android / AOSP**
- [NDK: Native Hardware Buffer](https://developer.android.com/ndk/reference/group/a-hardware-buffer) · [NDK: Bitmap](https://developer.android.com/ndk/reference/group/bitmap)
- [Migrate scripts to OpenGL ES 3.1](https://developer.android.com/guide/topics/renderscript/migrate/migrate-gles)
- [AOSP: Implement OpenGL ES and EGL](https://source.android.com/docs/core/graphics/implement-opengl-es)
- AOSP 源码：[Bitmap.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/Bitmap.java) · [Bitmap.cpp](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/jni/Bitmap.cpp) · [Graphics.cpp](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/libs/hwui/jni/Graphics.cpp) · [opengl util.cpp](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/jni/android/opengl/util.cpp) · [GLUtils.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/opengl/java/android/opengl/GLUtils.java) · [HardwareBuffer.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/core/java/android/hardware/HardwareBuffer.java) · [EGL15.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/opengl/java/android/opengl/EGL15.java) · [EGLExt.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/opengl/java/android/opengl/EGLExt.java) · [GLES11Ext.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/opengl/java/android/opengl/GLES11Ext.java) · [SurfaceTexture.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/graphics/java/android/graphics/SurfaceTexture.java) · [ImageReader.java](https://raw.githubusercontent.com/aosp-mirror/platform_frameworks_base/master/media/java/android/media/ImageReader.java)

**论文与工程实践**
- [HeteroLLM, arXiv:2501.14794](https://arxiv.org/html/2501.14794v1)（Adreno 750 同步开销 50–100 µs）
- [Measuring and Reducing WebGPU Dispatch Overhead, arXiv:2608.08730](https://arxiv.org/html/2608.08730)
- [Minimizing GPU Kernel Launch Overhead on Mobile GPUs, HotMobile 2021](https://dl.acm.org/doi/10.1145/3446382.3448606)
- [OpenCL Optimization and Best Practices for Adreno GPUs, IWOCL 2018](https://dl.acm.org/doi/pdf/10.1145/3204919.3204935)
- [AN5D, arXiv:2001.01473](https://arxiv.org/pdf/2001.01473) · [Revisiting Temporal Blocking, arXiv:2305.07390](https://arxiv.org/pdf/2305.07390)
- [ꟻLIP: A Difference Evaluator for Alternating Images](https://github.com/NVlabs/flip)（[论文](https://developer.nvidia.com/blog/wp-content/uploads/2020/07/flip-author-version-reduced-file-size.pdf)）
- [Filament PlatformEGLAndroid.cpp](https://raw.githubusercontent.com/google/filament/main/filament/backend/src/opengl/platforms/PlatformEGLAndroid.cpp) · [Filament discussion #7712](https://github.com/google/filament/discussions/7712)
- [llama.cpp PR #28531（Samsung 32KB shared memory）](https://github.com/ggml-org/llama.cpp/pull/28531)
- [ANGLE ExtensionSupport.md](https://chromium.googlesource.com/angle/angle/+/HEAD/doc/ExtensionSupport.md) · [Chromium gpu_driver_bug_list.json](https://raw.githubusercontent.com/chromium/chromium/main/gpu/config/gpu_driver_bug_list.json) · [Mozilla Bug 1264505](https://bugzilla.mozilla.org/show_bug.cgi?id=1264505)
- [Glide: Hardware Bitmaps](https://bumptech.github.io/glide/doc/hardwarebitmaps.html)
- [momentsingraphics: fma](https://momentsingraphics.de/FMA.html)

---

## 明确未找到的项

1. 「每次 dispatch+barrier 固定开销」的厂商公开数字（Qualcomm / Arm 均不发布）。
2. halo 冗余计算 vs barrier 减少的权衡，**针对 Adreno/Mali 的**定量数据（现有文献全部基于桌面 GPU）。
3. GLES 上关闭 fast-math / fma 收缩的扩展或 pragma（Vulkan 有 `VK_KHR_shader_float_controls`，GLES 无等价物）。
4. `eglCreateContext` 是否要求两上下文同 EGLConfig 的规范原文（EGL 1.5 spec 仅 PDF 且 registry 403）；GLES 规范附录「Shared Objects and Multiple Contexts」原文同样未核到。
5. Android 官方对 `glClientWaitSync` 轮询 vs 阻塞的具体建议。
6. 「申请 CPU usage 标志会迫使 gralloc 退回线性布局 / 关闭 AFBC-UBWC」的厂商一手来源（业界常说，但无可引用文档）。
7. 针对现代 Adreno 6xx/7xx 与 Valhall Mali 采样 RGBA8 EGLImage 的一手驱动缺陷报告（现有记录均为 Mali-400 时代）。
8. Adreno 上 `GL_EXT_texture_sRGB_decode` 能否作用于 EGLImage 纹理的官方结论。

**注**：本次会话的 WebSearch 配额（200 次）已用尽，上述第 1、2 项若要继续追，建议改为直接在目标设备上做微基准（空 kernel + barrier 循环，扫 dispatch 数量取斜率），比继续检索更快也更可信。
