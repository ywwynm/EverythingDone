# Thing Folders

Status: implemented (纯骨架模型，2026-06-23）。后续仅 `followups.md` 中的延后项。

## Goal

Add user-created Thing Folders to the EverythingDone home list. A Thing Folder
groups Things and may contain nested Thing Folders. The feature must work with
the existing home list, drawer navigation, Thing detail, reminders, habits,
goals, private Things, sorting, search, backup, export, widgets, and current
Thing Card appearance behavior.

## Documents

- `use-cases.md` - 权威行为矩阵（当前实现：纯骨架模型 + 跟随类型筛选）。
- `plan.md` - 早期规划稿（部分已被 use-cases.md / decisions.md 取代，见文内标注）。
- `execution.md` - phased implementation checklist and verification matrix.
- `decisions.md` - decisions confirmed during the grilling session.
- `followups.md` - deferred work that is intentionally outside the first
  implementation slice.

## Related Global Records

- Domain language: `CONTEXT.md` entries for Thing, Thing Folder, Thing Folder
  Path, Thing Card, and Thing Folder Card.
- Existing card presentation: `docs/features/thing-card-appearance/`,
  `docs/features/thing-card-media-target-geometry/`, and
  `docs/features/home-card-span-mode/`.
- Existing toolbar behavior: `docs/features/home-contextual-toolbar/`.
