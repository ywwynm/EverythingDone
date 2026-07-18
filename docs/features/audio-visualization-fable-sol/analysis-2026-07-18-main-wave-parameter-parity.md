# FableSol“主浪”参数双端一致性排查（2026-07-18）

> 状态：诊断发现的问题已由 D170 在 2026-07-18 当日收敛。Android 已删除两个旧 Punch 参数，
> `beat_gain` 两端均移入“环境与流动”，Python 面板已显式增加“主浪”组。

## 结论

Android 调参 Dialog 的“主浪”不是新增的视觉实体，而是把既有 `HeroWave` 参数显式列成了一个分组。
`HeroWave` 本身在 FableSol 首次移植 Android 时就已存在；分组 UI 则到 2026-07-17 才加入。

收敛前两端存在两种不同的不一致：

1. Python 的 `params.py` 注册了 6 个“主浪”全局参数，但 `ControlPanel` 从首个可追溯版本起就没有调用
   `_auto_group("主浪")`，所以这些参数不显示在面板里。它们不是被完整分散到其它栏目；只有逐层
   `hero_max_dp` 显示在“水体（逐层）”里。
2. Android “主浪”组共有 8 项，其中 6 项有生产链路并生效；`hero_punch` 与
   `hero_punch_decay_s` 是已失活的旧兼容项，当前生产代码从不写入 `heroPunch01` /
   `heroPunchBand01`，调节它们不会改变画面。Python 已在 2026-07-16 删除这两个参数及状态，
   Android 没有同步删除，却在次日创建调参 Dialog 时把它们列了出来。

## 时间线

- 2026-07-10 09:00，Python 首个可追溯快照 `3a0e440` 已包含 `hero_gain`、`hero_len_dp`、
  `hero_breath`、`hero_max_dp` 等主浪注册与 `HeroWave` 实现；同一快照的 `panel.py` 已经漏掉
  `_auto_group("主浪")`。因为这是仓库首个快照，Git 不能继续确定更早的原始加入时间。
- 2026-07-10 22:27，Android 提交 `81aa81a` 首次移植 FableSol，`FableSolHeroWave`、主浪参数、
  音频映射与模拟消费链同时进入 Android；当时没有用户调参入口。
- 2026-07-11 09:51，双端加固浪形连续性；主浪改为慢包络，快速事件只走 `DynamicWave`。
  `hero_punch` 已成为默认 0、没有实时生产者的旧兼容状态。
- 2026-07-16 11:06，Python 提交 `5daf6eb` 删除 `hero_punch`、`hero_punch_decay_s` 及其零状态链。
  Android 未同步这部分清理。
- 2026-07-17 16:10，Android 提交 `c88399b` 新增设置内调参 Dialog，“主浪”组第一次成为
  Android 可见栏目。D157 当时按“已注册且有读取点”收录参数，误把两个只会读取零状态的旧项也判为
  “实际生效”。

## 收敛前逐项活性

| Android “主浪”项 | Android 生产链路 | Python 当前状态 | 实际作用 |
|---|---|---|---|
| `hero_gain` | 生效 | 已注册、面板隐藏 | 缩放音频映射得到的主浪能量与振幅上限；同时影响一维主浪轮廓和连续水面的慢方向谱储能。 |
| `hero_len_dp` | 生效 | 已注册、面板隐藏 | 每个物理步重调六个色散模态的基准波长；值越大，轮廓越宽缓。 |
| `hero_attack_s` | 生效 | 已注册、面板隐藏 | 控制总主浪包络及低/中/高三组模态在能量上升时的追随速度。 |
| `hero_release_s` | 生效 | 已注册、面板隐藏 | 控制声音变弱后主浪能量的回落速度。 |
| `hero_punch` | **不生效** | 2026-07-16 已删除 | 只乘 `heroPunch01` / `heroPunchBand01`；当前生产路径从不写入这两个状态，它们恒为 0。 |
| `hero_punch_decay_s` | **不生效** | 2026-07-16 已删除 | 只负责衰减上述恒为 0 的旧状态。 |
| `hero_breath` | 生效 | 已注册、面板隐藏 | 对六个主浪模态做慢速、小幅能量交换；实现系数为滑杆值的 20%，不是整层同步缩放。 |
| `beat_gain` | 生效，但分组语义不准确 | 已注册、面板隐藏 | 当前只在拍点短暂加速近中层“环境波”相位；明确不加速或缩放 `HeroWave`，避免既有主浪轮廓在拍点变形。 |

Python “水体（逐层）”另有 `hero_max_dp`：它按层限制主浪振幅并参与音频目标映射。Android 运行时有
同一组九层默认值，但 D157 明确排除了全部逐层数组，所以 Android Dialog 没有对应控件。

## 收敛前为什么用户可能感到“主浪”没有作用

- 这组参数作用于模拟推进。调参预览暂停时，D161 会冻结 `Simulation.update`，因此主浪参数不会在
  冻结帧上立即显现；需要恢复播放。
- 主浪只接受慢音频包络，新的能量从上游画外出生区随流进入可见区，设计上约有 1～2 秒入场延迟；
  onset 等快速事件不会直接改写主浪。
- `hero_breath` 周期约 5.5～13 秒且幅度受 0.20 系数限制；`beat_gain` 又只在可信拍点改变环境纹理
  速度，二者都不适合用单个静止帧判断。
- `hero_punch` 与 `hero_punch_decay_s` 则无论是否播放都不会产生生产画面差异。

## 诊断与收敛验证

- 收敛前 Android：`FableSolWaveShapeContinuityTest` 确认 onset 不会改变旧 Punch 状态，慢主浪能量会从
  上游传播进可见区；静态全仓搜索也确认生产代码从未给两个 Punch 状态赋非零值。
- Python 确定性单变量消融确认 6 个共享参数有输出差异：`hero_len_dp` 改变轮廓、`hero_breath`
  改变模态合成、attack/release 改变包络响应、`hero_gain=0` 清空主浪储能；`beat_gain` 改变环境波
  相位而 `HeroWave` 相位差严格为 0。
- 收敛后 Android 完整 `:app:testDebugUnitTest` 通过；新增 `FableSolTuningCatalogTest` 锁定“主浪”只含
  5 个有效参数、`beat_gain` 位于“环境与流动”、两个旧 key 不再出现。
- 收敛后 Python 完整 159 项 `unittest` 通过；`test_parameter_registry_audit` 锁定同一分组合同，并确认
  `ControlPanel` 已生成“主浪”栏目。

## 建议的后续收敛

前三项已按 D170 完成：Android 删除 `hero_punch` / `hero_punch_decay_s` 全链；Python 显式展示
5 个有效主浪全局参数；`beat_gain` 两端均移入“环境与流动”。是否给 Android 补逐层
`hero_max_dp` 仍是独立 UI 范围决定，不与本轮收敛绑定。
