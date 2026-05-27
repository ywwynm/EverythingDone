# Sessions

## 2026-05-27 - App language support and language-selection fix

Added app language support for Japanese, Korean, Italian, Spanish, Russian,
French, German, Hindi, and Portuguese. The resource work was corrected to use
`values-zh-rCN/strings.xml` as the translation source after the Google
Translate batch attempt produced mixed Chinese/token artifacts in long Help
strings. The default English Help text was also translated from the Simplified
Chinese source so non-Chinese locale fallbacks no longer expose Chinese Help
content.

Fixed Settings language selection by comparing stored language codes instead
of displayed names, then syncing AppCompat per-app locales from the stored
preference. Added base-context locale wrapping for the Application, the common
base Activity, and AppCompat entry activities that do not inherit that base.
Enabled AGP generated locale config and added `resources.properties` with
English as the unqualified resource locale.

Verification:
- Cleared leftover translation protection tokens from the generated locale
  resources.
- `.\gradlew.bat :app:assembleDebug --console=plain` passed and produced
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 18:04:29`.
- `git diff --check` passed with only the repository's existing CRLF warnings.
- No device UI smoke test was run for the language picker or per-screen locale
  switching in this step.

## 2026-05-27 - Android 16 AppWidget click action fix

Investigated AppWidget click regressions after targeting Android 16 / SDK 36.
The affected widget surface has two distinct PendingIntent requirements:

- Collection rows (`ThingsListWidget` list rows and single-thing checklist
  rows) use `setPendingIntentTemplate(...)` plus `setOnClickFillInIntent(...)`.
  Their template PendingIntent must be mutable so launcher-provided fill-in
  extras such as thing id and checklist item position reach the app.
- Widget Activity launches are sent by the launcher. Added a shared
  `AppWidgetHelper.getActivityPendingIntentForWidget(...)` helper that attaches
  creator-side background-activity-launch `ActivityOptions` on Android 14+,
  then routed all widget `getActivity` PendingIntents through it.

Changed files:
- `AppWidgetHelper.kt`
- `CreateWidget.kt`
- `CheckUpcomingWidget.kt`
- `memory/decisions.md`

Verification:
- Source guard for AppWidget collection templates passed: both templates now
  use the mutable collection-template flag.
- Source guard for raw widget `PendingIntent.getActivity` usage passed: all
  AppWidget activity launches now go through the BAL helper.
- The first sandboxed Gradle attempts did not produce a fresh APK: one wrapper
  run timed out while trying to download Gradle, and later runs failed writing
  `swirl/build/intermediates/...` with access denied.
- Re-ran the project wrapper outside the sandbox using
  `.\gradlew.bat :app:assembleDebug --console=plain`; it completed with
  `BUILD SUCCESSFUL in 31s` and produced
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 11:45:51`.
- `git diff --check` passed with only existing LF-to-CRLF warnings.

No real launcher widget click smoke test was run in this session because no
emulator was attached; `adb devices` showed only the physical device.

Follow-up after user reported duplicate home-card update animation:
- Root cause: while `ThingsActivity` was stopped but still alive, its dynamic
  `UPDATE_MAIN_UI` receiver could receive widget checklist-toggle broadcasts.
  If a previous `mRemoteIntent` was already pending, the hidden receiver branch
  immediately called `updateMainUi(mRemoteIntent)`, which could enqueue a
  RecyclerView notify while the adapter was in wait-notify mode, then store the
  newer widget intent for `onResume()`. Returning to home could therefore replay
  a stale queued notify plus the fresh widget update.
- First attempted fix was too broad: it always replaced the pending hidden
  `mRemoteIntent` with the latest intent, which could drop meaningful distinct
  remote events. User correctly flagged this as risky.
- Revised fix: hidden remote updates now go through an explicit coalescing
  helper. The pending intent is replaced only when replacement is safe:
  `App.justNotifyAll()` is already true, the old result is `RESULT_NO_UPDATE`,
  or both intents are the same result for the same Thing. Otherwise the legacy
  behavior is preserved: process the older pending intent, then store the newer
  one after the delay.
- Verification: source guard confirmed the hidden branch uses the coalescing
  helper and that the helper covers the no-update and same-Thing replacement
  cases; `.\gradlew.bat :app:assembleDebug --console=plain` passed outside the
  sandbox (`BUILD SUCCESSFUL in 2s`) and produced
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 11:57:34`.

Second follow-up after the duplicate animation remained hard to reproduce:
- Found another plausible independent cause: `ThingsAdapter.onBindViewHolder()`
  replayed `things_show` on every bind while
  `mShouldThingsAnimWhenAppearing == true`. A remote widget
  `notifyItemChanged(...)` can therefore combine RecyclerView's change
  animation with the adapter's delayed appear animation. Because
  `playAppearingAnimation()` starts via `Handler.postDelayed(position * 30)`,
  stale delayed appear runnables can also fire after a later content-update
  bind, which explains the intermittent nature.
- Fix: `ThingsAdapter` now tracks which Thing ids have already played the
  appearing animation during the current appear cycle, clears that set when a
  new explicit appear cycle is requested, and uses a per-view keyed tag token
  to cancel stale delayed appear runnables on later non-appearing binds.
- Added `app/src/main/res/values/ids.xml` for the keyed animation token tag.
- Verification: source guard confirmed the appeared-id guard and per-view token
  cancellation are present; `.\gradlew.bat :app:assembleDebug --console=plain`
  passed outside the sandbox (`BUILD SUCCESSFUL in 4s`) and produced
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 12:57:32`.
- Rolled this second follow-up back after device testing showed severe
  AppWidget data/rendering regressions: tapping checklist item A could update B,
  checklist rows could show checked text with an unchecked icon, and Things-list
  widgets could show wrong thing data. `ThingsAdapter.kt` was restored to its
  pre-follow-up state and `app/src/main/res/values/ids.xml` was deleted.
- Rebuilt the rollback APK with
  `.\gradlew.bat :app:assembleDebug --console=plain`; it passed
  (`BUILD SUCCESSFUL in 1s`) and produced
  `app/build/outputs/apk/debug/app-debug.apk` at `2026-05-27 13:03:57`.

## 2026-05-25 — Homepage thing-card UI refresh, step 1

Updated EverythingDone's homepage thing cards using Everything-Android's
main-list card feel as reference:

- Added shared card tokens: 10dp outer spacing, 10dp corner radius,
  8dp normal elevation, 12dp dragging elevation.
- Applied the spacing to `activity_things.xml`, `ThingsAdapter`
  item margins, and `DisplayUtil.getThingCardWidth` so image cards
  keep the same waterfall grid geometry.
- Updated `card_thing.xml` with the larger radius, stronger shadow,
  max elevation, and outline clipping for image attachments.
- Added normal-mode touch response in `ThingsAdapter`: press scales to
  0.936 and lowers elevation; release bounces to 1.016 then settles.
- Kept moving/selecting mode geometry consistent via `BaseThingsAdapter`
  and `ModeManager`; updated new-item shining border radius to the
  same card-radius dimen.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed
  (`BUILD SUCCESSFUL in 2s` on the final incremental run).
- `git diff --check` passed; only existing CRLF conversion warnings.
- No visual install/screenshot was performed because `adb devices`
  showed only a physical device and no `emulator-5554`.

Follow-up in same session after user reported the radius still looked
unchanged:
- Root cause: `BackgroundUtil.applyCardBackground()` replaced
  CardView's runtime background with a plain `ColorDrawable` or a
  no-radius `GradientDrawable` on every bind. On API 21+, CardView's
  outline follows that runtime background, so the XML
  `cardCornerRadius` was effectively erased.
- Fix: `applyCardBackground()` now always uses a rounded
  `GradientDrawable`, syncing `gd.cornerRadius = cv.radius` and using
  identical color stops for PURE backgrounds so pure/gradient recycling
  keeps the same rounded shape.
- Re-verified `.\gradlew.bat :app:assembleDebug --console=plain`
  (`BUILD SUCCESSFUL in 8s`) and `git diff --check`.

Second follow-up in same UI pass:
- In selecting/moving mode, unselected cards already used
  `lightVariant(background)`, but their content layer kept full alpha.
  On light cards this made black text/icons stand out too much.
- Added `BaseThingsAdapter.shouldDimUnselectedContent()` and enabled it
  only for the homepage `ThingsAdapter`; unselected homepage cards now
  set `llContent` and sticky/ongoing icon alpha to `0.54f`.
- Adjusted `ThingsAdapter.onBindViewHolder()` so its new-item-animation
  cleanup only forces `llContent.alpha = 1f` in NORMAL mode, otherwise
  it would undo the selecting/moving-mode dim.
- Re-verified `.\gradlew.bat :app:assembleDebug --console=plain`
  (`BUILD SUCCESSFUL in 8s`) and `git diff --check`.
- User clarified black and white content should not use the same alpha
  because black reads visually heavier. Split unselected content alpha:
  black/dark foreground on light cards uses `0.38f`; white/light
  foreground on dark cards uses `0.54f`. Re-verified assembleDebug
  (`BUILD SUCCESSFUL in 6s`) and `git diff --check`.
- User clarified the alpha split should only apply to adaptive foreground
  content (text/icons/checklist/reminder/habit), not to image attachments
  or the card colour itself. Replaced the coarse `llContent.alpha` dim with
  per-view alpha assignment: image attachment container/image/cover/loading
  stay at `1.0f`, while adaptive foreground views use the black/white split.
  Re-verified assembleDebug (`BUILD SUCCESSFUL in 7s`) and `git diff --check`.
- User reported the "Blank thing has been abandoned" snackbar no longer
  appears after creating an empty thing and returning home. Root cause:
  `DetailActivity.createFailed()` used `setResult(resultCode)` without an
  `Intent`, while `ThingsActivity.onActivityResult()` now intentionally skips
  null `data` to avoid a migrated Kotlin NPE. The non-zero result code was
  therefore dropped before `RESULT_CREATE_BLANK_THING` could show its snackbar.
  Fixed `createFailed()` to put `KEY_RESULT_CODE` into its result `Intent`
  and call `setResult(resultCode, intent)`. Re-verified assembleDebug
  (`BUILD SUCCESSFUL in 9s`) and `git diff --check`.
- Extended the updated thing-card radius to `DoingActivity`, which reuses
  `card_thing` but had been forcing the CardView radius to `0f`. Kept
  `NoticeableNotificationActivity` square per user direction, and left the
  single-thing widget configuration preview unchanged because the actual
  desktop widget is RemoteViews + bitmap-backed, not a CardView. Re-verified
  assembleDebug (`BUILD SUCCESSFUL in 5s`) and `git diff --check`.

## 2026-05-21 — Post-migration Kotlin cleanup (full session)

Completed IDE inspection cleanup on the `kotlin` branch after the 17-group
Java→Kotlin migration. ~30 commits total. Full plan in
`docs/plans/KOTLIN_CLEANUP_PLAN.md`.

### Approach
- **Grilling session** established scope/risk boundaries (yellow warnings +
  safe grey suggestions; no behavior-changing transforms)
- **AS GUI** exported inspection inventory twice (`memory/inspections-gui/`,
  `memory/inspections-gui-2/`); headless `inspect.bat` unusable on AGP 9 /
  Compose
- **Hybrid execution**: user batch-applied safe Tier-1 via AS "Apply fix to
  all"; agent handled remaining ~230 structural/judgment items + Tier-2/3

### Fixes applied (~1100+ edits across ~150 files)
| Tier | What | Count |
|---|---|---|
| Tier-1 safe | Property access, cascade-if→when, string templates, join decl, redundant types/imports/qualifiers, elvis, safe-access, SAM ctor, put→[], visibility, range checks, isNullOrEmpty, enum entries, const, etc. | ~970 |
| Tier-2 judgment | ObjectLiteralToLambda (34/36 after B-class/hot-path guard audit), ReplaceJavaStaticMethodWithKotlinAnalog (1 remaining), CanBePrimaryConstructorProperty (3) | ~38 |
| Tier-3 boundary | RedundantNullableReturnType — return type T?→T across 25 files; 0 downstream compile errors | ~80 |
| Bug fix | ThingsActivity.onActivityResult NPE (data!! → null guard) | 1 |
| Suppressions | @file:Suppress("DEPRECATION") on 10 files (latent warnings surfaced by full recompile) | 10 |

### What was NOT touched
- Naming conventions (mXxx fields, f() helpers), BooleanLiteralArgument,
  spelling/Grazie — intentional style choices
- @JvmStatic/@JvmField, explicit getX()/setX() — Java interop, deferred
- data class, scope functions, coroutines — D-class, behavior-changing
- Android Lint (~700 items) — separate concern, mostly pre-existing
- Existing @file:Suppress annotations — deprecation swaps are behavior changes

### Tier-6 investigation (reported, not fixed)
Two potential ClassCastExceptions in unsafe casts (DetailActivity:3354
ViewHolder→EditTextHolder, ScreenshotHelper:115 params[0] as View) and
four benign findings (empty ranges, constant conditions). See session
transcript for details.

### Verification
- Every commit: `:app:assembleDebug` with 0 errors / 0 warnings
- Final smoke test on device: user confirmed "问题不大"

Files changed: App.kt, DoingActivity.kt, NoticeableNotificationActivity.kt, BaseThingsAdapter.kt, AppWidgetHelper.kt, BaseThingWidgetConfiguration.kt, CheckListHelper.kt (5 sites), NotificationReliabilityHelper.kt, ScreenshotHelper.kt, SendInfoHelper.kt, Habit.kt (6 sites), Reminder.kt, Thing.kt, ThingAction.kt, ThingBackground.kt (3 sites), PermissionUtil.kt (5 sites), DoingService.kt (2 sites), BitmapUtil.kt (2 sites), DeviceUtil.kt (2 sites), DisplayUtil.kt (2 sites), FileUtil.kt (4 sites), LocaleUtil.kt, StringUtil.kt (3 sites), SystemNotificationUtil.kt (4 sites), ThingsSorter.kt.

## 2026-05-21 — IDE inspection fixes in DateTimeUtil.kt

Fixed Kotlin IDE inspection items in `DateTimeUtil.kt`:
- **RedundantNullableReturnType** (13): Removed redundant `?` from return types on methods that never return null.
- **Join declaration and assignment** (3): Combined `val x; x = ...` into `val x = ...` for `daysStr`, `postfix`, `sdf`.
- **String concatenation to template** (7): Converted `"a" + x + "b"` to `"a${x}b"` in `getTimeLengthStr` and `getTimeLengthStrOnlyDay`.
- **Cascade-if to when** (5): Rewrote if-else chains to `when` expressions in `getDateTimeStrRec`, `getTimeTypeLimit`, `getTimeLengthStrOnlyDay`, `getThisTStr`, `calculateTimeGap`.
- **Skipped**: `Collections.synchronizedMap` replacement (compatibility concern).
Compile: `:app:compileDebugKotlin` BUILD SUCCESSFUL, zero errors.

## 2026-05-20 — Kotlin migration Group 17 (SettingsActivity) — FINAL

Translated `SettingsActivity.java` 1771 LoC → Kotlin. This is the last
group of the migration plan — every `.java` source in `app/` is now
`.kt`. The plan's §3 rule set held without revision through all 17
groups; the last group needed only routine nullability/!! tweaks.

### V1 / V2 results
First-pass compile: **15 errors, 0 warnings on SettingsActivity itself**.
All errors were nullability mismatches against already-Kotlin helpers:

| # | Site                                                | Fix |
|---|-----------------------------------------------------|-----|
| 1 | `FingerprintHelper.getInstance()` ×3                | `!!` (Companion returns nullable) |
| 2 | `ThingDoingHelper.getStartDoingTimeItems(this)` ×3  | `!!.toMutableList()` / `: List<String?>` |
| 3 | `NotificationReliabilityHelper.getDisabledCriticalChannels(this)` ×2 | `: List<String?>`, `!!`, `disabled.get(0)!!` |
| 4 | `DailyTodoHelper.getDailyTodoItems(this)`           | `?.toMutableList()` for `sDTItems: MutableList<String?>?` |
| 5 | `getRingtoneTitle(...)` return type                 | `String?` (replaceChineseBrackets returns String?) |
| 6 | Various `replaceChineseBrackets(dr.getTitle(ctx))`  | sRingtoneTitleList typed as `MutableList<String?>?` |

Second pass: BUILD SUCCESSFUL, 0 warnings on the new file.

### V3 / V4 (emulator-5554)
- Launch ThingsActivity → open drawer → tap Settings → SettingsActivity
  opens with yellow toolbar, full UI/Reminder-Reliability/Ringtone/
  Data/Privacy/StartDoing/Advanced sections render correctly.
- Tap "Language" row → ChooserDialogFragment opens with 5 language
  options (Follow System highlighted) — `showChooseLanguageDialog`
  path works including `setItems(MutableList<String?>?)` plumbing.
- Tap "Press Back twice to exit" CheckBox → toggles on screen.
- Back → ThingsActivity → `EverythingDone_preferences.xml` written:
  `twice_back=true`, `noticeable_notification=true`, `auto_save_edits=false`.
  Confirms `finish()` override → `storeConfiguration()` runs and
  `SharedPreferences.Editor.apply()` commits all groups.
- Logcat for app PID: 0 FATAL / 0 AndroidRuntime / 0 EverythingDone E lines.

### Notes / gotchas
- **`AsyncTask` deprecated, still functional**: BackupTask / RestoreTask
  inner classes extend `AsyncTask<Any?, Any?, Boolean>` — `vararg
  params: Any?` syntax in `doInBackground` replaces Java's `Object...`.
- **`@file:Suppress("DEPRECATION")` only, not OVERRIDE_DEPRECATION**:
  no methods here override deprecated framework methods (`onActivityResult`
  + `onResume/onStop/onCreate` lifecycle remain non-deprecated at AGP
  current target).
- **`@SuppressLint("ApplySharedPref")` for inline `commit()`**: applied
  to the single `mPreferences!!.edit()...commit()` call in
  `showChooseLanguageDialog`'s Confirm listener (called before
  `App.killMeAndRestart`, must be synchronous).
- **Static migration**: `sKeysRingtone`, `sRingtoneTitleList`,
  `sRingtoneUriList`, `sDTItems`, `sANItems` moved to companion
  object as `var` (still mutable, lazily initialized inside Activity
  methods — original Java code uses them across re-creations).
- **`setItems()` accepts `MutableList<String?>?`** not `List<String>`
  — required `.toMutableList() as MutableList<String?>` casts at every
  `Arrays.asList(strArr)` Java site, or just `array.toMutableList()`
  when items don't need explicit nullability.

### Migration complete
All 17 groups translated. App contains 0 `.java` files in
`app/src/main/java/com/ywwynm/everythingdone/`.

## 2026-05-20 — Kotlin migration Group 16 (ThingsActivity)

Translated `ThingsActivity.java` 2834 LoC → Kotlin in one Write pass.
Class declared `final` (plain `class : EverythingDoneBaseActivity()`).
Inner listener classes (`OnNavigationIconClickedListener`,
`OnThingTouchedListener`, `ThingsTouchCallback`,
`OnContextualMenuClickedListener`) kept as `internal inner class` —
they all access outer-state members (mAdapter, mModeManager, etc.).

### V1 / V2 results
First-pass compile: **8 errors, 0 warnings on ThingsActivity itself**.
All 8 boiled down to non-null arg expectations on already-Kotlin
constructors / setters that the Java code's platform-typed sites had
masked:

| # | Site                                                | Fix |
|---|-----------------------------------------------------|-----|
| 1 | `setTintTarget(menuItem.icon)`                      | `icon!!` (Drawable?) |
| 2 | `DrawerHeader(mApp, …)`                             | `mApp!!`, view!! ×3 |
| 3 | `ActivityHeader(mApp, mRecyclerView, …)`            | `mApp!!`, `mRecyclerView!!`, view!! ×4 |
| 4 | `mActivityHeader!!.setModeManager(mModeManager)`    | `mModeManager!!` |
| 5 | `mUndoSnackbar!!.setMessage(messages[0])`           | `messages[0]!!` / `[1]!!` |
| 6 | `ThingExporter.startExporting(…, getSelectedThings())` | spread + `?: emptyArray()` (vararg consumes `Thing?...`) |

Second pass: BUILD SUCCESSFUL, 0 warnings emitted from the new file.

### V3 / V4 (emulator-5554)
- Launch ThingsActivity → list renders 28 items, FAB visible.
- Open drawer → NavigationView with completion-rate header, all 9 items.
- Tap Reminder row → header switches to "Reminder 5 things", FAB stays
  spread, RecyclerView reloads.
- Tap FAB → reveal animation plays → DetailActivity opens green (newThingColor).
- Type content + back twice → return to ThingsActivity, header now
  "Underway 29 things", new green card "Group16_ThingsActivity_test"
  appears at position 1 after scroll-to-top — `playNewItemAnimation`
  (reveal variant) fires correctly.
- Tap search icon → search bar swaps in, FAB shrinks, typing "888888"
  filters to single matching dark-slate card.

No new crashes, no logcat exceptions. APK installs and runs clean.

### Notes / gotchas captured during this group
- Old Java `getCurrentFocus()` returns nullable View — `KeyboardUtil.
  hideKeyboard(currentFocus)` now passes `View?` but the util already
  null-checks.
- Java code referenced `mUndoThings.iterator()` and assumed
  `MutableIterator<Thing>` — added explicit type at the iterator
  declaration to keep `iterator.remove()` callable.
- `mUndoPositions` had to be re-cast after `updateStates` because the
  ThingManager.kt return type is `MutableList<Int?>?`; cast to
  `MutableList<Int>` is unchecked-suppressed but safe in this control
  flow (the manager never inserts nulls).
- The Java `for (final Thing undoThing : mUndoThings)` paired with
  `undoThing.setSelected(false)` becomes `undoThing.selected = false`
  in Kotlin because `Thing.kt` has a public `selected` property; the
  `isSelected()` method is also still available for reads.
- `ThingsTouchCallback.onChildDraw` uses `FrameLayout.LayoutParams`
  for `flDoing` — `InterceptTouchCardView.LayoutParams` doesn't exist
  as a nested type (same Group 12 fix).

## 2026-05-20 — Kotlin migration Group 15 (DetailActivity)

Translated the first monster, `DetailActivity.java` 3883 LoC → Kotlin.
Single file. Strategy: Option 1 m-prefixed for view fields, Option 3
for Group 2/4 model getters at call sites. Class declared `final`
(plain `class` in Kotlin, no `open`).

The class itself was Java `public final class`, so the Kotlin
translation is `class DetailActivity : EverythingDoneBaseActivity()`
with no `open` modifier. This in turn means Kotlin warns on every
`open fun` inside (since members of a `final` class can't be
overridden — `open` is silently meaningless). Stripped `open` from
9 fun/val declarations that don't override anything.

Special handling:

- **`@file:Suppress("DEPRECATION")`**: required because the file
  uses `getFragmentManager()` (deprecated since API 28),
  `overridePendingTransition(...)` (deprecated since API 34 in
  favour of `overrideActivityTransition`), and
  `getDrawable(R.mipmap.ic_launcher)` (deprecated since API 21,
  replaced by `ContextCompat.getDrawable`).
- **`fun getType()` → `val type: Int get() = mType`**: the Java
  getter `getType()` was being called as a Kotlin property
  (`mActivity!!.type`) by Group 13's DateTimeDialogFragment. Once
  DetailActivity was translated, `fun getType()` no longer matched
  JavaBean-property-access conventions (Kotlin's function `getX()`
  is just a function, not a property). Rewrote as a Kotlin `val
  type: Int get() = mType` so both Kotlin callers (property syntax)
  and Java callers (auto-generated `getType()` method) work.
- **`Pair<Thing, Int>?` from `App.getThingAndPosition`**: same
  Group 14 gotcha — App.kt declares the return as `Pair<Thing, Int>?`
  (non-null inner types via androidx.core.util.Pair) but at runtime
  the inner fields can still be null (Java @Nullable). `pair.second
  ?: -1` is the safe-access pattern.
- **`Pair<List<String?>, List<String?>>` from
  `AttachmentHelper.toAttachmentItems`**: Group 6's helper returns
  nullable inner Strings, my first declaration used non-null inner
  Strings, type-match failed. Fix: align to source type.
- **`ScreenshotHelper.ShareCallback.onTaskDone(file: File?)`**:
  Group 6's ScreenshotHelper declared `onTaskDone` taking nullable
  File. My override said `File`, override signature mismatch. Fix:
  match the supertype.
- **`Habit(mHabit)` copy ctor**: Habit's primary ctor in Kotlin
  expects non-null Habit; `mHabit` is `Habit?`. Need `!!`.
- **Smart cast impossible** for `mIbBack` (mutable property):
  `ImageViewCompat.setImageTintList(mIbBack, ...)` needs non-null
  ImageView. The if-null guard doesn't smart-cast a `var` because
  of concurrent mutation possibility. Need `mIbBack!!`.
- **`Layout.getOffsetForHorizontal(line: Int, horiz: Float)`**:
  Java auto-widens Int → Float; Kotlin requires explicit `.toFloat()`.
- **`List<Int?>?` vs `List<Int>` from
  `ScreenshotHelper.updateThingUiBeforeScreenshot`**: same as
  attachment items, return type alignment.
- **`Snackbar` ctor non-null params**: Group 11 translation
  declared `Snackbar(app: App, type: Int, parent: ViewGroup, ...)`
  with non-null app and parent. Call site passes `mApp` (`App?`)
  and `mFlRoot` (`FrameLayout?`) — need `!!` at both.
- **`ItemTouchHelper.startDrag(holder: RecyclerView.ViewHolder)`**:
  takes non-null. `mRvCheckList!!.findViewHolderForAdapterPosition(pos)`
  returns nullable; need `!!` at the call.
- **`when (action.getType())` exhaustiveness**: Java switch-fallthrough
  with `default:break;` → Kotlin `when` block with `else -> {}`. Two
  branches early-return; in those cases the outer `when` exits and
  the post-`when` code (`updateUndoRedoActionButtonState();
  shouldAddToActionList = true;`) wouldn't execute under the
  problematic `UPDATE_COLOR` branch. Rewrote to set the flag and
  call the function before `return` in the unreachable-cast branch.
- **Override deprecation propagation**: my Group 15 changes
  triggered the Kotlin compiler to recompile dependent fragment
  files and emit `OVERRIDE_DEPRECATION` warnings on
  `getFragmentManager` / `onDismiss` / `onCreateView` overrides
  inheriting from the deprecated `android.app.DialogFragment`.
  Added `"OVERRIDE_DEPRECATION"` to the `@file:Suppress(...)` of
  10 fragment files (BaseDialogFragment, AlertDialogFragment,
  AudioRecordDialogFragment, ChooserDialogFragment,
  DateTimeDialogFragment, HabitRecordDialogFragment,
  LongTextDialogFragment, PatternLockDialogFragment,
  ThreeActionsAlertDialogFragment, TwoOptionsDialogFragment).
  PatternLockDialogFragment didn't have any file-level suppression
  before (no internal deprecated-API use); just `OVERRIDE_DEPRECATION`
  was added.
- **Param-name mismatch in supertype** at
  `ThingsListWidgetConfiguration.kt:68`'s anonymous
  `RadioChooserAdapter` override: my parameter was `holder` but the
  supertype RadioChooserAdapter.onBindViewHolder is `viewHolder`.
  Kotlin warns to avoid named-argument confusion. Renamed.
- **Cross-file DateTimeDialogFragment nullable receiver chain**:
  Group 13's translation accessed `mActivity!!.tvQuickRemind.text` /
  `.cbQuickRemind.isChecked` / `.quickRemindPicker.pickPreviousForUI()`.
  Those fields were Java public fields when Group 13 ran (platform
  types). Once DetailActivity became Kotlin, they're strict `T?`
  fields. Added `!!` at the 7 dependent call sites.

Verifications:
- V1: BUILD SUCCESSFUL after 2 fix iterations. First pass 15 errors
  (App?/FrameLayout? in Snackbar ctor, Pair-inner-type mismatch,
  onTaskDone override sig, List<Int?> vs List<Int>, smart-cast on
  mIbBack, Habit(mHabit) non-null, 8 cross-file errors in
  DateTimeDialogFragment from `mActivity.X.method()` chain). Second
  pass: getOffsetForHorizontal Int→Float, List<Int?> return type.
  Final: APK 10.4 MB, **0 Kotlin warnings** after stripping
  `open` (9 places) + adding OVERRIDE_DEPRECATION to 10 fragment
  files + renaming `holder`→`viewHolder` in
  ThingsListWidgetConfiguration.
- V2: grep audit clean — N1, E1; all remaining `==` are Long
  primitive (`thing.id`, `createTime`/`updateTime`), Int primitive
  (`mType == CREATE/UPDATE`, `state == Thing.FINISHED`, etc.), or
  compile-time constants.
- V3: cold-start renders 27 things identically to baseline; FAB
  tap opens DetailActivity in CREATE mode with random yellow
  accent, title/content edit fields, attachment/checklist/colour-
  picker/overflow action bar icons, "Remind me after 15 minutes"
  bottom-bar. Screenshots:
  `memory/screenshots/group15/01_home.png`,
  `02_detail_create.png`,
  `03_home_after_create.png` (28 things — +1 new yellow card
  "Group15_DetailActivity_test").
- V4 full required: drove full new-thing-creation flow via the
  new poll-then-tap pattern (`.claude/rules/adb.md`):
  cold-start → poll ThingsActivity ready → dump for fab_create
  bounds → tap → poll DetailActivity ready (5 iterations × 200ms
  = 1s) → input text "Group15_DetailActivity_test" → dump for
  ib_back bounds → tap → poll ThingsActivity (returns immediately
  on the OS dispatch) → wake screen → screencap.
  End-to-end logcat: zero FATAL / AndroidRuntime /
  NullPointerException(ywwynm) / VerifyError. Save-on-back path
  exercised: the new note persisted to the ThingManager and is
  visible at position 0 of the underway grid with the correct
  yellow accent (random color generation).

## 2026-05-20 — Kotlin migration Group 14 (small activities/)

Translated 11 Activity classes (~3294 LoC, plan estimated 1864 —
gap from post-plan feature additions):
EverythingDoneBaseActivity (base), ShortcutActivity,
DelayReminderActivity, AuthenticationActivity, HelpActivity,
StartDoingActivity, AboutActivity, ImageViewerActivity,
NoticeableNotificationActivity, DoingActivity, StatisticActivity.

The three "monster" activities (DetailActivity 3883 LoC,
ThingsActivity 2834 LoC, SettingsActivity 1771 LoC) are Groups
15/16/17 and remain Java.

Strategy: Option 1 (mechanical `m`-prefixed `private var` + explicit
`fun setX()/getX()`) for view fields; Option 3 (property syntax) for
Group 2/4 model getters at call sites; `EverythingDoneBaseActivity`
abstract methods preserved as Kotlin `abstract fun`.

Pattern split:
- 9 → `open class : EverythingDoneBaseActivity()` (HelpActivity,
  AboutActivity, ImageViewerActivity, NoticeableNotificationActivity,
  DoingActivity, StatisticActivity)
- 4 → `open class : AppCompatActivity()` (ShortcutActivity,
  DelayReminderActivity, AuthenticationActivity, StartDoingActivity)
- 1 → `abstract class : AppCompatActivity()` (the base)

Special handling:

- **EverythingDoneBaseActivity.doWithPermissionChecked
  `vararg permissions: String?`**: Java's `String...` accepted nullable
  elements de facto. Per N1, vararg ref param → `String?`. Internal
  delegate `ActivityCompat.requestPermissions(this, permissions, ...)`
  expects `Array<String>` non-null elements, so the spread inside the
  method needs `@Suppress("UNCHECKED_CAST") permissions as Array<String>`.
  This unblocks the cross-file spread call at
  `BaseThingWidgetConfiguration.kt:145`
  (`*PermissionUtil.getRequiredPermissionsForThings(mThings)!!`)
  which returns `Array<String?>?`.
- **`App.getThingAndPosition` returns `Pair<Thing, Int>?`** (Group 5
  declared this — non-null inner types via androidx.core.util.Pair
  whose generics are Java-untyped at runtime). Initial Kotlin drafts
  in 5 of the 11 files declared `Pair<Thing?, Int?>` (per N1
  default), all failed type-match. Fixed to `Pair<Thing, Int>`.
  At runtime `pair.first` and `pair.second` can still be null (Java's
  Pair fields are @Nullable in source — Group 5 left a latent
  N1 violation in App.kt), so the existing `if (pair.first == null)`
  null checks still work. `pair.second` access uses `?: -1` as the
  safe fallback.
- **DoingService.DoingBinder method-form access**: Group 10 translated
  the binder's `getThing()` / `getTimeInMillis()` / `getLeftTime()` /
  `isInStrictMode()` / `getPlayedTimes()` etc. as `open fun`, not
  Kotlin properties. So callers from Kotlin need method-call syntax
  (`mDoingBinder!!.getThing()`, `isInStrictMode()`). Initial draft
  used property syntax — all failed. Bulk rewrite via `replace_all`
  across DoingActivity. Same gotcha as Group 13's `ModeManager.
  getCurrentMode()` / `ThingManager.getThings()`.
- **`object : BaseThingsAdapter(this)` → `this@OuterClass`** (same
  Group 12 fix surface): NoticeableNotificationActivity and
  DoingActivity both construct anonymous BaseThingsAdapter subclasses
  passing `this`. Kotlin's constructor-arg lookup adds the supertype's
  Companion to implicit receivers, so bare `this` may resolve to
  `BaseThingsAdapter.Companion` instead of the outer activity.
- **`ThingDoingHelper.getStartDoingTypeTimes` returns
  `Pair<List<Int>, List<Int>>`**: Group 6 used non-null inner types.
  StartDoingActivity initial draft declared `Pair<List<Int?>, ...>`
  per N1 default — type-match failed.
- **`ThingDAO.getThingsForDisplay` returns immutable `List<Thing?>?`**:
  ShortcutActivity sorts via `Collections.sort(things, ...)` which
  needs MutableList. Wrapped with `ArrayList(...)` constructor (same
  pattern as Group 13's ThingDoingDialogFragment fix).
- **Deprecated APIs surfacing**:
  - `android.os.AsyncTask` (StatisticActivity has 5 inner AsyncTask
    subclasses) — file declares
    `@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")`.
  - `View.SYSTEM_UI_FLAG_*` + `setSystemUiVisibility` in
    ImageViewerActivity — deprecated since API 30.
  - `getFragmentManager()` / `overridePendingTransition()` /
    `android.app.DialogFragment` references in
    AuthenticationActivity / DelayReminderActivity / StartDoingActivity.
- **`StatisticActivity.getStrsForReminderGoalRecord` Cursor-after-close
  bug**: original Java has `int tCount = cursor.getCount();` AFTER
  `cursor.close()`. Pre-existing Java bug (using a closed cursor);
  per goal-A behavior snapshot, preserved verbatim in Kotlin.
  Plan §1 forbids fixing pre-existing bugs during translation.

Verifications:
- V1: BUILD SUCCESSFUL after 1 fix iteration. First pass 25 errors:
  6 in `Pair<Thing?, Int?>` signature mismatch, 8 in DoingBinder
  method-vs-property, 2 in BaseThingsAdapter `this` → `this@Outer`,
  1 in ShortcutActivity MutableList vs List, 2 in StartDoingActivity
  `Pair<List<Int?>, ...>`, 1 cross-file in
  BaseThingWidgetConfiguration vararg spread (fixed by changing
  base activity vararg to String?), 5 in NoticeableNotificationActivity
  `Thing(mThing)` non-null. Final: APK 10.4 MB, 0 Kotlin warnings.
- V2: grep audit clean — N1, E1; remaining `==` are Long primitive
  (`App.getDoingThingId() == id`, `thingId == mThing!!.id`) or Char
  (`src[i] == key`).
- V3: cold-start renders 27 things identically to baseline. Drawer
  opens correctly via hamburger tap, showing all 9 menu items.
  Screenshots: `memory/screenshots/group14/01_home_underway.png` +
  `02_drawer.png`.
- V4 required: logcat clean on cold-start + drawer-open path — zero
  FATAL / AndroidRuntime / VerifyError / ClassNotFoundException /
  NoSuchMethodError / NoClassDefFound across any
  `com.ywwynm.everythingdone.activities` class. Cold-start dex-link
  verification covers ALL 11 translated classes — even
  ImageViewerActivity / DoingActivity / StatisticActivity / etc.
  that the user doesn't trigger on cold-start get their Kotlin
  metadata validated by Android's verifier at first dex load.
- V4 user-facing (added after methodology fix — see updated
  `.claude/rules/adb.md`): drove drawer → Help → back → drawer →
  About via the new poll-then-tap pattern. HelpActivity renders
  11 help items with yellow Toolbar; AboutActivity renders full
  layout (app logo, "ywwynm's EverythingDone" title, version 2.0.0,
  OPEN SOURCE LICENSES link, pink support FAB). Zero crash markers
  across both activity transitions. Screenshots:
  `memory/screenshots/group14/03_help.png` + `04_about.png`.
  The earlier failed taps in this session (drawer-Help tap missed
  because of stale 1.43 scale-factor + auto-closing drawer) are the
  reason the rules were updated — the new pattern lands every tap on
  the first try by polling uiautomator dump for bounds rather than
  estimating from a scaled screenshot.

## 2026-05-20 — Kotlin migration Group 13 (fragments/)

Translated 18 DialogFragment / Fragment classes (~4636 LoC):
BaseDialogFragment, LoadingDialogFragment, TwoOptionsDialogFragment,
AttachmentInfoDialogFragment, HelpDetailFragment,
HabitDetailDialogFragment, HabitRecordDialogFragment,
LongTextDialogFragment, GradientOrientationDialogFragment,
AddAttachmentDialogFragment, AlertDialogFragment,
ThreeActionsAlertDialogFragment, ThingDoingDialogFragment,
PatternLockDialogFragment, AudioRecordDialogFragment,
ChooserDialogFragment, LicenseDialogFragment,
DateTimeDialogFragment (1435 LoC monster).

Strategy: Option 1 (mechanical `m`-prefixed `private var` +
explicit `fun setX()/getX()`) for view fields; Option 3 (property
syntax) for Group 2 / Group 4 model getter call sites.

Pattern split:
- All 17 dialog fragments → `open class : BaseDialogFragment()`
- BaseDialogFragment → `abstract class : DialogFragment()` with
  `protected open fun getLayoutResource(): Int` and a generic
  `f<T : View?>(id): T` view-binding helper
- HelpDetailFragment → `open class : Fragment()` (the only
  non-dialog member of the group; uses androidx.fragment.app)

Special handling:

- **android.app.DialogFragment is deprecated** (since API 28).
  Every fragment that extends it carries
  `@file:Suppress("DEPRECATION")` per plan §3.11 — the same
  upstream-deprecated-API rule used in Groups 3/6/8/11. The whole
  inheritance tree (BaseDialogFragment and all 16 subclasses) is
  tagged; HelpDetailFragment uses androidx.fragment.app.Fragment
  and needs no suppression. The original Java code emitted a single
  -Xlint summary; Kotlin emits one warning per call site so V1's
  "0 warnings" bar requires the file-level suppression.
- **f() generic view-binding helper**: Java's
  `protected final <T extends View> T f(int id)` translated to
  Kotlin `protected fun <T : View?> f(@IdRes id: Int): T` with
  `T : View?` upper bound (matches BaseViewHolder.kt pattern from
  Group 12). Callers can declare nullable or non-null T as needed.
  Three subclasses use the 2-arg variant `f(view, id)` to bind
  views from a specific sub-tree (DateTimeDialogFragment for tab
  layouts, HabitDetailDialogFragment, etc.).
- **ChooserDialogFragment field-decl order**: the Java original
  declared `mAccentBackground` near the bottom of the file (around
  line 218) but used it from `initUI()` at the top. Kotlin
  permits this because `var X: T? = null` has no initializer that
  references other instance state; the default-init runs in
  primary-constructor order which doesn't affect correctness.
  Reordered in Kotlin to group all private fields at top for
  readability.
- **DateTimeDialogFragment property-initialized listeners**:
  `mPageChangeListener` and `mTvTimeAsBtClickListener` are
  declared as `private val ...: T = object : ... { ... }`. The
  anonymous body references outer fields (`mTabInitiated`,
  `mVpDateTime`, `mTvTimeAsBtAfter`, `mDtpAfter`, `mDtpRec`).
  Kotlin's `object :` expression captures the outer `this`
  automatically — no explicit `this@DateTimeDialogFragment`
  needed since these are bare names, not `Outer.this.X` syntax.
- **ReminderHabitParams Java getter calls vs Kotlin properties**:
  Group 4's translation made `reminderInMillis`,
  `reminderAfterTime`, `habitType`, `habitDetail` Kotlin
  properties (Option 3). Initial Kotlin draft used the Java
  method form `rhParams.getHabitDetail()`, `rhParams.
  setHabitType(X)` etc. — all broke at compile. Per plan §3.9
  last row, Kotlin callers of a translated POJO must use property
  syntax (`rhParams.habitDetail`, `rhParams.habitType = X`).
  10 call sites in DateTimeDialogFragment fixed.
- **InputLayout / DateTimePicker constructors are non-null**:
  Group 11 translated these with non-null primary-constructor
  params (`context: Context`, `view: View`). Kotlin call sites in
  DateTimeDialogFragment / ThingDoingDialogFragment passing
  `mActivity` (`DetailActivity?`) and `mContentView` (`View?`)
  fail without `!!`. Mechanical fix at each ctor invocation.
- **`f(tab2, R.id.X) as TextView?` → `f<TextView>(tab2, R.id.X)!!`**:
  the cast form `as TextView?` produces a nullable; passing it to
  `InputLayout(context, textView: TextView, editText: EditText, ...)`
  (non-null params) needs `!!` anyway. Cleaner to assert at the
  binding site: explicit generic + `!!` returns non-null straight
  from the helper. 6 sites in DateTimeDialogFragment.findViewsRec.
- **Int + String concatenation**: Java's `AttachmentHelper.IMAGE +
  file.absolutePath` (Int + String) doesn't compile in Kotlin —
  `Int` has no `plus(String)` overload. Mechanical fix:
  `AttachmentHelper.IMAGE.toString() + file.absolutePath`. Per
  plan §3.9 String.valueOf mapping. 3 sites
  (AddAttachmentDialogFragment x2, AudioRecordDialogFragment x1).
- **`putExtra(EXTRA_MIME_TYPES, arrayOf("...", "..."))`**: works
  in Kotlin without explicit `arrayOf<String>(...)` — the
  resolution picks the `String[]` overload of `Intent.putExtra`
  directly. No fix needed (initial concern unfounded).
- **PatternLockView.OnPatternListener overrides**: Group 11
  translated this abstract class with non-null params
  `pattern: List<Cell>, simplePattern: String`. Initial Kotlin
  draft used the conservative `List<Cell?>?, String?` (per N1
  default), but Kotlin override matching requires exact signature.
  Fixed all 6 overrides (3 listener instances × 2 methods) in
  PatternLockDialogFragment.
- **ThingDoingHelper.getStartDoingTimeItems returns List, not
  MutableList**: Group 6 translation returns `List<String?>?`
  (immutable). ThingDoingDialogFragment needs to call
  `items.add(0, ...)`, which requires MutableList. Wrapped with
  `ArrayList(...)` constructor: `val items: MutableList<String?>
  = ArrayList(ThingDoingHelper.getStartDoingTimeItems(...)!!)`.
- **AttachmentHelper.kt:340 cross-file fix**: AttachmentHelper
  calls `aidf.setItems(getAttachmentInfo(...))` where
  getAttachmentInfo returns `List<Pair<String, String>?>?`
  (non-null inner Strings). My initial
  AttachmentInfoDialogFragment.setItems took
  `List<Pair<String?, String?>?>?` (wider/nullable inner). Kotlin
  List is invariant — these aren't subtype-compatible. Tightened
  AttachmentInfoDialogFragment's signature to
  `List<Pair<String, String>?>?` to match the upstream supplier.
- **String?.equals(String) order**: `dayTimes[0].equals("28")`
  where dayTimes[0] is `String?` — Kotlin allows this (extension
  for nullable receivers), but the reversed form `"28".equals(
  dayTimes[0])` is cleaner and matches the textual-mapping rule
  (Java's `.equals` is null-safe in the same way). Used reversed
  form for 1 site in DateTimeDialogFragment.updateUIRecYear.
- **Bundle? arguments**: Java's `getArguments()` returns Bundle?,
  Kotlin sees `arguments: Bundle?` property. Several
  `arguments.getString(...)` style calls require `arguments!!`.
- **action.getExtras() in DateTimeDialogFragment.
  addActionForUndoRedo**: ThingAction.getExtras() returns
  Bundle? per Group 2 model translation. Two call sites need
  `!!` chain to call putBoolean/putInt.
- **DateTimeUtil.getTimePeriodStr returns String?**: assigned to
  `var period: String = ...` requires `!!`. 1 site.
- **`mVisualizer!!` in AudioRecordDialogFragment**: `AudioRecorder.
  link(VoiceVisualizer)` non-null param; mVisualizer is nullable
  var with no smart-cast (mutable property). Mechanical `!!`.
- **endSettingTimeRec restructure**: original Java had
  `int day = 28` in catch block then continues to set detail. Kotlin
  `val day: Int` declared in try-catch can't escape with default;
  refactored to extract `applyConfirm(type, detail)` helper called
  from both try-success and catch paths so flow stays linear.

Verifications:
- V1: BUILD SUCCESSFUL after 1 fix iteration. First pass 53
  errors: 8 in DateTimeDialogFragment InputLayout/DateTimePicker
  ctor null-safety, 10 in ReminderHabitParams property-syntax,
  6 in PatternLockListener override signatures, 3 in Int+String
  concat, 8 in mTvErrorAfter / setAnchor null-safety, 6 in
  TextView/EditText nullable cast, 4 in dayTimes[i] null
  unwrap, 4 in arguments / Bundle null deref, 2 in
  ThingDoingHelper.getStartDoingTimeItems immutable list,
  1 in AttachmentInfoDialogFragment Pair generic type, 1 in
  AudioRecorder.link non-null. Final: APK assembled 10.4 MB,
  0 Kotlin warnings.
- V2: grep audit clean — N1, E1; all remaining `==` are Int /
  Char primitive (`mState == PREPARED`, `time <= 1 && str[length
  - 1] == 's'`, etc.) or compile-time constants
  (`DetailActivity.CREATE`).
- V3: cold-start renders 27 things identically to baseline
  (group12 + 1 test note). Drawer, FAB, and staggered grid all
  intact. Screenshot:
  `memory/screenshots/group13/01_home_underway.png`.
- V4 sampled: logcat clean on cold-start — zero FATAL /
  AndroidRuntime / VerifyError / ClassNotFoundException /
  NoSuchMethodError / NoClassDefFound / SQLiteException /
  RuntimeException across any `com.ywwynm.everythingdone.
  fragments` class. Note: cold-start does not instantiate any
  DialogFragment (they show only on user action); dex link +
  class-load validation is what V4 sampled actually covers here.
  Full V4 (interactive dialog open) is deferred to user testing
  per group 13 table (sampled, not required).

## 2026-05-20 — Kotlin migration Group 12 (adapters/)

Translated 16 adapter classes (~3848 LoC):
BaseViewHolder, SingleChoiceAdapter, MultiChoiceAdapter,
ImageViewerPagerAdapter, DateTimePagerAdapter, StatisticAdapter,
HabitRecordAdapter, ThingsAdapterWrapper, RadioChooserAdapter,
ImageAttachmentAdapter, AudioAttachmentAdapter, TimeOfDayRecAdapter,
RecurrencePickerAdapter, ThingsAdapter, BaseThingsAdapter,
CheckListAdapter.

Strategy per plan §7.3: Option 1 (mechanical `m`-prefixed `private
var` + explicit `fun getX()/setX()`) for ViewHolder fields. Option 3
for Group 2 model getter call sites (thing.id / type / state /
content / attachment / location; reminder.* properties;
habit.record).

Pattern split:
- 4 plain `open class` (StatisticAdapter, HabitRecordAdapter,
  ThingsAdapterWrapper, ImageAttachmentAdapter,
  AudioAttachmentAdapter, ImageViewerPagerAdapter,
  DateTimePagerAdapter, RadioChooserAdapter, TimeOfDayRecAdapter,
  RecurrencePickerAdapter, CheckListAdapter)
- 2 `abstract class` (SingleChoiceAdapter, MultiChoiceAdapter,
  BaseThingsAdapter)
- 1 concrete subclass (ThingsAdapter extends BaseThingsAdapter)
- 1 `open class BaseViewHolder` (base ViewHolder)

Special handling:

- **BaseThingsAdapter / CheckListAdapter `static {}` blocks**:
  translated to `companion object { init { … } }` per §3.3 S-4.
  Each block reads `App.getApp()!!` (App.getApp returns App? per
  Group 5 translation; not-null asserted since `<clinit>` happens
  after App.onCreate sets sApp).
- **BaseThingsAdapter.BaseThingViewHolder fields**: declared as
  `@JvmField val ...: T? = f(...)` per plan §7.3 Option 1 to keep
  Java field-syntax access for downstream callers (no `m`-prefix
  here since all Java fields were unprefixed public — direct port
  preserves `holder.cv`, `holder.tvTitle` etc.).
- **Inner class restrictions**: TimeOfDayRecAdapter.TimeTextWatcher
  Java had `static final int HOUR, MINUTE` constants. Kotlin
  prohibits companion objects inside `inner class`. Hoisted HOUR /
  MINUTE to outer TimeOfDayRecAdapter's companion as
  `private const val` per the same pattern as Group 11's ColorPicker
  fix (ALL_COLOR/NORMAL/DIVIDER hoisted out of ColorPickerAdapter).
- **`this`-as-Context vs `this`-as-Companion** in `object :
  SuperClass(this, …)` form (Group 8's existing
  ThingsListWidgetConfiguration / BaseThingWidgetConfiguration):
  when extending a Kotlin class with a companion, the constructor
  arg list adds the supertype's Companion to implicit `this`
  receivers, so bare `this` resolved to `RadioChooserAdapter.
  Companion` / `BaseThingsAdapter.Companion` and failed
  type-check. Disambiguated to `this@OuterActivity`. Newly
  documented gotcha — pre-Group 12, RadioChooserAdapter and
  BaseThingsAdapter were still Java (no Kotlin Companion) so the
  bare `this` resolved unambiguously.
- **BaseThingViewHolder field nullability propagation**:
  Group 8's BaseThingWidgetConfiguration accessed `holder.cv.
  setRadius(0f)` / `holder.ivStickyOngoing.setImageAlpha(alpha)` /
  `cv.setOnClickListener` / `mInflater.inflate(…)` directly.
  Once these became Kotlin `T?` (per plan §7.3 Option 1), each
  access required `!!`. Five call sites in Group 8's file added
  `!!`.
- **InterceptTouchCardView.LayoutParams**: Java's
  `(InterceptTouchCardView.LayoutParams) holder.flDoing.
  getLayoutParams()` accessed the inherited LayoutParams type
  through the subclass name (allowed in Java but not in Kotlin —
  Kotlin requires referencing the inner class through its actual
  declaring class). Replaced with `FrameLayout.LayoutParams`
  (CardView extends FrameLayout, so its LayoutParams is
  FrameLayout.LayoutParams).
- **`MutableList<T>.remove(int)` vs `.removeAt(int)`**: TimeOfDay-
  RecAdapter / CheckListAdapter — Java's `List<E>.remove(int)`
  positional overload becomes Kotlin's `.removeAt(int)`. Calling
  `.remove(int)` on a `MutableList<Int?>` would call the
  element-removal overload (removing the value, not the
  position). Audited all `.remove(<int>)` call sites and converted
  positional ones.
- **`MutableList<String?>?` vs `List<String?>?`**: CheckListHelper.
  toCheckListItems returns MutableList; my initial BaseThingsAdapter
  `val items: List<String?>? = ...` declaration didn't match
  CheckListAdapter's constructor `MutableList<String?>?` param.
  Tightened the local to MutableList.
- **ModeManager.getCurrentMode / ThingManager.getThings stay as
  fun**: Group 7's translations kept these as `open fun` (not
  property) because `currentMode` is a private backing field
  with side-effect setters elsewhere. Call sites in ThingsAdapter
  use method form `mModeManager!!.getCurrentMode()` / `mThing-
  Manager!!.getThings()` — initial draft used property syntax
  (caught at V1).
- **ThingManager.getInstance**: returns `ThingManager?` per Group
  7. Call sites in ThingsAdapter need `!!` chain:
  `ThingManager.getInstance(mApp)!!.update(...)`.
- **CheckListAdapter inner classes**: TextViewHolder (Java `static`)
  → Kotlin `private class` (nested, not inner). EditTextHolder
  (Java implicit-inner) → Kotlin `open inner class`. Its
  TextWatcher with self-field `mBefore` → kept as `object :
  TextWatcher` per §3.5 guard 2.
- **CheckListAdapter `removeItem(int posIn, ...)`**: Java mutated
  `pos` parameter. Kotlin params are `val` ⇒ introduced local
  `var pos = posIn` and rewrote subsequent uses.
- **CheckListAdapter `holder.tv.text = "..."` / `holder.tv.
  setHintTextColor(...)`**: Group 8 already established that
  TextView.text uses property syntax in Kotlin (Java auto-
  generated getter). Kept method-form for `setText` on EditText
  to disambiguate from `text` property (which exists as
  Editable; setting String would conflict).
- **Bitwise ops in Kotlin**: `flag & ~Paint.STRIKE_THRU_TEXT_FLAG`
  → `flag and Paint.STRIKE_THRU_TEXT_FLAG.inv()`; `flag |
  Paint.STRIKE_THRU_TEXT_FLAG` → `flag or Paint.STRIKE_THRU_TEXT_
  FLAG`. Per §3.9 mapping.
- **`tintRowIcon(iv: ImageView?)` early-return**: ImageViewCompat.
  setImageTintList's first param is `@NonNull ImageView` so
  passing a nullable `iv` fails. Pattern from BaseThingsAdapter's
  `tintCardIcon` reused (`if (iv == null) return` early-out, then
  smart-cast handles the rest).
- **Anonymous Runnables / Listeners**: SAM lambda where the body
  only captures outer state without self-deregistration / self-
  field / explicit OuterType.this; `object :` form for: ThingsAdapter
  `holder.cv.post(this)` recursion (guard 1 — self-registration),
  ThingsAdapter `Animation.AnimationListener` (3-method
  interface), BaseThingsAdapter Glide `RequestListener<Drawable>`
  (2 methods), CheckListAdapter `TextWatcher` (guard 2 — self-
  field `mBefore`), BaseThingsAdapter `Runnable` posting layout
  fixup (kept for symmetry though could be SAM).
- **RecurrencePickerAdapter `setRippleColor` / `toGdOrientation`
  helpers**: were Java `private static` methods. Translated to
  `@JvmStatic private fun` inside the companion object.
- **ImageViewerPagerAdapter `mTabs as MutableList<View?>?`**:
  ctor receives `List<View?>?` per N1, but the body calls
  `mTabs!!.removeAt(index)` requiring MutableList. Java's
  `List.remove` was OK because at runtime the impl was ArrayList.
  Cast at field-store to preserve textual-mapping.

Verifications:
- V1: BUILD SUCCESSFUL after one fix iteration. First pass had
  19 errors: 4 in BaseThingsAdapter (items-MutableList, Layout-
  Params resolution, App.getApp! nullity), 3 in CheckListAdapter
  (tintRowIcon null safety, App.getApp! ×2), 3 in ThingsAdapter
  (currentMode/things as property, getInstance! nullity), 3 in
  TimeOfDayRecAdapter (Collections.sort on List<String?>,
  companion-in-inner-class, str.split nullable receiver),
  1 in ThingsListWidgetConfiguration (this-as-Companion),
  5 in BaseThingWidgetConfiguration (this-as-Companion, 4×
  nullable field access). Final: APK assembled, 0 Kotlin
  warnings, 10.3 MB APK.
- V2: grep audit clean — N1, E1; all remaining `==` are
  Long primitive (`thing.id`), Int primitive (mType / position /
  viewType / size / type / cursorPos / item), or compile-time
  constants (View.* / ModeManager.* / Thing.* / ThingBackground.
  Mode.* / Def.PickerType.*).
- V3: cold-start renders 26 things identically to baseline —
  same staggered-grid layout with `interesting`, `wow` (May 17,
  5:46 reminded), `9999...` habit card (gradient + "3 times a
  month" + "Next reminder: on May 31, 8:39 in the morning" +
  "Last five times" + "Finished 0 time this month"), and the
  full `000` / `888888` / `777` / `666` / `6` / `4` (May 17,
  5:45 reminded) / `5` / `3` / `1` / `2` / `555` / `111`
  palette in their expected positions.
  Screenshot: `memory/screenshots/group12/01_home_underway.png`.
- V4 sampled: logcat clean on cold-start — zero FATAL /
  AndroidRuntime / VerifyError / ClassNotFoundException /
  NoSuchMethodError / NoClassDefFound / SQLiteException /
  RuntimeException from any `com.ywwynm.everythingdone.adapters`
  class.

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

## 2026-05-26 — Dark mode planning

Planned dark-mode support for EverythingDone using `$grill-with-docs`.
Created root `CONTEXT.md` with domain language for Thing Background,
Thing Foreground, App Chrome, Hybrid Chrome Surface, and Appearance Mode.

Wrote `docs/plans/DARK_MODE_PLAN.md` covering:
- Appearance Mode settings (`followSystemDarkMode`, `forceDarkMode`) with
  follow-system priority and conservative light defaults.
- Strict light-mode visual compatibility: DayNight may be used only as the
  dispatch mechanism; `values/` resources must preserve current light UI.
- App Chrome scope: home, settings, help, about, statistics, dialogs,
  popups, pickers, snackbar, noticeable-notification shell, and widget
  configuration chrome.
- Thing Background scope: Thing-owned backgrounds and foregrounds ignore
  app dark mode and keep existing lightness-based adaptive text/icon logic.
- State policy for system dark-mode changes, including special caution for
  `DetailActivity`, `DoingActivity`, `SettingsActivity`,
  `NoticeableNotificationActivity`, and widget configuration screens.

## 2026-05-26 — Dark mode implementation first pass

Implemented the first dark-mode slice for App Chrome:
- Added Appearance Mode settings plumbing:
  `followSystemDarkMode`, `forceDarkMode`, `AppearanceUtil`, app-start
  `AppCompatDelegate` mode application, Settings rows, immediate
  persistence, and follow-system priority over force-dark.
- Added light-equivalent semantic chrome colours in `values/`, dark
  equivalents in `values-night/`, a dark picker background, and DayNight
  theme dispatch for Settings, Help, About, Statistic, dialog, and widget
  configuration chrome.
- Updated App Chrome surfaces: home background/header/search/drawer,
  Settings rows and disabled runtime text, Help/About/Statistic,
  snackbar, shared dialogs, license/loading/two-action dialogs,
  date/time and colour pickers, audio-record dialog chrome, widget
  configuration chrome, and NoticeableNotificationActivity's shell.
- Preserved Thing-background surfaces: thing cards, Detail/Doing bodies,
  embedded Noticeable thing content, actual widget RemoteViews, and
  Thing foreground lightness logic remain outside Appearance Mode.
- Added state handling: Settings stores and recreates on `uiMode`; Detail,
  Doing, Noticeable, and widget configuration surfaces avoid destructive
  automatic recreation where needed.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after the
  final implementation pass.
- `git diff --check` passed with only existing CRLF conversion warnings.
- No visual dark/light screenshot pass was run yet; only one physical
  device was attached when checked, and no emulator was available.

## 2026-05-26 — Dark mode regression fixes

Addressed reported dark-mode and light-mode regressions after the first
implementation pass:
- Restored light-mode Drawer and Statistic icon behaviour by avoiding
  unconditional icon tint lists. Drawer item icons and home toolbar actions
  are now tinted app-accent yellow only in dark mode; Statistic row icons are
  tinted only in dark mode.
- Repainted Settings icons in dark mode: TextView compound icons follow
  their text colour, and ImageView help/info icons use the App Chrome control
  foreground.
- Added DetailActivity dialog-theme propagation so dialogs opened from the
  Thing-owned Detail screen resolve DayNight App Chrome dialog resources.
- Filled dark foreground gaps in Detail dialogs: add attachment, audio
  recording, two-action/share choices, pattern lock path, and start/auto-start
  doing chooser paths.
- Filled DateTimeDialog "After" and "Recurrence" foreground gaps for edit
  fields, dropdown affordances, pick-all/delete icons, recurrence row text,
  and recurrence picker unselected text.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.

Audio attachment ripple follow-up:
- Reinstalled the audio attachment card foreground and the three action icon
  backgrounds in `AudioAttachmentAdapter.onBindViewHolder()` based on the
  current `Appearance Mode`. Dark mode now explicitly uses
  `selectable_item_background_light`; light mode keeps
  `selectable_item_background`.
- Checked other Detail-body selectable usages. Image attachments, checklist
  controls, and the move-checklist control are on Thing/image-owned surfaces and
  intentionally keep their existing light ripple rather than following App
  Chrome dark mode.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.

Settings Appearance Mode wording/visibility follow-up:
- Updated Settings Appearance Mode labels to "Follow system dark mode" and
  "Enable dark mode", with Simplified Chinese using `深色模式` and Traditional
  Chinese using `深色模式` / `開啟`.
- Changed `SettingsActivity.updateUiAppearanceMode()` so the enable-dark row is
  hidden while follow-system is checked instead of being disabled and dimmed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device screenshot pass was run in this step.

## 2026-05-26 — Dark mode regression fixes, second pass

Fixed the issues reported after testing the first regression pass:
- Reworked icon tinting for PNG assets with baked-in alpha. Added
  `DisplayUtil.opaqueTintDrawable(...)`, which normalises the source alpha
  mask to the target colour alpha and returns a new drawable instead of
  tinting shared drawable state in place.
- Settings section compound icons now use the TextView's current colour in
  both light and dark mode, so the notification-reliability header icon gets
  the same blue treatment as other section icons. Dark help/info ImageViews
  use the normalised App Chrome control tint.
- Home Drawer menu item icons no longer get the yellow app-accent tint.
  The dark-mode yellow tint is limited to the drawer toggle icon and home
  toolbar action icons, with normalised alpha so PNG actions are not dimmed.
- DetailActivity is now a DayNight Activity theme while keeping
  Thing-background foreground rules in the screen body. BaseDialogFragment
  now creates dialogs with a forced DayNight dialog context and an App Chrome
  elevated window background, so Detail dialogs are dark surfaces instead of
  white windows.
- DateTimeDialog recurrence controls received another pass: dark unpicked
  recurrence chip background, normalised pick-all/delete/reminder icons, and
  explicit text/icon colours for the "new reminder time" row.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- `adb devices` showed one physical device and no emulator; no visual
  screenshot pass was run to avoid taking over the attached device.

## 2026-05-26 - Dark mode crash and Drawer icon follow-up

Fixed two regressions reported from device testing:
- `BaseDialogFragment` no longer creates dialogs from a detached
  `createConfigurationContext(...)` wrapper. It now uses an Activity-backed
  `ContextThemeWrapper`, preventing AddAttachment and other restored
  DialogFragments from crashing with `WindowManager$BadTokenException`.
- Drawer menu item icons are now explicitly repainted in dark mode with a
  non-yellow App Chrome control colour using `DisplayUtil.opaqueTintDrawable`.
  This preserves the rule that only the drawer toggle and home toolbar action
  icons use app-accent yellow.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device smoke test was run in this step; the attached device was not
  controlled from the agent session.

## 2026-05-26 - Dark mode Detail polish follow-up

Fixed the next set of user-reported dark-mode regressions:
- Restored BaseDialogFragment dialog width to content-driven `WRAP_CONTENT`
  after show, so `ThingDoingDialogFragment` and `DateTimeDialogFragment`
  are no longer widened by DayNight dialog minimum-width defaults.
- Updated Detail audio attachment rows to use App Chrome elevated card
  background, semantic title/metadata colours, and normalised control icon
  tint for play/pause/stop/delete/info states.
- Repainted DateTimeDialog dropdown arrows with normalised App Chrome control
  tint, and aligned the "new reminder time" row text and icon to the same
  control colour in both light and dark mode.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` failed once on a
  nullable `ImageView` receiver in `AudioAttachmentAdapter`, then passed after
  adding the intended non-null assertion.
- `git diff --check` passed with CRLF conversion warnings only.
- No device smoke test was run in this step.

## 2026-05-26 - Dark mode baseline corrections

Followed up on three user-reported baseline mismatches:
- Restored `fragment_add_attachment.xml` light-mode icon baseline by removing
  XML `drawableTint` and explicit semantic text colour from the four action
  TextViews. The historical dark-mode-before baseline used the raw PNG icons;
  dark mode still tints them at runtime in `AddAttachmentDialogFragment`.
- Confirmed the pre-android-16 `master` baseline for `ThingDoingDialogFragment`
  and `DateTimeDialogFragment`: the layout widths were already `280dp` /
  `280dp + 20dp + 20dp`. The widening came from dialog theme/window minimum
  width, so `EverythingDoneTheme.Dialog` now sets
  `android:windowMinWidthMajor/Minor` to `0dp`.
- Reverted snackbar visuals to the original dark `bg_snackbar` background and
  white text, and aligned the snackbar semantic colours to the same non-adaptive
  values.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device smoke test was run in this step.

## 2026-05-26 - Dialog width and DateTime recurrence colour correction

Fixed the follow-up regressions after searching Android/AppCompat dialog sizing
behaviour:
- Replaced the ineffective generic `WRAP_CONTENT` dialog-window reset with a
  BaseDialogFragment width hook. `ThingDoingDialogFragment` now sets an exact
  `280dp` window width, and `DateTimeDialogFragment` sets an exact `320dp`
  window width, matching the pre-android-16 layout baselines.
- Kept dialog theme min-width overrides for both platform and AppCompat attrs
  (`android:windowMinWidthMajor/Minor` and `windowMinWidthMajor/Minor`) so
  future AppCompat/Material dialog contexts do not reintroduce the wide default.
- Aligned the DateTimeDialog recurrence-tab "new reminder time" text and icon
  with the same `app_chrome_on_surface_secondary` tint used by the first
  reminder-time icons.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run in this step.

## 2026-05-26 - Search icon and DateTime recurrence foreground follow-up

Fixed two reported visual mismatches:
- `ColorPicker.updateAnchor()` now treats the hue-bucket all-colours sentinel as
  a visual app-accent toolbar icon in dark mode, preventing the search action
  icon from becoming a dim yellow when no colour bucket is filtering.
- Added dedicated DateTime recurrence foreground resources: existing reminder
  icons use the stronger `#C4...` level, and the "new reminder time" row uses
  the weaker `#80...` level for both text and icon, matching the checklist
  existing-item/new-item relationship.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run in this step.

Follow-up in the same area:
- Tinted the ColorPicker "all colours" checkbox compound drawable in dark mode
  with App Chrome secondary foreground; the PNG had previously stayed black.
- Removed XML `drawableTint` from the DateTime "new reminder time" row to avoid
  runtime tint plus XML tint stacking. Lowered the shared new-reminder foreground
  to `#40...` so the text matches the already-accepted icon strength.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run in this step.

## 2026-05-26 - Search no-result dark-mode and state leak fix

Fixed the reported home search no-result regressions:
- `ThingsActivity` now tints the `img_no_result` raster at runtime in dark mode
  with App Chrome hint foreground, while keeping the raw asset in light mode.
- Added a `hideSearchNoResult()` path and made it run when leaving search,
  resuming ThingsActivity outside search, or accidentally handling search
  results while `App.isSearching == false`.
- Replaced repeated `append("...")` in keyboard-driven no-result updates with a
  stable `no_result + "..."` assignment so keyboard callbacks cannot accumulate
  ellipses.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run in this step.

## 2026-05-26 - Detail follow-system dark-mode overlay refresh

Fixed the reported DetailActivity follow-system dark-mode lifecycle issue:
- Detail now records the current night-mode mask and detects real `uiMode`
  appearance changes separately from orientation/layout changes.
- On a night-mode change, Detail applies the AppCompat night mode update,
  dismisses the toolbar overflow popup, dismisses Detail-owned DialogFragments,
  dismisses old picker PopupWindows, recreates `ColorPicker` and
  `quickRemindPicker` with the updated DayNight resources, reattaches their
  listeners, preserves the current colour/quick-remind selection, and rebuilds
  the reusable `DateTimeDialogFragment` instance.
- Added Detail App Chrome theme items to the base, `values-v19`, and
  `values-v21` `EverythingDoneTheme.Detail` definitions so overflow menus and
  other floating Detail chrome resolve dark text/control/background resources
  on high-version devices.
- Completed the `values-v29` dialog theme override with the same window
  background / floating background / min-width items as the base dialog theme.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed twice after the
  implementation and versioned-style corrections.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run from the agent session.

Follow-up investigation and correction:
- Rechecked pre-Kotlin Java history for Detail checklist sizing. The legacy
  implementation also used `findViewHolderForAdapterPosition()` to sum laid-out
  non-finished rows when finished checklist items were collapsed, with fixed
  fallbacks only for separator/count rows (`3`/`4`). It did not estimate
  offscreen normal item heights.
- Android Support Library 25.3.1, AndroidX RecyclerView 1.4.0, and the Kotlin
  migration all keep the same Detail layout shape: a non-scrolling
  `RecyclerView` inside `NestedScrollView`, with `layout_height="wrap_content"`
  and inner nested scrolling disabled.
- Replaced the height-estimation approach. Detail now keeps the checklist
  RecyclerView height at `WRAP_CONTENT`; `CheckListAdapter.getItemCount()`
  exposes only visible rows while finished items are collapsed and exposes the
  complete item list when expanded.
- Checklist structural edits now refresh the adapter data set instead of
  combining granular insert/remove notifications with a changing collapsed item
  count. EditText text changes still update the backing item and only request a
  layout pass through Detail, so ordinary typing should not lose focus.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed twice after the
  adapter-visible-row change.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run from the agent session.

Follow-up keyboard focus correction:
- Checklist "new item" insertion no longer actively hides the keyboard. The
  add-row icon path returns before the checkbox-toggle `hideKeyboard()` call,
  and `insertItem()` no longer clears focus before `DetailActivity.onInsert()`
  focuses the created EditText.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after the focus
  correction.
- `git diff --check` passed with CRLF conversion warnings only.

Detail overflow follow-up:
- Fixed `include_actionbar_detail.xml` still using
  `app:popupTheme="@style/Theme.AppCompat.Light"`. Detail toolbar popups now
  use `EverythingDoneTheme.Detail.Popup`, a DayNight overlay with App Chrome
  floating background and foreground colours.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after the popup
  theme change.
- `git diff --check` passed with CRLF conversion warnings only.

Image viewer attachment-info follow-up:
- Added `uiMode` to `ImageViewerActivity`'s handled config changes so switching
  follow-system dark mode does not destroy the image viewer while a dialog is
  open.
- `ImageViewerActivity` now tracks night-mode changes, dismisses stale image
  viewer dialogs, and reopens the attachment-info dialog for the current image
  if it was visible during the mode switch.
- `AttachmentInfoDialogFragment` no longer depends only on setter-injected
  fields. Accent background/color and attachment info rows are persisted in
  fragment arguments and restored after system recreation; its adapter now
  treats missing rows as an empty list instead of crashing during measure.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after the image
  viewer/dialog fix.
- `git diff --check` passed with CRLF conversion warnings only.

Follow-up fixes in the same Detail area:
- Preserved quick-remind popup position after follow-system light/dark changes
  when the user left the default "15 minutes later" selection untouched. The
  picker recreation path now infers the selected row from `rhParams` when the
  old picker reports no explicit picked row, and AFTER_TIME pickers initialise
  their adapter selection to row 8.
- Fixed long editable checklists being clipped inside Detail. The checklist
  RecyclerView remains non-scrollable, but Detail now updates its explicit
  layout height from checklist data changes. It uses already-laid-out holder
  heights when available and pure text/layout estimates for offscreen rows, so
  the outer `NestedScrollView` can reveal all checklist rows and the "new item"
  row without creating/binding item views during RecyclerView measurement.
- Reverted the previous `LayoutManager.onMeasure()` row-binding approach after
  it disturbed EditText focus/IME input and could race empty-row deletion.
  Empty-row backspace with no target row now hides the keyboard directly
  instead of asking RecyclerView for adapter position `-1`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run from the agent session.

Noticeable notification ripple and Things return-animation follow-up:
- `NoticeableNotificationActivity` now installs its action-button backgrounds
  programmatically from the current `Appearance Mode`: light mode keeps the
  existing black ripple, while dark mode uses the white ripple resource. It
  also reapplies the DayNight delegate and repaints the shell on `uiMode`
  changes so already-open noticeable notifications do not keep stale ripple
  drawables.
- `ThingsActivity` records whether it was restored from saved state, which is
  the path hit when it is recreated in the background after a system light/dark
  change. Restored lists no longer play the initial "appearing" card animation.
- Reverted the experimental Detail-return payload refresh after device testing
  showed it made the list jump back to the restored position without the normal
  item update affordance. Same-type Detail returns again use ordinary
  `notifyItemChanged(position)`; only the restored list-wide appearing animation
  remains disabled.
- Restored ThingsActivity instances now run the RecyclerView init runnable
  synchronously instead of waiting 240 ms. Normal cold/open animations keep the
  historical delay, but system-recreated home screens should not briefly expose
  an empty list before RecyclerView restores its scroll state.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- No device visual smoke test was run from the agent session.

Audio attachment info icon dark-mode correction:
- Repainted the audio attachment info icon during every
  `AudioAttachmentAdapter.onBindViewHolder()` call. The play/delete/stop icons
  were already rebound with the current App Chrome control colour, but the info
  icon was only tinted in `ViewHolder` init, so existing holders could keep a
  stale light-mode tint after a system dark-mode change and look too dim.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.

Dark-mode ripple correction:
- Replaced the API 21+ shared `selectable_item_background` and
  `selectable_item_background_light` resources with direct `RippleDrawable`
  XMLs using transparent content and an explicit mask. This covers the hardcoded
  selectable backgrounds used by Settings, Help, App Chrome dialogs, popup
  picker rows/buttons, attachment dialogs, chooser dialogs, DateTime controls,
  and related button-like rows.
- Added `app:rippleColor="@color/app_chrome_ripple"` to the audio-record dialog
  Material FAB so it participates in the same App Chrome ripple policy as the
  surrounding dialog controls.
- Follow-up after device testing showed no visual change: the dark build was
  still packaging `drawable-night/selectable_item_background.xml` as
  `drawable-night-v8`, so dark mode bypassed the new `drawable-v21` ripple.
  Added `drawable-night-v21/selectable_item_background.xml` with the same direct
  masked ripple. Rebuilt debug resources confirmed only `drawable-night-v21` and
  `drawable-v21` selectable backgrounds remain in packaged resources.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.

Launcher widget Android 16 follow-up:
- User clarified that the repeat animation after toggling a checklist item from
  a widget looked like the whole Things list reloading from the bottom, not a
  RecyclerView `notifyItemChanged()` change animation.
- Traced that visual path to `ThingsActivity.justNotifyAll()`, which reloads
  the Things data and always enabled `things_show` before
  `notifyDataSetChanged()`.
- Kept the full reload behavior for correctness, but parameterized
  `justNotifyAll()` and made the `onResume()` branch that consumes background
  `App.justNotifyAll()` call `justNotifyAll(false)`, so widget/background
  catch-up refreshes do not replay the list appearing animation.
- Confirmed the previous `ThingsAdapter.kt` / `ids.xml` experiment remains
  rolled back; this change does not touch widget RemoteViews data generation or
  adapter resource ids.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed and produced
  `app\build\outputs\apk\debug\app-debug.apk` at 2026-05-27 13:11:49.
- `git diff --check` passed with CRLF conversion warnings only.

Launcher widget rendering follow-up:
- Fixed Create widget and Things List widget create-button colour staleness by
  routing create clicks through `ShortcutActivity`. The RemoteViews PendingIntent
  now carries only the create action (and the list limit when applicable), so
  each click resolves the current `App.newThingBackground` immediately before
  opening `DetailActivity`.
- Added explicit luminance-adaptive icon rendering in `AppWidgetHelper` for
  widget card icons that sit on Thing backgrounds: checklist state icons,
  private lock, sticky/ongoing markers, reminder/goal icons, habit icon, habit
  record dots, audio attachment icon, and finished/deleted state icons.
- Added ids for the widget audio attachment ImageViews so RemoteViews can swap
  the black/white audio assets just like the normal card adapter does.
- Tightened widget reminder/habit/state text layout constraints by giving text
  next to icons the remaining row width plus single-line ellipsizing. This
  targets the intermittent launcher rendering case where a reminder icon showed
  but the adjacent reminder time text was missing.
- Did not modify `ThingsAdapter.kt` and did not recreate `values/ids.xml`.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed and produced
  `app\build\outputs\apk\debug\app-debug.apk` at 2026-05-27 14:36:32.
- `git diff --check` passed with CRLF conversion warnings only.

Launcher widget create/configuration correction:
- User found the standalone Create widget could fail on the second click in this
  sequence: keep ThingsActivity alive by pressing Home, click Create widget,
  press Back to abandon the empty created Thing and return to the Things home
  screen with the abandon snackbar, press Home again, then click Create widget.
  The second click resumed the Things home screen instead of opening
  `DetailActivity` in CREATE mode.
- First tried restoring the standalone Create widget to a direct
  `DetailActivity` PendingIntent plus refreshing standalone Create widgets
  after `App.newThingBackground` rolls. Device testing showed that approach did
  not fix the repeated task/colour staleness.
- Replaced the direct path by copying the Things List widget create action:
  standalone Create now opens `ShortcutActivity` with
  `SHORTCUT_ACTION_CREATE`, carries `KEY_LIMIT = ALL_UNDERWAY`, and uses the
  shared widget BAL PendingIntent helper. The Things List widget keeps the same
  `ShortcutActivity` create path with its selected list limit.
- Removed the temporary `AppWidgetHelper.updateCreateAppWidgets()` helper and
  the `DetailActivity` call site added by the failed direct-path attempt.
- Fixed `ThingsListWidgetConfiguration` so reopening settings reads the stored
  negative `ThingWidgetInfo.thingId` and preselects the saved limit instead of
  always showing "All".

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed after the final
  create-action correction and produced `app\build\outputs\apk\debug\app-debug.apk`
  at 2026-05-27 15:35:00.
- `git diff --check` passed with CRLF conversion warnings only.
