# Gradle invocation rules

See [toolchain.md](toolchain.md) for the wrapper path.

## Pre-allowed in settings

Gradle commands are pre-allowed in `.claude/settings.local.json` via
`PowerShell(*gradlew*)`. No need to ask permission — the rule covers any
compile / build / assemble invocation.

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

## Don't pre-announce compile commands

After making non-trivial edits, just run the gradle task directly — no
"now I'll compile" message. The user reads the output, not the
announcement.

## 发布阿里云 debug：必须传更新日志属性

`:app:publishDebugUpdate` 的应用内更新日志（latest.json 的 releaseNotes）
**完全依赖** gradle 属性 `-PdebugUpdateNotesFile`（路径相对仓库根）；不传则
latest.json 没有日志字段，应用内看不到任何更新说明（2026-07-11 实际发生过，
连续四次发布无日志）。标准调用：

```powershell
& "E:\projects\EverythingDone\gradlew.bat" :app:publishDebugUpdate --no-configuration-cache `
    "-PdebugUpdateNotesFile=docs/features/<slug>/debug-updates/update-<时间戳>.md"
```

任务只提取该文件的**第一个 `## ` 条目**作为日志。发布后核对
`app/build/**/latest.json` 含 `releaseNotes` 字段，并把发布号 + APK SHA-256 回填
到 `memory/debug-update-notes.md` 顶部条目。
