# Preferences

## Workflow

**Never commit unless explicitly asked.** Successful compile ≠ feature
correctness — UI changes may still need visual review. Stage and commit
only after the user has tested and given explicit go-ahead (e.g. "now
commit", "commit this"). Reverting an unrequested commit was needed
once on 2026-05-18; avoid the same mistake. Applies even when code
compiles and tasks look "done".

**Don't pre-announce compile commands.** After making non-trivial Java
edits, just run `./gradlew.bat :app:compileDebugJavaWithJavac` (or
relevant gradle task) directly — no "now I'll compile" message. The
user reads the output, not the announcement.

**Gradle commands are pre-allowed in `.claude/settings.local.json` via
`PowerShell(*gradlew*)`.** No need to ask permission; the rule covers
any compile / build / assemble invocation. Use the standard pattern
`$out = & .\gradlew.bat <task> ... 2>&1 | Out-String; $out -split "`n"
| Where-Object { $_ -match "error:|BUILD " } | Select-Object -First N`
so output stays compact.

**Default compile task: `:app:assembleDebug`, not
`:app:compileDebugJavaWithJavac`.** Each successful compile should
produce an installable APK at
`app\build\outputs\apk\debug\app-debug.apk` so the user can sideload
to a test device. The full assemble takes only a few seconds longer
than compileJavaWithJavac (most steps are FROM-CACHE / UP-TO-DATE).
The APK from a vanilla `assembleDebug` does **not** carry
`android:testOnly="true"` (that flag is only injected by Android
Studio's "Run" / instant-deploy path) — verified the project's
`build.gradle` has no `testOnly` config and the manifest doesn't set
it either, so `adb install` works on any device without `-t`.

## Color migration & UI gradients

**Principle: "If it can render gradient, make it render gradient."**
When migrating UI elements to the `ThingBackground` model, propagate
the full `ThingBackground` signal (not just `representativeColor()`)
to any view whose Android API permits a `Drawable`, `Shader`, or
custom-painted background. Only fall back to `representativeColor()`
when the platform API strictly accepts a single int (PorterDuff tints,
`RippleDrawable` `ColorStateList`, `EdgeEffect.setColor`,
`Notification.setColor`, FAB `setBackgroundTintList`, `setHighlightColor`,
cursor tint, `ProgressBar` tint).

**Ripple waveform** is an accepted single-int compromise:
`RippleDrawable` `ColorStateList` cannot hold a gradient — the
"water-ripple color" itself stays representative. The fake-ripple
alternative (`onTouch` + manual `GradientDrawable` scale animation)
is on the backlog as a follow-up iteration, not current scope.

## Screenshot frugality

Each emulator screenshot costs ADB `screencap` + `pull` + file
transfer + the vision-token cost of `Read`-ing the PNG. Don't take
them at every micro-step during exploratory or navigation work.

**Take a screenshot when**:
- One-time Phase 0 baseline capture (per scene)
- End-of-group V3 verification (per planned scene the group should
  have touched)
- A `adb input tap` had uncertain effect and a visual is the only
  reliable confirmation

**Don't take a screenshot when**:
- The expected state is unambiguous and downstream commands don't
  branch on a visual
- A `uiautomator dump` (plain text, ~10 KB vs ~150 KB PNG plus
  vision tokens) would confirm the same thing
- Multiple intermediate steps follow before the next decision point
  — capture only at the decision point

When in doubt, dump UI hierarchy first. Reach for screencap only
when an actual pixel comparison is required.

## Use uiautomator dump for precise tap coordinates

For any actionable UI element (toolbar icon, button, menu item, list
row), do **not** estimate coordinates from the screenshot — the
displayed image is rescaled (see next section) and visual estimation
routinely misses by 50-100 device pixels. Use the View hierarchy dump
instead:

```powershell
& $adb -s emulator-5554 shell uiautomator dump /sdcard/window.xml
& $adb -s emulator-5554 pull /sdcard/window.xml $localPath
```

Then grep for `resource-id` / `content-desc` to find the target, read
its `bounds="[x1,y1][x2,y2]"` attribute, and tap at `((x1+x2)/2,
(y1+y2)/2)`. These are real device pixel coordinates, ready for
`adb shell input tap`.

Reserve visual estimation only for non-actionable targets (list-item
contents at known but unstable scroll positions). For everything in
an action bar, drawer, dialog, or menu — always dump first.

## Read-tool image scaling vs adb tap coordinates

When the Read tool displays an Android screenshot, it **rescales** the
PNG for the model's vision channel — original `1280×2856` is shown at
`896×2000` (scale factor **1.43**). `adb shell input tap X Y` always
uses **original device-pixel coordinates** (1280×2856 for this Pixel
10 Pro emulator).

Workflow when picking a tap target from a screenshot read by the Read
tool:

1. Identify target's pixel position in the *displayed* image — call
   it `(dx, dy)`.
2. Multiply by **1.43** to get device coords: `(dx * 1.43, dy * 1.43)`.
3. Pass those to `adb shell input tap`.

If a tap lands on the wrong item, do **not** retry blindly — read back
the resulting screenshot, identify the actual item hit (record its
device-y), and use that as a known anchor to re-derive spacing for the
intended item.

## ADB screenshot capture (Windows PowerShell)

**Never** pipe `adb exec-out screencap -p > file.png` in PowerShell — `>`
re-encodes the byte stream to UTF-16 with BOM, corrupting the PNG (first
bytes become `FF FE FD FF 50 00 4E 00` instead of `89 50 4E 47`).

Use the **on-device file + pull** pattern instead:

```powershell
& $adb -s emulator-5554 shell screencap -p /sdcard/cap.png
& $adb -s emulator-5554 pull /sdcard/cap.png $localPath
& $adb -s emulator-5554 shell rm /sdcard/cap.png
```

Verify with `[BitConverter]::ToString((Get-Content $localPath -Encoding Byte -TotalCount 8))`
— must start with `89-50-4E-47`.

## Kotlin migration header stamp

When translating a `.java` file to `.kt`, if the original file's top-of-file
Javadoc has a `Created by … on YYYY/M/D.` line, **insert** a
`Translated to Kotlin by ywwynm and Claude Opus 4.7 on YYYY/M/D.` line
immediately after it. Match the original `Created by` date format (slash
separators, no zero-padding, e.g. `2026/5/20` not `2026-05-20`). If the
original has no such line (e.g. files born after 2024 like
`ThingBackground.java`), do not invent one — skip the stamp. Established
2026-05-20 mid-migration; Group 1+2 backfilled retroactively.

## Material FAB → fake-FAB

When a Material `FloatingActionButton` blocks gradient rendering
(`setBackgroundTintList` is single-int only), replace it with the
"fake-FAB" pattern used in `ColorPicker.FabViewHolder` / `color_picker_fab.xml`:
clipped-to-oval `FrameLayout` + inner background `View` carrying a
`GradientDrawable` + `setForeground(BackgroundUtil.circularRipple(...))`.
Outline and clipping installed in code via `setOutlineProvider`.
