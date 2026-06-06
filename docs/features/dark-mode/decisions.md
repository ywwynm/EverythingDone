# Dark Mode Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-27 - Locale override contexts must not freeze non-locale configuration

In-app language support should wrap contexts with a locale-only
`Configuration` override. Do not copy the full current `Configuration` into
`createConfigurationContext(...)` and then change just the locale, because that
also snapshots fields such as `uiMode`. A copied override can prevent
follow-system Appearance Mode changes from reaching Activity resources when a
specific app language is selected.

## 2026-05-27 - Do not change ThingsActivity theme opportunistically

`EverythingDoneTheme.Things` must not be converted to a DayNight parent as an
incidental fix for unrelated UI work. The dark-mode plan treats home App Chrome
theme conversion as planned dark-mode work that needs explicit light-mode visual
regression, not as a side effect of button-like ripple changes.

## 2026-05-27 - Button-like control ripple colour follows its owning surface

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
