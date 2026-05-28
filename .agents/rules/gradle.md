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

## Default compile task: `:app:assembleDebug`

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

## Debug update publish task: `:app:publishDebugUpdate`

When the goal is to publish a debug update APK to the configured Aliyun static
update channel, use `:app:publishDebugUpdate` instead of `:app:assembleDebug`.
That task assembles the debug APK, injects the debug update code for the APK
being published, generates `latest.json`, uploads the versioned APK and
metadata, and points the remote debug channel at the new build.

Do not run the publish task without update notes. Prefer a notes file when the
content is more than a short sentence:

```powershell
.\gradlew.bat :app:publishDebugUpdate -PdebugUpdateNotesFile=memory\debug-update-notes.md
```

For a very short note, pass it inline:

```powershell
.\gradlew.bat :app:publishDebugUpdate -PdebugUpdateNotes="Describe the debug update"
```

## Don't pre-announce compile commands

After making non-trivial edits, just run the gradle task directly — no
"now I'll compile" message. The user reads the output, not the
announcement.
