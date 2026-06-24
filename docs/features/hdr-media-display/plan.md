# HDR Media Display — Plan

## Intent

Show **HDR Media** images at their true brightness when the user opens them in
the full-screen image viewer, so a photo captured in HDR looks the way the
camera intended instead of a flattened SDR base.

## Scope

### In scope
- **HDR Display in the full-screen image viewer only** (`ImageViewerActivity`
  + PhotoView). This is the surface that detail attachments, card media, etc.
  open into, so "view this photo in HDR" is served from every entry point.
- Auto-detect HDR via `Bitmap.hasGainmap()` (API 34+); render the current page
  in HDR when its image has a gain map.
- A tappable **HDR badge** (Google-Photos style) on HDR images; tapping it
  temporarily forces SDR for the current viewing of that page.
- Graceful degradation: on API < 34, non-HDR displays, or images without a gain
  map, show the SDR base with no badge and no dimming. No explicit fallback
  code needed — these are automatic.

### Bundled side-fix
- External video-player launch crash in `ImageViewerActivity.getVideoListener()`
  (`startActivity` for `ACTION_VIEW`). Likely an invalid MIME for non-`.mp4`
  extensions (`video/mov`) and/or a missing `ActivityNotFoundException` guard.
  Fix: use `video/*` (or map to a valid MIME), wrap `startActivity` in a guard
  (resolve / try-catch / chooser). Confirm the exact cause via logcat repro.

### Out of scope (deliberate — see ADR 0006)
- HDR on the card list, detail attachment list, folder thumbnails, crop editor.
  They route media through `MediaCropBitmapRenderer`'s software Canvas (gain map
  lost), and a list window in `COLOR_MODE_HDR` would dim the app's colourful
  Thing cards.
- App widgets — RemoteViews cannot render HDR.
- HDR video: no inline player today; frames are always SDR. External player keeps
  handling HDR playback after the crash fix.

## Decisions (locked 2026-06-24)

1. Surface scope = full-screen image viewer only.
2. Media type = still images only; video = crash fix + external player.
3. Activation = auto-on + tappable HDR badge; no global setting.
4. Window `COLOR_MODE_HDR` tracks the **current page**; reset to default on SDR
   pages and on exit.
5. Badge toggle is ephemeral — leaving and returning to a page restores HDR.
6. Badge visibility is content-based (`hasGainmap()`), independent of whether
   the display can currently boost.
7. Badge lives in the top chrome and shows/hides with the immersive system UI.

## Implementation approach

- **`ImageViewerActivity.loadImage()`**: switch to a Bitmap-bearing load
  (`asBitmap()` + `dontTransform()`, or read `(drawable as BitmapDrawable).bitmap`)
  so the gain map survives Glide and `hasGainmap()` is checkable. Avoid an
  `.override()` exact-rescale path that could re-draw through a software Canvas.
  Let PhotoView do the fit/zoom via its matrix.
- **Window color mode**: on page-settle (and initial show), set
  `window.colorMode = COLOR_MODE_HDR` when the current image has a gain map and
  is not user-forced to SDR; else `COLOR_MODE_DEFAULT`. Gate the constant on
  `Build.VERSION.SDK_INT >= 34`.
- **HDR badge**: add a small badge view to the viewer chrome
  (`ImageViewerActivity` layout / `ImageViewerPagerAdapter`); visible only for
  gain-map pages on API 34+. Tap toggles a per-page "force SDR" flag (ephemeral)
  and re-applies the window color mode.
- **Video crash fix**: in `getVideoListener()`, correct the MIME and guard
  `startActivity`.

## Risks / verification

- **Glide gain-map survival is unverified.** Before building the badge, prove the
  pipeline end-to-end: load a known UltraHDR file and log `bitmap.hasGainmap()`
  after Glide. Adjust the load (asBitmap / dontTransform) until it survives.
- **Visual confirmation needs real hardware.** Emulators report non-HDR
  displays. Verification is by publishing a debug update to Aliyun; the user
  tests on their own device (do not auto-install on the `BYZL…` device).
- Optionally add temporary diagnostic logging of `Display.isHdr()` /
  `getHdrSdrRatio()` to confirm the test device's HDR capability.

## Acceptance criteria

- API 34+ HDR display: opening an UltraHDR image renders with visible HDR boost;
  HDR badge shown; tapping the badge reverts to SDR; swiping to an SDR image
  removes the boost/dimming; returning to the HDR page restores HDR.
- API < 34 / non-HDR display / non-HDR image: SDR base shown, no badge, no
  dimming, no crash.
- Tapping a video opens an external player without crashing, including non-mp4
  extensions.
- Card list, detail attachment list, folder thumbnails, crop editor, and widgets
  are visually unchanged (still SDR).
