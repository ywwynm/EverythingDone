# Thing Folders Preferences

## Navigation Semantics

- The previous preference that Thing Folders should not appear as Drawer items
  is superseded. The Drawer should include the non-deleted Thing Folder tree
  under Underway and above Note.
- The home Drawer should use an app-owned navigation view instead of Material
  `NavigationView` when Folder-tree precision is needed, so indentation,
  trailing affordances, title width, selected state, and row recycling are under
  project control.
- Folder navigation should happen through Folder Cards and the Drawer. Inside a
  Folder projection, the Activity header should show only the current Folder
  name as a plain non-link title, using the current Folder's pure colour or
  gradient text fill. It should not render the full Folder path in blue or
  underline it.
- Inside a Folder projection, the Activity header subtitle should show direct
  child counts split by type, in the form `X folders, Y things` /
  `X个文件夹，Y件记事`, instead of a single combined item count.
- If either direct child count is zero, omit that segment from the Activity
  header subtitle; for example show only `X things` / `X件记事` when there are
  no child Folders.
- The previous direct-Thing-count Activity Header rule is superseded. The
  Folder count remains the count of visible direct child Folders, but the Thing
  count is recursive: direct Things in the current projection plus matching
  Things inside all descendant Folders.
- Inside a Folder projection, the ThingsActivity list surface should use the
  same muted Folder-background surface as thumbnail-mode Folder Cards. Returning
  to a root projection should restore the plain `bg_activity_things` surface.
- The muted Folder surface should stay very close to `bg_activity_things`,
  carrying only a subtle trace of the Folder's pure colour or gradient.
- Long Folder names in the Activity header should be constrained before the
  right-side card edge in the expanded state, and before the Home toolbar search
  action in the collapsed state. Header width changes during scroll should be
  animated by the existing header-collapse progress. If the title wraps, the
  list's invisible header spacer must grow with the header so Folder info and
  the first visible card never overlap.
- Header collapse state must stay continuous at the boundary where the
  invisible header spacer scrolls offscreen. The title, subtitle alpha, and
  actionbar shadow should not reset or flicker when the first visible card is
  near the actionbar.
- Inside a Folder projection, the create-Thing FAB and normal actionbar icons
  should adopt the current Folder's pure colour or gradient. In contextual
  selecting mode, the contextual toolbar and status-bar spacer should adopt the
  Folder colour/gradient, while contextual icons and title text choose a dark
  or light foreground from that Folder background's luminance.
- The create-Thing FAB icon should be visually centered and strong enough by
  adjusting the vector resource itself, not by adding one-off FAB padding that
  would differ between root and Folder projection backgrounds.
- Gradient actionbar icon tint should be rendered from the actual icon alpha
  mask and normalize that mask's strongest alpha to opaque, so older
  semi-transparent toolbar assets do not look paler than Folder title text.
- Root Activity Header subtitles should also include direct child Folder counts
  and omit zero segments, matching the in-Folder subtitle rule.
- Root Activity Header subtitles follow the same recursive Thing-count rule:
  direct root Things plus matching Things inside every visible root Folder
  subtree.
- If a Folder name wraps to two lines in the actionbar, its collapsed title
  scale should become slightly smaller than the normal one-line collapsed
  title scale, and that additional shrink should be reached continuously during
  the same scroll collapse animation.
- In the Drawer, Underway acts as the root directory. The first Folder level is
  always visible; deeper Folder levels are revealed by expanding their parent
  Folder's trailing dropdown.
- Drawer Folder rows open the Folder in the Underway projection. The dropdown
  affordance toggles expansion without selecting or opening the Folder.
- Opening a new child Folder from a Folder Card should still start that child
  projection at the top. Returning to the parent Folder projection should
  restore the parent's previous RecyclerView scroll state within the current
  Activity session instead of jumping to the top.
- Returning to a saved parent or ancestor Folder projection should restore the
  saved `RecyclerView.LayoutManager` state directly, not with smooth scrolling.
  The Activity Header should settle to the restored scroll position before the
  next draw and without running its translation/scale animation.
- Returning to a saved parent or ancestor Folder projection should not play the
  ordinary Things appearing animation. That animation is for new/top-level list
  appearances and can visually fight the restored RecyclerView scroll state.
- Drawer hierarchy indentation should be visible and should begin at the Folder
  icon. Because Underway acts as the root directory, even first-level Folders
  should have a default 16dp indent beneath Underway.
- Drawer Folder indentation should not shrink the Folder icon or text. The
  first Folder level's title should align with the Underway title, while the
  visible indentation starts at the Folder icon. Deeper Folder levels shift both
  icon and title together, preserving the same icon-to-title gap.
- Only Folders that have child Folders should show the trailing dropdown
  affordance.
- Drawer Folder titles with a trailing expand/collapse affordance should be
  constrained before that affordance and ellipsize rather than drawing beneath
  it. Use an explicit title end margin in addition to the trailing affordance's
  measured width.
- All Drawer Folder rows should reserve the same trailing expand/collapse slot,
  even when the Folder has no child Folders. Leaf Folders hide the affordance
  but keep the reserved width so Folder title right edges align across rows.
- Drawer Folder expand/collapse affordances should use a fixed circular touch
  target with the app chrome ripple treatment, including dark mode. The touch
  target should be compact enough to avoid visually oversized ripples and
  should keep a small end margin from the Drawer edge.
- Drawer Folder expand/collapse should animate inserted or removed rows instead
  of making the list flash. Expanding should reveal child rows downward from
  their parent and rotate the dropdown icon clockwise to the expanded state;
  collapsing should remove child rows upward and rotate the icon back
  counter-clockwise.
- Folder icons should render with the Folder's own pure colour or gradient
  background.
- Built-in Drawer destination icons should keep their original asset colours
  when selected rather than being retinted to a dimmer app chrome colour.
  Static Drawer destination icons should be rendered through the opaque tint
  path so low-alpha PNG assets do not stay visually washed out. Use the same
  dedicated `app_chrome_drawer_item_foreground` colour for static Drawer
  destination icons, Drawer item titles, and trailing expand/collapse icons in
  both light and dark mode; selected state should use background/bold weight,
  not a stronger foreground colour. The current value sits between App Chrome
  primary and secondary foreground tiers.
- Add a separator above the Note item so built-in type filters remain visually
  distinct from the Underway root and Folder tree.
- Drawer sections separated by dividers should breathe as sections: the first
  item in each section has an 8dp top margin, and the last item in each section
  has an 8dp bottom margin. The Underway root and its visible Folder tree count
  as one section.
- The final Drawer row should add the current bottom system-bar/display-cutout
  inset to its bottom spacing so it clears gesture navigation and 3-button
  navigation areas.
- The Drawer should have exactly one checked item at a time.

## Move Semantics

- When a Thing or Thing Folder is moved into a different Thing Folder, moved
  back to its previous parent, or moved back to root, it should not preserve its
  old relative order. The moved entry should become the first item in the target
  root or target Folder's corresponding sticky or non-sticky section, preserving
  the entry's existing sticky state.
- The move-to-Folder dialog for a Thing Folder should still render the source
  Folder and its descendant subtree in the target tree. Those rows remain
  expandable so users can understand why a target is unavailable, but the source
  Folder and every descendant are disabled and cannot be selected.
- Disabled target rows in the move-to-Folder dialog should apply the App Chrome
  disabled foreground to the Folder icon and title text. The trailing expand
  affordance may remain normally visible and interactive when the disabled row
  has children.
- When the move-to-Folder dialog content becomes scrollable, show the same
  top/bottom divider treatment used by other scrollable App Chrome dialogs:
  hide both dividers when content does not scroll, and hide only the divider at
  the current scroll boundary while scrolling.
- The move-to-Folder dialog should reserve stable space for its scroll dividers
  and action-row gap so expanding a Folder tree into a scrollable state does not
  shift or flash the dialog layout.
- Empty Thing Folders are valid user-owned containers. Do not automatically
  delete a Thing Folder merely because moving Things or child Folders out of it
  leaves it structurally empty.
- Long-pressing a Folder Card should enter the same drag/select affordance as
  long-pressing a Thing Card. A Folder can be dragged into another Folder, and a
  release that returns to the original position should enter selecting mode
  instead of opening a Folder action dialog.
- When selecting mode is already active, long-pressing a Folder Card should
  exit selecting mode the same way long-pressing a Thing Card does, rather than
  swallowing the gesture.
- A separate dedicated Folder move UI remains deferred; the current slice uses
  drag interactions and contextual menu actions.

## Private Folder Cards

- Private Thing Folder cards should still show the Folder's stored name even
  when private content is hidden. The card may keep a lock indicator and should
  continue to hide contained thumbnails/previews until the private content is
  authenticated.
- Private Thing Folder cards should not reveal child Thing or child Folder
  counts while private content is hidden. They should keep the Folder icon beside
  the title and show a private lock indicator below.

## Folder-Aware AppWidgets

- Folder-aware Things-list AppWidget configuration should match the Drawer
  structure for the scope picker. The Underway root row is selectable, has no
  trailing expand/collapse icon, and is always expanded to show the first Folder
  level beneath it.
- In the Things-list AppWidget configuration picker, Folder row bodies select
  that Folder as the widget scope. Only the row's trailing expand/collapse
  affordance toggles child Folder visibility.
- Things-list AppWidget display mode should use ordinary radio controls for
  List versus Grid.
- The Things-list AppWidget transparency slider controls individual content
  card backgrounds. The existing "top bar transparent too" option is the switch
  that applies the same transparency to the header.
- Folder-scoped Things-list AppWidgets show direct child Things plus direct
  child Folder summary cards. They should not recursively flatten descendant
  Things into the current widget list.
- In Things-list AppWidgets, direct child Folders render as summary Folder Cards
  rather than thumbnail/large Folder previews.
- The Things-list AppWidget configuration Folder picker should mirror Drawer
  private Folder iconography: a private Folder uses the same Folder icon with
  an embedded lock, while effectively-private descendants still authenticate
  through their private ancestor without adding a separate lock to their own
  icon.
- The Things-list AppWidget configuration Folder picker should keep a slightly
  larger gap between each Folder icon and title, and trailing expand/collapse
  affordances should use the same circular App Chrome ripple treatment as the
  Drawer instead of a rectangular row ripple.
- Creating a Thing from a Folder-scoped Things-list AppWidget should preserve
  that target Folder when returning to the home list after creation, so the app
  shows the Folder projection rather than root. The create flow should still
  avoid forcing the Thing type from the widget's type filter.
- That Folder-scoped AppWidget create-return should refresh through the Folder
  projection path only. Because projection opening already reloads the list, it
  should not also run the ordinary same-list created-card insertion animation or
  `notifyItemInserted` path.
- That same projection refresh may still play the ordinary Things appearing
  animation, because the duplicate-card risk comes from a second adapter insert
  notification rather than from the rebind animation itself.

## Position Naming

- In Thing/Folder home-list code, use `thingIndex` only for the pure
  `ThingManager.getThings()` index and `listPosition` only for the mixed
  RecyclerView adapter position backed by `ThingListEntry`.
- Use qualified names such as `oldListPosition`, `newListPosition`,
  `sourceOldListPosition`, and `targetListPosition` when the value is a mixed
  list position captured before or after a list mutation.
- Avoid ambiguous local names such as `position`, `thingPos`, or
  `adapterPosition` in home-list code when both Thing indices and mixed-list
  positions are in scope. Local adapters with their own independent data sets
  may continue to use ordinary `position` or `pos` names.

## Drag Commit Animation

- When dropping a Thing Card or Thing Folder Card onto an existing Thing Folder
  Card, the dragged overlay should not visually return to its original list
  position or fly into the target Folder Card. It should shrink in place toward
  its own top-left corner until `scaleX/scaleY=0`, while RecyclerView keeps the
  normal targeted removal and gap-closing item animations for the remaining
  cards.
- When dropping a Thing Card onto another Thing Card to create a new Thing
  Folder, the dragged card should not visually return to its original list
  position. It should visually merge with the target card, the new Folder Card
  should appear at the target position, and the list should close the gap left
  by the removed source Thing.
- Prefer targeted RecyclerView item updates and removals over replaying the
  whole list appearing animation after a successful drag commit.
- Overlay reorder should preserve RecyclerView's own item move/re-layout
  animation for the other cards. Do not replace the reorder commit path with an
  immediate full-list refresh merely to get a final target rect; the overlay
  should synchronize with RecyclerView's final arrangement animation and use
  the same move duration rather than playing after the list has already
  settled.
- Pending create-folder outlines apply only when dragging one Thing Card onto
  another Thing Card. The target Thing Card should shrink, and the pending
  Folder outline should sit outside it with a visible gap. The outline colour
  uses the pending Folder background.
- Pending create-folder outlines should be drawn behind RecyclerView child
  cards, not in the RecyclerView overlay layer. The dragged Thing Card should
  visually occlude the outline when it passes over it.
- Pending create-folder outline entrance and exit should both be animated.
- Pending create-folder outline bounds should stay fixed relative to the target
  Thing Card's unscaled layout center during the entrance/exit animation. The
  outline should follow the target card's current RecyclerView-local layout
  position while the list scrolls, but the animation should change stroke
  progress, not recalculate geometry from the target card's current
  `scaleX/scaleY`.
- Dragging a Thing Card onto an existing Folder Card uses Folder-card-specific
  feedback instead of the create-folder outline. Summary-mode Folder Cards
  shrink. Thumbnail-mode Folder Cards also shrink and animate to a thicker
  outline.
- The visible gap between a pending create-Folder outline and the shrunken
  target Thing Card should be a fixed visual distance, not a percentage of the
  target card width or height.
- Drag-to-create and drag-into-folder detection should not arm immediately on
  the first geometric hit. The candidate must remain stable briefly so the
  Folder feedback does not fight RecyclerView's drag gap-filling/reorder
  animation.
- Folder-drop hover frames must not update ordinary reorder bookkeeping such
  as the final moved range. Only frames that actually move the mixed list via
  `notifyItemMoved(...)` may affect reorder persistence.
- Folder-drop target cleanup should restore every Folder/Thing card that was
  highlighted during the current drag, not only the latest target position.
  RecyclerView gap-filling can move or recycle target holders while drag
  feedback is active.
- Restoring Folder-drop target feedback should remain animated. Multi-target
  cleanup may be broad, but card scale and thumbnail outline width should
  animate back to their normal values rather than snapping immediately.
- Folder-drop target restore animations must synchronise final visual state
  when the latest active animation ends normally or is canceled without being
  superseded. `withEndAction(...)` covers the normal end path only, so cancel
  listeners also need a guarded final-state sync.
- Canceled Folder-drop animations must not write an obsolete target value after
  a newer highlight/restore animation has taken over the same property. Use a
  per-target animation token (or equivalent generation guard) so interruption
  retargets from the current visual value instead of jumping to the canceled
  animation's old final value.
- Successful Folder drops that exit Moving mode without an immediate full-list
  refresh must still rebind the home list after the merge/removal animation
  completes. This restores every visible card from Moving-mode dimmed
  selection colours to normal colours without breaking the targeted drop
  animation.
- The Moving-mode exit full-list rebind after a successful Folder drop must not
  run while RecyclerView still has pending adapter updates, a requested layout,
  or running item animations. It may run after `ItemAnimator` reports that the
  targeted removal/move animations have finished.

## Folder Card Layout

- In the Folder Card appearance panel, label the display selector as "Folder
  size" rather than "Display mode". The two options should read "Normal" and
  "Large" in English, and `正常` / `大` in Chinese.
- The Folder size label and its Normal/Large options should appear on the same
  row, matching the compact Card width row. Do not use the Thing media-position
  two-line layout for this Folder-only control.
- Folder Cards should use a compact header: a small folder icon in the top-left,
  the folder name to its right, and the first line of the name vertically
  centred with the icon.
- The Folder Card title should sit slightly lower than the icon baseline; the
  current preferred offset is 1dp from the top of the header row.
- Folder names on Folder Cards may use up to two lines.
- The recursive count appears below the name as a short thing-count label, for
  example `X件记事` in Chinese.
- When a Folder Card has direct child Folders that match the current
  projection, the count label should combine the direct child Folder count and
  the recursive matching Thing count, for example `X个文件夹，Y件记事` in
  Chinese. Omit either segment when its count is zero.
- The recursive count text aligns with the Folder icon's left edge, not with
  the Folder title column.
- The recursive count text should be visually aligned with the Folder icon's
  left edge. Because glyph ink can look slightly left of the layout start, keep
  the count text 2dp to the right of the icon's layout start.
- The recursive count text should use the same normal small count size as
  ordinary Thing Card media or audio count labels.
- Folder Card recursive count text should use the same tertiary hint colour
  tier as ordinary Thing Card audio and hidden media count labels, while still
  choosing the dark or light side from the rendered card foreground base.

## Folder Naming Dialog

- Folder creation and rename flows should use the app's custom DialogFragment
  styling instead of platform-default AlertDialog surfaces.
- Folder naming dialogs should adapt their title, EditText accent, and confirm
  button to the Folder background, including both solid colors and gradients.
  The EditText focus treatment should match the DateTime reminder dialog:
  underline, selected-text background, and selected/focused text color all
  follow the Folder background.
- Folder naming dialog keyboard visibility should be driven directly through
  `WindowCompat.getInsetsController(...).show/hide(WindowInsetsCompat.Type.ime())`
  on the dialog window. Do not rely on `InputMethodManager` fallbacks,
  `SOFT_INPUT_STATE_ALWAYS_VISIBLE`, or delayed dismiss workarounds for this
  dialog.
- Canceling the naming dialog opened after creating a new Folder means canceling
  Folder creation itself: the source Things return to their previous parent
  folder/list state instead of keeping an unnamed/default-named Folder.
- Canceling Folder creation after a Thing-to-Thing drop should restore both
  source Things to their pre-creation parent Folder and mixed-list locations,
  not insert them at the top of the parent Folder or root list.

## Folder Card Appearance Panel

- The Folder Card appearance UI is an in-Activity bottom panel included from
  `panel_thing_card_appearance.xml`, not a `DialogFragment`.
- Its Folder name field is a standard XML `<EditText>` inflated as
  `android.widget.EditText`, not a custom project input widget.
- Closing the appearance panel must hide the IME through the shared
  `KeyboardUtil` before setting the panel to `GONE`, because no dialog dismiss
  lifecycle exists for this bottom panel.

## Folder Card Interaction Polish

- In selecting and moving modes, unselected summary-mode Folder Cards should
  wash out their Folder background the same way unselected Thing Cards do.
- During an active drag over a thumbnail-mode Folder Card drop target, the
  Folder Card should restore its dimmed selecting/moving colours in sync with
  the existing target scale/outline animation. When the drag leaves that target,
  it should return to the dimmed unselected state using the same timing.
- During an active drag over a summary-mode Folder Card drop target, the Folder
  Card should also restore both its background and content alpha from the
  dimmed selecting/moving state. When the drag leaves that target, both should
  return to the dimmed unselected state using the same timing.

## Thumbnail Folder Card Surface

- Thumbnail-mode Folder Cards in the list should keep the same normal and
  dragging `CardView` elevation as ordinary Thing Cards. Because the true
  transparent outside-only shadow implementation was too expensive during
  scrolling and dragging, the Folder Card surface should fill its otherwise
  empty interior with a muted version of the Folder background to cover the
  inner half of native elevation shadow. This muted surface should stay very
  close to the home/list background in both light and dark mode while carrying
  a small amount of the Folder's pure colour or gradient.
- Thumbnail-mode Folder Card drag overlays should still look lifted with native
  View elevation. Use expanded overlay bounds and an inset rounded `Outline`
  for the real card content rect, then draw the same muted Folder-background
  surface inside that content rect before drawing the captured bitmap so any
  internal native elevation shadow is covered.
- Do not use a RecyclerView `ItemDecoration`, `MaterialShapeDrawable` compat
  shadow, `clipPath`, or per-frame outside-only shadow mask for thumbnail-mode
  Folder Cards; that path is not smooth enough on real devices.
- Ordinary Thing Cards, summary-mode Folder Cards, and thumbnail-mode Folder
  Cards should all use the native `CardView` / View elevation path for the
  visible outer shadow.
- Very tall thumbnail-mode Folder Card drag overlays must continue rendering
  their bitmap content. Avoid whole-overlay software layers, full-size
  `saveLayer(...)` cleanup, or single oversized texture uploads for the drag
  visual.
- Thumbnail-mode Folder Card title, icon, and count colours should contrast
  against the home/list background, not against the folder background. In light
  mode this means dark foreground content on the grey-white background; in dark
  mode this means light foreground content.
- The transparent thumbnail-mode surface must remain stable after scrolling
  away and back, so ViewHolder recycling must explicitly clear any summary-mode
  card background state.
- Thumbnail-mode Folder Card previews must not invent a title for a Thing whose
  stored title is empty. In that case the preview should show the available
  content, checklist, or media surfaces according to the same rules as ordinary
  Thing Card presentation.
- Thumbnail-mode Folder Card previews should support ordinary Thing Card
  preview types, including text content, checklist content, image Thing Card
  Media, and video Thing Card Media.
- Thumbnail-mode Folder Card previews should also include direct child Folder
  Cards. Child Folder previews render as summary Folder Cards even when that
  child Folder normally uses thumbnail mode.
- Tapping a child Folder preview opens that Folder, following the same privacy
  authentication rules as tapping the ordinary Folder Card.
- Thumbnail previews represent direct child entries of the Folder rather than
  flattening all recursive descendant Things. The bottom ellipsis means there
  are more direct preview entries not rendered in the constrained preview area.
- Thumbnail-mode Folder Card child previews should stay close to complete Thing
  Card presentation while stripping interactive behaviours except opening the
  child Thing. Checklist toggles, long-press actions, selection, dragging, and
  other nested card interactions should not be active inside Folder Card
  previews.
- Child previews should strictly reuse Thing Card Media presentation for Things
  with image or video media, including the selected Thing Card Media Source,
  crop, target aspect ratio, video frame, image placement, media background
  presentation, and full-span Thing Card presentation where applicable.
- In full-span thumbnail-mode Folder Cards, a full-span child Thing Card preview
  should also span the full preview width instead of being squeezed into one
  masonry column.
- In normal-span thumbnail-mode Folder Cards, a full-span child Thing Card
  preview cannot become wider than the Folder Card, but it should still keep
  full-span Thing Card internal presentation within that one-column preview.
- Child previews should avoid hard clipping. Their height should be controlled
  by preview-specific Thing Card constraints such as text max-lines, smaller
  typography, checklist item limits, simplified Habit detail, and media target
  sizing. Media-heavy Things may use more vertical space than text-only child
  previews.
- Thumbnail child previews should apply the compact scale to the whole rendered
  preview card, not only to body content. Folder and Thing preview titles,
  folder icons, checklist row text and icons, media/audio count labels and
  icons, reminder/habit/goal timing labels and icons, private/sticky/doing
  indicators, and compound drawables should all read as smaller than ordinary
  list cards.
- Thumbnail child previews should also compact their internal padding and
  margins before visual scaling, while preserving actual Thing Card Media
  surfaces edge-to-edge inside their own card. Side media panels and media
  backgrounds should not be visually shrunk as if they were icons. Fixed
  vertical spacer views such as the Thing Card bottom padding spacer should be
  compacted with the same spacing scale, so bottom whitespace does not become
  visibly larger or smaller than the top spacing after thumbnail scaling.
- Thing Card Media container margins, such as the top margin between text and a
  bottom media thumbnail, should still be compacted. Only the actual media
  `ImageView`/mask dimensions are protected from icon scaling.
- Text-only child previews should keep the ordinary Thing Card dynamic content
  text-size relationship, then clamp it to thumbnail-safe bounds. Short content
  should remain larger than long content inside Folder thumbnails.
- Thumbnail child previews should reapply Thing Card Media crop after the
  preview card has been compacted and measured. Side media panels must use
  `ThingCardSideMediaCrop`, foreground thumbnails must use
  `ThingCardThumbnailCrop`, and media backgrounds must use
  `ThingCardMediaBackgroundCrop`.
- Thumbnail child previews should share the parent Thing list's Thing Card
  Media bitmap cache instead of creating an isolated cache for each child
  preview adapter. Scrolling a Folder Card with media-heavy child previews
  should not repeatedly show loading spinners for already-rendered card media.
- Special Thing-type presentation may still be simplified when the full
  surface would be too dense for a constrained child preview, such as Habit
  status details.
- Habit child previews should keep the core Habit summary but may omit dense
  record details such as the last-five-record surface inside constrained Folder
  Card previews.
- In normal-span Folder Cards, thumbnail previews should render in one column,
  show at most three child Thing previews, and show a bottom ellipsis when more
  matching descendants exist.
- In full-span Folder Cards, thumbnail previews should render in three columns
  using the same masonry-style layout as the home Thing list, show at most six
  child Thing previews, and show a bottom ellipsis when more matching
  descendants exist.
- 大屏或横屏时，全宽大 Folder Card 内部的缩略图列数不应永久固定为
  3 列；后续实现应根据当前可用宽度响应式增加列数，同时保持普通宽度
  Folder Card 的一列缩略图布局。
- The bottom ellipsis in thumbnail-mode Folder Cards is a small Folder-open
  target, visually closer to the compact checklist "more items" ellipsis than
  to a large button. It should not take excessive vertical space.
- The bottom ellipsis should not reserve extra bottom margin; keep its footprint
  as close as practical to the glyph itself.
- Thumbnail-mode Folder Cards should use a consistent 12dp gap between the
  Folder count label and the first child preview, regardless of normal-span or
  full-span Folder width.
- Child previews inside thumbnail-mode Folder Cards should use a 7dp vertical
  item gap in both one-column normal-span previews and full-span masonry
  previews. Full-span children and masonry column rows should not create an
  additional doubled first-child top margin.
- Each child Thing preview inside a thumbnail-mode Folder Card may have an
  independent height, but it should stay compact through content constraints
  rather than by clipping the final rendered preview.
- Child Thing and child Folder preview cards use reduced elevation compared
  with ordinary list cards so their shadows do not look clipped inside the
  existing thumbnail preview spacing.
- Thumbnail preview containers should allow child card shadow overflow by
  disabling parent clipping, instead of reducing child preview elevation
  further.
- Thumbnail child previews must preserve saved Thing Card Media crop geometry,
  including center, user scale, and target/source aspect ratio. This applies to
  foreground thumbnails, side-panel media, and media-background cards, including
  video frame previews.

## Private Folder Polish

- Private Folder Cards should always render as normal-span summary-mode Folder
  Cards, regardless of any previously stored presentation. The Folder Card
  appearance panel for a private Folder should expose only the rename field.
- The Folder Card appearance panel name field should show an underline so the
  editable affordance is visible.
- The hidden-private Folder Card lock spacing should match the hidden-private
  Thing Card lock spacing. If only the bottom space looks wrong, inspect
  recycled bottom spacer and padding views before changing the lock ImageView's
  top margin.
- Hidden-private Folder Cards must hide ordinary content/status surfaces such
  as `tv_thing_content`, checklist, audio, reminder, habit, media-count, and
  inline-media views when showing the lock. Otherwise recycled Folder Card
  holder state can leave an invisible-looking content slot participating in
  measurement below the private lock.
- Hidden-private Folder Cards should explicitly reset the lock ImageView to the
  normal hidden-private Thing Card geometry: 48dp icon size and 16dp bottom
  spacer. They must not inherit a full-span private Thing icon size from a
  recycled holder.
- Drawer private Folder icons should include a small adaptive lock inside the
  Folder icon. The lock foreground should contrast with the Folder's own colour
  or gradient, and the lock should remain subtle, small, and centered inside
  the Folder glyph.
- The Drawer should still show the expand/collapse affordance for a private
  Folder that has child Folders, even when the private subtree is currently
  hidden. Tapping that affordance requires password or fingerprint verification
  before the subtree is expanded.
- Private Folder expansion authentication in both the Drawer and move-to-Folder
  dialog is transient to that surface. Dismissing the dialog or closing the
  Drawer resets the expansion authorization and collapses private subtrees
  outside the current private Folder path. If the current projection is already
  inside that private Folder, expansion does not ask again.
- Dragging a Thing Card or Folder Card onto a private Folder Card should be
  allowed and should use the same Folder-drop activation and merge animation as
  non-private target Folders. Opening or viewing the private Folder's contents
  remains protected by the normal authentication flow.

## In-Folder Creation

- Creating a new Thing while viewing a Folder projection should keep the Drawer
  selected on that current Folder row instead of switching selection to the
  Underway root.

## Folder-Aware AppWidgets

- Prefer merging the proposed dedicated Folder AppWidget into the existing
  Things-list AppWidget family instead of adding a separate launcher widget
  type.
- The Things-list AppWidget should be able to target either a built-in root
  projection or a selected Thing Folder projection.
- If Grid-style collection rendering is viable for AppWidgets, the
  Things-list AppWidget should add a Grid display option so Folder-targeted
  widgets can show internal content in a folder-like grid rather than only as a
  vertical list.
- The existing settings button in the Things-list AppWidget header should
  remain the way users reconfigure that widget. It should let users change the
  target projection, including the selected Thing Folder, and switch between
  List and Grid display modes.
- Folder-targeted Things-list AppWidgets should keep the same built-in type
  filters as root widgets. The configuration model should separate target
  scope, built-in type filter, and display mode so a widget can show, for
  example, all Things in a Folder or only Reminders in that Folder.
- Grid-mode Things-list AppWidgets should preserve high-information Thing Card
  rendering as much as RemoteViews supports, instead of reducing every Thing to
  a minimal summary tile. RemoteViews constraints may require widget-specific
  approximations, but the target is still a rich Thing Card projection.
- Thing Folder entries inside Things-list AppWidgets, including Grid mode and
  Folder-targeted widgets, should render as summary Folder Cards rather than
  trying to reproduce the in-app large thumbnail Folder Card.
- Summary Folder Cards inside Things-list AppWidgets should use the same count
  semantics as home Folder Cards: direct child Folder count plus recursive
  matching Thing count, omitting zero segments. Hidden private Folder content
  should not reveal child counts while protected.
- If a Things-list AppWidget's configured Thing Folder target becomes invalid
  because the Folder is deleted, permanently deleted, dissolved, or otherwise
  unavailable to the Underway widget projection, the widget should fall back to
  root scope while preserving its type filter, List/Grid display mode, alpha,
  and style settings.
- A Things-list AppWidget configured to target a private Thing Folder may show
  that Folder's stored name and Folder-coloured header after the user has
  authenticated during configuration. Its content still uses protected private
  presentation, and opening the private Folder from the widget still requires
  authentication.
- Launcher-visible Things-list AppWidget names should become generic, such as
  `Things list 3x3` / `记事列表 3x3`, rather than `Underway things`, because
  Folder scope, Grid mode, and type filters are configured per widget instance.
- The existing Things-list AppWidget simple-view option should remain available
  in both List and Grid display modes. It controls Thing Card content density,
  not the List/Grid layout choice. Summary Folder Cards are already compact and
  do not need separate simple-view behavior.
- In Grid mode, each card slot inside a row should own its click fill-in
  intent. The row is only a RemoteViews container and should not handle item
  clicks itself. Thing slots open Detail; Folder slots open the app at that
  Folder projection.
- Grid rows should keep stable column widths by filling incomplete rows with
  transparent, non-clickable empty slots.
- The Things-list AppWidget header should keep the settings and create buttons
  in both List and Grid display modes.
- The Things-list AppWidget configuration does not need a live widget preview in
  this slice.
- The List/Grid display-mode choice can use ordinary radio controls.
- Tapping a Thing Folder entry inside a desktop Things-list AppWidget should
  open the app at that Thing Folder projection. The desktop AppWidget itself
  should not navigate internally into the Folder or maintain its own back stack.
- Tapping the Things-list AppWidget header should open the app at the widget's
  full configured projection, including both the target scope and the built-in
  type filter. The header settings button remains the separate configuration
  entry point.
- Things-list AppWidget privacy behavior should match the home list while
  staying conservative on the desktop surface. Private Thing Folder entries
  show the stored Folder name, Folder icon, and lock affordance, but do not
  reveal child previews. Private Things and Things under a private ancestor
  should use the existing protected Thing widget presentation. Opening or
  selecting private Folder projections should require the existing Folder
  authentication flow.
- The first folder-aware Things-list AppWidget slice should keep state fixed to
  Underway. It should not add Finished or Deleted projection choices to the
  widget configuration yet.
- Single-Thing AppWidget configuration remains Thing-only for selection. It
  should show Thing Folder rows so users can browse into Folders, but tapping a
  Folder row navigates into that Folder instead of selecting the Folder as the
  widget target.
- Inside Single-Thing AppWidget configuration, each Folder projection should
  show child Thing Folders and direct Things in the same mixed order as the home
  list. Only Thing rows are selectable widget targets; Folder rows are
  navigation entries.
- Single-Thing AppWidget configuration should require existing private Folder
  authentication before entering a private Folder. After authentication, Things
  inside that Folder can be selected, while the placed single-Thing AppWidget
  continues to use the existing protected presentation for private Things.
- Single-Thing AppWidget configuration should keep its existing Underway-only
  scope. Folder navigation in that configuration is limited to Underway
  projections and should not add Finished or Deleted selection.
- Single-Thing AppWidget configuration should keep the root title as the
  existing "select one Thing" title. Inside a Folder projection, the toolbar
  title shows the current Folder name. The navigation icon and system Back
  return to the parent Folder while inside a Folder, and exit the configuration
  only from root.
- Single-Thing AppWidget configuration should present Folder navigation entries
  as home-style Folder Cards in the same mixed card list as Thing Cards, not as
  Drawer-style rows.
- Things-list AppWidget configuration is a separate type/style configuration
  surface today, not the single-Thing card picker. Folder target selection for
  list widgets needs its own configuration control instead of reusing the
  single-Thing picker semantics wholesale.
- The Things-list AppWidget configuration should prefer a Drawer-like or
  move-to-Folder-dialog-like scrollable projection picker instead of a separate
  lightweight "scope" row. The picker can show the Underway root with Folder
  rows beneath it, plus sibling built-in type rows such as Note, Reminder,
  Habit, and Goal. List/Grid display mode and transparency controls should
  remain available below the scrollable picker.
- In the Things-list AppWidget configuration Folder picker, the Folder row body
  selects that Folder as the widget scope, while the trailing expand/collapse
  icon reveals or hides child Folders. The root Underway scope is also
  selectable. This differs from Single-Thing AppWidget configuration, where
  tapping a Folder row navigates into the Folder.
- The Things-list AppWidget configuration Folder picker should show the first
  Folder level by default. Deeper levels appear only after expanding their
  parent. When editing an existing widget scoped to a nested Folder, the picker
  should expand that Folder's ancestor path and select the configured Folder.
- The first Things-list AppWidget Folder picker implementation does not need
  search. A scrollable expandable tree is sufficient for this slice.
- The Underway root row in the Things-list AppWidget configuration picker
  should be selectable and should not show a trailing expand/collapse icon. The
  first Folder level is always visible beneath it, matching the Drawer model.
- The Things-list AppWidget configuration should keep Folder scope selection in
  the scrollable projection picker, while type filtering is a separate
  horizontal row of five icons: All, Note, Reminder, Habit, and Goal. The type
  icons support multi-select so users can combine specific Thing types such as
  Reminder and Habit. Selecting All is exclusive and clears the specific type
  selection.
- The Things-list AppWidget configuration's Folder scope picker should use the
  same top scroll-divider treatment as app chrome chooser dialogs: the divider
  below the title appears only after the picker can scroll upward. The bottom
  divider below the picker is a stable section boundary and should remain
  visible even when the picker is scrolled to the bottom.
- The five Things-list AppWidget type-filter icons should keep compact circular
  ripple and selected-state targets with 2dp spacing between neighbouring
  icons.
- Things-list AppWidget type filtering should always keep a valid selection.
  If the user deselects every specific type icon, the filter should return to
  All automatically rather than allowing an intentionally empty widget.
- Grid-mode Things-list AppWidgets should derive their column count from the
  widget width instead of exposing a separate column-count setting. Narrow
  widgets can use one column, medium widgets two columns, and wide widgets
  three columns, with later tuning based on launcher/device testing.
- Grid-mode Things-list AppWidgets should support full-span Thing Cards by
  following each Thing's existing Thing Card Span Mode. This likely requires a
  row-oriented RemoteViews collection rather than a plain AppWidget `GridView`,
  because individual `GridView` items do not reliably span multiple columns.
- Grid-mode rows should preserve content rather than clipping. When a row has
  multiple normal-span cards, each card keeps its own wrap-content height, the
  row takes the tallest card's height, and shorter cards stay top-aligned
  rather than being stretched.
- Grid-mode item packing should preserve the current mixed-list order. Normal
  cards fill the current row in order. A full-span card first closes any
  partially filled normal row, then occupies a full row by itself before normal
  row packing resumes.
- List-mode Things-list AppWidget entries should remain full-width rows. Thing
  and Folder span preferences affect Grid mode only.
- Folder-aware Things-list AppWidget configuration should use explicit stored
  fields instead of extending the legacy negative `thing_id` encoding. The
  stored configuration needs separate values for target Folder, selected type
  mask, and display mode while preserving existing alpha/style settings.
- Things-list AppWidget ordering should follow the same mixed Thing/Folder
  ordering as the home projection, including sticky items. List and Grid display
  modes change layout only, not item order.
- The Things-list AppWidget configuration Folder picker should show all
  non-deleted Thing Folders rather than filtering the Folder tree by the current
  type icon selection. Type filters can change independently after a Folder is
  selected.
- The create button in a Things-list AppWidget should not force the new Thing
  type from the widget's type filter. Creation should use the existing Detail
  create flow, where the Thing's final type is determined by the reminder time,
  repeat settings, and other fields the user sets while creating it.
- When a Things-list AppWidget targets a Thing Folder, its create button should
  create the new Thing inside that Folder scope. The type filter still does not
  force the Thing type.
- Things-list AppWidget header titles should show the configured scope and type
  filters directly. Root + All shows the Underway title. Root + multiple
  specific types joins the type names with `/`, such as `Reminder/Habit`.
  Folder + All shows the Folder name. Folder + specific type filters shows
  `Folder name · Reminder/Habit` using the selected type names.
- Things-list AppWidget headers should use the app accent for root scopes and
  the selected Thing Folder's pure colour or gradient for Folder scopes. Header
  foreground should adapt to the header background, and the existing header
  transparency setting should still apply.
- The Things-list AppWidget transparency slider primarily controls each Thing
  card's own background transparency. The existing "make the top bar
  transparent too" option applies that same transparency to the widget header;
  without that option, the header remains opaque.
- The transparency slider should also apply to Folder summary cards in
  Things-list AppWidgets, because they are content cards like Thing items.
  Header transparency remains controlled by the existing top-bar transparency
  option.
- Existing Things-list AppWidget configuration state should map legacy Underway
  to All, legacy single type limits to the matching specific type icon, and new
  widgets to root scope, All, List mode, and the existing default alpha/style
  settings.
- Type icon ordering is fixed as All, Note, Reminder, Habit, and Goal. Header
  type-name joining uses this fixed order, not click order.
- A Folder-scoped Things-list AppWidget shows direct child Things and direct
  child Folder summary cards. It does not recursively flatten descendants.
- Things-list AppWidget item interactions are click-only. Folder and Thing rows
  do not provide widget-level long-press menus or card actions.
- Single-Thing AppWidget configuration must render selectable Thing cards
  through the same home-list Thing Card binding wherever possible. Folder
  browsing in that configuration should not use a visually approximate Thing
  card path; private content revealed by authenticated Folder navigation,
  foreground media, side/top/bottom media placement, media backgrounds, video
  covers, and saved crop geometry should match the home list.
- AppWidget alpha controls should affect media-backed Thing Cards as well as
  pure-colour and gradient cards. Foreground thumbnails, side media panels, and
  media backgrounds should all be composited with the configured widget alpha
  so media Things do not remain visually opaque while the card background is
  translucent.
- Things-list AppWidget grid rows must route taps to the exact Thing or Folder
  cell tapped. A row containing multiple cells must not use the first cell's
  fill-in intent for every click target.
- A 4x4 Things-list AppWidget should use two grid columns in Grid mode rather
  than three.
- Single-Thing AppWidgets should not expose the legacy inline finish action.
  The widget should open the Thing for action instead of showing a bottom
  completion button and its separator.
- Things-list AppWidget Folder summary cards should choose icon, title, lock,
  and count colours using the same luminance tiers as home summary Folder
  Cards: primary foreground for icon/title, secondary for lock, and tertiary
  for counts.
