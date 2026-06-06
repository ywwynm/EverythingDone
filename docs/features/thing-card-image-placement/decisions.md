# Thing Card Image Placement Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-31 - Home Card Image Placement scope

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

## 2026-05-29 - Full-span home-card state belongs to the Thing

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
