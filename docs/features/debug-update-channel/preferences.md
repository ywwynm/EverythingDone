# Debug Update Channel Preferences

Migrated from global `memory/preferences.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Debug APK update channel

For the debug APK update channel, do not use frequent internal debug publishes
as a reason to inflate the main Android `versionCode` in `app/build.gradle`.
Keep debug update cadence separate from the app's normal versioning policy.

Debug update release notes may be relatively long, similar in substance to a
commit message, so publishing should support reading notes from a file instead
of forcing everything through a single command-line property.

For debug app changes, use the dedicated Gradle task
`:app:publishDebugUpdate` as the default Gradle path unless the user explicitly
asks to keep the build local. Do not treat ordinary `:app:assembleDebug` as the
default publishing path; use it only as a local compile check or diagnostic
step.

Debug update notes should be written in Chinese by default. Keep code symbols,
file paths, Gradle task names, class names, and other proper technical names in
English where that is clearer.
Before every `:app:publishDebugUpdate` invocation, update
`memory/debug-update-notes.md` with a concise but comprehensive summary of the
conversation behind that debug build: the user's request, the agent's analysis,
important files and implementation changes, any user corrections after an
earlier attempt, the follow-up response, and relevant verification/publish
status. Prefer `-PdebugUpdateNotesFile=memory/debug-update-notes.md`; the
publish task embeds only the first/top `##` entry from that file into
`latest.json`, so keep the newest debug update entry at the top and leave older
entries as local history. Use inline `-PdebugUpdateNotes=...` only when
explicitly asked for a short inline note.
