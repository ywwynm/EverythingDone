# HDR 显示后续事项

## 2026-10-16：返回 SDR 详情时的亮度平滑（低优先级）

- 用户在 debug `202610161106` 后确认“前面没啥问题”，剩余 HDR 照片返回 SDR 详情时仍感到亮度突变，并说明不是关键问题。本次核对代码及官方资料，未实施应用改动或重新发布。
- 当前 `finish`／`onPause` 冻结 HDR 最后一帧，至 `onStop` 才释放；该策略修复提前撤层的黑帧，但没有逐渐退出 HDR 的过程。照片高光消失与宿主恢复 SDR 白亮度可能共同造成新的感受，尚无本次物理亮度测量，不能确定各自占比。
- 用户补充：卓易通会直接关闭 Activity 动画。因此撤回“与系统退出动画重叠”的前提，后续方案必须按无 Activity 转场设计；此为用户对测试环境的实测反馈，不外推到所有版本。
- 修正后的候选方案：主动返回时，先在当前 Activity 内保留同一 PQ 层，用已有 EGL 工作线程按经过时间将增益在约 120 ms 内降至 0，完成后再调用 `finish()`。该逐帧绘制不依赖 Activity 动画，但会真实增加约 120 ms 的返回等待；时长为待验证起点，不是已确认效果。应有超时兜底并防止重复返回，不修改其它暂停场景的冻结保护。
- 如果要求立即关闭 Activity，则当前应用无法保证同时平滑跨越两个窗口的亮度；给详情页加蒙层或继续保留 HDR 不能可靠替代宿主亮度控制。鉴于这是非关键问题，先保留上述取舍，不直接增加返回等待。
- 在 API 35 及以上同时评估 `SurfaceView.setDesiredHdrHeadroom`，向 1（无 HDR 余量）收敛；官方明确支持用它做 HDR 进入／退出动画，且独立于 Window 的颜色模式。自动值 0 不是可线性插值的实际余量；应使用可靠的当前余量或经实测的请求策略，不把当前内容的 4 倍重建上限当作屏幕实际余量。
- 仅降低像素增益不会改变 PQ 图层的 HDR 属性，宿主仍可能在撤层时调整亮度。卓易通之前 `isHdrSdrRatioAvailable=false`，API 返回 1 是缺省值，不能当作当前实测余量；该环境是否执行 headroom 请求仍待验证。若忽略请求，只能承诺尝试缓和画面变化，不能保证消除整屏变化。
- 若后续实现，范围为兼容查看器主动返回；继续保留同层输出和退出无黑帧，覆盖左上角返回、系统返回、连续返回及取消手势等流程。当前列为可选优化，不扩大 HDR 到详情页，也不接管系统屏幕亮度。
- 依据：[SurfaceView.setDesiredHdrHeadroom](https://developer.android.com/reference/android/view/SurfaceView#setDesiredHdrHeadroom(float))、[Android 混合 SDR／HDR 合成](https://source.android.com/docs/core/display/mixed-sdr-hdr)。

## 2026-10-16：新大图首帧交接验收

- 用户新录屏仍有加载后先亮、再暗、再渐亮。本轮从首帧开始统一 PQ 输出，并回归大图、强制 SDR、超时、缩放和动态照片；等待用户在同一卓易通设备／同一照片复测。若仍有突变，结合新的 `VIEWER_FIRST_FRAME_WAIT/REVEALED` 和 EGL 事件区分应用交接与宿主显示响应，不再只据加载圈消失推断准备完成。

## 2026-10-16：过渡优化后的验收

- 用户已确认普通查看器的卓易通 HDR 有效，并提供动态照片收尾、返回黑帧录像。本轮已修正这两处应用侧撤层和重建，并把增益过渡移至 EGL 线程；指定 OPPO 的过程回归通过，仍需在卓易通复测物理亮度和闪烁。
- 本机 APP5 首次打开仍可能比暖启动慢。新增 `VIEWER_DATA_READY`、`EGL_FIRST_GAIN_FRAME`、`EGL_FULL_GAIN_FRAME` 等分段日志；若仍有等待，先区分附加图准备、主线程排队、EGL 提交与宿主合成。不能凭原图像素数或仅 PQ 首帧就绪推断整体响应。

## 2026-10-16：用户调整优先级

- APP5 真实参数和厂商完整曲线研究按用户要求暂停，保留现有证据与未校准边界；当前转为接入全屏查看器并验证交互，不继续为曲线研究要求额外设备数据。

## 2026-10-16：卓易通回传日志后的待办

- APP5 实验版 `202610160756` 回传：用户表示模式 6 在自动余量下观感已与华为图库差不多，4 倍与 8 倍差异不明显。继续核实真实增益参数、重建曲线和元数据还原，不能将主观接近作为参考曲线正确性的证明；数值输出与系统显示映射需要分开检查。调查记录见 `app5-reconstruction-2026-10-16.md`。

- APP5 调试分支已实现严格容器定位、附加图提取、EXIF 对齐、原始灰度纹理和 PQ 实验输出；两张本地原图通过解析回放。**完整厂商重建曲线仍未确定**：本版使用明确标注的参考增益曲线，未知私有字段不参与重建，不能将亮度测试成功视为兼容完成。待用户在模式 6 的 2／4／8 倍、模式 7 及华为图库间比较高光、颜色、暗部，回传日志；标准 ISO 照片继续作为成功对照。

- 最新回传已证实内置图的直接 PQ Surface 可创建且用户确认颜色正确、高光提亮；scRGB 也创建并提亮，但有偏色。优先以 PQ 研究后续查看器兼容；scRGB 偏色暂不下原因结论。
- 两份华为原文件已收到并检查：4368×5824 主图与 1536×2048 附加灰度 JPEG 的 APP5 均有 ITUT35，附加数据具有 CUVA／HDR Vivid 系标识，主图记录的偏移准确指向附加图。定位已实现，仍需核实厂商增益参数重建，不能直接套 APP8 算法或将参考曲线当成标准；详见 `huawei-originals-2026-10-16.md`。
- 第三张测试设备现场照片具有 Vivid APP8＋ISO 21496-1＋MPF，用户确认 PQ 正常。保留它作为标准兼容成功对照，后续适配范围聚焦前两张 APP5 格式；不将品牌或 HDR Vivid 名称作为统一失败条件。
- 原文件元数据检查约 61—63 秒，预览解码约 0.17—0.22 秒；完整尺寸解码因预算不足跳过。已用 7 MB 回归重现每字节调用 File.length()（7,000,011 次），修复为一次查询并新增分段耗时日志；用户已确认新版速度明显改善。
- 用户确认 Android 能识别的 HDR 图片经 PQ 输出有效；跨设备仍需实际配置与色彩空间校验、代表性设备观感测试及 SDR 回退。探针的 203 nit 参考白和自动 4 倍重建尚不是通用亮度适配策略。
- 用户确认华为系统图库可以显示同一原图的 HDR 高光；卓易通内仅识别 gainmap，开关观感没有差异。
- 已收到 `202610160532` 的对照日志：内置图在原生与 Glide 中保留 gainmap，三种窗口 HDR 方式均无提亮；Android 显示能力及 scRGB／PQ EGL 扩展未暴露，SDR Surface 正常。详细证据见 `diagnostics-2026-10-16.md`。
- 已按用户授权实现绕过扩展预检的直接创建实验，保留实际色彩空间校验；第二轮已有成功证据。首轮在预检处停止，不能当作驱动实际拒绝创建的证据。
- 后选原图在原生与 Glide 中均 `hasGainmap=false`；完整尺寸解码被预算保护跳过，不能宣称已做完整尺寸复测。此次第一张原文件的独立 SHA-256 与两轮应用日志完全一致，已排除这份文件导入时被改写；问题重点转向华为格式解码适配。
- 已为诊断页 Glide 指定 `DownsampleStrategy.AT_MOST`，避免 `override(1280,1280)` 放大内置图或使长边超过上限。实际尺寸继续写入日志。

## Open

### Video attachments may reference an original public path (not an app copy)

Surfaced while root-causing the video-play crash (2026-06-24): a video
attachment stored the path `/storage/emulated/0/DCIM/Camera/VID....mp4` — the
camera roll's original location, not a copy under the app's files dir. Images
and newer imports go through `FileUtil.copyUriToFile` (raw byte copy into the
app dir), so this looks like older data or a path that bypassed the copy.

Risk if widespread: such attachments break when the user deletes/moves the
original media, and may be missing from app backups. The crash itself is fixed
(FileProvider now covers the external root + guarded), and `READ_MEDIA_VIDEO`
lets the app open it, so playback works for now.

Possible follow-up (needs a product decision, out of current scope):
- Decide whether all video attachments should be copied into the app dir on
  import (like images), and/or migrate existing external-path references.
- Confirm backup/export (`ThingExporter`, `BackupHelper`) handles external-path
  attachments correctly.

Deferred — not part of the HDR feature; raise with the user before acting.
