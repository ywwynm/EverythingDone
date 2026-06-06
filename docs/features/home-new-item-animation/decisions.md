# Decisions

## 2026-06-06 - Guard item-scoped ShiningBorder geometry during new-item animation

Use a short-term guard for the home new-item `ShiningBorder` animation instead
of locking device orientation. The card-scoped border path is computed from the
card and overlay window coordinates before playback, so RecyclerView scrolling,
configuration changes, search/filter refreshes, undo operations, and delayed
thing updates can invalidate that geometry.

The guard starts when the new card is hidden for the pending `ShiningBorder`
animation, consumes RecyclerView/item touch while active, stops ongoing scroll,
and finishes the new-item border immediately before known list geometry changes.
Finishing restores the target card to `VISIBLE`, hides and resets the border,
and clears the general reveal-animation flag.

## 2026-06-06 - Card-level new-item reveal is lower risk than ShiningBorder

There are two reveal-style animations in `ThingsActivity`:

- The FAB-to-detail create animation uses the global `RevealLayout` overlay and
  a window-coordinate center from the FAB. It runs before the thing is created
  and is separate from the new-card entry animation.
- The post-create new-card reveal uses
  `ViewAnimationUtils.createCircularReveal()` directly on the card view, with
  local card coordinates.

The post-create card reveal does not need the same geometry guard as the
item-scoped `ShiningBorder` because it is attached to the card view rather than
to an overlay path computed from window coordinates. If the card moves, the
reveal moves with it. It may still benefit from a smaller cleanup later:
tracking the pending 180ms delay and active card animator so geometry changes
can restore the card to `VISIBLE` consistently.
