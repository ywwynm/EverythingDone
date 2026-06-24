# HDR Media Display — Execution

Phased checklist for implementing the plan. Order is chosen so the riskiest
unknown (does the gain map survive Glide?) is proven before any UI is built, and
the independent video-crash fix lands first.

## Phase 1 — Video external-player crash fix (independent)

- [ ] `ImageViewerActivity.getVideoListener()`: replace MIME `"video/" + postfix`
      with `"video/*"` (avoids invalid `video/mov` etc.).
- [ ] Wrap `startActivity(intent)` in `try/catch (ActivityNotFoundException)`;
      on catch show a Toast (`image_viewer_no_video_player`). Do not swallow
      other exceptions.
- [ ] String `image_viewer_no_video_player` (default + zh-rCN/HK/TW).

## Phase 2 — HDR detection pipeline (prove gain map survival FIRST)

- [ ] `initMembers()`: allocate `mHasGainmap: BooleanArray` and
      `mForcedSdr: BooleanArray` sized to `mTypePathNames.size`.
- [ ] Rework `loadImage()` to `.asBitmap().dontTransform().disallowHardwareConfig()
      .override(w,h)` and take a `position` param; in `onResourceReady(bitmap)`
      set it on the PhotoView, then record
      `mHasGainmap[position] = Build.VERSION.SDK_INT >= 34 && bitmap.hasGainmap()`.
- [ ] Change the tab loop in `initUI()` to an indexed loop and pass `index`.
- [ ] **Verification gate**: add a temporary `Log` of `bitmap.hasGainmap()` after
      load; sideload (debug build), open a known UltraHDR image, confirm `true`.
      If `false`, adjust the load (drop `.override`, try `DownsampleStrategy`)
      until the gain map survives. Remove the log before publishing.

## Phase 3 — Window color mode per current page

- [ ] `applyHdrStateForCurrentPage()`: compute `isHdr` / `boostOn` for the
      current page; on API 34+ set `window.colorMode` to `COLOR_MODE_HDR` when
      `boostOn` else `COLOR_MODE_DEFAULT`.
- [ ] Call it from the load callback (when `position == currentItem`) and from
      `onPageSelected`.

## Phase 4 — HDR badge UI + toggle

- [ ] Drawables `bg_hdr_badge_on.xml` (solid white pill) and
      `bg_hdr_badge_off.xml` (transparent pill, white stroke).
- [ ] Strings `hdr_badge_text` (= "HDR", translatable=false), `cd_hdr_badge_on`,
      `cd_hdr_badge_off`.
- [ ] `activity_image_viewer.xml`: add `tv_hdr_badge` TextView overlay,
      `layout_gravity="top|end"`, `layout_marginTop="64dp"`, `marginEnd="16dp"`,
      `visibility="gone"`.
- [ ] `findViews()`: bind `mTvHdrBadge`; `initUI()`:
      `DisplayUtil.applyTopInsetAsMargin(mTvHdrBadge)` + click listener that flips
      `mForcedSdr[current]` then re-applies state.
- [ ] `updateHdrBadge()`: visible only when `isHdr && mSystemUiVisible`; swap
      on/off background + text colour + contentDescription.
- [ ] `toggleSystemUI()`: re-apply badge visibility after flipping
      `mSystemUiVisible`.

## Phase 5 — Build & verify

- [ ] `:app:assembleDebug` passes.
- [ ] Publish a debug update to Aliyun (note under `debug-updates/`); do NOT
      auto-install on the physical device — user tests remotely.
- [ ] Confirm acceptance criteria in [plan.md](plan.md) on a real Android 14+ HDR
      device.

## Notes / log

### 2026-06-24 — Phases 1-5 implemented, compiled, published

All phases implemented in one pass:

- **Phase 1 (video fix)**: `getVideoListener()` now uses MIME `video/*` and wraps
  `startActivity` in `try/catch (ActivityNotFoundException)` → Toast
  `image_viewer_no_video_player`.
- **Phase 2 (detection)**: `loadImage()` reworked to
  `.asBitmap().dontTransform().disallowHardwareConfig().override(w,h)`, takes a
  `position`, records `mHasGainmap[position] = SDK>=34 && bitmap.hasGainmap()`.
  Tab loop is now indexed.
- **Phase 3 (window mode)**: `applyHdrStateForCurrentPage()` sets
  `window.colorMode` (API 34+) from the current page; called on load, page
  change, and system-UI toggle.
- **Phase 4 (badge)**: `bg_hdr_badge_on/off.xml`, `tv_hdr_badge` overlay,
  strings (default + zh-rCN/HK/TW), tap toggles ephemeral `mForcedSdr`, visible
  only when `isHdr && mSystemUiVisible`.
- **Phase 5**: `:app:assembleDebug` clean (no warnings on changed files).
  Published debug update `202606240907` to Aliyun with the release note. Not
  installed on the physical device per the user's remote-test workflow.

### 2026-06-24 — Device verification + video crash root-caused

- **HDR verified**: user confirmed the boost + badge render on device, so the
  gain map survives the `asBitmap + dontTransform + disallowHardwareConfig`
  Glide path. No fallback (drop `.override` / `DownsampleStrategy`) needed.
- **Video crash, real root cause**: the first fix guarded only `startActivity`,
  but the crash log showed `IllegalArgumentException` from
  `FileProvider.getUriForFile` at `ImageViewerActivity.kt:196` — the attachment
  path was `/storage/emulated/0/DCIM/Camera/VID....mp4`, outside every
  configured FileProvider root. Fix:
  1. `file_provider_paths.xml`: added `<external-path name="external_storage"
     path="."/>` covering the whole external-storage root (longer-prefix roots
     still win for app-private dirs).
  2. `getVideoListener()`: moved `getUriForFile` inside the `try` and added a
     `catch (IllegalArgumentException)` Toast alongside the
     `ActivityNotFoundException` one, so no path/player combination can crash.
- Manifest already holds `READ_MEDIA_VIDEO` / `READ_EXTERNAL_STORAGE`, so the
  public-path video should actually play (FileProvider opens it as the app).
- Published debug `202606240927`.
