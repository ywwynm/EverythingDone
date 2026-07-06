# 云同步（Cloud Sync）设计与实施方案

Status: 设计定稿（2026-07-06 grilling 会话产出），待排期实施。
决策权威来源：[decisions.md](decisions.md)；领域术语：根目录 `CONTEXT.md`（Sync Account、Conflict Copy 等）；技术选型：ADR-0011、ADR-0012。

---

## 0. 决策速览

| 维度 | 决定 |
|---|---|
| 定位 | 公开多用户服务，小规模起步（单 ECS + OSS） |
| 范围 | 内容全量（记事/文件夹/提醒/习惯/打卡）+ 附件 + 设置漫游；`doing_records` 二期；`app_widget` 永不 |
| 后端 | 自建 API：TypeScript + Hono + Zod + Drizzle + PostgreSQL，Docker Compose + Caddy |
| 账号 | 用户名+密码登录，邮箱验证码注册/找回；identity 层可扩展手机号/微信 |
| 加密 | TLS + 服务端静态加密，无 E2EE（ADR-0012） |
| 协议 | 账号级单调序列号 + 墓碑 + 基线版本并发检测；时钟不参与判定 |
| 冲突 | Thing 分域：内容域并发出 Conflict Copy，结构域字段级 LWW；Folder 全字段 LWW 不出副本 |
| 附件 | OSS STS 直传，SHA-256 内容寻址去重，缩略图先行、原件按需 + 可选 Wi-Fi 全量 |
| 触发 | 写后防抖推送 + 前台/下拉拉取 + WorkManager 周期兜底 |
| 首绑定 | 双向合并 + 内容指纹去重，绝不清空任何一方 |
| 配额 | 免费：1GB/账号、100MB/附件、10 设备，服务端强制 |
| 验证 | JVM 单元测试 + 真 PG 集成测试 + 多客户端协议模拟器 + 用户真机手测（不用 adb） |

## 1. 目标与非目标

**目标**

1. 一个 Sync Account 登录的所有 Android 设备之间，双向同步全部内容数据与漫游设置，离线优先：无网时一切功能照旧，联网后自动收敛。
2. 绝不静默丢失内容：任何并发修改要么双方生效，要么以 Conflict Copy 显式保留落后方。
3. 未登录用户零感知：不登录时应用行为与今天完全一致，同步是纯增量能力。
4. 协议与账号体系按多用户服务设计，后续放量不推倒重来。

**非目标（一期）**

- 不做协同编辑/分享（单账号多设备，不是多人协作）。
- 不做 E2EE（ADR-0012）、不做 Web 端、不做实时秒级推送（SSE 二期）。
- 不同步 `doing_records`（计时历史）与 `app_widget`（设备本地概念）。
- 不做付费/支付，配额模型预留档位字段。

## 2. 总体架构

```
┌─ Android App ────────────────────────────┐
│ UI（账号页/同步状态/冲突副本呈现）        │
│ SyncEngine（推拉/合并/水位，纯 Kotlin）   │      HTTPS (Caddy)
│ 变更捕获（DAO 埋点 → sync_entities）      │ ──────────────────► Hono API (Node/TS)
│ AttachmentStore（ed:// 解析/上传下载队列）│                        │ Drizzle
│ WorkManager 调度（防抖/周期/约束）        │                        ▼
└──────────────────────────────────────────┘                   PostgreSQL
        │  STS 临时凭证直传/签名 URL 直下                            │
        └────────────────────────► 阿里云 OSS ◄── 服务端仅登记元数据─┘
```

- 附件字节不经过 ECS（固定带宽是最贵资源）：上传用 STS 临时凭证直传 OSS，下载用签名 URL 直连 OSS。
- 服务端是唯一权威：序列号、版本号、冲突裁决、配额都在服务端完成；客户端只是缓存 + 操作队列。
- 服务端代码在本仓库 `server/` 目录。

## 3. 数据模型

### 3.1 同步实体类型

把本地 8 张表收敛为 4 种同步实体，减少协议面：

| 实体类型 | 覆盖的本地表 | 说明 |
|---|---|---|
| `THING` | `things` + `reminders` + `habits` | reminder/habit 与 thing 1:1，逻辑上就是 Thing 的字段，折叠进同一 payload |
| `FOLDER` | `thing_folders` | 全字段 LWW，不出冲突副本 |
| `HABIT_RECORD` | `habit_records` | 追加型（只建/删，不改），天然无冲突 |
| `SETTING` | SharedPreferences 漫游键 | payload = {key, value}，按键 LWW |

**不同步而在本地再生的表**：`habit_reminders`（由 habit.detail 派生的提醒时刻表，应用远端 habit 变更后本地重建，与闹钟重建同一路径）。`app_widget`、`doing_records` 不参与（见范围决策）。

### 3.2 客户端迁移（DB v21 → v22，纯本地，先行合入）

新增列/表（`DBHelper`）：

1. `things`、`thing_folders`、`habit_records` 各加 `uuid TEXT`（唯一索引）；升级时为存量行补发 UUIDv4。本地自增 Long id 继续做应用内主键，闹钟（`AlarmHelper` 的 requestCode）、小部件、表间关联全部不动。
2. 新表 `sync_entities`（本地同步状态，与业务行分离）：
   `entity_type, local_id, uuid, server_version, server_content_version, dirty_mask, tombstone, fingerprint_bound, last_error, updated_at`
   - `dirty_mask`：按字段组的脏位（见 5.3），登录前也照常记录（登录时即成为待上传集）。
   - `tombstone=1`：本地永久删除后业务行已不在，凭此行把删除推给服务端，ACK 后清除。
3. 新表 `attachment_blobs`：`sha256, local_path, size, mime, state(LOCAL_ONLY/UPLOADED/REMOTE_ONLY), thumb_local_path`。
4. 新表 `sync_meta`：水位（`last_seq`）、账号信息、设备 id 等键值。

**附件引用改造（本迁移最重的部分）**：`attachment` 字段现存绝对路径（`SIGNAL+type+absolutePath` 列表，编解码集中在 `AttachmentHelper`），改为与设备无关的 `type + "ed://" + sha256 + "/" + 原文件名`。
- `onUpgrade` 里**不算哈希**（音视频可达数百 MB，不能阻塞升级）：升级只建表补 uuid；首次启动后跑幂等后台重写任务，逐条计算 sha256 → 写 `attachment_blobs` → 重写 `attachment` 字段。
- 完成前 `AttachmentHelper` 解析层同时兼容绝对路径与 `ed://` 两种形态（渲染处无感）。
- 老 ZIP 备份恢复后同样触发这个任务（见 14. 风险）。

### 3.3 服务端 schema（Drizzle / PostgreSQL）

```
users        id, username(唯一,不区分大小写), email(唯一,已验证), password_hash(argon2id),
             plan_tier, storage_used, last_seq, created_at
identities   id, user_id, type('password'|将来'phone'|'wechat'), identifier, secret, created_at
devices      id, user_id, name, platform, refresh_token_hash, last_sync_at, created_at
email_codes  email, purpose(register|reset), code_hash, expires_at, attempts
entities     id, user_id, type, uuid, seq, version, content_version, deleted,
             payload(jsonb), fingerprint, created_at, updated_at
             唯一键(user_id,type,uuid)；索引(user_id,seq)
attachments  user_id, sha256, size, mime, oss_key, thumb_key, state(pending|committed), created_at
             唯一键(user_id,sha256)
```

- `seq`：账号级单调序列号，取自 `users.last_seq`，在事务内 `UPDATE ... RETURNING` 保证并发下单调；任何 entities 写入（含墓碑）都盖新 seq。
- `version`：实体行乐观版本，每次接受写 +1；`content_version` 仅内容域变化时 +1（冲突判定用，见 5.4）。
- `payload`：镜像 App 模型字段的 JSON（含 `schema_version` 以备将来格式演进）；`content`/`attachment` 等字符串按不透明值存放（清单三元组格式不解析）。payload 中的关联一律用 uuid（thing.folderUuid、habitRecord.thingUuid）。
- `fingerprint`：创建时计算的内容指纹（见 5.7），首绑定去重用。
- OSS 对象：`{user_id}/{sha256}`（原件）与 `{user_id}/thumb/{sha256}`（缩略图）。

## 4. API 设计（/v1，全 JSON，Zod 校验）

**认证**

| 端点 | 说明 |
|---|---|
| `POST /v1/auth/email-code` | 发验证码（注册/找回），DirectMail 发信，限流：每邮箱每日上限 + IP 限流 |
| `POST /v1/auth/register` | username+email+password+code；用户名规则 3-20 位字母数字下划线连字符 |
| `POST /v1/auth/login` | → access token(JWT, 30min) + refresh token(30 天，轮换，绑定 devices 行) |
| `POST /v1/auth/refresh` / `logout` | 轮换/吊销 refresh；异常复用旧 refresh → 全设备强制下线 |
| `POST /v1/auth/reset-password` | email+code+新密码；重置后吊销全部 refresh |
| `GET/DELETE /v1/devices` | 设备列表 / 远程下线某设备 |
| `DELETE /v1/account` | 注销账号：删除全部 entities/attachments/OSS 对象（PIPL 合规必需，二次确认） |

**同步**

| 端点 | 说明 |
|---|---|
| `GET /v1/sync/changes?since={seq}&limit=500` | 按 seq 升序返回增量（含墓碑），`{items[], next_since, latest_seq}`，分页拉到追平 |
| `POST /v1/sync/push` | 批量提交（≤200 条）：每条 `{type, uuid, base_version, base_content_version, changed_fields, payload}`；逐条返回 `applied{version,...}` / `conflict{copy_uuid}` / `rejected{reason}` |
| `GET /v1/sync/fingerprints` | 首绑定用：全量 `(type, uuid, fingerprint)` 列表 |
| `GET /v1/account/usage` | 配额占用（存储/条目/设备数） |

**附件**

| 端点 | 说明 |
|---|---|
| `POST /v1/attachments/begin` | `{sha256,size,mime}`：已存在 → 秒传返回 committed；否则校验配额 → 返回 STS 临时凭证 + oss_key（>10MB 走 OSS multipart，断点续传由 OSS SDK 承担） |
| `POST /v1/attachments/commit` | 服务端 HEAD 校验对象存在且大小一致 → 登记 + 计入 `storage_used`；缩略图同流程 |
| `GET /v1/attachments/{sha256}/url` | 签名 GET URL（TTL 1h），原件与缩略图各一 |

## 5. 同步协议语义

### 5.1 水位与增量

客户端持有 `last_seq`，拉取 `> last_seq` 的变更按 seq 顺序应用，逐批推进水位（中断可续，天然幂等：按 uuid upsert + 版本比对，重放无害）。seq 是服务端写入全序，因此"thing 引用的 folder 先于 thing 出现"在创建场景天然成立；引用已墓碑 folder 的实体应用时回落到最近存活祖先/根（与回收站投影的修复语义一致）。

### 5.2 推送与基线

推送项携带 `base_version` / `base_content_version`（上次从服务端学到的版本）与 `changed_fields`（自基线实际改过的字段组集合）。服务端据此三方判定，而不是盲目整行覆盖。推送成功后客户端把返回版本写回 `sync_entities` 并清脏位；**应用远端变更的写路径不标脏**（echo 抑制，apply-from-remote 专用入口）。

### 5.3 字段分域（Thing）

| 字段组 | 成员 | 域 |
|---|---|---|
| `content` | title、content、attachment 引用列表 | 内容域 |
| `state` | state、finishTime、state_before_delete | 结构域 |
| `placement` | folderId(uuid)、location（含置顶负数区语义） | 结构域 |
| `appearance` | color/background、span_mode、image_placement、thing_card_appearance、detail_attachment_media_appearance | 结构域 |
| `reminder` | notifyTime、reminder state（1:1 折叠字段） | 结构域 |
| `habit` | type、detail、intervalInfo、record 等（1:1 折叠字段） | 结构域 |

已知语义微瑕（接受并记录）：设私密是 `PRIVATE_THING_PREFIX` 标题前缀，因此"一边设私密、一边改正文"会判为内容域并发出副本；真实困扰再调整（followups）。

### 5.4 冲突矩阵（Thing）

服务端逐项裁决（"服务端已变"指自客户端基线以来）：

| 客户端改了 | 服务端已变 | 结果 |
|---|---|---|
| 任意 | 无 | 直接应用，version+1（content 变则 content_version+1） |
| 仅结构域 | 仅内容域（或不相交字段组） | 双方都生效：按 `changed_fields` 合并，无副本 |
| 仅内容域 | 仅结构域 | 同上对称 |
| 内容域 | 内容域 | **Conflict Copy**：服务端保留现行内容；以客户端内容新建副本 Thing（新 uuid、同 folder、location 紧邻原件、标题加"（冲突副本 yyyy-MM-dd HH:mm）"），随变更流下发；客户端同批的结构域修改照常合并 |
| 内容域 | 已永久删除（墓碑） | 复活：以客户端 payload 重建（不静默丢内容） |
| 仅结构域 | 已永久删除 | 删除维持，推送项拒绝，客户端随墓碑收敛 |
| 永久删除 | 内容域已变 | 删除拒绝，实体在客户端复活为服务端最新版 |
| 永久删除 | 仅结构域已变 | 删除生效（墓碑） |

移入回收站（state=DELETED）只是结构域字段变更，照 LWW，不涉及以上墓碑行；墓碑仅对应 DELETED_FOREVER 的物理删除。

**Folder**：全字段 LWW（不出副本——副本会复制容器语义，子项归属无法两全）。服务端对 `parent` 变更做防环校验：构成环 → 拒绝该字段、保留服务端值，客户端拉取时收敛。Folder 永久删除是整棵子树逐实体墓碑；并发在该子树新建的 Thing 推送上来时因 folder 引用失效而回落最近存活祖先。

**HABIT_RECORD**：只有创建与删除（撤销打卡），uuid 各自独立，无冲突路径。

**SETTING**：按键 LWW；漫游键集合见 8.5。

### 5.5 触发调度

- 本地写库后防抖 ~8s 推送（WorkManager OneTime + REPLACE，附网络约束）。
- App 进前台 / 下拉刷新 → 先拉后推。
- 周期兜底：WorkManager Periodic 约 1h（仅联网）。
- 失败退避：指数 + 抖动，尊重服务端 `Retry-After`；鉴权失败静默刷新 token，refresh 失效才打扰用户重登。

### 5.6 应用远端变更的副作用（客户端）

按既有更新路径逐类触发：reminder/habit 变化 → `AlarmHelper` 重设/取消闹钟 + 重建 `habit_reminders`；列表 → 复用 `BROADCAST_ACTION_UPDATE_MAIN_UI`；小部件 → `AppWidgetHelper` 刷新；正在 Detail 编辑的 Thing 被远端更新 → 不打断，保存时按基线走正常冲突路径；Doing 中的 Thing 被远端永久删除 → 停止计时并提示。

### 5.7 首绑定归并

1. 登录成功 → 自动本地 ZIP 备份（复用 `BackupHelper`）作为安全垫。
2. 拉全量云端指纹表；对本地"从未同步过"的实体计算指纹 `sha256(type | createTime | title | content | 规范化附件引用)`：命中 → 绑定该云端 uuid（采纳服务端版本，不上传）；未命中 → 标脏待推送。
3. 全量拉取 + 全量推送（分批、可中断续传、进度 UI）。
4. 登出：本地数据保留并标记归属账号；换账号登录：确认后清除前账号本地数据（云端不受影响）；未登录期间产生的数据在下次登录时并入所登录账号。

## 6. 附件管线

- **上传**：迁移任务/新附件产生 sha256 → `begin`（秒传判断/配额校验/领 STS）→ OSS 直传（multipart ≥10MB）→ `commit`。缩略图（长边 ≤512px 的 WebP，质量 80）在客户端生成并随原件上传，供其他设备的 Thing Card 封面使用。上传默认约束：原件仅 Wi-Fi（可在设置放开），缩略图不限。
- **下载**：拉取到含附件引用的实体后自动入队缩略图下载；原件在用户打开（全屏查看/播放）时下载并留存 `attachment_blobs`（LOCAL 化）；设置项"仅 Wi-Fi 自动下载全部原件"给完整镜像需求。下载渠道是 OSS 签名 URL 直连。
- **去重**：内容寻址天然去重（跨记事、跨设备、老备份复制的同一文件只存一份）；删除走服务端"孤儿附件回收"后台任务（扫描不再被任何 payload 引用的 sha，7 天宽限后删 OSS + 退配额），客户端不管引用计数。
- **Drawer Header Image**：作为 SETTING 漫游键 + 附件引用组合处理（图 + 共享裁切参数）。

## 7. 账号与安全

- 密码 argon2id；JWT HS256（单服务足够），access 30min / refresh 30 天轮换，refresh 哈希落 `devices` 行，支持远程下线与"重置密码全下线"。
- 客户端 token 存 Android Keystore 加密的本地存储。
- 验证码 6 位、10 分钟有效、尝试次数与频率限制；注册/登录接口 IP 级限流（小规模下用 PG 计数即可，不引入 Redis）。
- 服务器：仅密钥 SSH、防火墙只开 80/443、fail2ban；PG 每日 `pg_dump` 加密上传 OSS；OSS 开启服务端静态加密。
- App 侧新增 INTERNET 权限 + Network Security Config：release 仅信任系统 CA + 正式域名；内测期（备案未完成前）允许 debug 构建信任自签证书连 IP。

## 8. 客户端实现方案

### 8.1 模块

新包 `com.ywwynm.everythingdone.sync`：`SyncEngine`（推拉状态机，纯 Kotlin 无 Android UI 依赖，可 JVM 单测）、`SyncApi`（Retrofit + OkHttp + Gson，复用现有 Gson）、`ChangeTracker`（DAO 埋点入口）、`AttachmentStore`、`SyncScheduler`（WorkManager）、`AccountManager`。线程模型沿用项目现状（ExecutorService + WorkManager），不引入协程。

### 8.2 变更捕获

所有写库都经 DAO/`ThingManager`（调研确认，含少数 DAO 直写点），在 DAO 写方法收口处调 `ChangeTracker.markDirty(entityType, localId, fieldGroup)`；`ThingDAO.updateState` 的物理删除点（DELETED_FOREVER）改记 tombstone。实现期需要一份**写路径审计清单**（含详情页 DAO 直写、widget 配置路径、`cancelCreatedFolder` 等旁路），逐一确认埋点覆盖——这是同步正确性的第一风险点。

### 8.3 UI

设置内"账号与同步"页：注册/登录（用户名+密码+邮箱验证码）、同步状态（上次成功时间/待推送数/错误）、配额占用条、"仅 Wi-Fi 下载全部原件"开关、立即同步、设备列表、登出、注销账号。首页非侵入指示：仅同步失败时在抽屉入口角标提示。冲突副本不做专门收件箱，副本作为普通 Thing 出现在原文件夹（标题标记 + 创建即置于原件旁）。

### 8.4 里程碑内的兼容原则

P1（v22 迁移 + ed:// 改造 + 埋点）不依赖任何网络代码，先行合入主线单独发布验证；确认稳定后再叠加网络层。任何阶段未登录用户行为与现状一致。

### 8.5 设置漫游键盘点

机制：漫游键白名单表驱动，SharedPreferences 变更监听 → SETTING 实体。首批候选（实现期逐键核对）：Appearance Mode、Cover Autoplay、计时数字风格、默认文字大小、抽屉头图及裁切、排序偏好。明确不漫游：通知渠道/权限状态、上次备份时间、`KEY_ONGOING_THING_ID`、doing 运行态、debug 更新渠道状态、设备尺寸相关缓存。

## 9. 服务端实现方案

```
server/
  src/
    index.ts            # Hono 入口、中间件（auth/限流/压缩/日志）
    routes/{auth,sync,attachments,account}.ts
    sync/merge.ts       # 冲突矩阵实现（纯函数，重点测试对象）
    db/schema.ts        # Drizzle schema + migrations/
    oss.ts  mail.ts  quota.ts
  test/
    integration/        # vitest + 真 PG（docker compose）
    sim/                # 多客户端协议模拟器
  docker-compose.yml    # api + postgres（+Caddy 于宿主或同 compose）
  Caddyfile  .env.example
```

关键实现点：seq 分配在推送事务内 `UPDATE users SET last_seq = last_seq + 1 ... RETURNING`（同账号并发推送串行化）；push 全批一个事务，逐项裁决产出结果数组；changes 查询走 `(user_id, seq)` 索引；孤儿附件回收与 email_codes 清理用定时任务（node-cron 级别即可）。部署：GitHub Actions 或本地脚本 build 镜像 → ECS `docker compose up -d`；Caddy 自动 HTTPS。

## 10. 验证方案（四层）

1. **JVM 单元测试（客户端）**：`SyncEngine` 状态机（水位推进/中断续传/echo 抑制）、字段组脏位、指纹计算、`AttachmentHelper` 新旧格式互转与重写任务幂等性、冲突副本落地位置。
2. **服务端集成测试（vitest + 真 PG）**：认证全流程（含 refresh 轮换与复用检测）、push/pull、**冲突矩阵逐格用例**（5.4 表每行至少一例）、防环校验、墓碑传播、配额拒绝、限流、注销级联清除、并发推送下 seq 单调。
3. **多客户端协议模拟器（`server/test/sim`）**：N 个虚拟客户端（复刻客户端合并逻辑的 TS 实现）对真实服务端跑带种子的随机操作序列（建/改内容/改结构/移动/回收站/恢复/永久删/文件夹操作），穿插离线窗口与乱序推送；静默后断言不变量——(a) 所有客户端状态与服务端逐字节收敛；(b) 无内容丢失：每个曾写入的内容版本要么在终态、要么在某个 Conflict Copy 里；(c) 文件夹树无环无孤儿；(d) 墓碑不复活（除复活规则命中）。失败可用种子复现。
4. **真机手测剧本（用户执行，debug 渠道分发）**：两台真机按剧本验收，覆盖：注册登录、首绑定合并（一台有数据一台空 / 两台都有数据含备份复制场景）、双端离线互改同一记事（结构×内容、内容×内容出副本）、回收站与永久删除传播、习惯打卡合流、附件（拍照上传→另一台缩略图→点开拉原件→断网重试）、配额顶格、登出/换账号确认、闹钟在远端改提醒时间后正确重响、小部件刷新。剧本随 P4 产出为 checklist 文档。

**性能基线**：1 万条记事全量首同步（不含附件原件）目标 <60s；日常增量一轮 <2s（p50）；周期兜底同步对电量影响可忽略（Battery Historian 抽查一次）。

## 11. 优化

- **流量**：请求/响应 gzip；推拉分批（500/批）；附件秒传（sha256 命中免传）；缩略图 WebP。
- **健壮**：指数退避 + 抖动；OSS multipart 断点；水位幂等重放；`Retry-After` 尊重。
- **服务端**：`(user_id, seq)`、`(user_id, type, uuid)` 索引；JSONB payload 控制在 KB 级（附件不入库）；OSS 生命周期规则清理未完成 multipart；连接池默认即可，规模化前不引入额外组件。
- **客户端**：防抖窗口合并连发写入；应用远端变更批内单事务 + 单次 UI 广播；附件下载队列前台优先；缩略图交给 Glide 既有缓存。
- **成本预估（100 活跃用户量级）**：OSS 存储按人均 300MB ≈ 30GB ≈ ￥4/月；出向流量走 OSS（人均 200MB/月 ≈ ￥10/月）；DirectMail 千封 ￥2 级；ECS/域名为存量成本。合计增量 <￥20/月，配额护栏保证上界可控。

## 12. 合规清单（上线前置）

1. 域名 ICP 备案（阿里云流程，2–4 周，**最长外部依赖，P0 即启动**）+ 公安备案。
2. 隐私政策与用户协议页面（服务端可读数据、数据出境=无、收集项清单），App 内注册处展示并留存同意。
3. 账号注销入口（PIPL 强制）：`DELETE /v1/account` + App 内二次确认。
4. debug 更新渠道的裸 IP HTTP 通道不得承载正式同步流量（ADR-0001 边界不变）。

## 13. 实施阶段

| 阶段 | 内容 | 出口条件 |
|---|---|---|
| P0 前置 | 启动备案；`server/` 骨架 + auth + Docker/Caddy 部署链路打通 | 内测环境可注册登录 |
| P1 客户端地基 | DB v22（uuid/sync_entities/attachment_blobs）+ ed:// 重写任务 + 写路径埋点审计（纯本地） | 合入主线随常规 debug 发布验证无回归 |
| P2 结构化同步 MVP | 账号 UI + 推拉引擎 + 冲突分域 + 墓碑 + 调度；协议模拟器同步开发并跑绿 | 模拟器不变量全绿；两真机结构化数据互通 |
| P3 附件管线 | STS 直传/秒传/缩略图/按需下载/配额 UI | 手测附件剧本通过 |
| P4 收尾 | 设置漫游 + 首绑定指纹去重 + 冲突副本呈现 + 手测剧本全量内测 | 剧本 checklist 全过 |
| P5 上线 | 备案完成切正式域名 HTTPS、隐私政策、注销功能、放量 | 正式渠道可用 |

## 14. 风险与对策

| 风险 | 对策 |
|---|---|
| 写路径埋点遗漏 → 静默不同步 | P1 写路径审计清单逐一核销；模拟器"无丢失"不变量兜底；`sync_entities` 与业务表比对的 debug 自检开关 |
| 老 ZIP 备份恢复覆盖 `dataDir`，砸掉水位/uuid 状态 | 恢复流程检测：备份内 DB 版本 < v22 或水位与账号不一致 → 强制视为新设备重走首绑定归并（指纹去重使其无重复代价） |
| 附件哈希重写任务耗时/耗电 | 幂等分片 + 仅充电或空闲执行的 WorkManager 约束；完成前双格式兼容 |
| 单 ECS/PG 单点 | 每日 pg_dump → OSS；`server/` 内含一键重建脚本（compose + 迁移 + 恢复） |
| 滥用与账单失控 | 配额服务端强制 + 注册/验证码限流 + OSS 用量告警（阿里云费用预警） |
| 国内网络到 OSS 直传抖动 | OSS SDK 重试 + multipart 分片小步提交；失败入队重试不阻塞结构化同步 |
| 私密前缀语义与内容域耦合 | 已知微瑕记录在案（5.3），真实困扰再调整分域 |

## 15. 二期展望

见 [followups.md](followups.md)：SSE 前台实时通道、`doing_records` 同步、手机号/微信 identity、付费档、pgvector 语义搜索与 AI 功能、CDN、Web 端。
