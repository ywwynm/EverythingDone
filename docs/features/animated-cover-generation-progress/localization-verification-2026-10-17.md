# 动态封面生成文案的全语言补齐

后续全面复核发现香港繁体的 6 处“產生”与应用地区词表不一致，已改为“生成”并随 debug `202610171520` 发布。逐语言依据见 [审查记录](review-2026-10-17.md)；下文保留初次补齐版本的验证与发布事实。

## 范围与缺项

- 以设置页 `language_codes` 列出的 13 套语言资源为准：默认英文、简体中文、台湾繁体、香港繁体，以及德语、西班牙语、法语、印地语、意大利语、日语、韩语、葡萄牙语、俄语。
- 原先只有默认英文和三套中文定义了 `strings_cover_generation.xml`。其余 9 套缺少全部 8 条文案，包括初版的等待、准备、生成、失败和重试；本轮共补充 72 条译文。
- 全部 8 个资源键为 `cover_generation_channel`、`cover_generation_background`、`cover_generation_waiting`、`cover_generation_preparing`、`cover_generation_running`、`cover_generation_finalizing`、`cover_generation_failed`、`cover_generation_retry`。
- `CoverGenerationBinding` 使用其中 5 条阶段提示及 1 条重试无障碍操作标签；`VideoCoverPreviewWorker` 使用通知渠道、生成中标题和完成后自动更新说明。已核对这些调用的资源覆盖范围。
- 简体中文作为原意基准，沿用各语言既有的封面名词；等待、准备、生成、编码与失败相互区分。已有英文及三套中文不作措辞改写。
- `values-zh` 仅含中文布局尺寸和背景配置，不是应用中的额外语言选项；本轮不新增语言，也不更改语言选择或回退规则。

## 验证结果

- `docs/features/localization/validate-resources.py` 通过：以简体中文 1614 项可翻译资源为基准，全部 13 套语言均无缺项、重复、空值、参数或数组／复数结构错误。
- `:app:assembleDebug` 构建成功。读取实际 APK 的 AAPT 资源表，8 个键 × 13 套配置共 104 个值与对应 XML 原文逐一相同；包括所有新增的 72 条译文。计数与完整资源检查结果见 [resources.json](checks/localization-2026-10-17/resources.json)。
- 已逐条检查等待、准备、生成、编码、失败和重试的含义，保留现有单行截断行为。代码核对确认 `App.attachBaseContext()` 使用应用所选语言，设置页切换语言后重启应用，Worker 通知沿用该上下文。
- 本轮仅补充语言资源，不重复前一轮大视频、长后台和进程恢复真机测试；这些验证的原始结果及边界见 [后台排查记录](background-progress-investigation-2026-10-17.md)。

## 发布核验

- 阿里云 debug 版本：`202610171502`，使用独立日志 [update-20261017230205.md](debug-updates/update-20261017230205.md)。
- 远端元数据和更新说明与本地一致。重新下载的 APK 大小为 31,656,937 字节，SHA-256 为 `2f3d0ca36a04d9cea31c9c8886e40e089bc5d218bddb032841b2a8b1f2276e14`，ZIP CRC 通过，包名为 `com.ywwynm.everythingdone`。
- 重新检查发布 APK 的 13 套配置、104 个文案值，与源码逐项一致；结果见 [publish.json](checks/localization-2026-10-17/publish.json)。未安装到设备，未创建 Git 提交。
