# Thing Folders Sessions

## 2026-06-20 - Fix Activity header boundary flicker

- Follow-up testing showed that both root Underway and in-Folder projections
  could flicker when the first visible card approached the actionbar. The title
  and actionbar shadow could briefly disappear, and the in-Folder count subtitle
  could reappear underneath list cards.
- Root cause: `ActivityHeader.updateAll(...)` still treated the old 102dp
  spacer as the maximum valid scroll distance and reset larger values to `0`.
  At the exact spacer boundary, RecyclerView can still report the invisible
  header item as visible while its top is already beyond that legacy threshold,
  so the header jumped from collapsed back to expanded for a frame.
- Fixed the state calculation by clamping scroll distance to the current header
  spacer height instead of resetting to expanded state. The subtitle alpha and
  actionbar shadow now stay continuous through the boundary.
- Updated `ThingsActivity` staggered-grid callers to pass the minimum visible
  adapter position across all spans to `ActivityHeader`, rather than only
  `positions[0]`, so the first-visible decision is stable at span boundaries.
- Added a compact collapsed scale for Folder names that require two actionbar
  lines. The scale animates continuously with the same header-collapse progress
  and the vertical centering calculation uses the two-line collapsed visual
  height immediately.

Verification: source search confirmed the legacy `scrollY >= 102dp -> 0`
reset and `positions[0]` header update paths are gone. `git diff --check`
passed with only the repository's existing LF/CRLF warnings.
`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
completed with `BUILD SUCCESSFUL`. Published debug update `202606200248` and
verified remote `latest.json` points at that code. Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200248.apk`.
Remote SHA-256:
`8c967fa38d199131fec7b5129e0248c0101cc2c0039abc1c0cedb5ac7d4ebcaf`.

## 2026-06-20 - Fix in-Folder header spacer crash during scroll

- Diagnosed a production crash log from OnePlus PLZ110 / Android 16:
  `RecyclerView` threw `IllegalArgumentException: Called attach on a child
  which is not detached` while `StaggeredGridLayoutManager.fill(...)` was
  handling a touch-driven scroll in `rv_things`.
- Root cause: the previous long-Folder-title spacer fix let `ActivityHeader`
  emit spacer height changes from header layout changes. During scrolling,
  changing the title width/line cap could remeasure the header and trigger
  `notifyItemChanged(0)` for the invisible RecyclerView spacer while the layout
  manager was still attaching children.
- Removed the scroll/layout-change path from spacer updates. The spacer is now
  refreshed only from explicit expanded-header refresh points such as
  `updateText()` and `reset(...)`.
- Added an Activity-side guard that keeps only the latest spacer height request
  and applies it to the adapter only after the RecyclerView is idle and not
  computing layout.
- Improved collapsed Folder-title centering by recalculating the collapsed
  header translation from the current visible title layout, including the
  two-line collapsed height cap.

Verification: the crash log was mapped to the RecyclerView attach path and the
source search confirmed `updateHeaderSpacerHeight()` is no longer reachable
from the scroll update path. `git diff --check` passed with only the
repository's existing LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug
--console=plain --no-configuration-cache` completed with `BUILD SUCCESSFUL`.
No automated UI regression seam exists for the user's exact device/data state
in this workspace; only a physical device was connected, so no emulator smoke
test was run. Published debug update `202606200220` and verified remote
`latest.json` points at that code. Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200220.apk`.
Remote SHA-256:
`47d0066c617659570aa5a86e1dcaa0951c20240098850a71d575b713d2dacc9f`.

## 2026-06-20 - Folder header and disabled move-target visual state follow-up

- Follow-up testing showed that forbidden source Folder subtrees in the
  move-to-Folder dialog were logically disabled but still looked active. Dialog
  binding now leaves disabled rows expandable while applying the App Chrome
  disabled foreground to their Folder icon and title text.
- Changed the in-Folder Activity header from a clickable full path to the
  current Folder name only. It now keeps the same plain style as the root
  Underway header and the subtitle reports direct child counts split as
  folders and things.
- Added dynamic in-Folder header width and line constraints for long Folder
  names. Expanded headers stay inset before the card edge, collapsed headers
  stay before toolbar actions, and the invisible RecyclerView header spacer is
  refreshed from the measured header height so wrapped names do not overlap the
  first visible card.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Published debug
update `202606200208` and verified remote `latest.json` points at that code.
Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200208.apk`.
Remote SHA-256:
`ab98531bd6fb30a47c054d67916d73fafccac2d4f604729ec9f9c3e9f689f503`.

## 2026-06-20 - Show forbidden Folder move targets and reset private expansion auth

- Reversed the previous move-dialog hiding rule after follow-up testing: when
  moving a Folder, the source Folder and every descendant now remain visible in
  the target tree. They can be expanded to inspect the hierarchy, but are
  disabled and cannot be selected as move targets.
- Stabilized the move-to-Folder dialog layout by reserving divider and action
  row spacing instead of changing margins and `GONE` divider visibility when
  expansion makes the tree scrollable.
- Drawer private Folders with child Folders now keep their trailing expand
  affordance visible while the subtree is hidden. Expanding requires
  authentication unless the current projection is already inside an
  authenticated private Folder path.
- Drawer and move-dialog private expansion authentication is now transient to
  the active surface. Closing the Drawer or dismissing the dialog resets local
  expansion authorization; Drawer close also collapses private subtrees outside
  the current private path.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Published debug
update `202606191645` and verified remote `latest.json` points at that code.

## 2026-06-20 - Hide forbidden Folder move targets and add scroll dividers

- Follow-up testing showed that the move-to-Folder dialog still rendered the
  source Folder as a disabled row in some Folder trees. The dialog now omits
  forbidden Folder ids from the tree entirely, which removes the source Folder
  and its forbidden subtree from possible targets rather than presenting them
  as disabled rows.
- Folder rows now compute expand affordance visibility from visible,
  non-forbidden children, so the root row or another parent does not show an
  expand button when all of its children were excluded by the move guard.
- Added top and bottom dividers to the move-to-Folder dialog's RecyclerView
  region. They appear only when the target tree is scrollable and follow the
  existing App Chrome dialog boundary rule: the divider at the current scroll
  edge is hidden while the opposite edge remains visible.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Published debug
update `202606191616` and verified remote `latest.json` points at that code.
Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606191616.apk`.
Remote SHA-256:
`6bd93aa54271df75f57f2c9fc58f0b97381bd9acec24798b365565e7bfd9edf2`.

## 2026-06-19 - Restore Folder move entry points

- Follow-up testing showed that Folder moves were still not reachable from the
  two expected entry points even though the move DialogFragment and manager
  operation existed.
- Fixed the selected-card contextual toolbar visibility rule so `Move to
  Folder` remains visible for a single selected non-deleted Folder, while
  still hiding it for mixed or multi-Folder selections.
- Refreshed the Activity options menu immediately after opening a Folder from a
  Folder Card. The current-Folder overflow menu therefore picks up the new
  in-Folder state and shows the current Folder's move action without requiring
  an app restart or navigation refresh.
- Adjusted Folder move privacy checks so moving a private Folder that is
  already open does not ask for authentication again for the source Folder.
  Moving into another private target still authenticates unless that target
  path has already been authenticated.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Published debug
update `202606191559` and verified remote `latest.json` points at that code.
Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606191559.apk`.
Remote SHA-256:
`51009f16b4d4112e13b03d9ccd3d53c1ec03740d22879df3a31f55d843d8ecd8`.

## 2026-06-19 - Keep overlay drag enlarged while source holder stays normal

- Corrected the overlay drag visual model after the user pointed out that
  `View.draw(Canvas)` captures normal view content rather than the View's
  transformed Moving-mode scale. The overlay now applies the drag lift scale
  (`1.11f`) to the bitmap `ImageView` itself, so the moving card remains
  enlarged during drag.
- Adjusted the overlay start frame and finger offset to use the enlarged
  visible overlay bounds. Folder-drop top-left hit testing therefore continues
  to use the visible dragged card's top-left corner, not the unscaled bitmap
  frame.
- Once the bitmap has been captured, the real source holder now clears its
  moving-scale recovery token, cancels any scale animation, resets to
  `scaleX=1f` / `scaleY=1f`, and remains transparent as a normal-size layout
  placeholder. This prevents a second ViewHolder shrink animation from
  appearing when the holder is finally revealed.
- Reorder settle target resolution now waits for RecyclerView item animations
  and a post-animation draw before resolving the final source holder frame.
  This avoids computing the overlay's final target from a stale transparent
  source placeholder that RecyclerView still has at the old list position
  before relayout.
- Tightened successful reorder reveal ordering so the final source ViewHolder
  is restored only after the overlay movement animation has ended and the
  overlay view has been removed from the overlay parent. This avoids showing
  the real card under the final overlay frame.
- After follow-up device feedback that the ViewHolder could still appear early,
  identified the remaining gap as adapter rebinds during `notifyItemMoved(...)`
  or related updates resetting `holder.itemView.alpha` to `1f`. Added a
  RecyclerView pre-draw guard for the active overlay session so the source
  holder is re-applied as a transparent normal-size placeholder immediately
  before each frame is drawn, until the overlay is removed and the deliberate
  reveal runs.
- After the user still observed early ViewHolder reveal and suspected
  `notifyDataSetChanged()`-style rebinding, hardened the guard against
  RecyclerView pre-layout. `ThingsAdapter` now tags each bound card root with
  its Thing/Folder stable business id, and the controller scans visible
  children by that tag instead of relying only on the source's current list
  position. Placeholder application now also resets the inner `cv_thing`
  CardView scale and recovery token, matching the layer that Moving mode
  actually enlarges.
- Confirmed that Moving mode already raises selected normal Thing/Folder Cards
  to `thing_card_dragging_elevation` while scaling them to `1.11f`. The overlay
  snapshot view now uses that same 12dp elevation and the card corner outline,
  so the moving overlay renders as the enlarged elevated card rather than a
  plain rectangular bitmap surface.
- Strengthened successful reorder settle timing after the user reproduced the
  old-slot placeholder problem. The overlay no longer starts its final movement
  as soon as a holder can be found. It waits until RecyclerView has no pending
  adapter updates, is not computing layout, item animations are no longer
  running, the source holder at the final adapter position is bound to the
  source stable id, and its layout rect is stable across consecutive frames.
- Reviewed the user's follow-up video and identified a more specific cause of
  the remaining old-slot behavior: the pre-draw placeholder guard was calling
  `itemView.animate().cancel()` every frame. That cancelled RecyclerView's own
  root ViewPropertyAnimator for moving the transparent source holder, so the
  holder could become stable at the old slot. Placeholder enforcement now keeps
  the root item animation intact and only cancels the inner card view's
  app-owned Moving-mode scale recovery animation.
- The user then rejected the full-refresh workaround because it removed
  RecyclerView's own re-layout animation and could disturb scroll position.
  Restored `notifyItemMoved(...)` for overlay reorder commits, then made the
  overlay wait more patiently before settling: it must allow at least the item
  move duration plus a small grace window for the item animator to start, wait
  for any observed item animator to stop, wait an additional short
  post-animation grace window, require idle scrolling, require no pending
  adapter updates, and require the final source rect to be stable across
  multiple frames.
- After the user reported the same screenshot sequence still reproducing,
  reviewed AndroidX `StaggeredGridLayoutManager` and `RecyclerView` source.
  The important finding is that SGLM keeps a lazy span lookup, `notifyItemMoved`
  only invalidates the affected range, and later gap correction or full-list
  rebinds can clear/rebuild span assignments after a holder rect has already
  looked stable. The first follow-up attempted a two-phase wait, but that made
  the overlay feel late. The revised approach now requests simple animations
  for the next layout and invalidates StaggeredGrid span assignments
  immediately after the successful `notifyItemMoved(...)` commit, before
  RecyclerView consumes the adapter update. The overlay waits only until that
  synchronized final layout rect is available, then starts on the next
  animation frame using RecyclerView's move duration so it runs with the final
  arrangement animation instead of after it.
- Updated successful drops into existing Thing Folders so the session overlay
  no longer flies into the target Folder Card. For `MOVE_TO_FOLDER` commits,
  the overlay now keeps its release position, pivots at its own top-left
  corner, and shrinks directly to `scaleX/scaleY=0`. Create-Folder drops still
  keep their merge-into-target animation.
- Diagnosed the missing remaining-card rearrangement animation on Folder drops.
  The commit path was already using `notifyItemRemoved(...)` plus a targeted
  Folder-card `notifyItemChanged(...)`; the animation was vulnerable because
  the later Moving-mode-exit full-list rebind could run before RecyclerView had
  consumed pending adapter updates, completed layout, or finished
  `DefaultItemAnimator` move animations. `runWhenThingListCanUpdate(...)` now
  checks pending adapter updates and requested layout, and Folder-drop
  mode-exit rebind waits for `ItemAnimatorFinishedListener` before calling
  `notifyDataSetChanged()`.
- Investigated the follow-up case where dropping A above bottom-left card B
  made B animate downward first, then jump to A's right side after the overlay
  finished. AndroidX source review showed that SGLM supports predictive item
  animations by default, while `checkForGaps()` can later clear lazy spans and
  request another simple-animation layout. With a non-top visible anchor near
  the bottom of the list, that means the first `notifyItemMoved(...)` animation
  can be based on an intermediate same-span post rect, and the final span
  correction arrives later.
- Added a one-shot overlay-reorder preparation path to
  `ThingsStaggeredLayoutManager`: the next layout after an overlay reorder
  suppresses predictive item animations, requests simple animations, and
  invalidates span assignments. This keeps RecyclerView move animations for the
  surrounding cards, but makes their post-layout rects come from the final
  non-predictive SGLM span assignment. Overlay reorder's delayed mode-exit
  full-list rebind now also waits for `ItemAnimatorFinishedListener`.
- Matched Folder Card long-press behavior to Thing Cards while selecting mode
  is active. The Folder branch now exits selecting mode through
  `ModeManager.backNormalMode(listPosition)` instead of swallowing the long
  press.
- Reworked thumbnail-mode Folder drag overlays again after source review of
  Android shadow handling. The custom stroke/gradient shadow approximations
  were removed; `DragOverlayImageView` now uses platform elevation again, with
  an expanded view bounds and an inset rounded `Outline` matching the real
  card content rect.
- Covered the transparent-interior shadow without self-drawing the outer
  shadow: before drawing the captured transparent bitmap, the overlay draws a
  rounded `bg_activity_things` fill inside the content rect. The platform still
  draws the real outer elevation shadow, while the interior shadow is hidden by
  the same background the thumbnail Folder Card normally reveals.
- Confirmed the list's thumbnail-mode Folder Cards had also been suppressing
  elevation by setting `cardElevation` and `maxCardElevation` to `0f`. Restored
  their normal and dragging elevation to match ordinary Thing Cards, including
  Moving-mode and touch elevation animations. Their outer CardView now uses
  `bg_activity_things` as an opaque rounded fill under the outlined content, so
  the platform shadow is still hidden inside the outline while remaining
  visible outside it.
- Kept the very tall Folder overlay fix from the previous pass. If the
  captured bitmap is larger than the safe tile size, `DragOverlayImageView`
  splits it into 1024px tiles and draws those tiles into the content rect
  instead of asking ImageView to upload one oversized texture.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:compileDebugKotlin --console=plain
--no-configuration-cache` and `.\gradlew.bat :app:assembleDebug
--console=plain --no-configuration-cache` completed with `BUILD SUCCESSFUL`;
the only compiler warnings were the existing deprecated `adapterPosition`
warnings in `ThingListOverlayDragController.kt`. Published debug update
`202606191435` to the Aliyun debug channel and verified remote `latest.json`
points at that code. Remote APK:
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606191435.apk`.
Remote SHA-256:
`f0048764ef1105d325e9671721342badb5366ef12d5b0744c0d7688a298a8c69`.

## 2026-06-19 - Align overlay release animation to layout-space card frames

- Diagnosed the remaining overlay reorder / release-in-place landing offset as
  a coordinate-frame mismatch. The controller was using
  `View.getLocationOnScreen()` for the target top-left while pairing it with
  raw `width`/`height`; during Moving-mode, the source holder can still carry
  transient `scaleX`/`scaleY`, so that produced a transformed top-left with
  untransformed right/bottom.
- Updated `ThingListOverlayDragController` so overlay start, folder-drop target
  centers, reorder settle targets, and release-in-place return targets use a
  shared layout-space rect helper. The helper walks view `left`/`top` up to the
  overlay root, ignores scale, and lets callers choose whether transient
  translations should be included.
- Reorder and release-in-place now target the final holder's untransformed
  layout frame, preserving top-left to top-left and bottom-right to
  bottom-right alignment when the real ViewHolder is revealed.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Publishing was
attempted in-sandbox but timed out at `:app:publishDebugUpdate`; the elevated
rerun was rejected by the safety reviewer because this turn did not explicitly
authorize remote APK upload and metadata updates, so publishing was not
confirmed.

## 2026-06-19 - Separate reorder landing target from RecyclerView relayout timing

- Reviewed the user's slow-motion reorder video from the 3-second mark and
  separated two effects: the overlay landing target could be offset, while the
  RecyclerView rearrangement/full-list rebind could also reveal the real moved
  card during the deliberately prolonged overlay test animation.
- Removed the temporary prolonged reorder settle duration and returned the
  successful reorder overlay flight to the RecyclerView item move duration.
- Changed the successful reorder and release-in-place target calculation to use
  the final source holder `itemView` layout rectangle as the outer bitmap-frame
  target. The controller no longer divides the overlay frame by the captured
  long-press card scale, because that scale is already baked into the lift-time
  bitmap contents and should not shift the frame's final `x`/`y`.
- Changed successful reorder cleanup so source placeholder reveal waits until
  both the overlay flight and RecyclerView item animations have finished. This
  removes the earlier timing experiment where the real holder could be restored
  while the overlay was still visibly moving.
- Deferred the Moving-mode exit full-list rebind while an overlay drag session
  is still active. If the delayed rebind fires before the overlay has ended, it
  is recorded as pending and runs only after `onOverlayDragActiveChanged(false)`.

Verification: `git diff --check` passed with only the repository's existing
LF/CRLF warnings. The in-sandbox
`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
run was blocked by the usual Kotlin daemon `AccessDeniedException` under the
user profile; the same local assemble task succeeded with elevated
permissions. Debug update publishing remains owned by the user.

## 2026-06-19 - Polish reorder release jitter and insertion line placement

- Revisited the overlay reorder release animation after device feedback that
  the card could jitter at the final reveal. The likely cause was a mismatch
  between the enlarged Moving-mode snapshot and the real holder being restored
  at normal scale, plus the possibility that RecyclerView's item move animator
  had not fully finished when the holder was revealed.
- Stored the card scale captured at overlay start and made the successful
  reorder settle animation shrink the overlay back toward the normal card scale
  while moving to the final source layout slot.
- Delayed source-holder reveal until RecyclerView's item animator reports that
  its animations have finished, keeping the overlay visible at the final target
  if RecyclerView needs an extra frame to finish its own translation cleanup.
- Changed the reorder insertion line from a geometric midpoint between visible
  neighbours to a small fixed offset from the target card edge, clamped only
  when the local visible gap is too small. Large masonry gaps from tall Things
  or Folders no longer pull the line far away from the final target card.
- Follow-up feedback clarified that returning to the original position should
  still play a spatial return animation. The no-op reorder / release-in-place
  path now animates the overlay back to the source layout slot before entering
  selecting mode instead of fading the overlay out.
- Tightened successful reorder reveal timing after the user pointed out that
  restoring the real holder alpha too early could explain the final jitter.
  The controller now waits for RecyclerView item animations and one additional
  draw, then restores only the holder resolved by the source stable id, avoiding
  the stale drag-start `sourceView` reference on successful moves.
- Increased the insertion line thickness from 6px to 12px so the source-colour
  line reads clearly during drag.
- Follow-up feedback clarified that the insertion line thickness should be
  density-independent rather than raw pixels. The line is now 4dp, which maps
  to the previous intended thicker visual weight on high-density devices while
  staying consistent across densities.
- Added a temporary timing experiment for the remaining final-frame jitter:
  successful reorder overlay flight now lasts 420ms, while source-holder alpha
  restoration still follows the current RecyclerView item-animation completion
  timing. This intentionally separates the overlay movement from the holder
  reveal so device testing can identify which moment causes the jitter.

Verification: in-sandbox `:app:assembleDebug` was blocked by the same Kotlin
daemon `AccessDeniedException` under the user profile. The same
`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
task succeeded with elevated permissions. Debug update publishing was left to
the user.

## 2026-06-18 - Synchronize overlay reorder settle with RecyclerView move

- Reworked the successful overlay reorder release path after device feedback
  that removing the overlay before `notifyItemMoved(...)` avoided flicker but
  made the dragged card landing animation feel disconnected.
- Kept the session overlay visible after release, committed the data move and
  `notifyItemMoved(...)`, then waited for the next RecyclerView pre-draw before
  resolving the moved source holder's final layout rectangle.
- Animated the overlay from the release position to that final source layout
  slot using the RecyclerView item move duration, so the surrounding cards'
  gap-making animation and the dragged card's settle animation share timing.
- Kept the real source holder transparent during the RecyclerView move
  animation, then restored it after the overlay settle and reset transient
  drag scale/tags before reveal.

Verification: in-sandbox `:app:assembleDebug` was blocked by a Kotlin daemon
`AccessDeniedException` under the user profile. The same
`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
task succeeded with elevated permissions. Debug update publishing was left to
the user.

## 2026-06-18 - Hide IME when closing Folder Card appearance panel

- Rechecked the user's intended UI and corrected the target: the problematic
  input is `et_folder_card_appearance_name` inside
  `panel_thing_card_appearance.xml`, not the Folder naming `DialogFragment`.
- Confirmed the Folder Card appearance UI is an in-Activity bottom panel
  included in `activity_things.xml`. The name input is a standard XML
  `<EditText>` inflated into `android.widget.EditText`; it is not a custom
  project input widget.
- Added IME hiding to `hideThingCardAppearancePanel()` before the panel is set
  to `GONE`, using the existing AndroidX Compat `KeyboardUtil.hideKeyboard(...)`
  path with the Activity window and current focus, falling back to the Folder
  name input.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. The only compiler
warning was the existing deprecated override warning in `ThingsActivity.kt`.
Debug update publishing was left to the user.

## 2026-06-18 - Use WindowInsets for Folder naming dialog IME control

- Reverted the previous delayed-dismiss Folder naming dialog keyboard attempt:
  removed the planned close state, delayed close runnable, repeated
  `InputMethodManager` hide calls, transient root focus handling, and related
  preference notes.
- Researched the current Android guidance and switched the project keyboard
  helper away from `InputMethodManager` for show/hide. `KeyboardUtil` now uses
  `WindowCompat.getInsetsController(window, view).show/hide(WindowInsetsCompat.Type.ime())`
  directly, resolving the `Activity` window from a plain `View` when callers do
  not pass a `Window`.
- Updated `ThingFolderNameDialogFragment` so the dialog no longer uses
  `SOFT_INPUT_STATE_ALWAYS_VISIBLE`. It shows the IME from the dialog window
  after start, and hides the IME from the same dialog window before cancel,
  confirm, and `onDismiss(...)` paths continue closing the dialog.
- Compared this with `DateTimeDialogFragment`: the reminder dialog was stable
  because it moves focus to a still-attached non-input view before hiding the
  IME, but the Folder naming dialog is a single-input dialog that benefits more
  from window-level IME control than from focus juggling or delayed dismissal.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. The only compiler
warning was the existing deprecated override warning in `ThingsActivity.kt`.
Debug update publishing was left to the user.

## 2026-06-18 - Delay Folder naming dialog close until after IME hide

- Reviewed the user's hypothesis about the Folder naming dialog keeping the
  keyboard visible. The analysis matched the important distinction from
  `DateTimeDialogFragment`: the reminder dialog hides the IME before dismissal
  from stable non-input views during normal interaction, while the Folder naming
  dialog was hiding from the `EditText` and then dismissing the dialog in the
  same frame.
- Removed the transient focus workaround from `fragment_thing_folder_name.xml`.
  The dialog root is no longer made focusable just to receive focus during
  close, avoiding rapid `EditText -> root -> no focus` changes in the same
  frame.
- Changed the Folder naming dialog's confirm and cancel buttons to enter a
  planned close state, request IME hide through the input/current focus and
  dialog window, then run the confirm/cancel callback and dismiss after
  `KeyboardUtil.HIDE_DELAY`. `onDismiss(...)` now only delivers the cancel
  callback on unplanned dismissals such as back/outside cancellation.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was left to the user.

## 2026-06-18 - Fix Folder naming IME hide and post-drop dimmed cards

- Rechecked the Folder naming dialog against `DateTimeDialogFragment`, which
  moves focus out of the active input before hiding the keyboard and also hides
  from dismissal paths. The previous Folder naming fix only hid through the
  `EditText` or Activity current focus, while `KeyboardUtil.hideKeyboard(window)`
  merely set `SOFT_INPUT_STATE_ALWAYS_HIDDEN` without calling
  `InputMethodManager.hideSoftInputFromWindow(...)` on the dialog window token.
- Updated `KeyboardUtil.hideKeyboard(window)` to hide the IME through the
  dialog decor/focused view window token and clear focus. The Folder naming
  dialog root is now focusable, and `hideNameKeyboard(...)` hides through the
  input/current focus, requests focus on the content root, hides through the
  content view, and finally hides through the dialog window.
- Diagnosed the dimmed-card regression after dropping a Thing into a Folder:
  the successful Folder-drop path intentionally exited Moving mode with
  `finishMovingModeWithoutListRefresh()` to preserve targeted removal and merge
  animations, but visible cards that had already been rebound in Moving mode
  stayed in their unselected dimmed colours because no later normal-mode rebind
  occurred.
- Added a post-drop mode-exit rebind after the Folder-drop merge animation (or
  immediately for no-visual/failed paths). The rebind disables list-appearing
  animation and only refreshes card binding once RecyclerView is safe, restoring
  all visible cards to normal colours without disrupting the targeted drop
  animation.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was left to the user.

## 2026-06-18 - Correct private Folder drop, lock spacing, and IME follow-up

- Rechecked the hidden-private Folder Card lock spacing after the user rejected
  the earlier 44dp-only explanation. The actual remaining problem was that
  `resetFolderCardHolder(...)` made the ordinary content TextView visible for
  Folder Cards, and the hidden-private Folder path did not hide it again before
  showing the lock. That recycled content/status state could still participate
  in the private Folder Card measurement below the lock.
- Updated hidden-private Folder binding to hide the ordinary content/status
  surfaces, reset Folder bottom spacing to the same 16dp spacer used by
  hidden-private Thing Cards, and explicitly reset the private lock ImageView to
  the normal 48dp size so recycled full-span private Thing holders cannot leave
  a larger lock.
- Confirmed the reference values: `card_thing.xml` declares the private lock
  ImageView as 48dp with a 16dp top margin and the bottom padding spacer as
  16dp. `BaseThingsAdapter.applyCardContentGeometry(...)` resets ordinary
  hidden-private Thing Cards to those values; the mdpi lock bitmap is 48x48 and
  has 4px transparent space below the visible glyph.
- Allowed Thing Cards and Folder Cards to be dropped onto private Folder Cards.
  The drop eligibility no longer treats the target Folder's effective privacy
  as a reason to block moving into that Folder; privacy authentication still
  applies when opening or viewing the Folder's contents.
- Made the Drawer private Folder lock smaller again while keeping it centered
  inside the Folder glyph.
- Fixed the shared `KeyboardUtil.hideKeyboard(view)` path used by the Folder
  naming dialog so it hides the IME with the current window token before
  clearing focus, instead of clearing focus first and then sometimes deciding
  the input method is no longer active.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was left to the user.

## 2026-06-18 - Follow up Folder drop, private lock, and naming IME polish

- Extended existing Folder-drop activation feedback from thumbnail-mode Folder
  Cards to summary-mode Folder Cards. Summary Folder targets now animate both
  their card background and content alpha back to the normal active state while
  the drag is over them, then restore the dimmed selecting/moving appearance
  when the drag leaves.
- Corrected the Drawer private Folder lock drawing. The lock is now smaller and
  centered inside the Folder glyph rather than drawn as a large lower-right
  overlay.
- Updated the Folder naming dialog so cancel, confirm, and dismiss hide the
  soft keyboard before the Activity continues with rename/create/cancel
  callbacks.
- Re-diagnosed the hidden-private Folder Card lock spacing after the user
  clarified that only the bottom space was wrong. The lock ImageView top margin
  was left alone; the Folder-card holder reset now clears recycled bottom
  status spacer height/weight and restores the ordinary 16dp bottom padding,
  preventing media-count padding from leaking into private Folder cards.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was prepared with `memory/debug-update-notes.md`.

## 2026-06-18 - Polish private folders, Drawer privacy, and drop cancel restore

- Fixed selecting/moving-mode visuals for summary-mode Folder Cards. Unselected
  summary Folder Cards now wash out their card background and content alpha in
  the same state where unselected Thing Cards are dimmed.
- Fixed thumbnail-mode Folder drop target feedback. While a dimmed thumbnail
  Folder Card is the active drop target, its content alpha animates back to the
  normal value in sync with the existing target scale/outline timing; leaving
  the target restores the dimmed value, and committing the drop keeps the target
  normal-coloured during the merge animation.
- Kept Drawer selection stable after creating a Thing from inside a Folder
  projection. The Drawer now re-checks the current projection instead of
  switching to the Underway root after create-return.
- Tightened private Folder presentation rules. Private Folders now use the
  default normal-span summary presentation as their effective presentation in
  adapters, DAO thumbnail prefetch, manager updates, and privacy toggling. The
  Folder Card appearance panel exposes only the rename field for private
  Folders, with an accent underline on the editable name field, and action
  menus no longer offer size/mode toggles for private Folders.
- Matched hidden-private Folder Card lock spacing to hidden-private Thing Card
  lock spacing.
- Updated the custom Drawer Folder row model with a private-folder flag. Private
  Folder icons now draw a small adaptive lock over the Folder glyph. Private
  Folder descendants and expand/collapse affordances are hidden unless the
  current Folder projection is inside that private Folder scope.
- Fixed canceling the name dialog after a Thing-to-Thing Folder drop. The two
  source Things now restore their pre-creation parent Folder ids and locations
  instead of being reinserted at the start of the parent list.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`.
`.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md"
--console=plain --no-configuration-cache` was attempted but timed out after
120 seconds at `:app:publishDebugUpdate`; an elevated rerun was not approved by
the safety reviewer, so publishing was not confirmed.

## 2026-06-18 - Harden drag clear during scroll/layout detach

- Analyzed the user's `thing_card_scale_recovery(3).log` from `11:45:07`.
  Several long drags over large Thing/Folder cards showed `clearView(...)`
  running while the Activity-level pointer was still down. This means
  `ItemTouchHelper` ended the active drag because the dragged child was
  detached during RecyclerView scroll/layout, not because the user released
  the drag.
- Analyzed `crash_20260618114415.log`. The crash was
  `IllegalStateException: Cannot call this method while RecyclerView is
  computing a layout or scrolling`, thrown by `notifyItemRemoved(...)` from
  `notifyFolderDropCommitted(...)` while `clearView(...)` was being called via
  `ItemTouchHelper.onChildViewDetachedFromWindow(...)` during RecyclerView
  layout.
- Added an interrupted-drag branch in `ThingsTouchCallback.clearView(...)`.
  If `clearView(...)` happens while the Activity still has a pointer down, the
  current folder-drop hover is canceled instead of committed, dragged-card
  scale animates back to normal when the view is still attached, and any
  in-memory reorder is moved back to the drag start position once RecyclerView
  is safe to update. Detached holders still get an immediate scale reset so a
  recycled view cannot keep a transient drag scale. The UI then enters
  selecting mode rather than pretending the drag was released.
- Added `runWhenThingListCanUpdate(...)` for RecyclerView-safe adapter updates.
  Normal folder-drop release still commits, but if RecyclerView is computing
  layout or still scrolling, the data mutation and adapter notifications are
  delayed until the list is idle.
- Extracted folder-drop commit handling into `commitFolderDropAfterClear(...)`
  so immediate and deferred commit paths share the same mutation/animation
  behavior. Deferred commits skip the merge overlay because the original child
  may already be detached.
- After the user confirmed the behavior looked acceptable on device, disabled
  the card-scale file diagnostics by setting `CARD_SCALE_RECOVERY_DEBUG` to
  `false`. The generic `DebugFileLogger` remains available for future probes,
  but this drag/scale path no longer writes `thing_card_scale_recovery.log`.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was left to the user.

## 2026-06-18 - Gate posted long-press drag on the active touch sequence

- Analyzed the user's follow-up device log after `11:32`. The previous fix did
  clear `fingerDown` on moving-mode `CANCEL`, but the delayed posted
  `ItemTouchHelper.startDrag(...)` still ran after the finger had already left
  the screen. `onSelectedChanged(...)` then marked the card as drag-active and
  restored `fingerDown=true`, so the delayed scale recovery still skipped.
- Adjusted the model: `dragActive=true` is not itself wrong, because the
  `ItemTouchHelper` drag state can remain able to move the card if the user
  touches it again. It just must not be treated as proof that the original
  finger is still down.
- Added Activity-level pointer tracking through `dispatchTouchEvent(...)`.
  `ThingsActivity` now records whether any pointer is down and increments a
  touch-sequence id on each new `ACTION_DOWN`.
- Replaced the long-press `post { startDrag(...) }` calls with
  `startLongPressDragIfTouchStillActive(...)`. The posted drag starts only if
  the same touch sequence is still active. If the finger has already left the
  screen, it skips `startDrag(...)`, clears the card's touch/drag tags, and
  converts the existing moving selection into selecting mode at the original
  list position.
- Updated real drag activation to copy the Activity-level pointer state into
  the card's finger tag instead of forcing it to true. The delayed scale
  recovery now shrinks whenever `fingerDown=false` and the card is still
  enlarged, even if `dragActive=true`.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was left to the user.

## 2026-06-18 - Distinguish canceled fast release from active drag

- Analyzed the user's device log from `11:25:02.972`. The failing sequence was:
  touch `DOWN` in normal mode, touch `CANCEL` after entering moving mode, then
  the delayed recovery check saw `fingerDown=true` and `stillEnlarged=true`, so
  it correctly skipped recovery according to the previous guard. No touch `UP`
  arrived for that card.
- The root cause was that moving-mode `ACTION_CANCEL` had been treated as
  "the finger is probably still down" to protect real drag startup. That was
  too broad: a quick release can also produce `CANCEL` without a live
  `ItemTouchHelper` drag, leaving the finger tag stuck true.
- Added a separate `tag_thing_card_drag_active` view tag. `ThingsTouchCallback`
  sets it when `ItemTouchHelper` enters `ACTION_STATE_DRAG` and clears it in
  `clearView(...)`.
- Updated the delayed scale recovery check to require both `fingerDown=false`
  and `dragActive=false` before shrinking. This preserves active dragging while
  allowing the fast-release cancel path to recover.
- Updated `ACTION_CANCEL` handling so it clears the finger tag unless the card
  is already marked as an active drag card.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`.

## 2026-06-18 - Add file diagnostics for card scale recovery

- Added targeted file diagnostics because the visual-only recovery for
  fast-release long press still did not take effect on the user's device.
- Introduced the generalized `DebugFileLogger`, which writes debug logs into
  the app-specific files directory under `debug_logs/`, appends entries on a
  background thread, rotates large log files, and can add a per-file session
  header. The card-scale probe is only its first caller: it writes to
  `thing_card_scale_recovery.log` with the unique
  `[DEBUG-card-scale-recovery]` prefix through an adapter-local wrapper.
- Instrumented the boundaries that distinguish the likely failure points:
  touch `DOWN` / `UP` / `CANCEL` / `OUTSIDE`, moving-mode enlarge scheduling,
  delayed recovery checks, stale-token or detached-view exits, actual recovery
  animation start, normal-geometry resets, Folder-card resets, and
  `ItemTouchHelper.clearView(...)`.
- Kept the instrumentation observational only. It does not change long-press
  dispatch, moving/selecting mode transitions, drag startup, or Folder-drop
  behavior.

Verification: `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was not run.

## 2026-06-18 - Recover fast-release card scale without blocking drag

- Reverted the previous pending long-press drag coordination attempt because it
  could block dragging Thing and Folder cards after long press.
- Kept the existing long-press mode and drag paths intact. Note cards still
  call `ItemTouchHelper.startDrag(...)` directly after entering moving mode,
  and Folder cards still rely on their existing moving-mode drag behavior.
- Implemented the safer visual-only recovery proposed by the user: when a
  Thing or Folder card starts its moving-mode enlarge animation, the adapter
  schedules a short delayed check after the enlarge animation should have
  completed. If the finger is no longer down on that card and the card is still
  scaled above normal size, the card plays the existing shrink-back animation.
- Tracked finger-down state through view tags instead of altering selection,
  moving mode, or drag state. `ACTION_CANCEL` during moving mode intentionally
  does not clear the finger-down tag, because a real drag can produce cancel
  events while the finger is still on screen. The tag is cleared from
  `ThingsTouchCallback.clearView(...)` when dragging finishes.
- Added dedicated view tag ids for the finger state and scheduled recovery
  token, so recycled card views can cancel stale delayed checks when their
  normal geometry is restored or a newer enlarge animation starts.

Verification: `git diff --check` passed apart from the repository's existing
LF/CRLF warnings; `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` completed with `BUILD SUCCESSFUL`. Debug update
publishing was not run.

## 2026-06-18 - Polish the custom Drawer tree controls

- Fixed the custom Drawer width calculation. `DrawerLayout` already constrains
  drawer children, so `DrawerNavigationView` no longer subtracts an additional
  right margin from the incoming measure spec. The preferred Drawer width is now
  320dp.
- Made Drawer row press feedback visible by using a bounded ripple mask on the
  row background while preserving a separate selected background colour.
- Kept built-in Drawer destination icons in their original asset colours for
  both normal and selected states instead of retinting selected icons to a
  dimmer app chrome colour.
- Added an explicit end margin to Folder title text when a trailing
  expand/collapse affordance exists. Combined with the measured trailing button
  width, this prevents the title from drawing beneath the affordance.
- Changed Folder rows to always reserve the same trailing expand/collapse slot,
  even for leaf Folders. Leaf rows hide the affordance but keep the slot width,
  so Folder title right edges align with rows that do show an affordance.
- Added an 8dp end margin to the expand/collapse slot so the affordance is not
  flush with the Drawer edge.
- Reduced the Folder expand/collapse touch target from 48dp to 40dp while
  preserving the 24dp icon visual size, making the circular ripple less
  oversized.
- Rendered built-in Drawer destination icons through
  `DisplayUtil.opaqueTintDrawable(...)` so the low-alpha PNG assets do not
  remain visually washed out after tinting. Static destination icons and Drawer
  item titles now share the dedicated `app_chrome_drawer_item_foreground`
  resource in light and dark mode. The trailing Folder expand/collapse icon
  uses the same foreground tier, while selected state uses background/bold
  weight instead of a stronger foreground colour. The current Drawer foreground
  value is `#B0000000` in light mode and `#B0FFFFFF` in dark mode.
- Added explicit group-start and group-end spacing to `DrawerNavigationView`
  rows. The Underway root plus visible Folder tree, Note/Reminder/Habit/Goal,
  Finished/Deleted, and Settings/Help/About groups now each get 8dp breathing
  room above the first row and below the last row.
- Added bottom inset handling to `DrawerNavigationView`; the final Drawer row
  now adds the current bottom system-bar/display-cutout inset to its bottom
  spacer so the last item clears the navigation area.
- Changed Folder Card recursive count text from the secondary text tier to the
  tertiary hint tier used by ordinary Thing Card audio and hidden media count
  labels, preserving light/dark foreground adaptation.
- Passed the toggled Folder id from `ThingsActivity` to `DrawerNavigationView`
  so the trailing icon can animate in the correct direction: clockwise on
  expand and counter-clockwise on collapse.
- Added a custom Drawer tree item animator so inserted Folder rows fade/slide
  downward from above and removed rows fade/slide upward during collapse.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.

## 2026-06-18 - Replace the home Drawer NavigationView

- Added `DrawerNavigationView`, an app-owned vertical Drawer container that
  keeps the existing `drawer_header` and renders Drawer rows through a
  RecyclerView adapter with typed `Destination` and `Folder` keys.
- Replaced the home `NavigationView` in `activity_things.xml` with
  `DrawerNavigationView`. The app still uses the existing `DrawerLayout`,
  `DrawerHeader`, and toolbar drawer toggle.
- Moved Drawer Folder row rendering out of Android menu/action-view APIs.
  Folder rows now have explicit 48dp height, 16dp hierarchy indentation from
  the Folder icon, a fixed 48dp trailing expand/collapse target only when child
  Folders exist, and single-line ellipsized titles that cannot draw beneath the
  trailing affordance.
- The custom Drawer adapter uses stable row keys and `DiffUtil`, so expanding
  or collapsing a Folder subtree animates row insertions/removals without
  relying on Material `NavigationView` presenter reuse.
- Updated `ThingsActivity` so Drawer state is tracked as
  `DrawerNavigationView.ItemKey` rather than `MenuItem`, while preserving the
  existing static destinations, Folder privacy authentication, and
  `openFolderPath(...)` navigation behavior.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.

## 2026-06-18 - Stabilize Drawer Folder expansion and indentation

- Increased Drawer Folder indentation to 16dp. The custom Folder icon drawable
  now treats indentation as extra leading width before a fixed 24dp icon, so
  the icon itself is not scaled down and the Folder title typography remains
  unchanged.
- Revised Drawer Folder indentation so the first Folder level's title aligns
  with the Underway root title, while the visible hierarchy offset starts at
  the Folder icon. Deeper Folder levels preserve the same icon-to-title gap.
- Constrained Drawer Folder titles before the trailing expand/collapse action
  area by giving expandable rows a fixed 48dp action view and keeping Drawer
  menu item text single-line with ellipsis.
- Added the existing app chrome circular ripple treatment to the trailing
  expand/collapse action view.
- Added a short transition/fade-slide animation when expanding or collapsing a
  Drawer Folder subtree so newly inserted rows do not simply flash into place.
- Fixed stale and cross-wired Drawer dropdown action views by assigning each
  Folder a stable dynamic `MenuItem` id based on its Folder id lifetime in the
  Activity, instead of deriving ids from the current visible row index.
- Leaf Folder rows now bind an explicit zero-size empty action view instead of
  `null`, preventing `NavigationView` from retaining a recycled dropdown icon
  on Folders that do not have child Folders.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.

## 2026-06-17 - Add the Underway Folder tree to the Drawer

- Superseded the earlier "Folders stay out of the Drawer" decision. The Drawer
  now shows the non-deleted Thing Folder tree under Underway and above Note,
  with the Note group separated from the Underway root and Folder tree.
- Split the static Drawer menu so Underway and dynamic Folder rows share one
  group, while Note/Reminder/Habit/Goal start in a separate group with the
  requested separator above Note.
- Added dynamic Drawer Folder rows in `ThingsActivity`. The Underway root is
  always expanded, so first-level Folders are always visible; deeper levels are
  shown only when their parent Folder's trailing dropdown action view is
  expanded.
- Corrected Drawer ordering by assigning explicit menu order values: dynamic
  Folder rows sit directly below Underway, while Note and later built-in items
  keep their separator below the Folder tree.
- Adjusted Folder indentation so even first-level Folders have a visible indent
  under the Underway root, and leaf Folders do not show a trailing dropdown.
- Increased Drawer Folder indentation to 16dp and changed the custom Folder
  icon drawable so indentation adds leading width before a fixed-size icon
  rather than shrinking the icon.
- Fixed stale/wrong dropdown actions by assigning a stable Drawer `MenuItem` id
  per Folder id instead of deriving item ids from visible row indexes. Leaf
  Folder rows now explicitly clear their action view so a recycled dropdown
  cannot remain attached.
- Drawer Folder rows open the Folder in the Underway projection through a full
  Folder path, so header path navigation still works for nested Folders.
  Effective private Folders reuse the existing private authentication flow.
- Kept Drawer selection single-item: built-in destinations, Underway root, or
  the current visible Folder row. If the current Folder is inside a collapsed
  subtree, the nearest visible ancestor is checked.
- Added a custom Drawer Folder icon drawable that renders the Folder shape with
  the Folder's own pure colour or gradient background, with modest hierarchy
  indentation beginning at the icon.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.

## 2026-06-17 - Move entries to target top and keep private Folder titles visible

- Updated Thing/Folder move semantics so moving a Thing into another Folder,
  moving selected Things to a Folder/root, moving a Folder to another
  Folder/root, and canceling a just-created Folder all assign the moved entry a
  fresh first-position location in the target container instead of preserving
  its old source-container order. Sticky entries keep sticky state and move to
  the first position within the target sticky section; non-sticky entries move
  to the first non-sticky position.
- Added mixed direct-child location queries in `ThingFolderDAO`, covering both
  direct child Things and direct child Folders. `ThingManager` now uses those
  queries when writing Thing `folderId + location` and Folder
  `parentFolderId + location` together.
- Added automatic cleanup for source Folders that become structurally empty
  after moving Things or child Folders out. Cleanup walks upward through empty
  ancestors and trims the active Folder projection if the user was viewing a
  Folder that was removed.
- Changed private Folder Card binding so hidden private Folder Cards keep the
  stored Folder title visible while still hiding thumbnail/contained previews
  and keeping the lock indicator.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.

## 2026-06-17 - Generalize the baked media crop path after Folder preview fix

- The previous Folder thumbnail foreground-video-only baked crop trial solved
  the reported top/bottom video cover issue, so the user requested replacing
  all remaining local `ImageView.imageMatrix` crop display paths with
  pre-cropped bitmaps.
- Removed the Folder-preview-specific
  `shouldBakeThingCardForegroundMediaCrop(...)` hook. `FolderThingPreviewAdapter`
  now uses the same baked Thing Card media path as normal cards instead of a
  special-case crop branch.
- Kept the targeted `[DEBUG-tf-video-crop]` logging hook for Folder preview
  top/bottom foreground video while this area is still under active testing,
  but the logged render path is now the general baked bitmap path.
- Folder thumbnail replay continues to call
  `applyThingCardMediaCropToBoundHolder(...)`; that method now compares the
  final measured target geometry and crop fingerprint against the current baked
  bitmap key and reloads/re-bakes if they differ.
- Verified as part of debug update `202606171256`.

## 2026-06-17 - Folder thumbnail foreground video baked crop trial

- After reviewing the device log for Thing `304` (`content="测试测试测试"`),
  narrowed the remaining failure away from Folder-preview target geometry. That
  Thing generated a `316x316` target, loaded a portrait `316x562` video-frame
  drawable, and applied the expected `ImageView.ScaleType.MATRIX` crop. If the
  visual output still appeared portrait, the failure was after or outside the
  matrix-based display path.
- Added a protected `BaseThingsAdapter.shouldBakeThingCardForegroundMediaCrop(...)`
  hook. Normal Thing Cards, side media, and media backgrounds keep the existing
  `ImageView.imageMatrix` path. `FolderThingPreviewAdapter` enables the hook
  only for child Thing previews whose selected foreground media is video, media
  background is disabled, and placement is top or bottom.
- When the hook is enabled, `loadThingCardImage(...)` now appends the crop
  fingerprint to the media cache/load key, converts the loaded video-frame
  drawable into a target-sized bitmap using the same crop-center,
  source-aspect-ratio, and user-scale calculation, sets that bitmap directly on
  the `ImageView`, and skips later matrix replay for that render request. This
  avoids relying on final `ImageView.imageMatrix` drawing state for the failing
  Folder thumbnail top/bottom video path while keeping the behavioral surface
  narrow for device testing.
- Verified with `git diff --check` and
  `.\gradlew.bat :app:assembleDebug --console=plain`.
- Published debug update `202606171217` to the Aliyun debug channel and
  verified remote `latest.json` points to
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606171217.apk`.

## 2026-06-17 - Folder thumbnail top/bottom video crop replay fix

- Added targeted diagnostic logging for the still-failing Folder thumbnail
  top/bottom foreground-video case. The logs use the unique
  `[DEBUG-tf-video-crop]` prefix and are enabled only for `FolderThingPreviewAdapter`
  child Thing previews whose selected media source is a video, media background
  is disabled, and image placement is top or bottom. Each log line includes the
  Thing id, title preview, content preview, media source key, media path, and
  placement so the failing child Thing can be identified from logcat.
- Instrumented the bind, foreground media load, Glide resource callback,
  post-load render request, crop replay, and final matrix application stages.
  The diagnostics record target width/height, thumbnail target aspect ratio,
  folder preview surface height, crop values, video frame timestamp, current
  view/layout sizes, drawable intrinsic size, cache/reuse path, and matrix
  scale/offset inputs. This should distinguish whether the remaining failure is
  caused by wrong generated geometry, video-frame drawable dimensions, skipped
  crop replay, or a later layout/scaleType overwrite.
- Published debug update `202606171203` to the Aliyun debug channel and
  verified remote `latest.json` points to
  `http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606171203.apk`.
- Reviewed logcat from the user's device for Thing `304`, whose content preview
  is `测试测试测试`. The bind path generated a `316x316` foreground thumbnail
  target for a bottom-placed video with target aspect ratio `0.99924934`, so
  the Folder-preview minimum-height guard is not the active cause for this
  Thing. Glide returned a portrait video-frame drawable (`316x562`), and the
  crop replay/final post-load path applied a matrix against the `316x316`
  target with source-aspect crop `0.99924934` and vertical offset `-62.455734`.
  If this Thing still visually appears as an uncropped portrait video in the
  Folder thumbnail, the remaining likely cause is that the applied
  `ImageView.ScaleType.MATRIX` state is later overwritten or bypassed by final
  drawing/layout state rather than the earlier target geometry calculation.
- Reverted the ineffective follow-up changes that tried to fix the top/bottom
  foreground-video case by adding a folder-thumbnail replay token, pre-draw
  replay scheduling, and a post-bind top/bottom media reload path. Device
  testing showed debug update `202606171003` still did not change the visible
  result, so that approach was removed before trying the next fix.
- Revised the diagnosis: Folder thumbnail child previews reuse the normal
  Thing Card top/bottom thumbnail height calculation, including the normal
  card's min/max height guardrails based on `surfaceAvailableHeight`. In a
  very narrow Folder preview column, the raw target height
  `imageW / thumbnailTargetAspectRatio` can be smaller than that minimum, so
  the minimum height wins and makes a default 4:3 or custom 1:1 thumbnail look
  much taller, close to a portrait video's intrinsic ratio.
- Added `BaseThingsAdapter.getThingCardForegroundThumbnailHeight(...)` as a
  protected hook. Normal Thing Cards keep the existing min/max guardrails, but
  `FolderThingPreviewAdapter` overrides the hook and returns the raw
  `imageW / getThingCardThumbnailTargetAspectRatio(thing)` height. This makes
  Folder thumbnail child cards generate top/bottom foreground media geometry
  directly from the thumbnail presentation ratio during binding, instead of
  trying to repair an already-bound media surface later.
- Published debug update `202606171146` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.
- After device testing still showed no visible effect from debug update
  `202606171003`, generated a Chinese PDF analysis report at
  `analysis/thing_folder_video_crop_flow_report.pdf`. The report documents the
  current video-frame Drawable generation path, crop parameter sources, crop
  matrix application, normal Thing Card media binding, Folder thumbnail preview
  binding/scale/replay flow, and the differences between top/bottom,
  left/right, and media-background media paths.
- Follow-up device testing showed debug update `202606170954` did not fix the
  top/bottom foreground-video thumbnail case. That version restored the target
  height during replay, but still used a single `post { ... }` replay timing and
  did not move foreground media to the same pre-draw/token pattern already used
  by media backgrounds.
- Left/right foreground media and media-background previews keep their existing
  geometry paths.
- Published debug update `202606170954` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.
- Published corrected debug update `202606171003` to the Aliyun debug channel
  and verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-17 - Thumbnail gap, crop ratio, and folder crash fixes

- Diagnosed two crash logs from device testing. Dragging inside a Folder could
  crash in `RecyclerView.onDraw(...)` because the temporary Folder-drop outline
  `ItemDecoration` was removed while RecyclerView was drawing its decoration
  list. Creating a Thing inside a Folder could crash in
  `ThingManager.deleteNEnow(...)` because folder projections may contain only
  the header row and no notify-empty row at index 1.
- Deferred Folder-drop outline decoration removal with `RecyclerView.post(...)`
  while clearing the adapter's active decoration reference immediately. This
  avoids mutating RecyclerView's decoration list during an active draw pass.
- Guarded `deleteNEnow(...)` so it only deletes the notify-empty row when the
  second `mThings` entry actually exists and is a notify-empty Thing.
- Split Folder thumbnail vertical spacing into a 12dp count-to-first-preview
  header gap and a 7dp child-preview item gap. Full-span masonry rows now own
  their first top gap, and first children inside columns do not add another top
  margin.
- Updated Thing Card Media crop application so thumbnail/side media uses
  `ThingCardThumbnailCrop.sourceAspectRatio`, and media-background previews
  use the saved media-background target aspect ratio. The final matrix now
  applies crop ratio, crop center, and user scale together for image and video
  previews.
- Verified with `git diff --check` and `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606170830` to the Aliyun debug channel with
  `memory/debug-update-notes.md` as the update note source.

## 2026-06-17 - Screenshot-driven thumbnail spacing and crop follow-up

- Reviewed a device screenshot of a thumbnail-mode Folder Card containing a
  mixed child Folder preview, short text/media previews, a full-span side-media
  preview, and a Habit media-background preview.
- Adjusted the child preview bottom spacer scale to match the general layout
  spacing scale. The previous 0.5-only bottom spacer scale overcorrected the
  earlier bottom-heavy cards and made Folder summary previews look top-heavy.
- Changed thumbnail media-surface protection so media container margins are
  still compacted while the actual media `ImageView`/mask is not scaled like an
  icon. This reduces the large gap between short text content and bottom media
  thumbnails without breaking edge-to-edge media drawing.
- Restored dynamic content text sizing for Folder thumbnail child previews by
  using the normal computed content size and clamping it to thumbnail-safe
  bounds. Short content such as a few Chinese characters can now render larger
  than long content inside thumbnails.
- Tightened media-background crop replay to prefer the current rendered media
  target size when available, so Habit media-background previews reapply crop
  against the final thumbnail geometry after compaction.
- Verified with `git diff --check` and `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Thumbnail bottom spacing and media crop replay

- Follow-up testing showed that Folder summary child previews and content-only
  Thing child previews still had too much bottom whitespace. The cause was a
  fixed-height `view_thing_padding_bottom` spacer that was not affected by the
  earlier padding/margin compaction pass.
- Added preview-only scaling for the Thing Card bottom padding spacer so
  `X things` count text and content-only text no longer keep a visibly larger
  bottom gap than top gap in Folder thumbnails.
- Follow-up testing also showed that Habit child previews with media, at least
  video media, could display the wrong crop after thumbnail compaction.
- Changed the bound-holder media crop reapply path so side media uses
  `ThingCardSideMediaCrop` instead of falling back to thumbnail crop.
- Folder thumbnail child previews now post a media-crop replay after the child
  card has been compacted and measured. This reapplies foreground, side-panel,
  or media-background crop against the preview's final target dimensions
  without rebinding the whole media-background card and undoing compact spacing.
- Verified with `git diff --check` and `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Thumbnail preview spacing, media cache, and shadow clipping

- Follow-up testing showed that child preview cards still kept too much of the
  ordinary Thing/Folder Card whitespace. The visible text and icons were
  smaller, but title/content/status padding and margins still consumed too much
  thumbnail space.
- Added preview-only layout spacing compaction in `ThingsAdapter`, applied
  after the child Thing or Folder card is fully bound. This scales internal
  padding and margins separately from text/icon scaling, so Folder headers,
  content, checklist, reminder, Habit, media-count, and audio rows get a
  tighter thumbnail layout without changing ordinary list cards.
- Preserved actual Thing Card Media surfaces during that scale pass. Side media
  panels and media backgrounds are no longer treated as generic `ImageView`
  icons, so left/right media remains edge-to-edge inside the child card.
- Added `BaseThingsAdapter.getThingCardHabitSummaryTextSize(...)` and set Habit
  summary text to the same preview base size as reminder time before the
  post-bind scale, avoiding a larger Habit summary in thumbnail previews.
- Added a protected Thing Card Media bitmap cache hook in `BaseThingsAdapter`
  and made Folder child preview adapters reuse the parent `ThingsAdapter`
  cache. Media-heavy child previews should now benefit from the existing LRU
  cache while scrolling, instead of spinning on every temporary child adapter.
- Disabled clipping on thumbnail preview containers so child preview elevation
  can draw outside column/list container bounds without reducing elevation
  further.
- Verified with `git diff --check` and `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Thumbnail preview card-wide text and icon scaling

- Follow-up testing showed that child preview cards only scaled their main
  content text. Titles, Folder icons, media/audio count labels and icons,
  reminder/habit/goal timing labels and icons, and the doing overlay still used
  ordinary list-card sizes inside thumbnail-mode Folder Cards.
- Kept the existing constrained full-card preview path, including content
  max-lines, checklist item limits, checklist read-only behavior, Habit summary
  simplification, media sizing, and child Folder summary-mode rendering.
- Added a post-bind preview-only scale pass in `ThingsAdapter` for child Thing
  and child Folder preview cards. The pass traverses the rendered view tree and
  scales `TextView` text, `TextView` compound drawables, and `ImageView` icons,
  so ordinary list cards remain unaffected.
- Tightened checklist preview text size and row icon scale through the nested
  checklist adapter so checklist rows stay compact even when their item views
  are created by the nested RecyclerView path.
- Verified with `git diff --check` and `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Nested Folder thumbnail previews and compact preview polish

- Added direct child Folder metadata to `ThingListEntry.FolderEntry`:
  `directFolderCount`, `thumbnailEntries`, and `thumbnailEntryCount`.
- Changed `ThingFolderDAO` thumbnail seed loading from recursive descendant
  Thing-only previews to direct child mixed entries. A thumbnail-mode Folder
  Card can now preview direct child Folders and direct child Things in their
  shared location order, capped by the existing normal/full-span limits.
- Child Folder previews render as summary-mode Folder Cards, regardless of the
  child Folder's own presentation mode. Tapping a child Folder preview opens
  that Folder through the same `openThingFolder(...)` path as ordinary Folder
  Cards.
- Updated Folder Card count text to combine direct child Folder count with the
  recursive matching Thing count, omitting zero-count segments.
- Reduced child preview card elevation to 2dp so shadows are less likely to be
  clipped by the existing compact preview spacing, and reduced the thumbnail
  ellipsis bottom margin to zero.
- Added English and Chinese string resources for mixed Folder/Thing count
  labels.
- Verified with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Thumbnail Folder Card preview layout polish

- Recorded the new thumbnail-mode Folder Card preview rules in
  `preferences.md`, `decisions.md`, and `plan.md`: normal-span Folder Cards use
  one preview column capped at three Things, full-span Folder Cards use a
  three-column masonry preview capped at six Things, and both show a compact
  bottom ellipsis when additional matching descendants are not rendered.
- Replaced the earlier title/content-only preview path in `ThingsAdapter` with
  a constrained `BaseThingsAdapter` preview path that inflates and binds the
  normal `card_thing` layout for each child Thing. Folder child previews now
  reuse Thing Card title handling, checklist rendering, image/video Thing Card
  Media rendering, media source/crop/frame selection, and full-span internal
  presentation.
- Added preview-specific Thing Card hooks for title text size, content line
  count, content text size, checklist item limit, checklist text size, and
  dense Habit detail visibility. Normal Thing Cards keep their existing
  behavior; Folder child previews use these hooks to stay compact without hard
  clipping the rendered card.
- Stripped nested interactions from child previews. Child cards only open the
  child Thing; checklist row toggles and long-press style card interactions are
  disabled inside the Folder Card preview surface.
- Added full-span-aware preview placement: full-span child Things span the full
  preview width inside a full-span Folder Card, while ordinary child Things use
  the three-column masonry distribution. Normal-span Folder Cards remain a
  one-column preview list.
- Added a shared `ThingFolderCardPresentation.effectiveThumbnailPreviewLimit()`
  so DAO thumbnail seed queries and UI binding use the same normal/full-span
  caps.
- Verified with `.\gradlew.bat :app:assembleDebug`, which completed
  successfully in the sandbox.
- Published debug update `202606170442` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.
- Follow-up testing showed ordinary Thing Card content text became oversized.
  The regression came from reading `TextView.textSize` after setting it: Android
  returns pixels, but assigning that value back through `textSize` treats it as
  sp. Changed the new preview hook to receive the computed default text size in
  sp before writing to the TextView, so ordinary Thing Cards keep their previous
  dynamic content text sizing while Folder child previews can still override it
  to 12sp.
- Re-verified with `.\gradlew.bat :app:assembleDebug`.
- Published fixed debug update `202606170448` to the Aliyun debug channel and
  verified remote `latest.json` points to the fixed APK.

## 2026-06-17 - Cross-entry position audit after mixed-list Detail fixes

- Audited `KEY_POSITION` usage across the home list, Detail, notification
  actions, full-screen notification dialogs, authentication actions, start/do
  and delay flows, widgets, image viewer, help pages, adapters, and
  `ThingManager` update APIs.
- Reconfirmed the intended split: `KEY_POSITION` remains the pure
  `ThingManager.getThings()` index used by manager update APIs, while
  `KEY_LIST_POSITION` carries the mixed Thing/Folder RecyclerView adapter
  position for targeted home-list notifications.
- Found that remote notification/widget actions could mutate `ThingManager`
  before broadcasting back to `ThingsActivity`, while only sending the pure
  Thing index. After mutation, a mixed-list fallback lookup could no longer
  recover the old adapter position reliably.
- Added pre-mutation `KEY_LIST_POSITION` capture in `RemoteActionHelper` so
  remote finish/type-correction broadcasts can remove or refresh the correct
  mixed-list row.
- Found the same missing pre-mutation capture for Detail instances opened from
  outside the home list, such as notification dialogs. Detail now captures the
  current mixed-list position by Thing id before result-producing mutations,
  including sticky/cancel-sticky moves.
- Follow-up audit for opened Thing Folder projections showed that a
  `KEY_LIST_POSITION` also needs the list projection that produced it. Opening
  a Folder replaces both `ThingManager.mThings` and `mThingListEntries` with
  the Folder projection, so an old adapter position from root or another Folder
  must not be trusted for targeted notifications.
- Added a stable `ThingListProjection.key()` and `KEY_LIST_PROJECTION` to bind
  `KEY_LIST_POSITION` to the active built-in destination plus Folder Path.
  `ThingsActivity` now uses old adapter positions only when the result
  projection matches the current projection; otherwise it falls back to id
  lookup or a full refresh.
- Reviewed the external position-audit report and rechecked folder-scoped
  creation. Creation from an `ALL_UNDERWAY` Folder projection was already
  folder-scoped because `ThingManager.create(...)` assigns
  `mProjection.currentFolderId`, but create results that returned to
  `ThingsActivity` from a non-`ALL_UNDERWAY` Folder projection could switch to
  `ALL_UNDERWAY/root` before the manager create call. The create intent now
  carries the source Folder id explicitly so new Things keep the requested
  Folder membership even if the visible list projection changes before create
  commit.
- Re-audited all `RecyclerView.Adapter.notifyItem*` calls. Main-list
  notifications in `ThingsActivity` use mixed-list adapter positions or full
  refresh fallbacks. Local adapters such as attachments, checklist editing,
  chooser rows, and reminder-time rows use their own local adapter positions
  and are not affected by Thing Folder projections.
- Fixed one real mixed-position smell in `ThingsAdapter`: inline checklist
  toggles now convert the card holder's mixed-list adapter position to a pure
  Thing index before calling `ThingManager.update(...)`, while still notifying
  the mixed-list adapter position for the visible card refresh.
- Standardized the main-list naming convention after the audit: pure
  `ThingManager.getThings()` positions use `thingIndex`; mixed
  Thing/Folder adapter positions use `listPosition`; old/new or source/target
  list positions are qualified accordingly. Applied the cleanup to Detail
  result passing, remote action broadcasts, swipe/undo/drop handling, new-item
  animations, and Thing Card appearance preview state.

## 2026-06-17 - Mixed-list position repair after Folder Cards

- Follow-up testing showed that adding Folder Cards exposed more stale
  position assumptions: some code paths still used a Thing-only `mThings`
  index as a RecyclerView adapter position.
- Added `KEY_LIST_POSITION` for Detail results so `KEY_POSITION` can keep its
  existing Thing-index meaning for `ThingManager.update(...)`, while
  RecyclerView notifications use the mixed-list adapter position.
- Updated Detail return handling in `ThingsActivity` to resolve visible list
  positions by Thing id for item changes, removals, sticky moves, and
  doing/cancel refreshes. When a returned Thing is no longer visible, the UI
  falls back to a full list refresh instead of notifying the wrong item.
- Updated the selected-Thing card appearance entry to store the selected
  Thing's mixed-list adapter position, not its Thing-only index.
- Adjusted Folder Card count text by 2dp to the right of the Folder icon's
  layout start to match the user's visual alignment request.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-17 - Folder count alignment and custom naming dialog

- Moved the Folder Card recursive count out of the recycled ordinary
  `tvContent` slot into a dedicated dynamic count TextView inserted directly
  below the Folder header. The count keeps the existing small text size and
  now starts at the same 16dp left inset as the Folder icon.
- Added `ThingFolderNameDialogFragment` with app DialogFragment chrome for
  Folder creation and rename naming flows.
- The Folder naming dialog adapts title, confirm action, and EditText focus
  treatment to the Folder background. Gradient Folders use gradient text and a
  custom gradient EditText underline; pure-color Folders use the folder color
  as the focused text/underline accent. Selected-text background uses the
  folder accent's light color.
- Changed drag-create Folder naming so Cancel rolls back the created Folder:
  the original source/target Things are moved back to the Folder's parent
  projection and only the new Folder record is removed.
- Added a conservative rollback guard that only removes the created Folder
  record when the Folder still contains exactly the Things that the rollback is
  about to reparent.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-16 - Active swipe/drag card z-order stabilization

- Follow-up testing showed that the active Thing Card could change z-order
  relative to nearby Thing and Folder Cards during horizontal swipe or drag.
- Diagnosed the likely cause as competing z sources: AndroidX
  `ItemTouchHelper` raises the selected item based on sibling elevation at the
  start of an active draw path, while card touch, moving-mode, Folder Card
  surfaces, and RecyclerView item animations can later change sibling card z.
- Added per-frame active gesture z enforcement in `ThingsTouchCallback` after
  `super.onChildDraw(...)`: active swipe/drag cards now compute the current
  maximum sibling `z` and receive enough transient `translationZ` to remain
  above it.
- Kept normal `cardElevation` ownership unchanged so card press/release,
  selection, moving-mode, and Folder-drop feedback animations continue to use
  their existing elevation values.
- Reset the transient `translationZ` in `ItemTouchHelper.clearView(...)` and in
  `ThingsAdapter.onBindViewHolder(...)` to avoid recycled card z leakage.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161548` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Folder-drop animation isolation hardening

- Implemented a first hardening pass for Thing-to-Thing create-Folder and
  Thing-to-Folder drag feedback without enabling RecyclerView Adapter stable
  ids.
- Kept Folder-drop drag state keyed by stable business identity
  (`sourceThingId`, `targetThingId`, and `targetFolderId`) while allowing
  adapter positions to move during RecyclerView gap-filling.
- Ended pending RecyclerView item animations before arming or committing a
  Folder drop, and deferred/cleared Folder-drop hover feedback while the
  RecyclerView is computing layout or still running item animations.
- Changed Folder-drop hit-testing to use translated source/target bounds and
  skip holders with `RecyclerView.NO_POSITION`, so animated or moving cards do
  not use stale layout-only coordinates.
- Split normal animated restore from commit cleanup: ordinary hover exit still
  animates target scale/outline back to normal, while successful commit clears
  the real target card's scale/outline immediately after the overlay snapshot
  is captured so the commit overlay owns the visible finish animation.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161401` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Drag-drop animation architecture analysis

- Reviewed the current Thing-to-Thing create-folder and Thing-to-Folder drop
  implementation without changing app code.
- Confirmed that `ThingsTouchCallback` owns ordinary mixed-list reorder,
  Folder-drop hover arming, target feedback, commit overlay capture, business
  mutation, and targeted RecyclerView notifications in one callback.
- Identified the main remaining risk as concurrency between live
  `ItemTouchHelper` reorder/gap-filling, RecyclerView item animations,
  target-card scale/outline feedback, mode rebinds, and commit-time
  `notifyItemRemoved(...)` / `notifyItemChanged(...)`.
- Noted that the strongest long-term mitigation is to move Folder-drop dragging
  toward an overlay/drag-session model that freezes RecyclerView structural
  animation during Folder-drop hover and commit, uses stable entry ids for
  targets, and lets a single drag visual layer own hover and commit animation.

## 2026-06-16 - Create-Folder outline follows target during scroll

- Follow-up testing showed that the pending create-Folder outline could detach
  from its target Thing Card after a long drag path that caused the list to
  scroll.
- Refined the fixed-center model: the outline remains fixed relative to the
  target card's unscaled layout center, but it must not be fixed to one
  RecyclerView coordinate snapshot.
- Changed `FolderDropOutlineDecoration` to keep a reference to the target card
  and recompute the card's unscaled RecyclerView-local layout bounds in
  `onDraw(...)`. This makes the outline follow list scrolling and item
  translation while still ignoring the target card's current `scaleX/scaleY`.
- Kept the outline animation itself as progress-only stroke width and alpha.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161316` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Create-Folder outline fixed-center correction

- Follow-up testing showed that recalculating create-Folder outline bounds from
  the target card's current `scaleX/scaleY` was wrong: the outline drifted
  toward the target Thing Card's lower-right corner.
- Corrected the geometry model. The pending create-Folder outline and the
  target Thing Card should share one fixed center. The entrance/exit animation
  should only animate outline progress, which controls stroke width and alpha.
- Replaced transform-affected `getLocationOnScreen(...)` bounds for the target
  card with an untransformed RecyclerView-local layout bounds calculation.
- Kept the fixed visual gap from the shrunken target card by using the intended
  target scale once when the fixed outline bounds are created, not on every
  draw frame.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161251` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Create-Folder outline exit follows target scale

- Superseded by the fixed-center correction above. Recalculating bounds from
  current `scaleX/scaleY` caused visible lower-right drift.
- Follow-up testing showed that the create-Folder outline entrance animation
  was visible, but the exit animation still looked instant.
- Clarified the root cause: the outline is intentionally drawn below
  RecyclerView child cards so the dragged source Thing Card can occlude it.
  That also means the target Thing Card can cover the outline while restoring
  from its shrunken scale back to normal size.
- Changed the outline decoration to calculate its bounds during `onDraw(...)`
  from the target card's current `scaleX/scaleY`, keeping the outline outside
  the target card's current visual edge while the target card expands.
- Simplified outline exit state so clearing the current highlight detaches the
  decoration from the active-highlight slot, then animates its progress from
  the current value to `0f` and removes the item decoration once.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161240` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Create-Folder outline layering and exit animation

- Follow-up testing showed two create-Folder outline issues: the outline exit
  could disappear without animation, and the dragged source Thing Card did not
  visually occlude the outline because the outline was drawn in
  `RecyclerView.overlay` above child views.
- Moved the create-Folder outline from `RecyclerView.overlay` to a zero-offset
  `RecyclerView.ItemDecoration` drawn in `onDraw(...)`. This places the outline
  below RecyclerView child cards, so the dragged Thing Card naturally covers it
  while passing over the target.
- Kept the existing `FolderDropOutlineDrawable` progress animation for the
  entrance, but now invalidates the RecyclerView while the decoration animates.
- Added animated outline exit: clearing a pending create-Folder target now
  animates decoration progress from its current value to `0f`, then removes the
  item decoration.
- Added a token guard for outline exit removal so a canceled old exit animation
  cannot remove a newly created outline decoration.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161220` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Retargeted Folder-drop restore animations

- Follow-up testing showed that synchronising `scaleX/scaleY = 1f` directly in
  every cancel callback still creates a visible jump. A restore animation may
  be canceled because a newer highlight animation has taken over the same
  target; in that case the old animation must not write its obsolete final
  value.
- Replaced unconditional cancel fallback with per-target animation tokens. When
  a new card-scale animation starts, it invalidates the previous token before
  canceling the old animation. The old cancel callback exits without writing
  any value, so the new animation retargets from the current visual scale.
- Applied the same token guard to thumbnail Folder outline animations.
- Thumbnail outline animations now track the current stroke width per content
  view and start the next animation from that current visual width, rather than
  restarting from the normal or highlighted width.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161209` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Folder-drop restore final-state fallback

- Follow-up testing clarified that animated Folder-drop restore still needs a
  final-state fallback. Restore should animate normally when possible, but if a
  later drag feedback animation cancels it, the target must still be
  synchronised to the intended final visual state.
- Added `withEndAction(...)` final-state synchronisation for target card scale
  restore animations, and paired it with an `onAnimationCancel(...)` listener
  fallback because Android does not run `withEndAction(...)` for canceled view
  property animations.
- Thumbnail-mode Folder outline animations now synchronise the target stroke
  width in both `onAnimationEnd(...)` and `onAnimationCancel(...)`.
- New target highlight animations explicitly clear any previous restore
  listener/end action before starting, so stale restore callbacks cannot affect
  a fresh highlight animation.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161159` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Animated multi-target Folder-drop cleanup

- Follow-up testing clarified that the broad multi-target cleanup should not
  snap Folder-drop feedback back to normal. The cleanup must remain animated so
  target cards and thumbnail outlines visually settle instead of jumping.
- Kept per-drag tracking for every highlighted Folder/Thing target, but changed
  restoration back to animations: each tracked card now animates scale back to
  `1f` with the existing Folder-drop target animation duration.
- Replaced the single thumbnail-outline animator with a per-content animator
  map. This lets multiple thumbnail-mode Folder targets animate their outline
  width back to normal independently instead of cancelling each other's
  recovery animation.
- Removed the direct thumbnail outline reset path from Folder-drop cleanup.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161015` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Multi-target Folder-drop highlight cleanup

- Follow-up testing showed a remaining visual-only drag regression: when a
  Thing Card drag path crossed multiple Folder Cards, triggered Folder-drop
  feedback, and also caused RecyclerView drag gap-filling, one previous Folder
  target could remain scaled down even after the dragged card's top-left corner
  was no longer inside it.
- Changed Folder-drop highlight bookkeeping from a single latest target view
  to per-drag collections of every card and thumbnail-outline content that has
  been highlighted.
- Folder-drop cleanup now cancels pending ViewPropertyAnimators and restores
  all tracked target card scales to `1f`. Thumbnail-mode Folder targets also
  have their outline reset back to the normal stroke immediately.
- Tightened the same-target early-return condition so it is used only when the
  tracked target collection contains exactly the current target card. If extra
  highlighted targets are still recorded, cleanup runs before re-highlighting
  the current target.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606161009` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Reorder persistence guard for Folder-drop hover frames

- Follow-up testing showed two remaining drag regressions: a Folder-drop target
  could stay visually shrunken during RecyclerView drag gap-filling, and a drag
  path that passed through Folder targets could leave the in-memory list order
  different from the persisted database order after app restart.
- Fixed the persistence bug by ensuring Folder-drop hover candidate frames do
  not update `finalFrom` or `finalTo`. Only the normal mixed-list move branch
  that actually calls `mThingManager.move(...)` and `notifyItemMoved(...)` now
  affects reorder persistence through `updateLocations(...)`.
- Hardened target highlight refresh for RecyclerView gap-filling by comparing
  the actual target `CardView`, not only the adapter position and action. If a
  position now resolves to a different card view, the old shrunken card is
  restored before the new target can be highlighted.
- Added immediate pending-highlight cleanup from `onMove(...)` while
  RecyclerView is computing layout or its item animator is running, or when the
  current candidate no longer matches the armed pending Folder drop.
- Moved the Folder Card title offset from 2dp to 1dp.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606160958` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Stabilized drag hover arming around RecyclerView gap filling

- Follow-up testing showed that Folder-drop feedback was still too eager while
  RecyclerView was filling the drag gap. Dragging the first Thing Card toward
  the second could let the target card move first while the pending Folder
  outline remained at the old visual location.
- Split Folder-drop detection into a hover candidate and an armed pending drop.
  A candidate must keep the same source Thing id, target Thing/Folder id,
  target adapter position, and action for a short delay before the pending
  Folder feedback is shown or can be committed on release.
- Deferred arming while RecyclerView is computing layout or its item animator
  is running, so Folder-drop feedback waits for normal drag gap-filling to
  settle before deciding whether the user is really creating or merging into a
  Folder.
- Changed Folder-drop highlight cleanup to remember and restore the actual
  highlighted card view instead of resolving it again by adapter position. This
  prevents a target card from staying shrunken when adapter positions shift
  during drag.
- Moved the Folder Card title 2dp lower in the compact header.
- Verified with `.\gradlew.bat :app:assembleDebug --console=plain`; only the
  pre-existing deprecated override warning remains.
- Published debug update `202606160944` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Folder drop refinement and count typography

- Follow-up testing clarified that thumbnail-mode Folder Cards should both
  shrink and thicken their outline when a Thing Card is dragged onto them.
- Changed create-Folder pending outlines to use a fixed visual gap from the
  shrunken target Thing Card instead of relying on the target card's original
  width and height. This keeps horizontal and vertical gaps visually even
  across different target-card aspect ratios.
- Hardened the drag state machine for the case where a Thing Card enters a
  Folder-drop candidate, leaves it before release, returns to its original
  position, and then releases. Frames immediately after leaving a pending
  Folder target now clear the pending drop without falling through to a normal
  reorder, and release checks the dragged Thing's final list position against
  its drag-start position before deciding whether to enter selecting mode.
- Adjusted Folder Card title/count typography: the title no longer uses the
  previous negative top margin, and the recursive count explicitly uses the
  ordinary small count text size used by normal Thing Card media/audio count
  labels.
- Audited Thing Card count typography. Inline image/video and audio counts are
  `11sp` in their normal state and expand to `18sp` only when the card is
  otherwise attachment-count-only. Image/video counts shown over visible media
  or media backgrounds are separate overlay labels but also use `11sp`.
- Verified with `.\gradlew.bat :app:assembleDebug`; only the pre-existing
  deprecated override warning remains.
- Published debug update `202606160903` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Existing Folder drop feedback and drag-exit crash fix

- Follow-up testing clarified that the create-Folder outline with a visible gap
  applies only when dragging one Thing Card onto another Thing Card.
- Changed dragging a Thing Card onto an existing Folder Card to use
  Folder-card-specific feedback: summary-mode Folder Cards scale down, while
  thumbnail-mode Folder Cards keep their size and animate their outline to a
  thicker stroke.
- Fixed a crash reported on OnePlus Android 16 when a drag entered a Folder
  target and then left it before release. The likely cause was adding/removing a
  high-frequency highlight `View` directly under the activity
  `ContentFrameLayout` during render traversal. The create-Folder highlight now
  uses `RecyclerView.overlay` drawables instead of root child views, and
  existing-Folder highlights no longer use the root overlay path.
- Adjusted Folder Card header alignment: the count now aligns with the Folder
  icon's left edge, and the title `TextView` removes extra font padding with a
  slight top offset so the first title line sits visually closer to the icon.
- Verified with `.\gradlew.bat :app:assembleDebug`; only the pre-existing
  deprecated override warning remains.
- Published debug update `202606160815` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-16 - Folder Card layout, drop outline gap, and private folder scope

- Updated the pending drag-drop target highlight so the outline is rendered as
  a root overlay at the original target-card bounds while the target card
  scales down. This creates a visible animated gap between the card body and
  the Folder outline while preserving pure-colour and gradient Folder
  backgrounds. The overlay is also cleared before restoring the target card so
  a recycled target holder cannot leave a stale outline behind.
- Refined Folder Card header layout: the Folder icon is smaller and placed at
  the top-left, the Folder title sits to its right with up to two lines, and
  the recursive count is aligned under the title column with the shorter
  Chinese format `X件记事`.
- Added private Folder prerequisite handling: setting a Folder private now
  requires the existing private-content app password, and shows a
  Folder-specific warning dialog if no password exists.
- Added authenticated private Folder scope support in `ThingManager`. Opening a
  protected Folder after authentication records that Folder as authenticated for
  the current Folder path; descendants render normally inside that path, and the
  scope is trimmed when the user navigates above or outside the authenticated
  path.
- Wired the authenticated scope through Folder Cards, Thing Cards, thumbnail
  clicks, Folder move dialogs, the Activity header path, and Detail screen
  Folder-location text so private Folder names and contained cards are revealed
  only inside the authenticated path or global private-content mode.
- Verified the changes with `.\gradlew.bat :app:assembleDebug` after rerunning
  Gradle with elevated permissions because the sandbox denied access to
  `.gradle/configuration-cache/configuration-cache.lock`.
- Published debug update `202606160743` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode` and APK URL.

## 2026-06-15 - Planning baseline

- Read global memory indexes, operational rules, `CONTEXT.md`, ADRs, and
  relevant feature docs before planning.
- Audited current Thing persistence, home list loading, long-press drag,
  drawer navigation, header rendering, Detail loading, backup/export/share,
  widgets, and ShiningBorder entry animation touchpoints.
- Created the initial Thing Folders `README.md`, `plan.md`, `execution.md`, and
  `decisions.md`.
- Added Thing Folder domain language to `CONTEXT.md`.
- Stopped before code so unresolved product decisions can be grilled one at a
  time.

## 2026-06-15 - Count and state projection decisions

- Confirmed that Folder Card counts are recursive descendant Thing counts.
- Confirmed that hidden private Things contribute to Folder Card counts, while
  thumbnails and previews must still hide private content when private content
  is hidden.
- Verified existing header counts use `ThingsCounts` by type/state and do not
  filter out private Things.
- Confirmed that finished and deleted Things preserve folder membership.
- Confirmed that Finished and Deleted built-in destinations should show Folder
  Cards for folders with matching descendant Things.

## 2026-06-15 - Drawer and folder projection decision

- Confirmed that Thing Folders should not appear as drawer items.
- Updated the navigation model to a `ThingListProjection`: one built-in drawer
  destination plus an optional Thing Folder Path.
- Confirmed that opening a Folder Card keeps the current built-in drawer item
  selected and opens a folder projection inside that built-in destination.
- Confirmed that selecting a built-in drawer item clears the current folder
  path projection.

## 2026-06-15 - Built-in list, ordering, sticky, and privacy decisions

- Confirmed that all built-in destinations, including Notes, Reminders, Habits,
  and Goals, should show Folder Cards for folders with matching recursive
  descendant Things.
- Confirmed that Thing Folders support manual mixed ordering among Thing Cards.
- Confirmed that Thing Folders support sticky placement using the same sticky
  concept as Things.
- Confirmed that Thing Folders support privacy.
- Confirmed that Private Thing Folder privacy inherits to descendants for
  display/access while preserving each descendant's own stored private state.

## 2026-06-15 - Folder delete and restore decision

- Confirmed that deleting a Thing Folder moves the folder subtree to Deleted.
- Confirmed that folder deletion preserves the folder subtree, Thing
  memberships, and descendant stored states.
- Confirmed that descendants of a Deleted Thing Folder are effectively deleted
  for display/navigation while inside that deleted folder.
- Confirmed that restoring a Deleted Thing Folder restores subtree visibility
  according to descendant stored states.
- Confirmed that permanent deletion is the operation that destroys folder
  records.
- Confirmed that permanently deleting a Deleted Thing Folder deletes the entire
  folder subtree and contained Things, including descendants that are only
  effectively deleted by the deleted folder.

## 2026-06-15 - Widget scope decision

- Confirmed that Things-list widgets should not render Thing Folder Cards in
  v1.
- Deferred folder-aware widget rendering, projection intents, effective privacy,
  and effective deletion handling to `followups.md`.

## 2026-06-15 - Data model and migration foundation

- Bumped the database version to v15.
- Added `Thing.folderId` with Cursor and trailing Parcelable compatibility.
- Added `ThingFolder` and `ThingFolderCardPresentation` models.
- Added the `thing_folders` table, `things.folder_id`, and folder lookup
  indexes for fresh installs and v14 upgrades.
- Updated Thing DAO create/update/state-restore paths to preserve folder
  membership.
- Added a basic Thing Folder DAO for create/update, parent movement, state,
  privacy, card presentation, ordering, path lookup, cycle prevention, and
  permanent subtree deletion.
- Verified the foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Projection and recursive count foundation

- Added `ThingListProjection` for built-in destination plus optional Thing
  Folder Path.
- Added `ThingListEntry` as the mixed Thing/Folder home-list entry model.
- Added projection-aware Thing row loading that preserves current type/state,
  search, and colour filtering while filtering by `folder_id`.
- Added recursive destination-aware Folder Card counts and thumbnail seed
  queries.
- Added a parallel mixed-entry list in `ThingManager` while keeping the legacy
  `mThings` list for existing adapters and widgets.
- Verified the projection foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Home summary Folder Card foundation

- Added localized strings and a vector icon for Thing Folder cards.
- Added adapter hooks so `BaseThingsAdapter` can keep legacy Thing rows while
  `ThingsAdapter` renders mixed `ThingListEntry` rows.
- Added a Folder Card view type using the existing `card_thing.xml` holder.
- Implemented summary Folder Card rendering with folder background, adaptive
  foreground, folder icon, recursive count, sticky indicator, private-folder
  title protection, and normal/full-span placement.
- Added a thumbnail-mode outlined card shell; actual child thumbnail rendering
  remains pending.
- Verified the adapter foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Folder Card click navigation foundation

- Added ThingManager helpers that map adapter positions to mixed entries and
  legacy Thing positions.
- Wired Folder Card clicks in normal mode to open that folder projection and
  keep the current built-in destination.
- Updated Thing clicks to pass the legacy Thing index to Detail while using
  mixed adapter positions for UI updates.
- Temporarily disabled legacy drag/swipe paths while Folder Cards are present,
  preventing position-mapping writes before real mixed ordering is implemented.
- Wired Back to navigate from a nested folder projection to its parent before
  falling through to the existing root-exit behavior.
- Verified the click-navigation foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Detail folder path foundation

- Added a Detail path row under the title.
- Displayed a Thing's containing Thing Folder Path when `folderId` is present.
- Protected private folder names in the Detail path row with the private-folder
  placeholder.
- Kept Detail membership read-only in this slice, so `Thing.noUpdate` remains
  unchanged.
- Verified the Detail path foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Folder creation business operation foundation

- Added a manager operation that creates a Thing Folder from two Things in the
  current projection.
- Added a manager operation that moves a Thing into or out of a folder by
  updating `things.folder_id`.
- Kept the drag/drop gesture wiring pending; the UI can call the manager
  operation once target detection and naming are implemented.
- Verified the operation foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Drag-to-folder creation and header path

- Wired long-press drag so dropping one eligible user Thing onto another opens
  a naming dialog and creates a Thing Folder in the current projection.
- Blocked folder creation for header/placeholder content, current Doing Things,
  non-underway Things, and stored-private Things while hidden-private handling
  remains conservative.
- Kept legacy mixed-list reorder/swipe disabled when Folder Cards are present,
  but allowed additional drag-to-folder creation in lists that already contain
  Folder Cards.
- Updated `ModeManager` to select Things by mixed-list position mapping instead
  of raw `mThings` index.
- Added clickable ActivityHeader folder paths that preserve the active built-in
  drawer destination and navigate to root or ancestor folders.
- Verified the drag and header foundation with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Folder Card thumbnails and actions

- Implemented thumbnail-mode Folder Cards using lightweight child Thing preview
  views that adapt their height to title/content length.
- Hid stored-private child Thing content in thumbnails when private content is
  not shown, while still preserving recursive counts.
- Added thumbnail taps that open the child Thing Detail with a `position=-1`
  update path and reload the home projection after Detail changes.
- Added long-press Folder Card actions for rename, summary/thumbnail mode,
  normal/full span, private toggle, and sticky/cancel-sticky.
- Added manager update APIs for Folder rename, card presentation, privacy, and
  sticky location writes.
- Verified the thumbnail/action slice with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Folder state, effective deletion, and effective privacy

- Added Folder Card actions for delete, restore, and permanent delete.
- Implemented folder deletion as a `ThingFolder.state` change so descendant
  Things keep their stored states and memberships.
- Implemented effective deletion through arbitrary deleted ancestors: deleted
  folders and their descendants disappear from non-Deleted projections and
  count all contained Things in Deleted projections.
- Implemented permanent delete for a folder subtree by deleting descendant
  folder records and contained Things.
- Fixed mixed-list empty-state handling so lists with only Folder Cards do not
  create notify-empty placeholder Things.
- Implemented effective privacy through arbitrary private ancestors for Folder
  Cards, current-folder Thing rendering, Folder Card opening/actions, and
  thumbnail seed filtering.
- Routed Folder Card search and colour filtering through the same keyword/hue
  filters used by Thing rows.
- Updated ActivityHeader subtitles inside folder projections to show current
  visible direct children instead of global built-in destination counts.
- Verified the state/privacy/search slice with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-15 - Mixed ordering and move targets

- Replaced the temporary drag guard with mixed `ThingListEntry` movement for
  Things and Thing Folders.
- Persisted mixed ordering changes to both `things.location` and
  `thing_folders.location`, including sticky-area Folder Card movement.
- Added an overlap threshold and target-card highlight so Thing-to-Thing drag
  can distinguish folder creation from ordinary reordering.
- Added a Folder Card action that starts manual card-order dragging.
- Added Folder Card movement into another Thing Folder or root, with cycle
  prevention and private-target authentication.
- Added an Underway selection-menu action to move selected Things into any
  non-deleted Thing Folder or back to root.
- Verified the slice with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606151628` to the Aliyun debug channel for device
  testing.

## 2026-06-16 - Drag feedback and mixed-list regressions

- Replaced the folder-create overlap threshold with top-left-corner hit
  testing: a pending Folder drop is active only while the dragged Thing Card's
  top-left corner is inside the target card.
- Added animated pending-drop feedback for Thing targets: the target card
  shrinks and draws a pure or gradient Folder outline using the same random
  background that will be assigned to the newly created Folder.
- Added Thing-to-existing-Folder drag support: dropping an eligible Thing onto a
  Folder Card moves the Thing into that Folder instead of creating another
  Folder, with a thicker animated target outline.
- Included Folder Cards in the existing list appearing animation and replayed
  that animation when opening or navigating within a Folder projection.
- Fixed mixed-list new Thing creation by assigning new Things to the current
  Folder projection, rebuilding mixed entries after manager mutations, and
  arming the insertion animation against the new Thing's mixed-list position.
- Restored left/right swipe actions for Thing Cards in mixed Thing/Folder
  lists while keeping Folder Cards non-swipable.
- Verified the fixes with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160225` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.

## 2026-06-16 - Pending drop release and undo position fixes

- Fixed a drag release regression where the pending Folder drop animation could
  be visible, but `clearView` rechecked already-reset View coordinates, cleared
  the pending drop, and fell through to selecting mode instead of creating a
  Folder or moving the Thing into an existing Folder.
- Changed drag release handling to consume the last active pending Folder drop
  state established during drag frames; moving the dragged card out of the
  target continues to clear that state before release.
- Fixed mixed-list undo notifications after swipe-finish by mapping the undone
  Thing id back to its current mixed adapter position before notifying the
  adapter. This prevents a leading Folder Card from flashing when undoing the
  second visible Thing.
- Applied the same mixed-position notification mapping to habit undo after a
  swipe-finish.
- Verified the fixes with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160240` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.

## 2026-06-16 - Active-frame pending drop hardening

- Follow-up testing showed that the visible pending Folder drop animation could
  still fail to execute after release.
- Hardened the drag lifecycle so pending Folder drop hit-testing is updated
  only during active drag frames. Non-active `ItemTouchHelper` recovery frames
  no longer clear a valid pending drop after the user releases the card.
- Changed pending Folder drop state to store the source Thing id plus the target
  Thing id or target Folder id. Release handling resolves the current Thing or
  Folder from those ids instead of relying on adapter positions captured during
  drag.
- Removed the obsolete position-based drop helpers so future changes do not
  accidentally reintroduce the unstable path.
- Verified the hardening with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160305` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.

## 2026-06-16 - Drop commit animation and targeted list updates

- Follow-up testing showed that successful Folder drops still felt wrong
  because `ItemTouchHelper.clearView(...)` visually returned the dragged Thing
  Card to its original position before the business change became visible.
- Added a transient overlay snapshot of the dragged Thing Card before
  `clearView(...)` resets the real item view. The overlay now animates from the
  release position into the target Folder Card or target Thing Card, while the
  real source view stays hidden until the removal animation has finished.
- Added a moving-mode exit path that skips the old delayed full-list refresh
  after a successful Folder drop, allowing the drop commit path to own the
  RecyclerView notifications.
- Changed Thing-to-Folder and Thing-to-Thing drop commits to mutate the manager
  first, then issue targeted `notifyItemRemoved(...)` plus
  `notifyItemChanged(...)` calls so the source gap closes and the target Folder
  Card updates without replaying the whole list appearing animation.
- Reset recycled root item-view visibility, alpha, and scale in
  `ThingsAdapter.onBindViewHolder(...)` so a hidden source holder cannot leak
  into later bindings after the overlay animation path.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160352` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.

## 2026-06-16 - Pre-recovery drop visual capture

- Follow-up testing showed that the dragged Thing Card still visibly returned
  to its original position before entering a Folder, which means the previous
  `clearView(...)` overlay capture still happened after AndroidX
  `ItemTouchHelper` had already started or completed its drag recovery.
- Moved successful Folder-drop visual preparation earlier in the drag lifecycle:
  `getAnimationDuration(...)` now prepares the overlay snapshot, hides the real
  source item view, and returns `0L` for the pending drag recovery before the
  default recovery animation can display a snap-back.
- Added release-time bookkeeping for the active drag holder and last active
  source coordinates in root coordinates, so the overlay starts from the user's
  last real drag position instead of whatever coordinates remain by
  `clearView(...)`.
- Kept `clearView(...)` as the business commit point so normal no-drop drag,
  reorder, and selecting-mode behavior continue to use the existing paths.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160403` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.

## 2026-06-16 - Thumbnail Folder Card transparent surface fix

- Follow-up testing showed that thumbnail-mode Folder Cards looked wrong in
  light mode: the outlined card interior could show an elevation shadow or a
  stale solid folder-colour fill, and the title/icon/count colours were still
  chosen against the folder background instead of the transparent list
  background.
- Fixed the root cause in `ThingsAdapter`: summary-mode Folder Cards paint
  `CardView.background` through `BackgroundUtil.applyCardBackground(...)`,
  while thumbnail mode had only called `setCardBackgroundColor(Color.TRANSPARENT)`.
  Because these are different drawable layers, recycled holders could keep the
  old summary-mode `GradientDrawable`.
- Thumbnail-mode Folder Cards now explicitly replace the outer `CardView`
  background with a transparent rounded drawable, set both `cardElevation` and
  `maxCardElevation` to `0f`, and keep only the inner `llContent` transparent
  outline drawable.
- Added a `tag_thing_folder_thumbnail_surface` marker so touch animations skip
  elevation changes for thumbnail Folder Cards, while ordinary Thing Cards and
  summary Folder Cards restore normal elevation behavior on bind.
- Changed thumbnail-mode folder title, folder icon, count text, and sticky icon
  colours to use the app/list background as the contrast base, matching light
  and dark mode resources.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.
- Published debug update `202606160528` to the Aliyun debug channel and
  verified remote `latest.json` returns that `debugUpdateCode`.

## 2026-06-17 - Folder contextual actions and card appearance editing

- Changed Folder Card long-press behavior to follow Thing Card long-press
  semantics: underway folders enter moving mode and can be reordered or dropped
  into another Folder Card; releasing without a meaningful move enters selecting
  mode.
- Extended mixed `ThingListEntry` selection support so contextual toolbar counts,
  select-all, sticky, private, restore, dissolve, delete, and card-appearance
  actions can target a single selected Folder without routing through the old
  Folder action dialog.
- Added Folder Card appearance editing through the existing card appearance
  panel. Folder editing exposes a Folder name field, card width, and display
  mode choices while reusing the existing confirm/cancel preview lifecycle.
- Renamed the Folder appearance selector from display mode to Folder size, with
  Normal and Large as the user-facing choices.
- Kept the Folder size options on the same row as the label instead of reusing
  the Thing media-position two-line layout.
- Added current-folder overflow actions for toggling private state, dissolving
  the current Folder, and deleting or permanently deleting it depending on the
  current Deleted projection.
- Implemented Folder dissolve in the DAO/manager layer by moving direct child
  Things and child Folders to the parent, then removing the Folder record.
- Kept Folder delete semantics state-based: outside Deleted it moves the Folder
  subtree into Deleted through Folder state; in Deleted it uses recursive
  permanent deletion.
- Replaced Folder dissolve/delete confirmations with `AlertDialogFragment`
  prompts and added localized strings for the new Folder actions.
- Updated private Folder Card rendering so hidden private folders keep the
  Folder icon in the title row, suppress child counts, and show a lock below
  the title.
- Reduced the large audio-only count text in Folder thumbnail previews by 2sp.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-18 - Full-session overlay drag controller

- Replaced the long-press Thing/Folder drag owner with a custom
  `ThingListOverlayDragController` that renders a full-size bitmap snapshot of
  the source card in the Activity overlay for the whole drag session.
- Kept the real source card in the RecyclerView as a transparent layout
  placeholder, with cleanup by stable Thing/Folder identity so recycled or
  detached ViewHolders do not keep transparent, scaled, dimmed, or highlighted
  state.
- Moved active drag move/up/cancel handling to `ThingsActivity.dispatchTouchEvent`
  while leaving inactive touch dispatch unchanged. The controller consumes the
  active session events so release is still observed outside RecyclerView
  bounds.
- Added controller-owned edge auto-scroll and stopped existing RecyclerView
  scroll/fling when the overlay session starts. ItemTouchHelper no longer owns
  Thing/Folder drag and now exposes only swipe flags for Thing Cards.
- Added source-coloured reorder insertion-line feedback, using the dragged
  Thing/Folder `ThingBackground` and supporting pure colours and gradients.
  Reorder is committed once at release by resolving source and target stable
  business ids.
- Preserved existing Folder-drop hover visuals by bridging the overlay
  controller to the current target shrink, outline, background, and content
  alpha feedback. Folder drop still uses the dragged card's top-left point and
  requires stable hover before arming.
- Preserved release outcomes for Folder drop, reorder, release-in-place
  selection, and cancellation. Folder-drop commits reuse the session overlay for
  the fly-into-target animation; selection/cancel restores or fades the overlay
  without committing stale data.
- Kept optional file-backed overlay drag debug logging behind a disabled flag.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-18 - Overlay drag polish after device feedback

- Device testing of the first overlay-drag build showed four regressions:
  reorder insertion lines were drawn directly against card edges instead of in
  the visual gap, cards could remain in Moving-mode dimmed colours after a
  reorder, the source card could become visible again when auto-scrolling back
  to its original position, and reorder release animations fought
  RecyclerView's own `notifyItemMoved(...)` animation.
- Moved the insertion line to the midpoint between the candidate card and its
  nearest vertically adjacent visible card in the same horizontal span, with a
  small fallback gap when there is no visible neighbour.
- Added a RecyclerView child-attach guard to the overlay drag session so any
  reattached source ViewHolder is immediately restored to transparent
  placeholder state while the drag is active.
- Changed reorder release animation ownership: the overlay no longer flies to
  the final insertion edge. It is removed before the final `notifyItemMoved(...)`
  commit so RecyclerView owns the visible reorder movement. Release-in-place
  selection now fades the overlay out instead of flying it back.
- Changed successful reorder cleanup to exit Moving mode through
  `finishMovingModeWithoutListRefresh()` and schedule a delayed full rebind
  after item movement, restoring cards from Moving-mode dimmed colours without
  competing with the move animation.
- Verified the changes with `.\gradlew.bat :app:assembleDebug`.

## 2026-06-19 - Private Folder auth, mixed sticky ordering, and move dialog

- Required privacy authentication before editing a private Folder card
  appearance, before moving Things/Folders into or out of private Folder
  boundaries, before expanding private Folders in the move-target tree, and
  before swipe actions on private Things in private contexts.
- Added Folder sticky support in the home list and kept sticky Things/Folders
  mixed in one parent-scoped sticky region. Toggling sticky now refreshes the
  visible list immediately instead of waiting for an app restart.
- Added parent-scoped sticky support inside Folders. Sticky Things/Folders move
  to the top of that Folder, use the parent Folder colour for their marker, and
  have sticky state cleared when moved out of the parent.
- Added a custom move-to-Folder DialogFragment with drawer-style tree rows,
  root selection, forbidden self/subtree targets, deferred commit until confirm,
  and Folder-coloured title/confirm affordances.
- Updated Folder drop and reorder behavior so private Folder drops authenticate
  before commit, and reorder insertion feedback is hidden when dragging normal
  cards ahead of sticky cards.
- Updated Thing Detail to show the Folder path below timestamps with a
  correctly coloured Folder icon and slash-joined path text.

## 2026-06-19 - Folder privacy titles, thumbnail sticky preview, and move-dialog polish

- Replaced generic private-auth titles for Folder operations with
  operation-specific titles for opening, expanding, managing, moving, and
  customizing private Folder content.
- Fixed large Folder thumbnail previews so sticky child Things/Folders use the
  same sticky-aware ordering as the home list. Negative sticky locations are no
  longer sorted behind normal children and dropped by the preview limit.
- Moved the Detail Folder-path icon down by 1dp so its visual centre aligns
  with the path text baseline.
- Updated the custom move-to-Folder dialog rows to occupy the available dialog
  width, use drawer-style root icon tint, provide full-row ripple feedback,
  and use circular ripple feedback on the expand/collapse affordance.
