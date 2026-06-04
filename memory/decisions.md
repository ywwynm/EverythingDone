# Decisions

## 2026-05-31

### Home Card Image Placement scope

Use **Home Card Image Placement** as the canonical term for the user-facing
preference that controls where the first image attachment appears inside a
Thing Home Card relative to the card's other visible content.

The first planning scope is image placement only:
- normal-span cards may place the image at `TOP` or `BOTTOM`;
- full-span cards may place the image at `TOP`, `BOTTOM`, `LEFT`, or `RIGHT`.

Do not fold attachment selection, crop ratio, crop focus, multi-image gallery
layout, or image carousel behavior into the first Home Card Image Placement
feature. The current app already treats the first image attachment as the home
card image; users can change that image by reordering attachments in Detail.
Video thumbnails that participate in the same first image/video attachment
home-card path should use the same Home Card Image Placement, sizing, and crop
rules as images. Existing video indicators remain controlled by current video
thumbnail logic.

Home Card Image Placement is persistent Thing-level presentation state, not
attachment metadata and not per-filter UI state. Store it as a new integer
Thing field, with Kotlin naming `homeCardImagePlacement` and database naming
`home_card_image_placement`.
Database migration and first-install schema creation should both use
`home_card_image_placement INTEGER NOT NULL DEFAULT 0`, where `0` means
`DEFAULT`. Existing rows receive `0` on upgrade, preserving the current
top-image appearance.

The value shape should include `DEFAULT` in addition to explicit placements:
`0 = DEFAULT`, `1 = TOP`, `2 = BOTTOM`, `3 = LEFT`, `4 = RIGHT`. Keeping a
default sentinel lets future versions change the automatic/default rendering
policy without rewriting old Things.

For the first implementation, `DEFAULT` renders like `TOP` for both normal-span
and full-span cards. Existing image cards therefore keep their current top-image
appearance after upgrade. New Things and migrated Things should store/use
`DEFAULT`.
Expose `DEFAULT` in the placement dialog so users can return to automatic
behavior after choosing an explicit placement. In the first implementation,
the Chinese labels should be `默认（图片在上）`, `图片在上`, `图片在下`,
`图片在左`, and `图片在右`; normal-span cards only expose default, top, and
bottom options.

Removing all image attachments does not reset Home Card Image Placement. The
setting is simply dormant while there is no image, and it becomes effective
again if the user later adds an image attachment.
When a Thing has no image attachments, the app should not show a Home Card
Image Placement editing entry. There is no separate overflow action for
pre-setting image placement before an image exists.
If a private Thing is hidden on the home screen, Home Card Image Placement must
not affect that hidden-private rendering. The hidden card continues to show the
private/lock presentation without image layout. The stored placement remains
dormant and becomes effective again if the home card is allowed to show its
content.

The editing entry should live on the Detail image attachment item, not in the
main Detail overflow menu. Add a small placement icon inside the image
attachment tile, immediately to the left of the delete icon. The entry should
be tied to the first image attachment, because Home Card Image Placement affects
the home-card image selected by current first-image attachment semantics, not
each image attachment independently.

Show the Home Card Image Placement entry only on the first image attachment
tile, only in editable Detail mode, and hide it while Detail is preparing a
screenshot. Do not show the entry on every attachment tile, because placement
is not per-attachment state.

Clicking the placement entry should open a dialog rather than expanding inline
controls on the attachment tile. The dialog owns the placement choices and
commits the chosen value back into Detail's normal edit state.
The placement dialog should use tap-to-apply behavior: tapping an option applies
that placement and closes the dialog immediately, rather than requiring a
separate confirmation button. A changed selection still creates a normal
undo/redo action and then shows feedback after the dialog closes.

The Home Card Image Placement dialog is an App Chrome dialog. Its shell should
follow Appearance Mode like other configuration surfaces. Picked/selected
placement options may use the current Thing Background or representative Thing
colour for emphasis, but the entire dialog surface should not become a Thing
Background surface.

`LEFT` and `RIGHT` are valid stored Home Card Image Placement values even when a
Thing is currently normal-span. Normal-span home-card rendering should degrade
stored `LEFT` / `RIGHT` to the default top-image presentation. The normal-span
placement dialog should show only `TOP` and `BOTTOM`. If the user later switches
the same Thing back to full span, the stored `LEFT` / `RIGHT` placement becomes
effective again.
When a normal-span Thing has stored `LEFT` or `RIGHT`, the normal-span placement
dialog should show the selected state as `默认（图片在上）`, matching the actual
degraded home-card rendering. Opening the dialog must not rewrite the stored
value. The stored `LEFT`/`RIGHT` value is overwritten only if the user taps an
available normal-span option.

Changing Home Card Image Placement participates in DetailActivity's normal edit
lifecycle, matching Home Card Span Mode. Selecting a different placement in the
dialog updates Detail's edit state, adds an undo/redo action, and is persisted
only through the existing save/update return flow. A no-op selection should not
add an action or mark the Thing as changed. Saving a changed placement updates
the Thing's `updateTime`.
After a placement change, Detail should show user feedback like Home Card Span
Mode. Prefer Snackbar and fall back to Toast if no Snackbar host is available.
Use explicit Chinese messages that reference the Thing Home Card, for example
`已将图片放置于记事卡片上方`, `已将图片放置于记事卡片下方`,
`已将图片放置于记事卡片左侧`, and `已将图片放置于记事卡片右侧`.
For default, use `已将图片位置设为记事卡片默认位置`.

For full-span side-image rendering, use fixed layout proportions in the first
implementation. `LEFT` places the image on the physical left side and content
on the right; `RIGHT` reverses that. These stored values are physical
left/right semantics rather than layout-direction start/end semantics. The
image column should take roughly 42% of the card content width in the first
implementation, with the ratio and minimum/maximum dimensions kept as resource
tokens. The side image should visually fill the card's final height rather than
ending at an independent fixed image height. Do not make the image/content
ratio user-adjustable in the first implementation.

For side-image placement, the user's primary visual concern is the horizontal
image/content ratio. Height follows the final card height: the side image fills
that height and is expected to crop as needed to preserve a stable side panel.
Use a separate resource token for the side-image minimum height, initially
128dp after visual tuning, so sparse content does not make the side image too
shallow without leaving excessive empty space under short content columns.
Side-image card height should be driven by the larger of the content column's
measured height and the side-image minimum height, not by the source image's
intrinsic aspect ratio. The image fills and crops inside the side panel instead
of expanding the card based on its own ratio.
Use `centerCrop` as the first implementation's unified image scaling strategy
for every placement. Top and bottom placement continue the current bounded
image-card behavior, while side placement fills the side panel height and crops
overflow. Do not add user-adjustable crop ratio, crop focus, or crop region
state in the first implementation.
Side images should behave as full-height card-edge visual blocks. The image
itself should touch the card's outer side, top, and bottom edges without content
padding, while the opposite content column keeps the normal text/content
padding. Card corner clipping should still apply.
For side-image placement, the non-image content column keeps the existing home
card element order: title, content/checklist, audio, reminder/goal, habit, and
bottom spacer. The feature should only move the image relative to that existing
content stack.
Implementation may restructure the Thing Home Card layout into an explicit
image container and content container. Top/bottom placement can reorder those
containers vertically, while full-span left/right placement can put them in a
horizontal parent with a fixed image/content ratio. This replaces the current
hard assumption that the card image always lives at the top of the single card
content stack.

For `BOTTOM` Home Card Image Placement, the image should appear after all main
card content: title, content/checklist, audio, reminder/goal, and habit. When
the image is placed at the bottom, it becomes the bottom visual block and the
normal bottom padding spacer should be hidden so the image reaches the card
bottom edge.
Normal-span bottom-image placement should keep the same image sizing algorithm
as the current normal-span top-image card. Moving the image to the bottom should
not introduce a new ratio rule or alter waterfall width/height assumptions; the
main layout difference is image order and bottom padding handling.
Full-span top/bottom image placement should keep the same full-span horizontal
image sizing strategy as the current full-span top-image card. Only full-span
left/right placement enters the new side-image layout with the fixed
image/content ratio.
Bottom image placement should keep a normal content gap between the preceding
content column and the image block for both normal-span and full-span cards.
The bottom image still hides the final bottom spacer so it reaches the card
bottom edge.
For full-span left/right placement, side-image height is constrained by the
measured height of the non-image content column, with the side-image minimum
height as the floor. The image width continues to use the configured horizontal
ratio, and the bitmap is cropped inside that fixed side panel. Image attachment
count UI must remain inside the side image panel.
When a card changes span or placement, binding must reset image container and
thumbnail `LayoutParams`. Rebuild the Glide request only when the image path or
target image size changes, so ordinary content rebinding does not reload the
same thumbnail unnecessarily.
The tap-to-apply placement dialog hides its action row, but should keep 12dp of
bottom breathing room so the final option does not touch the dialog bottom
edge.

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

2026-06-02: Thing Card media display settings should be shared Thing Card
presentation preferences rather than home-list-only preferences. Image and
video thumbnails used on Thing Cards are named Thing Card Media. The new
planning vocabulary separates Thing Card Media Crop, which controls the visible
region and frame ratio, from Thing Card Media Background, which uses the media
as the card's visual background behind Thing Foreground. Thing Card Media
Background does not replace Thing Background; Thing Background remains the
Thing's identity background and fallback.

2026-06-02: Thing Card Media Background should not switch the card to a new
reduced-information cover layout. It should keep the existing Thing Card
content layout and rely on an overlay/mask plus live preview to make title,
content, reminders, habit state, doing state, private state, and other card
foreground content readable on top of the media.

2026-06-02: The preferred planning direction for Thing Card media customization
is an entry from the thing list, such as a long-press contextual action, where
the user can preview changes in place while choosing the media source, crop
ratio, crop focus, span mode, and whether the media is used as the card
background.

2026-06-02: Thing Card Media Source is a persistent Thing Card presentation
preference that chooses which image or video attachment provides Thing Card
Media. Its default behavior is the first available image/video attachment. When
the user explicitly chooses an attachment, that chosen attachment remains the
source even if attachment order changes. If the chosen attachment is deleted or
unavailable, rendering falls back to the first available image/video attachment
without clearing the stored preference.

2026-06-02: The home-list entry for Thing Card Appearance should use the
existing long-press flow. On underway lists, long-press still starts MOVING for
sorting; when the drag ends without a real move, the existing clearView flow
enters SELECTING contextual toolbar mode. The contextual toolbar should show a
"customize card appearance" action when exactly one Thing is selected, that
Thing has image/video attachments, and it is not the current doing Thing. The
appearance editor covers span mode, image placement, media source, crop ratio,
crop focus, and whether Thing Card Media Background is enabled.

2026-06-02: Thing Card Appearance live preview should use a temporary draft.
Entering the appearance editor applies draft settings to the selected visible
Thing Card for preview. Confirming writes the settings to the Thing and
database. Cancel or back restores the original card appearance without
persisting the draft.

2026-06-02: The Thing Card Appearance editor should stay inside the thing list
as a bottom editing panel, using the real selected Thing Card as the live
preview target. Entering the editor should keep or scroll the selected card into
the visible area above the panel and may temporarily add RecyclerView bottom
padding so the panel does not hide the preview. A separate Activity is rejected
because it would lose the live home-card layout preview.

2026-06-02: Because Thing Card Appearance customization combines several
complex interaction decisions, the final design should be documented in
`docs/plans/` and recorded with an ADR in `docs/adr/` once the key product and
implementation trade-offs are settled.

2026-06-02: Thing Card Appearance customization has two distinct feature goals.
The first is adjusting the crop ratio and visible crop position for the
separate image/video thumbnail region. The second is using an image/video
thumbnail directly as the Thing Card Media Background, which also needs crop
ratio and visible crop position controls. The two goals can share editor
controls and persistence structures, but they are not the same feature.

2026-06-02: When Thing Card Media Background is enabled, the separate
image/video thumbnail region should not render. The chosen media is drawn behind
the existing Thing Card content layout instead. Thing Card Image Placement and
separate thumbnail crop settings remain available for the non-background mode
and resume when the media background is disabled.

2026-06-02: Thing Card Thumbnail Crop and Thing Card Media Background Crop
should be persisted separately. They can share editing controls, and enabling
media background for the first time may initialize the background crop from the
thumbnail crop, but later changes should not keep them coupled.

2026-06-02: Thing Card Media Background Crop must be previewed and rendered
against the card's real measured height, because Thing Card height is hard to
predict statically and depends on title, content, checklist, reminder, habit,
audio, private, doing, span, and other visible card content.

2026-06-02: Thing Card media crop controls should allow free cropping of the
source image/video thumbnail, not only choosing a display frame ratio. The
editor can also provide preset aspect ratios as shortcuts or constraints, but
the user should be able to decide the visible source region.

2026-06-02: Thing Card media crop settings should store display parameters
only, such as a normalized crop rectangle and any selected ratio constraint.
They must not modify the underlying image or video attachment files. Attachment
viewing, sharing, exporting, and Detail attachment lists continue to use the
original attachment files.

2026-06-02: Thing Card Appearance editing should use a two-level interaction
for crop work. The in-list bottom panel handles fast card-appearance controls,
including media source, span mode, image placement, media background toggle, and
preset crop ratios. A dedicated larger crop editor handles precise free crop
adjustment; confirming in that crop editor returns to the bottom panel and
applies the draft crop to the live Thing Card preview.

2026-06-02: Video attachments should support Thing Card Video Frame selection
inside the Thing Card Appearance work instead of deferring it. For a video media
source, the user should be able to choose the video frame that provides Thing
Card Media, then crop that selected frame for the separate thumbnail and/or
Thing Card Media Background. The implementation plan should include a clear
todo checklist so the interaction can be built and verified step by step.

2026-06-02: Thing Card Video Frame should persist the selected video time point,
such as `videoFrameMs`, rather than persisting a generated frame image as the
source of truth. The app should generate and cache frame images on demand using
a cache key that includes the video identity, video file modified time, selected
time point, and target size.

2026-06-02: Thing Card media crop and video-frame preferences should be stored
per media source attachment. Each image/video attachment that has been selected
as Thing Card Media Source can keep its own thumbnail crop, media-background
crop, and video frame time point. Switching the current Thing Card Media Source
uses that source's own saved appearance settings instead of applying one global
crop/frame setting to all attachments.

2026-06-02: Thing Card media appearance settings should associate per-attachment
settings with the existing attachment storage item string (`typePathName`) and
include optional file characteristics such as last modified time and file size
for validation. Do not introduce a new attachment ID system for the first
implementation, because that would broaden attachment persistence, reorder,
delete, export, and import behavior beyond the card-appearance feature.

2026-06-02: The first Thing Card Appearance implementation should affect Thing
Card surfaces only, such as the home list, DoingActivity, and
NoticeableNotificationActivity. AppWidget image regions and system notification
big pictures should keep their current behavior for the first version; extending
crop, media background, and video-frame selection there is future scope.

2026-06-02: Hidden private Thing Cards must not expose Thing Card Media. When
private content is hidden, the card keeps the existing lock presentation and
does not show the separate thumbnail or media background. The Thing Card
Appearance editor should not open from a hidden private card unless the user has
authenticated or the current UI state is already allowed to show private
content.

2026-06-02: Thing Card Media Background should include a readable overlay/mask.
The first version should choose an automatic baseline mask direction/strength
from the media and foreground readability, while also exposing a simple user
control for mask strength, such as a 0% to 80% slider, so the user can trade
off media visibility against text/icon readability during live preview.

2026-06-02: Thing Card Media Background mask strength should be stored per media
source attachment, not as one global card value. Different images and selected
video frames can need very different mask strengths, so each `typePathName`
source record should keep its own user-adjusted mask strength while still
allowing a default automatic baseline for never-adjusted sources.

2026-06-02: In Thing Card Media Background mode, card height ownership belongs
to the existing content layout. Title, content, checklist, reminder, habit,
audio, private, doing, span, and other visible card content determine the actual
Thing Card height. The media background then covers that measured card
rectangle using the source crop/focal settings. Media background crop ratio must
not force card height or truncate the existing content layout.

2026-06-02: For separate Thing Card Media placed at the top or bottom of a
Thing Card, the media region owns its own height. The card width determines the
media region width, the selected thumbnail crop ratio determines the media
region height subject to practical min/max constraints, and the rest of the card
content keeps its existing adaptive height above or below the media.

2026-06-02: For separate Thing Card Media placed left or right, media crop ratio
must not participate in layout size calculation. Side placement should separate
side panel geometry from media crop. Side panel geometry determines how much
card width the media column uses and therefore how much width remains for the
content column. Media crop only controls the visible source region inside that
already-sized side panel. This avoids a feedback loop where crop ratio changes
content width, content width changes content height, and content height changes
the visible media rectangle again.

2026-06-02: Thing Card Side Media Width should be part of Thing Card Appearance.
The first implementation should allow controlling the media column width for
left/right placement separately from crop ratio and crop position.

2026-06-03: Thing Card Side Media Width should be freely adjustable within a
guarded valid range rather than limited to a small set of fixed presets. It is a
layout control: changing side media width changes the content column width, so
the measured content height and final card height may change. Crop center and
user zoom remain media-framing controls and must not change layout.

2026-06-03: Thing Card Side Media Width should default to 42% and be
continuously adjustable from 30% to 60% in the first implementation.

2026-06-03: In media-background mode, the content's natural measured height is
the minimum card height, but the user can increase the media background target
height and therefore increase the final card height. The final card height is
the maximum of natural content height and the user's background media height
preference. This lets sparse cards become taller media-backed cards without
allowing media settings to compress or truncate content.

2026-06-03: When media-background mode increases the card height beyond the
content's natural height, distribute the extra vertical space intentionally:
primary content such as title, body text, and checklist stays top-aligned;
reminder, goal, and habit status blocks align to the bottom and stack upward;
the middle becomes flexible space. Sticky/ongoing markers stay at the top edge,
and the doing overlay still covers the whole final card.

2026-06-03: In media-background mode with extra vertical space, the bottom
status block includes audio attachment count, reminder/goal time information,
habit summary, habit next reminder, recent habit record, and finished-this-period
habit information. Primary content above the flexible space includes title,
plain text body, and checklist.

2026-06-03: Media-background mode should still show image/video attachment
count, but not as the current over-thumbnail corner badge. Use an inline status
indicator shape similar to the existing audio attachment count so it fits the
bottom status area visual language.

2026-06-03: In the media-background bottom status block, reminder/goal and habit
status information should sit closer to the bottom edge than attachment status.
Image/video and audio attachment counts should appear above those more important
time/habit status rows.

2026-06-03: The doing overlay keeps its current priority in media-background
mode. It covers the whole final Thing Card after media background, mask, content,
and status layout are applied; no special bottom-status handling is needed while
the overlay is visible.

2026-06-03: Selection and moving-mode visual feedback should keep priority over
Thing Card media appearance. In media-background mode, unselected dimming or
moving feedback should apply to the whole final card rather than to a separate
image region.

2026-06-03: Media-background height preference should be stored as a ratio
relative to current card width, not as an absolute dp height. Rendering derives
preferred height from the current card width and the saved ratio, then uses the
maximum of content natural height and preferred media background height, subject
to practical min/max guards.

2026-06-03: Media-background height preference should be stored per media source
attachment. Different images and selected video frames can need different card
heights, so each source record should keep its own media-background height
ratio.

2026-06-03: Media-background height preference should default to `null`, meaning
the media background does not add extra height and the final card height is the
content's natural height. The editor can save a ratio after the user adjusts
background height and should provide a reset/adapt-to-content action that clears
the ratio back to `null`.

2026-06-03: Media-background height control should be continuous rather than a
small set of presets. The editor should preview changes live and guard the range
with content natural height as the minimum and span/screen-specific maximum
height constraints.

2026-06-03: In the home list, media-background height can be permissive because
the user is intentionally controlling the card's look. The first maximum for
home-list media-background height should allow up to 96% of screen height for
both normal-span and full-span cards. Single-card surfaces such as DoingActivity
and NoticeableNotificationActivity should still cap against their available
space so the card does not cover required controls or exceed the dialog/screen
layout.

2026-06-03: The permissive 96% home-list height maximum applies to
media-background height only. Top/bottom separate thumbnail height should keep a
more conservative max guard in the new appearance editor.

2026-06-03: Top/bottom separate thumbnail height should be capped at 72% of
screen height for both normal-span and full-span cards in the first Thing Card
Appearance implementation. This adds a normal-span max guard that current code
does not have and raises the existing full-span max from 36% to the new 72%
limit.

2026-06-03: Top/bottom separate thumbnail minimum height should also be
expressed as a percentage of screen height rather than as a fixed dp value. The
new appearance editor should use percentage-based min/max guards for top/bottom
thumbnail height so the limits scale with device and orientation.

2026-06-03: Initial top/bottom separate thumbnail height guards are normal-span
minimum 12% of screen height, full-span minimum 18% of screen height, and 72% of
screen height maximum for both span modes. Treat these as configurable tuning
values that can be adjusted later.

2026-06-03: Thing Card Appearance height percentages should be calculated
against the current card surface's available content height rather than raw
physical screen height. Home list should use the RecyclerView/list visible
content area, DoingActivity should use the card area available above its bottom
controls, and NoticeableNotificationActivity should use its dialog/screen
available area.

2026-06-03: The Thing Card Appearance editor should enforce height guard ranges
during dragging instead of letting the user drag beyond the range and only
clamping after save. Live preview and persisted rendering should match.

2026-06-02: Thing Card media cropping should be understood as applying a
user-selected crop center and zoom/scale to the current Thing Card Media Target,
not as one fixed crop aspect ratio that always controls layout. The media target
is determined by placement and measured content: top/bottom uses a separate
thumbnail target whose width fills the card and whose height follows the
selected source crop area's aspect ratio; left/right uses the side panel target
derived from side media width and measured content height; media background uses
the measured card rectangle. When Thing content changes, keep the saved crop
center and zoom/scale, recompute the effective visible source region for the new
target rectangle, and do not require the previous visible crop aspect ratio to
remain unchanged.

2026-06-02: Thing Card media crop scale should be stored as an extra zoom
multiplier relative to the minimum cover-fit scale for the current media target.
`scale = 1.0` means the media just covers the target rectangle, while larger
values zoom in further. Cropping must never introduce black/empty borders, so
the minimum stored scale is 1.0 and crop center/pan must be constrained to the
range that still fully covers the media target.

2026-06-02: The "no black border" rule means the renderer must compute the
cover-fit base transform from source media size and current Thing Card Media
Target before applying user zoom. For example, a 100x90 source drawn into a
160x90 target needs a base cover scale of 1.6 on the source width before any
user zoom is applied. Stored crop `scale = 1.0` is multiplied on top of that
cover base, so it still fills the target without empty side bars.

2026-06-02: For top/bottom thumbnail placement, the thumbnail target aspect
ratio should come from the user's freely drawn source crop shape. Preset ratios
can still be offered as shortcuts, but they should not be the only allowed
ratios.

2026-06-02: Top/bottom thumbnail crop should persist the freely chosen source
crop shape as `sourceAspectRatio`. This value can come from direct crop-frame
dragging or from a preset ratio shortcut. It determines the top/bottom thumbnail
target height, while `centerX`, `centerY`, and user zoom preserve the crop
position and zoom intent.

2026-06-02: `sourceAspectRatio` belongs only to top/bottom thumbnail crop. Media
background crop and left/right side media crop should store crop center and user
zoom only, because their media target ratio is determined by the measured card
or side-panel rectangle.

2026-06-02: One `thumbnailCrop` record can be shared across top/bottom and
left/right separate thumbnail placement. All placements use `centerX`,
`centerY`, and user zoom. Only top/bottom placement reads `sourceAspectRatio`;
left/right ignores it but does not clear it, so switching back to top/bottom can
restore the previous top/bottom thumbnail ratio.

2026-06-02: A media source without saved Thing Card Appearance settings should
default to behavior equivalent to the current centered `centerCrop`
implementation. Use centered crop intent (`centerX = 0.5`, `centerY = 0.5`) and
cover-fit user zoom (`scale = 1.0`) for new sources. Do not inherit another
source's crop by default.

2026-06-02: For the same media source, first-time initialization can inherit
crop center and user zoom between thumbnail crop and media-background crop. If
background crop is missing, initialize its center/zoom from thumbnail crop. If
thumbnail crop is missing, initialize its center/zoom from background crop and
use a default `sourceAspectRatio` for top/bottom thumbnail height. Do not copy
`sourceAspectRatio` into background crop.

2026-06-02: Default top/bottom thumbnail `sourceAspectRatio` should preserve
the current visual defaults: normal-span thumbnails default to 4:3 and full-span
top/bottom thumbnails default to 16:9. Side placement and media background do
not use `sourceAspectRatio`.

2026-06-02: Switching between normal-span and full-span should use the span's
default top/bottom thumbnail ratio only when the source has no customized
thumbnail crop yet. Once the user has customized `sourceAspectRatio`, changing
span should preserve that user ratio instead of replacing it with the other
span's default.

2026-06-02: The crop editor UI does not need to display a numeric zoom
multiplier. Internally the app still stores user zoom relative to cover-fit, but
the UI should present direct manipulation of the image/crop frame instead of a
visible "1.0x" or similar scale label.

2026-06-02: The crop editor interaction should differ by media placement mode.
For top/bottom separate thumbnails, the user can resize the crop frame because
the freely chosen source crop-area aspect ratio determines thumbnail height.
For left/right side media and media-background mode, the media target ratio is
fixed by the measured card layout, so the crop editor should keep the target
frame fixed and let the user pan/zoom the media inside it.

2026-06-02: When Thing content changes after appearance settings have been
saved, do not rewrite the saved crop center, user zoom, side media width, or
top/bottom source crop aspect ratio. Re-render against the new measured target
rectangle using cover-fit plus the saved user zoom, then clamp the crop center
if needed so no black/empty borders appear. Top/bottom thumbnail height remains
driven by the saved source crop aspect ratio and card width; left/right side
media and media background naturally expand or shrink their visible source
region as content-driven target height changes.

2026-06-02: The first Thing Card Appearance implementation should not actively
warn or prompt the user when later content updates make a saved crop look less
ideal. Re-render automatically using the saved appearance intent; the user can
manually reopen the appearance editor if they dislike the new result.

2026-06-02: Left/right Thing Card Media placement should remain full-span-only.
Normal-span cards support top/bottom separate thumbnails and media background,
but not side-by-side media/content. If a Thing stores left/right placement and
side media width while full-span, then later switches to normal span, rendering
should fall back to an allowed placement without clearing the stored side
settings; restoring full-span should restore the saved side placement and side
media width.

2026-06-02: Thing Card Media Background is allowed in both normal-span and
full-span cards. It does not require side-by-side geometry; the media target is
the measured card rectangle for the current span.

2026-06-02: Top/bottom separate thumbnail height should be constrained by
practical min/max guards even though the user can freely choose the source crop
aspect ratio. Use separate normal-span and full-span bounds, tuned from existing
Thing Card image dimensions and screen-height ratios, so extreme crop ratios do
not create unusably thin strips or oversized card images.

2026-06-02: Thing Card Media Background should not add its own media-specific
height min/max constraints. Its height is owned by the existing content layout
and existing card-content constraints such as text max lines, checklist limits,
and single-card surface caps.

2026-06-02: The first Thing Card Media Background Mask should be a uniform
single-colour overlay across the whole card, not a gradient mask. The overlay
direction can be black or white based on readability, and strength is adjusted
per media source.

2026-06-02: Video frame selection UI should use a timeline slider, current-frame
preview, and small step buttons such as previous/next 0.5s or 1s. Do not build a
filmstrip-style frame timeline in the first implementation, because it requires
batch frame decoding and heavier cache/performance handling.

2026-06-02: For video Thing Card Media Source editing, frame selection should
happen before crop editing. The crop editor works on the selected frame. If the
user later changes the selected frame, keep the existing crop center and user
zoom as the initial crop intent for the new frame and let the user adjust it.

2026-06-02: The media source selector should offer a "default" source that uses
the first available image/video attachment, plus explicit attachment choices.
Do not add a "no media" source in the first implementation, because hiding all
card media is a separate behavior. Media background is controlled by its own
enable/disable setting.

2026-06-02: When media background is enabled in the Thing Card Appearance
editor, hide or disable the separate thumbnail placement controls because the
separate thumbnail region does not render. The editor should focus on media
source, background crop, video frame if relevant, and mask strength. When media
background is disabled, show placement and thumbnail crop controls again.

2026-06-02: The Thing Card Appearance editor should edit only the crop for the
currently visible media mode. When media background is enabled, crop actions edit
the media-background crop. When media background is disabled, crop actions edit
the separate thumbnail crop. The two crop settings are still stored separately,
but the UI should not show both crop editors at the same time.

2026-06-02: When Thing Card Media Background is enabled, Thing Foreground
colours should be chosen from the masked visible media background rather than
from Thing Background alone. Thing Background remains the identity background,
fallback background, editor accent source, and loading/error background, but
text and icons on the media-backed card must adapt to the actual masked media
for readability.

2026-06-02: If Thing Card Media cannot be loaded because the selected attachment
is missing, unavailable, or blocked by permissions, the card should silently
fall back to Thing Background and normal foreground readability. If another
image/video attachment is available, rendering may temporarily fall back to the
first available media source according to the media-source fallback rule. Stored
appearance preferences should not be cleared automatically. The appearance
editor should surface the unavailable media state and let the user choose
another source or clear the setting.

2026-06-02: Thing Card Appearance persistence should migrate all card
presentation settings into one JSON-backed model instead of keeping span mode
and image placement as separate long-term fields. The unified JSON should carry
the former span and image-placement settings plus media source, per-source
thumbnail crop, per-source media-background crop, per-source video frame time,
media-background enablement, and media-background mask settings.

2026-06-02: The old `thing_card_span_mode` and `thing_card_image_placement`
SQLite columns should be semantically deprecated but do not need to be
physically removed from upgraded databases. Add a new `thing_card_appearance`
JSON column. Upgrade should copy existing span/image-placement values into the
JSON model. Runtime reads should prefer JSON and fall back to old columns only
when JSON is missing. Runtime writes should write the JSON model, not continue
updating the old columns as the source of truth. Fresh schema can use the JSON
column as the card-appearance source.

2026-06-02: Thing content `updateTime` should remain the content/update time of
the Thing itself. Changing Thing Card Appearance from the list editor should
write a separate appearance update timestamp inside `thing_card_appearance`
JSON, such as `appearanceUpdateTime`, instead of reusing content `updateTime`
or adding another SQLite column. The appearance timestamp lets the app track
card-presentation changes without implying that title, content, reminder,
habit, or attachment content changed, while keeping all card-appearance state
inside one JSON-backed model.

2026-06-02: If a save operation changes Thing content state, update content
`updateTime`. If it changes Thing Card Appearance, update
`appearanceUpdateTime` inside the appearance JSON. If both content and
appearance changed in one save operation, update both timestamps.

2026-06-02: When the user explicitly removes image/video attachments and saves
that attachment change, Thing Card Appearance should remove per-source crop,
media-background crop, and video-frame settings for the deleted media
attachments. Runtime file unavailability or missing permissions should not clear
appearance settings, but an intentional saved attachment deletion should prevent
stale per-source settings from accumulating.

2026-06-02: Once the new Thing Card Appearance editor exists, remove the old
Detail-side card appearance shortcuts instead of keeping parallel entry points.
The old full-span action menu item and the first-attachment image-placement
button should be removed so card appearance is edited through the unified
list-side Thing Card Appearance editor.

2026-05-31: The Thing Home Card image/video count label should use equal
horizontal and vertical margins. Use 10dp for both side margins and bottom
margin.

2026-05-31: Detail image attachment action icons should use the same visual
brightness and a circular press ripple. The home-card image placement icon uses
`white_76p`, matching the existing delete attachment icon's effective opacity,
and both attachment action icons use the same oval-mask ripple drawable.

2026-05-31: Detail image attachment action icons should keep a 40dp touch target,
but their visible circular ripple should be slightly inset so it does not touch
the image tile edge. Use a 2dp inset on the circular ripple mask.

2026-06-01: Rename the persistent card presentation settings from Home Card to
Thing Card because the settings now apply beyond the home list. Kotlin should
use `thingCardSpanMode` and `thingCardImagePlacement`; SQLite should use
`thing_card_span_mode` and `thing_card_image_placement`. Database version 12
should add the new columns and copy values from the legacy
`home_card_span_mode` and `home_card_image_placement` columns when present.

2026-06-01: DoingActivity should support Thing Card Span Mode and Thing Card
Image Placement. Normal-span cards keep the current width, while full-span
cards use a wider width with configurable horizontal margins. The card region
should grow to show as much content as possible, then cap itself against the
space above the bottom buttons with a configurable vertical margin.

2026-06-01: NoticeableNotificationActivity should also support Thing Card Span
Mode and Thing Card Image Placement. Normal-span cards keep the current 280dp
dialog width. Full-span cards use one consistently wider dialog/card width
regardless of whether the image is placed top, bottom, left, or right. Left and
right image placement remains full-span-only across card surfaces.

2026-06-01: DoingActivity and NoticeableNotificationActivity should use the
same fixed dp widths for their single Thing Card surfaces. Normal-span cards use
`thing_card_single_surface_normal_width` at 256dp. Full-span cards use
`thing_card_single_surface_full_span_width` at 300dp. Both surfaces still cap the
configured width against the screen width minus
`thing_card_single_surface_horizontal_margin` on each side.

2026-06-03: Freeze Thing Card Appearance v1 into
`docs/plans/THING_CARD_APPEARANCE_EXECUTION.md` as the implementation and
verification checklist. When older planning notes conflict with later
2026-06-03 height decisions, the execution checklist and
`THING_CARD_APPEARANCE_PLAN.md` are authoritative for v1. In v1, the separate
side-media user control is side width; media-background height can be adjusted
per source with content natural height as the minimum.

2026-06-03: Implement the first Thing Card Appearance code slice by keeping
`thing_card_span_mode` and `thing_card_image_placement` as physical
compatibility columns while making `thing_card_appearance` JSON the runtime
source of truth. `Thing.thingCardSpanMode` and `Thing.thingCardImagePlacement`
remain compatibility facade properties backed by `ThingCardAppearance`, and
DAO writes target JSON instead of actively writing the old columns.

2026-06-03: Appearance update timestamp comparison should ignore
`appearanceUpdateTime` itself. DAO content updates compare the old and new
Thing Card Appearance presentation data without the timestamp; only real
presentation changes refresh `appearanceUpdateTime`.

2026-06-03: Thing Card Appearance's bottom-panel UI should keep the editor
compact by relying on the precise crop editor for crop center and zoom. The
inline crop center X, crop center Y, and crop zoom sliders should be removed
from the bottom panel.

2026-06-03: Thing Card Appearance panel controls should use the selected
Thing's full Thing Background as their accent source. Text buttons and selected
choice labels that can render a gradient should use the full `ThingBackground`;
platform controls that only accept a single tint, such as `SeekBar` and
similar sliders, should use the representative color. Cancel actions should follow
the app-chrome dialog cancel-button color rather than the Thing accent.

2026-06-03: The Thing Card Appearance media source row should visibly indicate
that it is clickable by showing a dropdown arrow at the trailing edge. Every
clickable panel control should have ripple feedback, including generated
precise-crop dialog buttons.

2026-06-03: In the Thing Card Appearance panel, media-background mask strength
and media-background height controls should use compact single-row layouts with
their label and slider on the same row. The media-background height control is
user-facing "Card height" because it makes the final media-background card
taller than content natural height; its reset action only resets that saved
per-source height ratio and should be labeled simply "Reset".

2026-06-03: Thing Card Appearance panel changes must stay dark-mode safe by
using App Chrome foreground colors, App Chrome ripple colors, and
ThingBackground representative colors instead of hard-coded light-mode colors.

2026-06-03: Thing Card Appearance panel interaction should treat the panel as an
App Chrome surface for touch containment and ripple feedback. The panel consumes
blank/non-interactive touches so they never leak to the underlying Thing list.
Clickable controls and precise-crop dialog controls use App Chrome ripple colors
so light mode shows black-tinted feedback and dark mode shows white-tinted
feedback. Thing Background still owns selected-control text/accent rendering and
single-tint platform controls use the Thing representative color.

2026-06-03: Thing Card Appearance media-background mode is selected through the
same media-position row as Top, Bottom, Left, and Right. The row is labeled
"Image/video position", the background option is simply "Background", and the
old checkbox plus summary line are removed because selected options already
communicate the current state.

2026-06-03: Thing Card Appearance live preview should use the final home-list
available-height basis while the bottom panel is open. This keeps normal-span
thumbnail ratio clamps and media-background card-height clamps aligned between
live preview and the persisted card after the panel closes. Full-span sparse
minimum-height rules must not overwrite an explicit media-background card-height
preference.

2026-06-03: Thing Card Appearance's source selector should call the unfixed
source state "Auto select" rather than "Default source". The row is labeled
"Cover image" and uses an App Chrome `PopupPicker`-style popup like
DetailActivity's quick reminder picker, not a platform `PopupMenu`.

2026-06-03: Thing Card Appearance segmented options should render selected
state as a Thing Background pill with adaptive foreground text, not as gradient
text alone. This applies to card width, media position, and cover-image ratio
shortcuts. Indicator labels should use the more subdued App Chrome hint colour,
while option text uses the stronger secondary colour when unselected.

2026-06-03: Thing Card Appearance card-width labels should be concise:
"Normal" / "Wide" in English and "正常" / "宽" in Simplified Chinese. The
cover-image ratio selector should expose 1:2, 9:16, 3:4, 1:1, 4:3, 16:9, and
2:1 shortcuts, with the slider sharing the second row. The precise crop action
belongs in the bottom action row on the left, paired visually with Cancel and
Confirm on the right.

2026-06-03: Thing Card Appearance popup and segmented-control polish should
follow the app's compact dialog rhythm. The cover-image source popup needs
visible elevation and a small gap from the panel so it does not visually merge
with the panel surface. Multi-option segmented controls need small margins
between Thing Background pill selections, and label-plus-option groups need
slightly more vertical spacing. The crop action should read "Crop cover image"
and align its text with the panel label column, while Cancel/Confirm keep the
same right-edge spacing rhythm as other compact `DialogFragment` actions.

2026-06-03: Thing Card Appearance high-frequency live preview updates must not
use per-progress adapter `notifyItemChanged` or repeated preview `smoothScroll`
calls. Slider-driven changes such as media-background card height, thumbnail
ratio, side media width, and mask strength should coalesce preview refreshes to
the next frame and rebind only the currently visible selected card. This avoids
mutating the `RecyclerView` child set while `StaggeredGridLayoutManager` is
laying out or flinging. Do not add a new catch for the resulting
`IllegalArgumentException`; if the root update path is still wrong, the crash
should remain visible.

2026-06-03: Superseding the previous visible-holder-only preview refresh
decision, Thing Card Appearance edits that can change card size must still let
the adapter and `StaggeredGridLayoutManager` run a real item change. The safe
path is to coalesce high-frequency slider changes and issue one deferred
`notifyItemChanged` only when `RecyclerView` is not computing layout, has no
pending adapter updates, and is idle. This keeps background card height,
thumbnail ratio, and side width previews live while avoiding adapter changes
during layout or fling.

2026-06-03: Cover-image ratio editing should no longer use separate preset
pills. Use a single slider whose endpoints are the current effective min/max
source aspect ratio derived from card width and the configured thumbnail height
guards. Draw labeled tick marks for the preset 1:2, 9:16, 3:4, 1:1, 4:3, 16:9,
and 2:1 ratios when they are reachable in the current range, and snap to a
nearby reachable tick when the user releases the slider. Use the same slider
and tick design in the precise crop editor.

2026-06-03: The precise crop editor should have a visible title and size its
preview view from the selected image or video-frame aspect ratio, with a
screen-height cap. The crop frame inside that preview still represents the
actual target rectangle for the current thumbnail, side-media, or media
background mode.

2026-06-03: Media-background card-height preview shrinkage must not derive the
next expanded content height from the view's currently laid-out height. During
live preview, the current laid-out height can still be the previous larger
height, so using it in `max(...)` makes the card shrink only after a later full
home-list relayout. Bottom-status layout work posted from media-background bind
must carry the current bind token and ignore stale posts, then compute the
expanded text-content height from the current target minimum height and natural
content height instead of old `llContent.height`.

2026-06-03: Thing Card Appearance media rendering callbacks must apply the
current render request, not the crop and size values captured by an older Glide
listener closure. Thumbnail and media-background image views now keep separate
render-request tags in addition to load-key tags, so rapid normal-span ratio
changes, full-span side-width/ratio changes, crop confirmations, and background
height changes cannot be overwritten by a stale posted crop-matrix update.

2026-06-04: Thing Card Appearance ratio sliders should draw preset-ratio ticks
inside the slider region, not in a separate label row below it. Dense preset
labels alternate above and below the track so adjacent ratios such as 1:2 and
9:16, or 16:9 and 2:1, do not collide. Both the bottom-panel ratio slider and
the precise-crop ratio slider snap during drag when the thumb is near a
reachable preset tick, with a final normalization on release.

2026-06-04: The precise crop editor must use the same visual source orientation
and target geometry as final Thing Card rendering. Image-source preview bitmaps
should apply EXIF orientation before entering the crop UI. Media-background
crop target ratio should be computed from the current draft's final background
target height and content natural height instead of trusting a possibly stale
already-laid-out `CardView.height`.

2026-06-04: Superseded later the same day. Media-background card height was
briefly considered as a transparent card-level height target view inside the
card root frame, with the content container staying `wrap_content` and the
media background/mask staying `match_parent`. Device feedback showed this did
not reliably solve the height-slider shrink path, so the current rule below
returns height ownership to `llContent.minimumHeight` plus explicit overlay
size reset/sync.

2026-06-04: Superseded later the same day. Appearance preview refreshes were
briefly changed to wait for the RecyclerView item animator to be idle before
issuing the deferred `notifyItemChanged`. This proved too conservative for
continuous height-slider preview, so the current rule below ends old item
animations once layout/pending-update/scroll states are safe.

2026-06-04: Precise-crop confirmations that only change crop center or user
zoom should apply the new crop matrix directly to the currently visible selected
Thing Card holder. The deferred `notifyItemChanged` path remains necessary for
layout-changing edits such as aspect-ratio changes, but center/zoom-only edits
do not need a card remeasure and should not wait behind RecyclerView layout,
pending adapter updates, scroll, or item animator idle checks. The direct path
must update the current render-request tag before applying the matrix so stale
Glide callbacks for the same load key keep using the latest crop.

2026-06-04: Thing Card media Glide requests must not apply Glide transformations
for the final card media itself. The card renderer owns cover-fit, pan, and user
zoom through `applyThingCardMediaCrop()`. If the target `ImageView` is still
`CENTER_CROP` when Glide loads into it, Glide can auto-center-crop the drawable
to the target dimensions before the app matrix runs; then saved `centerX` and
`centerY` have no extra source area to pan and the card appears permanently
center-cropped. Use `dontTransform()` on both thumbnail and media-background
requests, then apply the app-owned matrix after the drawable is ready.

2026-06-04: Superseded later the same day. After removing the transparent
card-level height target view, media-background height was briefly moved back to
`llContent.minimumHeight` with explicit overlay reset/sync. Device feedback
showed this still did not make the height slider take effect, because the real
background layer was still not the direct height owner.

2026-06-04: Appearance preview refreshes should still avoid adapter changes
while RecyclerView is computing layout, has pending adapter updates, or is
scrolling. Once those states are safe, a running old item animator should be
ended with `endAnimations()` before issuing the current `notifyItemChanged`,
rather than deferring repeatedly behind prior change animations. This keeps
height-slider previews responsive without hiding RecyclerView framework
exceptions.

2026-06-04: Media-background card height should be owned directly by
`iv_thing_media_background.layoutParams.height`, with
`view_thing_media_background_mask.layoutParams.height` kept in sync. On each
media-background bind, set those overlay heights to the current target height
before requesting layout so the root `wrap_content` card measures against the
visible background layer itself. After root-card pre-draw, set the overlay
heights to the final measured root-card height and apply/load the crop against
that same target. The Card height slider should not rely only on deferred
`notifyItemChanged`; after updating the draft, it should directly update the
currently visible selected holder's background `ImageView` height and request
layout on both the item and RecyclerView, falling back to deferred adapter
refresh only when the holder is not visible.

2026-06-04: Media-background Card height slider bounds must reflect the
renderer's effective minimum height. The minimum slider value is the selected
card's current natural content height converted to the same available-height
percentage scale used by `mediaBackgroundHeightRatio`, capped by the configured
maximum. Do not expose lower values that cannot change rendering because the
content layout already requires that height. When the slider value is at or
below this dynamic minimum, store `mediaBackgroundHeightRatio = null` so the
card follows natural content height; the Reset action also clears the ratio and
the UI displays the thumb at the dynamic minimum.

2026-06-04: Media-background natural-height measurement must ignore artificial
layout state written by previous media-background passes. The adapter may expand
`ll_thing_text_content.height` and enable `view_thing_bottom_status_spacer`
with `weight=1` to push bottom status to the lower edge. When computing the
background-height slider minimum or media-background crop target height, the
measurement code should temporarily restore text content to `wrap_content`,
hide the artificial spacer with zero weight, measure, then restore the live
layout state. Otherwise the slider minimum can be inflated by an old expanded
height and become inconsistent.

2026-06-04: Media-background Card height no longer needs a separate Reset
button. The dynamic slider minimum is the no-extra-height/reset state, so the
background mask and Card height control rows should align on the same label
width and expose only the two sliders.

2026-06-04: The precise crop editor should present the whole source image,
not only the selected crop frame. The selected crop rectangle is unmasked, and
all non-selected editor regions are dimmed. Superseded detail: pinch zoom must
not resize the crop frame. The crop frame remains fixed for the current target
aspect ratio; drag and pinch gestures move and scale the image under that fixed
frame, matching the final card renderer's cover-scale crop matrix.

2026-06-04: Media-background height previews should preserve the current
drawable when the source media and selected video frame are unchanged. Target
size changes should update the current render request and crop matrix
immediately, and reuse that drawable as the Glide placeholder for any later
size-specific reload, so the underlying Thing Background does not flash during
height dragging.

2026-06-04: Continuous media-background Card height dragging must not rebind
the whole appearance panel or recalculate the dynamic slider minimum on every
progress change. The minimum captured during panel binding is the stable
threshold for that drag session; changing live holder layout while dragging can
otherwise inflate the measured minimum and make near-left-end drags jump the
card taller.

2026-06-04: Media-background image-count text should be a bottom Card overlay,
not a child of `ll_thing_text_content`. It is informational chrome for the media
background, and keeping it out of the text-content flow prevents spacer-driven
bottom-status re-layout from making it flicker during Card height changes.

2026-06-04: Media-background mask/Card-height labels should not use a fixed dp
column width. Use `wrap_content` labels followed by a small SeekBar start
margin; row-level automatic measurement is preferred over forced slider-column
alignment for these two controls.

2026-06-04: Media-background height preview must treat a `null`
`mediaBackgroundHeightRatio` as natural content height, not as a zero-height
background target. The direct preview path should compute a positive effective
target height (`max(targetMinHeight, naturalContentHeight)`) before updating
`iv_thing_media_background` and its mask. Passing `0` into the overlay-height
setter causes the view to become `MATCH_PARENT`, which can reuse stale parent
height and make the card jump taller near the slider's left end even though the
saved value is correct.

2026-06-04: Entering Things selection mode must end any running RecyclerView
item animations and disable change animations before the full selection-mode
rebind. Minimum-height media-background cards can have correct saved data while
the live transition still reuses an old measured card height from the animator;
the mode transition should remove that animated measurement state before
rebinding selected/unselected card chrome.

2026-06-04: Media-background rendering must not feed the parent `CardView.height`
back into `iv_thing_media_background.layoutParams.height`. RecyclerView boundary
relayout, pre-layout, and selection-mode transitions can expose stale parent
heights even when the saved appearance data is correct. The media-background
height source of truth is `max(saved media-background target height, natural
content height)`, and pre-draw should use that computed value while only relying
on the laid-out card for final width.

2026-06-04: Media-background natural-content-height measurement must prefer the
explicit content width written during the current bind
(`llContent.layoutParams.width`) over `ll_thing_text_content.width` or
`CardView.width`. Reused holders can carry stale laid-out widths from a previous
card or mode transition; measuring text against that stale width can make the
bind-time natural height differ from the pre-draw natural height, causing a
minimum-height media-background card to grow when entering MOVING/SELECTING
mode. `toMovingMode()` should also use the same RecyclerView rebind preparation
as `toSelectingMode()` before its full mode refresh.

2026-06-04: The Thing Card Appearance panel must use the same explicit
content-width rule when computing the media-background Card height slider's
dynamic minimum. `ThingsActivity.getThingCardBackgroundNaturalHeight()` should
prefer `holder.llContent.layoutParams.width` over stale laid-out text/card
widths, otherwise the slider UI can expose a temporary minimum lower than the
renderer's effective minimum after a preview rebind.

2026-06-04: Media-background image/video count chrome should use the same
icon-plus-text composition as the audio attachment count. Tint the count icon
and text with the same adaptive media-background tertiary foreground color
instead of relying on static white or dark assets, so masked media backgrounds
keep the status readable.

2026-06-04: Superseding the previous exact-color media-background count-icon
decision, the media-background image/video count icon should follow the audio
attachment count's black-side/white-side resource semantics rather than being
tinted with the exact text ARGB value. The icon PNG carries its own alpha, so
using the semi-transparent tertiary text color as the tint double-applies alpha
and can make the icon visually mismatch the count text.

2026-06-04: Superseding the media-background count-icon color-filter approach,
do not render the card image/video count icon from the global
`ic_image_count.png` asset. That asset's alpha peaks below the 66% text tier and
includes transparent canvas padding, so even opaque black/white color filters
can look too light and require runtime matrix cropping. Use card-specific
cropped black/white PNG resources with alpha raised to the 66% tier, and switch
between those resources like the audio attachment count.

2026-06-04: SeekBar-style sliders should not rely on platform/theme default
inactive-track rendering. `DisplayUtil.setSeekBarColor(...)` now owns the full
progress drawable so inactive tracks stay visible in both light and dark App
Chrome. Thing Card Appearance sliders should pass the full `ThingBackground` to
the new SeekBar helper: pure backgrounds render a solid active track, gradient
backgrounds render a gradient active track, and the thumb remains the
representative color for a stable handle.

2026-06-04: Superseding the first custom SeekBar layer-list implementation,
SeekBar-style sliders should use an app-owned self-drawing progress drawable
instead of a `LayerDrawable` with `InsetDrawable`/`ClipDrawable` children.
The custom drawable draws the inactive track across the full slider bounds and
then draws the active portion from the current drawable `level`, so the track
does not depend on platform layer bounds or theme default progress drawing.
Gradient Thing Backgrounds are rendered directly in this drawable; the thumb
remains a representative single-colour handle.

2026-06-04: Superseding the single self-drawing SeekBar progress drawable,
SeekBar-style sliders must still expose the standard `ProgressBar` layer IDs:
`android.R.id.background`, `android.R.id.secondaryProgress`, and
`android.R.id.progress`. Android's `ProgressBar.setVisualProgress(...)` updates
the drawable level on the layer matching the refreshed progress ID; a single
custom drawable can render initially but lose reliable track painting after
dragging. Use a `LayerDrawable` where the background layer draws the full
inactive track, the secondary layer is a transparent placeholder, and the
progress layer is a `ClipDrawable` wrapping the active track drawable. Keep
gradient rendering inside the active track drawable and keep the thumb as the
representative single-colour handle.

2026-06-04: Detail media attachment classification must not wrap arbitrary
external-storage files with the app `FileProvider` just to inspect whether they
contain a video track. Chosen media may resolve to paths such as
`/storage/emulated/0/DCIM/Camera/...`, which are outside the configured
provider roots. Use `MediaMetadataRetriever` against the original picker
`content://` URI when available, falling back to the file path only when there
is no source URI. Metadata failure may fall back to the known video postfix to
avoid crashing the add-attachment flow, while a successful "no video track"
result still lets ambiguous `mp4`/`3gp` files become audio attachments.

2026-06-04: The Thing Card Appearance panel title should be treated as
Thing-background-owned accent text. It uses the current Thing Background as
pure colour or gradient text, matching the panel's selected controls and crop
dialog title, and the panel needs slightly more top padding so the title has
the same visual breathing room as other compact app-chrome surfaces.

2026-06-04: Video cover-frame selection belongs in the precise crop dialog,
not in the bottom Thing Card Appearance panel. When the current media source is
a video, the crop action reads "Crop cover video" / "裁切封面视频"; the dialog
uses the same crop preview surface for the selected video frame and the crop
region. Below that preview, playback/pause and stop circular icon buttons plus
a frame SeekBar select the source frame. Dragging the slider or simulated
playback updates the crop preview frame; confirming the dialog saves
`videoFrameMs` together with the current crop values in one source-appearance
update. Cancelling the dialog discards the in-dialog frame selection.

2026-06-04: Superseding the simulated playback part of the previous video crop
dialog decision, the precise-crop preview for video sources should use real
`TextureView` + `MediaPlayer` playback. Periodic still-frame decoding is not a
true preview and can make the playback controls look inert. The video crop view
owns the media surface and overlays the same rounded crop mask/pan/zoom
interaction as the image crop editor, while applying the same cover-scale,
crop-center, and user-scale matrix formula used by final card rendering.
`MediaMetadataRetriever` remains useful only for opening-time initial-frame
sizing, and confirmed `videoFrameMs` should come from the current playback/seek
position with protection against asynchronous `seekTo()` completion lag.

2026-06-04: The video precise-crop preview should not show a blank `TextureView`
while `MediaPlayer.prepareAsync()` and first-frame rendering are still pending.
Keep the `TextureView` alive but transparent during loading, cover the preview
area with a rounded App Chrome surface and a custom indeterminate progress
indicator, and reveal the video/crop overlay only after the first
`onSurfaceTextureUpdated` frame arrives. The progress indicator is
Thing-background-owned: pure backgrounds draw a solid arc and gradient
backgrounds draw a sweep-gradient arc. Video playback state should be explicit
inside the crop view so completion reliably switches the play/pause button back
to the play icon.

2026-06-04: Video precise-crop playback completion must not rely solely on
`MediaPlayer.OnCompletionListener`. Some device/video combinations can leave the
dialog play/pause icon in the pause state even after visible playback has ended.
The video crop view's position ticker should also detect end-of-video using the
current playback position and actual `MediaPlayer.isPlaying` state, then route
both listener-based and ticker-based completion through one `finishPlayback()`
function that clears the explicit `playing` flag and dispatches
`onPlayingChanged(false)` on the main thread.

2026-06-04: Thing Card Appearance "Auto select" media source means
`mediaSourceKey = null`, which resolves to the first available image/video media
source in the Thing attachment string order. The popup label for the auto row
must therefore resolve with a null source key, not with the current explicit
draft source key, otherwise the auto row can display the currently explicit
media while clicking it actually switches back to the default first media.

2026-06-04: Video cover-frame selection must use exact-frame semantics across
the crop dialog, opening-time preview decode, and final card rendering.
Glide's `RequestOptions.frameOf(...)` defaults to
`MediaMetadataRetriever.OPTION_CLOSEST_SYNC`, so every video cover request that
uses a stored `videoFrameMs` must also set
`VideoDecoder.FRAME_OPTION = MediaMetadataRetriever.OPTION_CLOSEST`. The
opening-time `MediaMetadataRetriever.getFrameAtTime(...)` path should use the
same `OPTION_CLOSEST` mode. To avoid selecting metadata-only end positions that
some devices cannot render into a paused `TextureView`, video frame sliders and
the crop playback view clamp to a small renderable end guard instead of
`duration - 1ms`; seek-complete also has a bounded first-frame fallback so the
loading overlay cannot spin forever when a surface update is not delivered.

2026-06-04: Video precise-crop gestures need a visible still-frame and paused
preview path, not only live `MediaPlayer` playback. A fixed-size `TextureView`
plus `setTransform(...)` can fail to give immediate visual feedback while the
player is paused or while the first surface frame is pending. The video crop
view should instead lay out the `TextureView` itself as the current scaled media
rectangle (`imageLeft`, `imageTop`, scaled source width/height), matching the
image crop editor's geometry. During initial loading or missing first-frame
callbacks, draw the already decoded opening frame as a fallback layer under the
same crop overlay, and keep the crop frame visible so panning and pinch zoom
show the current crop position even before the live texture is ready.
