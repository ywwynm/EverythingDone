# Project Maintenance Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-29 - Dynamic home-card width must account for RecyclerView padding exactly once

`BaseThingsAdapter` may refresh `mCardWidth` from the attached RecyclerView so
private hidden cards, image cards, and rotation/multi-window cases use the live
span width. When deriving that width from `RecyclerView.width - paddingLeft -
paddingRight`, the formula must subtract only per-item left/right margins. Do
not reuse the full-screen `DisplayUtil.getThingCardWidth(...)` spacing formula
with `(spanCount + 1)`, because the RecyclerView outer padding has already been
removed and would be double-counted.

## 2026-05-27 - EverythingDone remains the primary Android update target

Future app updates should be made in the `EverythingDone` project. The
`Everything-Android` directory can be used as a reference for designs, code, or
new functionality, but it is not the target project for changes and may be
deleted later.

## 2026-05-26 - ThingsActivity restored-list animation boundary

When ThingsActivity is restored from saved state after a background
configuration change, do not replay the normal first-bind "things appearing"
animations. The restored RecyclerView is trying to put the user back at the
same scroll position, not present a fresh list.

Do not suppress the normal Detail-return item update animation. Same-type
Detail returns should continue to use ordinary `notifyItemChanged(position)`;
only the restored list's first-bind appearing animation is disabled. The
payload/no-change-animation approach made the restored list jump into place
without the expected item update affordance and was reverted.
