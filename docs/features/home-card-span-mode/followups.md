# Home Card Span Mode Followups

Migrated from global `memory/followups.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Home card span mode - Full-span home-card rich layout strategies (deferred 2026-05-30)

**Scope:** Special rendering for Full-Span Home Cards beyond reliable width
adaptation: centred enlarged text-only cards, artistic typography, image
placement based on aspect ratio, side-by-side image/text layouts, and denser
or more editorial checklist/reminder/habit arrangements.

**Current fallback:** The first implementation persists Home Card Span Mode,
toggles it from Detail with undo/redo support, and renders the existing
home-card layout at full span with conservative width adaptation. Existing
content order remains image, title, private lock, content/checklist, audio,
reminder/habit, padding, and doing cover.

**Reason deferred:** The first iteration already touches database schema,
Thing model mapping, Detail edit actions, RecyclerView full-span layout, image
width calculation, private hidden-card sizing, and doing-cover sizing. Rich
layout variants should be designed and visually tested after the base span
semantics are stable.
