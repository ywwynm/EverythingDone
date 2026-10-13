# Thing Transfer（记事传输）

Status: 一期（记事文件）已发布；二期（应用内碰一碰）2026-09-24 实现完成，经过一轮代码审查（16 条全部修复）与两轮动画返工，界面、动画与导入在模拟下经三星、魅族真机验证。粒子动画第三版 2026-09-25 在模拟器里定稿并移植到 Android，2026-09-26 在三星、魅族上用模拟验证后发布（202609251710）。2026-09-26 用户的 Find X9 Ultra 与一加 15T 实际互碰暴露「发送方的系统先读到接收方」，发送方改为只做卡后实测互碰成功，连同下拉按钮与头部的修正一起发布（202609260219）；其余实际互碰的检查项见 followups.md。

把一条或几条 Thing 当面交给另一个人。分两部分：

- **一期 · 记事文件**：一个能被完事儿还原成 Thing 的私有格式文件，通过系统分享
  面板发出去。用户在面板里选互传，碰一碰这件事由系统完成，我们一行代码不写。
- **二期 · 应用内碰一碰**：双方都打开完事儿，一方在详情页或多选界面、另一方在首页，
  碰一下直接触发传输。原理与系统碰一碰相同：NFC 只传握手信息，数据走 Wi-Fi Direct。
  可行性需先在真机上验证。

## Documents

2026-10-14：按用户决定暂缓卓易通网络兼容排查。诊断总开关 `TapDiagnostics.ENABLE_DIAGNOSTICS` 默认关闭，入口统一 GONE；采集和额外查询停用，已有兼容传输实现继续保留。重新采集需改开关并构建 debug，详见 [诊断记录](diagnostics-2026-10-13.md)。

- [tcp-refusal-2026-10-13.md](tcp-refusal-2026-10-13.md) — v5 至 v7 双端 TCP 拒绝排查；已排除应用提前关闭，v7 明确绑定 IPv4 仍失败，继续核对卓易通 P2P 可达性与角色切换路由

- [compatibility-detection-research-2026-10-13.md](compatibility-detection-research-2026-10-13.md) — 卓易通识别调研：`anco` 系统属性、开源实现、普通华为样本对照及应用读取限制；尚未改代码

- [receiver-group-2026-10-13.md](receiver-group-2026-10-13.md) — 卓易通优先建组的双向传输，NFC 协商、连接恢复与本地验证范围

- [diagnostics-2026-10-13.md](diagnostics-2026-10-13.md) — 鸿蒙 7／卓易通两端反馈：用户确认 v3 在系统选设备并允许后可以接收；debug `202610130937` 延长兼容确认等待、增加操作提示和诊断文本文件分享，原 P2P 拒绝及旧反向 ACK 超时仍分别追踪

- [receive-blank-card-2026-10-10.md](receive-blank-card-2026-10-10.md) — 批量接收后滚动出现空白卡片：缓存视图漏恢复的复现、修复及两机回归

- [decisions.md](decisions.md) — 17 项决策记录（2026-09-21 逐项确认）
- [probe-plan.md](probe-plan.md) — 二期可行性的真机验证方案：七个验证项、分段计时、否决线
- [sessions.md](sessions.md) — 会话记录，含**真机验证的全部实测数据**
- [followups.md](followups.md) — 两台手机实际互碰的检查顺序、二维码备选通道、格式版本等未定项
- [plan-phase2.md](plan-phase2.md) — 二期的代码结构、协议与验证方式
- [preferences.md](preferences.md) — 用户对碰一碰动画的要求
- [design-2026-09-25-particle-motion.md](design-2026-09-25-particle-motion.md) — 粒子动画第三版的设计（模拟器里逐轮定下）
- [plan-2026-09-25-particle-port.md](plan-2026-09-25-particle-port.md) — 第三版移植到 Android 的做法与结果
- [particle-sim/README.md](particle-sim/README.md) — 发送与接收动画的 PC 模拟器：真机录下的整段会话重放、调参并写回
  `shared/tap-transfer/particles.properties`、与真机录屏逐帧对照
- `probe/` — 验证用的独立最小 APK。**验证已完成但按用户决定保留**：代码留在仓库
  （`settings.gradle` 里 include 了 `:nfcprobe`）、两台真机上也没卸载，二期实施时可直接参考或复跑。

## 二期可行性：已验证通过（2026-09-21）

三星 SM-S9180（Android 16）与魅族 20 Infinity（Android 15）之间实测 **7 轮全部成功**：

| 项 | 实测 |
|---|---|
| T1 贴上→握手完成 | 37–91 ms |
| T2 握手→入组 | 565–2997 ms（与文件大小无关，取决于射频状态） |
| 传输速率 | 1 MB 约 3、10 MB 约 20–23、**100 MB 达 43–68 MB/s** |
| 100 MB 全程 | **2963 ms**（三星发）／ 4153 ms（魅族发） |
| 握手容量 | `maxTransceiveLength = 65279 B`，握手 84–90 B，不需分段 |
| 系统确认框 | 全程不弹 |
| AID 被抢占 | 未发生，注册为 `*DEFAULT* ApduService` |

否决线（T1+T2 > 10 秒或 10 次少于 7 次成功）**通过，余量一个数量级**。

## 调研结论（2026-09-21）

第三方应用**无法调用系统那套碰一碰**，四条路逐条查证：

| 路径 | 结果 |
|---|---|
| 系统分享面板 → 互传 | 可用，标准 `ACTION_SEND`，今天就通 |
| 厂商互传 SDK | 小米、OPPO、vivo 开放平台均未对外提供，只有推送、传包、统一链接 |
| Android 原生 NFC P2P（Android Beam） | Android 10 废弃，Android 14 移除 |
| 自建 NFC 握手 + Wi-Fi Direct | 成立，即二期方案 |

另两条：Google Quick Share 2026 年起向第三方开放但**必须有 GMS**，国内机型无解；
华为 Share Kit 是唯一有应用内碰一碰 API 的，但属 HarmonyOS NEXT 的 ArkTS 接口，
Android 应用用不了。

互传联盟 2026-09-16 发布的碰一碰互传技术标准（小米 / OPPO / vivo / 荣耀，10 月中旬起
随系统推送）是 NFC 触碰握手 + Wi-Fi P2P 传数据，**直接做在各家系统底层**，应用侧的
接入点就是 `ACTION_SEND`。

## 仓库现状（本功能开始前）

- `SendInfoHelper.shareThing` 已走 `ACTION_SEND` / `ACTION_SEND_MULTIPLE` +
  `createChooser`，但产物是**拼接的纯文本 + 图片原件**，丢掉类型、颜色、清单层级、
  提醒规则、习惯周期、卡片外观、私密标记
- `ThingExporter` 导出的是给人阅读的 txt + 附件 zip，不可还原
- **全项目没有任何导入能力**
- manifest 已能作为 `ACTION_SEND` 接收方（`text/plain` / `image/*` / `video/*` /
  `audio/*`）创建新记事，但没有 `ACTION_VIEW` 文件类型关联

## Related Global Records

- `CONTEXT.md`：**Thing File** 词条
- ADR-0005：撤销机制已全局移除，状态变更统一事前确认——导入确认的形态由它决定
- ADR-0011（私密编码）：私密在标题前缀，序列化时必须显式去掉
- ADR-0012（同步不做端到端加密）：私密不承诺密码学保证，这是「去掉私密标记」的依据
- `docs/features/cloud-sync/plan.md` 5.3 字段分域表：序列化的字段映射照它写
- `docs/features/share-screenshot/plan.md`：分享对话框改造与附件勾选交互的来源
