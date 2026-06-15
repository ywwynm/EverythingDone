# Share Screenshot Followups

## ColorOS sharesheet preview thumbnail fallback (deferred 2026-06-15)

**Scope:** If OPPO ColorOS still shows a blank preview for long-screenshot
shares after the explicit `ClipData` / chooser-grant compatibility fix, add a
separate small preview thumbnail URI for the sharesheet while continuing to
share the full long screenshot through `Intent.EXTRA_STREAM`.

**Risk if left undone:** The actual screenshot share may still work, but the
system Sharesheet preview can remain blank on OEM builds that fail to decode
very tall images for preview.
