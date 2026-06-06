# Sessions

## 2026-06-06 - Stabilize new-item ShiningBorder geometry

- Added a dedicated new-item `ShiningBorder` guard in `ThingsActivity`.
- The guard covers the 180ms pending window before the border starts, the border
  playback itself, RecyclerView/item touch, configuration changes, lifecycle
  pause, search/filter refreshes, undo operations, delayed detail-result
  updates, and card-appearance preview refreshes.
- Avoided orientation locking because Android large-screen behavior and Android
  16 compatibility make fixed orientation an unreliable control surface.
