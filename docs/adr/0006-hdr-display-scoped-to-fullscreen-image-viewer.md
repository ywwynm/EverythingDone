# HDR Display is scoped to the full-screen image viewer only

We add **HDR Display** for **HDR Media** images only in the full-screen image
viewer (`ImageViewerActivity` + PhotoView), and deliberately keep every other
media surface — the home card list, detail attachment list, folder thumbnails,
crop editor, and app widgets — on the SDR base image.

## Why

HDR still rendering requires three things at once (Android 14 / API 34+): the
image's gain map, a window in `COLOR_MODE_HDR`, and a hardware-accelerated
Canvas drawing the gain-map bitmap. Two project realities make the non-viewer
surfaces a bad fit:

- **The crop/transform pipeline destroys HDR.** Card list, detail attachments,
  and folder thumbnails all render through `MediaCropBitmapRenderer`'s software
  Canvas (or Glide transforms), which composite only the SDR base and drop the
  gain map. HDR there would require re-architecting that pipeline to a
  gain-map-preserving hardware path.
- **`COLOR_MODE_HDR` dims surrounding SDR content.** Those surfaces live in
  windows full of the app's colourful Thing cards (the product's core identity).
  Switching such a window to HDR to boost one thumbnail would dim every colourful
  card — a visual regression. Google also advises against many HDR images in a
  scrolling list (brightness thrash, doubled memory).

The full-screen viewer has none of these problems: it is a single focused image
(Google's recommended HDR pattern), PhotoView is already a hardware ImageView,
and its window can switch to HDR without dimming any card list.

App widgets are excluded because RemoteViews render in the launcher process and
cannot enter an HDR window at all.

## Consequences

- A future reader who expects HDR on cards should not "fix" it by adding
  `COLOR_MODE_HDR` to the home/detail windows — that would dim the colour
  identity. Expanding scope means first replacing the software crop pipeline with
  a gain-map-preserving hardware path and a per-surface headroom strategy.
- The feature only activates on API 34+ with an HDR-capable display; everything
  degrades automatically to the SDR base elsewhere.
