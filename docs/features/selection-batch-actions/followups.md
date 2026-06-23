# Selection Batch Actions Followups

## 清理未用的单选旧方法

`ThingsActivity` 的 `toggleSelectedStickyEntry`、`toggleSelectedPrivateEntry`
（以及它们调用链上仅被其使用的 `toggleSelectedThingSticky` / `toggleSelectedFolderSticky` /
`toggleSelectedThingPrivate` 等，若确无其它调用方）在改为批量入口后变为未用，仅产生告警。
确认无引用后可删除。

## 新增字符串的完整本地化

`act_*_selected_items`、`confirm_*_selected_items`、`no_matching_things_in_selection`、
`private_batch_skipped` 目前只有英文（默认）与简体中文（zh-rCN），其余语言回退英文。后续补齐翻译。

## 批量置顶 / 私密的列表刷新方式

批量置顶、批量私密目前用整表 `notifyDataSetChanged` 重绑，未做逐项移动动画（单选路径有动画）。
如需更精细的过场动画，后续可优化为按受影响项做局部 notify。
