# HDR 媒体显示

## 2026-10-16：收起 HDR 调试入口

用户确认此前主要显示问题已解决，并要求撤掉调试入口后提交。常规 debug 不再提供设置中的 HDR 对照页、HDR 标识长按入口或 HDR 回归 instrumentation；查看器专用日志默认停止写入。保留 HDR 标识的单击开关、卓易通 PQ 输出、APP5 兼容，以及已验证的首次显示、缩放和动态照片交接。

诊断页、日志导出和回归源码作为开发资料保留，未注册到正常 APK 的清单中。需要重新调查时，开发者须显式恢复 `HdrDiagnosticsActivity` 和 `HdrViewerRegressionProbe` 的临时清单注册；回归仪器运行期间才打开查看器日志。下文及研究报告中的调试入口说明属于当时的验证记录。返回 SDR 详情的进一步平滑仍为低优先级待办，不增加返回等待。

## 2026-10-16：修正大图首次显示的亮暗反复

用户新录屏显示加载后先亮、再暗、再提亮。真实查看器回归确认原图会在 PQ 就绪前绘制；现由 PQ 首帧完成首次揭示，保留加载指示，并停止同屏原生 Gainmap 绘制。普通 JPEG 用有界头预检跳过不必要的 HDR 复制与解码，失败或超时恢复原图。新首帧回归及原有缩放、动态照片、退出和开关测试通过；卓易通实际观感待用户确认，详见 [大图首次显示验证](viewer-opening-2026-10-16.md)。

## 2026-10-16：动态照片收尾、返回与增益调度

针对用户录屏中的两次黑帧，动态照片播放期间保留同一 PQ 层并准备 SDR 静图，结束在该层恢复增益；返回与暂停时冻结最后一帧，到 `onStop` 再释放。增益过渡改由 EGL 线程推进，减少首帧后等待主线程的时间。相关过程回归、首帧计时修正与验收边界见 [生命周期与延迟验证](viewer-lifecycle-2026-10-16.md)。

## 2026-10-16：首帧与切换体验优化

用户已确认查看器在卓易通可以显示 HDR。本轮处理打开后的等待、黑屏闪烁和亮度突变：补全首帧同步、同层渐变切换、复用已解码图片、减少纹理传输，并限制小图在解码时被额外放大。保留现有 APP5 参考重建和日志导出。详见 [首帧、分辨率与过渡验证](viewer-transitions-2026-10-16.md)。

## 2026-10-16：全屏查看器的卓易通兼容输出

`ImageViewerActivity` 已接入共享的 PQ Surface。默认只在 API 34 及以上、命中碰一碰现有卓易通环境判据时启用，普通 Android 保持系统 HDR。标准 Gainmap 优先，前两张 APP5 原图沿用已确认观感接近图库的参考曲线和自动 4 倍余量。

PhotoView 继续处理图片、点击、双击、缩放和平移；PQ 层读取同一矩阵，不抢占其监听器。当前页切换、进入空间照片或窗口停止显示时释放 PQ 层；动态照片播放覆盖在已准备的 PQ 静图之上，结束直接恢复增益。HDR 开关复用同一 Surface，按 180 ms 渐变重建增益。debug 查看器事件纳入现有对照页的 TXT 分享、下载和另存为。

指定 OPPO PLZ110 `3B1629006YC00000` 已通过 18 项实机断言（调试仪器临时启用 PQ，常规启动未启用），含两张 APP5、标准 Gainmap、双击缩放和平移、开关保留矩阵、GIF、动态照片自动／长按播放、视频、旋转、后台恢复和日志导出。空间照片验证状态切换，不执行模型推理。截图只用于几何检查，不证明卓易通物理显示效果。详见 [查看器接入与验证](viewer-integration-2026-10-16.md)。

## 2026-10-16：卓易通对照诊断（查看器接入前的记录）

APP5 实验版用户回传：模式 6 在自动余量下已接近华为图库，4 倍与 8 倍观感差别不明显。本轮继续核对元数据、两版 SDK 的旧合成内核和像素数值，尚未确定厂商完整公式，保留参考曲线与未校准提示。详见 [真实增益与重建曲线调查](app5-reconstruction-2026-10-16.md)。

当前 debug 迭代增加 APP5 实验适配：标准 Android Gainmap 优先；前两张华为原图可提取真实附加灰度图并送入现有 PQ Surface。界面明确标注曲线未校准，参考公式为 `linearHDR = linearSDR × headroom^(Y'/255)`，余量由原有 2／4／8 倍选项控制（自动仍按 4 倍实验值）。APP5 私有增益参数的语义尚未确定，不能称为精确复现华为图库。模式 1—4 仍展示原生解码结果，模式 7 为基图对照；日志保留解析、元数据、直方图、公式、耗时及 EGL 状态。仅 debug 对照页启用，不修改正式查看器。

用户反馈同一图片在华为系统图库可提亮，卓易通内现有查看器开关 HDR 没有差异。debug 包新增“HDR 对照测试与日志”，入口位于设置的高级设置，也可长按全屏查看器的 HDR 标识。

最新实测：用户在 `202610160644` 确认内置图的 PQ 10 位 Surface（模式 6）颜色正确且提亮；scRGB（模式 5）提亮但偏色。两者均在未声明扩展时成功创建且返回所请求的色彩空间，因此不能继续用能力列表推断所有 HDR 输出不可用。用户随后确认扫描修复版初始化明显加快、Android 可识别的 HDR 图片经 PQ 输出有效。两份华为原文件已检查：双 JPEG、APP5 中的 ITUT35／CUVA 线索、附加灰度图均存在，第一张摘要与导入日志一致；尚需华为增益参数解析适配。详见 [原图与跨设备调查](huawei-originals-2026-10-16.md)。

提供七种显示方式、内置增益图与原图对照、观感备注，以及分享 TXT、保存到下载、另存为。实现和验证边界见 [诊断记录](diagnostics-2026-10-16.md)。此次不更改正式查看器的 HDR 策略。

第三张现场拍摄照片已成功对照：它同时带 Vivid APP8、ISO 21496-1 和 MPF，用户确认 PQ 效果正确。前两张 APP5 文件缺少这套标准增益图信息；因此兼容性需要按实际封装判断，不能统称 HDR Vivid 都不能被 Android 识别。

第二轮探针中，模式 5／6 直接尝试创建 scRGB FP16／PQ 10 位 Surface，缺少扩展不再提前终止；日志区分实际调用、错误、创建与色彩空间查询结果。选图自动检查字节摘要、Ultra HDR XMP、ISO 21496-1、MPF／附加 JPEG、Vivid APP8 线索，并在内存预算允许时进行未缩放解码。原图没有 Android gainmap 时，需用内置图执行 HDR Surface 对照。

Render HDR images at their full brightness in the full-screen image viewer,
auto-detecting the gain map and exposing a tappable HDR badge.

## Status

- **Phase**: implemented and verified on device (2026-06-24).
  - HDR full-screen display: **verified** by the user (boost + badge render;
    gain map survives Glide).
  - Video-player launch crash: **fixed** (root cause was
    `FileProvider.getUriForFile` on an uncovered public path, not
    `startActivity`). Published debug `202606240927`.
- **Scope**: full-screen image viewer only. HDR still images only. Bundled
  side-fix: external video-player launch crash.
- **Follow-up**: some video attachments reference an original public path
  (e.g. `DCIM/Camera/...`) rather than an app-private copy — see
  [followups.md](followups.md).

## Document map

- [plan.md](plan.md) — intent, scope, decisions, approach, risks, acceptance.

## Related global docs

- ADR [0006-hdr-display-scoped-to-fullscreen-image-viewer](../../adr/0006-hdr-display-scoped-to-fullscreen-image-viewer.md)
- `CONTEXT.md` — terms **HDR Media**, **HDR Display**.

## Key facts (from research + code, 2026-06-24)

- App: `minSdk 26`, `target/compileSdk 36`, Glide 4.16.0, PhotoView 2.3.0.
  No Coil / Media3 / ExoPlayer.
- HDR still display requires **API 34+**, a window in `COLOR_MODE_HDR`, and a
  **hardware-accelerated** Canvas drawing a gain-map `Bitmap`. Software Canvas
  drops the gain map.
- Import path (`FileUtil.copyUriToFile` / `copyUriToExistingFile`) is a raw
  byte copy, so the gain map is preserved on disk.
- Home-screen widgets (RemoteViews) cannot show HDR — SDR base only.
- `MediaMetadataRetriever` frames are always SDR — every video thumbnail is SDR.
