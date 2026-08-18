# 实机验证：GAME_ROTATION_VECTOR 同样被方向权限拦截

日期：2026-08-17
设备：OPD2515（`9018f404`），Android 16 / ColorOS，系统语言中文

## 结论

`TYPE_GAME_ROTATION_VECTOR` 与 `TYPE_GRAVITY`、`TYPE_ACCELEROMETER` 一样被 ColorOS 的
`DIRECTION_SENSORS` 开屏门禁拦截，因此空间照片这条线（D 与空间照片设置页）成立，不是死代码。

## 决定性证据

权限保持在「仅开屏时不允许」（`appops` 读作 `default`），空间照片处于激活状态、
`SpatialPhotoView` 的传感器连接已建立。用 `am start -a android.settings.SETTINGS` 盖上
系统设置、3 秒后 `KEYCODE_BACK` 返回，日志：

```text
23:57:44.120 D SensorService: SensorInterceptByDirectionOp: pkg=com.ywwynm.everythingdone,
             uid=10347, type=android.sensor.game_rotation_vector, stage=2
23:57:44.120 D SensorServiceExtImpl: setSensorInterceptStats uid=10347, hasStats=1
23:57:50.104 D SensorServiceExtImpl: setSensorInterceptStats uid=10347, hasStats=0
```

拦截窗口 **5.98 秒**，与厂商说明的「应用启动时 6 秒内」一致。触发动作是**从另一个 Activity
返回前台**，与 MediaProjection 无关——这也再次印证了诊断证据 #3。

## 否定结论：`has sensor access` 不能当判据

`dumpsys sensorservice` 的连接行带两个诱人的字段：

```text
com.ywwynm.everythingdone : ...spatial.SpatialPhotoView | ... | has sensor access: true | direction perm: 3
```

在上述整个 12 秒采样窗口内（每 450ms 采一次，共 25 点），`has sensor access` **全程为
`true`**，而同一时段日志明确记录了 5.98 秒的拦截。**门禁实现在事件投递路径上，不体现为连接的
access 标志**。历史行里出现过的 `[access: false | perm: 3]` 是别的原因（应为后台态），不要
误读成门禁指示器。

应用侧也读不到这两个字段。因此「首样本看门狗」是唯一可行的判据。

## 权限档位的驱动手法（OPD2515，2520×1680 横屏）

`adb shell appops set` **写不了**这个 op —— ColorOS 连 shell（uid 2000）都不给
`MANAGE_APP_OPS_MODES`：

```text
java.lang.SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES
```

但 `appops get` 可以读，且系统设置界面可以用 adb 驱动，因此三档对照实验完全可跑：

```powershell
$adb = "E:\AndroidSDK\platform-tools\adb.exe"
# 打开应用详情页
& $adb -s 9018f404 shell am start -a android.settings.APPLICATION_DETAILS_SETTINGS `
    -d package:com.ywwynm.everythingdone
& $adb -s 9018f404 shell input tap 1240 1089   # 权限管理
& $adb -s 9018f404 shell input tap 1240 1065   # 设备动作与方向
& $adb -s 9018f404 shell input tap 1240  750   # 允许          -> appops: allow
& $adb -s 9018f404 shell input tap 1240  868   # 仅开屏时不允许 -> appops: default
& $adb -s 9018f404 shell input tap 1240 1041   # 不允许        -> appops: ignore
# 读回核对
& $adb -s 9018f404 shell "appops get com.ywwynm.everythingdone DIRECTION_SENSORS"
```

三档均已实测写入成功并读回核对。**测试结束后必须恢复为 `default`**（用户设备的原始状态）。

四个坑，前两个各浪费了好几轮：

1. **绝不能用固定坐标**。`am start APPLICATION_DETAILS_SETTINGS` 会**恢复已存在的
   securitypermission 任务**，可能直接落在上次停留的档位选择页而不是应用详情页；同一串坐标
   在两个页面上含义完全不同。实测后果：一次把档位误设成「不允许」，一次在应用详情页盲滚之后
   点出了「强行停止」确认框。每一步都必须 dump → 按**文案精确匹配**取 bounds → 再点。
2. **设为「不允许」后，该行会移出「允许」分组**，落到页面下方的禁止分组里，需要滚动才可见
   （`uiautomator dump` 只输出可见节点）。按文案找不到不等于不存在。
3. **PowerShell 读 `uiautomator dump` 的 XML 必须显式按 UTF-8 读**
   （`[System.IO.File]::ReadAllText($p, [System.Text.Encoding]::UTF8)`），否则中文标签全是
   乱码，按文案定位必然失败。
4. 驱动脚本里**不能出现中文字面量**（无 BOM 的 `.ps1` 被 PS 5.1 按 ANSI 解析，会把后面的函数
   定义一起带崩，报出「函数未定义」这种假错）。用码位拼，且**必须 `-join`**——PowerShell 的
   `[char] + [char]` 是字符码相加，不是字符串拼接。可用脚本见会话 scratchpad 的
   `set_direction_tier.ps1`（换会话需重建）。

档位选择器可按文案区分：「仅开屏时不允许」那一档下方带一行「应用启动时 6 秒内将无法通过
摇一摇跳转……」。三档写入后 `appops get` 分别读作 `allow` / `default` / `ignore`，前两者还会
多出一行 `Uid mode:`。

## 进入空间照片的路径（本轮实测）

`monkey -c LAUNCHER` **不能**用来「返回原界面」：它把 `ThingsActivity` 拉到前台，
`ImageViewerActivity` 的传感器连接已经注销，采样窗口里只会看到 no connection。要复现
「返回前台」必须用 `am start` 打开另一个 Activity 再 `KEYCODE_BACK`。

记事列表 → 点带图记事 → 点 `iv_image_attachment` → `act_spatial_photo`（工具栏）。首次进入
若无派生会现场生成，本机约 21 秒；派生存在时瞬时进入。派生落在
`no_backup/spatial-photo/derivatives/<sha256(canonicalPath\0length\0mtime)>/`。
