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

## Commit messages

When writing bilingual commit messages, do not prefix paragraphs with
`EN:` / `中文：`. Do not include Gradle command output or APK verification
details in the commit message body. Add the standard collaborator trailer
used by recent commits:

`Co-authored-by: GPT-5.5 <gpt-5.5@openai.com>`

## Localization

When adding or revising translations, use `values-zh-rCN/strings.xml` as the
source of truth. Do not use Google Translate for this project unless the user
explicitly re-authorizes it. Prefer direct agent-authored translations over
API-generated batches, especially for long Help/About text.

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
