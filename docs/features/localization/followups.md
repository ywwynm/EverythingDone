# Localization Followups

Migrated from global `memory/followups.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-10-11 — 术语、错义及表达审查后的修订（已完成）

- 用户随后授权修复报告全部问题。30 组、333 个语言条目全部处理：332 条修订，德语原本正确的 `Alle Dinge` 复核后保留。
- 已统一 finish / finished 与各语言产品术语，修正错义、损坏文本、相关菜单、通知、帮助、分享和无障碍描述；西意葡 1 点介词、俄语周数宾格、每日待办标题等需要代码配合的语法已修改，24 小时制保持不变。
- 统计长文案布局及截图发现的俄语完成率、四语输入提示同步修复。13 套资源静态检查通过；最终 debug `202610110559` 在 OPPO、三星、魅族分别通过 5,153 / 5,067 / 5,067 项检查，数据库和语言设置一致。详细处理清单、完整语言覆盖和验证边界见 [修订记录](wording-fix-2026-10-11.md)。

## 2026-10-11 — 本轮译文与历史译文的母语审校

- 资源缺项、动态文案和报告内术语语义问题已完成修订；当前 1,600 个中文可翻译资源对应的其他语言缺项均为零，构建和三机检查通过。详见 [修订记录](wording-fix-2026-10-11.md)。
- 外语译文由代理按中文和实际调用语境编写及修订；仍可由母语者复核语气与长说明。历史译文未逐句全面重译，不能将本次术语审查和机器检查等同于整份译文的母语审校。
- 三机合并覆盖全部语言的格式化、日期弹窗及重点清单与统计控件；其余页面、超大字体和 TalkBack 实际朗读属于后续体验审校。

## 2026-10-10 — 补齐资源缺项与卡片外观英文残留（2026-10-11 已完成）

- 以下保留初次审计时的范围。用户随后要求实施，已于 2026-10-11 补齐。原始清单见 [多语言文案完整性报告](coverage-audit-2026-10-10.md)。
- 英文无缺键；香港、台湾繁体各缺 136 个；德、西、法、印地、意、日、韩、葡、俄语各缺 252 个，去重共 259 个。缺项中有 11 个共用技术名称或标识，58 个未发现主应用直接引用，不能直接按总数安排翻译。
- 优先复核有直接引用且非共用标识的缺项：两套繁体各 74 个，九种外语各 183 个。另有 30 个卡片外观键在九种外语中共同沿用英文，需按语种区分真实未译与正确同形词。
- 两套繁体的 3 个旧文件夹确认文案缺少中文新增的信息和参数；未发现直接引用，先确认用途再同步或清理。
- 后续补齐仍以简体中文为准，遵守 `preferences.md` 的翻译来源和繁体用词约定；本次未做完整的语义审校和设备排版验证。

## Detail colour sampling and information - Native review for machine-translated Simplified Chinese colour names (deferred 2026-05-28)

**Scope:** The bundled `color-name-list` 14.38.0 TSV now has a populated `zh`
column for Simplified Chinese translations of the upstream English colour
names.

**Current state:** All 31,902 colour-name rows were translated with Google
Translate on 2026-05-28. The first successful batches used
`translate.googleapis.com`; after that endpoint returned HTTP 429, the
remaining rows were translated through Google Translate's mobile web endpoint
with stable marker parsing. The runtime can now show Chinese colour names in
Chinese locales.

**Deferred verification:** Native-speaker and colour-domain review of machine
translations. Some upstream names are puns, brands, place names, or invented
labels, so Google Translate can produce literal or mixed English/Chinese names.

## Localization - Native-speaker review for new app languages (deferred 2026-05-27)

**Scope:** Japanese, Korean, Italian, Spanish, Russian, French, German, Hindi,
and Portuguese string resources, especially long Help/About copy.

**Current state:** Resources compile, visible Google-translation protection
tokens were removed, and the long Help strings were reworked from the
Simplified Chinese source instead of from the failed Google batch output.

**Deferred verification:** Have native speakers review terminology, tone, and
long-form Help readability. Also smoke-test Settings language switching on
device across a few screens whose Activities do not share the common base class.

**Reason deferred:** The current session could verify build/resource validity,
but not human-level localization quality or on-device locale switching visuals.
