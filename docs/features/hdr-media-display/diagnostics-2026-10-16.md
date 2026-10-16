# 卓易通 HDR 对照诊断

## 已知事实与范围

- 用户确认同一原图在华为系统图库中能看到 HDR 高光，在卓易通中的完事儿切换 HDR 没有观感差异。
- 2026-10-15 只读调研未查到针对卓易通 Ultra HDR 静态图片的已验证修复。窗口 COLOR_MODE_HDR 只是请求；gainmap 存在不等于屏幕已提亮。
- 2026-10-16 用户授权增加可操作的对照页、直接导出日志并发布 debug 包；不连接设备、不自动安装、不提交 Git。
- 从 b8876a3af 建立独立工作树，避免把当前工作区其他未提交功能混入发布。

## 对照设计

- 仅 debug 包提供入口：设置的高级设置，以及全屏看图时长按 HDR 标识。
- 支持内置已知增益图测试图、选择图片，以及从查看器带入原图；原图只在本机诊断目录使用，不随日志导出。
- 对照包括 SDR、ImageView HDR、Glide + PhotoView HDR、窗口创建时请求 HDR，以及独立 Surface 的 scRGB FP16、BT.2020 PQ 10 位与 SDR 输出。
- 独立 Surface 按 Android Gainmap 文档应用增益图，明确记录实际 EGL 格式、色彩空间、扩展、错误及增益参数。创建失败直接报告，不伪装成 HDR 成功或静默改为 SDR。
- 记录显示能力、HDR/SDR 比例是否可用、窗口请求、硬件加速、亮度/电源状态、图片元数据、场景切换和用户观感。API 返回值不作为最终屏幕效果证明。
- 持久化 UTF-8 日志，支持系统分享、直接保存到 Downloads，以及系统另存为。无需 ADB 或复现后及时获取 logcat。

## 依据

- https://developer.android.com/media/grow/ultra-hdr/display
- https://developer.android.com/reference/android/graphics/Gainmap
- https://developer.android.com/reference/android/view/Window#setColorMode(int)
- https://developer.android.com/reference/android/view/SurfaceView#setDesiredHdrHeadroom(float)
- https://developer.android.com/reference/android/view/Display#getHdrSdrRatio()

## 验证边界

本地验证覆盖增益计算、日志持久化/导出快照、构建和发布产物；最终 HDR 观感由用户在卓易通实测。截图通常经过 SDR 映射，不作为 HDR 高光生效证明。

## 实现与检查记录

- 已实现 7 种对照、内置三行增益测试图、原图选择与查看器带入、亮度余量选择、观感记录和备注。
- 日志为独立 UTF-8 TXT，持续追加到应用私有目录；窗口重建、进程退出后重新进入继续使用同一记录。导出快照采用跨实例锁，避免渲染线程与生命周期记录交错。
- Android 34 以下仍提供环境日志导出；缺失 HDR 扩展或无法处理 gainmap 方向/存储格式时明确记录失败。未根据 `Display.isHdr` 阻止独立 Surface 尝试。
- 图片解码限制长边 1280，导入限制 80 MB；保留当前原图供重建恢复，替换图片后清理旧副本。导出只复制事件日志。
- 独立 Surface 的自动选项使用固定 4 倍进行增益重建，窗口请求仍为自动；PQ 使用 203 nit 参考白。两者均在界面/日志标为探针假设，不声称是设备测量值。
- 最终 `assembleDebug` 与 7 项专项 JVM 测试通过。实际 GLSL 的 6 组桌面离屏数值输出通过，覆盖超过 SDR 白的浮点值、部分增益、PQ、SDR、方向、alpha，以及增益前后基色转换与负值保留；桌面验证不代替设备 HDR 实测。
- 构建期间主工作区提交历史更新为 17c5567c4，经比对其文件树与独立工作树基线 b8876a3af 完全相同，没有丢失已提交功能。
- 已发布 debug `202610160527`；公开元数据和说明与本地产物一致，远端完整 APK SHA-256 为 `bb00b2814711538e95b414f31d4b37b98e99897fde12413f585c2db3db1db013`，与本地一致。
- 随后依据 Android SDK 源码发现，自定义 DoubleUnaryOperator RGB 没有原生 ICC 参数，不能用于 Bitmap / Canvas。改为系统 LINEAR_EXTENDED_SRGB 浮点位图，两个基色转换矩阵在 GLSL 中执行。首包需由此次重新发布的包替代，避免将应用准备失败误判为宿主限制。
- 最终发布版本为 `202610160532`，APK 大小 30,760,777 字节，SHA-256 为 `cd923d00c2ec70a360438bfa82e9ac7a61fac554548b5348dec4a1711ffedef8`。公开元数据、中文发布说明、本地及远端完整 APK 校验一致，APK 签名验证通过。

## 用户回传日志：2026-10-16 14:14—14:18

来源为用户提供的 `hdr-1792131261207-82c76111-1792131496267.txt`，SHA-256 为 `4393958d6e205a69553571545d8861022edb629a7d69c3b78277e27d9f563020`。用户补充：后选 HDR 原图的观感与内置图相同。测试版本为 `202610160532`，运行环境报告 HUAWEI VOL-AL00、Android 16／API 36；这不等同于宿主鸿蒙版本。

### 已确认事实

| 检查项 | 日志证据 | 判断 |
| --- | --- | --- |
| Android 显示能力 | `isHdr=false`、`supportedHdrTypes=[]`、`wideColorGamut=false` | 当前兼容环境没有向应用声明 HDR／宽色域显示支持，不能据此否定物理屏幕能力 |
| HDR 比例 | `hdrSdrRatioAvailable=false`，比例与最高比例均返回 1 | 这是 API 不可用时的默认值，不是测得屏幕只能达到 1 倍 |
| 内置图解码 | 原生与 Glide 均 `hasGainmap=true`，ratioMax 为 4，元数据一致 | 已知 Ultra HDR 内容在当前解码路径中完整保留 |
| 原生／Glide／提前请求窗口 | 三种模式均记录“没有变化”；HDR 模式请求值为 2，绘制后硬件加速为 true | 现象不只发生在 PhotoView，也不能仅用请求 HDR 的时机解释 |
| scRGB Surface | 缺少 `EGL_EXT_gl_colorspace_scrgb_linear` | 探针在扩展预检处主动停止，尚未实际尝试创建该 HDR Surface |
| PQ Surface | 缺少 `EGL_EXT_gl_colorspace_bt2020_pq` | 同样在扩展预检处主动停止，不能写成驱动已经返回创建失败 |
| SDR Surface | RGBA 8/8/8/8，多次成功提交；HUAWEI Maleoon 935F，OpenGL ES 3.2 | 基本 Surface／GLSL 绘制可以运行 |
| 亮度与电源 | 自动亮度，系统值 128；省电关闭、热状态 0；余量始终选择自动 | 此轮未测试手动请求 2／4／8 倍余量 |

内置图的 6 条观感记录集中在 14:14:56—14:15:34。后选原图没有再点击观感按钮，其主观结果以用户消息补充为准。

### 后选原图的独立问题

14:17:45 系统选图得到 JPEG，编码尺寸 4368×5824、Display P3、7,341,609 字节，SHA-256 为 `e3b6b8a830f2451c835833bba07a33c4263399c82a7a32576ce35926050c8a47`。原生解码为 960×1280、Glide 解码为 1280×1707，二者均 `hasGainmap=false`。这次两个 HDR Surface 模式停在“没有 Android 可解码的 gainmap”，比 EGL 扩展检查更早。

因此“观感一样”不代表失败位置完全一样。暂不能区分：选图提供的文件经过转换、图片采用当前 Android 解码器不识别的 HDR 封装、或解码过程中未保留增益信息。Display P3 只说明色域，不能单独证明存在 HDR 增益图；也不能据此否定用户在华为图库看到的 HDR 效果。下一步若检查该图片，应比较真正原文件的字节摘要、JPEG 元数据及未缩放解码结果。

### 首轮结论与边界（能力限制判断已被下述第二轮实测修正）

- 现有证据强烈指向卓易通所呈现的 Android 显示环境未提供标准 HDR 输出能力。此前同一原图在华为原生图库能提亮，与这个判断相容。
- 已经排除“所有图片都在 Glide 中丢失 gainmap”这一解释；不能据此认定后选原图的解码与导入完全无问题。
- 不能宣称所有绕行方案均已排除：探针没有绕过扩展预检直接创建 HDR Surface，也没有测试其他输出接口。
- 暂无证据支持通过反复切换窗口 HDR 标志即可解决。若继续实验，优先做受控的直接创建测试并记录真实 EGL 错误／实际色彩空间，同时检查原图元数据；成功创建仍须由用户确认实际高光。
- 另发现诊断页的 Glide `override(1280,1280)` 不保证长边最多 1280，内置图实际被解码为 1920×1280。后续可对齐缩放策略；这不改变内置图 gainmap 已保留的证据。

参考核对：Android [窗口颜色模式](https://developer.android.com/reference/android/view/Window#setColorMode(int)) 明确仅为请求；[HDR 比例 API](https://developer.android.com/reference/android/view/Display#getHdrSdrRatio()) 在不可用时固定返回 1；Khronos [scRGB 扩展](https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_gl_colorspace_scrgb_linear.txt) 与 [BT.2020 扩展](https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_gl_colorspace_bt2020_linear.txt) 分别定义本次缺失的输出色彩空间能力。

## 2026-10-16：第二轮诊断实验（已收到设备反馈）

用户授权绕过扩展预检直接创建 HDR Surface、加入原文件元数据检查并发布 debug。继续使用已有独立工作树，主工作区的无关改动不进入此次构建。

- 扩展声明仅作记录；保留 scRGB FP16／PQ 10 位的精确配置和色彩空间校验，不静默回退 SDR。每次 EGL 调用立即读取错误，区分配置选择、窗口创建、查询和提交阶段。
- 对选图提供的字节执行有界 JPEG 元数据检查，包含 XMP、ISO 21496-1、MPF 和附加 JPEG；摘要不等同于证明系统选图前的文件没有被转换。不得导出 EXIF、GPS 或完整 XML。
- 增加受内存预算保护的未缩放解码对照，并修正 Glide 的缩放上限。设备输出效果仍待用户验证。

### 第二轮实现与验证

- 已移除扩展列表的拦截。`EGL_EXTENSION_PREFLIGHT_BYPASSED` 记录声明缺失；`EGL_CALL_ATTEMPT`／`EGL_CALL_RESULT` 记录配置、上下文、窗口 Surface 与提交的即时错误。`EGL_FAILED` 带失败阶段及 `surfaceCreationAttempted`，区分尚未到创建步骤与实际创建失败。查询实际色彩空间后仍严格核对，不将 SDR 当成 HDR 成功。
- `IMAGE_RAW_METADATA` 在解码前记录完整字节摘要、有界 JPEG 段扫描、标准／扩展 XMP 白名单、ISO 21496-1 版本、两种字节序 MPF 及其偏移核对、附加 JPEG 与 Vivid APP8 的 ITUT35 头。APP5 仅记存在；扫描不完整、未知私有格式或非 JPEG 均不推出“不是 HDR”。
- `IMAGE_FULL_SIZE_DECODE` 在每次新选图时自动执行无 targetSize 的软件解码；最高 3600 万像素，按 12 字节／像素估算并保留 64 MiB 余量，预算最高 384 MiB。成功后及时释放完整位图，失败或内存不足均显式记录；小图由本来就未缩放的预览检查覆盖。恢复页面不重复大图解码。
- 系统选图只记录 provider、MIME 和字节复制边界，不记录 URI 路径或名称。元数据日志不含完整 EXIF、GPS、XML 或照片字节；HDR 字段只输出受限数字／布尔值／容器角色。
- Glide 使用 AT_MOST，防止测试页图片被放大、长边超过预览上限；此策略不保证与 ImageDecoder 的具体采样尺寸完全相同，实际尺寸仍逐项记录。
- 16 项 JVM 专项测试通过，包括 9 项新检查：附加 JPEG 中的 XMP、隐私字段排除、ISO／Vivid 标记、分段 XMP 重组和超限、MPF 双字节序、非 JPEG／普通 JPEG、损坏文件、XML 外部实体阻止与内存预算。6 组现有实际 GLSL 桌面 GPU 数值检查通过。未运行 Android／卓易通设备测试。

格式依据：[Android Ultra HDR v1.1](https://developer.android.com/media/platform/hdr-image-format)、[Google libultrahdr 编码器](https://github.com/google/libultrahdr/blob/main/lib/src/jpegr.cpp)、[OpenHarmony hdr_helper.cpp](https://github.com/openharmony/multimedia_image_framework/blob/master/plugins/common/libs/image/libextplugin/src/hdr/hdr_helper.cpp)。Vivid APP8 识别参考最后一项开源实现，只作为封装线索，不声称完成厂商 HDR 解码。

### 第二轮发布核对

已发布 debug `202610160644`，APK 为 30,760,777 字节，SHA-256 为 `800e5cb2c63e5959fee6a7e948d9a8009064c53a555bbfe8ae8a2fbc9423e322`。发布流程中的 16 项 JVM 测试通过；公开元数据与中文发布说明一致，远端完整 APK 摘要匹配，本地 APK v2 签名验证通过，嵌入的 debug 版本及本轮 EGL／元数据诊断代码均已核对。待用户在卓易通用内置图测试模式 5／6／7，并重新选择原图后导出日志。

## 用户第二轮回传：2026-10-16 14:51—14:58

日志 `hdr-1792131261207-82c76111-1792133939864.txt`，SHA-256 为 `6e3042a9066420870d30a7d55904ef5a507a2015b37dee6109c24cd647118d00`；包含首轮历史，本次分析仅取版本 `202610160644` 对应的新事件。用户反馈：内置图模式 5／6 都提亮，5 偏色、6 颜色正确；原图初始化很慢并提示没有 Android gainmap。

### 输出能力的新证据

- scRGB：配置 23，RGBA 16/16/16/16 浮点；创建、绑定、查询和提交均 EGL_SUCCESS，实际色彩空间 `0x3350` 与请求一致。
- PQ：配置 25，RGBA 10/10/10/2；相同步骤成功，实际色彩空间 `0x3340` 与请求一致。
- 两种扩展仍未声明，Display.isHdr=false、supportedHdrTypes=[]；但用户已确认 PQ 提亮且颜色正确。此证据推翻“未声明能力就无法走标准 HDR Surface”的推断。可确认本机本次测试可行，不能外推至所有图片或所有卓易通版本。
- 用户在 14:53:27、14:53:35 分别记录高光明显提亮；SDR 模式 7 记录没有变化。scRGB 偏色细节来自用户消息，原因尚待查。此次余量仍为自动（Surface 按 4 倍重建），不是 API 报告的实际屏幕余量。

### 原文件兼容性

- SHA-256 仍为 `e3b6b8a830f2451c835833bba07a33c4263399c82a7a32576ce35926050c8a47`，7,341,609 字节，与上一轮收到的文件相同。
- 主 JPEG 为 4368×5824、8 位、3 通道，结束于偏移 7,022,242；附加 JPEG 从该偏移开始，为 1536×2048、305,523 字节，结束于 7,327,765。两幅图分别有 24 字节、50 字节 APP5。
- 没有检出标准 Ultra HDR XMP、ISO 21496-1、MPF 或已知 Vivid APP8 签名。只有双 JPEG／APP5 结构不足以认定第二张图就是增益图，不能断言具体为 CUVA／Vivid；需要原文件检查其私有字段。
- 选图 provider 为 `com.android.externalstorage.documents`。两次导入字节相同，不支持“本轮新增诊断改变了原图”的解释，但尚不能证明手机原文件在进入卓易通前未经过转换。
- 14:55:21 未缩放解码因估算 305,270,784 字节大于预算 270,255,128 字节而跳过；不能声称本轮完整解码已证实没有 gainmap。预览仍为 960×1280、Display P3、hasGainmap=false。HDR Surface 因缺少增益内容主动停止，原图片本身已成功读入。

### 初始化缓慢的定位与修复

- 重新选图 14:54:18.483 → 元数据完成 14:55:21.620，约 63.137 秒；之后预览完成只需约 0.18 秒。窗口重建且无需复制、无需完整解码时，元数据仍约 61.672 秒和 63.500 秒，随后预览约 0.17—0.22 秒。
- 主要嫌疑为元数据扫描；文件复制无法解释恢复时同样缓慢，完整尺寸解码实际被跳过。代码发现扫描每个压缩字节都会执行 `file.length()`，反复访问文件系统。
- 先增加 7 MB 双 JPEG 回归，以 File 子类计数真实解析路径的大小查询；原实现失败，记录 7,000,011 次调用。随后将私有输入大小缓存一次，回归通过，且附加 JPEG／XMP 识别保持正确。
- 新增复制完成、检查开始、hash／scan／total 毫秒日志，以便用户复测实际改善。无 gainmap 时界面明确“图片已读入，当前解码器未识别 gainmap”，单独记录未执行 Surface，避免误解为原图片无法打开。
- 17 项 JVM 专项与 assembleDebug 通过；新大文件回归约 0.155 秒（桌面 JVM，并非卓易通实测）。输出渲染代码本次未更改。原图片的私有 HDR 兼容与 scRGB 偏色仍未解决。

### 扫描修复发布

debug `202610160707` 已发布，说明 `debug-updates/update-20261016150617.md`。APK 为 30,760,777 字节，SHA-256 为 `cc859c36012746c85f1a4b7fb196cd146ef84f2cf708bf11f72c5b6f601cee80`；公开元数据、中文说明、本地和远端完整 APK 一致，签名验证及嵌入版本核对通过。待用户复测加载速度；私有格式和 scRGB 偏色尚未修复。
