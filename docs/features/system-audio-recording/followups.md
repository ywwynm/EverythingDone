# 后续事项

## 已撤销：通知返回后 HDR 未复原的观察系误判（2026-08-16 核实）

受控实验（同一会话：录音 → HOME → 通知返回，以 `fablesol_hdr.log` 为证）证明 HDR
复原完全正常：HOME 时 display-ratio 释放回 1.0，返回后约 700ms 内 HDR surface 重建
（`fp16-linear-scrgb`）且 headroom 拉回 4.93（与全新会话一致），截图视觉亦一致。
最初对比里的"偏灰画面"实为当时（swap 竞争修复前的构建）Canvas 回退激活中的软件
渲染画面——Canvas 路径本无 HDR，该回退已被 swap 失败分级处理根治。无需修复。

## Android 10+ 真机验收（部分完成，2026-08-16）

2026-08-16 已在 OPD2515（OPPO 平板，Android 16）完成：三种来源切换、授权允许/拒绝、跨 Dialog 记忆、后台前台服务录音接续、真实系统声音采集（对照静默基线）。仍待其他设备验证的项：

- 系统状态入口撤销投影、锁屏触发结束投影的收尾表现；
- `AEC` 在混合模式下的实际可用性与音质（OPD2515 上 `aec=false`，AEC 效果器创建失败或不可用，尚未深查）；
- 禁止 playback capture 的 App（如多数国内音乐 App）触发 6 秒静音提示的界面表现；
- 三星真机整条链路（用户另一台 OPPO 手机的现象与平板一致，预计已由同一修复覆盖，仍建议装新包确认）。

若发现厂商差异，应记录设备型号、Android 版本、来源 App、选择模式和通知权限状态。

## 已知边界：引擎任务真卡死无法自恢复（2026-08-16 用户裁定不修）

所有引擎任务共用单线程执行器，且 `AudioCaptureEngine.configure` 持对象锁。若厂商
`AudioRecord` 初始化真的永久不返回，8 秒超时只能恢复 UI 状态，后续配置任务仍会排在
卡死任务与引擎锁后面。彻底修复需要废弃并重建 engine+executor 并迁移 FableSol 消费者，
复杂度与"从未实测发生过"的概率不成比例；裁定接受现状，重启 App 即可恢复。若真机
出现过一次，再启动全案。

## 来源 Popup 打开时首项可能滚出可视区（2026-08-16 用户裁定不修）

`AudioInputPicker` 的窗口高度按 `ITEM_HEIGHT_DP = 108dp` 估算三项总高，但"系统""麦克风+系统"的英文摘要实际排到 6 行以上，单项高度可达约 200dp。打开时 `scrollToPosition(选中项)` 会把"麦克风"整项滚出可视区，用户看不到第一项，需要手动上滚。可选修法：按测量后的真实内容高度收敛窗口高度，或打开时改用 `scrollToPositionWithOffset` 保证首项可见，或压缩摘要文案行数。

## 探针日志的保留位置

2026-08-16 排查时在授权结果回调、`prepareSystemModeInternal`、`fallbackToMicrophoneInternal` 三处保留了 `BuildConfig.DEBUG` 下的 `AudioRecProbe` 日志。若后续认为冗余可移除，但建议保留：授权链路一旦再次断裂（如新增 Activity 覆写 `onActivityResult` 忘调 `super`），这三条日志可以在一轮复现内区分"回调未到达"与"服务配置失败"。
