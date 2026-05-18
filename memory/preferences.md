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

## Material FAB → fake-FAB

When a Material `FloatingActionButton` blocks gradient rendering
(`setBackgroundTintList` is single-int only), replace it with the
"fake-FAB" pattern used in `ColorPicker.FabViewHolder` / `color_picker_fab.xml`:
clipped-to-oval `FrameLayout` + inner background `View` carrying a
`GradientDrawable` + `setForeground(BackgroundUtil.circularRipple(...))`.
Outline and clipping installed in code via `setOutlineProvider`.
