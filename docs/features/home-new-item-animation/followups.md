# Follow-ups

## 2026-09-14

- RenderThread 动画在“重新挂到窗口后从未绘制”的 RenderNode 上立即结束，只从真机日志确认了现象与规避方式，hwui 内部原因未逐行核实；若以后再遇到 `createCircularReveal` / `ViewPropertyAnimator` 秒结束，先查目标 View 是否自上次挂载后参与过绘制。
- 长滚动场景目前只有 R5CW20BLNKL 的录像证据；没有自动化回归。可在 `NewItemAppearanceProbeActivity` 之类的 debug 探针里加“揭示起止间隔 ≥ 400 ms”的断言。

## Deferred

- Replace the short-term guard with a geometry-aware animation model that keeps
  the border anchored to the target card across scroll/layout/configuration
  changes, or cancels cleanly from a centralized RecyclerView layout observer.
- Consider adding a lightweight generic new-card entry animation state for the
  card-level reveal path, mainly to restore the card after the pending delay or
  animator is interrupted by lifecycle/configuration changes.
