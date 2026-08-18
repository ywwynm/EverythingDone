# 后续事项

## 待办：重力轨迹种子那一行没有单测（2026-08-18 记录）

`FableSolGravityTrack.Collector.start()` 调 `SystemClock.elapsedRealtime()`，而本项目单测既
没有 Robolectric 也没开 `testOptions.unitTests.returnDefaultValues`，那一行在纯 JVM 下抛
`Stub!`。本轮不为一个测试改全局测试配置，改为**解析产物**验证：受限窗口内起录，拉回 WAV 解析
`EDmo` 首格，按模长区分真实重力（9.8）与伪造竖直（1.0）。

若将来要补单测，最小改动是给 Collector 注入时钟；改 `returnDefaultValues` 会影响全部 921 项
单测，不应为此开启。

## 待办：倾斜传感器三件套的重复实现（2026-08-17 记录）

A/B/C 三处的 `prepareTiltSensor` / `startTiltSensor` / `stopTiltSensor` 是三份几乎逐行
相同的拷贝：同样的 `TYPE_GRAVITY`→`TYPE_ACCELEROMETER` 回退链、同样的 `HandlerThread`、
同样的 registered 标志。本轮明确**不动**它们，只抽看门狗，以免真机验证时分不清回归来源。

若将来做这次重构：B 叠着 `FableSolExportFreezeGate`（导出冻结期不注册）、A 叠着重力轨迹
投递与采样序号，两处的生命周期语义并不完全相同，不能简单合并。重构应当行为不变、可用单测
与真机分开验证。

## 待办：EDmo 轨迹缺失段的如实表达与导出后告知（2026-08-17 记录）

`EDmo` v1 每格必须三个 float，格式层面没有「缺失」这个表达。本轮决定不升 v2：对缺失段
最终仍只能选「保持第一个真实姿态」，升级格式的额外收益只在「导出后告知用户开头约 X 秒
没有真实倾斜数据」这一项。

若将来产品上认为该告知必要，则需要：v2 增加 `firstValidGrid` 字段、读写保持对 v1 的兼容、
新增一条 13 语言文案。

## 待验证：首样本判据对「流中断」失明的边界（2026-08-17 记录）

诊断证据 #4 表明不注销监听器时事件流本身也会中断约 6 秒。当前四处都在 `onPause` 注销、
`onResume` 重注册，因此首样本判据是完备的。**若将来任何一处改成跨界面保留注册**，看门狗
会静默漏报，届时需要把判据改成「距最近一个样本已超过 T1」并加低频心跳。

## 待验证：应用内 Activity 跳转与运行时权限弹窗是否触发 stage 2（2026-08-17 记录）

两条路径都没实测过。它们不影响本轮实现（首样本看门狗与触发路径无关），但会影响用户实际
遇到提示的频率判断。特别是 `AddAttachmentDialogFragment` 在打开录音 Dialog **之前**请求
POST_NOTIFICATIONS，位置极其不巧。
