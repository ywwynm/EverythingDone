# Preferences

Global startup preferences only. Feature-specific preferences live in `docs/features/<kebab-case-feature-slug>/preferences.md`.

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

When a broad UI sweep finds additional candidate omissions beyond the user's
explicitly reported bug, report those candidates first and wait for user
confirmation before modifying them.

When the user explicitly invokes `grill-with-docs`, treat it as a request to
stress-test and clarify the design before implementation. Even if the request
contains concrete implementation preferences, ask at least the remaining
product/design trade-off questions one at a time unless the user explicitly
asks to proceed directly to coding.

## Android dialogs

Prefer custom `DialogFragment` implementations under `app/src/main/java/.../fragments/`,
usually extending `BaseDialogFragment`, for in-app dialogs. Avoid constructing
raw `android.app.Dialog` instances directly inside Activities for feature UI.
If a dialog's content is tightly coupled to Activity state, use a thin custom
`DialogFragment` host rather than keeping the raw `Dialog` in the Activity.

## Documentation organization

For every new feature request or substantial technical initiative, create a
dedicated documentation directory under
`docs/features/<kebab-case-feature-slug>/`. Keep feature-specific planning,
review, analysis, execution checklists, and archived debug notes in that
directory. Do not add new feature plans to `docs/plans/`.

Keep `CONTEXT.md`, `docs/adr/`, and canonical `memory/*.md` files global.
Feature directories may link to those global documents but should not move or
duplicate their authority.

## Commit messages

When writing bilingual commit messages, do not prefix paragraphs with language
labels such as `English:`, `EN:`, `中文：`, or `Chinese:`. Do not include Gradle
command output or APK verification details in the commit message body. Keep the
repository's normal Git author/committer identity, and add the collaborator
trailer for the collaborating model that actually did the work. The project has
worked with both models below, so either format may appear in history (use the
one matching the model that wrote the commit, not both):

`Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`
`Co-Authored-By: GPT-5.5 gpt-5.5@openai.com`

For substantive commits, follow the recent project style: use a bilingual
subject in the form `English / Chinese`, then write paired English and Chinese
body paragraphs that describe the same implementation decisions at a useful
review level.

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

## Debugging and exception handling

Do not add silent exception catches to hide newly observed runtime crashes.
For RecyclerView/layout crashes and similar framework errors, fix the caller
state/update path so the error disappears; if an error still happens, it should
remain visible in crash logs rather than being swallowed by a new broad catch.
Existing legacy catches should not be expanded without an explicit product or
technical reason.

## Publishing and commits

When the user asks to "submit" during a debug-testing cycle, interpret it as
publishing a debug update, not creating a Git commit. Only create a Git commit
when the user explicitly asks for `commit`, `git commit`, or says the tested
version has no obvious bugs and is ready to commit.

For small bug fixes, after implementing the fix and running the appropriate
verification, publish the debug update directly by default. The user has granted
standing permission for the Gradle debug update publish task in this small-bug
workflow; still respect sandbox/escalation policy prompts if the environment
requires them.

Do not auto-install the debug APK onto the connected physical device (the
`BYZL…` serial). The user verifies on their own device remotely: after a
successful compile, publish a debug update to the Aliyun channel and let the
user test there. This matters especially for features that can only be confirmed
visually on real hardware (e.g. HDR rendering, which an emulator cannot show).

## Memory and feature documentation

- Keep `memory/*.md` lightweight and cross-feature. Do not store detailed feature implementation history here.
- Put feature-scoped preferences, decisions, follow-ups, sessions, execution notes, and debug-note archives under `docs/features/<kebab-case-feature-slug>/`.
- When working on a feature, read that feature directory after reading the global memory files.
- If a new note applies to multiple features or to agent behavior generally, record it in global `memory/*.md`; otherwise record it in the feature directory.

## Feature-scoped preference indexes

- `app-chrome-polish`: `docs/features/app-chrome-polish/preferences.md`
- `color-system-migration`: `docs/features/color-system-migration/preferences.md`
- `dark-mode`: `docs/features/dark-mode/preferences.md`
- `debug-update-channel`: `docs/features/debug-update-channel/preferences.md`
- `detail-attachment-media-appearance`: `docs/features/detail-attachment-media-appearance/preferences.md`
- `detail-color-sampling`: `docs/features/detail-color-sampling/preferences.md`
- `kotlin-migration`: `docs/features/kotlin-migration/preferences.md`
- `localization`: `docs/features/localization/preferences.md`
- `thing-folders`: `docs/features/thing-folders/preferences.md`
- `remote-thing-card-appearance`: `docs/features/remote-thing-card-appearance/preferences.md`
- `thing-background-editor`: `docs/features/thing-background-editor/preferences.md`
