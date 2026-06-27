# Gradient Ripple — Decisions

## 2026-06-27 — A custom Drawable is required for a gradient ripple

Verified against AOSP `master` source (`RippleDrawable.updateRipplePaint()`): the ripple
paint colour comes from `mState.mColor.getColorForState(...)` — a single int — with
`setShader(null)` (Solid style). When a mask is set the shader is only a clip mask
(`setColor(color & 0xFF000000)` keeps alpha only); the API 31+ patterned style uses a
noise shader, still single-colour. `content` and `mask` layers only affect the backing
content and the clip shape, never the ripple's colour.

Therefore "the expanding ripple itself is a gradient" (form **B**, chosen by the user over
form **A** = a persistent gradient underlay + a plain semi-transparent ripple) is not
achievable with the platform `RippleDrawable` and needs a custom `Drawable`.

## Scope of colour and state

- Only the **unselected** state's ripple becomes a gradient. Selected controls already
  paint the Scope/record gradient as their fill, so their ripple stays a faint neutral
  (a gradient ripple would be invisible over the identical gradient).
- Colour source: drawer controls use the panel's `scopeBackground` (root = accent→accent2,
  folder = folder colour); recurrence buttons use the record's `mAccentBackground`
  (falling back to `ThingBackground.pure(mAccentColor)`).
- PURE and GRADIENT both flow through `GradientRippleDrawable` (PURE degrades to a solid
  expanding ripple) so the three sites behave identically and a null/pure record colour is
  a no-regression fallback.

## GradientRippleDrawable design

- Lives in the `views` package; mirrors the existing `DisplayUtil.SeekBarTrackDrawable`
  custom-Drawable style. Constructed directly at the call sites (no `BackgroundUtil`
  factory) to avoid a `utils → views` dependency.
- Draws a `LinearGradient` (via `BackgroundUtil.createLinearGradient`, which already maps
  the 8 orientations) as a circle expanding from a **press-locked origin**, clipped to OVAL
  or ROUND_RECT. The origin is captured on press (the platform sets the hotspot before
  `pressed` on ACTION_DOWN) and does **not** track finger movement afterwards, so sliding
  does not drag the ripple. Radius and alpha animate **independently**: the radius always
  runs to full and is **not** interrupted by release/cancel; only alpha fades out. So a
  quick release or a parent scroll-cancel still fills then fades rather than vanishing
  half-filled. Timing: radius 260ms (decelerate), alpha enter 60ms, alpha exit 300ms
  (radius slightly shorter than the fade so the filled state is briefly visible).
  `stopAnimations()` is called from `onDetachedFromWindow` / `onViewRecycled` to avoid leaks
  and recycle afterimages.
- `PEAK_ALPHA`: gradient ripples use vivid brand colours, so they need more than the
  existing ~10–16% neutral ripple to read clearly. Currently `0.36f` (first on-device test
  build); still subject to tuning (dark backgrounds may want lower).

## End-of-month pill and DisplayUtil hardening

- The "月末" pill is included. Its layout is a `CardView` carrying
  `style="@style/SelectableItemForeground"` (a `?attr/selectableItemBackground` foreground),
  with the label as a sibling overlay — there is no inner FrameLayout. Unselected →
  gradient ROUND_RECT ripple on `cv.foreground`; selected → a hand-built grey pill
  `RippleDrawable`. It no longer goes through `setRippleColorForCardView`.
- `DisplayUtil.setRippleColorForCardView` was hardened from an `as RippleDrawable` cast to
  an `is RippleDrawable` check, removing the `ClassCastException`/`NPE` risk once a
  foreground may be a non-RippleDrawable.
