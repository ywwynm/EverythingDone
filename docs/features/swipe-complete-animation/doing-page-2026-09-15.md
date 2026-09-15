# 正在做事界面接入滑动完成动画

## 需求

用户报告：正在做事界面左右滑动完成记事，动画没有继承设置里的档位；档位选「粒子」时应当和
首页记事列表左滑一样播粒子。该界面**左右滑都是完成记事**，两个方向只是方向不同、效果相同。
同一轮把设置项的文案从「左滑完成记事动画风格」改为「滑动完成记事动画风格」。

## 两边原本的差别

首页的粒子滑动由 `ThingsActivity` 接：`dispatchTouchEvent` 喂触点、`OnItemTouchListener` 在
按下那一刻预热快照、`onChildDraw` 在 `super` 之前把粒子画进去、`onSwiped` 把完成动作推迟到
消散播完、`clearView` 收尾。`DoingActivity.CardTouchCallback` 这五处一处都没有，只有原本的
整体淡出，因此档位设成粒子也没有任何变化。

`ParticleSwipeAnimator` 此前只为左滑写死（见 2026-09-13「粒子运动固定向左」）。四处随方向
变号：进度取 `-位移/宽度`、消散虚拟触点放在卡片左外侧、完成后把卡片停在左侧屏外、右滑一律
按「方向不对」取消预热。

## 改动

- 新增 `ParticleSwipeDirection`，把上述变号的三个算式集中成纯函数（方向判定、进度位移镜像、
  虚拟触点横坐标、完成后的停靠位移），5 项 JVM 单测逐项定死。符号写反在真机上表现为「粒子
  朝反方向飞」或「进度永远是 0」，本地能定死就不靠真机逐个方向试。
- `ParticleSwipeAnimator` 增加 `allowRightSwipe`。打开时预热不再因右移取消，方向由**第一次
  越过 touch slop 的横向位移**决定——这比 ItemTouchHelper 判定滑动更早，消散方向赶得上在
  识别之前定下来。快照捕获与建动画层因此拆开：定向之前只留快照，定向之后才按方向建层，
  首页仍在捕获回调里直接建层，起步时延不变。同一次手势里划过原点换方向时，只要还没有粒子
  上屏就允许改向（重建动画层），已经上屏就锁定。
- `DoingActivity` 接上述五处入口，`allowRightSwipe = true`。
- 快照按容器透明度补偿：做事期间记事卡片所在列表常驻 0.84，而粒子画在宿主窗口那一个共享
  Surface 上、拿不到这个值，直接播会在接管的那一帧整体提亮约两成。现在把各级容器的透明度
  之积烤进快照（首页各级容器都不透明，是空操作），同时在粒子接管期间把列表这一层还原成
  不透明——准备期在卡片原位补画的内容和粒子层因此都恰好是原来的浓度，交接处没有亮度跳变。
- 顺带修掉一处既有问题：滑到一半松手回弹时 `dX` 归零，原来的算式给出完全不透明，列表会一直
  停在比进入时更亮的浓度上。`clearView` 现在恢复做事期间的常驻浓度，并把 0.84 提成具名常量。

## 文案

英文原本是 "Swipe-to-complete animation style"，而项目里完成记事一律用 finish
（`act_finish`、`finish_all_things_in_folder`、`welcome_underway_content` 的 "Swipe left to
finish"），这是唯一一处 complete，改为 "Swipe-to-finish animation style"。中文简繁、日文、
韩文原本写的是「左滑／左スワイプ/왼쪽 스와이프」，去掉方向限定。德、西、法、印地、意、葡、俄
七种原本就是不带方向的「滑动完成」，未改动——其中西班牙语与意大利语用的是 complete 的同源词，
与各自的 `act_finish` 不完全一致，但那是各语言的用词取舍，留给用户裁定。资源名
`settings_swipe_complete_animation` 与 SharedPreferences 键 `swipe_complete_animation` 不动：
前者只是标识，后者一改就会把所有人已选的档位重置。

## 验证状态

`:app:assembleDebug` 通过；单测 1028 项，唯一失败是既有的 `FableSolCurveFairnessTest`。
**没有上真机。** 方向相关的算式已在 JVM 上定死，但下面几项只能在真机上确认：

- 粒子 Surface 用 `setZOrderOnTop(true)`，而做事界面的窗口带 `FLAG_SHOW_WALLPAPER` 且
  `PixelFormat.TRANSLUCENT`，这个组合本项目此前没有跑过。
- 左右两个方向的消散是否真的各自朝手指滑走的一侧飞出。
- 接管处的浓度是否真的没有跳变（改动按 0.84 推算，未实测像素）。
- 甩动、滑到一半松手回弹、滑过原点换方向这三条路径。

## 发布复核

阿里云 debug 更新 `202609150810` 已发布。本地发布产物、远端完整 APK 与远端 `latest.json`
声明的 SHA-256 均为 `397a2a0f55279dc562a10bae9f8d054414290ed3e62d4063d2f065d70c2462c0`，
26,282,932 字节，含完整 releaseNotes（1357 字）。本轮未创建 Git 提交，未连接任何真机——
上面四项待确认的内容要在这个发布包上实际试。
