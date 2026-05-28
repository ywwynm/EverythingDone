# Preferences

Operational rules — ADB invocation, Gradle invocation, toolchain paths —
have moved to `.claude/rules/`. This file holds **user preferences**
(workflow attitudes, principles, conventions) only.

## Communication

Use professional, concise Chinese in agent-user conversation. When updating
repository instruction, memory, planning, review, or analysis documents for
this project workflow, write those updates in English unless the target file is
explicitly a localisation resource or already requires another language.

## Workflow

**Never commit unless explicitly asked.** Successful compile ≠ feature
correctness — UI changes may still need visual review. Stage and commit
only after the user has tested and given explicit go-ahead (e.g. "now
commit", "commit this"). Reverting an unrequested commit was needed
once on 2026-05-18; avoid the same mistake. Applies even when code
compiles and tasks look "done".

For the debug APK update channel, do not use frequent internal debug publishes
as a reason to inflate the main Android `versionCode` in `app/build.gradle`.
Keep debug update cadence separate from the app's normal versioning policy.

Debug update release notes may be relatively long, similar in substance to a
commit message, so publishing should support reading notes from a file instead
of forcing everything through a single command-line property.

For publishing a debug update APK, use the dedicated Gradle task
`:app:publishDebugUpdate`. Do not treat ordinary `:app:assembleDebug` as the
publishing path.

Every `:app:publishDebugUpdate` invocation should include update notes. Prefer
`-PdebugUpdateNotesFile=...` for longer notes; use `-PdebugUpdateNotes=...`
only for short one-line notes.

After a debug change compiles successfully, default to publishing the resulting
debug APK to the configured Aliyun update server with `:app:publishDebugUpdate`
unless the user explicitly asks to keep it local.

## Commit messages

When writing bilingual commit messages, do not prefix paragraphs with
`EN:` / `中文：`. Do not include Gradle command output or APK verification
details in the commit message body. Add the standard collaborator trailer
used by recent commits:

`Co-authored-by: GPT-5.5 <gpt-5.5@openai.com>`

For substantive commits, follow the recent project style: use a bilingual
subject in the form `English / Chinese`, then write paired English and Chinese
body paragraphs that describe the same implementation decisions at a useful
review level.

## Localization

When adding or revising translations, use `values-zh-rCN/strings.xml` as the
source of truth. Do not use Google Translate for this project unless the user
explicitly re-authorizes it. Prefer direct agent-authored translations over
API-generated batches, especially for long Help/About text.

Exception authorized on 2026-05-27: Google Translate may be used for bulk
Simplified Chinese translation of the `meodai/color-names` colour-name dataset.
This exception is scoped to fine-grained colour-name labels only. English colour
names should keep the upstream source wording, and non-Chinese app locales may
fall back to English until explicitly translated later.

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

When in doubt, dump UI hierarchy first (see `.claude/rules/adb.md`).
Reach for screencap only when an actual pixel comparison is required.

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

## Dark mode dialog polish

When adding dark mode, do not stop at background resources. Dialogs,
popups, pickers, snackbars, and dialog-like activities need explicit
review of text, icons, ripple/pressed states, dividers, edit fields,
progress indicators, and disabled states so their foreground UI adapts
correctly in dark mode.

For app-owned long-running flows such as debug update downloads, prefer a
project `DialogFragment` that follows App Chrome light/dark styling and exposes
live progress details over a detached system-style progress surface.

## Button-like control ripple shape

When ImageView, TextView, FrameLayout, LinearLayout, RelativeLayout, or similar
plain views are used as button-like controls, their press/ripple feedback
should match the visual control shape instead of staying square. Text or
icon+text controls should use a pill-shaped rounded rectangle whose radius is
half of the control height. Icon-only controls should use a circular ripple.
Changing the ripple shape must not shift the visual position of existing text
or icons, and icon-only controls should keep the icon's visual size unchanged.
Ripple colours must adapt to Appearance Mode and to Thing Background ownership
where the control sits directly on a Thing Background.

Full-row, full-card, and full-width dialog action-row surfaces are not included
in this preference; they should not be reshaped as part of button-like control
ripple work.

Compact dialog text buttons, including affirmative "Got it" style buttons and
cancel/confirm pairs, are included in button-like control ripple work.

For gradient Thing Backgrounds, the ripple waveform can remain representative
single-colour feedback; do not introduce a custom gradient touch animation for
this button-like control pass.

Treat shaped ripple drawables as dynamic UI state. Reinstall or retint them
when a Thing Background changes and when an Activity handles light/dark
Appearance Mode changes in place.
