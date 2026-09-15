# 华为 Maleoon 910 上粒子动画全部回退：顶点着色器 SSBO 数为 0（2026-09-15）

## 现象

用户报告：弹窗出现/消散的粒子动画在华为平板上完全播放不了，"似乎自动回退到了
默认的、最简单的动画"。记事卡片、新建记事等所有入口一并失效。

## 设备

| 项 | 值 |
|---|---|
| 型号 | PCE-W30（MatePad Pro 13.2），序列号 5JB0224328002836 |
| SoC | kirin9000s |
| GPU | HUAWEI Maleoon 910，OpenGL ES 3.2 |
| 系统 | Android 12 / API 31 |

Maleoon 是 Mali 血统：扩展串里有 `GL_ARM_rgba8`、`GL_ARM_mali_shader_binary`、
`GL_ARM_mali_program_binary`、`GL_ARM_shader_framebuffer_fetch_depth_stencil`。

## 根因

驱动在链接 `material.vert` + `material.frag` 时直接给出原文：

```
material.vert, material.frag:  The number of vertex shader storage blocks (3)
is greater than the maximum number allowed (0).
```

`material.vert` 声明了三个 SSBO：

```glsl
layout(std430,binding=0) readonly buffer A {Material particles[];};
layout(std430,binding=1) readonly buffer B {State state[];};
layout(std430,binding=2) readonly buffer C {float pigmentation[];};
```

而该设备 `GL_MAX_VERTEX_SHADER_STORAGE_BLOCKS = 0`。**这完全符合规范**：GLES 3.1
与 3.2 对这一项的最小值都是 0，允许实现完全不支持顶点着色器 SSBO。Mali 系历来报 0。

## 失效链路

`preparePipeline` 抛 `IllegalStateException`
→ `ParticleSession.fail()` 打 "粒子动画回退"
→ `ParticleDismissOverlay.onSessionFinished(false)`
→ `fallbackFadeOut()`：动画层整体淡出。

这就是用户看到的"最简单的动画"。三处入口共用同一个渲染器，所以一起失效；启动时的
`ParticleMicroflakeGpuWarmup` 也在同一行抛出，日志里表现为"材料预热未完成"。

## 完整能力画像（本机实测）

```
ssbo顶点=0  ssbo片元=8  ssbo计算=8  ssbo绑定点=32  输出资源=20
shared=32768  工作组线程=1024  顶点属性=16  顶点纹理=16  纹理缓冲=268435456
```

除顶点 SSBO 外无一项吃紧，事前担心的几项都已排除：

- `MAX_SHADER_STORAGE_BUFFER_BINDINGS = 32`，压力着色器用到的 `binding=7` 没问题；
- `shared = 32 KB`，够 `pressure-batched-large.comp` 的 BAND=16；
- RGBA16F 作 color attachment 完整（`EXT_color_buffer_half_float` / `EXT_color_buffer_float` 都在），
  预热里 `Shared.ensure(64,64)` 成功即为证；
- 上下文拿到的是 GLES 3.2，版本检查通过。

## 为什么此前没发现

开发用的两台真机 SM-S9180（Adreno 740）与 OPD2515（Adreno 829）都是高通，Adreno
这一项报 24。整套粒子 GL 代码从未在非 Adreno GPU 上运行过。同类教训见
`.claude/rules` 邻域的 GLSL 跨阶段精度问题（2026-08-16，同一台 PCE-W30）。

## 修法

两条都不需要改动缓冲区的内容与布局，compute 一侧一个字不用动。

### A. 实例化顶点属性（已采用）

同一个 buffer 对象既作 SSBO（compute 写）又作 `GL_ARRAY_BUFFER`（顶点读），
`glVertexAttribPointer` + `glVertexAttribDivisor(1)`：

| 数据 | std430 布局 | 属性数 |
|---|---|---|
| `Material` | `vec4 src / physical / random`，stride 48 | 3 |
| `State` | `vec4 pos / vel`，stride 32 | 2 |
| `pigmentation` | `float`，stride 4 | 1 |

共 6 个，设备给 16。纯 GLES 3.0 核心功能，不依赖任何扩展，零数据拷贝。
改动面：`material.vert` 的三处 buffer 声明改为 `in` 变量，`render()` 里三行
`glBindBufferBase` 换成属性指针设置；`drawReverseFrame` / `drawGestureFrame` 传入的
`stateBuffer` 可变，每次绘制要重设第 4、5 号属性。

`resolve` 用同一个 VAO 且只靠 `gl_VertexID` 取顶点，绘制前需把这些属性数组关掉。

### B. 纹理缓冲（TBO，未采用）

`glTexBuffer(GL_TEXTURE_BUFFER, GL_RGBA32F, buffer)` 绑成 `samplerBuffer`，
shader 里改 `texelFetch`。同样零拷贝，shader 改动更小，但依赖 GLES 3.2 /
`EXT_texture_buffer`，兼容面不如 A。

## 验证结果（PCE-W30 实测）

| 路径 | 结果 |
|---|---|
| 启动预热 | 不再抛异常（此前每次启动必现"材料预热未完成"） |
| 出现 | `particles=159238 frames=33 elapsedMs=550 p90Ms=16.67 maxMs=33.33` |
| 消散 | `count=106461 frames=30 elapsedMs=1016.7 p90Ms=50.0 maxMs=50.0` |
| 左滑手势 | `粒子 Surface 就绪` + `首帧上屏 requestToVisibleMs=160.3`，无异常 |

手势路径按设计不打 `logCompletion`（`if (completed && !isGesture)`），判据取"首帧确认上屏
且无回退异常"。

**消散约 30 fps，出现稳定 60 fps。** 消散要实时积分加压力求解，比回放历史的出现重得多。
尚无 Adreno 同口径对照，是否需要按 Mali 调档待定。

## 尚未做的

1. **Adreno 回归**（SM-S9180 / OPD2515）：用户自行安排。本项目 GPU 输出有本底噪声，
   逐位对照须先做同路径自比较再比 A/B。
2. 其他 Mali 设备（联发科天玑、部分三星 Exynos）的实测确认。

## 诊断入口

`ParticleMicroflakeRenderer.logCapabilities()`，debug-only、进程内打一次，由
`ParticleGl.ensureContext()` 与 `ParticleMicroflakeGpuWarmup.run()` 各调一次，
输出驱动身份与本管线依赖的全部上限。
