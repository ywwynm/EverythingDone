# 多语言文案完整性检查（2026-10-10）

## 结论

- **英文没有缺少中文基准中的资源键。** 香港、台湾繁体各缺 **136** 个；德语、西班牙语、法语、印地语、意大利语、日语、韩语、葡萄牙语、俄语各缺 **252** 个。两套繁体的缺项完全相同，九种外语的缺项也完全相同。
- 去重后共 **259 个缺失键**，按“资源键 × 缺失语言”计共 **2,540 处**。其中 **129 个**在上述 11 套非英文资源中共同缺失，**123 个**只在九种外语中缺失，**7 个**只在两套繁体中缺失。
- 数字代表本地资源未定义，不等于必须新增同样数量的译文：其中 **11 个**是中英文一致的技术名称或标识，可共用默认资源；**58 个**未发现主应用直接引用，需先确认是否仍使用。
- 除缺键外，九种外语还有同一组 **30 个卡片外观相关键沿用英文内容**。这组里有明显未翻译的文案，例如 `Auto select`、`Card width`、`Max content lines`、`Previous frame`；个别同形词应按具体语种判断。
- 两套繁体另有 **3 个既有文件夹确认文案**未同步中文内容和参数。目前未发现这三个键的直接调用，不能据此认定当前弹窗一定显示错误。

## 检查范围与方法

- 仓库版本：`f84d5a789`，以检查时工作区文件为准。
- 基准：主应用 `app/src/main/res/values-zh-rCN/`。检查其余全部 12 套语言资源，语言范围与设置页 `language_codes` 一致；“跟随系统设置”不是额外语言。
- 解析全部 `values*/*.xml` 中的 `string`、`string-array`、`plurals` 和 `item type="string"`；包含独立的 `tap_update.xml`，不只比较 `strings.xml`。
- 简体中文共有 **1,545 个文本资源键**。排除中文或默认资源中标注 `translatable="false"` 的 `language_codes`、`spatial_big_lama_title`、`spatial_moge2_title`、`spatial_moge2b_title`，检查基准为 **1,541 项**：1,525 个字符串、11 个数组、5 个复数资源。
- `tools:ignore="MissingTranslation"` 不作为完整性的豁免条件。
- 使用 Python XML 解析和 PowerShell XML 解析分别计算，各语言缺项数量一致；报告中的 259 个键及中文行号已逐项回查源文件。
- 默认目录的语言在 `resources.properties` 中声明为 `en`。全部缺失键都有默认英文值；缺少本地翻译的实际使用处存在显示英文的风险。本次为静态检查，未连接设备，也未进行逐屏截图验证。
- 引用检查覆盖 `app/src/main/` 内 Kotlin、Java、XML 的 `R.string.<键名>` 和 `@string/<键名>`。发现直接引用不保证所有代码路径都可达；未发现直接引用也不能排除动态查找。
- 不包含停止维护的 `Everything-Android`、独立 NFC 验证程序、测试程序和依赖库自带文案。本地库 `swirl`、`timelytextview` 未定义需对照的用户文本资源。
- 颜色名称 TSV 数据集不计入本轮资源缺项；其他语言共用英文名称是 `preferences.md` 已记录的例外。本次不是所有翻译的逐句语言质量审校，也不包含代码硬编码文案的完整提取。
- 仅新增检查报告并更新本地化功能文档；未修改翻译资源，未构建、发布或创建 Git 提交。

## 按语言统计

“有直接引用”列是缺失键的子集；最后一列再排除本报告已识别的共用技术标识，可用来安排优先补齐。

| 语言 | 目录 | 缺失键 | 其中有直接引用 | 有引用且非共用技术标识 |
|---|---|---:|---:|---:|
| 英语 | `values` | 0 | 0 | 0 |
| 繁体中文（香港） | `values-zh-rHK` | 136 | 82 | 74 |
| 繁体中文（台湾） | `values-zh-rTW` | 136 | 82 | 74 |
| 德语 | `values-de` | 252 | 194 | 183 |
| 西班牙语 | `values-es` | 252 | 194 | 183 |
| 法语 | `values-fr` | 252 | 194 | 183 |
| 印地语 | `values-hi` | 252 | 194 | 183 |
| 意大利语 | `values-it` | 252 | 194 | 183 |
| 日语 | `values-ja` | 252 | 194 | 183 |
| 韩语 | `values-ko` | 252 | 194 | 183 |
| 葡萄牙语 | `values-pt` | 252 | 194 | 183 |
| 俄语 | `values-ru` | 252 | 194 | 183 |

## 按功能分布

下表统计资源缺项，包含共用标识和未发现直接引用的历史资源。繁体两套的数字相同；九种外语的数字也相同。

| 功能 | 不重复的缺失键 | 每套繁体缺项 | 每种外语缺项 |
|---|---:|---:|---:|
| 文件夹、批量操作与私密内容 | 110 | 87 | 110 |
| 桌面小部件与类型筛选 | 40 | 1 | 40 |
| 系统音频输入与录音 | 35 | 0 | 35 |
| 颜色编辑、信息与相机取色 | 29 | 29 | 29 |
| 媒体、卡片外观与无障碍 | 16 | 8 | 16 |
| 首页空状态 | 8 | 0 | 8 |
| 空间照片及通用错误 | 7 | 7 | 0 |
| HDR 导出格式名称 | 5 | 0 | 5 |
| 抽屉头部图片 | 3 | 0 | 3 |
| 长截图与通用文案 | 2 | 0 | 2 |
| 音频可视化预览无障碍 | 2 | 2 | 2 |
| 多级清单无障碍 | 2 | 2 | 2 |

## 缺键之外的内容问题

### 卡片外观文案沿用英文

以下 30 个键在九种外语中均已定义，但逐字等同默认英文。不能以“键存在”判断这些页面已经完成本地化；`Image`、`Video`、`Normal` 等在部分语言中可能是正确同形词，不把所有相同值机械计为缺译。

当前调用证据：`ThingsActivity.kt:4433` 读取 `thing_card_appearance_source_default`；`panel_thing_card_appearance.xml:176` 使用 `thing_card_appearance_card_width`；同布局第 335 行把 `thing_card_appearance_video_frame_previous` 作为无障碍描述。

| 资源键 | 简体中文 | 九种外语当前共同值 | 中文行号 |
|---|---|---|---:|
| `thing_card_appearance_media_image` | 图片 | Image | 519 |
| `thing_card_appearance_media_video` | 视频 | Video | 520 |
| `thing_card_appearance_source_default` | 自动选择 | Auto select | 522 |
| `thing_card_appearance_cover_media` | 封面图片 | Cover image | 524 |
| `thing_card_appearance_span_normal` | 正常 | Normal | 525 |
| `thing_card_appearance_span_full` | 宽 | Wide | 526 |
| `thing_card_appearance_card_width` | 卡片宽度 | Card width | 527 |
| `thing_card_appearance_content_max_lines` | 内容文本的最大显示行数 | Max content lines | 528 |
| `thing_card_appearance_checklist_max_items` | 清单项的最大显示个数 | Max checklist items | 529 |
| `thing_card_appearance_limit_value_default_format` | %1$s · %2$d（默认） | %1$s · %2$d (default) | 531 |
| `thing_card_appearance_settings_span_normal` | 卡片宽度为正常时 | When card width is normal | 532 |
| `thing_card_appearance_settings_span_full` | 卡片宽度为宽时 | When card width is wide | 533 |
| `settings_thing_card_appearance` | 记事卡片外观设置 | Thing card appearance | 534 |
| `thing_card_appearance_placement_default` | 默认位置 | Default placement | 539 |
| `thing_card_appearance_placement_top` | 上方 | Top | 540 |
| `thing_card_appearance_placement_bottom` | 下方 | Bottom | 541 |
| `thing_card_appearance_placement_left` | 左侧 | Left | 542 |
| `thing_card_appearance_placement_right` | 右侧 | Right | 543 |
| `thing_card_appearance_mode_thumbnail` | 缩略图 | Thumbnail | 544 |
| `thing_card_appearance_media_position` | 图片/视频位置 | Image/video position | 545 |
| `thing_card_appearance_mode_background` | 背景 | Background | 546 |
| `thing_card_appearance_cover_image_width_format` | 封面图片宽度 · %1$d%% | Cover image width · %1$d%% | 547 |
| `thing_card_appearance_precise_crop` | 裁切封面图片 | Crop cover image | 548 |
| `thing_card_appearance_thumbnail_shape` | 封面图片比例 | Cover image ratio | 550 |
| `thing_card_appearance_background_mask` | 背景遮罩 | Background mask | 554 |
| `thing_card_appearance_background_height` | 卡片高度 | Card height | 555 |
| `thing_card_appearance_video_frame` | 视频选帧 | Video frame | 556 |
| `thing_card_appearance_video_frame_preview` | 视频帧预览 | Video frame preview | 557 |
| `thing_card_appearance_video_frame_previous` | 上一帧 | Previous frame | 561 |
| `thing_card_appearance_video_frame_next` | 下一帧 | Next frame | 562 |

### 繁体旧文案未同步

这三项在繁体文件中存在，因此不计入 136 个缺失键，但缺少简体新增的信息或格式参数。未发现直接引用，建议先确认保留必要性，再决定同步或清理。

| 资源键 | 简体新增信息 | 当前繁体内容问题 | 繁体行号 |
|---|---|---|---:|
| `dissolve_thing_folder_confirm` | 解散不可恢复、内容数量 `%1$s`、各项保留原状态 | 只有“移到上一层并删除资料夹”，缺少上述说明和参数 | 196 |
| `delete_thing_folder_confirm` | 操作范围 `%1$s`、数量 `%2$d`、附加范围 `%3$s`，仅删除进行中的记事 | 仍写成把资料夹及全部内容移到回收站／回收桶，范围与简体不一致 | 198 |
| `delete_thing_folder_forever_confirm` | 文件夹及全部内容、数量 `%1$s`、无法恢复 | 仍只有永久删除资料夹及其中记事的简短询问 | 201 |

### 同英文不必然缺译

全量扫描还发现品牌、单位、纯格式和部分语言中的同形词与英文一致。例如 `EverythingDone`、`s`、`h`、法语的 `30 minutes` 等不能按相同值认定未翻译。本报告的缺项统计不包含这些同值候选；卡片外观一组单列，是因为其中存在明确的英文短语及实际界面引用。

## 无缺项的结构检查

- **全部 11 个文案数组**在其余 12 套资源中都有定义，项目数量一致，没有空条目。
- **全部 5 个复数资源**在其余 12 套资源中都有定义，均包含非空 `other` 分支。本次只检查结构完整性，不把中文复数类别数量套用到其他语言，也不据此宣称各语言语法完全正确。
- **`tap_update.xml` 的 9 个键**在全部 13 套资源中齐全。
- 未发现同目录下文本资源重名或空字符串值。
- 未发现额外只在默认英文中定义、却遗漏于中文基准的可翻译文本；默认目录额外的 `hue_bucket_names` 是由既有 `@string/hue_bucket_*` 组成的引用数组，其他额外键均为明确不可翻译项。
- 简体文案有格式参数的普通字符串与现有各语种逐项对照，确认上述繁体 3 项参数缺失；`formatted="false"` 的百分比说明不视为格式参数。此项不代替运行时格式化验证。

## 建议顺序

1. 先补齐有直接引用的用户文案，重点是文件夹操作、系统录音、桌面小部件、颜色编辑、首页空状态，以及两套繁体缺少的空间照片入口。九种外语各有 183 个、每套繁体有 74 个“有引用且非共用标识”的缺项。
2. 同步处理卡片外观中已定义但仍为英文的内容，避免只补缺键后界面继续混用语言。
3. 对 58 个未发现直接引用的键确认用途；历史资源可清理，需要保留的再同步翻译。不要仅凭正则引用扫描直接删除。
4. 对下表中的共用技术名称统一检查是否应标记 `translatable="false"`；本次未改资源属性。

## 完整缺项清单

以下 **259 个键全部位于** `app/src/main/res/values-zh-rCN/strings.xml`；“中文行号”指检查时的文件行号。中文原文保留资源格式参数，便于后续翻译。

- “共用标识”：中英文值相同、经复核属于技术名称或来源信息，不要求机械补译。
- “未见直接引用”：限定为本次静态扫描范围，不等同于确定未使用。
- 没有备注的键：发现主应用直接引用，且不属于上述共用标识。

### 11 套非英文资源共同缺失：129 项

| 资源键 | 简体中文 | 功能 | 中文行号 | 备注 |
|---|---|---|---:|---|
| `thing_folder` | 记事文件夹 | 文件夹、批量操作与私密内容 | 192 |  |
| `private_thing_folder` | 私密记事文件夹 | 文件夹、批量操作与私密内容 | 193 |  |
| `thing_folder_count` | %1$d件记事 | 文件夹、批量操作与私密内容 | 194 |  |
| `thing_folder_count_folders` | %1$d个文件夹 | 文件夹、批量操作与私密内容 | 195 |  |
| `thing_folder_count_folders_things` | %1$d个文件夹，%2$d件记事 | 文件夹、批量操作与私密内容 | 196 |  |
| `cannot_set_as_private_thing_folder_title` | 无法设置为私密记事文件夹 | 文件夹、批量操作与私密内容 | 197 | 未见直接引用 |
| `thing_folder_location` | 位于 %1$s | 文件夹、批量操作与私密内容 | 198 | 未见直接引用 |
| `create_thing_folder_title` | 命名记事文件夹 | 文件夹、批量操作与私密内容 | 199 |  |
| `thing_folder_name_hint` | 文件夹名称 | 文件夹、批量操作与私密内容 | 200 |  |
| `default_thing_folder_name` | 新文件夹 | 文件夹、批量操作与私密内容 | 201 |  |
| `rename_thing_folder` | 重命名文件夹 | 文件夹、批量操作与私密内容 | 202 |  |
| `show_thing_folder_summary` | 显示摘要卡片 | 文件夹、批量操作与私密内容 | 203 | 未见直接引用 |
| `show_thing_folder_thumbnails` | 显示缩略图 | 文件夹、批量操作与私密内容 | 204 | 未见直接引用 |
| `make_thing_folder_wide` | 设为宽卡片 | 文件夹、批量操作与私密内容 | 205 | 未见直接引用 |
| `make_thing_folder_normal_width` | 使用普通宽度 | 文件夹、批量操作与私密内容 | 206 | 未见直接引用 |
| `folder_count_segment` | %1$d个子文件夹 | 文件夹、批量操作与私密内容 | 211 |  |
| `thing_count_segment` | %1$d件记事 | 文件夹、批量操作与私密内容 | 212 |  |
| `folder_impact_separator` | 、 | 文件夹、批量操作与私密内容 | 213 |  |
| `folder_impact_empty` | 无内容 | 文件夹、批量操作与私密内容 | 214 |  |
| `finish_all_things_in_folder` | 完成文件夹中所有记事 | 文件夹、批量操作与私密内容 | 215 |  |
| `finish_all_things_in_folder_confirm` | 将完成%1$s的所有正在进行的记事，共%2$d件%3$s。 | 文件夹、批量操作与私密内容 | 216 | 未见直接引用 |
| `finish_all_things_root_confirm` | 将完成所有正在进行的记事，共%1$d件%2$s。 | 文件夹、批量操作与私密内容 | 217 | 未见直接引用 |
| `scope_excludes_doing` | （不含正在做的记事） | 文件夹、批量操作与私密内容 | 219 |  |
| `scope_includes_subfolders_and_excludes_doing` | （包含所有子文件夹中的记事，不含正在做的记事） | 文件夹、批量操作与私密内容 | 220 |  |
| `scope_selected_things` | 所选 | 文件夹、批量操作与私密内容 | 221 | 未见直接引用 |
| `scope_selected_folders` | 所选文件夹 | 文件夹、批量操作与私密内容 | 222 | 未见直接引用 |
| `scope_selected_items` | 所选项 | 文件夹、批量操作与私密内容 | 223 | 未见直接引用 |
| `thing_status_deleted` | 已删除 | 文件夹、批量操作与私密内容 | 224 |  |
| `no_underway_things_to_finish` | 没有正在进行的记事可完成 | 文件夹、批量操作与私密内容 | 225 |  |
| `restore_all_things` | 恢复所有记事 | 文件夹、批量操作与私密内容 | 226 |  |
| `restore_all_things_in_folder` | 恢复文件夹中所有记事 | 文件夹、批量操作与私密内容 | 227 |  |
| `restore_all_finished_in_folder_confirm` | 将把%1$s的所有已完成的记事恢复为正在进行，共%2$d件%3$s。 | 文件夹、批量操作与私密内容 | 228 | 未见直接引用 |
| `restore_all_finished_root_confirm` | 将把所有已完成的记事恢复为正在进行，共%1$d件%2$s。 | 文件夹、批量操作与私密内容 | 229 | 未见直接引用 |
| `delete_all_finished_root_confirm` | 将把所有已完成的记事移入回收站，共%1$d件%2$s，可在回收站恢复。 | 文件夹、批量操作与私密内容 | 230 | 未见直接引用 |
| `delete_all_finished_in_folder_confirm` | 将把%1$s的所有已完成的记事移入回收站，共%2$d件%3$s，可在回收站恢复。 | 文件夹、批量操作与私密内容 | 231 | 未见直接引用 |
| `delete_forever_all_root_confirm` | 将永久删除所有已删除的记事，共%1$d件%2$s，无法恢复。 | 文件夹、批量操作与私密内容 | 232 | 未见直接引用 |
| `delete_forever_all_in_folder_confirm` | 将永久删除%1$s的所有已删除的记事，共%2$d件%3$s，无法恢复。 | 文件夹、批量操作与私密内容 | 233 | 未见直接引用 |
| `no_finished_things_to_delete` | 没有已完成的记事可删除 | 文件夹、批量操作与私密内容 | 234 |  |
| `no_trashed_things_to_delete_forever` | 回收站中没有记事可永久删除 | 文件夹、批量操作与私密内容 | 235 |  |
| `restore_all_trashed_in_folder_confirm` | 将把%1$s的所有已删除的记事恢复到删除前状态，共%2$d件%3$s。 | 文件夹、批量操作与私密内容 | 236 | 未见直接引用 |
| `restore_all_trashed_root_confirm` | 将把所有已删除的记事恢复到删除前状态，共%1$d件%2$s。 | 文件夹、批量操作与私密内容 | 237 | 未见直接引用 |
| `no_finished_things_to_restore` | 没有已完成的记事可恢复 | 文件夹、批量操作与私密内容 | 238 |  |
| `no_trashed_things_to_restore` | 没有回收站中的记事可恢复 | 文件夹、批量操作与私密内容 | 239 |  |
| `no_things_to_delete_in_folder` | 所选文件夹中没有可删除的记事 | 文件夹、批量操作与私密内容 | 242 |  |
| `delete_all_things_in_folder_forever` | 永久删除文件夹中所有记事 | 文件夹、批量操作与私密内容 | 243 |  |
| `move_thing_folder_card` | 调整卡片顺序 | 文件夹、批量操作与私密内容 | 300 | 未见直接引用 |
| `move_thing_folder_to` | 移动到文件夹 | 文件夹、批量操作与私密内容 | 301 |  |
| `all_things_scope` | 全部记事 | 文件夹、批量操作与私密内容 | 302 | 未见直接引用 |
| `thing_folder_root` | 根目录 | 文件夹、批量操作与私密内容 | 303 | 未见直接引用 |
| `thing_folder_child_count` | %1$d 项内容 | 文件夹、批量操作与私密内容 | 304 | 未见直接引用 |
| `act_color_info` | 颜色信息 | 颜色编辑、信息与相机取色 | 390 |  |
| `act_use_color` | 使用颜色 | 颜色编辑、信息与相机取色 | 392 |  |
| `color_info_title` | 颜色信息 | 颜色编辑、信息与相机取色 | 393 |  |
| `color_info_current` | 当前颜色 | 颜色编辑、信息与相机取色 | 394 | 未见直接引用 |
| `color_info_gradient_start` | 渐变起点 | 颜色编辑、信息与相机取色 | 395 |  |
| `color_info_gradient_end` | 渐变终点 | 颜色编辑、信息与相机取色 | 396 |  |
| `color_editor_tab_pure` | 纯色 | 颜色编辑、信息与相机取色 | 397 |  |
| `color_editor_tab_gradient` | 渐变 | 颜色编辑、信息与相机取色 | 398 |  |
| `color_channel_r` | R | 颜色编辑、信息与相机取色 | 399 | 共用标识 |
| `color_channel_g` | G | 颜色编辑、信息与相机取色 | 400 | 共用标识 |
| `color_channel_b` | B | 颜色编辑、信息与相机取色 | 401 | 共用标识 |
| `color_channel_hex` | Hex | 颜色编辑、信息与相机取色 | 402 | 共用标识 |
| `cd_random_color` | 随机取色 | 颜色编辑、信息与相机取色 | 403 |  |
| `color_editor_back` | 返回 | 颜色编辑、信息与相机取色 | 404 |  |
| `color_info_representative` | 代表色 | 颜色编辑、信息与相机取色 | 405 |  |
| `color_info_name` | 名称 | 颜色编辑、信息与相机取色 | 406 |  |
| `color_info_english_name` | 英文名称 | 颜色编辑、信息与相机取色 | 407 |  |
| `color_info_rgb` | RGB | 颜色编辑、信息与相机取色 | 408 | 共用标识 |
| `color_info_hex` | Hex | 颜色编辑、信息与相机取色 | 409 | 共用标识 |
| `color_info_hsl` | HSL | 颜色编辑、信息与相机取色 | 410 | 共用标识 |
| `color_info_matched_entry` | 匹配条目 | 颜色编辑、信息与相机取色 | 411 | 未见直接引用 |
| `color_info_source` | 名称来源 | 颜色编辑、信息与相机取色 | 412 | 未见直接引用 |
| `color_info_source_value` | meodai/color-names full list v14.38.0 (MIT) | 颜色编辑、信息与相机取色 | 413 | 共用标识 |
| `color_info_match` | 匹配方式 | 颜色编辑、信息与相机取色 | 414 | 未见直接引用 |
| `color_info_match_value` | CIEDE2000 ΔE 最近匹配，距离 %1$s | 颜色编辑、信息与相机取色 | 415 |  |
| `color_info_translation_pending` | 中文颜色名翻译仍待生成；当前显示英文源名称。 | 颜色编辑、信息与相机取色 | 416 | 未见直接引用 |
| `camera_color_title` | 相机取色 | 颜色编辑、信息与相机取色 | 417 | 未见直接引用 |
| `camera_color_waiting` | 将中心点对准颜色 | 颜色编辑、信息与相机取色 | 418 |  |
| `camera_color_unavailable` | 相机不可用 | 颜色编辑、信息与相机取色 | 419 |  |
| `act_delete_all_forever` | 永久删除所有记事 | 文件夹、批量操作与私密内容 | 470 |  |
| `act_move_to_thing_folder` | 移动到文件夹 | 文件夹、批量操作与私密内容 | 483 |  |
| `confirm_finish_selected_things` | 将完成所选记事，共%1$d件。 | 文件夹、批量操作与私密内容 | 486 | 未见直接引用 |
| `confirm_delete_selected_things` | 将把所选记事移入回收站，共%1$d件，可在回收站恢复。 | 文件夹、批量操作与私密内容 | 487 | 未见直接引用 |
| `confirm_restore_selected_things` | 将恢复所选记事，共%1$d件。 | 文件夹、批量操作与私密内容 | 488 | 未见直接引用 |
| `confirm_delete_selected_things_forever` | 将永久删除所选记事，共%1$d件，无法恢复。 | 文件夹、批量操作与私密内容 | 489 | 未见直接引用 |
| `act_finish_selected_items` | 完成所选项中的记事 | 文件夹、批量操作与私密内容 | 490 | 未见直接引用 |
| `act_delete_selected_items` | 删除所选项中的记事 | 文件夹、批量操作与私密内容 | 491 | 未见直接引用 |
| `act_restore_selected_items` | 恢复所选项中的记事 | 文件夹、批量操作与私密内容 | 492 | 未见直接引用 |
| `act_delete_selected_items_forever` | 永久删除所选项中的记事 | 文件夹、批量操作与私密内容 | 493 | 未见直接引用 |
| `act_finish_selected_folders` | 完成所选文件夹中的所有记事 | 文件夹、批量操作与私密内容 | 494 | 未见直接引用 |
| `act_delete_selected_folders` | 删除所选文件夹中的所有记事 | 文件夹、批量操作与私密内容 | 495 | 未见直接引用 |
| `act_restore_selected_folders` | 恢复所选文件夹中的所有记事 | 文件夹、批量操作与私密内容 | 496 | 未见直接引用 |
| `act_delete_selected_folders_forever` | 永久删除所选文件夹中的所有记事 | 文件夹、批量操作与私密内容 | 497 | 未见直接引用 |
| `confirm_finish_selected_items` | 将完成所选项中的记事，共%1$d件%2$s。 | 文件夹、批量操作与私密内容 | 498 | 未见直接引用 |
| `confirm_delete_selected_items` | 将把所选项中的记事移入回收站，共%1$d件%2$s，可在回收站恢复。 | 文件夹、批量操作与私密内容 | 499 | 未见直接引用 |
| `confirm_restore_selected_items` | 将恢复所选项中的记事，共%1$d件%2$s。 | 文件夹、批量操作与私密内容 | 500 | 未见直接引用 |
| `confirm_delete_selected_items_forever` | 将永久删除所选项中的记事，共%1$d件%2$s，无法恢复。 | 文件夹、批量操作与私密内容 | 501 | 未见直接引用 |
| `confirm_finish_selected_folders` | 将完成所选文件夹中的所有记事，共%1$d件%2$s。 | 文件夹、批量操作与私密内容 | 502 | 未见直接引用 |
| `confirm_delete_selected_folders` | 将把所选文件夹中的所有记事移入回收站，共%1$d件%2$s，可在回收站恢复。 | 文件夹、批量操作与私密内容 | 503 | 未见直接引用 |
| `confirm_restore_selected_folders` | 将恢复所选文件夹中的所有记事，共%1$d件%2$s。 | 文件夹、批量操作与私密内容 | 504 | 未见直接引用 |
| `confirm_delete_selected_folders_forever` | 将永久删除所选文件夹回收站中的所有记事，共%1$d件%2$s，无法恢复。 | 文件夹、批量操作与私密内容 | 505 | 未见直接引用 |
| `delete_selected_folders_forever` | 永久删除所选文件夹 | 文件夹、批量操作与私密内容 | 506 | 未见直接引用 |
| `delete_selected_items_forever_structural` | 永久删除所选项 | 文件夹、批量操作与私密内容 | 507 | 未见直接引用 |
| `delete_selected_folders_forever_confirm` | 将永久删除所选文件夹及其全部内容，共%1$s，无法恢复。 | 文件夹、批量操作与私密内容 | 508 | 未见直接引用 |
| `delete_selected_items_forever_structural_confirm` | 将永久删除所选项（所选文件夹及其全部内容、以及所选记事），共%1$s，无法恢复。 | 文件夹、批量操作与私密内容 | 509 | 未见直接引用 |
| `no_matching_things_in_selection` | 所选范围内没有可操作的记事 | 文件夹、批量操作与私密内容 | 510 |  |
| `private_batch_skipped` | 正在做的记事未设为私密 | 文件夹、批量操作与私密内容 | 511 |  |
| `thing_card_appearance_precise_crop_video` | 裁切封面视频 | 媒体、卡片外观与无障碍 | 549 |  |
| `thing_card_appearance_video_frame_play` | 播放视频帧预览 | 媒体、卡片外观与无障碍 | 558 |  |
| `thing_card_appearance_video_frame_pause` | 暂停视频帧预览 | 媒体、卡片外观与无障碍 | 559 |  |
| `thing_card_appearance_video_frame_stop` | 停止视频帧预览 | 媒体、卡片外观与无障碍 | 560 |  |
| `act_set_as_private_items` | 设为私密项 | 文件夹、批量操作与私密内容 | 588 |  |
| `act_cancel_private_items` | 取消私密项 | 文件夹、批量操作与私密内容 | 589 |  |
| `cd_fablesol_tuning_pause` | 暂停或继续预览 | 音频可视化预览无障碍 | 1097 |  |
| `cd_fablesol_tuning_switch_color` | 切换水体颜色 | 音频可视化预览无障碍 | 1098 |  |
| `open_private_thing_folder` | 打开私密文件夹 | 文件夹、批量操作与私密内容 | 1244 |  |
| `expand_private_thing_folder` | 展开私密文件夹 | 文件夹、批量操作与私密内容 | 1245 |  |
| `manage_private_thing_folder` | 管理私密文件夹 | 文件夹、批量操作与私密内容 | 1246 | 未见直接引用 |
| `customize_private_thing_folder_card` | 调整私密文件夹卡片外观 | 文件夹、批量操作与私密内容 | 1247 |  |
| `customize_private_thing_card` | 调整私密记事卡片外观 | 文件夹、批量操作与私密内容 | 1248 |  |
| `move_private_thing_or_folder` | 移动私密内容 | 文件夹、批量操作与私密内容 | 1249 |  |
| `finish_private_thing_by_swipe` | 完成私密记事 | 文件夹、批量操作与私密内容 | 1250 |  |
| `widget_status_label` | 记事状态 | 桌面小部件与类型筛选 | 1389 |  |
| `cd_checklist_indent` | 将该清单项向右移动一个层级 | 多级清单无障碍 | 1464 |  |
| `cd_checklist_outdent` | 将该清单项向左移动一个层级 | 多级清单无障碍 | 1465 |  |
| `cd_motion_photo` | 动态照片，按住播放 | 媒体、卡片外观与无障碍 | 1493 |  |
| `motion_photo_live_badge` | 实况 | 媒体、卡片外观与无障碍 | 1494 |  |
| `cd_edit_image_attachment_appearance` | 编辑该图片附件外观 | 媒体、卡片外观与无障碍 | 1515 |  |
| `cd_edit_video_attachment_appearance` | 编辑该视频附件外观 | 媒体、卡片外观与无障碍 | 1516 |  |

### 仅九种外语缺失：123 项

| 资源键 | 简体中文 | 功能 | 中文行号 | 备注 |
|---|---|---|---:|---|
| `error_generating_screenshot` | 无法生成长截图，请重试。 | 长截图与通用文案 | 5 |  |
| `title_widget_thing_1x1` | 记事 1x1 | 桌面小部件与类型筛选 | 128 |  |
| `title_widget_thing_2x2` | 记事 2x2 | 桌面小部件与类型筛选 | 129 |  |
| `title_widget_thing_3x3` | 记事 3x3 | 桌面小部件与类型筛选 | 130 |  |
| `title_widget_thing_4x4` | 记事 4x4 | 桌面小部件与类型筛选 | 131 |  |
| `title_widget_thing_4x2` | 记事 4x2 | 桌面小部件与类型筛选 | 132 |  |
| `title_widget_thing_2x4` | 记事 2x4 | 桌面小部件与类型筛选 | 133 |  |
| `title_widget_thing_4x3` | 记事 4x3 | 桌面小部件与类型筛选 | 134 |  |
| `title_widget_thing_3x4` | 记事 3x4 | 桌面小部件与类型筛选 | 135 |  |
| `title_widget_thing_5x2` | 记事 5x2 | 桌面小部件与类型筛选 | 136 |  |
| `title_widget_thing_2x5` | 记事 2x5 | 桌面小部件与类型筛选 | 137 |  |
| `title_widget_thing_5x3` | 记事 5x3 | 桌面小部件与类型筛选 | 138 |  |
| `title_widget_thing_3x5` | 记事 3x5 | 桌面小部件与类型筛选 | 139 |  |
| `title_widget_thing_5x4` | 记事 5x4 | 桌面小部件与类型筛选 | 140 |  |
| `title_widget_thing_4x5` | 记事 4x5 | 桌面小部件与类型筛选 | 141 |  |
| `title_widget_thing_5x5` | 记事 5x5 | 桌面小部件与类型筛选 | 142 |  |
| `title_widget_thing_6x2` | 记事 6x2 | 桌面小部件与类型筛选 | 143 |  |
| `title_widget_thing_2x6` | 记事 2x6 | 桌面小部件与类型筛选 | 144 |  |
| `title_widget_thing_6x3` | 记事 6x3 | 桌面小部件与类型筛选 | 145 |  |
| `title_widget_thing_3x6` | 记事 3x6 | 桌面小部件与类型筛选 | 146 |  |
| `title_widget_thing_6x4` | 记事 6x4 | 桌面小部件与类型筛选 | 147 |  |
| `title_widget_thing_4x6` | 记事 4x6 | 桌面小部件与类型筛选 | 148 |  |
| `title_widget_thing_6x5` | 记事 6x5 | 桌面小部件与类型筛选 | 149 |  |
| `title_widget_thing_5x6` | 记事 5x6 | 桌面小部件与类型筛选 | 150 |  |
| `title_widget_thing_6x6` | 记事 6x6 | 桌面小部件与类型筛选 | 151 |  |
| `title_widget_things_list_3x3` | 记事列表 3x3 | 桌面小部件与类型筛选 | 152 |  |
| `title_widget_things_list_4x4` | 记事列表 4x4 | 桌面小部件与类型筛选 | 153 |  |
| `title_widget_things_list_5x4` | 记事列表 5x4 | 桌面小部件与类型筛选 | 154 |  |
| `title_widget_things_list_4x5` | 记事列表 4x5 | 桌面小部件与类型筛选 | 155 |  |
| `title_widget_things_list_5x5` | 记事列表 5x5 | 桌面小部件与类型筛选 | 156 |  |
| `title_widget_things_list_6x4` | 记事列表 6x4 | 桌面小部件与类型筛选 | 157 |  |
| `title_widget_things_list_4x6` | 记事列表 4x6 | 桌面小部件与类型筛选 | 158 |  |
| `title_widget_things_list_6x5` | 记事列表 6x5 | 桌面小部件与类型筛选 | 159 |  |
| `title_widget_things_list_5x6` | 记事列表 5x6 | 桌面小部件与类型筛选 | 160 |  |
| `title_widget_things_list_6x6` | 记事列表 6x6 | 桌面小部件与类型筛选 | 161 |  |
| `all_content` | 所有内容 | 长截图与通用文案 | 173 |  |
| `set_thing_folder_private` | 设为私密文件夹 | 文件夹、批量操作与私密内容 | 207 |  |
| `cancel_thing_folder_private` | 取消私密文件夹 | 文件夹、批量操作与私密内容 | 208 |  |
| `dissolve_thing_folder` | 解散文件夹 | 文件夹、批量操作与私密内容 | 209 |  |
| `dissolve_thing_folder_confirm` | 将解散所选文件夹：文件夹本身将被删除（不进入回收站，无法恢复），其全部内容（共%1$s）按各自原状态移到上一级目录。 | 文件夹、批量操作与私密内容 | 210 | 未见直接引用 |
| `delete_thing_folder` | 删除文件夹中所有记事 | 文件夹、批量操作与私密内容 | 240 |  |
| `delete_thing_folder_confirm` | 将把%1$s的所有正在进行的记事移入回收站，共%2$d件%3$s，可在回收站恢复。 | 文件夹、批量操作与私密内容 | 241 | 未见直接引用 |
| `delete_thing_folder_forever` | 永久删除文件夹 | 文件夹、批量操作与私密内容 | 244 |  |
| `delete_thing_folder_forever_confirm` | 将永久删除所选文件夹及其全部内容，共%1$s，无法恢复。 | 文件夹、批量操作与私密内容 | 245 | 未见直接引用 |
| `folder_op_scope_only_search` | 本次操作仅作用于当前搜索范围内的记事，其他不在搜索范围内的记事不受影响 | 文件夹、批量操作与私密内容 | 247 |  |
| `folder_op_scope_type_and_search` | 本次操作仅作用于当前类型筛选（%1$s）和搜索范围内的记事，其他类型的记事、不在搜索范围内的记事不受影响 | 文件夹、批量操作与私密内容 | 248 |  |
| `folder_op_scope_search` | 搜索范围 | 文件夹、批量操作与私密内容 | 253 |  |
| `folder_op_scope_status_search` | 状态筛选（%1$s）和搜索范围 | 文件夹、批量操作与私密内容 | 254 |  |
| `folder_op_scope_type_filter_search` | 类型筛选（%1$s）和搜索范围 | 文件夹、批量操作与私密内容 | 255 |  |
| `folder_op_scope_status_type_search` | 状态和类型筛选（%1$s、%2$s）以及搜索范围 | 文件夹、批量操作与私密内容 | 256 |  |
| `home_empty_underway` | 这里还没有进行中的记事 | 首页空状态 | 326 |  |
| `home_empty_note` | 这里还没有笔记 | 首页空状态 | 327 |  |
| `home_empty_reminder` | 这里还没有提醒 | 首页空状态 | 328 |  |
| `home_empty_habit` | 这里还没有习惯 | 首页空状态 | 329 |  |
| `home_empty_goal` | 这里还没有目标 | 首页空状态 | 330 |  |
| `home_empty_finished` | 这里还没有已完成的记事 | 首页空状态 | 331 |  |
| `home_empty_deleted` | 这里还没有已删除的记事 | 首页空状态 | 332 |  |
| `home_empty_folder` | 这个文件夹还是空的 | 首页空状态 | 333 |  |
| `act_customize_folder_card_appearance` | 调整文件夹外观 | 文件夹、批量操作与私密内容 | 513 |  |
| `thing_card_appearance_source_none` | 无 | 媒体、卡片外观与无障碍 | 523 |  |
| `folder_card_appearance_name_hint` | 文件夹名称 | 文件夹、批量操作与私密内容 | 535 |  |
| `folder_card_appearance_display_mode` | 文件夹大小 | 文件夹、批量操作与私密内容 | 536 |  |
| `folder_card_appearance_mode_summary` | 正常 | 文件夹、批量操作与私密内容 | 537 |  |
| `folder_card_appearance_mode_large` | 大 | 文件夹、批量操作与私密内容 | 538 |  |
| `thing_card_appearance_ratio_overridden_hint` | 封面图片比例的调整范围受记事卡片高度影响，当前卡片高度较大，封面图片按照卡片宽高显示 | 媒体、卡片外观与无障碍 | 551 |  |
| `thing_card_appearance_ratio_overridden_hint_video` | 封面视频比例的调整范围受记事卡片高度影响，当前卡片高度较大，封面视频按照卡片宽高显示 | 媒体、卡片外观与无障碍 | 552 |  |
| `audio_input_label` | 音频输入 | 系统音频输入与录音 | 820 |  |
| `audio_input_microphone` | 麦克风 | 系统音频输入与录音 | 821 |  |
| `audio_input_system` | 系统 | 系统音频输入与录音 | 822 |  |
| `audio_input_system_and_microphone` | 麦克风+系统 | 系统音频输入与录音 | 823 |  |
| `audio_input_microphone_summary` | 录制设备麦克风。正式录音在切换到其他 App 后仍会继续，可从通知停止并保留。 | 系统音频输入与录音 | 824 |  |
| `audio_input_system_summary` | 录制其他 App 允许捕获的播放音频。需要 Android 10+、麦克风权限和整个屏幕范围的系统授权，但本应用不会读取或保存画面；部分 App 可能禁止捕获。正式录音在切换 App 后仍会继续。停止录音会结束授权；也可从系统的屏幕共享状态入口撤销，撤销后将停止并保留当前录音。 | 系统音频输入与录音 | 825 |  |
| `audio_input_system_and_microphone_summary` | 同时录制系统播放音频和设备麦克风。需要 Android 10+、麦克风权限和整个屏幕范围的系统授权，但本应用不会读取或保存画面。建议使用耳机减少回声。正式录音在切换 App 后仍会继续。停止录音会结束授权；也可从系统的屏幕共享状态入口撤销，撤销后将停止并保留当前录音。 | 系统音频输入与录音 | 826 |  |
| `cd_choose_audio_input` | 选择音频输入 | 系统音频输入与录音 | 827 |  |
| `audio_input_projection_denied` | 已取消系统授权，音频输入已恢复为“麦克风” | 系统音频输入与录音 | 828 |  |
| `audio_recording_size_limit_reached` | 录音已达单文件上限，已自动停止并保留 | 系统音频输入与录音 | 831 |  |
| `audio_recording_storage_full` | 存储空间不足，录音未能保存 | 系统音频输入与录音 | 832 |  |
| `audio_recording_finalize_failed` | 录音封装失败，未能保存 | 系统音频输入与录音 | 833 |  |
| `audio_recording_stop_not_kept_notification_title` | 录音已停止但未能保存 | 系统音频输入与录音 | 834 |  |
| `audio_recording_busy_other_thing` | 正在为另一条记事录音 | 系统音频输入与录音 | 835 |  |
| `audio_recording_go_to` | 前往 | 系统音频输入与录音 | 836 |  |
| `audio_input_system_initialization_failed` | 无法启动系统音频，音频输入已恢复为“麦克风” | 系统音频输入与录音 | 837 |  |
| `audio_input_projection_revoked` | 系统音频授权已结束，已保留此前录音 | 系统音频输入与录音 | 838 |  |
| `audio_input_system_capture_ended` | 系统音频授权已结束，音频输入已恢复为“麦克风” | 系统音频输入与录音 | 839 |  |
| `audio_input_recording_start_failed` | 无法开始录音 | 系统音频输入与录音 | 840 |  |
| `audio_recording_finalizing` | 正在保存录音… | 系统音频输入与录音 | 841 |  |
| `audio_recording_finalizing_notification_title` | 正在保存录音 | 系统音频输入与录音 | 842 |  |
| `audio_recording_file_output_failed` | 录音文件写入失败 | 系统音频输入与录音 | 843 |  |
| `audio_recording_file_write_interrupted` | 录音写入中断，已保留此前录音 | 系统音频输入与录音 | 844 |  |
| `cd_save_unavailable_not_kept` | 录音未能保留，无法保存 | 系统音频输入与录音 | 845 |  |
| `audio_input_capture_failed` | 必需的音频输入已停止，已保留此前录音 | 系统音频输入与录音 | 846 |  |
| `audio_input_microphone_unavailable` | 无法启动麦克风 | 系统音频输入与录音 | 847 |  |
| `audio_input_system_silent` | 未检测到可捕获的系统音频，部分 App 可能禁止捕获 | 系统音频输入与录音 | 848 |  |
| `audio_input_aec_unavailable` | 此设备无法启用回声消除，建议使用耳机减少回声 | 系统音频输入与录音 | 849 |  |
| `audio_recording_notification_channel` | 音频录制 | 系统音频输入与录音 | 850 |  |
| `audio_input_preview_notification_title` | 正在预览系统音频 | 系统音频输入与录音 | 851 |  |
| `audio_input_authorization_notification_title` | 系统音频授权已启用 | 系统音频输入与录音 | 852 |  |
| `audio_recording_notification_title` | 正在录音 | 系统音频输入与录音 | 853 |  |
| `audio_recording_notification_stop_keep` | 停止并保留 | 系统音频输入与录音 | 854 |  |
| `audio_recording_notification_permission_missing` | 通知权限未开启。录音仍可进行，但 Android 13+ 只会在系统“活动应用”入口显示前台服务状态；通知栏不会显示来源和“停止并保留”操作。 | 系统音频输入与录音 | 855 |  |
| `audio_recording_stopped_notification_title` | 录音已停止并保留 | 系统音频输入与录音 | 856 |  |
| `settings_drawer_header_choose_image` | 选择图片 | 抽屉头部图片 | 966 |  |
| `settings_drawer_header_adjust_crop` | 调整裁切 | 抽屉头部图片 | 967 |  |
| `drawer_header_ratio` | 头图比例 | 抽屉头部图片 | 968 |  |
| `set_password_first_title` | 请先设置密码 | 文件夹、批量操作与私密内容 | 1261 |  |
| `all_types` | 全部类型 | 桌面小部件与类型筛选 | 1383 |  |
| `widget_type_filter_summary` | 记事类型：%1$s | 桌面小部件与类型筛选 | 1387 | 未见直接引用 |
| `widget_display_mode_label` | 显示模式 | 桌面小部件与类型筛选 | 1388 |  |
| `widget_display_mode_list` | 列表 | 桌面小部件与类型筛选 | 1390 |  |
| `widget_display_mode_grid` | 网格 | 桌面小部件与类型筛选 | 1391 |  |
| `cd_back_parent_folder` | 返回上级文件夹 | 文件夹、批量操作与私密内容 | 1437 |  |
| `cd_expand_thing_folder` | 展开文件夹 | 文件夹、批量操作与私密内容 | 1467 |  |
| `cd_collapse_thing_folder` | 收起文件夹 | 文件夹、批量操作与私密内容 | 1468 |  |
| `cd_hdr_badge_on` | HDR 已开启，点按以标准动态范围显示 | 媒体、卡片外观与无障碍 | 1686 |  |
| `cd_hdr_badge_off` | HDR 已关闭，点按开启 HDR 显示 | 媒体、卡片外观与无障碍 | 1687 |  |
| `image_viewer_no_video_player` | 没有可播放该视频的应用 | 媒体、卡片外观与无障碍 | 1688 |  |
| `fablesol_export_hdr_brand_dolby_vision` | 杜比视界 | HDR 导出格式名称 | 1818 |  |
| `fablesol_export_hdr_format_name_hdr10` | HDR10 | HDR 导出格式名称 | 1819 | 共用标识 |
| `fablesol_export_hdr_format_name_hdr10_plus` | HDR10+ | HDR 导出格式名称 | 1820 | 共用标识 |
| `fablesol_export_hdr_format_name_hlg` | HLG | HDR 导出格式名称 | 1822 | 共用标识 |
| `fablesol_export_hdr_format_name_dolby_vision_84` | 杜比视界 8.4 | HDR 导出格式名称 | 1823 |  |
| `detail_media_preview_loading` | 正在加载预览 | 媒体、卡片外观与无障碍 | 2094 |  |
| `detail_media_preview_load_failed` | 无法加载媒体预览 | 媒体、卡片外观与无障碍 | 2095 |  |

### 仅香港、台湾繁体缺失：7 项

| 资源键 | 简体中文 | 功能 | 中文行号 | 备注 |
|---|---|---|---:|---|
| `act_spatial_photo` | 空间照片效果 | 空间照片及通用错误 | 1870 |  |
| `act_exit_spatial_photo` | 退出空间照片效果 | 空间照片及通用错误 | 1871 |  |
| `act_regenerate_spatial_photo` | 重新生成空间效果 | 空间照片及通用错误 | 1872 |  |
| `act_remove_spatial_photo` | 移除空间效果 | 空间照片及通用错误 | 1873 |  |
| `cd_spatial_photo_view` | 可交互的空间照片 | 空间照片及通用错误 | 1881 |  |
| `unknown_error` | 未知错误 | 空间照片及通用错误 | 1903 |  |
| `cd_spatial_effect_ready` | 已生成空间照片效果 | 空间照片及通用错误 | 2036 |  |
