# 弹窗叠弹窗时粒子被下层弹窗盖住

## 现象与根因

详情页打开「改变颜色」面板、再点「从世界取色」，取色弹窗的出现与消散粒子都跑在颜色面板的
**下面**；而首页 contextual menu 的同一条路径层级是对的。

真机确认（OPD2515，2026-09-16）：截图里粒子只出现在颜色面板矩形**之外**的那一条，到面板边缘
就被白色面板盖住。

根因是粒子表面的位置。`ParticleWindowSurface` 是挂在 Activity DecorView 上的 SurfaceView，
`setZOrderOnTop(true)`——它在 Activity 窗口之内最上，**但仍在 Dialog 这种独立窗口之下**
（这一句本来就写在该类的注释里）。两边的差别正好卡在这里：

| | 改变颜色面板是什么 | 结果 |
|---|---|---|
| 详情页 | `ThingBackgroundEditorBottomSheet : BaseDialogFragment` → **独立 Dialog 窗口** | 在粒子表面之上 → 盖住粒子 |
| 首页 | `mThingCardAppearanceEditor` → **页内 View 面板** | 在粒子表面之下 → 正确 |

## 为什么不能把粒子挂进下层弹窗的窗口

本项目的弹窗主题是 `windowIsFloating`、按内容尺寸布局（`setLayout(width, WRAP_CONTENT)`）。
把粒子表面挂进颜色面板那个窗口会被它的矩形裁掉——而粒子要飞出卡片一个身位。挂进取色弹窗
自己的窗口同理。只有**另起一个全屏、且在那些弹窗之后加入的窗口**才同时满足「在上面」与
「不裁剪」。

## 做法

新增 `ParticleElevatedHost`：一个全屏、透明、不可聚焦不可触摸、无窗口动画、无系统 dim 的
`Dialog`，只是一块画布，焦点与触摸照旧穿过去。需要时显示、空闲即撤。

启用判据由 `ParticleDialogStack` 给出，只有一条：**这个弹窗下面还压着另一个会留下来的弹窗**。
`BaseDialogFragment` 在 `onStart` 登记、`onDismiss` 注销，粒子消散接管时标记「正在离场」——
正在离场的弹窗不计入。**A 关闭紧接着 B 打开因此仍走 Activity 那张常驻表面**：现有设计把并发
会话合成进同一张表面、交接靠同一份帧时间戳，把 B 抬到另一个窗口就成了跨窗口交接（本项目记过
「跨窗口交接必闪」）。

`ParticleWindowSurface` 的索引从「一个 Activity 一个」改成「一个承载 decor 一个」，同一个
Activity 因此可以同时有两张表面。承载 decor 经 `ParticleDismissController` 传到
`ParticleDismissOverlay`。

两处边界值得记下来：

- **承载窗口 `show()` 之后 decor 要等下一次遍历才附着**，而消散是同步判定的（`startContent`
  一开始就检查 `hostDecor.isAttachedToWindow`）。第一版实测直接得到「消散未受理：宿主 decor
  未附着」，整场不播。现在两条一起兜：叠加状态还在时不撤窗口（消散紧跟在出现之后，重建来不及
  附着），以及承载 decor 没附着就退回 Activity 表面——层级仍是旧的，但绝不会因此整场不播。
- 承载窗口在 Activity 的 decor 从窗口摘下时一并收掉，否则留下 `has leaked window`。

## 真机验证（OPD2515）

判据打了一行 debug 日志（`elevate=... stack=...`），逐场景确认闸门的启用与否：

| 场景 | 闸门 | 结果 |
|---|---|---|
| 详情页 颜色面板 → 从世界取色（出现） | 启用 | 粒子盖在面板之上，35 帧 |
| 同上（消散） | 启用 | 粒子盖在面板之上，62 帧 |
| 单个弹窗（首页「全部完成」确认框）开／关 | `elevate=no stack=1` | 34 / 62 帧，与改前一致 |
| A 关闭紧接着 B 打开 | `elevate=no stack=1` | 34 / 62 帧；实测 A 的窗口在消散期间仍吞触摸，从操作栏根本点不开 B |
| FableSol 调参弹窗（设置页，自带 SurfaceView）开／关 | `elevate=no stack=1` | 34 / 62 帧 |
| 新建记事入场 | 不经过闸门 | 35 帧，`ThingCreationTransition` 直接调 `appearContent`，没有 `elevate` 记录 |
| 首页调整外观面板（页内面板）开／关 | 不经过闸门 | 34 / 62 帧 |
| 左滑完成记事 | 不经过闸门 | 记事正常完成（17 → 15 条），`ParticleSwipeAnimator` 走 Activity 表面 |

也就是说：**除了弹窗叠弹窗这一种，其余路径一条都没有进入新代码**。

## 没有验到的

- `ThingDoingDialogFragment` → 选择做事时长是全项目第二处弹窗叠弹窗，走的是同一个
  `startCondense` / `start` 入口与同一个闸门，但没有在设备上单独复现（需要一条提醒或习惯记事）。
- 首页「调整外观」面板里的从世界取色：面板是页内 View 而不是窗口，闸门必然不启用、层级本来
  就正确；本轮没有走通那个面板里的取色入口（`Pick from world` 在该面板里没找到同名控件）。
- 只在 OPD2515 上验过。窗口层级在不同 ROM 上的行为本项目踩过坑，三星那台没有连接。

设备状态：测试记事已移入可恢复的回收站，首页恢复为 2 个文件夹 15 条记事，三项动画档位
（滑动完成 0、新建 2、弹窗 3）与开始时一致，设备上的临时文件已删。

## 发布复核

阿里云 debug 更新 `202609160339` 已发布。本地发布产物、远端完整 APK 与远端 `latest.json`
声明的 SHA-256 均为 `850d6bcbe90684c79c2ba49dbc8284b9fae141f58d41e1197a5da64c6f2d8536`，
26,285,004 字节，含完整 releaseNotes（1501 字）。本轮未创建 Git 提交。

## `202609160339` 的黑屏回归与修正

用户实测 `202609160339`：详情页点「从世界取色」**整屏直接黑屏**，按返回倒是能看到取色弹窗的
消散粒子。实测整屏近黑像素 99.3%。两个原因叠在一起，都是本轮引入的：

1. **承载窗口是不透明的。** `dumpsys window` 里它的 `mAttrs` 没有 `fmt=`，即 `PixelFormat.OPAQUE`
   ——主题里的 `windowIsTranslucent` **不会**替 `Dialog` 设置窗口格式，必须显式
   `window.setFormat(PixelFormat.TRANSLUCENT)`，并把 decor 背景显式设成透明。
2. **「承载 decor 未附着就回退」那道兜底把出现这条路整个抵消了。** 承载窗口是刚 `show()` 出来
   的，decor 必然还没附着，于是出现动画每次都回退到 Activity 表面（抬升等于没做），而窗口已经
   显示、又被「叠加状态还在就不撤」保留着——一块没人用的不透明全屏画布盖住整个屏幕。

出现这条路本来就是**异步**取宿主的：真正用到 `hostDecor` 是在 preDraw 之后的捕获回调里，那时
承载窗口早已附着。因此同步的附着检查只能留给消散（`startContent` 一开始就要判定），出现那一路
必须直接用传进来的承载 decor。

### 这次为什么没测出来

第一版的截图（`e*`、`x*`）整屏近黑只有 0%–5%，画面确实是对的——**但那之后我又改了代码**（保留
承载窗口、未附着回退、调试日志），而后续的验证只看了 `elevate=` 日志与帧数，**没有再看一次
画面**。改完代码只核对日志、不重新看图，是这次漏检的直接原因。

### 修正后的验证（整屏，不再只看裁剪）

| 帧 | 整屏近黑 | 内容 |
|---|---:|---|
| 出现 +400ms / +800ms | 0.0% / 0.0% | 粒子明确盖在 ADJUST COLOR 面板之上（圆点、滑条、按钮全被粒子覆盖） |
| 出现 +2.8s / +5.8s | 0.7% / 0.7% | 取色弹窗正常显示在面板之上 |
| 消散 +300ms / +700ms | 0.7% / 0.0% | 消散粒子盖在面板之上 |
| 消散 +3.7s | 0.0% | 回到面板，无残留 |
| 单个弹窗出现 | 1.9% | 首页与粒子正常，未创建承载窗口 |
| 单个弹窗消散 | 3.5% | 同上 |
| 新建记事入场 | 0.0% | 同上 |

单测 1036 项，唯一失败是既有的 `FableSolCurveFairnessTest`。

### 修正版发布复核

阿里云 debug 更新 `202609160402` 已发布。本地发布产物、远端完整 APK 与远端 `latest.json`
声明的 SHA-256 均为 `6b6971cbd7490109c81519f8e716c7fb8f1db9d9210331006b9d32fdd6d13949`，
26,285,004 字节，含完整 releaseNotes（1087 字）。本轮未创建 Git 提交。

## 补测一：提醒记事详情页 → 开始做事 → 选择做事时长

`DetailActivity` 里提醒/习惯记事点右下角开始做事，打开的是 `ThingDoingDialogFragment`；
在它上面再点一行设置（`ll_auto_start_doing_as_bt`），会叠一个 `ChooserDialogFragment`——
这是全项目**第二处弹窗叠弹窗**，此前只推理过、没在设备上跑过。

新建了一条临时提醒 `PROBE-REMIND`（勾选快捷提醒使其成为 `Thing.REMINDER`；普通记事点开始做事
会直接进 `StartDoingActivity`，到不了这一处）。实测：

```
12:57:57.319 elevate=true stack=2 dialog=GestureAnchoredDialog
12:57:58.194 出现完成 particles=106176 frames=35
12:58:06.826 elevate=true stack=2 dialog=GestureAnchoredDialog
12:58:08.249 消散完成 count=106195 frames=62
```

七帧整屏近黑分别为 0.0% / 0.3% / 0.3% / 0.3% / 0.3% / 0.1% / 0.0%，**没有黑屏**。逐帧看画面：

| 帧 | 内容 |
|---|---|
| 出现 +380ms | 粒子明确盖在开始做事弹窗的白卡之上（「Auto start doing time」那几行被粒子覆盖），并溢出到卡片左侧的深色背景上 |
| 出现 +900ms / +2.9s / +5.9s | 选择弹窗稳定显示在开始做事弹窗之上 |
| 消散 +330ms / +630ms | 消散粒子盖在白卡之上，CONFIRM 正在碎开，粒子溢出到卡片右缘之外 |
| 消散 +3.6s | 回到开始做事弹窗，无残留、暗层档位正确 |

即抬升在这一处同样生效，层级正确。

## 补测二（未完成）：更突出的记事通知 → 开始做事

这条路的结构与上一条不同：`NoticeableNotificationActivity` 自己是一个 `useParticleContentDialog`
的内容弹窗（单窗口栈），点「开始做事」后它 `finish()`，再由 `ReminderNotificationActionReceiver`
起 `StartDoingActivity`，其中只有一个 `ChooserDialogFragment`。两个 Activity 各自 `stack=1`，
按设计都不该抬升。

已拿到的证据：`NoticeableNotificationActivity` 的内容弹窗出现时日志为
`elevate=no stack=1 dialog=GestureAnchoredDialog`，闸门判定正确；`dumpsys window` 里该 Activity
的两个窗口分别是 `ty=BASE_APPLICATION fmt=TRANSPARENT`（Activity 自身）与
`ty=APPLICATION fmt=TRANSLUCENT (672xwrap)`（内容弹窗），**没有多出承载窗口**。

缺的是画面：驱动过程中平板被锁到 6 位数字 PIN 的锁屏后面，ColorOS 的锁屏下 `screencap` 一律
返回全黑，后续步骤无法进行。解锁后一条命令即可补齐（脚本见下）。

### 这条路怎么起：三个已经踩过的坑

1. **真实闹钟起不来这个弹窗。** OPD2515 上 `appops get com.ywwynm.everythingdone
   USE_FULL_SCREEN_INTENT` 是 `default`，闹钟到点时通知照常发出、但 full-screen intent 被拒
   （`rejectTime` 正好是响铃时刻），Activity 根本不会起。**授权属于设备设置，不代为改动**，
   改走调试包的入口。
2. **`run-as ... am start` 起不了非导出 Activity。** `am` 把调用包写死成 `com.android.shell`，
   框架校验 `package=com.android.shell does not belong to uid=10347` 直接抛
   `SecurityException`。
3. 因此扩展了调试包的 `ParticleDialogRouteProbeActivity`：`route=notice` 原先只接受「当前打开
   的普通记事」，现在多认一个显式的 `thingId` 外部参数，用于验证提醒记事上的动作按钮。默认
   路径不变。

```powershell
& $adb -s 9018f404 shell am start -n com.ywwynm.everythingdone/.views.particledismiss.ParticleDialogRouteProbeActivity `
    --es route notice --el thingId <记事 id>
```

驱动脚本：会话 scratchpad 的 `notice3.ps1`（开头自带锁屏判据，锁着就直接退出）。
