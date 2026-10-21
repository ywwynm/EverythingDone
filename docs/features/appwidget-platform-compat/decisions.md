# AppWidget Platform Compatibility Decisions

## 2026-10-21：刷新移到持久化后台工作

- 单记事与列表小组件的广播只安排 WorkManager 工作，渲染和系统提交均在后台执行。按小组件 ID 替换旧请求，等待记事保存队列完成后从数据库取独立对象，串行提交防止旧视频解码覆盖新结果。
- 清单集合直接读取已保存内容并应用有效私密规则；保留数据集通知与 RemoteViews 更新的先后顺序，不添加资源 ID。使用系统实际绑定的提供者组件，保留旧尺寸元数据的兼容性。
- 真机测试使用独立 AppWidgetHost，必须完成真实配置 Activity 的 `RESULT_OK` 协议；Android 17 会拒绝未完成配置的小组件集合转换，不能用修改生产代码的方式规避。详见 [转场功能验证](../thing-detail-transition/widget-quick-return-2026-10-21.md)。

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-27 - Do not add new AppWidget-adjacent resource ids for animation bookkeeping
An attempted fix for duplicate-looking home-card update animation added
`res/values/ids.xml` and keyed view tags for `ThingsAdapter` appearing
animation bookkeeping. That build immediately caused existing AppWidget
RemoteViews to display incorrect/stale-looking checklist and Things-list data
after install. The change was rolled back.

For AppWidget regressions, avoid fixes that add new resource ids or perturb the
resource table unless the AppWidget update/install lifecycle is explicitly
smoke-tested on device. Keep future animation fixes inside existing code paths
or existing resources.

## 2026-05-27 - Background full-list refresh should not replay Things appearing animation

`ThingsActivity.justNotifyAll()` remains the conservative full-list reload path
for stale or coalesced remote updates, but the `onResume()` path that consumes a
background `App.justNotifyAll()` should call it without enabling the
`things_show` first-bind animation. Returning from a launcher widget update is a
data catch-up, not a fresh list presentation, and replaying the bottom-up card
appearance reads as a second update animation.
