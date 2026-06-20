# Thing Card Appearance Preferences

## Doing Cover

- The currently-doing cover shown on Thing Cards and Things AppWidgets should
  use the same vector drawable based on the newer `vec_ic_start_thing` rocket
  language.
- The doing-cover vector should preserve the old `ic_doing_thing` intrinsic
  canvas size but use a larger visible glyph than the first replacement pass,
  so it does not read smaller than the legacy PNG.
- The doing-cover icon and `正在做` label should use a compact compound-drawable
  gap. Use the same 4dp drawable padding on the Thing Card and AppWidget
  layouts so the new vector does not sit too far from the text.
- The doing-cover icon may include a simplified matching exhaust shape below
  the rocket; keep it vector-based so it scales consistently with card and
  AppWidget presentation.

## Media Count Overlay

- When image/video count text is rendered on top of a black overlay for top,
  bottom, left, or right Thing Card media placement, keep that text and icon in
  the fixed light overlay style. Live colour preview in the Thing Card
  Appearance panel should not recolour this overlay count from the draft Thing
  background. Inline hidden-media counts that sit directly on the Thing Card
  foreground may continue to follow the adaptive Thing foreground colour.
