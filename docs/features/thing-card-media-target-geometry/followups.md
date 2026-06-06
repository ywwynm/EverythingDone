# Thing Card Media Target Geometry Followups

Migrated from global `memory/followups.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Remote Thing Card Appearance - Single-Thing widget side media width should keep saved percent as primary (resolved 2026-06-05)

**Scope:** Left/right Thing Card Media in single-Thing AppWidgets, especially
4x4 widgets showing full-span habits with short text but visible habit status
chrome.

**Resolved state:** `AppWidgetHelper.getWidgetMediaSlotTarget()` no longer reads
`sideMediaDisplayAspectRatioHint`. Existing legacy cards without a side-panel
target aspect ratio fall back to the saved `sideMediaWidthPercent`, while new
confirmed edits project side media from `sidePanel.targetAspectRatio` and clamp
only to the existing widget-side min/max guardrails.

**Verification done:** `.\gradlew.bat :app:assembleDebug --console=plain
--no-configuration-cache` and `git diff --check`.

**Residual risk:** Manual launcher checks across 1x1-6x6 cell presets, side/top/
bottom/background placement, and normal/simple widget styles are still useful
before publishing a debug build.

## Remote Thing Card Appearance - Stabilize side-panel target-ratio layout solving (resolved 2026-06-05)

**Scope:** Full-span Thing Cards with left/right media in the home preview and
AppWidgets. Changing `sidePanel.targetAspectRatio` changes media width, which
changes the content column width, which can change text wrapping and card
height, which feeds back into media width.

**Resolved state:** Home-card side panels now use a deterministic projection
that applies image width, content width, and image height together. The
projection approximates the side-panel fixed point with bounded measurements and
does not read the live side-media View height. The appearance panel computes
side-panel slider range from min/max side-media widths and measured content
height, and freezes the active ratio range while the user drags. Things List
AppWidgets now use the same finite projection idea over the existing RemoteViews
height estimator.

**Verification done:** `git diff --check` and
`.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`.

**Residual risk:** Manual preview and launcher checks around sharp text wrapping
thresholds are still useful because Android layout measurement and RemoteViews
row estimation are not identical.
