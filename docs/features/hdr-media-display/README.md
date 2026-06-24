# HDR Media Display

Render HDR images at their full brightness in the full-screen image viewer,
auto-detecting the gain map and exposing a tappable HDR badge.

## Status

- **Phase**: implemented and verified on device (2026-06-24).
  - HDR full-screen display: **verified** by the user (boost + badge render;
    gain map survives Glide).
  - Video-player launch crash: **fixed** (root cause was
    `FileProvider.getUriForFile` on an uncovered public path, not
    `startActivity`). Published debug `202606240927`.
- **Scope**: full-screen image viewer only. HDR still images only. Bundled
  side-fix: external video-player launch crash.
- **Follow-up**: some video attachments reference an original public path
  (e.g. `DCIM/Camera/...`) rather than an app-private copy — see
  [followups.md](followups.md).

## Document map

- [plan.md](plan.md) — intent, scope, decisions, approach, risks, acceptance.

## Related global docs

- ADR [0006-hdr-display-scoped-to-fullscreen-image-viewer](../../adr/0006-hdr-display-scoped-to-fullscreen-image-viewer.md)
- `CONTEXT.md` — terms **HDR Media**, **HDR Display**.

## Key facts (from research + code, 2026-06-24)

- App: `minSdk 26`, `target/compileSdk 36`, Glide 4.16.0, PhotoView 2.3.0.
  No Coil / Media3 / ExoPlayer.
- HDR still display requires **API 34+**, a window in `COLOR_MODE_HDR`, and a
  **hardware-accelerated** Canvas drawing a gain-map `Bitmap`. Software Canvas
  drops the gain map.
- Import path (`FileUtil.copyUriToFile` / `copyUriToExistingFile`) is a raw
  byte copy, so the gain map is preserved on disk.
- Home-screen widgets (RemoteViews) cannot show HDR — SDR base only.
- `MediaMetadataRetriever` frames are always SDR — every video thumbnail is SDR.
