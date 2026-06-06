# Kotlin Migration Preferences

Migrated from global `memory/preferences.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Kotlin migration header stamp

When translating a `.java` file to `.kt`, if the original file's top-of-file
Javadoc has a `Created by … on YYYY/M/D.` line, **insert** a
`Translated to Kotlin by ywwynm and Claude Opus 4.7 on YYYY/M/D.` line
immediately after it. Match the original `Created by` date format (slash
separators, no zero-padding, e.g. `2026/5/20` not `2026-05-20`). If the
original has no such line (e.g. files born after 2024 like
`ThingBackground.java`), do not invent one — skip the stamp. Established
2026-05-20 mid-migration; Group 1+2 backfilled retroactively.
