# 决策

本目录覆盖「设备方向权限受限的检测与提示」这一跨特性技术专项：一个共享看门狗加四个
倾斜数据消费者，横跨 `system-audio-recording` 与 `spatial-photo-effect` 两个特性。
根因分析见 `docs/features/system-audio-recording/analysis-2026-08-17-fablesol-tilt-after-source-switch.md`。

## 2026-08-17 - 倾斜数据消费者与触发条件的完整盘点

实时读方向传感器的四处：

| 代号 | 位置 | 传感器 | 开关 |
|---|---|---|---|
| A | 录音 Dialog `AudioRecordDialogFragment` | `TYPE_GRAVITY`→`TYPE_ACCELEROMETER` | `FableSolTuning.liveTiltEnabled` |
| B | 音频附件播放 Dialog `AudioPlayDialogFragment` | 同上 | 同上 |
| C | 音频海浪动画设置 Dialog 的预览 `FableSolTuningDialogFragment` | 同上 | 同上 |
| D | 空间照片 `SpatialPhotoView`（宿主 `ImageViewerActivity`） | `TYPE_GAME_ROTATION_VECTOR` | `SpatialPreferences.deviceTiltEnabled` |

间接依赖的一处：E — 导出保留录音时的倾斜（`fablesol_param_export_tilt`）。导出时不读
传感器，读的是 WAV 里的 `EDmo` 重力轨迹。受限窗口内录制的轨迹本身就是错的，**缺陷永久
写进音频文件**，事后授权也救不回来。这是唯一一处「提示晚了就没用了」的。

触发条件比 f25e079e 覆盖的宽：MediaProjection 授权页返回（已覆盖）、从任意外部全屏
Activity 返回（诊断证据 #3 实测，仅打开 Android 设置再返回即触发，延迟 6.04 秒）、
应用启动后约 6 秒内、应用内 Activity 跳转（未验证）、运行时权限弹窗（未验证，且
`AddAttachmentDialogFragment` 正好在打开录音 Dialog 之前请求 POST_NOTIFICATIONS）。

## 2026-08-17 - 检测改为与触发路径无关的首样本看门狗

按触发路径接线的做法要求先枚举全部路径，而路径清单列不全。改为：只要
`registerListener` 返回 true，就从注册那一刻起等 T1；期间零样本即判定受限。这一条判据
自动覆盖冷启动、外部返回、应用内跳转、权限弹窗与 MediaProjection 全部路径，不需要逐条
验证厂商的 launch-stage 语义。

判据只看**首样本**，不做常驻心跳。诊断证据 #4 表明「不注销监听器时流也会中断」，但四处
都在 `onPause` 注销、`onResume` 重注册，所以在当前代码下首样本判据是完备的；将来若某处
改成不注销，需要同步复查这一条。

T1 = 1.5 秒，四处统一。实测正常首样本延迟 16～41 毫秒，1.5 秒是 37～94 倍余量；传感器
回调跑在独立 `HandlerThread` 上，不受主线程首帧繁忙影响。`registerListener` 返回 false
不启动计时；音频附件的导出冻结期本就不注册，因此也不计时。

组件只抽看门狗，**不动**四处的 `prepareTiltSensor`/`startTiltSensor`/`stopTiltSensor`。
本次的缺陷是「没有提示」，不是「传感器代码重复」；混进重构会让真机验证分不清回归来源。
落点 `com.ywwynm.everythingdone.permission.DirectionSensorWatchdog`（这是一次权限可用性
探测，不属于录音特性），由 `views/recording/DirectionSampleDelayHintGate` 改造而来。

## 2026-08-17 - 提示分两层，每处用各自最合适的载体

第一层现场提示，第二层设置页可点说明行。

| 代号 | 载体 | 频次 | 持久化键 |
|---|---|---|---|
| A | 现有 `tv_audio_input_notice`，排在优先级链**最底**（`systemSilent`、`aecUnavailable` 之后） | 条件驱动，首样本到达即隐藏 | 无 |
| B | **新增**同款界内提示区 | 同上 | 无 |
| C | 开关下方条件说明行，可点跳系统设置 | 同上 | 无 |
| D | Toast | 整次安装一次 | `SpatialPreferences` 新键 |

设置页的说明行**不置灰开关**。「仅开屏时不允许」这一档下功能其实 95% 时间可用，置灰会把
可用功能报成不可用；而要区分它与「彻底禁止」必须等过 6 秒门禁，1.5 秒就置灰会让开关灰
4.5 秒再自己亮回来。

说明行不依赖任何 sticky 状态：它就是「本次注册至今仍未收到任何样本」的镜子，因此也天然
满足「用户改完权限回来即消失」。C 天然可用（预览本来就注册着传感器）；空间照片设置页
**不注册方向传感器**，若 D 那条线成立，该页需要自己短暂注册一次探测。

两个设置页的**消失时机故意不同**，判据是「这一页上有没有会打脸的实时画面」：

- 海浪动画设置 Dialog（C）的说明行**样本一到就消失**。它上方就是实时预览，受限窗口结束后
  预览恢复响应倾斜，此时还留着「画面无法响应设备方向」就是在说假话。
- 空间照片设置页没有任何实时预览，因此那一页的探测**一旦做出结论就保留到本次离开**：结论
  不会被后续样本改写，避免在受限窗口结束的瞬间闪一下。下次 `onResume` 重新探测。

持久化键必须按特性分开，不得混用一个键。f25e079e 的
`AudioInputPreferences.KEY_DIRECTION_SAMPLE_DELAY_HINT_SHOWN` 弃用删除——录音改成条件
驱动后不再有「弹过没弹过」这个状态，而「整次安装只提示一次」与「样本恢复后自动隐藏」在
语义上互斥，留着它只会让受限用户第二次遇到时反而看不到提示。

## 2026-08-18 - 两个设置页里「系统设置」是可点片段，按强调色着色且支持渐变

跳转不是整行的隐式行为，而是文案里「系统设置」这几个字本身可点，因此它们要用当前强调色
着色：海浪动画设置的强调色随记事颜色变（挂进 `applyUiAccent` 统一走的那组回调），空间照片
设置页固定是 accent + accent2 的渐变。整行仍保留点击，作为点偏了的兜底。

片段位置来自资源模板里的 `%1$s`（锚文本单独一条 `direction_sensor_restricted_settings`），
**不在译文里搜关键词**——13 个语言地区词序各不相同，搜关键词迟早在某个语言上失配。不可点的
三处（录音/播放提示区、空间照片 Toast）取格式化后的纯文本，因此那两处 XML 里不能再用
`android:text` 引用带占位符的资源，否则屏幕上会出现字面的 `%1$s`。

说明行本体的颜色取 `app_chrome_on_surface_hint`，与它上方那条「默认开启……」描述一致。

渐变必须用 `Layout.getPrimaryHorizontal` 查出片段位置、再用 shader 局部矩阵平移过去，并且
在 `updateDrawState` 里把 `alpha` 拉回 255。三种偷懒写法实机都验过是坏的：只按片段宽度生成
而不平移 → 句中片段落到 CLAMP 区外，成一块死板端点色；按整块文本宽度生成 → 片段只取到渐变
里很窄一段，落在浅色端时几乎看不清；不设 alpha → shader 颜色被 hint 色的低 alpha 乘成一片
淡影。

## 2026-08-17 - 一条通用文案，四处共用

现有 `audio_input_direction_samples_delayed` 的主语是「音频海浪动画」，四处主体不同，
改为通用的「画面」。必须保留「画面无法响应设备方向」这一句现象描述，以及「去系统设置
允许设备动作与方向」这一句操作指引；删掉「因权限设置原因」（与后半句重复）。约 38 字。

提示区的硬约束：录音 Dialog 固有宽 280dp，notice 左右各 36dp，文本可用宽 208dp，字号
11sp、无 `ellipsize` —— 中文每行约 18 字。原文案约 68 字需 4 行，`maxLines="3"` 会**硬
截断**掉操作指引且不显示省略号；英文约 240 字符需约 6.3 行，德语俄语更长。`maxLines`
提到 5，提示区稍微盖住波浪画面可以接受。

跳转用 `ACTION_APPLICATION_DETAILS_SETTINGS` 加 `package:` uri，`try/catch` 兜底退回
`ACTION_SETTINGS`。不使用厂商私有权限页 action——换 ROM 即失效。文案不出现任何厂商品牌。

## 2026-08-17 - 重力轨迹不再用伪造竖直姿态填充缺失

`FableSolGravityTrack.Collector.start()` 用 `lastX/lastY/lastZ` 种下 t=0，默认值是竖直
`(0,1,0)`。原注释假设「传感器早在准备态就已注册，录音开始时手上一定有一个当前值」——
这条假设正是本权限门禁打破的那一条。受限窗口内 `hasLast` 为 false，t=0 被种成伪造竖直，
再经零阶保持撑到约 6 秒后的首个真实样本。

只改 `hasLast == false` 这一支：不再种 t=0，让零阶保持把开头回填为**第一个真实姿态**。
正常路径（准备态已有样本，种子是真实姿态）零影响。

不升 `EDmo` v2、不加 `firstValidGrid`、不在导出后告知。格式层面表达「缺失」之后，对缺失段
最终仍只能选「保持第一个真实姿态」，额外收益只在「告知」这一项，不值得动读写兼容与 13
语言文案。

已知取舍：改后导出视频开头是第一个真实姿态，而用户录音时看到的实时预览是竖直，二者不
一致。选择更接近设备真实姿态的一侧——用户录音开头的姿态与 6 秒后大概率接近，而竖直是
任意值。

## 2026-08-17 - 空间照片这条线先验证再动（已验证，D 成立）

`TYPE_GAME_ROTATION_VECTOR` 是否同样被 stage 2 拦截没有实测过（诊断只测了
`TYPE_GRAVITY` 与 `TYPE_ACCELEROMETER`）。若它其实不被拦，D 的 Toast 与空间照片设置页
的探测都是永不触发的死代码。

**验证结论（2026-08-17 当晚，OPD2515）**：同样被拦，窗口 5.98 秒，D 与空间照片设置页照做。
另有一条否定结论：`dumpsys sensorservice` 的 `has sensor access` **不反映**这道门禁（拦截
期间全程为 `true`），因此首样本看门狗是唯一可行的判据。三个权限档位均可用 adb 驱动系统设置
界面切换并用 `appops get` 读回，A/B/C 对照实验不必模拟。详见
`analysis-2026-08-17-game-rotation-vector-intercepted.md`。

验证方式：adb 抓 logcat 的 `SensorInterceptByDirectionOp`，直接读它的 `type` 字段
（OPD2515 上此日志可读，上一轮诊断即由此得到结论）。不靠目视掐表——那只能测到「有停顿」，
分不清是传感器被拦还是渲染/生成卡顿。用户 2026-08-17 明确授权本次为此连接设备。

执行顺序：验证 `GAME_ROTATION_VECTOR` → 抽看门狗与单测 → 接入 A → 真机验 A（受限窗口内
提示出现、首样本到达后自动隐藏）→ B 与 C → 重力轨迹种子修正 → 视验证结果决定 D 与空间
照片设置页 → 13 语言文案 → 发布。
