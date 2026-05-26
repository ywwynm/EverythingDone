# Dark Mode Plan

Status: **proposed** - 2026-05-26.

This plan adds dark-mode support to EverythingDone's App Chrome while
preserving the visual priority of each Thing's own background. It uses
the domain language in [CONTEXT.md](../../CONTEXT.md): Thing Background,
Thing Foreground, App Chrome, Hybrid Chrome Surface, and Appearance Mode.

## 1. Goals

- Add Appearance Mode support for App Chrome.
- Add two settings:
  - Follow system dark mode.
  - Enable dark mode.
- Give follow-system priority over force-dark. When follow-system is on,
  the enable-dark setting is hidden in Settings.
- Keep existing users in the current light UI by default:
  `followSystemDarkMode = false`, `forceDarkMode = false`.
- Preserve light-mode visuals exactly: colours, spacing, radius, shadows,
  text sizes, icon tints, ripple feedback, status bars, navigation bars,
  and other visible styling must not intentionally change in light mode.
- Make dialog, popup, picker, and snackbar foreground UI readable in dark
  mode, including text, icons, dividers, edit fields, progress indicators,
  disabled states, and pressed states.
- Handle system dark-mode changes without losing important user state or
  data, especially active Thing edits.

## 2. Non-goals

- Do not change Thing Background colours or gradients for dark mode.
- Do not add Appearance Mode as an input to Thing Foreground colour
  selection. Text and icons drawn directly on a Thing Background keep using
  the existing lightness-based black/white foreground logic.
- Do not change attachment image alpha or Thing-card background alpha based
  on whether the app is in light or dark mode.
- Do not update the actual desktop AppWidget RemoteViews in this first
  pass. Widget output keeps following Thing Background, widget style, and
  user-configured alpha.
- Do not use this work to redesign layouts, roundness, spacing, shadows,
  or animations.
- Do not depend on Android framework force-dark auto-inversion. The app
  should own its dark resources explicitly.

## 3. Scope

### 3.1 App Chrome surfaces

These surfaces should adapt to Appearance Mode:

- `ThingsActivity`, including the home background, drawer, search UI,
  contextual toolbar, empty/header chrome, FAB surroundings, snackbar
  integration, status bar, and navigation bar.
- `SettingsActivity`, including all settings rows, section labels, status
  text, checkboxes, buttons, and dialogs launched from Settings.
- `HelpActivity`, `AboutActivity`, and `StatisticActivity`.
- Ordinary dialogs, popups, pickers, snackbars, and chooser surfaces.
- `ThingsListWidgetConfiguration`.
- `BaseThingWidgetConfiguration` while selecting a Thing from the list.

### 3.2 Thing Background surfaces

These surfaces should not redraw their Thing-owned body just because
Appearance Mode changes:

- `DetailActivity`.
- `DoingActivity`.
- Thing cards and embedded Thing content in any host.
- Widget previews and Thing previews where the visual goal is to show the
  real Thing/widget appearance.

Their foreground text and icons continue to follow the existing Thing
Background lightness logic only.

### 3.3 Hybrid Chrome surfaces

`NoticeableNotificationActivity` is a Hybrid Chrome Surface:

- The simulated dialog shell should adapt to Appearance Mode: root/dialog
  background, title text, time text, title icon, cancel control, action
  icons, ripples, and any shell dividers.
- The embedded Thing card continues to use its Thing Background and current
  Thing Foreground logic.
- The previous decision that this embedded card does not need rounded
  corners still stands.

`BaseThingWidgetConfiguration` is also hybrid during preview:

- The selection list and toolbar are App Chrome.
- The wallpaper preview, widget preview, and Thing preview should keep
  showing the real output.
- The bottom preview controls must remain readable, but their translucent
  preview semantics should not be redesigned.

## 4. Current code facts

- Themes are currently light-only or no-actionbar themes. There is no
  existing `AppCompatDelegate` night-mode wiring.
- `appcompat` is available (`androidx.appcompat:appcompat:1.7.1`) and the
  app has `minSdk 26`, so `AppCompatDelegate.setDefaultNightMode(...)` is
  the preferred mode switch.
- `values/colors.xml` contains fixed light App Chrome colours such as
  `bg_activity_things = #EEEEEE`, black alpha colours, white alpha colours,
  `bg_snackbar`, and `selectable_item_background`.
- Many layouts and Kotlin files directly reference `black_54p`,
  `black_26p`, `black_14p`, `bg_activity_things`,
  `selectable_item_background`, and hard-coded colours.
- `DisplayUtil.darkStatusBar(...)` is used by light screens and assumes
  dark status-bar icons on light backgrounds.
- `SettingsActivity` stores most settings in `finish()` via
  `storeConfiguration()`, so a theme-triggered recreate could otherwise
  lose transient settings UI state.
- `DetailActivity` already has substantial edit/save state and an
  `onConfigurationChanged(...)` implementation. Theme changes must not
  interrupt update/create flows.
- `NoticeableNotificationActivity.onDestroy()` cancels the related system
  notification, so blind recreate on `uiMode` changes is risky.
- Widget configuration screens contain unconfirmed selection and alpha
  state, so blind recreate can reset user choices unless state is saved or
  the surface handles `uiMode` manually.

## 5. Implementation strategy

### 5.1 Appearance model

Add explicit settings keys in `Def.Meta`, for example:

- `KEY_FOLLOW_SYSTEM_DARK_MODE`
- `KEY_FORCE_DARK_MODE`

Add cached access in `FrequentSettings` or a dedicated
`AppearanceModeUtil` so early app startup can read the mode without
duplicating SharedPreferences logic.

Resolve the effective mode as:

```kotlin
if (followSystemDarkMode) MODE_NIGHT_FOLLOW_SYSTEM
else if (forceDarkMode) MODE_NIGHT_YES
else MODE_NIGHT_NO
```

Call `AppCompatDelegate.setDefaultNightMode(...)` during application
startup before Activity inflation. When the user changes either Appearance
Mode setting, persist it immediately, update the cached value, then apply
the new default night mode.

Settings UI behaviour:

- Show both toggles in the UI section of Settings.
- Follow-system enabled: enable-dark checkbox/row is hidden.
- Follow-system disabled: enable-dark checkbox/row is visible and enabled.
- The settings must be committed immediately instead of waiting for
  `SettingsActivity.finish()`, because applying night mode may recreate
  activities.

### 5.2 Theme and resource strategy

Use DayNight as the mode-dispatch mechanism, but explicitly preserve the
current light appearance.

- Switch app-chrome themes to `Theme.AppCompat.DayNight...` parents.
- Keep all current light colours and drawables in `values/` equivalent to
  today's UI.
- Put only dark-mode differences in `values-night/` or explicit dark-mode
  code paths.
- Add `values-v29` force-dark opt-out if needed:
  `android:forceDarkAllowed=false`, so Android does not auto-invert views.
- Avoid relying on implicit DayNight defaults for light mode. Define
  explicit `colorControlNormal`, `colorControlHighlight`,
  `android:textColorPrimary`, `android:textColorSecondary`, dialog
  background, and list selector behaviour where current screens depend on
  fixed colours.

Introduce semantic App Chrome resources. In `values/`, each semantic
resource should map to the existing light colour/drawable:

- `app_chrome_surface`
- `app_chrome_surface_elevated`
- `app_chrome_on_surface_primary`
- `app_chrome_on_surface_secondary`
- `app_chrome_on_surface_disabled`
- `app_chrome_divider`
- `app_chrome_control_normal`
- `app_chrome_control_highlight`
- `app_chrome_ripple`
- `app_chrome_snackbar_background`
- `app_chrome_snackbar_text`

Then add `values-night/` equivalents for dark mode.

### 5.3 Programmatic colour strategy

Add a small appearance helper, for example `AppearanceUtil`, for places
where colours are currently chosen in Kotlin:

- `isDarkMode(context)`
- `getChromeSurfaceColor(context)`
- `getPrimaryTextColor(context)`
- `getSecondaryTextColor(context)`
- `getDisabledTextColor(context)`
- `getDividerColor(context)`
- `getIconColor(context)`
- `tintChromeIcon(context, drawable)`
- `applyChromeSystemBars(activity, surfaceColor)`

Use this helper only for App Chrome. Do not route Thing Foreground through
it. Thing Foreground should continue to use `BackgroundUtil.isLight(...)`
or the existing Thing Background-aware path.

### 5.4 System bars

Replace light-only calls such as `DisplayUtil.darkStatusBar(...)` on App
Chrome surfaces with Appearance-aware helpers:

- Light App Chrome: keep current status/nav visual behaviour.
- Dark App Chrome: use dark system-bar surfaces and light system-bar icons.
- Transparent and Thing Background surfaces should keep their existing
  special handling unless the surface has App Chrome shell that needs
  explicit adaptation.

## 6. Lifecycle and state policy

Do not assume `recreate()` is always safe. Classify each affected Activity
before implementation.

### 6.1 Direct recreate candidates

Direct AppCompat recreate is acceptable only after verifying there is no
important transient state or destructive lifecycle side effect:

- `HelpActivity`
- `AboutActivity`
- likely `StatisticActivity`, after checking loading dialog behaviour
- simple read-only App Chrome screens

### 6.2 State-sensitive surfaces

These need save/restore or manual `uiMode` handling:

- `DetailActivity`: add/keep `uiMode` handling so active edits are not
  destroyed by a system dark-mode change. If a user edits a Thing, then
  switches system dark mode, then saves/returns, the Thing update must
  still complete.
- `DoingActivity`: avoid resetting countdown/doing state because the app
  chrome mode changed.
- `SettingsActivity`: Appearance Mode toggles must persist immediately;
  other settings should not be lost if the screen is recreated. Either save
  UI state before applying night mode or handle `uiMode` without blind
  recreation.
- `NoticeableNotificationActivity`: avoid blind recreate because
  `onDestroy()` cancels the notification. Prefer handling `uiMode` and
  reapplying the shell colours/icons in place.
- `BaseThingWidgetConfiguration` and `ThingsListWidgetConfiguration`: do
  not lose selected Thing, selected list filter, preview state, checkbox
  state, or alpha progress during a system dark-mode change.

### 6.3 Manifest policy

Add `uiMode` to `android:configChanges` only for surfaces that intentionally
handle Appearance Mode changes in place. Do not add it as a blanket fix,
because it prevents automatic resource reinflation and requires manual
repainting.

For each `uiMode` handler, add a small, explicit repaint method rather
than re-running unrelated initialization:

- `applyChromeAppearance()`
- `applyDialogShellAppearance()`
- `applyWidgetConfigurationAppearance()`

## 7. Work phases

### Phase 0 - Baseline and inventory

- Capture light-mode baseline screenshots for:
  - Home list with several Thing backgrounds.
  - Home selection mode.
  - Settings.
  - Help/About.
  - At least one standard alert dialog.
  - Date/time dialog.
  - Snackbar.
  - Noticeable notification dialog shell.
  - Single widget configuration list and preview.
  - Things-list widget configuration dialog.
- Record all hard-coded App Chrome colour/tint sites with `rg`.
- Confirm which activities can safely recreate and which need
  `uiMode` handling.

### Phase 1 - Settings and mode plumbing

- Add keys to `Def.Meta`.
- Add cached reads/writes through `FrequentSettings` or
  `AppearanceModeUtil`.
- Apply effective mode in `App.onCreate()`.
- Add Settings rows, strings, and follow-system hide/show logic.
- Persist Appearance Mode settings immediately on click.
- Verify default install/upgrade remains light.

### Phase 2 - Themes and semantic resources

- Convert App Chrome themes to DayNight parents with explicit light
  attributes.
- Add semantic App Chrome colours/drawables in `values/`.
- Add dark equivalents in `values-night/`.
- Add dark variants for selectors/ripples/dividers where needed.
- Add force-dark opt-out for API 29+ if needed.
- Build and run light-mode visual regression before proceeding.

### Phase 3 - App Chrome screens

- Update `ThingsActivity` App Chrome resources: activity background,
  drawer, toolbar/contextual toolbar, search field, empty/header chrome,
  list edge effects, snackbar anchors, and system bars.
- Update `SettingsActivity`: background, rows, labels, status text,
  controls, disabled rows, and system bars.
- Update `HelpActivity`, `AboutActivity`, and `StatisticActivity`.
- Keep all Thing cards and Thing Foregrounds on their existing
  Thing Background-aware rendering path.

### Phase 4 - Dialogs, popups, pickers, and snackbar

- Update shared dialog window/background handling in `BaseDialogFragment`
  or theme resources where possible.
- Audit and adapt foreground colours in:
  - `AlertDialogFragment`
  - `ThreeActionsAlertDialogFragment`
  - `LongTextDialogFragment`
  - `TwoOptionsDialogFragment`
  - `ChooserDialogFragment`
  - `LoadingDialogFragment`
  - `LicenseDialogFragment`
  - `PatternLockDialogFragment`
  - `DateTimeDialogFragment`
  - `ThingDoingDialogFragment`
  - `AddAttachmentDialogFragment`
  - `AttachmentInfoDialogFragment`
  - `AudioRecordDialogFragment`
  - `HabitDetailDialogFragment`
  - `HabitRecordDialogFragment`
  - `GradientOrientationDialogFragment`
  - popup picker classes
  - custom `Snackbar`
- Replace App Chrome `black_54p`, `black_26p`, `black_14p`, divider
  colours, icon tints, and pressed selectors with semantic resources or
  `AppearanceUtil`.
- Keep Thing accent/Thing Foreground logic separate from App Chrome
  foreground logic.

### Phase 5 - Hybrid and widget configuration surfaces

- Update `NoticeableNotificationActivity` shell:
  - root/dialog background
  - title text/time span
  - title icon tint
  - cancel/action icon tint
  - selector/ripple
  - system/dialog window appearance
- Do not change the embedded Thing card background or its foreground
  lightness logic.
- Update `BaseThingWidgetConfiguration` App Chrome list/toolbar.
- Preserve widget preview and wallpaper preview semantics.
- Update `ThingsListWidgetConfiguration` dialog background, text,
  dividers, checkbox/radio controls, seekbar, ripple, and confirm button.

### Phase 6 - Lifecycle tests and refinements

- Test system dark-mode changes while each state-sensitive surface is open:
  - Editing an existing Thing in `DetailActivity`, then saving and returning
    to home.
  - Creating a new Thing with content, switching system mode, then saving.
  - Showing an empty new Thing, switching system mode, then returning home
    and confirming the blank-Thing discard snackbar still appears.
  - Running `DoingActivity`.
  - Showing `NoticeableNotificationActivity`.
  - Changing settings in `SettingsActivity`.
  - Configuring both widget types.
- Fix any state loss before treating the visual work as complete.

### Phase 7 - Final verification

- `.\gradlew.bat :app:assembleDebug`
- Light-mode visual regression against Phase 0 baselines.
- Dark-mode visual pass across all scoped surfaces.
- Follow-system behaviour:
  - App in light system mode.
  - App in dark system mode.
  - System mode toggled while app is foregrounded.
- Manual forced-dark behaviour:
  - Follow-system off, force-dark on.
  - Follow-system off, force-dark off.
  - Follow-system on hides enable-dark row and uses system mode.

## 8. Acceptance criteria

- Existing users launch into the current light UI after upgrade unless they
  enable Appearance Mode settings.
- Light mode has no intentional visual differences from the current app.
- Follow-system has priority over force-dark and hides enable-dark UI.
- App Chrome surfaces are readable and coherent in dark mode.
- Dialogs, popups, pickers, and snackbars have dark-mode-safe text, icons,
  controls, dividers, ripples, and disabled states.
- Thing Background remains the highest-priority visual background.
- Thing Foreground remains driven by Thing Background lightness, not by app
  dark mode.
- Switching system dark mode while editing a Thing does not lose edits and
  does not prevent the final create/update/return flow from completing.
- Switching system dark mode while a state-sensitive configuration or
  notification surface is open does not trigger destructive lifecycle
  side effects.
- Actual desktop AppWidget output is unchanged in this first pass.

## 9. Risks

- DayNight parent themes can change implicit light-mode defaults. Mitigate
  with explicit light attributes and screenshot regression.
- Hard-coded Kotlin colour choices can leave dark-mode text/icons too dark.
  Mitigate with a targeted foreground audit, especially dialogs.
- Adding `uiMode` to `configChanges` prevents automatic resource updates.
  Mitigate by using it only where manual repaint is deliberate.
- Blind Activity recreation can lose transient UI state or trigger existing
  lifecycle side effects. Mitigate with the lifecycle policy in section 6.
- Some vector drawables may have baked colours instead of tintable paths.
  Mitigate by auditing icons used in dark-mode App Chrome.
- PopupWindow and dialog window backgrounds may not inherit Activity theme
  colours. Mitigate by testing real popup/dialog instances, not only parent
  screens.
