# Share Screenshot

Status: feature-scoped memory archive for generated screenshot sharing.

## Scope

This feature covers the long-screenshot share flow used by Detail and
Statistic screens through `ScreenshotHelper.ShareCallback`.

## 文档

- [plan.md](plan.md) — 记事分享产物与选项规划（2026-07-26）。本 feature 的范围由
  「长截图」扩展到分享产物整体：长视频、滚动视频、附件单独分享
- [animated-share-evaluation.md](animated-share-evaluation.md) — 让分享产物里的
  动态附件动起来的可行性评估（2026-07-26，含体积实测）
- [tools/](tools/) — 上述评估的体积基准复现脚本

## 硬约束

- 分享产物与其中间产物**一律不得使用 GIF**；并发解码器超限时排队等待，不降级画质。
  既有的派生 GIF 管线继续服务卡片与详情页，不参与分享。
- **不以接收方观感设限**：产物在接收方屏幕上显示多大、看不看得清是接收方的事，
  他可以放大、可以换播放器。只有生成侧的真实成本（编不出来、体积、耗时）与功能性失败
  （如 IM 把大动图转成静态）才算约束。

## Related Code

- `app/src/main/java/com/ywwynm/everythingdone/helpers/ScreenshotHelper.kt`
- `app/src/main/java/com/ywwynm/everythingdone/activities/DetailActivity.kt`
- `app/src/main/java/com/ywwynm/everythingdone/activities/StatisticActivity.kt`
