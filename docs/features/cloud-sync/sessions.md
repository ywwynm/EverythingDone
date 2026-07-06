# Cloud Sync Sessions

## 2026-07-06 - grill-with-docs 设计会话：13 项决策 + 完整方案文档

用户提出"账号内多设备同步记事、文件夹全部数据"的大功能，经 grilling 逐题确认 13 项决策（服务定位、同步范围、后端形态、技术栈、账号、加密、冲突、协议、附件、触发、首绑定、配额、验证），全部记入 decisions.md。

- 前置调研：Explore agent 梳理数据层（DB v21 八张表、attachment 绝对路径编码、无网络栈/无 INTERNET 权限、ZIP 备份机制、广播/闹钟/小部件更新路径、Everything-Android 曾预留 `remoteId`）。
- 产出：plan.md（架构/数据模型/协议冲突矩阵/附件管线/安全合规/四层验证/优化/六阶段/风险）、followups.md、README.md；ADR-0011（Node/TS 后端，中途因用户"要上 AI 功能"重开选型、Python 落选后定稿 TS + Hono + Drizzle + PG）、ADR-0012（无 E2EE）；CONTEXT.md 新增 Sync Account、Conflict Copy 词条与关系。
- 过程要点：用户纠正过一次"框架没问就定了"（Hono 曾未询问即写入 ADR，后补问并确认）；冲突分域决策会后自纠——Thing Folder 不生成冲突副本（副本复制容器语义不可接受），改全字段 LWW，已在 decisions.md 同日修正条目注明。
- 验证授权边界：用户选择"协议模拟器为主 + 真机手测"，本功能开发期间不使用 adb/模拟器 E2E。
