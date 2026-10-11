# Localization Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-10-11 — 全面恢复中文基准与各语言数量分隔符

- 用户明确要求复核本次全部中文变更，不止数量摘要。恢复产品术语统一以外的不必要改写，范围包含 XML 和动态代码；继续遵守已明确授权的 24 小时制及按应用语言确定日期顺序。
- 英文列举保留既有标点；所有语言的文件夹及附件数量摘要、文件夹操作影响范围统一保留标点连接，不引入语言中的“和”。
- 本轮以本地化前的 Git `HEAD` 为原文与代码基准；繁体补齐的新键保留必要补译，逐项区分缺项修复与已有中文改写。其他功能既有改动保留，不整体撤销工作区文件。
- 原文基线固定为 `51b71df3c6898fcddb7160ccc3b0171b5b03fc26`。中文每日待办帮助保留原来的日期模板及“下午两点”示例，不应用下方旧决策中重写示例的做法；香港繁体统计原句中的逗号和词序独立保留。
- 中文兼容格式通过原资源实现，不回退外语复数、词形和日期输入顺序，也不重新引入此前发现的时长数值错误。

## 2026-10-11 — 恢复中文数量摘要的原有格式

- 用户指出首页文件夹与记事数量被 ICU 列表连接成“和”，要求保留原有中文内容；附件数量也受影响。此前将外语语法修复扩大为中文格式统一，超出了修改范围。
- 首页、文件夹卡片与小组件继续使用原中文计数资源；图片、视频、音频数量恢复原有空格、量词及英文逗号加空格；文件夹操作范围恢复原有顿号。简体、台湾和香港繁体同步保留各自原文。
- 不能全局替换 `LocalizedText.list` 的连接方式：不同中文场景原本使用不同分隔符，需要在相应调用处恢复，外语复数和语法修复保持有效。

## 2026-10-11 — 按术语与表达报告完成修订

- 用户已明确要求修复报告全部问题，统一产品术语、保留中文原意，并一并修改动态语法代码；继续保留 24 小时制，在原 OPPO、三星和魅族设备验证。
- Thing 总称延续 2026-09-28 词表；Note 类型采用德语 Notiz、法语 Note、日语 ノート、韩语 메모、俄语 заметка，中文统一“记录 / 記錄”。日语 Thing 仍为 メモ，与具体 Note 类型区分；俄语帮助同步区分 запись 和 заметка。
- 英语完成动作和状态使用 finish / finished；养成习惯使用 develop a habit。completion rate、技术流程 complete、图案锁 Done 和产品名按原语境保留。
- 周期时间将介词作为每个时间短语的一部分，由当前应用语言和实际小时选择，再组合列表；不修改 24 小时存储格式或提醒计算。
- 德语时长优先改写完整句式，避免把独立数量短语直接放入要求不同词形的介词后。俄语分享与日期列表兼顾自然表达和词形。
- 每日待办帮助使用与创建页相同的标题格式化入口；示例日期也跟随应用语言，不能在帮助中另写英文日期模板。
- 较长统计文案通过明确的两列约束和按内容增高显示，不为适配固定行高而删去“总次数 / 连续 / 周期”等含义。

本轮使用的类型术语如下；Thing 表示记事总称，Note 是其中不带提醒设置的具体类型。普通叙述中的“事情”不机械替换为产品名词。

| 语言 | Thing 总称 | Note 类型 |
|---|---|---|
| 英语 | Thing | Note |
| 简体中文 | 记事 | 记录 |
| 台湾、香港繁体 | 記事 | 記錄 |
| 德语 | Ding | Notiz |
| 西班牙语 | Cosa | Nota |
| 法语 | Élément | Note |
| 印地语 | चीज़ | नोट |
| 意大利语 | Elemento | Nota |
| 日语 | メモ | ノート |
| 韩语 | 항목 | 메모 |
| 葡萄牙语 | Item | Nota |
| 俄语 | Запись | Заметка |

## 2026-10-11 — 补译同时修复全应用的动态文案

- 用户在 `grill-me` 讨论中明确选择：本轮一起修复全应用中发现的动态文案问题，不限定于 2026-10-10 缺项清单涉及的功能。
- 范围包括已有资源键背后的错误拼接、给非中文统一追加英语 `s`、代码硬编码的英文时间说明，以及数量、语序和上下文词形问题。
- 用户随后确认：保持现有 24 小时制，日期顺序随应用语言调整，开始实施。不跟随系统切换为 12 小时制，不增加新的时间制式设置。
- 当前已核实的调用链与修复建议见 [实现风险检查](implementation-review-2026-10-11.md)。

## 2026-05-27 - ThingsActivity header collapse endpoint is measured, not density-guessed

`ActivityHeader` should not rely on hard-coded `scrollY * factor` values to
place the title inside the toolbar when the Things list collapses. Those factors
only matched the original Chinese/English text metrics on common toolbar
heights; they drift with other locales, fallback fonts, font scale, device
metrics, and any toolbar height variant.

Keep the legacy collapse distance and title scale timing, but compute the
collapsed header `translationY` from measured coordinates: toolbar vertical
centre minus the scaled title visual centre. Interpolate from `0` to that
measured endpoint while scrolling. Recompute after title text changes so locale
or drawer-category changes can update the endpoint.

## 2026-05-27 - App language selection uses AppCompat locales plus context wrapping

The old in-app language path mutated only `App.getApp().resources` through
`Resources.updateConfiguration(...)`. That is not a reliable Activity
localisation boundary after the Android 16 / AppCompat update, especially when
the selected app language differs from the system language.

Use a two-layer locale path instead:
- wrap `Application` and Activity base contexts from the stored app-language
  preference so resources are correct before layout inflation;
- keep `AppCompatDelegate.setApplicationLocales(...)` in sync so AppCompat and
  Android's per-app language machinery see the same locale.

Settings language preselection must compare saved language codes, not displayed
language names, because displayed names are locale-dependent and can belong to
the previous resource configuration.

## 2026-09-28 - 记事传输（记事文件、碰一碰）在 11 种语言里的用词

补齐 thing-transfer 的 79 条文案时定下的用词（由 agent 按各语言文件现有用法选定，未经用户或母语者审校）。之后给这个
功能或相邻功能加文案，照此选词，保持一致。

「记事」在各语言文件里本来就不统一：抽屉「所有记事」等早期文案多是 Dinge / cosas / choses / cose / coisas / вещи，
文件夹操作那批较新的文案（`home_state_*`、`home_structure_*`）用 Dinge / cosas / éléments / elementi / itens /
записи / メモ / 항목 / चीज़ें。这次跟随较新的那批：

| locale | 记事 | 记事文件 | 碰一碰（用户看到的发送） | 碰一碰（功能名：HCE 描述、调试开关） | 接收记事 |
|---|---|---|---|---|---|
| de | Ding / Dinge | Dinge-Datei | Senden per Antippen | Übertragung per Antippen | Dinge empfangen |
| es | cosa / cosas | Archivo de cosas | envío por toque | transferencia por toque | Recibir cosas |
| fr | élément / éléments | Fichier d'éléments | envoi par contact | transfert par contact | Recevoir des éléments |
| it | elemento / elementi | File di elementi | invio al tocco | trasferimento al tocco | Ricevi elementi |
| pt | item / itens | Arquivo de itens | envio por toque | transferência por toque | Receber itens |
| ru | запись / записи | Файл записей | отправка касанием | передача касанием | Получить записи |
| ja | メモ | メモファイル | タッチ送信 | タッチ転送 | メモを受信 |
| ko | 항목 | 항목 파일 | 터치 전송 | 터치 전송 | 항목 받기 |
| hi | चीज़ / चीज़ें | चीज़ों की फ़ाइल | टैप करके भेजना | टैप ट्रांसफ़र | चीज़ें प्राप्त करें |
| zh-rTW / zh-rHK | 記事 | 記事檔案 | 碰一碰 | 碰一碰 | 接收記事 |

其他约定：

- 设备：de Gerät、es / it / pt dispositivo、fr appareil、ru устройство、ja 端末、ko 기기、hi डिवाइस、繁体 裝置
  （量词随既有的「這部裝置」用「部」）。Wi-Fi 在 de 写 WLAN，hi 写 वाई-फ़ाई，其余保留 Wi-Fi；NFC 一律不译。
- 引号按各文件已有的习惯：de „…“，es / it / ru «…»，fr « … »（引号内侧与冒号前用不换行空格），pt / hi “…”，
  ja / 繁体 「…」，ko ‘…’。
- 繁体两套按 preferences.md 的对照表（导入→匯入、文件→檔案、文件夹→資料夾、设备→裝置、数据→資料）。两地分开的：
  TW 用「裡、傳送、連線、產生、暫存、辨識、偵錯版、鄰近的 Wi-Fi 裝置、位置資訊權限」，HK 用「裏、發送、連接、生成、
  臨時、識別、調試版、附近的 Wi-Fi 裝置、位置權限」。
- 不是 plurals 的计数串（如「已导入 %1$d 件记事」）在有单复数变化的语言里写成「名词: 数字」，避免 1 与多数的配合出错。
  要把复数片段拼进句子的 `tap_receive_done_folders` / `tap_receive_done_mixed`，es / fr / it / pt 用
  Has recibido / Vous avez reçu / Hai ricevuto / Você recebeu 起句（动词不随后面的数量变化），de 用不变的分词
  empfangen，ru 与 hi 用「Получено: …」「प्राप्त: …」。
- 已知局限：`tap_share_title` / `tap_share_title_folders` 是不带数字的标题，而俄语的 one 类包含 21、31……，
  选中 21 条时会显示单数的 Поделиться записью。
