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

**已知遗留**：两个繁体文件整体上是从简体轻度转换来的，还留着别的大陆用词（例如
「視頻」，港台惯用「影片」）。本次只统一了「介面」一词，其余未动，需要时再单独提出。

Motion Photo 的中文一律用**动态照片 / 動態照片**，不用苹果商标「Live Photo」
（见根目录 CONTEXT.md 的 Motion Photo 词条 _Avoid_ 一行）。

Exception authorized on 2026-05-27: Google Translate may be used for bulk
Simplified Chinese translation of the `meodai/color-names` colour-name dataset.
This exception is scoped to fine-grained colour-name labels only. English colour
names should keep the upstream source wording, and non-Chinese app locales may
fall back to English until explicitly translated later.
