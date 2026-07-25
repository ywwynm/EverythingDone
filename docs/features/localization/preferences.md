# Localization Preferences

Migrated from global `memory/preferences.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Localization

When adding or revising translations, use `values-zh-rCN/strings.xml` as the
source of truth. Do not use Google Translate for this project unless the user
explicitly re-authorizes it. Prefer direct agent-authored translations over
API-generated batches, especially for long Help/About text.

When a new user-visible Settings key is added or revised for a feature, update
the same key in every currently supported non-default locale in the same pass
when the UI is expected to be localised. For feature dialogs, include the nearby
mode labels used by the same dialog so the screen does not become partially
localised.

## 繁體用詞（2026-07-25）

**两个繁体 locale（`values-zh-rTW` 与 `values-zh-rHK`）一律用「介面」，不用「界面」。**
软件 interface 这个义项上，港台用词一致（Apple / Microsoft / Google 的 zh-HK 与 zh-TW
本地化都用「介面」），「界面」是大陆用法、只留给 `values-zh-rCN`。2026-07-25 两个文件
各 26 处已全部替换完毕，此后新增文案直接用「介面」。

替换用 Edit 工具的 replace_all，**不得**用 PowerShell 改这类含中文的资源文件。

## 繁體用詞對照表（2026-07-25 全量扫描后确立）

两个繁体文件此前是从简体轻度转换来的，留着大量大陆用词。已按下表全量替换，**新增
繁体文案时照此表选词**，不要从 zh-rCN 直译。

| 大陆 | zh-rTW | zh-rHK | 备注 |
|---|---|---|---|
| 界面 | 介面 | 介面 | |
| 視頻 | 影片 | 影片 | |
| 音頻 | 音訊 | 音訊 | |
| 屏幕 / 全屏 | 螢幕 / 全螢幕 | 同 | |
| 鎖屏界面 | 鎖定畫面 | 鎖定畫面 | |
| 文件 | 檔案 | 檔案 | **文件夾 → 資料夾必须先替换**，否则会变成「檔案夾」 |
| 文本 | 文字 | 文字 | |
| 數據 / 元數據 | 資料 / 中繼資料 | 同 | |
| 信息 | 資訊 | 資訊 | |
| 默認 | 預設 | 預設 | |
| 設置 | 設定 | 設定 | |
| 設備 | 裝置 | 裝置 | |
| 組件 | 元件 | 元件 | |
| 程序 | 程式 | 程式 | |
| 應用（app） | 應用程式 | 應用程式 | 「小米應用商店」是专名，保留 |
| 用戶 | 使用者 | 使用者 | 主流厂商 zh-HK 亦用「使用者」 |
| 創建 | 建立 | 建立 | |
| 添加 | 新增 | 新增 | |
| 鏈接 | 連結 | 連結 | |
| 保存 | 儲存 | 儲存 | |
| 存儲 | 儲存 | 儲存 | |
| 激活 | 啟用 | 啟用 | |
| 撤銷 | 復原 | 復原 | |
| 視圖 | 檢視 | 檢視 | |
| 縮略圖 | 縮圖 | 縮圖 | |
| 對話框 | 對話方塊 | 對話方塊 | |
| 菜單 | 選單 | 選單 | |
| 圖標 | 圖示 | 圖示 | |
| 剪貼板 | 剪貼簿 | 剪貼簿 | |
| 布局 | 佈局 | 佈局 | |
| 硬件 / 網絡 | 硬體 / 網路 | **硬件 / 網絡（保留）** | 港台在此分歧 |
| 智能 | 智慧 | **智能（保留）** | 同上 |

**按语境区分、不能盲替换的词**：

- **支持**：technical support→「支援」（不支援的檔案類型、不支援農曆）；人际义保留
  「支持開發者」「您的支持」。
- **通過**：means-of→「透過」（透過 Email 發送）；pass-verification 保留「未通過驗證」。
- **應用**：作 app 名词→「應用程式」；专名「小米應用商店」保留。

**刻意未改**（不算错，改动收益低于风险）：

- 「點擊」：台港软件文案中通用，未改成「點選」。
- 「通知欄」：Android 中文本地化里「通知欄」通用，未改成「通知列」。
- 「恢復」：本项目里同时承担 resume（恢復習慣提醒）与 restore（恢復資料）两义，
  统一改成「還原」会伤到前者，故整体保留。

顺带修掉两个错字：zh-rTW 的「標凖」→「標準」，zh-rHK 的「恢複」→「恢復」。

Motion Photo 的中文一律用**动态照片 / 動態照片**，不用苹果商标「Live Photo」
（见根目录 CONTEXT.md 的 Motion Photo 词条 _Avoid_ 一行）。

Exception authorized on 2026-05-27: Google Translate may be used for bulk
Simplified Chinese translation of the `meodai/color-names` colour-name dataset.
This exception is scoped to fine-grained colour-name labels only. English colour
names should keep the upstream source wording, and non-Chinese app locales may
fall back to English until explicitly translated later.
