# Cloud Sync（云同步）

Status: 设计定稿（2026-07-06），待排期实施。

每个 Sync Account 在其所有 Android 设备间同步记事（Thing）与文件夹（Thing Folder）的全部数据：内容、提醒/习惯/打卡、附件文件与漫游设置。离线优先，绝不静默丢内容。

## Documents

- [plan.md](plan.md) — 权威方案：架构、数据模型、协议、冲突矩阵、附件管线、安全合规、验证、优化、阶段划分、风险。
- [decisions.md](decisions.md) — 13 项决策记录（2026-07-06 grilling 会话逐项确认）。
- [followups.md](followups.md) — 二期项、已知微瑕、实现期待办。

## Related Global Records

- `CONTEXT.md`：**Sync Account**、**Conflict Copy** 词条及关系。
- ADR-0011：自建 Node.js/TypeScript 同步后端（Hono + Drizzle + PostgreSQL）。
- ADR-0012：同步数据不做端到端加密。
- ADR-0001：debug 更新渠道（裸 IP HTTP）不承载正式同步流量的边界。
- `docs/features/thing-folders/`：文件夹骨架模型与 `folder.updateTime` 语义（为同步铺路的前置工作）。
