# Sessions

## 2026-05-20 — Kotlin migration Group 3 (utils/) + retroactive header stamps

Translated 14 Java utility classes (~4537 LoC) under
`app/src/main/java/com/ywwynm/everythingdone/utils/` to Kotlin
`object`s: FontCache, StringUtil, DeviceUtil, EdgeEffectUtil,
KeyboardUtil, LocaleUtil, ThingsSorter, UriPathConverter,
BitmapUtil, DisplayUtil, SystemNotificationUtil, FileUtil,
BackgroundUtil, DateTimeUtil. All `static` Java methods became
`@JvmStatic fun`; `static final` primitives/Strings became
`const val`; pure-utility singletons (private ctor, only-static
members) became Kotlin `object` per plan §3.3 S-3.

Three families of issue surfaced and were resolved:

1. **Property-syntax rewrite at call sites** — Group 2 model
   classes (Thing / Reminder / Habit) translated their unprefixed
   Java fields to Kotlin `var` properties via plan §7.3 Option 3.
   Callers in utils/ that wrote `thing.getType()` had to become
   `thing.type` (and the same for id/state/content/attachment/
   location/updateTime/finishTime/notifyTime/habitReminders).
   Mechanical fix per plan §3.9 last row. Methods that stayed as
   real Kotlin `fun` (Thing.getColor / getBackground /
   getTitleToDisplay / isPrivate — they have side-effect setters
   or hand-rolled logic) keep method-form call sites.

2. **Java-API deprecation warnings** — Display.getDefaultDisplay /
   getRealSize / getSize, Drawable.setColorFilter(Int, Mode),
   Notification.PRIORITY_*, Locale(String, String),
   Resources.updateConfiguration, InputMethodManager.SHOW_FORCED,
   NotificationCompat.WearableExtender.setBackground all surface
   as Kotlin warnings (Java only emitted a single -Xlint summary).
   Group 2 had zero of these. Per plan §5 V1's "0 warnings" bar,
   added `@file:Suppress("DEPRECATION")` at the top of
   BackgroundUtil / DisplayUtil / KeyboardUtil / LocaleUtil /
   SystemNotificationUtil. New translation rule documented in
   plan §3.11. Behaviour-snapshot is preserved (same Java APIs,
   same arguments).

3. **Mechanical fixes for Kotlin strictness** —
   - `arrayOf<String?>(...)` for varargs into Java methods that
     accept `String[]` → use `arrayOf<String>(...)` (reflection
     method names are non-null literals; the nullable wrapper
     mismatch warns).
   - `java.util.List<...>` typed local with `java.util.ArrayList()`
     init → use `MutableList<...>` typed local (Kotlin doesn't
     widen `ArrayList` → `kotlin.collections.List` here).
   - `WindowInsetsAnimationCompat.Callback.onProgress` `running`
     param → `MutableList<Animation>` not `java.util.List<...>`.
   - String `==` regressed from Java `.equals()` reverted to
     `.equals()` form per §3.2 textual-mapping rule (behaviour
     identical either way, but `.equals()` keeps line-by-line
     reviewability).
   - `Drawable.unwrap(bg)` where bg is `Drawable?` → assert
     `bg!!` to preserve Java's NPE-on-null behaviour.

Verifications:
- V1 BUILD SUCCESSFUL, 0 Kotlin warnings, APK assembled.
- V2 grep audit clean for N1/E1/S-* on all 14 .kt files.
- V3 visual smoke (cold-start home + drawer open) — both render
  identically to baseline modulo settle-time differences.
- V4 sampled — logcat clean on cold-start, no FATAL /
  VerifyError / ClassNotFound / NoSuchMethod / NoClassDef.

Also retroactively stamped
`Translated to Kotlin by ywwynm and Claude Opus 4.7 on 2026/5/20.`
into every translated `.kt` file's top-of-file Javadoc (per new
user rule). 29 files total (15 from Group 1+2 + 14 from Group 3);
ThingBackground.kt has no `Created by` line so skipped (rule
documented in preferences.md).

## 2026-05-18 — Phase 8 cont'd: dialog downstream gradient propagation

User reported `DateTimeDialogFragment` (and other `mAccentColor`-driven
dialogs: AudioRecord, HabitDetail, HabitRecord) had not adopted the
thing gradient — the top-level dialog chrome was migrated in Phase 8
round 1 but the downstream views (InputLayout, TimeOfDayRecAdapter,
RecurrencePickerAdapter, DateTimePicker callers) still consumed
`mAccentColor` int only.

Done:
- `InputLayout` / `TimeOfDayRecAdapter` / `RecurrencePickerAdapter`
  each gained `setAccentBackground(ThingBackground)`.
- `DateTimeDialogFragment` propagates `mAccentBackground` to all 8
  InputLayout instances, both DateTimePickers, the TimeOfDayRecAdapter
  and the three RecurrencePickerAdapters; `mEtTimeAfter` focus
  listener installs the gradient shader on focus.
- `RecurrencePickerAdapter` NORMAL holder rewritten as fake-FAB
  (`recurrence_picker_normal.xml` + adapter rewrite) so picked cells
  render real gradients.
- `AudioRecordDialogFragment` `mBase` voice-visualizer backplate uses
  `applyBackground`; visualizer waveform + EditText tints stay int.
- `HabitDetailDialogFragment` / `HabitRecordDialogFragment` title and
  button text use `applyTextBackground`.

Commit `a984b91`. 8 files, +305/-40.

Earlier in same session: `Add Claude Code project config and agent skills`
(`9f91693`) — set up CLAUDE.md, .claude/ statusline, Matt Pocock skills
docs, and switched local checkout from `migration/android-16` to `master`
post PR-merge.
