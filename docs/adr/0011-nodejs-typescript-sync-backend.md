# 自建 Node.js/TypeScript 同步后端

云同步功能需要一个多用户 API 服务。我们决定自建单体服务而非使用 BaaS 或 WebDAV 目标模式，因为增量同步协议、冲突处理、配额与账号体系都需要服务端完全自主；技术栈选 Node.js LTS + TypeScript（Hono + Zod + Drizzle + PostgreSQL，Docker Compose 部署于阿里云 ECS，Caddy 负责 HTTPS）。附件文件存阿里云 OSS，不入库。

## Considered Options

- Kotlin + Spring Boot / Ktor：与 App 同语言，但用户明确不想用 Spring 系，且 JVM 的 AI 生态薄。
- Python + FastAPI：AI/大模型生态最厚（用户规划了后续 AI 功能），曾是推荐项；用户权衡后选 TypeScript——大模型厂商的 TS SDK 足以支撑 API 编排型 AI 功能。若将来需要重 Python 工具链，可加挂独立 worker 服务，不动同步 API。
- BaaS（LeanCloud 等）：免运维但有锁定与配额费用，增量同步逻辑仍需自建。
- WebDAV/网盘目标（Joplin 模式）：无账号体系、冲突检测仅文件 ETag 级，与公开多用户服务定位冲突。

## Consequences

- 仓库将引入第二语言（TypeScript）与独立的服务端子项目、部署流程。
- App 侧将首次引入 INTERNET 权限与网络栈。
- 域名备案与 HTTPS 是上线前置条件（现有 debug 更新渠道走裸 IP HTTP，不满足公开服务要求）。
- PostgreSQL 预留 pgvector，为将来语义搜索/AI 功能留位。
