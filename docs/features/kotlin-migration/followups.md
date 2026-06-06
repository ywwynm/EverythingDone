# Kotlin Migration Followups

Migrated from global `memory/followups.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Kotlin migration - Global N1 sweep on `set*Listener` / `set*Callback` params (deferred 2026-05-20)

**Scope:** Every Kotlin file translated in Groups 1–12.

**Background:** Plan §3.1 N1 says every Java reference parameter →
Kotlin `T?`. In practice this was missed on listener / callback
setters where the param "looks" non-null but Java callers legitimately
pass `null` to deregister. One concrete crash hit production on
2026-05-20: `ThingsActivity.playNewItemShiningBorder` calls
`mShiningBorder.setOnProgressUpdateListener(null)` to clear the
progress listener before the end-listener runs — Kotlin's intrinsic
non-null check threw `NullPointerException` and crashed the new-thing
animation flow on every newly-created note.

**Fixed in this incident (2026-05-20):** `ShiningBorder.kt` 3 setters,
`PatternLockView.setOnPatternListener`, `Snackbar.setUndoListener` —
all changed to `param: T?`. Scan was scoped to `views/` only.

**Path to global sweep:**

```bash
grep -nE "fun\s+set\w+(Listener|Callback)\s*\(.*:\s*[A-Z]\w*\)" \
     app/src/main/java/com/ywwynm/everythingdone/**/*.kt
```

Any match with a non-`?` parameter is a candidate. For each, check
whether any Java caller passes `null` (deregistration); if yes (or
the original Java had no `@NonNull`), append `?`. Don't widen the
signature blindly — some callbacks are required at construction and
Java callers don't pass null. Inspect the original Java in
`git show <pre-group-commit>:<file>.java` to confirm.

**Risk if left undone:** more silent NPE landmines waiting for the
right Java call site to hit `null`. The original cross-language
N1 audit trail principle holds; widening these is behavior-preserving
(Java already accepts null at runtime — Kotlin's intrinsic check is
the regression, not the param itself).
