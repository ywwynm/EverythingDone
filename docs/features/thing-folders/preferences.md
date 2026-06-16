# Thing Folders Preferences

## Navigation Semantics

- The user confirmed that Thing Folders should not appear as drawer items. The
  drawer should remain focused on built-in destinations such as Notes,
  Reminders, Habits, Goals, Finished, and Deleted.
- Folder navigation should happen through Folder Cards and clickable header
  path segments within the active built-in destination projection.

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

- Folder Cards should use a compact header: a small folder icon in the top-left,
  the folder name to its right, and the first line of the name vertically
  centred with the icon.
- The Folder Card title should sit slightly lower than the icon baseline; the
  current preferred offset is 1dp from the top of the header row.
- Folder names on Folder Cards may use up to two lines.
- The recursive count appears below the name as a short thing-count label, for
  example `X件记事` in Chinese.
- The recursive count text aligns with the Folder icon's left edge, not with
  the Folder title column.
- The recursive count text should use the same normal small count size as
  ordinary Thing Card media or audio count labels.

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
