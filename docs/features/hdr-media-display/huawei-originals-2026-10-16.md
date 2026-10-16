# 华为原图与 PQ 输出兼容性调查

## 最新结论：同为 HDR Vivid，文件兼容信息不同

用户随后提供当前测试设备刚拍摄的第三张照片，并确认 PQ Surface 提亮和颜色正确。其文件同时包含 Vivid APP8 与标准 ISO 21496-1 元数据／MPF；前两张为 APP5 CUVA 路径且未检出标准增益图元数据。因此问题应精确定位为前两张的 APP5 封装兼容，不能泛称“Android 不支持所有 HDR Vivid 照片”。

| 检查项 | 前两张照片 | 第三张成功对照 |
| --- | --- | --- |
| EXIF 机型 | HUAWEI Pura 70 Ultra | HUAWEI Pura X View |
| 主图尺寸 | 4368×5824 | 3072×4096 |
| 附加图尺寸 | 1536×2048 | 1536×2048 |
| ITUT35 所在段 | 主图／附加图均为 APP5 | 主图／附加图均为 APP8 |
| 标准 ISO 21496-1 标记 | 未检出 | 主图／附加图均有 APP2 标记与版本字段，附加图带参数 |
| MPF | 未检出 | 存在 |
| Adobe HDR XMP | 未检出 | 未检出；ISO 信息可独立供支持的解码器使用 |
| PQ 测试反馈 | 无 Android Gainmap，未进入重建／输出 | 用户确认效果正确 |

第三张文件为 2,242,570 字节，SHA-256 `f8b5b812f0f7f94599527c42e6cadcbbdc83eb3a74fc89372b1a5e42fab50bce`。主 JPEG 结束于 1,410,139，附加 JPEG 从该处开始，长 252,481 字节。主图 APP8 负载为 24 字节，附加图 APP8 为 276 字节；ISO APP2 分别为 32／89 字节。底图 ICC 仍描述 P3 基色与 sRGB 传递函数，成功输出无需照片本身先以 PQ 存储。

[Android 文档的 ISO 兼容章节](https://developer.android.com/media/platform/hdr-image-format#iso-21496-1-compatibility) 明确 Android 15／API 35 起支持 JPEG 的 ISO 21496-1 与 Bitmap 互转。本机兼容环境报告 Android 16／API 36，当前代码要求原生 Gainmap 才进入 PQ 重建。因此最合理的解释是标准 ISO 元数据提供了可解码路径；尚未跟踪系统内部解析分支，不能宣称已经证明系统直接解析了 Vivid APP8。

## 2026-10-16：原文件证据

用户确认 debug `202610160707` 的初始化速度明显改善，并补充：Android 能识别的 HDR 图片通过 PQ Surface 已有效果。最初提供两张华为相机 JPEG 原图，问题是其 HDR 封装兼容性，以及 PQ 是否能适用于其他卓易通设备；以下保存这两张原文件的检查结果。

读取本地完整文件，未依据聊天中的缩放预览判断封装，未改写或上传照片。EXIF 仅检查相机品牌与型号；两张均为 HUAWEI Pura 70 Ultra。此次只做调查与文档更新，不修改应用代码或发布版本。

| 项目 | 照片 1 | 照片 2 |
| --- | --- | --- |
| 文件字节数 | 7,341,609 | 8,364,640 |
| SHA-256 | `e3b6b8a830f2451c835833bba07a33c4263399c82a7a32576ce35926050c8a47` | `fa57c74f25b2bbf3e52aba2fc4ef0cf916b5fd67fe565935a2d98ce986b8a21c` |
| 主 JPEG 尺寸 | 4368×5824 | 4368×5824 |
| 主 JPEG 结束／附加 JPEG 起始偏移 | 7,022,242 | 7,840,368 |
| 附加 JPEG 尺寸 | 1536×2048 | 1536×2048 |
| 附加 JPEG 字节数 | 305,523 | 510,428 |
| 两幅 JPEG 的 APP5 负载长度 | 24／50 字节 | 24／50 字节 |
| APP5 前缀 | 两幅均为 `ITUT35` | 两幅均为 `ITUT35` |
| ICC 描述 | sRGB EOTF with DCI-P3 Color Gamut | sRGB EOTF with DCI-P3 Color Gamut |

两张附加图均可独立按 JPEG 解码，基本为灰度：照片 1 各通道范围 0—169，照片 2 为 0—255。两份文件均未发现标准 Ultra HDR 的 XMP gainmap 标记或 ISO 21496-1 标记。

进一步按字节核对：主图 APP5 负载第 16—19 字节按大端整数读取，分别正好为附加 JPEG 的起始偏移；附加 APP5 第 18—25 字节包含 2048、1536，与实际图像尺寸对应。附加 APP5 在 `ITUT35` 后为 `26 00 04 00 06`，其国家码 `0x26`、提供者码 `0x0004` 对应 HDR Vivid 系标识，参见 [FFmpeg 的 T.35 常量定义](https://ffmpeg.org/doxygen/9.0/itut35_8h_source.html)。这些证据比单纯存在两幅 JPEG 更明确，但不等同于已解释剩余增益参数。

照片 1 的完整摘要与前两轮应用日志一致。就这份用户提供的原文件而言，可以排除导入时的字节改写；此前“尚无独立原文件摘要对照”的待办已完成。

此前扫描器只识别 APP8 中的 `ITUT35`，将 APP5 记为未知私有段，因此旧日志里的“未发现 Vivid APP8”不能解释为排除华为 HDR。当前证据指向华为／CUVA 的双层 HDR 封装；不能把 APP5 直接按 APP8 的字段布局解析，也不能仅凭相似签名断言全部元数据已正确还原。

## 开源实现核对

[OpenHarmony 的 hdr_helper.cpp](https://github.com/openharmony/multimedia_image_framework/blob/master/plugins/common/libs/image/libextplugin/src/hdr/hdr_helper.cpp) 明确区分 APP8 的 `HDR_VIVID_DUAL` 与 APP5 的 `HDR_CUVA` 路径。后者动态加载 `libimage_cuva_parser.z.so`，调用 `GetCuvaGainMapOffset` 处理增益图位置；当前文件虽然保留 `GetCuvaGainMapMetadataT` 类型声明，但没有对应的调用实现，不能据此声称已取得 CUVA 增益参数算法。公开的 APP8 解析函数也不能直接套用到 APP5。

[Android Ultra HDR 格式](https://developer.android.com/media/platform/hdr-image-format) 另行定义增益图封装及重建参数。本例缺少已知标准标记，当前卓易通中的 Android 预览解码也没有生成 Gainmap，因此主要障碍是格式适配。厂商可以扩展 Android 解码器，不能把本机结果表述为任何 Android 都无法识别华为 HDR。

## PQ 与设备能力

[Khronos EGL BT.2020 扩展](https://registry.khronos.org/EGL/extensions/EXT/EGL_EXT_gl_colorspace_bt2020_linear.txt) 规定：应用声明帧缓冲的 BT.2020 基色及 PQ 传递函数，像素的 PQ 编码由应用完成。这里描述的是输入数据格式，不是屏幕的原生色域。两张原图的底图采用 P3 基色和 sRGB 传递函数，也可以在重建 HDR 后转换为 BT.2020／PQ 输出。

[Android 色彩管理文档](https://source.android.com/docs/core/display/color-mgmt) 将输入色彩空间、合成转换与显示硬件区分处理。因此本机 PQ 成功不能解释成“这块屏幕恰好是 PQ 空间”；更准确的结论是，此版本卓易通、图形驱动和宿主显示链路能正确处理本次 PQ 输出。

- 跨设备仍依赖 EGL 配置、10 位缓冲、色彩空间传递、宿主 HDR 合成和实际显示能力。本次缺少扩展声明却成功，说明声明不能作为该环境唯一的拦截条件；也不能由此忽略其他设备创建失败或颜色错误。
- 实际创建、格式查询和提交成功只能验证软件接口；是否高光提亮、颜色准确，仍需内置对照图及代表性设备实测。失败时保留 SDR 回退，不把静默接受当成物理 HDR 输出已验证。
- 当前探针使用 SDR 参考白 203 nit、自动选项按 4 倍重建，这是实验参数。不能将其当作所有设备的实际屏幕余量；正式方案仍需处理亮度适配及不可用能力 API。

## 兼容实现方向

复用现有 PQ 输出，在其前面增加华为 APP5 解码适配：解析并验证主图／附加图位置及参数，按正确的传递函数、色域、旋转和尺寸关系还原 HDR，再编码为 BT.2020／PQ。若文件参数可以完整映射到 Android Gainmap，也可构造内存中的 Gainmap 后复用现有重建代码，无需改写原图。

目前附加图与元数据均在，且图像可以独立解码，存在明确的实现路径。尚未验证的是 APP5 的完整重建公式和相机原始效果的一致性；不能承诺只补签名识别就能正确显示，也不能为缺失语义随意指定固定增益。后续应先在 debug 页验证两张原图，与华为图库对照高光、颜色、暗部，再考虑正式查看器集成。

## 当前待核实边界

### 后续实现调查：本地 SDK 与实验边界

用户随后授权实现 APP5 调试分支并发布。只读检查已安装 HarmonyOS 7 SDK 的 `libimage_cuva_parser.z.so`，未连接或启动设备／模拟器，也不将厂商二进制打入 APK。其定位函数对主 APP5 的 fileType=2 使用负载第 20 字节的大端偏移加主图所有 APP 段长度及 SOI 的 2 字节；fileType=1 则使用第 16 字节的绝对偏移。两张原图的 APP 段总长分别为 295,038／288,039 字节，增加 SOI 后两种偏移记录精确吻合。元数据函数将附加 APP5 的第 18—49 字节转换为八个 32 位值，没有在该库提供重建算法或字段语义。

公开 OpenHarmony 的 `VpeUtils::SetSurfaceBufferInfo` 对 HDR_CUVA 提前返回，不传入通用增益元数据；不能以通用 ISO 默认值推断旧 APP5 的私有曲线。新找到的 CN122780116A 描述带 ISO URN 的增益图，与这两份不含 ISO 的旧 APP5 文件不匹配，也不能作为其曲线依据。

本轮测试版明确标记参考曲线未校准，读取真实附加图并记录几何、原始参数块、采样值、所选余量与公式。该实验能验证 APP5 提取、对齐、色彩处理和 PQ 提交，但准确复现华为图库仍需进一步确定曲线。

- 核对 APP5 的字段布局、数值含义及增益重建算法；仅提取灰度图和将亮度乘以固定倍数，不能算正确兼容。
- 将文件解码与输出能力分别处理：原图没有 Android Gainmap 时，当前测试页停在 HDR 重建之前，尚未用其真实增强内容尝试 PQ 输出。
- PQ 是输入像素的传递函数，BT.2020 是本次 Surface 的基色定义；物理屏幕的原生色域和最大亮度是不同概念。当前设备成功不能外推为所有卓易通版本／设备均成功。
