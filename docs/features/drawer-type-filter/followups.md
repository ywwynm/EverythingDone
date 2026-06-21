# Drawer Type Filter - Follow-ups

## 2026-06-21 - Review follow-ups

- Deferred by user: preserve `typeFilterMask` across status switches. `ThingListProjection.withStatus()` currently rebuilds the projection with the default all-types mask.
- Deferred by user: align ActivityHeader title semantics with the design decision: status or folder name only, without appending type-filter text.
- Deferred by user: decide whether list widgets should remain UNDERWAY-only or persist a real widget status. `ThingWidgetInfo.status` and `COLUMN_STATUS_APP_WIDGET` are declared, but the DB schema, DAO insert path, widget configuration, and widget service do not use the status field.

## 2026-06-21 - Completed in follow-up fix

- Removed the app-wide `limit` protocol and compatibility layer from active code: `Def.LimitForGettingThings`, `Def.Communication.KEY_LIMIT`, `App.getLimit()`, `App.setLimit()`, and the Detail/DateTime legacy argument chain are gone. Cross-component projection routing now uses `KEY_STATUS`.
- Restored deleted-folder semantics for the unified type-filter folder projection. DELETED status now allows effectively deleted folders and applies the type mask without requiring descendant Things to have `state=DELETED`.
- Reconciled `ThingsCounts.getThingsCountForStatus()` with DAO selection for FINISHED/DELETED all-type projections.
