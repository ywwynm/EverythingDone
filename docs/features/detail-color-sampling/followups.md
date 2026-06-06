# Detail Color Sampling And Information Followups

Migrated from global `memory/followups.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Detail colour sampling and information - Device QA for CameraX colour sampling and colour information (deferred 2026-05-28)

**Scope:** DetailActivity's editable ColorPicker "Pick from world" camera
colour-sampling entry, full-width rounded-square CameraX preview dialog with
an internal live colour preview strip, final Use Color commit semantics, and
the colour-information overflow action across create, underway, habit,
finished, and deleted Detail states.

**Current state:** Implementation is present and `:app:assembleDebug` passed
outside the sandbox after Android Studio sync and after the follow-up UI
refinement. The APK was produced at
`app/build/outputs/apk/debug/app-debug.apk` on 2026-05-28 09:01:42.

**Deferred verification:** Install on a real device or emulator and verify the
camera permission prompt, preview orientation, centre sampling marker, dialog
colour preview strip, Use Color text tint, Cancel/Back/outside dismissal
without Detail-side background changes, single undo entry on Use Color, and
colour-info formatting/scrolling for pure and gradient Thing Backgrounds.

**Risk:** CameraX behaviour is device-sensitive. Compile proves API wiring but
not camera selection, frame sampling correctness, preview rotation, or dialog
layout on varied aspect ratios.
