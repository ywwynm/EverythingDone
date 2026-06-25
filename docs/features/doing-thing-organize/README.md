# Doing Thing Organize

Status: 设计中（grilling，2026-06-25）。

## Goal

让 Doing Thing（正在做 / 正在计时的唯一记事）在首页可以像普通正在进行记事一样被组织：
长按拖拽重排、拖拽建夹 / 拖入已有文件夹、选择模式"移动到文件夹"、置顶；同时保护它
不被完成 / 删除 / 设为私密。此前 Doing 卡在首页被完全锁定（长按直接 backNormalMode、
点按选不中、拖拽全禁）。

顺带修复在 grilling 中坐实的三个既有 bug：

1. 选择模式下 Doing 卡高度有时被撑高（蒙层测量时序）。
2. "全选"能选中 Doing 而点按不能（排除口径不一致）。
3. 选中含 Doing 的文件夹做完成 / 删除时，递归命中并真的改了 Doing 状态、却不停计时，
   且确认弹窗无提示。

## Documents

- `decisions.md` — grilling 中确认的决策。
- `plan.md` — 实现方案与改动地图。
- `execution.md` — 分阶段勾选清单与验证矩阵。
- `sessions.md` — 会话记录。
- `debug-updates/` — 阿里云 debug 发布日志。

## Related Global Records

- `CONTEXT.md`：Doing Thing、Ongoing Thing 词条。
- `docs/features/thing-folders/`：拖入文件夹、移动到文件夹 dialog、文件夹范围递归动作。
- `docs/features/selection-batch-actions/`：选择模式批量动作、`HomeActionWordingHelper` 文案模型。
