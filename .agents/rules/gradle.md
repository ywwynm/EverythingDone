# Gradle invocation rules

See [toolchain.md](toolchain.md) for the wrapper path.

## Sandbox escalation

Gradle wrapper invocations may require sandbox escalation in Codex sessions.
If an in-sandbox Gradle run is blocked, interrupted, or appears unable to run
normally because of the sandbox, rerun the same Gradle command with elevated
permissions and the appropriate Gradle command prefix.

## Standard PowerShell invocation pattern

Keep output compact so it stays inside the tool window:

```powershell
$out = & "E:\projects\EverythingDone\gradlew.bat" <task> 2>&1 | Out-String
$out -split "`n" |
    Where-Object { $_ -match "error:|warning:|BUILD " } |
    Select-Object -First 20
```

For deeper inspection, write the full output to a temp file under
`memory/` and grep separately:

```powershell
[System.IO.File]::WriteAllText("E:\projects\EverythingDone\memory\compile.txt",
                              $out, [System.Text.Encoding]::UTF8)
```

## Default debug task: `:app:publishDebugUpdate`

For debug app changes, the default Gradle task is now
`:app:publishDebugUpdate`, not plain `:app:assembleDebug`, unless the user
explicitly asks to keep the build local. The publish task assembles the debug
APK, injects the debug update code for the APK being published, generates
`latest.json`, uploads the versioned APK and metadata, and points the remote
debug channel at the new build.

Run a local `:app:assembleDebug` first only when it is useful as a faster
compile check or when diagnosing a build failure. A successful local assemble
does not replace the default publish step for a debug app change.

### Debug update notes

Do not run `:app:publishDebugUpdate` until `memory/debug-update-notes.md` has
been updated for the current change. The notes file should summarise the
conversation that led to the update:

- what the user asked for;
- what analysis or diagnosis was performed;
- what code or resource changes were made, including important file names and
  key implementation details;
- what follow-up concerns or corrections the user raised after seeing an
  earlier attempt;
- how those corrections were addressed;
- the verification or publish status when relevant.

The notes do not need to be exhaustive, but they should be comprehensive enough
that a tester reading the debug update understands the actual change history,
not just a one-line feature label.

Write debug update notes in Chinese by default. Keep code symbols, file paths,
Gradle task names, class names, and other proper technical names in English
where that is clearer.

Use the notes file path by default:

```powershell
.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
```

When the notes file contains multiple `##` entries, the publish task only
embeds the first/top entry into `latest.json`. Keep the newest debug update
entry at the top of `memory/debug-update-notes.md`; older entries may remain as
local history without being shown in the app update dialog.

Use inline `-PdebugUpdateNotes=...` only when the user explicitly asks for a
short inline note.

Use a forward-slash path for `-PdebugUpdateNotesFile` in PowerShell. A
Windows backslash path can be misparsed by the wrapper/Gradle command line and
show up as an extra task such as `.md` instead of a project property.

## Local compile task: `:app:assembleDebug`

**Not** `:app:compileDebugJavaWithJavac` and **not**
`:app:compileDebugKotlin`. Each successful compile should produce an
installable APK at `app\build\outputs\apk\debug\app-debug.apk` so the user
can sideload to a test device. The full assemble takes only a few seconds
longer than `compileJavaWithJavac` (most steps are FROM-CACHE / UP-TO-DATE).

The APK from a vanilla `assembleDebug` does **not** carry
`android:testOnly="true"` (that flag is only injected by Android Studio's
"Run" / instant-deploy path) — the project's `build.gradle` has no
`testOnly` config and the manifest doesn't set it either, so `adb install`
works on any device without `-t`.

## Don't pre-announce compile commands

After making non-trivial edits, just run the gradle task directly — no
"now I'll compile" message. The user reads the output, not the
announcement.
