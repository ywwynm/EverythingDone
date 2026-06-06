# Follow-ups

## Deferred

- Replace the short-term guard with a geometry-aware animation model that keeps
  the border anchored to the target card across scroll/layout/configuration
  changes, or cancels cleanly from a centralized RecyclerView layout observer.
- Consider adding a lightweight generic new-card entry animation state for the
  card-level reveal path, mainly to restore the card after the pending delay or
  animator is interrupted by lifecycle/configuration changes.
