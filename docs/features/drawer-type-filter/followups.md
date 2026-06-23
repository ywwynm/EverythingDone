# Drawer Type Filter - Follow-ups

## 2026-06-22 - Phase A 完成的 review follow-ups

- 已完成：状态切换保留 `typeFilterMask` 与 Scope。`ThingListProjection.withStatus()` 改为保留 folderPath + typeFilterMask；越界回退（已删文件夹切非回收站状态、文件夹不存在）由 `ThingManager.setStatus` → `trimProjectionToVisibleFolders()` 处理，并保留已认证私密文件夹。
- 已完成：ActivityHeader 标题只显示状态名（根 Scope）或文件夹名，不再附加类型筛选文本（删除 `getRootTitle`/`updateText` 中的 type-filter 分支）。

## 2026-06-22 - Phase E 完成

- 已完成：列表 Widget 状态维度（正在进行/已完成）。DB v19 加 `app_widget.status` 列，`AppWidgetDAO.insert` 写入，`ThingsListWidgetService` 按状态查询，配置界面新增“记事状态”行。不含回收站。
- 仍待办：配置界面完整复用 Drawer 的 `ThingFilterPanel`/状态分段控件（目前配置界面用自有 XML 类型 UI + 新增状态行，未统一为同一组件）。
- 已完成：Widget header 标题反映状态。`AppWidgetHelper.getThingsListHeaderTitle` 现按 `info.status` 组合标题（根+全部+正在进行→“正在进行”；已完成→加“已完成”段；与文件夹名/类型名用 · 连接）。

## 2026-06-21 - Completed in follow-up fix

- Removed the app-wide `limit` protocol and compatibility layer from active code: `Def.LimitForGettingThings`, `Def.Communication.KEY_LIMIT`, `App.getLimit()`, `App.setLimit()`, and the Detail/DateTime legacy argument chain are gone. Cross-component projection routing now uses `KEY_STATUS`.
- Restored deleted-folder semantics for the unified type-filter folder projection. DELETED status now allows effectively deleted folders and applies the type mask without requiring descendant Things to have `state=DELETED`.
- Reconciled `ThingsCounts.getThingsCountForStatus()` with DAO selection for FINISHED/DELETED all-type projections.
