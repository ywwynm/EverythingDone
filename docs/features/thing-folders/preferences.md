# Thing Folders Preferences

## Navigation Semantics

- The user confirmed that Thing Folders should not appear as drawer items. The
  drawer should remain focused on built-in destinations such as Notes,
  Reminders, Habits, Goals, Finished, and Deleted.
- Folder navigation should happen through Folder Cards and clickable header
  path segments within the active built-in destination projection.

## Move Semantics

- When a Thing or Thing Folder is moved into a different Thing Folder, moved
  back to its previous parent, or moved back to root, it should not preserve its
  old relative order. The moved entry should become the first item in the target
  root or target Folder's corresponding sticky or non-sticky section, preserving
  the entry's existing sticky state.
- When a Thing Folder becomes empty after moving Things or child Folders out of
  it, delete that empty Folder automatically.
- Long-pressing a Folder Card should enter the same drag/select affordance as
  long-pressing a Thing Card. A Folder can be dragged into another Folder, and a
  release that returns to the original position should enter selecting mode
  instead of opening a Folder action dialog.
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

- When dropping a Thing Card onto an existing Thing Folder Card, the dragged
  card should not visually return to its original list position. It should
  animate from the released position into the Folder Card, then the Folder Card
  should update and the list should play the normal gap-closing item removal
  animation for the dropped Thing.
- When dropping a Thing Card onto another Thing Card to create a new Thing
  Folder, the dragged card should not visually return to its original list
  position. It should visually merge with the target card, the new Folder Card
  should appear at the target position, and the list should close the gap left
  by the removed source Thing.
- Prefer targeted RecyclerView item updates and removals over replaying the
  whole list appearing animation after a successful drag commit.
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

## Folder Naming Dialog

- Folder creation and rename flows should use the app's custom DialogFragment
  styling instead of platform-default AlertDialog surfaces.
- Folder naming dialogs should adapt their title, EditText accent, and confirm
  button to the Folder background, including both solid colors and gradients.
  The EditText focus treatment should match the DateTime reminder dialog:
  underline, selected-text background, and selected/focused text color all
  follow the Folder background.
- Canceling the naming dialog opened after creating a new Folder means canceling
  Folder creation itself: the source Things return to their previous parent
  folder/list state instead of keeping an unnamed/default-named Folder.

## Thumbnail Folder Card Surface

- Thumbnail-mode Folder Cards should keep a truly transparent interior with
  only the outline using the folder background colour or gradient. They should
  not show a `CardView` elevation shadow or a stale solid folder-colour fill
  inside the outline.
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
