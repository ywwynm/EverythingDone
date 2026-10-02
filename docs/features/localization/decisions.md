# Localization Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

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
