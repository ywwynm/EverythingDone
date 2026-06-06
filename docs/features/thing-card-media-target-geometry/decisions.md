# Thing Card Media Target Geometry Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-06-06 - Suppress list appearing animation during appearance previews

Thing Card Appearance live preview refreshes should not trigger the home-list
`thingsAppearingAnimation`, including after configuration changes. The preview
workflow rebinds only the selected card with `notifyItemChanged`, so if the
adapter-level appearing-animation flag is left enabled after rotation, only the
actively edited Thing animates upward while other cards remain stable. While the
appearance panel is showing, configuration changes and preview refreshes should
keep that adapter flag disabled.

## 2026-06-06 - Cap Thing Card Appearance editor surfaces at 480dp

The home-list Thing Card Appearance bottom panel and the precise crop editor
dialog should not expand to full tablet width. Keep their phone behavior by
using the available window width on narrow screens, but cap the editor surface
width at `480dp` on larger screens. The bottom panel remains bottom-attached
and centered, while the crop editor uses the same constrained width for both
its window and preview-height calculation.

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
`docs/features/thing-card-appearance/` and recorded with an ADR in `docs/adr/`
once the key product and implementation trade-offs are settled.

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
`docs/features/thing-card-appearance/execution.md` as the implementation and
verification checklist. When older planning notes conflict with later
2026-06-03 height decisions, the execution checklist and
`docs/features/thing-card-appearance/plan.md` are authoritative for v1. In v1, the separate
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

2026-06-04: Thing Card Appearance source selection now has three distinct
states: `mediaSourceKey = null` means Auto select, an explicit source key means
that specific image/video, and `ThingCardAppearance.MEDIA_SOURCE_NONE` means
hide all image/video media on the Thing Card. The None state is a persistent
user choice and should survive attachment cleanup/reordering, while per-source
crop, frame, background, and sizing settings remain preserved for when the user
selects Auto or a concrete media source again. Hiding media only affects media
display and media-dependent controls; Card width controls remain available so a
hidden-media card can still be normal or wide.

2026-06-04: Thing Card Appearance is not only a media-appearance editor. Any
eligible real Thing Card may open the panel to adjust Card width, even if the
Thing has no image/video attachments. In no-media cases, the panel hides the
cover-source row and every media-dependent control, leaving only the title,
Card width controls, and cancel/confirm actions. When media sources do exist,
the source popup order is Auto select, explicit image/video sources in
attachment order, and None as the final row.

2026-06-04: Inline image/video attachment-count rows and audio attachment-count
rows should use the same bottom-spacing strategy: the row itself has zero
bottom padding and the standard `view_thing_padding_bottom` spacer supplies the
bottom gap (`THING_CARD_DEFAULT_PADDING_BOTTOM_DP`, 16dp). Their icons should
share fixed view dimensions (`12x14dp` normal, `14x16dp` large) instead of using
the PNG intrinsic widths, because `card_image_attachment_count` is much wider
than `card_audio_attachment` and otherwise shifts the count text to the right.

2026-06-04: A hidden-media inline image/video count row should not participate
in full-span sparse-card minimum height. `thing_card_full_span_sparse_min_height`
is 120dp and is useful for title/text/audio-only wide cards, but applying it
when hidden-media count chrome is present makes the count row appear to have a
large bottom margin. Keep the count row's normal bottom spacer at 16dp and skip
the full-span sparse minimum while that row is visible. The shared count-icon
view dimensions stay fixed for text-start alignment. Use `fitCenter` so the
wider image/video count PNG is not clipped, but make the icon view slightly
wider (`14x14dp` normal, `16x16dp` large) so the image/video icon does not look
too small.

2026-06-05: Image/video count icons use the same fixed view dimensions in
inline hidden-media rows and media-background overlay rows. The media-background
overlay must not fall back to PNG intrinsic size, because that makes the icon
larger than the inline count row. Image/video count icons keep `fitCenter` and
receive `1dp` left/top padding to nudge the fitted drawable slightly right and
down; audio count icons do not receive that padding. Image/video count icon
views are 2dp wider than audio count icon views (`16x14dp` normal and
`18x16dp` large), while their text start margins are 2dp smaller (`6dp` normal
and `10dp` large), keeping the following text aligned with audio count text.

2026-06-05: Thing Card Appearance confirmation is the single durable update
point for custom card appearance operations. After `ThingsActivity` persists the
draft with `updateThingCardAppearance`, remote surfaces that depend on saved
appearance must be refreshed immediately: single-Thing widgets for the edited
Thing, all Things-list widgets, and the ongoing Thing notification. Individual
appearance controls should continue to update only the in-memory draft and home
card preview until the user confirms.

2026-06-05: Single-Thing widget configuration preview should prioritise showing
the actual provider-sized widget surface rather than expanding to show the full
Thing content. The preview frame should clip to the Thing Card corner radius,
and fallback previews should be clipped/non-scrolling so they do not imply a
different widget reading surface. For remote side media, keep the user's saved
side-width percent as a clamp but store an optional per-source display aspect
ratio hint at Thing Card Appearance confirmation time. AppWidgets may use that
derived hint to reduce left/right media stretching caused by widget widths that
do not match home-list card widths; the hint is not a primary user setting and
should degrade back to the percent-based behaviour when unavailable.

2026-06-05: Thing Card Appearance should distinguish the media target aspect
ratio from media crop. The target aspect ratio controls the shape of the area
where Thing Card Media is drawn across top/bottom thumbnails, side media
panels, and media backgrounds. Crop remains a per-source presentation choice
that controls crop center and user zoom inside the chosen target ratio, and
video frame selection remains independent from both.

2026-06-05: Thing Card Media Target Aspect Ratio should be stored per media
source, not as one card-wide value. Different image/video attachments can have
different natural presentation shapes, and switching the Thing Card Media Source
should restore the target ratio and crop choices that were tuned for that
source.

2026-06-05: Thing Card Media Target Aspect Ratio is a preferred target that
surfaces should respect as strongly as possible. Home cards, AppWidgets, and
other card projections may apply explicit guardrails only to keep the rest of
the Thing Card readable and to stay within platform rendering limits. Those
guardrails adapt the current surface projection and must not rewrite the saved
target ratio.

2026-06-05: The unified Thing Card Media Target Aspect Ratio slider should use
the same visual control style as the current cover-image ratio slider, but its
effective min/max range should be derived from the active media placement's
existing guardrails. Top/bottom derives the range from thumbnail height
constraints, side placement derives it from side-media width constraints, and
media background derives it from background height constraints. The slider ends
should correspond to the active min/max values instead of using one fixed
global ratio preset range for every placement.

2026-06-05: Legacy Thing Card Appearance geometry fields should be read for
compatibility but should not be preserved after the user confirms a new edit in
the Thing Card Appearance UI. Once the user changes and saves card appearance,
the app should write the new per-source media target aspect ratio field and omit
the migrated legacy geometry fields from the saved JSON where possible.

2026-06-05: The legacy geometry fields to remove from newly confirmed Thing
Card Appearance JSON include the card-wide `sideMediaWidthPercent`,
per-source `mediaBackgroundHeightRatio`, `thumbnailCrop.sourceAspectRatio`, and
`sideMediaDisplayAspectRatioHint`. The crop objects should keep crop center and
user zoom only; the new per-source media target aspect ratio should carry target
shape across foreground thumbnails, side media, media backgrounds, home cards,
and AppWidget projections.

2026-06-05: Thing Card Media Target Aspect Ratio should be stored per media
source and per presentation mode. The same image/video source may have separate
target ratios for foreground thumbnails, side panels, and media backgrounds.
This preserves the existing separation between thumbnail and background tuning,
while moving side media away from the old card-wide width percentage.

2026-06-05: Missing Thing Card Media Target Aspect Ratio values should be
initialised lazily per source and per presentation mode. Foreground thumbnails
default to the existing span-based ratios (`16:9` for full-span cards and `4:3`
for normal cards). Side panels derive their first ratio from legacy
`sideMediaWidthPercent` and the measured preview side-panel height when
available, falling back to the old default side width if measurement is not
available. Media backgrounds derive their first ratio from legacy
`mediaBackgroundHeightRatio` when available, otherwise from the current natural
card height. Initialising one presentation mode must not overwrite target ratio
values for the other modes.

2026-06-05: Thing Card Media Crop should also be stored per media source and
per presentation mode. Foreground thumbnails, side panels, and media
backgrounds each keep their own crop center and user zoom, so adjusting one
presentation does not overwrite the crop tuned for another presentation.

2026-06-05: When the user switches Thing Card Media presentation and the new
presentation has no saved target ratio or crop values for the current source,
the app should seed the new presentation from the previously active
presentation. The seeding should preserve target ratio, crop center, and user
zoom as much as possible, while clamping the target ratio to the new
presentation's active min/max guardrails. This is an initialisation step only;
after the new presentation has saved values, switching back and forth should
restore each presentation's own values.

2026-06-05: During presentation seeding, crop center should be preserved where
possible because it represents the user's chosen subject. Crop user zoom should
also be preserved unless the new target ratio would fail to cover the target
area; in that case the app should raise zoom only as much as needed for cover
rendering. The app should not shrink zoom during seeding unless a separate hard
maximum requires it.

2026-06-05: New per-presentation Thing Card Media Target Aspect Ratio and Crop
values should follow the existing Thing Card Appearance draft workflow. Slider
changes, media presentation switches, and crop edits update only the in-memory
draft and live preview. Cancelling restores the original appearance. Confirming
the panel is the only durable write point, and confirmation should normalise the
JSON into the new model, omit migrated legacy geometry fields, and refresh
remote surfaces that depend on saved appearance.

2026-06-05: The new per-source Thing Card Appearance JSON should group media
geometry by presentation instead of flattening fields directly under
`SourceAppearance`. Each source should have presentation entries such as
`thumbnail`, `sidePanel`, and `mediaBackground`, where each entry can hold its
own target aspect ratio and crop. Media-background-only settings such as mask
strength belong to the media background presentation entry.

2026-06-05: Presentation seeding must be non-destructive. When the user switches
from one media presentation to another and the target presentation has no saved
draft values, the app may derive an initial target presentation state from the
source presentation and clamp it to the target presentation's guardrails.
However, the source presentation's draft values must remain unchanged. If the
user switches back before confirming, the original presentation should restore
its exact previous draft state rather than inheriting any clamped values created
for the target presentation. None of these seeded values are durable unless the
user confirms the Thing Card Appearance panel.

2026-06-05: Thing Card Appearance confirmation should save only media
presentation entries that already existed, were migrated from legacy fields, or
were touched/seeded during the user's current edit session. It should not eagerly
write all possible presentation entries for every media source. Untouched
presentations should remain absent so future defaulting and migration rules can
continue to evolve.

2026-06-05: `ThingCardAppearance.hasSamePresentationAs` should compare
normalised JSON rather than raw data-class equality. Legacy compatibility fields
can remain readable in memory, but they should not affect presentation-change
detection once new serialization omits them.

2026-06-05: Side-panel Thing Card media should use a deterministic geometry
projection instead of deriving slider ranges and render widths from the current
live side-media View height. The saved `sidePanel.targetAspectRatio` remains the
user intent; min/max side-width percentages are render guardrails. During slider
dragging, the active ratio range and preset tick mapping should stay stable, and
home cards plus AppWidgets should share the same conceptual projection rules.

2026-06-05: The Thing Card Appearance panel should expose a side-panel-only
"cover image width" slider below the target-ratio slider. This slider is an
alternate projection control for `sidePanel.targetAspectRatio`: it displays and
accepts the projected side media width as a percentage of the card width, but it
must not restore `sideMediaWidthPercent` as a canonical saved field.

2026-06-06: The Thing Card crop editor dialog should always expose the
target-ratio slider for the active presentation. Video sources should show the
video-frame slider first and the ratio slider below it. The side-panel cover
image width slider remains a main appearance-panel control only and should not
be duplicated inside the crop dialog.

2026-06-06: Things List AppWidget media-background bitmaps should be capped by
pixel budget, not only by dp-based dimensions. Collection rows should degrade to
ordinary widget backgrounds if media-background rendering fails, because
oversized RemoteViews bitmaps can cause the affected row and following rows to
disappear in launchers.

2026-06-06: Things List AppWidget rows that use Thing Card Media Background
should reserve the projected media-background target height with
`View.setMinimumHeight` through RemoteViews. The reserved height is the maximum
of the saved `mediaBackground.targetAspectRatio` projection and the estimated
natural content height, bounded by the existing hard widget bitmap height cap.
The bitmap may still be downscaled for the collection-row pixel budget, but it
must keep the same target aspect ratio as the reserved row surface so `fitXY`
does not turn crop-preserving output into visible stretching.

2026-06-06: AppWidgets should directly respond to Android launcher resize
events via `onAppWidgetOptionsChanged`. Single-Thing widgets should regenerate
their RemoteViews immediately so media bitmaps are re-rendered against the new
widget options. Things List widgets should notify their collection data changed
and update the outer RemoteViews so visible rows are re-created with the new
size options.

2026-06-06: Feature-specific project documentation should be organized under
`docs/features/<kebab-case-feature-slug>/`, with one directory per feature,
migration, review track, or substantial technical initiative. Each feature
directory may contain `README.md`, `plan.md`, `execution.md`, `review.md`,
`analysis.md`, and optional `debug-updates.md` files as needed. `CONTEXT.md`,
`docs/adr/`, and canonical `memory/*.md` files remain global sources of domain
language, durable decisions, preferences, sessions, and follow-ups.

## 2026-06-05 - Remote AppWidget surface projection

Thing Card Appearance remains the stored visual intent. AppWidgets render a
Thing Card Surface Projection of that intent onto a widget or list-row surface;
the projection must not write widget-adapted media dimensions back to the Thing's
saved appearance fields.

AppWidget projections should preserve media source, media source None, top /
bottom / left / right placement, crop center, user zoom, exact video frame,
media background, media-background mask, and side media width. Saved thumbnail
or media-background aspect ratios are treated as desired media target ratios.
When following the desired ratio would consume the widget's usable content area,
single-Thing AppWidget rendering may clamp the rendered media target height to
its fixed widget budget while keeping the saved crop semantics inside that
clamped target. This adaptive clamp is a surface rendering decision, not a
change to home-list Thing Card rendering and not a database value change.

Single-Thing AppWidget media-height projection is content-floor first. For
top/bottom media, the renderer should reserve enough vertical space for the
widget card to remain recognizable as a Thing: title or private state, at least
one line of body text or checklist content when present, required reminder,
habit, state, or action regions, and bottom padding. Media receives the
remaining height budget. If the saved desired ratio would exceed that budget,
the media target is reduced before content is sacrificed; if needed it may fall
back to a small thumbnail target rather than letting the fixed AppWidget show
only media.

Things List AppWidget rows should not use a product-level row-height clamp just
because top/bottom media has a tall saved ratio. The list widget already scrolls
through its collection rows, so a row may grow to honor the saved desired media
ratio and content layout. Hard safety caps for RemoteViews bitmap dimensions,
IPC limits, and launcher compatibility still apply; if those caps are hit,
degrade the affected row rather than changing saved Thing Card Appearance.
These safety caps are platform transport limits, not visual design limits:
first attempt to honor the saved ratio, then reduce or degrade only when the
rendered bitmap would be too large to send safely through RemoteViews or too
risky for launcher compatibility.

Do not rely on arbitrary nested scrolling inside AppWidget rows or ordinary
single-Thing AppWidgets to support unbounded top/bottom media height. The
reliable AppWidget scrolling primitive is a collection view. Things List rows
should rely on the parent collection scroller rather than row-level nested
scrolling, and converting the single-Thing AppWidget into a collection widget is
a separate feature trade-off rather than the default answer for this appearance
port.

Single-Thing AppWidget configuration has two different card surfaces. The Thing
candidate list is an App Chrome selection surface and should reuse home-list
Thing Card rendering so users can recognize wide cards, placement, media
backgrounds, crops, and video frames while choosing a Thing. The post-selection
preview is a widget preview surface and should render the single-Thing
AppWidget projection instead of a plain home-list card: apply widget alpha,
widget size/aspect, square widget chrome, RemoteViews-compatible media
projection, and the fixed-surface content-floor budget for top/bottom media.

Add additional launcher-visible AppWidget size presets for media-heavy Thing
Cards and tablet or large-grid launcher use. Existing AppWidget providers remain
resizable, but separate provider entries improve discoverability and give
launcher pickers better default cell shapes. Single-Thing AppWidgets should keep
the existing 1x1, 2x2, 3x3, and 4x4 presets and add 4x2, 2x4, 4x3, 3x4, 5x2,
2x5, 5x3, 3x5, 5x4, 4x5, 5x5, 6x2, 2x6, 6x3, 3x6, 6x4, 4x6, 6x5, 5x6, and
6x6. Do not add 1xN or Nx1 single-Thing media presets beyond the existing 1x1,
because they are too narrow for the media-heavy Thing Card surfaces that drive
this expansion. Things List AppWidget should keep the existing 3x3 provider and
add 4x4, 5x4, 4x5, 5x5, 6x4, 4x6, 6x5, 5x6, and 6x6 presets.

AppWidget Size Preset labels should show the cell shape in the launcher picker.
Renaming provider labels is acceptable because it does not change existing
receiver classes, provider XML bindings, widget ids, or saved Thing/widget
relations. Existing single-Thing providers should be relabeled as 1x1, 2x2,
3x3, and 4x4 presets; new single-Thing providers should be labeled with their
cell shapes up to 6x6. The existing Things List provider should be relabeled
3x3, and new Things List providers should be labeled with their cell shapes up
to 6x6.

Every AppWidget Size Preset provider should declare both the Android 12+
`targetCellWidth` / `targetCellHeight` default grid shape and `minWidth` /
`minHeight` fallback dimensions. `targetCell*` expresses the intended launcher
cell preset on Android 12+, while `minWidth` / `minHeight` remains the fallback
for Android 11 and below and for launchers that still derive picker size from
minimum dimensions.

Implementation decision: new single-Thing AppWidget size preset providers may
reuse `BaseThingWidgetConfiguration` instead of registering one configuration
Activity per size. The base configuration screen resolves the actual provider
class from `AppWidgetManager.getAppWidgetInfo(appWidgetId).provider.className`
and writes the corresponding saved widget `size` through
`AppWidgetHelper.getSizeByProviderClass`. This keeps provider entries distinct
for launcher pickers while avoiding duplicated configuration classes.

## 2026-06-05 - Remote AppWidget side media fallback geometry

Things List AppWidget left/right media fallback sizing must not use thumbnail
source aspect ratio to derive side-panel height. Source aspect ratio belongs to
top/bottom thumbnail projection; side media is a full-height panel whose height
comes from the visible list-row content projection. When a saved
`sideMediaDisplayAspectRatioHint` is unavailable, Things List rows should
estimate the text/reminder/habit/state content height and render the side media
bitmap for that row height, subject only to RemoteViews bitmap safety caps.

Things List AppWidget item rendering should resolve the concrete provider class
from the `appWidgetId` before estimating widget width. `RemoteViewsFactory`
cannot directly read the parent collection row's measured width in `getViewAt`,
so the practical fallback is launcher options when present, otherwise the
provider preset's default cell width. The old single 320dp list fallback is too
wide for smaller list widgets and can make saved side-media width percentages
project as a much larger actual row fraction.

Remote AppWidget side media `ImageView`s should use `centerCrop`, not `fitXY`.
The pre-rendered bitmap target should still be close to the expected slot, but
RemoteViews and launcher measurement can leave small mismatches between bitmap
size and final view size. `centerCrop` preserves media proportions in those
mismatches; `fitXY` turns the mismatch into visible non-uniform stretching.
