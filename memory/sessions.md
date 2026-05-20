# Sessions

## 2026-05-20 — Kotlin migration Group 5 (App + FrequentSettings)

Translated 2 root-package files (~722 LoC):

- `FrequentSettings.java` (119 LoC) → `object FrequentSettings`
  per plan §3.3 S-3 (private ctor, all-static). The Java
  `static { loadFromSharedPreferences(); }` block became
  `init { loadFromSharedPreferences() }` inside the object
  (§3.3 S-4 — JVM `<clinit>` timing preserved). Map values
  cast via `as Boolean` / `as Long` / `as String?` (Java
  `(boolean) Object` unboxing semantics preserved — NPE on
  null receiver).

- `App.java` (603 LoC) → `open class App : Application()`
  with a `companion object` holding every static
  field/method. Mix of patterns:
  - Direct-access publics (Java callers do `App.isSearching
    = true` / `App.newThingColor`) → `@JvmField var`.
    Affects: `isSearching`, `runningDetailActivities`,
    `newThingBackground`, `newThingColor`.
  - Method-accessed privates with non-JavaBean getter
    names → keep explicit `@JvmStatic fun`. Affects:
    `isSomethingUpdatedSpecially()` / `setSomething…`
    (non-`is`-prefixed property would generate `getX`),
    `justNotifyAll()` / `setJustNotifyAll()` (no `is` or
    `get` prefix), `getApp()`, `getDoingThingId()` /
    `setDoingThingId()` (could be Kotlin property but kept
    explicit for symmetry).
  - Inside companion-object setters, accessing the
    shadowed property uses `this.x = x` (not
    `Companion.x = x` — the latter is the *outer-class*-
    qualified path and doesn't resolve from inside the
    companion itself).
  - Two anonymous Runnables (in
    `releaseResourcesAfterDeleteForever` /
    `deleteAttachmentFiles`) reference `App.this` for
    `Def.getAppFileDir(App.this)` and DAO `getInstance` —
    kept as `object : Runnable` with `this@App` per §3.5
    guard 3 (outer-this reference).
  - `selfHealAlarmsIfStale`'s `new Thread(Runnable, name)`
    constructor takes a Runnable with `App.this` access —
    same `object : Runnable` treatment.
  - `Handler.postDelayed`'s `System.exit(0)` Runnable has
    no outer reference; kept as `object : Runnable` for
    consistency rather than SAM lambda (minor).
  - `getParcelableExtra` is deprecated upstream (API 33+);
    added `@file:Suppress("DEPRECATION")` per §3.11.

Verifications:
- V1: BUILD SUCCESSFUL, 0 Kotlin warnings, APK assembled.
- V2: grep audit clean — N1 on every ref type, E1 (the
  one `==` is `temp.id == id` Long primitive compare,
  correct).
- V3: cold-start renders 26 things identically to
  baseline.
- V4 **required** for this group (logcat diff on
  cold-start):
  - Raw logcat: 53 baseline lines vs 53 post-translation
    lines; differences only in PID, APK install-path
    hash, Surface consumer name, and ImeTracker
    correlation ID (all system noise — PM regenerates
    install hashes on every reinstall, framework
    generates fresh IDs per process).
  - App-only filter (lines tagged `EverythingDone` or
    `com.ywwynm`): 11 lines vs 11 lines, identical after
    normalising the APK install-path hash.
  - Zero FATAL / VerifyError / ClassNotFoundException /
    NoSuchMethodError / NoClassDefFound / SQLiteException
    / RuntimeException across the new bytecode.

## 2026-05-20 — Kotlin migration Group 4 (database/)

Translated 6 DAO + DBHelper classes (~1900 LoC):
DoingRecordDAO, AppWidgetDAO, ReminderDAO, DBHelper, ThingDAO,
HabitDAO. Five are singletons with the canonical Java
double-checked-locking pattern (private ctor + static `sXxx` +
`getInstance(Context)`); DBHelper extends SQLiteOpenHelper.

Translation notes:

- Singleton pattern: `class Xxx private constructor(context)
  { … }` + `companion object { @JvmField var sXxx;
  @JvmStatic fun getInstance(context) }`. The
  double-checked-locking idiom translates verbatim with
  `synchronized(Xxx::class.java)`. HabitDAO's original used
  `synchronized(ReminderDAO.class)` — preserved verbatim as
  `synchronized(ReminderDAO::class.java)` (likely a bug, but
  behaviour snapshot).
- DBHelper SQL constants: `private const val SQL_*` for all
  pure-string CREATE/ALTER/DROP statements (Def.Database.X is
  `const val`, so concatenation is compile-time). The one
  exception is `SQL_INSERT_HEADER` which embeds
  `System.currentTimeMillis()` — declared as `private val`
  (not `const`) inside the companion object, init-once on
  class load just like Java's `static final`.
- Property-syntax rewrites for Group 2 model accessors:
  thing.id / type / state / location / content / attachment /
  createTime / updateTime / finishTime / title;
  reminder.id / notifyTime / state / notifyMillis /
  updateTime; habit.id / type / detail / record /
  intervalInfo / remindedTimes / createTime / firstTime /
  habitReminders / habitRecords; habitReminder.id /
  habitId / notifyTime; habitRecord.id / habitId /
  habitReminderId / recordTime / recordYear / recordMonth /
  recordWeek / recordDay / type. Methods that stay as
  `fun` in Group 2 (Thing.getColor / getBackground;
  habit.isPaused / getClosestHabitReminder /
  getFinalHabitReminder / getMinHabitReminderTime /
  initHabitReminders / getHabitRecordsThisT;
  doingRecord.shouldAutoStrictMode is a `Boolean` property
  with `@get:JvmName("shouldAutoStrictMode")`) keep their
  call-site form (property for shouldAutoStrictMode, method
  for the rest).
- Local-var promotion: `int backFrom` in
  `createFakeFinishedHabitRecord` had a conditional
  reassignment branch (`backFrom += timesEachT`) that
  required `var` (initial Kotlin draft used `val` → compile
  bug; caught before commit).
- `cursor`, `cursor2` declared as separate locals in
  `updateMaxHabitReminderRecordId` (Java had `Cursor c` and
  `Cursor c2`) — kept the same naming for textual mapping.
- The Java HabitDAO had `for-loop with continue` inside
  `updateStates` — translated to Kotlin `while` with
  manual `i++; continue` since Kotlin's `for in 0 until n`
  doesn't permit continuing to next iteration after
  conditional skip without restructuring.
- `ThingBackground.fromRandom()` returns `ThingBackground?`
  in Kotlin (N1); DBHelper `generateInsertInitialSQL`
  unwraps with `!!` (matches Java's NPE-on-null semantics).

Verifications:
- V1: BUILD SUCCESSFUL, 0 Kotlin warnings, APK assembled.
- V2: grep audit — N1 on every ref type, E1 (`===` for
  enum compares, `.equals()` for String compares); the
  remaining `==` are all Int/null/Char compares (verified
  by greppable filter).
- V3: cold-start renders 26 things including reminders
  ("May 17, 5:46, reminded"), habit cards ("3 times a
  month, Next reminder: on May 31"), and the empty
  finished-this-month indicator — all of which exercise
  ReminderDAO / HabitDAO read paths.
- V4 **required** for this group:
  - SQLite dump diff: baseline 50 INSERTs vs post-cold-
    start 50 INSERTs — byte-identical except the HEADER
    row (id 120→121, timestamps refreshed). This is the
    expected behaviour of `ThingDAO.recreateHeader()`
    which bumps HEADER id by 1 on every cold-start (Java
    behaviour preserved).
  - logcat clean: zero FATAL / VerifyError /
    ClassNotFoundException / NoSuchMethodError /
    NoClassDefFound / SQLiteException across the new
    bytecode.

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
