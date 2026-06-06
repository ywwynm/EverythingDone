# Dark Mode Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

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

NoticeableNotificationActivity embedded Thing row corner fix:
- User reported that the Thing row inside `NoticeableNotificationActivity`'s
  rounded dialog shell had become a rounded rectangle, even though it is an
  embedded full-row Thing card and should remain square.
- Root cause: `BaseThingsAdapter` first installed a rounded
  `GradientDrawable` into `CardView.background` through
  `BackgroundUtil.applyCardBackground()`. `NoticeableNotificationActivity`
  then set `holder.cv.radius = 0f`, but its PURE colour branch used
  `setCardBackgroundColor()`, which did not replace that existing
  `View.background`, leaving the previous rounded outline in place.
- Fix: after setting the embedded card radius and elevation to zero,
  `NoticeableNotificationActivity` now reapplies the Thing Background through
  `BackgroundUtil.applyCardBackground(holder.cv, bg)`, synchronising the
  runtime drawable corner radius to `0f` for both pure and gradient
  backgrounds.

Verification:
- `git diff --check` passed with CRLF conversion warnings only.
- In-sandbox `:app:assembleDebug` was blocked by `.gradle` configuration-cache
  lock permissions; the same Gradle command passed with sandbox escalation.
- Published the debug update with `:app:publishDebugUpdate` using
  `memory/debug-update-notes.md`; debug update code `202605282346` now points
  at `http://120.25.194.207/everythingdone-updates/debug/latest.json`.

Debug APK update channel publish task:
- Recorded the workflow preference that debug update APK publishing should use
  `:app:publishDebugUpdate`, not ordinary `:app:assembleDebug`.
- Fixed the publish task's command execution path after Gradle 9 rejected the
  previous `project.exec()` use at task execution time. The task now shells out
  through `ProcessBuilder`, converts Groovy interpolated values to strings, and
  reads optional release-note Gradle properties without execution-time
  `project` access.
- Ran `.\gradlew.bat :app:publishDebugUpdate --console=plain` with elevated
  permissions. The task succeeded, assembled the debug APK, uploaded the APK and
  metadata to the configured static Aliyun update source, and published debug
  update code `202605281147`.
- Ran the publish task again at the user's request to prepare an app-side update
  test. It published debug update code `202605281150` and the public
  `latest.json` endpoint returned that metadata successfully from
  the configured Aliyun static update endpoint.
- User corrected the future workflow: every `:app:publishDebugUpdate` run should
  include update notes through `-PdebugUpdateNotes=...` or
  `-PdebugUpdateNotesFile=...`, instead of publishing without release notes.

Private card width regression:
- Investigated an intermittent home-list issue where hidden private Thing cards
  could appear very narrow after repeated touch bounce animations on other
  cards and then scrolling down.
- The likely minimal cause was the hidden-private bind path: `card_thing.xml`
  uses `wrap_content`, and private cards hide every wide content surface, leaving
  only the title and lock icon to define width. Added a bind-time minimum width
  on `ll_thing_content` for hidden private cards and reset it for all other
  cards, while also restoring card touch interception for that recycled state.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`.
- User set the default workflow that debug changes should be published to the
  configured update server after successful compilation. Published this fix with
  `:app:publishDebugUpdate` and inline update notes. The task succeeded and
  published debug update code `202605281213`; the public metadata endpoint
  returned the matching release notes.
- User questioned whether the static `mCardWidth` value remains correct after
  rotation and multi-window transitions. Tightened the fix so
  `BaseThingsAdapter` refreshes `mCardWidth` from the currently attached
  RecyclerView width and current `StaggeredGridLayoutManager.spanCount` during
  binding, rather than relying only on startup display metrics. Published the
  follow-up as debug update code `202605281216`.
- Fixed a second recycled-state issue in the hidden private card branch: the
  bottom padding spacer could stay `GONE` when a holder was reused from an
  image-only card. The private branch now explicitly restores
  `view_thing_padding_bottom` visibility. Published the fix as debug update
  code `202605281220`.

Debug APK update channel:
- Ran a grill-with-docs design pass and recorded the debug-only static update
  channel decisions in memory and in
  `docs/adr/0001-static-debug-apk-update-channel.md`.
- Added `:app:publishDebugUpdate`, which assembles the debug APK, injects a
  UTC timestamp `debugUpdateCode`, writes `latest.json`, uploads the versioned
  APK and metadata with system `ssh` / `scp`, and cleans old server APKs.
- Added `server/update-debug-apk/` with setup notes, an Nginx example, and a
  directory setup helper for the Aliyun static source.
- Added debug-only manifest/network configuration for HTTP/IP update checks,
  `INTERNET`, and `REQUEST_INSTALL_PACKAGES`; release builds do not expose the
  update entry.
- Added About-screen "Check update" next to "Open source licenses", all
  localized strings, `DebugApkUpdateHelper`, the update-available dialog, and
  the fixed-height download progress dialog.
- The app now checks `latest.json`, compares `debugUpdateCode`, downloads to
  `cacheDir/debug-updates/*.apk.part`, verifies SHA-256, prechecks package name
  and Android `versionCode`, handles unknown-source authorization, and launches
  the system installer with the existing `FileProvider`.
- Updated `AGENTS.md` and `.agents/rules/gradle.md` to record that Gradle
  wrapper invocations may need sandbox escalation in Codex sessions.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed with elevated
  permissions and produced `app\build\outputs\apk\debug\app-debug.apk` at
  2026-05-28 19:28:19.
- `.\gradlew.bat :app:publishDebugUpdate --dry-run --console=plain` passed and
  confirmed the publish task graph without uploading.
- Debug merged manifest includes `INTERNET`, `REQUEST_INSTALL_PACKAGES`,
  `@xml/debug_network_security_config`, and `usesCleartextTraffic="true"`.
  The generated ordinary debug build carries `debug_update_code = 0`.
- `git diff --check` passed with CRLF conversion warnings only.
- Real Aliyun upload and device end-to-end install flow were deferred until the
  server IP/SSH configuration is available.

Daily TODO auto-create time title:
- Split the Daily TODO auto-create time picker title from the automatic
  notification time title by adding `daily_todo_set_time_title` in every
  supported `strings.xml` locale and wiring `SettingsActivity` to use it.
- Kept the user's existing `DailyTodoHelper.kt` work untouched.

Verification:
- `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug --console=plain`
  passed.
- `git diff --check` passed with CRLF conversion warnings only.

Follow-system dark-mode diagnosis:
- Traced the "follow system stays light after system dark-mode change" symptom
  to the in-app language context wrapper. `LocaleUtil.getContextForLanguage()`
  copied the full current `Configuration` before setting the stored locale,
  which could snapshot `uiMode` and block later system night-mode changes from
  reaching wrapped Activity contexts.
- Changed the wrapper to create a locale-only `Configuration` override instead,
  leaving `uiMode` and other non-locale fields to flow from the base context.
- Noted that `ThingsActivity` still uses a non-DayNight theme by design, so the
  home screen remains separate planned Appearance Mode work rather than part of
  this locale-wrapper fix.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.
- Device-level system night-mode verification was not run because only physical
  devices were connected and no emulator was available.

Button-like ripple correction pass:
- Restored compact confirm dialog buttons to symmetric horizontal padding so
  the confirm text remains visually centered inside the pill ripple.
- Added a small end/right margin to those confirm buttons instead of changing
  their internal padding, increasing the right-side dialog spacing without
  moving the text inside the pill.
- Kept the already-confirmed DateTime recurrence "new reminder time" clipping
  fix in place and did not change the deferred follow-system dark-mode issue.

Verification:
- `.\gradlew.bat :app:assembleDebug --console=plain` passed.
- `git diff --check` passed with CRLF conversion warnings only.

Button-like control ripple correction pass:
- Kept the first two user-confirmed fixes from the shaped-ripple pass: full-row
  dialog action rows are no longer auto-shaped as compact buttons, and
  `TwoOptionsDialogFragment` no longer gets a pill ripple.
- Tightened compact dialog cancel/confirm spacing by reducing the left padding
  on the right-side confirm button in paired button rows. This keeps the
  confirm text's right-side distance stable while reducing the cancel/confirm
  text gap.
- Preserved the negative-margin alignment for DateTime recurrence's "new
  reminder time" pill, but disabled clipping on the recurrence tab, its
  RecyclerView, and the item root so the left side of the pill ripple is not
  cut off.
- Reverted an over-broad attempt to switch `EverythingDoneTheme.Things` to a
  DayNight parent. Home theme conversion belongs to the dark-mode plan and
  requires dedicated light-mode visual regression.

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
