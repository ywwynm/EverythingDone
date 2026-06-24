# HDR Media Display — Sessions

## 2026-06-24 — Design grilled, implemented, published

- grill-with-docs session resolved scope to the full-screen image viewer only
  (images), with a tappable auto-on HDR badge; video kept on external player
  plus a crash fix. Captured in `CONTEXT.md` (HDR Media / HDR Display), ADR 0006,
  plan.md, execution.md.
- Implemented in `ImageViewerActivity` (gain-map detection via `asBitmap` +
  `hasGainmap`, per-page `window.colorMode` on API 34+, HDR badge overlay,
  video-launch crash fix) plus badge drawables and strings.
- `:app:assembleDebug` clean; published debug `202606240907` to Aliyun for
  remote testing (not installed on the physical device).
- **Verified on device**: HDR boost + badge render correctly (gain map survives
  Glide). A second iteration root-caused the video-play crash to
  `FileProvider.getUriForFile` on an uncovered public path (`DCIM/Camera/...`),
  not `startActivity`; fixed by covering the external-storage root in
  `file_provider_paths.xml` and guarding `getUriForFile` too. Published debug
  `202606240927`. Discovered that some video attachments reference an original
  public path rather than an app-private copy — see followups.md.

Key files: `activities/ImageViewerActivity.kt`,
`res/layout/activity_image_viewer.xml`, `res/drawable/bg_hdr_badge_on.xml`,
`res/drawable/bg_hdr_badge_off.xml`, `res/values*/strings.xml`.
