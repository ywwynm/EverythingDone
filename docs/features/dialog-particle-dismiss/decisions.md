# dialog-particle-dismiss 决策记录

## 2026-08-26 技术路线定案

用户确认采用调研推荐路线（见
[research-2026-08-26-technical-approach.md](research-2026-08-26-technical-approach.md)）：

- DecorView 快照 → `GL_POINTS` 无状态点粒子（波前溶解）→ 全屏透明 TextureView
  挂 Activity DecorView；
- 不延迟真实 dismiss，动画为纯装饰层，触摸穿透；
- 接入点集中在 BaseDialogFragment / GestureAnchoredDialog 两处，子类零改动；
- 先以 AlertDialogFragment 做单点样板，观感满意后再翻默认开关铺开到全部 Dialog。
