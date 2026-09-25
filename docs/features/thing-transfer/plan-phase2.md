# 二期（Tap Transfer）实现结构

决策见 decisions.md 最前面的 2026-09-24 各条；动画要求见 preferences.md；实现过程与真机验证见
sessions.md 的 2026-09-24「二期实现」一条。

## 分层

```
taptransfer/
  TapTransfer.kt                    入口判定、缺哪几项、设备名、天线位置、调试开关、传输工厂
  protocol/                         纯逻辑，JVM 单测覆盖
    TapHandshake.kt                 握手的二进制编解码（版本、令牌、端口、SSID、密码、设备名、条数、颜色）
    TapApdu.kt                      APDU 命令与状态字
    TapNfcExchange.kt               TapCardResponder（卡模拟端应答）、TapReaderExchange（读取端命令序列）
    TapStream.kt                    TCP 帧：令牌 → 状态 + 长度 + 内容 → 确认字节
  transport/
    TapTransport.kt                 发送方、接收方的接口与失败原因
    TapPayloadWriter.kt             分享界面一打开就在后台写记事文件
    nfc/TapHceService.kt            卡模拟服务 + TapSenderGate（只有分享界面准备好时才应答）
    wifi/WifiDirectSenderTransport.kt   建组、开端口、按令牌服务、同一台只收一次
    wifi/WifiDirectReceiverTransport.kt 读取模式、按 SSID 与密码入组、收文件、离组
    sim/                            调试包里的模拟传输，走完全相同的界面与导入代码；模拟接收可以发来带图片、
                                    封面在上 / 下 / 左 / 右 / 作背景的样例（调试配置 simulate_samples）
  ui/
    TapProgress.kt                  TapProgressModel（真实进度 → 目标进度）、ProgressSmoother（目标 → 显示）
    TapSetupNavigator.kt            权限申请、NFC / Wi-Fi 快捷面板、位置信息设置
    TapPrerequisiteWatcher.kt       NFC / Wi-Fi / 位置信息开关的状态广播（开关打开要一两秒）
    TapAntennaClearance.kt          卡片让开天线：天线落在卡片里时把卡片挪到它的上方或下方
    card/TapThingCards.kt           用首页卡片的布局画一条记事（继承首页适配器），装卡片的容器
    flight/TapParticleFlight.kt     碰一碰的粒子：流出（手势会话）、拼出（倒放，可多张卡片合成一张快照）
    flight/TapParticleParams.kt     粒子参数（shared/tap-transfer/particles.properties）
    flight/TapFixtureDump.kt        调试：飞行输入落盘给 PC 模拟器
    flight/TapSessionRecorder.kt    调试：整段会话（事件时间线 + 各层截图）落盘给 PC 模拟器
    particles/                      只剩接收状态的屏幕边框在用
      FlowNoise.kt                  梯度噪声与旋度场
      ParticleSpriteBatch.kt        drawVertices 一次画完全部粒子，拖尾头亮尾淡
      PerimeterParticles.kt         沿圆角矩形周长流动的粒子
      RoundRectPerimeter.kt         圆角矩形的弧长参数化
      StageClock.kt                 动画时钟与拖尾换算
    share/
      TapShareOverlay.kt            分享界面：暗层、舞台、面板，三项与碰一碰状态，流出与流回的编排
      TapShareCardStage.kt          抬升、叠放、让开天线、天线处的等待圈与光点
      TapSendController.kt          发送方状态（准备 / 就绪 / 连接 / 发送 / 已发送 / 失败）
    receive/
      TapReceivePull.kt             下拉手势与按钮
      TapReceiveStageView.kt        接收状态的罩层（可为新卡片留出空洞）与屏幕边框（碰上时换成来件的颜色）
      TapReceiveController.kt       逐项引导、接收状态、导入、等列表腾位 / 滚动 / 图片、在空位里拼出
```

粒子本身是 `views/particledismiss` 的消散引擎，碰一碰只多一个终点：`ParticleDismissSpec.sink`
（默认 null）与着色器里的 `#ifdef SINK` 一段，终点的寿命改写在 `ParticleSink.shape`。终点可以是
天线一点，也可以一部分微片流向屏幕边框离它最近的那条边（`ParticleSink.target` 与着色器的
`sink_target` 逐项对应）。带终点的倒放从首帧上屏起算时钟。其余动画的规格里没有终点，着色器也
不编进这一段。

界面与动画只依赖传输接口；模拟开关打开时换成模拟传输，界面、动画与导入的代码完全相同，
模拟跑通的部分就是真实使用时的部分。

## 协议

- NFC：发送方卡模拟服务只在分享界面打开、Wi-Fi Direct 组建好之后应答（`TapSenderGate`），其余
  时候一律回 6985。命令为 SELECT（AID `F0 45 44 54 41 50 31`，与验证程序的 AID 不同）、GET（按偏移
  分段读握手，每段 ≤ 240 字节，不依赖扩展长度 APDU）、HELLO（接收方的随机标识与设备名；发送方据此
  认定碰上了哪一台）。接收方读完握手先查令牌是否已收过，再发 HELLO。
- 握手来自碰上来的任何设备，解码时严格校验：UTF-8 必须合法；组名必须以 `DIRECT-xy` 开头、
  不超过 32 字节，密码 8 到 63 个字符——这两项原样交给 `WifiP2pConfig.Builder`，不合规的值在那里
  会抛异常。
- 握手里的颜色（最多 12 个）让接收方碰上时就把屏幕边框换成来件的颜色。握手不带内容预览：接收方
  等数据到齐再在空位里拼出卡片（decisions.md「接收方在列表的空位里直接拼出」）。
- TCP：接收方先发令牌与 HELLO 里报过的接收方标识；发送方两项都对上、且此刻正在等这一台（一次
  HELLO 只放行一条连接），才发状态、8 字节长度与记事文件，接收方收齐写好文件后回 1 字节确认；
  发送方等到确认才算发完。状态码：0 成功、1 令牌不对、2 不是正在等的那一台（没发 HELLO、已超时、
  已经连上过一条）、3 记事文件没写出来。同一次分享里某一台收完之后再碰会被告知已收过。
- 时限：发送方从 HELLO 起 25 秒内等不到连接判失败；接收方从碰上起 22 秒内连不上就先判失败，
  不会在发送方已经判失败之后才连上来。发送方最多等 120 秒写完记事文件，接收方读头部的时限相应
  放到 130 秒，数据段仍是 15 秒。
- Wi-Fi Direct 组是整机共用的。发送方只拆自己建的组：建组之前先查设备上有没有组，没有就建；
  有且是自己上次没拆掉的（`tap_transfer_state.xml` 的 `group_created`）就拆掉重建；是别的应用的
  （投屏、打印）就不动，这次碰一碰不可用。接收方离组时只拆这一次加入的那个组（按组名核对）。
- 接收方回了确认字节之后，即使用户已经退出接收状态，这份文件也照常导入（不播落位）：发送方
  那边已经显示「已发送」了。

## 导入

`ThingFileImporter.import` 增加落点文件夹与逐条提交回调：碰一碰收到的记事落在发起接收时所在的
文件夹，每条提交在主线程的同一条消息里通知列表插入，并先藏起来（`ThingsAdapter.setThingHidden`）。
颗粒在空位里拼好的那一刻显出来；屏幕外的几条直接显示。

## 调试开关

都在 `shared_prefs/tap_transfer_debug.xml`，只在调试包生效：

- `simulate`：设置页「碰一碰模拟（调试包）」。打开后分享界面几秒后模拟对方碰上，接收状态几秒后
  模拟收到两条样例记事（标题写明是模拟）。每次打开分享界面、每次进入接收状态只模拟一次。
- `simulate_failure`：界面上没有入口，用 run-as 改。模拟传输传到一半时失败，用来看失败时两边的动画。
- `simulate_samples`：界面上没有入口，用 run-as 改。模拟接收发来哪几条样例，逗号分隔：`plain`、
  `top`、`bottom`、`left`、`right`、`background`（封面在左、右只对宽卡片有效，所以 `left`、`right` 是宽卡片）。
  不设时是两条纯文字。
- `dump_fixtures`：界面上没有入口，用 run-as 改。每次打开分享界面、每次进入接收状态，把整段会话写到应用
  外部私有目录的 `tap-fixtures/<时刻>-<send|receive>-session/`（`TapSessionRecorder`：事件时间线、各层截图，
  粒子飞行的输入在其中的 `flight-<n>/`，由 `TapFixtureDump` 写），给 PC 模拟器重放，见 `particle-sim/README.md`。

## 粒子参数

碰一碰动画的全部可调量在 `shared/tap-transfer/particles.properties`（打进 assets），由
`flight/TapParticleParams` 读取并校验；终点那一组以 `ParticleSink.Tuning` 传给粒子引擎，转向区间经
uniform `sink_homing` 进着色器。除粒子之外还包括发送的打开阶段（罩层、面板开合、抬起与落回、投影、扩散圈、
天线光）与接收舞台（罩层、屏幕边框粒子、调色板、那圈光、空洞合上）。PC 模拟器读写同一份文件。
