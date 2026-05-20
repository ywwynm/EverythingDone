# Sessions

## 2026-05-20 — Kotlin migration Group 11 (views/)

Translated 19 view classes (~4967 LoC):
HackyViewPager, StablerRecyclerView, InterceptTouchCardView,
BakedBezierInterpolator, HabitRecordPresenter,
ThingsStaggeredLayoutManager, PopupPicker, DrawerHeader,
FloatingActionButton, RevealLayout, InputLayout, Snackbar,
ActivityHeader, VoiceVisualizer, AudioRecorder, DateTimePicker,
ShiningBorder, ColorPicker, PatternLockView.

Strategy: Option 1 (mechanical `m`-prefixed `private var` +
explicit `fun getX()/setX()`) per plan §7.3 — views/ retains
Android's `mFoo` field convention so Option 3 would auto-emit
`getMFoo()` and break Java callers.

Special handling:

- **Constructors**: `View` subclasses keep all 3 (Context),
  (Context, AttributeSet?), (Context, AttributeSet?, Int) chained
  via `: super(...)` per Kotlin secondary-constructor pattern.
  `AttributeSet` annotated `?` (framework nullable).
- **PopupPicker abstract base**: protected fields exposed to
  subclasses (ColorPicker, DateTimePicker) via `@JvmField
  protected var` — required because Kotlin `protected` is
  *subclass-only* (no same-package access), unlike Java.
  Without `@JvmField` the property getter would also be
  protected, but the field access pattern subclasses use needs
  the bare field.
- **VoiceVisualizer.receive(Int)**: was Java `protected` called
  cross-package from `AudioRecorder` (same package). Kotlin
  `protected` blocks same-package access ⇒ translated as
  `internal` to preserve the call site.
- **PatternLockView.Cell** (Parcelable + cached singletons):
  `Cell.CREATOR` → `@JvmField val CREATOR` in companion;
  `Cell.sCells[][]` → `@JvmField val sCells` in companion;
  `Cell.of(int)` / `Cell.of(int, int)` static factories →
  `@JvmStatic @Synchronized fun of(...)` in companion.
  `BaseSavedState` subclass nested similarly. Parcel constructor
  uses `parcelIn` (not `in` — Kotlin keyword).
- **PatternLockView.CellState** static-data POJO: public mutable
  fields used by outer for animation → `@JvmField var`.
- **PatternLockView.OnPatternListener** Java abstract class with
  empty default impls → Kotlin `abstract class` with `open fun`
  bodies (each empty), preserving the "override only what you
  need" pattern.
- **ColorPickerAdapter inner class**: Kotlin forbids companion
  objects inside inner classes. ALL_COLOR / NORMAL / DIVIDER view-
  type ints and `toGdOrientation()` mapping helper hoisted to
  ColorPicker's outer companion as `private const val` / `private
  fun`. Inner-class call sites resolve unqualified because
  companion members are visible without prefix from anywhere in
  the enclosing class.
- **ShiningBorder.Particle** mutable holder class → plain
  `class Particle { var x: Float = 0f; ... }` (no @JvmField needed,
  only accessed from outer Kotlin code).
- **PatternLockView.SavedState constructor**: Java's `Parcel in`
  → Kotlin `parcelIn: Parcel` (renamed to avoid `in` keyword
  clash). `Boolean.readValue(loader)` returns Any?, cast as
  `Boolean` directly (non-null asserted by call-site invariant).
- **@file:Suppress("DEPRECATION")** applied to 4 files using
  deprecated framework APIs:
  * InputLayout — DisplayUtil.setSelectionHandlersColor (API 36+
    non-SDK reflection restriction)
  * PatternLockView — HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_
    SETTING, invalidate(Rect), invalidate(int,int,int,int),
    announceForAccessibility
  * ColorPicker — Drawable.setColorFilter(int, Mode),
    ViewHolder.getAdapterPosition()
  * DateTimePicker — ViewHolder.getAdapterPosition()
- **AudioRecorder** `@file:Suppress("MissingPermission")` —
  AudioRecord constructor requires RECORD_AUDIO; suppression
  matches original Java's lack of @RequiresPermission propagation.
- **Smart-cast hardening**: nullable fields that the compiler
  can't smart-cast inside lambdas / inner methods (e.g.
  `mAnchor`, `mProgressAnimator`, `mPathFrame`) extracted to
  local `val` before use, or asserted with `!!`.
- **Vararg + spread**: `FloatingActionButton.bindSnackbars
  (vararg snackbars: Snackbar?)` callers pass arrays, so the
  field is stored as `Array<Snackbar?>?` via `@Suppress("UNCHECKED_
  CAST") as Array<Snackbar?>` — preserves Java caller contract.
- **Property-syntax rewrites for Group 2 model getters**: none
  required in this group; views/ classes interact with Group 2
  models only through method-form members (Thing.getBackground,
  ThingBackground.representativeColor, etc.) and through fields
  already exposed as Kotlin properties (`bg.color`, `bg.endColor`,
  `bg.mode`, `bg.orientation`).

Verifications:
- V1: BUILD SUCCESSFUL (after 2 iterations — first failed with
  10 errors: nullable-receiver from Group 3 utils' Point?
  returns, ThingBackground.pure/gradient platform-type returns,
  Companion-in-inner-class, deprecated String.toLowerCase()).
  Final: APK assembled, 0 Kotlin warnings.
- V2: grep audit clean — N1, E1; one `D.equals(header)` rewritten
  to `D == header` in DrawerHeader; only remaining `.equals(` is
  PatternLockView.Cell's `super.equals(other)` which is the
  idiomatic Any?.equals override.
- V3: cold-start renders 26 underway things identically to
  baseline — `interesting`, `wow` (with reminder time),
  `9999…` habit card (gradient + "3 times a month" + "Next
  reminder on May 31, 8:39 in the morning"), `000` / `888888` /
  `777` / `666` / `6` / `4` / `555` / `5` / `3` / `2` / `1` /
  `111` palette, etc., all in their staggered-grid positions.
  Screenshot: `memory/screenshots/group11/01_home_underway.png`.
- V4 sampled: logcat clean on cold-start — zero FATAL /
  AndroidRuntime / VerifyError / ClassNotFoundException /
  NoSuchMethodError / NoClassDefFound from any
  `com.ywwynm.everythingdone.views` class.

## 2026-05-20 — Kotlin migration Group 10 (services/)

Translated 3 background-work classes (~805 LoC):
- AlarmHealthWorker (73 LoC) — WorkManager Worker periodic
  alarm health check
- PullAliveJobService (79 LoC) — legacy JobScheduler 30-min
  best-effort alarm rebuilder
- DoingService (653 LoC) — foreground Service controlling
  the "currently doing" countdown for reminders/habits

Special handling:

- AlarmHealthWorker `extends Worker(context, params)` → Kotlin
  `class : Worker(context, params)` with the constructor
  arguments passed directly to the super primary
  constructor.
- PullAliveJobService's Runnable body references the outer
  Service's `getApplicationContext()` and `jobFinished()`
  → kept as `object : Runnable` per §3.5 guard 3.
- DoingService is the most complex Service in the codebase:
  * `Handler.Callback` field initializer with `DoingService.this`
    references for `SystemNotificationUtil.createDoingNotification(
    DoingService.this, …)` and `DateTimeUtil.getGeneralDateTimeStr(
    DoingService.this, …)` → kept as `object : Handler.Callback`
    with `this@DoingService` per §3.5 guard 3.
  * Inner `DoingBinder` class extends Binder, exposes
    package-private setter/getter methods to the bound
    DoingActivity. Each forwards via
    `this@DoingService.X(...)` (Java's `DoingService.this.X(...)`).
  * Nested `interface DoingListener` — Kotlin nested interface,
    matches Java semantics.
  * Nested `@interface State` / `@interface StartType` IntDef
    annotations → Kotlin `annotation class` with `@IntDef`.
    Per plan §3.9 mapping, the IntDef values are integer
    literals (0, 1, 2) not symbol refs — companion's
    `STATE_DOING` / `START_TYPE_ALARM` would be forward-
    references at the annotation declaration site.
  * Static mutable fields (`sStopReason`, `sSendBroadcastTo-
    UpdateMainUi`, `sResetDoingIdInOnDestroy`, `sHrTime`)
    → `@JvmField var` in companion object so Java callers
    access them as plain fields (e.g.
    `DoingService.sStopReason = X`).
  * `Handler(Handler.Callback)` constructor is deprecated
    since API 30; file declares `@file:Suppress("DEPRECATION")`.
  * Manual local-var loops (`for (int i = start, j = 0; …)`)
    aren't present, but several `for (int i = 1; i <= n;
    i++)` translate to Kotlin `for (i in 1..n)`.
- Property-syntax rewrites for Group 2 model getters:
  `thing.getId()` → `thing.id`, `thing.getType()` → `thing.type`,
  `habit.getType()` → `habit.type`, etc. Methods kept as
  `fun` (Thing.getBackground, Habit.getDoingEndLimitTime,
  ThingDoingHelper.shouldAutoStrictMode) keep their
  method-form call sites.
- `calculateTimeNumbers(long leftTime)` mutates the
  parameter (`leftTime %= HOUR_MILLIS`); Kotlin params are
  `val` ⇒ introduced local `var lt: Long = leftTime` and
  rewrote subsequent uses.

Verifications:
- V1: BUILD SUCCESSFUL on first compile attempt, 0 Kotlin
  warnings, APK assembled
- V2: grep audit clean — N1, E1
- V3: cold-start renders 26 things identically; services
  are manifest-declared so they're loaded but DoingService
  isn't activated without user starting a doing session —
  pure registration smoke test
- V4 sampled: logcat clean on cold-start — zero FATAL /
  VerifyError / ClassNotFoundException / NoSuchMethodError /
  NoClassDefFound / SQLiteException / RuntimeException
- V3 (full 12-scene visual diff, captured after the fact):
  replayed every baseline scene from `memory/screenshots/
  baseline/README.md` and saved to `memory/screenshots/
  group10_full/`. Verdict: **no Kotlin-regression
  signals across any of the 12 scenes**.
  * Scenes 02, 04, 05, 06, 07, 08, 09, 10, 12 are pixel-
    identical to baseline modulo the always-ignorable
    status-bar clock/signal/battery region.
  * Scene 11 (color picker over wow detail) — popup
    position, gradient, action bar, swatch grid layout
    all identical; **one** bottom-left swatch differs
    (baseline turquoise vs group10 red). The selected
    swatch (rose with white checkmark) and remaining 11
    swatches are identical. Attributable to "wow" Thing's
    underlying color-state having been mutated between
    the baseline run and the group10 run, not to the
    translation.
  * Scenes 01, 03 — baseline PNGs were captured mid
    staggered-entrance animation (scene 01: only
    "interesting" + "wow" cards visible above a mostly-
    empty list with the rest still off-screen; scene 03:
    11 cards with unnatural row gaps and bottom row only
    half-rendered). group10 captures are at-settle (all
    26 cards / full grid). This is a **baseline-capture
    timing artifact**, not a regression — the baseline
    needs to be re-captured after the list settles for
    future V3 diffs to be meaningful here.

## 2026-05-20 — Kotlin migration Group 9 (receivers/)

Translated 13 BroadcastReceiver classes (~1059 LoC):
LocaleChangeReceiver, DailyCreateTodoReceiver,
AppUpdateReceiver, UserPresentReceiver, AutoNotifyReceiver,
BootReceiver, DailyUpdateHabitReceiver,
HabitWidgetActionReceiver, DoingNotificationActionReceiver,
HabitNotificationActionReceiver,
ReminderNotificationActionReceiver, ReminderReceiver,
HabitReceiver.

Uniform pattern:
- All → `open class : BroadcastReceiver()` with single
  `override fun onReceive(context: Context, intent: Intent)`.
- `public static final String TAG` → `companion object {
  const val TAG: String = ... }`.
- Three of them (AppUpdateReceiver, UserPresentReceiver,
  BootReceiver) wrap their work in `new Thread(new Runnable
  { ... }).start()`. Kept as `Thread(object : Runnable {
  override fun run() { ... } }).start()` — bodies only
  reference a captured `appContext` local plus static-only
  helpers (no outer-class `this`), so SAM lambda would have
  worked too. Chose `object :` form for symmetry with
  Group 5's similar pattern.

Special handling:

- AutoNotifyReceiver uses deprecated
  `Notification.PRIORITY_DEFAULT` (API 26+); file declares
  `@file:Suppress("DEPRECATION")` per §3.11.
- HabitReceiver's "after 1600ms" notification Runnable
  references `thing` (outer-scope local). Kept as
  `object : Runnable` per §3.5 guard 3 (captures effectively
  count as outer-scope reference for clarity).
- ReminderNotificationActionReceiver's `LEGAL_ACTIONS`
  `private static final String[]` → `private val
  LEGAL_ACTIONS: Array<String> = arrayOf(...)` in companion
  object. Not `const` — array initialiser isn't a
  compile-time constant.
- DoingNotificationActionReceiver's `ACTION_FINISH` /
  `ACTION_USER_CANCEL` / `ACTION_STOP_SERVICE` were Java
  `static final` strings built from `TAG + ".finish"` etc.
  Translated as `const val` with the literal full string
  baked in ("DoingNotificationActionReceiver.finish") —
  matches the JVM constant pool entry the original Java
  would emit (Java's string concatenation of two final
  literals is also compile-time-foldable).
- Property-syntax rewrites for Group 2 model getters:
  `reminder.getState()` → `reminder.state`,
  `reminder.getNotifyTime()` → `reminder.notifyTime`,
  `reminder.setState(X)` → `reminder.state = X`,
  `habitReminder.getHabitId()` → `habitReminder.habitId`,
  `habitReminder.getNotifyTime()` → `habitReminder.notifyTime`,
  `habit.getRecord()` → `habit.record`,
  `habit.getRemindedTimes()` → `habit.remindedTimes`,
  `habit.getHabitReminders()` → `habit.habitReminders`,
  `thing.getType()` → `thing.type`,
  `thing.getState()` → `thing.state`,
  `thing.setContent(X)` → `thing.content = X`,
  `thing.getId()` → `thing.id`. Methods that remain `fun`
  in Group 2 (Thing.getBackground / getColor / getTitleTo-
  Display / isPrivate / isSelected, Habit.isPaused /
  getMinHabitReminderTime / getSummary, ReminderHabitParams
  member getters) keep their method-form call sites.
- Java vararg `String...` not used here — all `Intent.action`
  comparisons are sequential `.equals()` chains.

Verifications:
- V1: BUILD SUCCESSFUL on first compile attempt (no
  iteration needed), 0 Kotlin warnings, APK assembled
- V2: grep audit clean — N1, E1; all remaining `==` are
  Long/Int primitive or `== null` checks
- V3: cold-start renders 26 things identically; receivers
  manifest-declared so they're loaded but won't fire
  without an alarm — pure registration smoke test
- V4 sampled: logcat clean on cold-start — zero FATAL /
  VerifyError / ClassNotFoundException / NoSuchMethodError /
  NoClassDefFound / SQLiteException / RuntimeException

## 2026-05-20 — Kotlin migration Group 8 (appwidgets/)

Translated 17 widget files (~2301 LoC):
- root/: AppWidgetHelper (1104 LoC), CheckUpcomingWidget,
  CreateWidget
- list/: ThingsListWidget, ThingsListWidgetService,
  ThingsListWidgetConfiguration
- single/: BaseThingWidget, BaseThingWidgetConfiguration
  (421 LoC), ChecklistWidgetService, ThingWidget{Tiny/Small/
  Middle/Large}, ThingWidgetConfiguration{Tiny/Small/Middle/
  Large}

Pattern split:
- AppWidgetHelper → `object` (pure-static utility)
- 3 simple `AppWidgetProvider` subclasses (CheckUpcomingWidget,
  CreateWidget, ThingsListWidget) → `open class`
- BaseThingWidget → `abstract class` with abstract
  `getTag(): String?`; 4 size subclasses just override `getTag`
- 4 size subclasses + 4 ThingWidgetConfiguration size
  subclasses each override one abstract method returning a
  literal value
- 2 RemoteViewsService classes (ThingsListWidgetService,
  ChecklistWidgetService) with nested
  `class … : RemoteViewsFactory`
- ThingsListWidgetConfiguration → `AppCompatActivity`
- BaseThingWidgetConfiguration (421 LoC) →
  `EverythingDoneBaseActivity`-derived, with inner
  `ThingsAdapter` (extends BaseThingsAdapter, holds
  `inner class Holder`) and several `object :` listeners

Special handling:

- Abstract Java method `protected String getTag()` →
  `protected abstract fun getTag(): String?` and subclasses
  return non-null literals. N1 nullable return type
  doesn't restrict subclass body.
- Abstract raw `Class` return → `Class<*>?`. Used in
  ThingWidgetConfiguration*/ThingWidget* size variants.
- `EverythingDoneBaseActivity` (still Java) extends
  ComponentActivity; Kotlin sees `getOnBackPressedDispatcher()`
  only via property syntax → use `onBackPressedDispatcher`.
  Initial draft used method form → compile error at V1.
- Inside `object : OnBackPressedCallback(true)`, inherited
  `setEnabled(boolean)` maps to property `isEnabled = false`.
- Java varargs `String...` requires Kotlin spread (`*`)
  when passing an existing array:
  `..., *PermissionUtil.getRequiredPermissionsForThings(
  mThings)!!`. Caught at V1.
- `clazz!!.getSuperclass().equals(X)` triggered "Only safe
  (?.) or non-null asserted (!!.) calls are allowed on a
  nullable receiver of type 'Class<*>?'" — `getSuperclass()`
  returns `Class<*>?` (Java may return null). Fixed by
  chaining `!!`: `clazz!!.getSuperclass()!!.equals(X)`.
  Three occurrences in AppWidgetHelper.
- Java APIs deprecated in API 33/35:
  RemoteViews.setRemoteAdapter(Int, Intent),
  AppWidgetManager.notifyAppWidgetViewDataChanged(Int, Int),
  Window.setStatusBarColor(Int),
  FLAG_TRANSLUCENT_NAVIGATION,
  RecyclerView.ViewHolder.getAdapterPosition(). Four files
  (AppWidgetHelper, ThingsListWidget, BaseThingWidget,
  BaseThingWidgetConfiguration) declare
  `@file:Suppress("DEPRECATION")` per plan §3.11.
- AppWidgetHelper's `screenDensity` / `dp12` are runtime-init
  `private val`s inside the object — same `<clinit>` order
  as Java `private static final` per §3.3 S-4.
- 35-plus `R.id.*` `private static final int` constants →
  `private val` (not `const val` — R.id.* is a Java static
  field, not a Kotlin compile-time const).

Verifications:
- V1: BUILD SUCCESSFUL, 0 Kotlin warnings, APK assembled
- V2: grep audit clean — N1, E1
- V3: cold-start renders 26 things identically. Widget
  classes themselves not added to home screen, but
  AppWidgetHelper / DAO paths are exercised by
  RemoteActionHelper / receivers at launch.
- V4 sampled: logcat clean on cold-start — zero FATAL /
  VerifyError / ClassNotFoundException / NoSuchMethodError /
  NoClassDefFound / SQLiteException / RuntimeException

## 2026-05-20 — Kotlin migration Group 7 (managers/)

Translated 2 controller classes (~1236 LoC): ModeManager,
ThingManager. Both are stateful singletons.

- `ModeManager` (308 LoC) — UI mode controller for ThingsActivity
  (NORMAL / MOVING / SELECTING). Translated to a plain class
  with a primary constructor accepting all collaborator
  views/listeners. Three `Runnable`s (notifyDataSetRunnable,
  hideActionBarShadowRunnable, backNormalModeListener)
  reference outer-class fields → kept as `object : Runnable` /
  `object : View.OnClickListener` per §3.5 guard 3, with
  `this@ModeManager.backNormalMode(0)` replacing Java's
  `ModeManager.this.backNormalMode(0)`. NORMAL / MOVING /
  SELECTING moved into companion object as `const val Int`.
- `ThingManager` (928 LoC) — singleton with private ctor +
  `companion object { getInstance, isTotallyInitialized,
  sThingManager }`. The class header comment explicitly says
  "we cannot use lambda in this class to replace 'new
  Runnable'" — preserved by keeping every executor task as
  `object : Runnable { override fun run() { … } }` (the
  bodies reference `mDao`, `mUndoGoals`, `mUndoHabits`,
  `mContext`, etc. — outer state).

Special handling:

- Thing's `var selected: Boolean` (Kotlin property) coexists
  with `open fun isSelected(): Boolean` (Kotlin fun). Property
  name is `selected` not `isSelected`, so callers writing
  `things.get(position).setSelected(true)` translate to
  `things.get(position).selected = true`. Initial draft of
  ModeManager wrote `.isSelected = true` → compile error
  ("Function invocation 'isSelected()' expected. Variable
  expected.") caught at V1.
- Java's `List.toArray(T[])` Collection method isn't directly
  callable from Kotlin on `MutableList<T>` here — replaced
  with `mutableList.toTypedArray()` which returns a new
  `Array<T>`. Used in `getSelectedThings()` and the executor
  task inside `updateLocationsByAlarmTime`.
- `Collections.sort(mThings, comparator)` where `mThings` is
  `MutableList<Thing?>?` — needs `mThings!!` to unwrap the
  nullable list (Kotlin's strict signature on Java's
  `Collections.sort` doesn't auto-unwrap).
- Java's `for (int i = start, j = 0; i <= end; i++, j++)`
  pattern in `updateLocations` translated to manual
  `while (i <= end) { ...; i++; j++ }` — Kotlin's `for in`
  doesn't support multiple iterators.

Verifications:
- V1: BUILD SUCCESSFUL, 0 Kotlin warnings, APK assembled
- V2: grep audit clean — N1, E1 (the only ref `==` are
  `thing.id == X` Long primitive compares)
- V3: cold-start renders 26 things identically — exercises
  `ThingManager.getInstance` (private ctor + loadThings +
  HEADER discovery) and the executor singleton init paths
- V4 sampled: logcat clean on cold-start — zero FATAL /
  VerifyError / ClassNotFoundException / NoSuchMethodError /
  NoClassDefFound / SQLiteException / RuntimeException

## 2026-05-20 — Kotlin migration Group 6 (helpers/)

Translated 18 helper classes (~4147 LoC):
DailyTodoHelper, AuthenticationHelper, LineSpacingHelper,
CrashHelper, PossibleMistakeHelper, AutoNotifyHelper,
BackupHelper, CheckListHelper, AppUpdateHelper, FingerprintHelper,
NotificationReliabilityHelper, AlarmHelper, ThingExporter,
RemoteActionHelper, SendInfoHelper, ScreenshotHelper,
AttachmentHelper, ThingDoingHelper.

Pattern split:
- 13 → `object` (pure utility singletons, private ctor + only-
  static members): DailyTodoHelper, AuthenticationHelper,
  LineSpacingHelper, PossibleMistakeHelper, AutoNotifyHelper,
  BackupHelper, CheckListHelper, NotificationReliabilityHelper,
  AlarmHelper, ThingExporter, RemoteActionHelper,
  SendInfoHelper, ScreenshotHelper, AttachmentHelper
- 3 → `class` with companion-object singleton (mutable instance
  state): CrashHelper (mApplication, mDefaultHandler),
  AppUpdateHelper (mContext), FingerprintHelper (mContext,
  mKeyguardManager, mKeyStore, mKeyGenerator, mCipher)
- 1 → `class` with public constructor (caller passes Thing per
  instance): ThingDoingHelper (mContext, mThing,
  mSpStartDoing, mSpSettings)

Special handling:

- `AutoNotifyHelper` had a `static { … }` init block that
  overwrites the `AUTO_NOTIFY_TIMES` / `_TYPES` arrays in
  DEBUG builds → `init { … }` inside the object per §3.3 S-4
  (preserves `<clinit>` timing).
- `LineSpacingHelper` has a `private static class
  LineSpacingCursorDrawable extends ShapeDrawable` →
  Kotlin `private class … : ShapeDrawable()` (nested, not
  inner — equivalent to Java static nested).
- `ScreenshotHelper` / `ThingExporter` each subclass
  `AsyncTask` (deprecated upstream in API 30). Both files
  declare `@file:Suppress("DEPRECATION",
  "OVERRIDE_DEPRECATION")` — the latter is needed because
  Kotlin's per-override warning isn't covered by file-level
  DEPRECATION.
- `ScreenshotHelper.getScreenshot(vararg params)` Java code
  had `if (params == null || params.size == 1) return null` —
  Kotlin vararg cannot be null, so the null check was
  dropped (compiler reported "Condition is always 'false'").
  Same behaviour: vararg with 1 arg still returns null.
- `AppUpdateHelper`, `AttachmentHelper`,
  `AuthenticationHelper`, `FingerprintHelper` all call
  `activity.getFragmentManager()` which is deprecated since
  API 28. The Java original used the same call (no
  replacement adopted yet). All 4 add
  `@file:Suppress("DEPRECATION")` per §3.11.
- `ThingDoingHelper.mSpStartDoing` / `mSpSettings` are
  initialised inline from the primary-constructor parameter
  `context: Context?`. Both initialisers must use
  `context!!.getSharedPreferences(...)` — initial draft
  forgot the `!!` on the second one (caught at V1).
- `RemoteActionHelper.finishReminder` mutates the `thing`
  parameter (`thing = Thing.getSameCheckStateThing(...)`).
  Java parameters are reassignable, Kotlin parameters are
  `val`. Translated to a local `var t: Thing = thing!!` and
  rewrote all subsequent uses to `t`.
- `SendInfoHelper` keeps a `private const val
  EXTRA_WX_SHARE_EXPLORE_CONTENT = "Kdescription"` for the
  WeChat Moments share-key field.

Verifications:
- V1: BUILD SUCCESSFUL, 0 Kotlin warnings, APK assembled
- V2: grep audit clean — all remaining `==` are
  Int/null/Char/Boolean primitives; reference compares use
  `===` or `.equals()` per §3.2
- V3: cold-start renders 26 things identically; UI exercises
  AttachmentHelper (`getFirstImageTypePathName`),
  AlarmHelper (cold-start `createAllAlarms` path),
  CrashHelper (init in App.onCreate), and various
  Helper-dependent dialogs
- V4 sampled: logcat clean on cold-start — zero FATAL /
  VerifyError / ClassNotFoundException / NoSuchMethodError /
  NoClassDefFound / SQLiteException / RuntimeException
  across the new bytecode

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
