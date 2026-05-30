# Decisions

## 2026-05-30

### Full-span home-card entry lives in Detail overflow

Home Card Span Mode should be toggled from DetailActivity's overflow menu for
real editable Things. The create screen should not expose the action in the
initial implementation; users can create the Thing first, then change its home
card span after it has a stable persisted identity.

The Simplified Chinese menu labels should be "放大记事卡片" for switching from
normal span to full span, and "缩小记事卡片" for switching from full span back to
normal span.

Use `homeCardSpanMode` naming consistently for this state. Do not use the
broader name `style`, because the value only controls card span and should not
imply image placement, typography, or a full layout style.
The database column follows existing schema style as `home_card_span_mode`,
while Kotlin properties and helpers use `homeCardSpanMode`.

Changing Home Card Span Mode should participate in DetailActivity's normal
edit lifecycle and undo/redo stack. It should not behave like sticky or
ongoing, which write immediately and finish the Detail screen. The overflow
label updates immediately after the toggle, while persistence happens through
the existing create/update return flow.

Clicking the Detail overflow action to enlarge or shrink a Home Card should
show immediate feedback because the visual result is only visible after
returning to the home list. Use DetailActivity's normal Snackbar when it is
available; fall back to Toast only if the Snackbar has not been initialized.
The Simplified Chinese messages are "已放大记事卡片" and "已缩小记事卡片".

Changing Home Card Span Mode does not change a Thing's `location` or business
ordering. Returning from Detail should treat the change as an update to the
same item; the home list may relayout the waterfall spans, but it should not
delete/reinsert the Thing or replay the whole-list appearing animation.

Implement the first iteration as reliable full-span behavior plus conservative
width adaptation. Keep the existing card content order: image, title, private
lock, content/checklist, audio, reminder/habit, padding, and doing cover. Do
not include magazine-style text centering, artistic typography, or manual image
placement in the first implementation.

Full-span image cards keep the existing top-image and `centerCrop` behavior in
the first iteration. Normal cards keep the existing `cardWidth * 3 / 4` image
height. Full-span cards should bound image height, for example around
`min(fullCardWidth * 9 / 16, screenHeight * 0.36)` with a reasonable dp
minimum, so wide cards do not become excessively tall. This visual ratio can be
revisited later.

Full-span text cards should keep the first iteration's normal card typography:
left-aligned title/content, existing length-based content text-size formula,
and existing Thing Foreground colour logic. Do not add centred text, artistic
fonts, or a separate text-poster layout yet. The first iteration may increase
the content `maxLines` for full-span cards so wider cards do not truncate too
aggressively.

Full-span checklist cards keep the existing single-column checklist rendering
and existing card-level checklist toggle behavior. Normal span keeps the
current maximum of 8 visible checklist rows; full span may raise the visible
maximum to 12. Do not introduce a two-column checklist or a full-span-specific
checklist layout in the first iteration.

Full-span audio, reminder, goal, and habit sections keep their existing
vertical block structure in the first iteration. Audio-only cards may continue
using the existing enlarged audio layout. Reminder/goal and habit metadata
should gain horizontal room from the wider card, but should not become a
separate horizontal information bar yet. The doing cover continues to cover the
final measured card bounds.

Full-span hidden private cards still hide all private content. They should use
the full-span width, keep the existing title behavior, and enlarge the lock
icon so the card does not read as an overly wide, shallow strip. The first
iteration should not reveal any image, content, checklist, audio, reminder, or
habit metadata for locked private Things.

Home card fixed-width ownership should stay on the card content container
(`llContent`) rather than being split across content minimum width and image
child width. Full-span cards, hidden private cards, and image cards all need a
known content width; set that on `llContent.layoutParams.width`, reset stale
minimums during bind, and let the image container use `MATCH_PARENT` inside the
content container. This prevents recycled hidden-private holders from affecting
image-card image measurement.

Full-span cards with sparse visible content should have an adjustable minimum
content height so they do not become overly wide, shallow strips. Apply this to
hidden private cards, title-only or short-text cards, and audio-only cards.
Avoid forcing the minimum height onto image, checklist, reminder, habit, or
long-text cards. Keep the height value in a resource token rather than hardcoding
it in adapter logic.

Home Card Span Mode affects only the ThingsActivity home list in the first
iteration. It should not change DoingActivity's embedded cards,
NoticeableNotificationActivity's embedded Thing row, single-Thing widgets,
Things List widgets, or widget configuration previews. Shared card binding code
may support full-span sizing, but the decision to apply full span belongs to
the home-list adapter.
Search results are still a filtered home list, so they should respect each
Thing's Home Card Span Mode.

Existing Things and newly created Things default to normal span. Database
upgrade should add `home_card_span_mode` with default `0`, and no migration
should automatically promote existing rows to full span.

Toggling Home Card Span Mode updates the Thing's `updateTime`, matching other
Detail visual edits such as changing the Thing Background.

Implementation must handle both upgrade and fresh-install database paths. The
schema version should increase, old databases should receive
`home_card_span_mode integer not null default 0`, fresh installs should create
the column directly, and every fixed-column initial insert/header insert path
must provide the new normal-span value.

The v10 migration should guard new trailing Thing columns with a column-exists
check before executing `ALTER TABLE`. This keeps v9 -> v10 simple while also
allowing older restored databases that skipped an intermediate app version to
receive any missing trailing columns without duplicate-column failures.

Backup and restore do not need a separate format layer for this feature,
because backups copy the database file. Restored older databases should be
handled by the normal SQLiteOpenHelper upgrade path, which adds
`home_card_span_mode` with default normal span.

## 2026-05-29

### Full-span home-card state belongs to the Thing

The new full-span home-card feature should be a persistent Thing-level
presentation preference, not a temporary RecyclerView state and not a property
of one home-list filter. When a real user-created Thing is marked as a
Full-Span Home Card, that preference should apply wherever the Thing appears in
the home list: all underway lists, type-specific lists, finished, and deleted.
System rows such as the invisible header, empty-list notification rows, welcome
items, and notification pseudo-things are outside the feature unless explicitly
revisited later.

Use an integer field for this persistent state rather than a boolean. The
initial feature still starts from the full-span need, but the stored shape
should leave room for future home-card presentation modes without another
schema rename. The field represents only the Home Card Span Mode, not the
full rendering style: `0 = NORMAL`, `1 = FULL_SPAN`. Image placement,
text-centering, font treatment, checklist density, and other visual choices are
rendering strategies derived from the Thing's content and current span mode.
The mode is available to every real, normally editable Thing, including the
initial welcome Things if they enter the normal edit path. It is not available
to the invisible header row, empty-list rows, or notification pseudo-things.

### Dynamic home-card width must account for RecyclerView padding exactly once

`BaseThingsAdapter` may refresh `mCardWidth` from the attached RecyclerView so
private hidden cards, image cards, and rotation/multi-window cases use the live
span width. When deriving that width from `RecyclerView.width - paddingLeft -
paddingRight`, the formula must subtract only per-item left/right margins. Do
not reuse the full-screen `DisplayUtil.getThingCardWidth(...)` spacing formula
with `(spanCount + 1)`, because the RecyclerView outer padding has already been
removed and would be double-counted.

### Thing-owned local controls use contrast foregrounds

When a local control paints its own background with a Thing Background or Thing
accent, any text or icon drawn directly on that control must use the same
lightness-based foreground rule as Thing cards. Use `BackgroundUtil.onColor(...)`
for text on the painted surface, and use Thing-owned ripple colours for press
feedback. This applies to compact dialog action cards such as ThingDoingDialog's
"Start doing" button and to picked recurrence cells in DateTimeDialog.

### NoticeableNotificationActivity keeps its embedded Thing row square

`NoticeableNotificationActivity` is a Hybrid Chrome Surface: the outer
dialog-like App Chrome shell may be rounded, but the embedded full-row Thing
card inside the dialog is still Thing Background content and should remain a
square full-row surface. Do not let normal home-card corner radius or App Chrome
dialog corner clipping leak into that embedded card.

## 2026-05-28

### Camera colour sampling previews stay inside the dialog

DetailActivity camera colour sampling should no longer repaint the underlying
Thing Background while the sampling dialog is open. The dialog owns the live
preview state: it shows the sampled colour in its own preview strip and tints
the "Use Color" action with the sampled colour. DetailActivity commits a single
final pure-colour Thing Background only when the user accepts the sample.

### Thing Background Information is user-facing, not diagnostic

The colour-information dialog should keep the preview and user-facing colour
values: recognised name, RGB, Hex, and HSL. It should not show the matched
dataset entry, matching method, match distance, or dataset/source row in the
main UI. Long gradient information should be height-bounded so the final action
button stays visible.

## 2026-05-27

### Camera colour sampling previews live and commits once

The Detail colour-picker camera entry should treat live camera sampling as a
preview state, not as repeated committed colour changes. While the camera
sampling dialog is open, the sampled centre colour may repaint the
DetailActivity Thing Background in real time. When the user accepts the sampled
colour, DetailActivity should commit a single final pure-colour
Thing Background and add at most one `ThingAction.UPDATE_COLOR`.

Closing or cancelling the camera sampling flow should restore the Thing
Background that was active before sampling began and should not add an undo
entry.

### Camera-picked colour names should use a fine fixed name library

Camera-picked colours and colour-info surfaces should identify colours through
a fine-grained fixed colour-name library rather than a small algorithmic
"modifier + base colour" vocabulary. The implementation should prefer a
dataset with clear provenance, licence, and enough entries for nearest-colour
matching against arbitrary RGB samples.

Use `meodai/color-names` as the colour-name data source. The project provides a
roughly 31k-entry curated full list, MIT licence, multiple downloadable formats,
and documented nearest-colour API behaviour. Do not use `colornames.org` as the
primary bundled source despite its larger community dataset, because its
vote-driven naming quality is less predictable for an offline app UI.

For display languages, English should use the upstream `meodai/color-names`
name exactly. Simplified Chinese should use a fine-grained translated version of
that same name set; Google Translate is explicitly allowed for this bulk
colour-name translation pass. Other app locales should fall back to the English
source names until a later translation pass is requested.

### Detail should expose current Thing Background information

DetailActivity should add an options-menu action that opens colour information
for the current Thing Background. The information surface should support pure
colours and gradients, include RGB, Hex, and HSL values, and include the colour
name source when a fixed name-library match is shown.

The colour-information action should be an overflow menu item, not an always
visible toolbar icon. It should be available from every DetailActivity state
menu, including create, underway, habit variants, finished, and deleted, because
all Thing states still have a Thing Background that can be inspected.

The camera colour-sampling entry should live in the Detail ColorPicker's bottom
action area in `COLOR_EDIT` mode, not inside the two-column colour grid. Add a
visible divider between the colour grid and the bottom tool actions so the
camera entry is read as a tool entry rather than another colour candidate. The
gradient-orientation action remains conditional on a gradient selection; the
camera entry is always available in `COLOR_EDIT`.

The camera colour-sampling dialog should use explicit actions. Live sampling is
only a preview until the user taps "Use Color". Back, outside dismissal, and
"Cancel" restore the pre-sampling Thing Background and add no undo entry. The
dialog layout should keep the rounded square live preview as the primary visual,
show the current localized/English colour name beneath it, and place compact
Cancel / Use Color actions below the name.

Use CameraX for the embedded camera colour-sampling preview. The project did
not previously have an embedded live-camera preview path, and CameraX should
own preview lifecycle, rotation, and device compatibility while an
`ImageAnalysis` pipeline samples the centre colour at a controlled rate.

Request `android.permission.CAMERA` only when the user opens the camera
colour-sampling tool. If permission is denied, close or avoid opening the
sampling dialog and show the existing Detail snackbar-style error path rather
than leaving an empty camera surface or forcing a settings deep link. Add the
manifest camera permission while keeping the existing optional camera hardware
feature declaration.

Bundle the `meodai/color-names` dataset as app data under `assets` rather than
as Android string resources. Keep English and Simplified Chinese colour names
in compact dataset files, include attribution/licence material, and load the
dataset lazily. Chinese app locales should load the translated dataset; other
locales can load the English source dataset.

Colour-name matching should prioritise perceptual accuracy. Final committed
colours and the Detail colour-information surface should use a full
CIEDE2000-style nearest-colour match against the full precomputed Lab dataset.
The live camera preview may throttle updates, average a small centre sample
region, cache recent results, and use cheaper candidate filtering to remain
smooth, but the committed result should be recomputed with the full precise
matcher.

The camera sampling dialog and the Detail colour-information surface should
share the same colour parsing/matching service and result model so the same RGB
value resolves to the same names and numeric values everywhere. Their UI
surfaces stay separate: camera sampling shows a lightweight, large live name
display, while colour information shows the full source, RGB, Hex, HSL, and
gradient breakdown.

Colour-information source attribution should be dataset-level, not per-entry.
The `meodai/color-names` distribution does not provide reliable per-colour
source metadata for each matched entry. Show the dataset source and licence,
the matching method, the matched entry name/hex, and the match distance instead
of implying that each colour name has a separate displayed source.

Lock the bundled `meodai/color-names` dataset to the exact package version or
Git commit used during implementation. Do not fetch the latest dataset at build
time. Include the locked version, download date, and MIT licence attribution in
the bundled asset metadata so colour-name matching remains stable and
user-visible names do not change unexpectedly when upstream data changes.

Implementation lock: the bundled dataset uses `color-name-list` 14.38.0,
downloaded from unpkg on 2026-05-27. Google Translate's unauthenticated endpoint
returned a reCAPTCHA block during the implementation attempt, so the shipped
asset reserves a Simplified Chinese name column but falls back to upstream
English names until a translated file or official Google Cloud Translation
credential is available.

The dataset should be stored as a plain `.tsv` asset, not `.tsv.gz`. AGP's asset
merge step expands `.gz` assets and strips the `.gz` suffix, so runtime
`AssetManager.open(...)` must target the packaged `.tsv` path.

For gradient Thing Backgrounds, the colour-information surface should show
three colour sections: gradient start colour, gradient end colour, and the
representative colour returned by `ThingBackground.representativeColor()`. Each
section should include the matched colour name plus RGB, Hex, and HSL values.
The surface should also show a gradient preview that preserves the stored
gradient orientation.

### Locale override contexts must not freeze non-locale configuration

In-app language support should wrap contexts with a locale-only
`Configuration` override. Do not copy the full current `Configuration` into
`createConfigurationContext(...)` and then change just the locale, because that
also snapshots fields such as `uiMode`. A copied override can prevent
follow-system Appearance Mode changes from reaching Activity resources when a
specific app language is selected.

### Do not change ThingsActivity theme opportunistically

`EverythingDoneTheme.Things` must not be converted to a DayNight parent as an
incidental fix for unrelated UI work. The dark-mode plan treats home App Chrome
theme conversion as planned dark-mode work that needs explicit light-mode visual
regression, not as a side effect of button-like ripple changes.

### Button-like control ripple work excludes full-row and full-card surfaces

Button-like control ripple shaping should target local command controls: compact
text actions, icon+text actions, and icon-only actions that behave like buttons
even when they are built from plain views. Full-row and full-card clickable
surfaces are not part of this change and should keep their current interaction
surface unless handled by a separate design pass. Full-width dialog action rows
count as full-row surfaces and are excluded too.

Compact text buttons on dialogs and dialog-like surfaces, including affirmative
buttons such as "Got it" as well as cancel/confirm buttons, are included in the
button-like control ripple shaping pass.

Full-row affirmative "Got it" buttons in dialogs are treated as layout debt, not
as intentionally full-row action surfaces. Convert them to the same bottom-end
compact text-button form used by most dialogs, then apply the pill ripple.

DateTimeDialog's TabLayout tabs are included only for touch-feedback shaping.
Keep their existing selected-state semantics: accent or gradient tab text and
the bottom indicator stay unchanged; only the pressed ripple should become a
pill-shaped rounded rectangle.

DateTimePicker popup entry controls, such as the visible time-unit TextView with
its dropdown icon, are included. The full-row selection items inside the popup
are excluded and keep their current full-row feedback.

### Button-like control ripple colour follows its owning surface

Button-like controls on App Chrome should use Appearance Mode-owned ripple
colours. Button-like controls drawn directly on a Thing Background should use
the Thing representative colour's lightness to choose black or white translucent
ripple feedback. Gradient Thing Backgrounds still use a representative single
colour for the ripple waveform, because Android `RippleDrawable` exposes ripple
colour as a `ColorStateList`, not a gradient.

Button-like control ripple drawables are dynamic state, not one-time XML chrome.
Reinstall or retint them when their owning surface changes colour ownership:
Thing-background controls must update when the Thing colour/background changes,
and App Chrome controls must update when Appearance Mode changes in-place.

### Dialog and popup corner radius has its own App Chrome token

Custom App Chrome dialogs and popup pickers should use a dedicated corner-radius
token, `@dimen/app_chrome_dialog_popup_corner_radius`, currently set to `16dp`
for visual review. This keeps dialog and popup shape adjustable without
changing the home Thing card radius.

### EverythingDone remains the primary Android update target

Future app updates should be made in the `EverythingDone` project. The
`Everything-Android` directory can be used as a reference for designs, code, or
new functionality, but it is not the target project for changes and may be
deleted later.

### Background DetailActivity refreshes from storage after remote widget actions

Launcher widget and notification actions should only be blocked when the
matching `DetailActivity` is actually visible in the foreground. A stopped but
still alive Detail screen, such as one left by pressing Home, must not prevent
the remote action from writing the database.

`DetailActivity` should keep a rendered Thing snapshot and, when returning to
the foreground, compare that snapshot with the latest Thing from the manager or
DAO. If the same Thing was changed externally while Detail was stopped, rebuild
the screen instead of calling `initUI()` directly. `initUI()` is not a safe
standalone refresh entry point because it assumes freshly initialised views,
adapters, watchers, and undo/redo state.

### ThingsActivity header collapse endpoint is measured, not density-guessed

`ActivityHeader` should not rely on hard-coded `scrollY * factor` values to
place the title inside the toolbar when the Things list collapses. Those factors
only matched the original Chinese/English text metrics on common toolbar
heights; they drift with other locales, fallback fonts, font scale, device
metrics, and any toolbar height variant.

Keep the legacy collapse distance and title scale timing, but compute the
collapsed header `translationY` from measured coordinates: toolbar vertical
centre minus the scaled title visual centre. Interpolate from `0` to that
measured endpoint while scrolling. Recompute after title text changes so locale
or drawer-category changes can update the endpoint.

### AppWidget collection click templates must be mutable
AppWidget collection rows that use `RemoteViews.setPendingIntentTemplate(...)`
plus `setOnClickFillInIntent(...)` need a mutable template `PendingIntent`.
The launcher/widget host supplies the row-specific fill-in intent at send time;
if the template is created with `FLAG_IMMUTABLE`, Android ignores that
additional intent data and row extras such as thing id and checklist position
never reach the app.

Keep ordinary direct widget click actions immutable. Use `FLAG_MUTABLE` only
for explicit-component templates whose behavior depends on collection row
fill-in extras.

### AppWidget activity PendingIntents must opt in to BAL creator delegation
For widget clicks that launch an Activity, the app is the `PendingIntent`
creator and the launcher is the sender. With target SDK 35+ / Android 16-era
background activity launch hardening, the creator can no longer rely on the
launcher to contribute sender-side privileges. Widget `getActivity(...)`
PendingIntents should therefore be created with an `ActivityOptions` bundle
using `setPendingIntentCreatorBackgroundActivityStartMode(...)`.

Apply this only to widget Activity launches. Broadcast-only widget actions
that update app state in-place should remain normal broadcast PendingIntents.

### Do not add new AppWidget-adjacent resource ids for animation bookkeeping
An attempted fix for duplicate-looking home-card update animation added
`res/values/ids.xml` and keyed view tags for `ThingsAdapter` appearing
animation bookkeeping. That build immediately caused existing AppWidget
RemoteViews to display incorrect/stale-looking checklist and Things-list data
after install. The change was rolled back.

For AppWidget regressions, avoid fixes that add new resource ids or perturb the
resource table unless the AppWidget update/install lifecycle is explicitly
smoke-tested on device. Keep future animation fixes inside existing code paths
or existing resources.

## 2026-05-21

### Post-migration Kotlin cleanup: scope + risk boundaries (grilling session)
After the 17-group Java→Kotlin migration completed, a cleanup phase
targets the IDE inspection noise (warnings + suggestions) AS shows on
`.kt` files, while keeping behaviour identical. Decisions, captured via
a grilling session and written up in
[KOTLIN_CLEANUP_PLAN.md](../docs/plans/KOTLIN_CLEANUP_PLAN.md):

- **Target bar**: fix yellow warnings + *safe* grey suggestions. Skip
  the migration plan's C-class / D-class items that risk behaviour
  (`data class`, scope functions, behaviour-changing property syntax).
- **Nullability boundary**: remove AS-flagged redundant `!!`, and narrow
  *purely-local* `var x: T? = ...` (used in one function, provably never
  null) to `T`. **Do NOT** touch declared nullability of fields /
  params / cross-function values — the N1 audit trail and the crash
  surface (see [followups.md](followups.md) ShiningBorder incident)
  stay intact.
- **Work-list source**: ground-truth from AS's own engine —
  `E:\software\Android Studio\bin\inspect.bat` against
  `.idea/inspectionProfiles/Project_Default.xml`, plus `gradlew lint`.
  NOT `assembleDebug` (migration drove compiler warnings to 0 via
  suppressions; IDE inspections are a different, larger set).
- **Verification**: each commit must `:app:assembleDebug` with 0 new
  warnings; one consolidated install + smoke-test + logcat (V3/V4) at
  the end, not per-commit (screenshot frugality).
- **Commit granularity**: one commit per **module** (mirror the 17
  migration groups → ~17 commits), each applying all in-scope fixes to
  that module's files. (Revised from per-category mid-session: too many
  commits. Trade-off accepted: harder to bisect a regression to a
  specific transform type, mitigated by behaviour-neutrality + final
  smoke test.) Plus **one isolated commit** for
  `RedundantNullableReturnType` (return-type narrowing — crosses the N1
  boundary and ripples to callers; done last).
- **RedundantNullableReturnType (77) opted IN** (revises the original
  "don't touch return types" boundary): AS proves the function never
  returns null, so narrowing `T?`→`T` is runtime-safe and is the only
  real lever to reduce nullability noise (AS flags **zero** redundant
  `!!` — the migration's `!!` are all genuinely needed given `T?`
  declarations).
- **Tier 6 — investigate & report, do NOT fix**:
  `KotlinConstantConditions` (e.g. "cast always fails",
  `Habit.kt` "total always zero"), `EmptyRange` (downTo? bugs),
  `KotlinUnreachableCode`, `UnusedSymbol` (many false positives —
  `App.kt` is manifest-instantiated). "Fixing" these would change
  behaviour; surface as findings, preserve behaviour per goal-A.
- **Out of scope (kept untouched)**: existing `@file:Suppress(...)`
  (deprecation-API swaps are behaviour changes); Java-interop ceremony
  (`@JvmStatic` / `@JvmField` / explicit `getX()`/`setX()`) — not AS
  warnings, higher risk (Parcelable CREATOR, reflection, widgets);
  header date stamps. Logged as follow-ups, not this pass.

### Cleanup execution: hybrid (IDE batch + agent judgment)
After Group 1 + Habit.kt by-hand (cost: ~1 edit per fix, ~1100 fixes
total = too slow + transcription risk on structural transforms),
switched to a hybrid: the **user batch-applies** the safe high-volume
Tier-1 idiom inspections via AS Inspection Results → "Apply fix to all"
(the IDE's own refactoring engine — behaviour-exact, zero transcription
risk). The **agent** does the judgment-required items (Tier-2:
ObjectLiteralToLambda hot-path/guard, ReplaceJavaStaticMethodWithKotlinAnalog,
CanBePrimaryConstructorProperty, KotlinRedundantOverride), Tier-3
(RedundantNullableReturnType isolated commit), Tier-6 investigation, and
**all compile-verify + per-module commits** (stage package-by-package
from the en-masse batch result to keep ~17 module commits reviewable).

## 2026-05-18

### Recurrence picker NORMAL cells: Material FAB → fake-FAB
`RecurrencePickerAdapter`'s `NormalViewHolder` swapped Material FAB
for a fake-FAB (FrameLayout + bg View + RippleDrawable foreground,
mirroring `color_picker_fab.xml`). This lets picked cells carry a real
OVAL `GradientDrawable` instead of being flattened to representative
via `setBackgroundTintList`. The ripple waveform itself remains
single-int representative (Android `RippleDrawable` `ColorStateList`
limit) — assessed acceptable; "real-gradient ripple via custom touch
animation" is a follow-up, not Phase 8 scope.

### Gradient signal propagation into all DateTime/Habit/AudioRecord dialogs
Phase 8 extended into `InputLayout`, `TimeOfDayRecAdapter`,
`RecurrencePickerAdapter` — each grew a `setAccentBackground(ThingBackground)`
entry, with text colours migrated to `BackgroundUtil.applyTextBackground`
and other paths kept on representative int.

### Plan §4.7.4 "FAB tint must be single int" is overruled where fake-FAB is feasible
The COLOR_MIGRATION_PLAN.md classification of FAB as "single-int only"
was too conservative — the fake-FAB pattern bypasses the API
restriction by replacing the widget. Plan §4.7.4 still applies to
genuine Android-API single-int seams (Notification.setColor,
PorterDuff tints, EdgeEffect.setColor, RippleDrawable ColorStateList).

## 2026-05-20

### Kotlin migration: branch `kotlin`, frozen master, behavior-snapshot semantics
After a grilling session, all strategy + rule decisions for the
Java→Kotlin migration of `app/` are captured in
`docs/plans/KOTLIN_MIGRATION_PLAN.md`. Highlights:

- Goal **A** — behavior snapshot, not modernisation. Later refactor
  phase removes `!!`, `@JvmStatic`, etc.
- **Long-lived `kotlin` branch** off master. **Master frozen** for
  the migration's duration.
- **17 groups**, bottom-up dependency order, one commit per group.
- Translation rules: **N1** (every Java reference → `T?`, every
  deref → `!!`, audit trail preserved), **E1** (`===` for reference
  equality, explicit numeric widening), **S-1..S-4** (`@JvmStatic`,
  `const val`/`@JvmField`, `object` for pure-static singletons,
  `companion object { init }` for `static {}`), **A-class
  modernisations** adopted (Elvis, `is`/`as`, `for in`, void
  omission), **B-class** SAM lambdas with guard rule (10 sites
  identified that must keep `object : Listener` form), **C-class
  deferred**, **D-class rejected**.
- **V1+V2+V3** required on every group; **V4** required on groups 1,
  4, 5, 14, 15, 16, 17.
- **V3 closed-loop**: Claude installs APK on emulator-5554, takes
  PNG screenshots, diffs against `memory/screenshots/baseline/`.
  Approach validated on 2026-05-20 — baseline home screenshot
  captured successfully via the PowerShell-safe `screencap` →
  `pull` workflow (see [preferences.md](preferences.md)).

**Don't reintroduce** anything from the C/D-class modernisation
list during the migration phase — those are explicitly deferred or
rejected. They are revisit candidates for the post-migration
refactor only.

### Group 0 surprise: AGP 9.2.1 ships a built-in Kotlin compiler
Trying to apply `org.jetbrains.kotlin.android` (tested 2.1.21 and
2.2.0, both classpath and `plugins {}` forms) fails on this
codebase with `Cannot add extension with name 'kotlin', as there is
an extension already registered with that name`. AGP 9 owns the
`kotlin` DSL extension and ships its own compiler
(`built_in_kotlinc`) that picks up `.kt` files dropped into the
java source set with **zero build.gradle changes**.

Verified by `:app:compileDebugKotlin` task running successfully on
just the `Dummy.kt` file, with `Dummy.class` ending up in the dex
output. Group 0 commit therefore contains a single new file — no
`build.gradle` edits — and the APK installs and cold-starts
identically to baseline `01_home_underway.png`.

**Don't try** `apply plugin: 'kotlin-android'` or
`id 'org.jetbrains.kotlin.android'` while AGP 9.x is in use — it
will fail. kapt and any plugin that requires the standalone Kotlin
Gradle Plugin are also unavailable. See
[KOTLIN_MIGRATION_PLAN.md §7.5](../docs/plans/KOTLIN_MIGRATION_PLAN.md)
for the kapt-replacement decision tree (KSP / defer / downgrade).

### Kotlin migration Group 3 (utils/) — deprecation suppression as file-level

14 utility classes translated cleanly; the only mechanical
challenge was the wave of Java-API deprecation warnings the
Kotlin compiler emits where the original Java already used the
deprecated API (Display.getDefaultDisplay/getRealSize/getSize,
Drawable.setColorFilter(Int, Mode), Notification.PRIORITY_*,
Locale(String, String), Resources.updateConfiguration,
InputMethodManager.SHOW_FORCED, etc.). Group 2's model/ classes
had none of these because Cursor / Parcel are still un-deprecated.

Decision: `@file:Suppress("DEPRECATION")` at the top of any
`.kt` file whose Java original called a since-deprecated API.
This preserves V1's "0 warnings" bar without changing which
API is called (behaviour snapshot), and the post-migration
refactor pass can revisit those call sites with intent.

Documented as new rule in [KOTLIN_MIGRATION_PLAN.md §3.11](../docs/plans/KOTLIN_MIGRATION_PLAN.md).

### Kotlin migration: header date stamp convention (added mid-migration)

Each `.java` → `.kt` translation now stamps
`Translated to Kotlin on YYYY-MM-DD.` immediately after the
existing `Created by … on YYYY/M/D.` Javadoc line. Files without
a `Created by` comment skip the stamp (e.g. ThingBackground was
born post-convention). Group 1+2 backfilled. Plan §3.10.5
captures the rule.

## 2026-05-19

### PopupPicker keeps IME visible — `INPUT_METHOD_NOT_NEEDED`
Pre-edge-to-edge, opening a `ColorPicker` / `DateTimePicker` while an
EditText was focused left the IME up and the popup floated above it.
On the edge-to-edge build that legacy behaviour broke: `setFocusable(true)`
made the popup steal window focus, IME was forced to hide, and the
`applyBottomInsetAsPadding(mFlRoot)` chain re-fired mid-show — the
bottom bar dropped, the popup's anchor shifted, and the popup got
auto-dismissed while the bottom bar stayed stuck at the pre-hide
`ime.bottom` value (no inset re-dispatch reached the activity because
the popup's own window owned the IME focus on its way down).

Restored the legacy UX by setting
`mPopupWindow.setInputMethodMode(PopupWindow.INPUT_METHOD_NOT_NEEDED)`
in the `PopupPicker` constructor. That flag tells the framework
"popup doesn't participate in IME, so don't change IME visibility
because of it" — IME stays open, no inset chain perturbation, no
flicker, no auto-dismiss. `setFocusable(true)` remains so BACK still
dismisses the popup.

**Don't reintroduce** `hideKeyboardBeforeShow()` / pre-emptive IME hide
in pickers — coexistence is the desired behaviour, not a workaround.

### DetailActivity bottom-bar padding is owned solely by the inset chain
`DetailActivity#setEvents` had a legacy `KeyboardUtil.addKeyboardCallback`
that did `mFlRoot.setPadding(0,0,0,keyboardHeight)` on IME show and
`mFlRoot.setPadding(0,0,0,0)` on IME hide. With edge-to-edge +
`applyBottomInsetAsPadding(mFlRoot)`, those calls now collide with the
chain: any IME-up → DialogFragment-show transition triggers
`onKeyboardHide`, which writes `padding=0` on top of the chain's
`bars.bottom` value — and the bottom bar ends up under the gesture /
3-button nav bar.

Stripped both `setPadding` calls. The callback now only does its
scroll-to-cursor work on `onKeyboardShow`; padding is entirely
chain-managed.

**Don't reintroduce** any direct `mFlRoot.setPadding(...)` from
keyboard callbacks — IME ↔ navbar geometry must stay in
`applyBottomInsetAsPadding`'s hands so the two cases compose.

### `chainDecorInsetsCallback` skips apply listener during IME animation
The decor-view dispatch order for an animated IME show is:
1. `onPrepare(animation)`
2. `onApplyWindowInsetsListener` fires with the **target** insets
   (IME at full height) — _before_ the animation starts
3. `onStart`
4. `onProgress` every frame with interpolated insets
5. `onEnd`

Applying the chain in step 2 snapped padding to the final IME-up
value; then `onProgress` re-applied the interpolated value starting
near the IME-down state → visible "flash to final, jump back,
animate up" flicker every time the keyboard opened.

Fix: shared `imeAnimating` flag set in `onPrepare` and cleared in
`onEnd` (gated on `WindowInsetsCompat.Type.ime()`). While true, the
`setOnApplyWindowInsetsListener` skips the chain, leaving the
animation purely driven by `onProgress`. Non-IME insets (rotation,
multi-window, gesture-nav entering / leaving) still flow through
the apply listener as before.

**Don't bypass** the flag in the apply listener for "always reset
on a stable dispatch" — that brings the flicker back.

### `chainDecorInsetsCallback.onEnd` does a stable re-read for IME animations
Once `imeAnimating[0]` is cleared in `onEnd`, the callback explicitly
re-fires the chain with `ViewCompat.getRootWindowInsets(decor)`.

Why: in multi-window mode, the platform temporarily folds the navbar
inset into the IME envelope while the focused half resizes — so
`onProgress`'s last frame reports `bars.bottom = 0`. After the
animation settles, the post-animation stable insets carry the
correct `bars.bottom` (multi-window nav handle), but the platform
does not reliably re-dispatch them; and even if it did, the apply
listener still skips them while `imeAnimating[0]` is true. The
result without this fix: bottom bar loses its multi-window nav
handle accommodation forever after the first IME open / close.

Implementation note: uses `getRootWindowInsets` (local read) rather
than `decor.requestApplyInsets()` so the recovery doesn't trigger a
layout pass that would collide with concurrent DialogFragment show
timing (the original reason `requestApplyInsets` was removed from
`onEnd`).

### PopupPicker positioning is always anchor-driven, window-relative
`PopupPicker.mAnchor` is a `View` (was `Object`). Subclasses compute
popup x/y from `mAnchor.getLocationInWindow()` and `mParent.getWidth()`
/ `mParent.getHeight()` — never from `getLocationOnScreen()` +
`getDisplaySize()`. Reason: `showAtLocation`'s gravity reference is
the popup's parent window (= the activity window). In multi-window,
that window is one half of the display, but `getLocationOnScreen`
and `getDisplaySize` return display-global coordinates — mixing the
two computes an xOffset that throws the popup into the other split's
region (search popup drifts left in left-half multi-window; Detail
ColorPicker drifts right in right-half).

ColorPicker's tint behaviour (recolour an icon Drawable on each pick)
moved off `mAnchor` and onto a separate `setTintTarget(Drawable)`
slot. `mAnchor` is strictly the View we position against.

**Y placement rule for bottom-bar pickers** (DateTimePicker AFTER_TIME):
popup bottom lands at `anchor vertical centre`, i.e.
`Y_offset = mParent.getHeight() - anchorTopInWindow - anchor.height / 2`.
That matches the legacy non-edge-to-edge visual (where the old formula
`displayHeight - pos[1] - anchor.height` accidentally landed there
because `displayHeight - mParent.getHeight() ≈ navbar`); encoding it
explicitly makes the position hold across edge-to-edge, multi-window,
and gesture-nav layouts where that accidental relationship no longer
holds.

**Don't** reintroduce `displayHeight - …` based offsets — they only
match the legacy visual by coincidence and break the moment the
window starts spanning the navbar / cutout.

### PopupWindow Y offset must compensate for navbar inset
`PopupWindow` is constructed without `FLAG_LAYOUT_IN_SCREEN`, so
`WindowManager` insets the popup's own window by the bottom system
bars. This means `Gravity.BOTTOM`'s reference "popup-window-bottom"
sits at `mParent.height - navBottom` (in screen coords), **not** at
`mParent.height`.

For `Gravity.BOTTOM` placements that want a specific anchor
relationship (e.g. popup bottom = anchor centre), the Y offset is:

```
Y = mParent.getHeight() - navBottom - anchorTopInWindow - <anchor adjustment>
```

where `navBottom = ViewCompat.getRootWindowInsets(mParent).getInsets(
systemBars() | displayCutout()).bottom`.

Legacy non-edge-to-edge windows had `mParent.getHeight() ==
display.height - navbar` already, so the navbar fell out of the math
by accident and the old `displayHeight - pos[1] - anchor.height`
formula landed in the right place. Edge-to-edge `mParent.getHeight()
== display.height` exposes the gap — we have to compensate
explicitly.

This applies only to bottom-gravity popups. Top-gravity popups
(`Gravity.TOP`, e.g. ColorPicker) reference window top, which is the
same regardless of bottom-bar insets, so no compensation needed.

### Anchor View lookups must happen at show time, not in `onCreateOptionsMenu`
Looking up a toolbar action menu item via `findViewById(id)` inside
`onCreateOptionsMenu` returned a View whose location wasn't yet
final under multi-window — the menu inflate completed but the
ActionMenuItemView hadn't been measured / laid out yet. Caching that
reference and reading `getLocationInWindow` later still returned
stale (0,0) coordinates because the View was never properly attached
in that path.

Fix: look up `findViewById(R.id.act_…)` inside
`onOptionsItemSelected`, right before `mColorPicker.show()`. The
menu item is fully attached by then (we're literally handling a tap
on it). DetailActivity already worked correctly because it followed
this pattern from day one.

## 2026-05-26 — Noticeable notification dark-mode boundary

`NoticeableNotificationActivity` is a hybrid chrome surface for dark-mode planning. Its dialog-like shell, background, title, action icons, cancel control, ripple/chrome affordances, and similar wrapper UI should adapt to `Appearance Mode`. The embedded thing/card content still follows the thing's own `Thing Background` priority.

## 2026-05-26 — Dark-mode defaults

Dark mode will ship with conservative defaults for existing users:
`followSystemDarkMode = false` and `forceDarkMode = false`. Users must
explicitly enable either following the system or forced dark mode in
settings.

## 2026-05-26 — Light-mode visual compatibility for dark-mode work

The dark-mode implementation may switch themes to DayNight, but light
mode must remain visually identical to the current UI. New semantic
resources in `values/` must resolve to the same colours/drawables used
today; dark-mode differences belong in `values-night/` or explicit dark
branches. Verification should include light-mode regression checks, not
only dark-mode checks.

## 2026-05-26 — Thing-background foreground ignores app dark mode

Whenever a thing's own background is the base surface, text and icons
drawn on top of it keep using the existing lightness-based adaptive
foreground logic. Do not add `Appearance Mode` as an extra input for
those foreground colours. This applies consistently across home cards,
detail/doing surfaces, noticeable-notification embedded cards, widget
previews, and any other thing-background surface.

## 2026-05-26 — Dark-mode lifecycle handling for state-sensitive chrome

`SettingsActivity` handles `uiMode` changes by first storing the current
settings UI state, then recreating. This keeps Appearance Mode changes and
other pending settings from being dropped when follow-system dark mode
changes while Settings is open.

`NoticeableNotificationActivity` handles `uiMode` in place instead of
blindly recreating, because `onDestroy()` cancels the related system
notification. Its dialog shell colours/icons are repainted manually, while
the embedded thing card remains Thing-background-owned.

Yellow app-accent toolbars keep black controls in both light and dark mode.
Do not route those toolbar navigation/action icons through the generic
dark App Chrome foreground colour, because white controls on yellow lose
contrast and would alter the established light-toolbar look.

## 2026-05-26 — Dark-mode icon tint boundaries

Home toolbar chrome uses explicit dark-mode-only runtime tinting. In light
mode it should preserve the original drawable appearance and avoid global
NavigationView / adapter tint lists.

In dark mode, the home drawer toggle icon and home toolbar action icons use
the app accent yellow. Drawer menu item icons do not use yellow; they keep
their original NavigationView drawable appearance. Statistic row icons are
tinted only in dark mode; in light mode their original drawable colours are
left untouched.

Settings screen icons are App Chrome foreground. TextView compound icons
follow their TextView's current text colour in dark mode, while ImageView
help/info icons use the dark App Chrome control colour.

PNG toolbar and settings icons often carry baked-in 54% alpha. When tinting
those icons to an explicit App Chrome colour, normalise the source alpha mask
to the target colour's alpha and return a new mutated drawable. Plain
`setTint`/`setColorFilter` preserves the baked-in alpha and can make dark
toolbar icons look dimmer than the app accent.

DetailActivity remains a Thing-background-owned screen, but dialogs opened
from it are App Chrome surfaces. Its Activity theme should be DayNight, and
BaseDialogFragment should create a DayNight dialog context/window background
so those dialogs resolve dark App Chrome resources without changing the
Detail body foreground rules.

## 2026-05-26 - Dark-mode dialog context and Drawer menu icons correction

`BaseDialogFragment` dialogs must be created from an Activity-backed
context. Do not use `Activity.createConfigurationContext(...)` as the base
for `Dialog(...)`: it can lose the Activity window token and crash with
`WindowManager$BadTokenException` when a restored or newly opened
DialogFragment starts. Use an Activity-backed `ContextThemeWrapper` for the
dialog theme, then set the App Chrome elevated window background explicitly.

Home dark-mode icon boundaries were corrected again: the drawer toggle icon
and home toolbar action icons use the app accent yellow, but Drawer menu
item icons use a non-yellow App Chrome control tint in dark mode. Generate
new per-item drawables with `DisplayUtil.opaqueTintDrawable(...)` instead of
using a global `NavigationView.itemIconTintList`, so PNG assets with baked-in
alpha are not left looking like their light-mode originals.

## 2026-05-26 - Base DialogFragment width and Detail audio attachments

`BaseDialogFragment` owns the dialog-window width policy. DayNight dialog
themes can apply a platform/AppCompat minimum width that visually widens
fixed-width content such as `ThingDoingDialogFragment` and
`DateTimeDialogFragment`. After `Dialog.show()`, reset the fragment dialog
window to `WRAP_CONTENT` width/height so each layout's explicit content width
continues to be authoritative.

Detail audio attachment rows are App Chrome cards placed inside the
Thing-background-owned Detail screen. The card surface, text, and action icons
should use App Chrome semantic colours in dark mode. They do not use the
Thing-background adaptive foreground rule, because the row itself has its own
elevated App Chrome card surface.

## 2026-05-26 - AddAttachment icon and snackbar dark-mode boundaries

`AddAttachmentDialogFragment` action icons are PNG assets whose light-mode
appearance is the source asset itself. Do not add XML `drawableTint` to those
four action TextViews; it makes light mode visibly lighter than the pre-dark
mode baseline. Dark mode may tint those compound drawables at runtime only.

The custom Snackbar keeps its original dark background and white text in both
light and dark mode. It is not an App Chrome surface that should invert or
lighten under dark mode.

Dialog content width remains owned by each layout's explicit width. The
pre-android-16 baseline used `fragment_thing_doing.xml` root width `280dp` and
`fragment_date_time.xml` content width `280dp + 20dp + 20dp`; the DayNight
dialog theme must therefore override `android:windowMinWidthMajor/Minor` to
`0dp` so AppCompat/platform dialog minimum width does not widen those dialogs.

## 2026-05-26 - Fixed-width dialog window sizing

For historical fixed-width `BaseDialogFragment` subclasses, do not rely on
`Window#setLayout(WRAP_CONTENT, WRAP_CONTENT)` to restore baseline width. Android
`DecorView` applies dialog minimum width during `AT_MOST` measurement, so
`WRAP_CONTENT` can still expand fixed content under DayNight/AppCompat dialog
themes. `ThingDoingDialogFragment` and `DateTimeDialogFragment` now override a
BaseDialogFragment width hook and set exact window widths matching the
pre-android-16 layouts: `280dp` and `320dp`. Exact window width bypasses the
DecorView min-width remeasure while keeping other dialogs content-driven.

DateTimeDialog's "new reminder time" row should use the same
`app_chrome_on_surface_secondary` foreground as the existing reminder-time icons
and edit text when unfocused. Do not use `app_chrome_control_unchecked` for that
row, because in light mode it is darker than the reminder icons and in dark mode
it can desynchronise text and icon tint.

## 2026-05-26 - Search all-colours icon and DateTime recurrence foreground levels

In ThingsActivity search mode, the ColorPicker "all colours" sentinel
(`0x8A000000`) is a data/search neutral value, not always a visual toolbar tint.
When the hue-bucket picker is attached to the search action icon in dark mode,
the all-colours state should render as the same full `app_accent` yellow used by
the home FAB and toolbar actions. Do not apply the semi-transparent sentinel as a
PorterDuff filter over an already-yellow icon, because that makes the icon look
dim.

DateTimeDialog recurrence rows use two explicit foreground levels modelled on
checklist rows: existing reminder-time icons use the stronger existing-item
level (`#C4...`), while the "new reminder time" text and icon use the weaker
new-item level (`#80...`). Keep these as dedicated DateTime resources so they
do not perturb broader App Chrome semantic colours.

Follow-up correction: `time_of_day_rec_tv.xml` must not apply
`android:drawableTint` on top of the runtime `opaqueTintDrawable(...)` for
`act_new_time_rec`. Double tinting multiplies alpha and makes the icon visually
lighter than the text. The new-reminder row now uses one explicit code tint for
the icon and the same resource for text; the resource is `#40...`, matching the
previous visually accepted icon strength without tint stacking.

ColorPicker's all-colours checkbox is a PNG compound drawable, so it needs an
explicit dark-mode tint when bound. The all-colours toolbar icon and the
all-colours picker checkbox are separate surfaces: toolbar icon uses
`app_accent` in dark search mode, picker checkbox follows App Chrome secondary
foreground.

## 2026-05-26 - Search no-result overlay ownership

The ThingsActivity no-result overlay belongs strictly to search mode. Any path
that leaves search mode, resumes ThingsActivity while `App.isSearching == false`,
or calls `handleSearchResults()` outside search must force-hide the overlay and
cancel its fade animation. The overlay is not a general empty-list surface; it
must never remain visible over the normal thing list.

The no-result PNG is a static raster asset and does not adapt through XML theme
colours. In dark mode it should be installed programmatically as an
`opaqueTintDrawable(...)` using App Chrome hint foreground; light mode keeps the
raw asset for visual compatibility.

## 2026-05-26 - DetailActivity follow-system uiMode overlay policy

`DetailActivity` keeps handling `uiMode` in place instead of removing
`uiMode` from `android:configChanges` or forcing full Activity recreation.
The Detail screen has unsaved title/content/attachment/checklist state and
several DialogFragments rely on setter-injected state, so blind recreation is
too risky for data flow.

When follow-system dark mode changes while Detail is open, Detail now treats
App Chrome overlays as stale: dismiss toolbar overflow menus, dismiss active
DialogFragments opened from Detail, dismiss the old `ColorPicker` /
`quickRemindPicker` PopupWindows, then recreate those picker instances against
the updated DayNight resources and reattach their listeners. Reopened popups
and dialogs should therefore resolve the current App Chrome theme, while the
Thing-background-owned Detail body keeps the existing foreground rules.

Version-qualified `EverythingDoneTheme.Detail` definitions must carry the same
App Chrome text/control/floating-background items as the base style. Android
devices that match `values-v19` or `values-v21` do not automatically inherit
items added only to `values/styles.xml`.

## 2026-05-26 - Detail quick-remind and checklist measurement corrections

Quick-remind picker recreation must preserve a valid picked index even when
the old PopupWindow was never opened after the default "15 minutes later"
selection was installed. Detail now infers the picked index from `rhParams`
when the old picker reports `-1`, and `DateTimePicker` gives AFTER_TIME
pickers the same default selected row internally.

Detail checklist RecyclerView stays non-scrollable; the outer
`NestedScrollView` owns the scrolling. Do not measure checklist height by
creating/binding RecyclerView item views inside `LayoutManager.onMeasure()`;
that steals focus during editing and can race item removal.

Also do not estimate offscreen checklist row heights. The legacy Java code only
used laid-out holder heights plus fixed fallbacks for separator/count rows, and
that relied on unstable RecyclerView layout state. Collapsed finished checklist
items should instead be represented by adapter visibility: while collapsed, the
adapter exposes only unfinished rows plus the add/separator/finished-count rows;
while expanded, it exposes the complete item list. The RecyclerView height stays
`WRAP_CONTENT` and relies on RecyclerView AutoMeasure.

## 2026-05-26 - ThingsActivity restored-list animation boundary

When ThingsActivity is restored from saved state after a background
configuration change, do not replay the normal first-bind "things appearing"
animations. The restored RecyclerView is trying to put the user back at the
same scroll position, not present a fresh list.

Do not suppress the normal Detail-return item update animation. Same-type
Detail returns should continue to use ordinary `notifyItemChanged(position)`;
only the restored list's first-bind appearing animation is disabled. The
payload/no-change-animation approach made the restored list jump into place
without the expected item update affordance and was reverted.

## 2026-05-26 - App Chrome ripple resources must be real API 21 ripples

The project's shared `selectable_item_background` and
`selectable_item_background_light` resources are the interaction surface for
Settings rows, Help rows, App Chrome dialogs, chooser rows, popup picker rows,
and many dialog action buttons. On API 21+ these resources should be direct
`RippleDrawable` XMLs with transparent content plus an explicit full-view mask,
not `selector -> ripple` wrappers. This keeps the pressed feedback as a real
bounded ripple in dark mode instead of letting the state-list wrapper degrade
into a simple block highlight.

Dialog-local Material FABs should also opt into the same App Chrome ripple
semantic colour when they live on an App Chrome dialog surface.

Important qualifier correction: `drawable-v21` applies to API 21 and higher,
but `drawable-night` can still win on a dark-mode device because the `night`
qualifier is a better configuration match than an unqualified `v21` drawable.
For dark-mode API 21+ ripple resources, provide `drawable-night-v21` explicitly
or the app can keep packaging the old night selector.

Detail audio attachment rows are an additional runtime-repaint case: they are
App Chrome cards inside DetailActivity's Thing-background-owned body, and
DetailActivity may handle `uiMode` in place. Their icon/card ripple drawables
must therefore be reinstalled during adapter binding from `AppearanceUtil`, not
left solely to the XML-inflated background.

## 2026-05-26 - Settings Appearance Mode row visibility

Settings should present the Appearance Mode controls as "Follow system dark
mode" and "Enable dark mode". When follow-system is checked, the enable-dark row
is hidden rather than disabled/dimmed. When follow-system is unchecked, the
enable-dark row is visible again and keeps its previous checked state.

## 2026-05-27 - Background full-list refresh should not replay Things appearing animation

`ThingsActivity.justNotifyAll()` remains the conservative full-list reload path
for stale or coalesced remote updates, but the `onResume()` path that consumes a
background `App.justNotifyAll()` should call it without enabling the
`things_show` first-bind animation. Returning from a launcher widget update is a
data catch-up, not a fresh list presentation, and replaying the bottom-up card
appearance reads as a second update animation.

## 2026-05-27 - Widget create actions should resolve the new-thing colour at click time

Launcher widget PendingIntents must not keep using the same precomputed
`App.newThingBackground` forever. The new-thing background changes when
`DetailActivity` opens in CREATE mode, while widget RemoteViews may keep the
same PendingIntent for a long time.

Correction after device testing: the standalone Create widget should mirror the
Things List widget create action, not open `DetailActivity` directly. The direct
`DetailActivity` plus standalone-widget refresh attempt still allowed repeated
colour/task staleness after abandoning an empty created thing and pressing Home.

Both create-widget entry points should go through `ShortcutActivity` with
`SHORTCUT_ACTION_CREATE`. The list widget carries its selected limit; the
standalone Create widget carries `KEY_LIMIT = ALL_UNDERWAY`. This keeps the
background resolved at click time and follows the entry path the user verified
as repeatedly opening the create page correctly.

## 2026-05-27 - Widget card icons must be luminance-adaptive like card text

RemoteViews do not inherit the normal `BaseThingsAdapter` icon tint pipeline.
Every widget card icon that sits directly on a Thing background should therefore
be set explicitly from the Thing representative colour: black-side assets or a
black color filter on light backgrounds, white-side assets or a white color
filter on dark backgrounds. This covers checklist state, private lock,
sticky/ongoing, reminder/goal, habit, habit record, audio attachment, and
finished/deleted state icons.

## 2026-05-27 - App language selection uses AppCompat locales plus context wrapping

The old in-app language path mutated only `App.getApp().resources` through
`Resources.updateConfiguration(...)`. That is not a reliable Activity
localisation boundary after the Android 16 / AppCompat update, especially when
the selected app language differs from the system language.

Use a two-layer locale path instead:
- wrap `Application` and Activity base contexts from the stored app-language
  preference so resources are correct before layout inflation;
- keep `AppCompatDelegate.setApplicationLocales(...)` in sync so AppCompat and
  Android's per-app language machinery see the same locale.

Settings language preselection must compare saved language codes, not displayed
language names, because displayed names are locale-dependent and can belong to
the previous resource configuration.

## 2026-05-28 - App update flow starts as a debug test update channel

The About-screen "check for updates" feature should first be designed as a
debug test update channel, not as a formal public release channel. Each
published debug build can expose the latest APK and metadata from the user's
Aliyun server, and the app can use that channel for manual tester-initiated
updates.

The Aliyun side should start as a static update source instead of a long-running
API service. The server can host a version metadata JSON file and APK assets,
with repository-side docs or scripts under `server/` for upload and hosting
conventions.

Publishing should be an explicit Gradle task rather than a side effect of every
`:app:assembleDebug` run. Normal debug assembly should stay local; the publish
task should assemble the debug APK, generate update metadata, and upload the
APK plus metadata to the static Aliyun update source.

The explicit Gradle task should live under the app module as
`:app:publishDebugUpdate`, matching the APK it publishes and avoiding an
over-broad root-project task name.

The debug update channel should not use the Android manifest `versionCode` as
the publishing cadence, because the user wants to avoid inflating the normal app
version code for frequent debug builds. Use a separate debug update identifier
for update-channel comparison instead.

The debug update identifier should be a UTC timestamp number in `yyyyMMddHHmm`
form. This keeps the channel naturally increasing without maintaining a counter
file and without touching Android `versionCode`.

The installed app should read its current debug update identifier from the APK
itself, not from a value written before launching the package installer. This
prevents cancelled installs, failed installs, or manual ADB installs from
desynchronising the app's idea of the currently running debug build.

Ordinary `:app:assembleDebug` builds should carry `debugUpdateCode = 0`. Only
`:app:publishDebugUpdate` should inject a real UTC timestamp identifier, so
local debug builds are not mistaken for published update-channel artifacts.

The app-side update flow should automate checking, downloading, SHA-256
verification, and launching the system package installer, but it must leave the
final install confirmation and unknown-source authorization to Android's system
UI.

The APK download UI should be an app-owned `DialogFragment` following the
existing `BaseDialogFragment` / App Chrome styling. It should show download
progress and current speed, and it must adapt to light and dark Appearance Mode.

The download dialog should expose an explicit cancel button while preventing
accidental dismissal via Back or outside taps. Cancelling should stop the
download and delete any partial APK file.

Downloaded APKs must pass SHA-256 verification against `latest.json` before
installation is launched. The app should also parse the APK package information
first and reject files whose package name is not `com.ywwynm.everythingdone` or
whose Android `versionCode` is lower than the currently installed app.
Signature mismatch can be left to Android's package installer to reject.

The initial Aliyun update source may use debug-only HTTP access by bare IP so
the feature can start before a domain or automated IP-address certificate setup
exists. Cleartext allowance should be scoped to the debug update channel and
removed once HTTPS hosting is available.

The static update source should expose versioned APK files under a debug update
directory, for example `debug/apk/app-debug-<debugUpdateCode>.apk`, with
`debug/latest.json` pointing at the current file. Avoid overwriting a single
fixed APK filename while a device may still be downloading it.

Publishing should retain only the most recent five debug APK files on the
server by default while keeping `latest.json` pointed at the newest build.

Aliyun publishing connection details should be read from untracked
`local.properties`, including host, user, remote directory, public base URL, and
optional SSH key path. Do not hardcode those values in committed Gradle or
server files.

The Gradle publish task should use the system `ssh` and `scp` commands for
uploading and remote cleanup rather than introducing a Gradle SSH plugin or
server-side deployment service.

The app's debug update metadata URL should be injected into debug builds from
untracked `local.properties` via `BuildConfig`, not hardcoded in source. Local
debug builds without that property can leave the URL empty and report that the
update source is not configured.

The About-screen "check for updates" entry should be visible only in debug
builds for now. Release builds should not expose the debug update channel,
especially while the initial source may use debug-only HTTP by bare IP.

APK downloads should be performed by app-owned HTTP streaming on a background
thread rather than Android `DownloadManager`, because the app needs direct
control over dialog progress, speed display, cancellation, temporary files, and
post-download verification.

Installation should launch Android's package installer with
`Intent.ACTION_INSTALL_PACKAGE` and a `FileProvider` `content://` URI. The app
should declare `REQUEST_INSTALL_PACKAGES`, check
`PackageManager.canRequestPackageInstalls()`, and route the user to
`Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES` when unknown-source authorization
is missing.

When unknown-source authorization is missing after a verified APK download, the
app should keep the verified APK as a pending install and route the user to
settings. When AboutActivity resumes and the permission is granted, it should
reuse that APK and relaunch the installer instead of forcing another download.

The About screen should place the existing open-source license action and the
debug-only check-update action in a horizontally centered row. Both should be
underlined text buttons using the existing About visual tone. When the update
action is hidden in release builds, the license action should remain centered.

Because the update feature adds only a small number of user-facing strings,
localize them across all existing app language resource directories with
agent-authored translations rather than limiting the change to English and
Chinese fallback.

Server-side files for the debug APK update channel should live under a dedicated
subdirectory inside `server/`, because the project may add unrelated server
features later. Keep this channel's static-hosting docs and helper scripts
scoped to that subdirectory rather than placing them directly at `server/`.

Use `server/update-debug-apk/` as the dedicated server-side directory for this
debug APK update channel.

App-side network update behavior should live in a new focused helper such as
`DebugApkUpdateHelper`, not in the existing `AppUpdateHelper`, whose current
role is old-version migration and post-upgrade informational dialogs.

The debug update metadata contract should use `channel`, `debugUpdateCode`,
`versionCode`, `versionName`, `apkUrl`, `sha256`, `sizeBytes`, `publishedAt`,
and optional `releaseNotes`. The channel must be `debug`, `debugUpdateCode`
uses UTC `yyyyMMddHHmm`, and `sha256` is lowercase hex.

Downloaded debug APK files should live under `cacheDir/debug-updates/`. Active
downloads should write to `.apk.part`, verified downloads should be renamed to
`.apk`, stale partial files should be removed before a new download, and the app
should keep only the most recent verified APK in that cache.

The check-update UI should explicitly handle unconfigured source, checking,
already-up-to-date, update-available, metadata/network failure, download
failure, verification failure, and installer-launch failure states with
localized user-facing feedback. Update-available feedback should include
version, size, published time, and optional release notes before the user starts
download and install.

The initial debug update downloader should not retry automatically. Failures
should be reported clearly, and the user can retry manually.

`:app:publishDebugUpdate` should fail the Gradle build when any required
configuration, assembly, metadata generation, checksum, upload, remote setup, or
cleanup step fails. A successful task must mean the static update source now
points at a usable latest debug APK.

Published debug update metadata should use UTC consistently: `publishedAt` is
UTC ISO-8601 with a `Z` suffix, and `debugUpdateCode` is derived from the same
UTC timestamp. The app can format the timestamp into the device locale and time
zone for display.

Debug update release notes are optional and may be long. The publish task should
support both `-PdebugUpdateNotes=...` and `-PdebugUpdateNotesFile=...`, with the
file input taking precedence when both are present.

The update-available confirmation UI should use a dedicated dialog fragment
with a bounded scrollable content area for long release notes. Its title and
bottom action area should remain visually stable while content scrolls, using
separator behavior consistent with the app language chooser and colour
information dialogs.

The download-progress dialog should stay fixed-height and non-scrollable. It
should show determinate progress, downloaded/total size, current speed, and a
cancel action, with App Chrome light/dark colours and ripple behavior.
