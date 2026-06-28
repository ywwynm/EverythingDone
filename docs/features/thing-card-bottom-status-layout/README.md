# Thing Card Bottom Status Layout

Status: 设计已定，实现中（2026-06-28）。

## Scope

统一处理"记事卡片有图片/视频附件时，提醒/目标/习惯等状态块的位置"以及
"封面图片比例（媒体背景目标比例）与卡片内容高度的关系"三个相关问题：

1. 侧栏媒体（左/右）模式：当内容列比侧图矮时，**Thing Card Bottom Status**
   应锚定在内容列底部并保留底部边距，而不是紧跟正文。
2. 媒体背景模式："X张图片，Y段视频"提示语与 Thing Card Bottom Status 都位于
   底部，自上而下顺序为：先提示语、再状态块。
3. 封面图片比例（媒体背景 **Thing Card Media Target Aspect Ratio**）在内容
   高度变化时如何处理，以及各界面（首页、NNA、Doing、widget）渲染比例不一致
   的问题。

## Affected Surfaces

规则作用于**真正渲染 Thing Card Bottom Status 的界面**：

- ThingsActivity（首页列表）
- 单一记事 AppWidget（含 widget 配置的候选列表，复用首页渲染）
- 记事列表 AppWidget
- 单一记事 AppWidget 配置界面的候选列表

NoticeableNotificationActivity 与 DoingActivity **继续隐藏**提醒/目标/习惯状态块
（信息改由标题 + 操作按钮 / 计时蒙层表达），不在本次"状态块置底"改动范围内。

## Documents

- `decisions.md` - 逐条设计决策。

## Related Global Records

- 领域语言：CONTEXT.md 的 Thing Card Bottom Status、Thing Card Side Media
  Panel、Thing Card Media Background、Thing Card Media Target Aspect Ratio。
- ADR：`docs/adr/0003-thing-card-media-target-presentation-geometry.md`。
- 相关功能：`docs/features/thing-card-image-placement/`、
  `docs/features/thing-card-media-target-geometry/`、
  `docs/features/remote-thing-card-appearance/`。
