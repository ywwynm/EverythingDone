# ADB invocation rules

See [toolchain.md](toolchain.md) for the `adb.exe` path and the `$adb` /
`-s emulator-5554` convention. The patterns below assume `$adb` is already
set to the absolute path.

## Screenshot capture (Windows PowerShell)

**Never** pipe `adb exec-out screencap -p > file.png` in PowerShell — `>`
re-encodes the byte stream to UTF-16 with BOM, corrupting the PNG (first
bytes become `FF FE FD FF 50 00 4E 00` instead of `89 50 4E 47`).

Use the **on-device file + pull** pattern instead:

```powershell
& $adb -s emulator-5554 shell screencap -p /sdcard/cap.png
& $adb -s emulator-5554 pull /sdcard/cap.png $localPath
& $adb -s emulator-5554 shell rm /sdcard/cap.png
```

Verify with `[BitConverter]::ToString((Get-Content $localPath -Encoding Byte -TotalCount 8))`
— must start with `89-50-4E-47`.

## Driving the UI: prefer uiautomator dump over visual estimation

**For any actionable element (toolbar icon, button, menu item, list row,
drawer item) always use uiautomator dump — never estimate from a
screenshot.** Visual estimation has failed repeatedly on this project:
hard-coded scale factors don't match every screen, transient UI (drawers,
dialogs) closes before tap-after-screencap, and a single miss cascades
because every retry compounds the error.

### Step 1 — Trigger UI change, then poll until target appears

After tapping anything that changes UI state (open drawer, open dialog,
launch activity), don't `Start-Sleep` a fixed duration — the target may
not be ready, or transient UI may auto-close while you wait. Poll the
dump for a stable indicator:

```powershell
# Open drawer (or whatever)
& $adb -s emulator-5554 shell input tap 84 220
# Poll until target text appears in the dump (max 3s)
$found = $false
for ($i = 0; $i -lt 15; $i++) {
    Start-Sleep -Milliseconds 200
    & $adb -s emulator-5554 shell uiautomator dump /sdcard/win.xml 2>$null | Out-Null
    & $adb -s emulator-5554 pull /sdcard/win.xml "$env:TEMP\win.xml" 2>$null | Out-Null
    if ((Get-Content "$env:TEMP\win.xml" -Raw) -match 'text="Help"') { $found = $true; break }
}
if (-not $found) { throw "Help item never appeared" }
```

Total wait: ≤3s with ~200ms granularity, vs a blind `Start-Sleep -Seconds 3`
that's both slower and unreliable. Polling also tells you immediately
if the UI never reached the expected state (e.g., the drawer didn't open
because the previous tap missed).

### Step 2 — Extract bounds, tap centre

Once the target is in the dump, grep for `resource-id` /
`content-desc` / `text` and read `bounds="[x1,y1][x2,y2]"`:

```powershell
$xml = Get-Content "$env:TEMP\win.xml" -Raw
if ($xml -match 'text="Help"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"') {
    $cx = ([int]$matches[1] + [int]$matches[3]) / 2
    $cy = ([int]$matches[2] + [int]$matches[4]) / 2
    & $adb -s emulator-5554 shell input tap $cx $cy
}
```

Bounds are **real device-pixel coordinates** — no scale conversion
needed, regardless of which device/emulator.

### Step 3 — Verify the tap landed before moving on

After every interactive tap that should change activity or open a dialog,
verify the new state landed. Two patterns:

**Activity transition** — poll `topResumedActivity` until it changes:

```powershell
$expectedActivity = "HelpActivity"
$landed = $false
for ($i = 0; $i -lt 20; $i++) {
    Start-Sleep -Milliseconds 200
    $top = & $adb -s emulator-5554 shell dumpsys activity activities |
        Select-String "topResumedActivity"
    if ($top -match $expectedActivity) { $landed = $true; break }
}
if (-not $landed) { throw "Tap on Help didn't open HelpActivity" }
```

**Dialog / in-activity UI change** — poll the dump for an element that
only exists in the post-tap state. Same pattern as step 1.

If a tap doesn't land, **do not retry blindly with a different
coordinate**. Re-dump, inspect what's actually on screen, and either:
- The target moved (animation in progress) — wait+re-poll
- The trigger never fired (previous step failed silently) — debug upstream
- The dump didn't find the element (wrong query) — fix the grep

## Visual estimation (fallback, when no dump works)

Only when the target has no `resource-id` / `content-desc` / `text`
attribute and you must estimate from a Read-tool screenshot.

The Read tool rescales screenshots to fit its vision channel — the
scale factor **varies per image** because the tool picks a target
display size based on aspect ratio. Hard-coding a constant (the
previous "1.43" rule was wrong: it only held for 1280×2856 displayed at
896×2000; tall drawer screenshots got displayed at ~640×1408 with
ratio ≈ 2.03).

Always derive the ratio from the actual device and displayed
dimensions:

```powershell
# Real device pixel size — query once per session
$wmSize = & $adb -s emulator-5554 shell wm size
# Output: "Physical size: 1280x2856"
if ($wmSize -match '(\d+)x(\d+)') {
    $deviceW = [int]$matches[1]
    $deviceH = [int]$matches[2]
}
```

The displayed image dimensions are visible in the Read tool's rendered
output — find the actual rendered width (the tool may report it in the
tool result, or measure from the image). Then:

```
ratio = deviceW / displayedW
device_x = displayed_x * ratio
device_y = displayed_y * ratio
```

If a tap lands on the wrong item, **do not retry blindly**. Re-screencap,
identify what was actually hit (record its device-y), and use that as a
known anchor to re-derive spacing for the intended item.

## Drawer / transient-UI timing

DrawerLayout and DialogFragment have an auto-dismiss window. If you open
the drawer and the next interaction takes >2s, the drawer may close on
its own. Either:

1. Use the poll-until-appears pattern above (Step 1) — minimises gap
2. Tell the drawer not to close: not feasible from adb, just be fast

Specifically: don't dump-then-screencap-then-read-then-tap. The
screencap+read round-trip adds ~3-5s with the vision-token cost; by
then the drawer is gone. Prefer dump-then-tap (no screencap), and only
screencap **after** the navigation has landed.

## FableSol 视频导出的真机验证流程

2026-07-29 建立并验证。整条链路约 40 秒一轮：`:app:assembleDebug` → `adb install -r` →
启动 → uiautomator 定位点击 → 导出 → `adb pull` → `ffprobe`。

### 入口路径

- **导出**：记事列表 → 点记事 → **点音频卡片本体**（`tv_audio_file_name` 所在的那块，
  不是右侧的播放按钮，点播放按钮只会开始播放）→ 播放 Dialog 里的
  `iv_export_fablesol_video`。
- **设置**：抽屉 → 「设置」→ 滚到「音频海浪动画设置」→ 打开调参 Dialog → 一路下滚到
  「导出色彩模式」。`SettingsActivity` 是 `exported="false"`，`am start -n` 起不来，
  必须走抽屉。
- `FableSolVideoExportService` 同样 `exported="false"`，无法用 `am start-foreground-service`
  直接发起导出（报 `Requires permission not exported from uid`）。

### 两条必须遵守的定位规则

1. **有 `resource-id` 就按 id 定位**，不要按本地化文案。用户的设备可能是英文系统
   （OPPO 平板就是），中文 `content-desc` 直接找不到。终态文字匹配要同时接受中英文
   （`导出完成|Export finished`、`位置：|Location: `）。
2. **`uiautomator dump` 只输出屏幕上真的可见的节点。** 在长对话框里找控件必须"滚动 + 重复
   dump"，一次 dump 找不到不等于控件不存在。调参 Dialog 从顶部滚到导出组约需 40 次
   600px 的 swipe，一趟 14 次的扫描会卡在中间。

### 产物检查

```powershell
& ffprobe -v error -select_streams v:0 `
    -show_entries "stream=codec_name,profile,level,width,height,pix_fmt,color_range,color_space,color_transfer,color_primaries,chroma_location,r_frame_rate,bit_rate" `
    -show_entries "format=duration,size,bit_rate" -of default=noprint_wrappers=1 <file>
& ffprobe -v error -select_streams v:0 -read_intervals "%+#1" -show_frames -of json <file>
```

第二条给出 MDCV（母版 primaries/白点/亮度范围）、CLLI（MaxCLL/MaxFALL）与逐帧
ST 2094-40。**容器 box 与码流 SEI 可能不一致**：最低母版亮度这一项，PLZ110 上被 MP4
写入器多乘了 10000，OPD2515 上没有；stream 级与 frame 级 side data 要分开看。

### 驱动脚本

`export_once.ps1` / `set_color_mode.ps1` 放在会话 scratchpad 里，换会话要重建。两点提醒：
含中文的 `.ps1` 必须存成 **UTF-8 with BOM**，否则 PS 5.1 按 ANSI 解析报假语法错误；
脚本里用 `$ErrorActionPreference = 'Continue'`，且不要对 adb/monkey 加 `2>$null`——
它们往 stderr 写正常输出，会被当成终止错误。

## debug 版改设置：走 `run-as`，别驱动设置 Dialog

调参 Dialog 从顶部滚到导出组约需 40 次 swipe，而导出设置全部落在
`shared_prefs/fablesol_tuning.xml`。debug 版可以直接改：

```powershell
& $adb -s <serial> shell am force-stop com.ywwynm.everythingdone
Start-Sleep -Milliseconds 800
& $adb -s <serial> shell "run-as com.ywwynm.everythingdone sed -i 's|<string name=\`"export_color_mode\`">[a-z0-9-]*</string>|<string name=\`"export_color_mode\`">hlg</string>|' shared_prefs/fablesol_tuning.xml"
& $adb -s <serial> shell "run-as com.ywwynm.everythingdone cat shared_prefs/fablesol_tuning.xml"
```

两点必须遵守：**先 `am force-stop`**（进程活着时 SharedPreferences 的内存副本会把改动盖
回去），**改完再 `cat` 核对**。删一个键用 `/keyname/d`。验证结束后把用户原来的值改回去。

同一条路也用来读回缓存结论，例如 HLG 回环的
`shared_prefs/fablesol_export_hlg_range.xml`、亮度统计的
`shared_prefs/fablesol_export_luminance.xml`。

## 驱动脚本不要写中文字面量

PS 5.1 按 ANSI 解析无 BOM 的 `.ps1`，脚本里的 `'导出完成'` 会变成乱码，匹配永远失败——
而症状是"导出没完成"，很容易误判成应用的问题（2026-07-29 实际发生过）。定位一律按
`resource-id`，终态判定用 `Movies/EverythingDone` 这类 ASCII 片段。中文只出现在**读回来
打印**的那一步，不出现在脚本源码里。

必须用码位拼中文时（例如按厂商设置页的本地化文案定位），**一定要 `-join`**：PowerShell 的
`[char] + [char]` 是**字符码相加**，不是字符串拼接，拼出来是个数字，匹配永远失败。

```powershell
function CjkText([int[]]$codes) { return (-join ($codes | ForEach-Object { [char]$_ })) }
```

## 驱动厂商设置页：每一步都要重新 dump，不许沿用坐标

2026-08-18 在 OPD2515 上改「设备动作与方向」权限时实测的三条，代价是误设了一次档位、
还点出了一次「强行停止」确认框：

1. **`am start -a android.settings.APPLICATION_DETAILS_SETTINGS` 会恢复已存在的
   securitypermission 任务**，可能直接落在上次停留的**档位选择页**而不是应用详情页。同一串
   坐标在两个页面上含义完全不同——上一轮记下的 y 值这一轮可能正好压在别的档位上。
2. **在应用详情页盲滚/盲点极其危险**：「强行停止」「卸载」就在页面顶部一行。任何一步都必须
   dump → 按**文案精确匹配**取 bounds → 再点，并在点完之后再 dump 确认落到了预期页面。
3. **权限被设为「不允许」后，该行会移出「允许」分组**，落到页面下方的禁止分组里，需要滚动
   才可见。`uiautomator dump` 只输出可见节点，按文案找不到不等于它不存在。

档位写入后一律用 `appops get <pkg> <OP_NAME>` 读回核对（`allow` / `default` / `ignore`），
**验证结束把用户原来的档位改回去**。`appops set` 写不了厂商私有 op —— ColorOS 连 shell
（uid 2000）都不给 `MANAGE_APP_OPS_MODES`，只能读。

## 带动画的 Dialog：`uiautomator dump` 返回陈旧缓存树

已知会中招的：录音 Dialog、音频附件播放 Dialog、音频海浪动画调参 Dialog——都有走秒时钟或
连续 GL 动画，等不到 idle。2026-08-18 实测：dump 判定"提示没出现"，而同一时刻的截图里提示
就在屏幕上，白查了一轮应用逻辑。**这三处的验收一律用截图**（on-device screencap + pull +
md5 校验），dump 只用于取静态页面的 bounds。

## 探针广播：必须 `-n` 指定组件，结果必须落盘

两条今晚（2026-08-13）各浪费了好几轮的坑。

**其一：`-a <action>` 发不到清单声明的接收器。** Android 8 起隐式广播不再投递给
manifest receiver，`am broadcast -a com.ywwynm.everythingdone.SPATIAL_INPAINT_BENCH`
会返回 `Broadcast completed: result=0` —— 看起来成功，实际没有任何接收器跑。必须显式
指定组件：

```powershell
& $adb -s <serial> shell am broadcast `
    -n com.ywwynm.everythingdone/.spatial.SpatialInpaintingBenchmarkReceiver `
    --es action depthtest --es model moge_2_vits_normal
```

**其二：这台三星（R5CW20BLNKL）的 logcat 读不到探针输出。** 系统日志（`shsusrd` 逐
线程打 `/proc/*/stat`、`io_stats`、`sensors-hal`）每秒数千行，而 `logcat -G` 的上限是
**5 MiB**——一次 50 秒的推理期间缓冲区已经整轮冲掉，`logcat -d -s TAG` 什么也捞不到。
`Start-Process ... -RedirectStandardOutput` 也救不了（那是另一回事：块缓冲）。

正确做法是让探针**同时写文件**，读结果一律以文件为准：

```kotlin
val probeLog = File(context.getExternalFilesDir(null), "probe.log")
fun report(line: String) {
    Log.i(TAG, line)
    runCatching { probeLog.appendText(line + "\n") }
}
```

驱动侧 `rm -f` 该文件 → 广播 → 轮询 `adb pull` 到本地判非空。

## 从 app 私有目录取文件：`run-as` + 外部目录 + `chmod 644`

`run-as` 的 shell 写不了 `/sdcard` 根（`Permission denied`），但写得了应用自己的
`/sdcard/Android/data/<pkg>/files/`。且 `cp` 出来的文件是 **600 且属 app uid**，
adb shell 用户读不到，`adb pull` 报 `remote open failed: Permission denied`。三步齐全：

```powershell
$ext = "/sdcard/Android/data/com.ywwynm.everythingdone/files/pull"
& $adb -s $s shell "run-as com.ywwynm.everythingdone sh -c 'mkdir -p $ext && cp <私有文件> $ext/'"
& $adb -s $s shell "run-as com.ywwynm.everythingdone chmod 644 $ext/<文件名>"
& $adb -s $s pull "$ext/" <本地目录>
```

## 冷启动进程发不出 Activity

`am force-stop` 之后直接广播让应用起 Activity 会**静默失败**：Android 10+ 的后台启动
限制不允许后台进程启动 Activity，广播照常"投递成功"，但什么也不会发生。顺序必须是

```powershell
& $adb -s $s shell am force-stop com.ywwynm.everythingdone
Start-Sleep -Milliseconds 1200
& $adb -s $s shell monkey -p com.ywwynm.everythingdone -c android.intent.category.LAUNCHER 1
Start-Sleep -Seconds 4
& $adb -s $s shell am broadcast -n <组件> ...
```

## `adb pull` 不可信，截图必须按**内容哈希**校验

实测这条链路（R5CW20BLNKL，2026-08-13）三种坏法都出现过：128 KiB 的残桩、中段损坏、
以及**首 4 字节是正确的 `89-50-4E-47`、尾 8 字节是正确的 IEND、中间却是垃圾**——一批
约 50 张里坏 13 张。只校验文件头发现不了，连尾部 IEND 一起校验**同样发现不了**。

唯一可靠的做法是比对设备端与本地的 md5，不一致就重传：

```powershell
& $adb -s $s shell screencap -p /sdcard/cap.png
$remote = (& $adb -s $s shell md5sum /sdcard/cap.png | Out-String).Trim()
if ($remote -match '^([0-9a-f]{32})') { $remoteHash = $matches[1] }
& $adb -s $s pull /sdcard/cap.png $dest
$localHash = (Get-FileHash -Path $dest -Algorithm MD5).Hash.ToLower()
if ($localHash -ne $remoteHash) { <重传> }
```

同理适用于任何 `adb pull` 下来的二进制产物（模型、派生、视频）。

## 通知栏自动化：手势展开 + 截图定位（2026-08-16，OPD2515）

两条实测教训：

1. **`cmd statusbar expand-notifications` 有确定性副作用**：在 OPD2515（Android 16）
   上用它展开通知栏并点击通知启动 Activity 后，约 8 秒系统会自动回桌面约 3 秒再把
   应用带回前台（`wm_resume_activity Launcher` → 3s → resume 回 app）。真实下拉手势
   完全没有此现象。凡验证"通知点击后的应用行为"，必须用手势展开：
   ```powershell
   & $adb -s <serial> shell input swipe 500 5 500 900 300    # 左半屏下拉=通知列表
   # 右半屏下拉是控制中心，不是通知列表
   ```
2. **带秒表的通知（setUsesChronometer）让 uiautomator 永远等不到 idle**：每秒刷新使
   `uiautomator dump` 报 "could not get idle state" 并输出**陈旧缓存树**（内容是上一次
   成功的 dump，极易误判画面状态）。通知栏上的定位一律改用截图（on-device screencap +
   pull + md5 校验）目视读坐标，不要依赖 dump。

## 真机驱动脚本：PowerShell 下必须 `PYTHONUTF8=1`，run id 必须每次换新

2026-09-15 在跑粒子动画的验收脚本时各踩一次：

1. **`verify_*.py` 这类读设备端 JSON 的脚本，在 PowerShell 里直接跑会报
   `UnicodeDecodeError: 'gbk' codec can't decode byte 0x80`**——它们用默认编码读文件，而
   PowerShell 下 Python 的 locale 编码是 GBK。跑之前设 `$env:PYTHONUTF8 = "1"`。
   Bash 工具里没有这个问题。
2. **`verify_dialog_coverage.py` / `verify_repeated_appearance.py` 开头就断言设备上还没有
   同名 run 的 `result.json`**。失败重跑一定要换一个新的 `--run` / `--prefix`，否则报的是
   一个看不出原因的 `AssertionError`（空消息）。
3. **`verify_repeated_appearance.py` 的 `--close cancel|confirm` 必须配 `--kind chooser`**：
   探针找的是 `tv_cancel_as_bt_fragment_chooser` / `tv_confirm_as_bt_fragment_chooser`，
   附件弹窗里没有这两个控件，症状是驱动侧 `KeyError: 'x'`。
4. 这两个脚本都要求**应用里已经打开一条普通记事**（探针以当前详情页为宿主），否则
   `result.json` 里是 `error: 先打开一条普通记事`。

## 观察器写 JSON 不能出现 NaN：`toString()` 返回 null，Kotlin 侧直接把应用打崩

2026-09-15 实际发生：debug 观察器把 `Float.NaN` 放进 `JSONArray`，Android 的
`JSONObject.toString()` 捕获 `JSONException` 之后**返回 null**，Kotlin 的平台类型检查在
`writeText(result.toString())` 处抛 `NullPointerException: toString(...) must not be null`，
应用主线程崩溃。而驱动脚本看到的症状只是「观察器没有收尾」，下一轮还会被崩溃提示弹窗挡住
（OPPO 的 `CRASH DETECTED` 对话框），一连几个场景全部失败。

两条防线：缺值一律写 `JSONObject.NULL`；写盘那一步包 `runCatching`，失败时落一份
`result-error.txt`，别让序列化失败伪装成「没跑完」。

## screenrecord 是变帧率：帧号换算成秒必须逐帧读时间戳

`adb shell screenrecord` **只在画面变化时出帧**。R5CW20BLNKL（SM-S9180）实测：
129 帧里 99 帧集中在动画的 0.84 秒内（约 117fps），其余 30 帧铺在 4.8 秒的静止期
上，容器给出的 `avg_frame_rate` 因此只有 22.7。

用任何单一帧率（无论是假设的 60，还是探测到的平均值）把帧号换算成秒都会错。
2026-08-31 两次实际发生：先按 60 算把 1.15 秒的窗口算成 0.35 秒，改用平均帧率后
又把 7.2 秒的录像算成 17-24 秒的窗口。

正确做法是逐帧读时间戳，并让解码保持原始帧序（`-vsync 0`）：

```powershell
& ffprobe -v error -select_streams v:0 -show_entries frame=best_effort_timestamp_time -of csv=p=0 <file>
```

解码出的帧数必须与时间戳数一致，不一致就说明 `-vsync` 没生效或滤镜改了帧率。
`tmp/particle-dismiss-tuning/detect_device_window.py` 已按此实现。

## Adreno 的 `EXT_disjoint_timer_query` 不计 compute dispatch

2026-09-14 在 OPD2515（Adreno 829）与 SM-S9180（Adreno 740）上实测。两台的扩展串里都有
`GL_EXT_disjoint_timer_query`，`glBeginQuery(GL_TIME_ELAPSED_EXT /* 0x88BF */, id)` 不报错、
`GL_GPU_DISJOINT_EXT` 全程为 0，但**结果只对渲染通道有效**：包住一次出现预计算（约 1550 次 compute
dispatch）的一对查询只报 0.3–0.6 ms，同一机制下包住每帧两遍实例化绘制 + resolve 却给出合理的
1.7–3.4 ms。算 compute 的 GPU 时间要换口径。

Android 的 `android.opengl.*` 绑定里没有 `glQueryCounterEXT` / `glGetQueryObjectui64vEXT`，
`GL_TIMESTAMP` 这条路在 Java 侧走不通。可用的替代：

1. **整段墙钟**，最可靠，也是最终的判据。
2. **按段 `glFinish` + `System.nanoTime()`**：会串行化流水线（实测整段涨到 3 倍以上），绝对值不可
   信，只能比较三段之间的量级。
3. **消融实验**：把要归因的那一段临时去掉再测墙钟。这是唯一能给出份额的方法。
4. `/sys/class/kgsl/kgsl-3d0/gpubusy` 对**应用 uid 可读**（`run-as` 能 cat），但它**不是「自上次
   读取以来」的累计值**：由 devfreq 调速器约每秒更新一次，两次快速读取之间返回 `0 0`。无法对齐到
   几百毫秒的窗口，只能当粗略的 1 秒忙闲比看。

## 设备的动画时长缩放不能只看 `settings get global`

2026-09-14 在 OPD2515（ColorOS，Android 16）实测：`settings list global` 里**根本没有**
`animator_duration_scale` 这一行（`Settings.Global.getFloat(..., 1f)` 读到默认 1.0），
而框架给进程内 `ValueAnimator` 实际施加的是 **5.0**。`window_animation_scale` 与
`transition_animation_scale` 都是 1.0，`dumpsys window` 里也查不到这个值。

后果：凡是「应用自己算时长」与「交给 ValueAnimator / ViewPropertyAnimator」混用的地方，
在这台设备上会差 5 倍。诊断时不要相信 adb 读到的设置值，**要在应用里实测**：

```kotlin
// 隐藏 API 先读一次（Android 16 上仍可反射到），再用一个短动画实测校准
val reflected = runCatching {
    ValueAnimator::class.java.getDeclaredMethod("getDurationScale")
        .apply { isAccessible = true }.invoke(null) as Float
}.getOrElse { -1f }
// 120 ms 的动画，onAnimationStart 到 onAnimationEnd 的墙钟 / 120 即为真实缩放
```

OPD2515 得到 `settings=-1(缺失) reflected=5.0 measured=5.017`，SM-S9180 得到 `1.0 / 1.0 / 1.008`。

## uiautomator 的陈旧树还有「缺半棵」这一种

adb.md 上面已经写了「带动画的 Dialog 会返回陈旧缓存树」。补充一种更隐蔽的：首页在启动动画期间
dump 出来的树**结构完整、能正常解析，但缺了 actionbar 与 FAB 那一支**，看上去就像「按钮不存在」。
同一时刻截图里按钮就在屏幕上。判据仍是「dump 只反映它能拿到的那一刻」，处理方式是
**每次 dump 前先 `rm -f` 目标文件**，并对关键控件轮询到出现为止（不要拿第一次 dump 的结果下结论）。

## Python 驱动脚本：`xml.etree` 的 Element 是「无子节点即为假」

`if find_node(tree, ...)` 这种写法在 ElementTree 上是陷阱：**没有子元素的 Element 布尔值为 False**，
于是「找到了叶子控件」被判成「没找到」。2026-09-14 为此白跑了四轮「UI never showed fab_create」。
一律写成 `is not None`：

```python
if require is None or self.find(tree, rid=require, quiet=True) is not None:
```

## 轮询设备上的产物文件：先删旧的，否则读回上一轮

2026-09-15 实际发生，整批场景的数据全是空的却毫无报错。应用侧的观察器是「收尾 + 300 ms 才写
result.json」，而驱动脚本收尾后立刻开始轮询同一个路径。**同名 run 重跑时上一轮的文件还在**，
第一次轮询就命中它，脚本一路当成本轮结果，差异只有内容对不上——很容易误判成应用没记录。

规则：每次 begin 之前 `adb shell rm -rf <设备上的本轮输出目录>`，不要只建本地目录。
同理适用于任何「写文件 + 轮询读取」的探针链路。

## `ps -T` 里的线程名不等于「我们创建的线程」

Adreno 驱动首次在某条线程上建 EGL 上下文时会派生若干工作线程，**这些线程继承父线程名**。
EverythingDone 的常驻 GL 线程叫 `ParticleGl`，实测 OPPO 上 `ps` 里能看到 1–3 条、
三星上 1–6 条同名线程，数量稳定、不随动画次数增长。

判断「有没有残留线程」不能只数名字出现几次，要看它**是否随动作次数增长**；
真正属于我们的线程数应当由代码结构保证（`object` + `by lazy` 只可能有一条）。

## Windows 上读设备拉回来的 JSON 必须显式 UTF-8

`pathlib.Path.read_text()` 与 `open()` 在本机默认 GBK。设备写的 JSON 只要带中文
（例如探针的错误消息）就会抛 `UnicodeDecodeError`，而报错位置在读文件那一行，看起来像文件损坏。
一律写成 `read_text(encoding='utf-8')` / `open(..., encoding='utf-8')`。

## atrace 的应用自定义分段要用 `-a <包名>`，不是把 `app` 当类目

2026-09-15 实际浪费了整整一轮采集：`atrace --async_start -b 40000 gfx view sched freq app`
里那个 `app` **不会**打开应用级 tag，`android.os.Trace.beginSection` 写的分段一条也采不到
（解析出 86172 条切片，自定义的那四种是 0 条）。应用级 tag 由 `-a` 打开：

```powershell
& $adb -s <serial> shell atrace --async_start -b 40000 -a com.ywwynm.everythingdone gfx view sched freq
```

另外两条同批的教训：

- **`atrace` 的输出不要用 PowerShell 的 `>` 重定向**（会转成 UTF-16）。用 `--async_stop -o
  /data/local/tmp/trace.txt` 写到设备上再 `adb pull`。
- **采集本身会明显加重设备负载**，绝对毫秒数不能当基线。同一台设备上，采集轮里
  `COMMAND_ISSUE` p90 是 13.47 ms，关掉采集的干净复测只有 0.68 ms。**可用的是 trace 的结构
  （哪一段在等谁），不是它的数值。**

## FrameMetrics 的长帧先看 `DEADLINE`：可能只是刷新率被降了

2026-09-15 在 SM-S9180（Android 16）上，弹窗宿主窗口涟漪期「P95 18–20 ms、最大 48–52 ms」
的长帧，逐帧分段是 `DRAW` 0.24–0.47 ms、`COMMAND_ISSUE` 12–17 ms、`GPU` 15–18 ms，看上去像
在排队；但同一批帧的 **`DEADLINE` 从 16.7 ms 涨到 50.0 ms**——**`TOTAL` 36 ms < `DEADLINE`
50 ms，这些帧根本没有超时**。trace 里 `DrawFrames` 内部是 `dequeueBuffer` 12–20 ms，即生产者
在等消费端的节拍。根因是 Android 15+ 的可变刷新率按**脏区面积**投票，脏区实测 180×180 px
（占 1440×3088 窗口的 0.73%）被判成「小面积更新」，整窗降到约 20 Hz。

三条纪律：

1. **看到长帧先读 `DEADLINE`**，不要默认 16.7 ms。`TOTAL < DEADLINE` 就不是丢帧。
2. 应用自绘的连续动画要显式投票：`View.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH)`
   （API 35+），动画结束改回 `REQUESTED_FRAME_RATE_CATEGORY_DEFAULT`。常量前缀是
   **`REQUESTED_`** 不是 `REQUEST_`，且没有 `NO_PREFERENCE` 之外的"复位"值可用时用 `DEFAULT`。
3. **这种场景下「帧耗时 P95 / 最大值」是错的口径**：刷新率提上来之后每帧预算变小，同样的绝对
   耗时在百分位上反而更难看。要看的是**同一段时间里真正出了多少帧**。

## A/B 工具的「恢复默认」必须读回来核对

2026-09-15 实际发生：`setLanes(lanes.coerceIn(1, 16))` 把「0 = 恢复自动」夹成了 1，于是
「1 道 vs 默认道数」的逐位对照**比的是两次相同的串行运行**（恒等式，永远通过），而且跑过
对照的进程此后一直串行。加两条 `check`（切过去要真的切到、恢复之后要真的恢复）之后才第一次
比到真东西。凡是「临时改一个全局开关再恢复」的对照，两端都要**读回来断言**。

## Adreno 上「同一条命令流」不保证逐位复现：逐位对照必须先做自比较

2026-09-15 在 OPD2515（Adreno 829）上实测：同一个离屏渲染器、同一批着色器、同一份 uniform、
同一份输入、同一个 EGL 上下文、同一条线程，把整段动画**重复跑两遍**，约 **0.5–0.9%** 的运行
会有**一帧**不一样（整帧个位数像素、最大通道差约 49/255）。固定随机种子之后，出事的永远是
**同几帧**——是「某个量卡在判据边界上」，不是均匀随机。

把出事的那一帧抓下来逐项比对之后可以更精确地说：**GPU 算出来的状态缓冲逐位相同，
画出来的像素却差了两个**（相邻、最大通道差 13）。绘制的其余输入都是静态的，
也就是说分歧发生在顶点/光栅/混合/解析那一段，不在 compute 着色器里。

后果：任何「GPU 输出逐位相同」的验收都有本底噪声，**孤立单帧、个位数像素的差异不能直接判成
回归**。判据要分两步：

1. 先让**同一条路径自己跑多遍**（本项目里是 `--gpu-diagnostics`），看它与自己是否一致；
2. 自比较也出分歧 ⇒ 本底噪声；自比较干净而 A/B 有差异 ⇒ 才是真回归。

两条常见的误判要一并排除：

- **不是回读不稳**：每帧 `glReadPixels` **连读两次**（画面不重画）比一比。实测两次恒等，
  说明差异在读回来之前就存在于画面里。
- **不是并发**：把并发 GPU 负载做成可以独立开关的自变量（另起一条线程、在**自己的**
  EGL 上下文里跑同形态的 compute），开关两种条件各跑几百次试次再比事件率。
  本项目实测开 328 试次 3 次、关 472 试次 3 次，分不出差别。**两三次运行的「关掉就清零」
  在 1% 量级的事件率下毫无信息量**（本来就有 98% 的概率清零）。

## 离屏对照不要同时留多份整帧像素

平板全屏一帧 RGBA8 是 2520×1680×4 = **16.9 MB**，而应用堆上限只有 256 MB。
「反复跑同一条路径、把每一遍的像素都留着最后再比」必然 OOM（2026-09-15 实际发生，
报错出现在定位阶段，把前面已经跑完的统计一起带走了）。两条做法：

1. 只同时保留**第一遍**与**当前这一遍**，对得上就立刻丢掉；
2. 诊断报告对象**先挂到结果里再往里填**，这样定位阶段抛异常时前面的统计还在
   （`result.put("diagnostics", report)` 之后再 `runCatching { … }`）。

## 诊断工具不得改生产路径会读的进程级状态

2026-09-15 审查发现：离屏对照的 `withBaselineGpu` 改的是**进程级**开关，而同一时刻常驻 GL
线程上的提前预算每次推进都在读同一批开关、还把它们写进缓存键。改成**线程局部**的标记
（`ThreadLocal` + 「基线标记 && 设置值」两层，读点一个字不用动）之后才真正隔离。
凡是「临时改一个全局值再恢复」的诊断手法，先问一句：**这一刻还有别的线程在读它吗**。

## 用 `log -t` 往 logcat 打标记切段；标记必须纯 ASCII

判定「某个动作之后应用做了什么」最省事的做法是在驱动脚本里每一步前后打一条标记，
事后按标记把日志切成段：

```powershell
& $adb -s <serial> shell log -p i -t PhaseAMark "attach-back.0.close"
```

三条实测（2026-09-15，魅族 + 华为）：

1. **标记文本必须是纯 ASCII，且不含 shell 元字符。** `adb shell` 把参数交给 `sh`，
   文本里的 `|` 会被当成管道；中文虽然能过去，但按 tag 过滤时容易与终端编码纠缠。
   一律用 `[A-Za-z0-9_.=-]`，中文只出现在打印出来给人看的那一步。
2. **打完之后别用 `logcat -d -t 40` 去找它。** 华为这台主缓冲 16 MiB 里已被系统日志占了
   13 MiB，400 ms 内就能刷掉几十行，`-t 40` 根本够不着。要么 `-s <TAG>` 过滤，
   要么（推荐）全量流到文件再离线 grep。
3. **设备的 logcat 缓冲可能小得离谱**：魅族 20 Infinity 的 `main` 只有 **256 KiB**
   （`adb logcat -g` 可查），一轮测试跑完早就整轮冲掉。**凡是靠日志判定的验收，
   都必须在跑之前就开一条 `adb -s <serial> logcat -v time > 本地文件`**
   （Bash 工具重定向；PowerShell 的 `>` 会转成 UTF-16），不能事后 `logcat -d`。

## `uiautomator dump` 失败时 `cat` 会报错，驱动脚本不能 `check=True`

adb.md 上面已经写了「每次 dump 前先 `rm -f` 目标文件」。配套的一条：**dump 拿不到 idle 时
可能什么都不写**，此时 `cat` 返回非零，Python 里 `subprocess.run(..., check=True)` 直接抛
`CalledProcessError`，整条驱动脚本崩在半路。`rm -f` + `uiautomator dump` + `cat` 这三步都要
允许失败并重试，只对「解析成功且目标出现」才返回。

## `wm size` 有 Override size 时，输入坐标落在 override 的坐标系里

华为 MatePad Pro 13.2 报的是 `Physical size: 2880x1920` + `Override size: 1920x2880`。
`input tap` 与 `uiautomator dump` 的 bounds 都用 **override** 那一组。脚本里取 `wm size`
输出的**最后**一个 `<数字>x<数字>` 即可。

## 不要代为授予运行时权限

设备上没授予的权限（例如 `RECORD_AUDIO`）会让相应入口弹出系统授权框，把驱动脚本卡住。
**授权属于设备设置，不得用 `pm grant` 代为改动**，也不要按返回键去「消掉」授权框
（Android 11+ 两次拒绝等于永久拒绝，那是在改用户的设置）。正确做法是开跑前只读查询：

```powershell
& $adb -s <serial> shell dumpsys package <包名> | Select-String "android.permission.RECORD_AUDIO: granted=true"
```

查不到就把那一格跳过并在报告里写明。

## 「截图对、屏幕不对」：唯一的口径是 `dumpsys SurfaceFlinger` 的 dataspace

2026-09-15 定位魅族 20 Infinity 上 FableSol 海浪颜色异常时用到。用户能给的证据只有
「屏幕看着不对，系统截屏拍下来是对的」——截屏走的是色彩管理正确的那条通路，所以这句话
本身就说明**缓冲内容是对的，显示通路对缓冲的解释不对**。应用这边读不回屏幕上的结果，
能查的只有那一层在合成器里长什么样：

```powershell
& $adb -s <serial> shell dumpsys SurfaceFlinger > <设备上的文件>   # 别用 PowerShell 的 > 直接落盘
```

要找的是 `layer: <n> name: ... composition: Device/Device alpha: 255 format: ... dataspace:0x...`
这一行（在 `dumpsys` 的**图层表**里，不是上面那棵树）。三项各自的含义：

- **format**：`RGBA16161616F_UBWC` = FP16，`RGBA_8888_UBWC` = 普通 8 位。
  另一处 `req fmt:` 是应用请求的值（22 = `RGBA_F16`，1 = `RGBA_8888`）。
- **dataspace**：按位拆，`(v>>27)&7` 是 range、`(v>>22)&0x1F` 是 transfer、`(v>>16)&0x3F`
  是 standard。两个常见值：`0x18410000` = EXTENDED | LINEAR | BT709 = **scRGB linear**；
  `0x08810000` = FULL | SRGB | BT709 = 普通 sRGB。
- **composition**：`Device` 是硬件合成器直出，`Client` 是 GPU 合成。扩展线性的浮点层被
  `Device` 直出而颜色不对，就是硬件合成器不按这个口径显示它——应用侧无从检测，只能提供
  一个手动退回 SDR 的开关。

修前修后各取一次，这是除截图之外**唯一**能证明显示通路的证据。层名里带包名与 Activity 名，
`grep` 包名即可；输出很长，整份落盘再解析，不要只 grep 一行。

## 验证「离屏抓图 + 逐个 Surface 合成」是否真的合上了

判据不能只看动画有没有播——底下那层背景和真实的 Surface 像素都能让画面「看起来有东西」。
两条量化判据（2026-09-15 实测，魅族 20 Infinity）：

1. **与同一时刻的系统截屏逐像素比**：按弹窗窗口的 bounds 裁剪截屏，与落盘的合成快照比。
   合上了就是不透明区逐通道绝对差均值 0.07、p99 = 2；没合上会在 Surface 那一块整片偏掉。
2. **两张相隔几十秒的合成快照互相比**：静态控件区必须逐像素相同，而 Surface 那一块必须
   有变化（实测水体区 10% 的像素差 > 2）。**没变化就说明拿到的是底下的背景，不是 Surface**。

## 别用第二个 `HardwareRenderer` 去画活着的、含 `SurfaceView` 的视图树

2026-09-16 在魅族 20 Infinity（Android 15）上实测。Android 15 起 `SurfaceView` 的位置与 crop
由 RenderThread 驱动（`Flags.clipSurfaceviews`，`replacePositionUpdateListener` 逐帧上报
「这一帧把它的 RenderNode 画到了哪里」）。离屏渲染器（`HardwareRenderer` + `RenderNode` +
`view.draw(recordingCanvas)`）把同一个 RenderNode 再画一遍，上报的是**离屏那一帧**的边界，
而它会被应用到**真实的** SurfaceControl 上：屏幕上那一层被裁成别的尺寸，真实窗口下一帧
上报正确边界后又自己恢复，**只闪一两帧**，很容易被当成偶发。

需要离屏抓一棵含 `SurfaceView` 的树时改用软件 `Canvas`（`view.draw(Canvas(bitmap))`）：
它不经过 RenderNode。`SurfaceView` 的透明洞照样有——`clearSurfaceViewPort` 调的
`Canvas.punchHole` 在 `BaseCanvas` 与 `BaseRecordingCanvas` 都有原生实现。

## 单帧级视觉缺陷：录屏 + 逐帧差分，别靠肉眼回放

上面那一条只闪两帧，回放根本抓不住。可复用的判据（脚本在
`docs/features/audio-visualization-fable-sol/device_recording_dialog.py`）：

1. `adb shell screenrecord --time-limit N`，**在触发动作之前**开起来；变帧率，逐帧读
   `best_effort_timestamp_time`，解码加 `-vsync 0`，断言帧数与时间戳数一致。
2. 每帧算**整屏亮度**与**目标区域的小块均值**，再做一阶差分，按差分排序看最大的十帧。
   一次 ±9.5 灰阶的单帧跳变在肉眼回放里看不出来，在差分里一眼就是最大的那两行。
3. 找到可疑帧之后，与**相邻帧**做空间差分并取 bounding box：差异落在哪个控件的范围内，
   直接决定是哪一层出的问题（本次「严格落在弹窗包围盒内、窗外页面逐像素相同」一条就
   排除了暗层交接乱序）。
4. 逐行扫描可见边界（`|像素 − 背景色| > 阈值` 的首尾 x），能分辨「被裁」与「被缩放」：
   裁剪时内容位置一个像素不动，缩放时特征点会一起移动。

## ColorOS 不让 shell 用 `screenrecord`

OPPO OPD2515 上 `/sdcard`、`/sdcard/Movies`、`/sdcard/Download`、`/data/local/tmp`、
应用外部私有目录全部 `Unable to open ...: Permission denied`，`--output-format=h264 -`
的管道也只出 0 字节。那台设备上的逐帧验收做不了，只能改用应用自己的日志判据
（例如本次「遮盖已上 / 撤下 停留=…ms」两行），并在另一台能录屏的设备上补画面证据。

## 绝对不要按电源键熄屏：设备会锁回 PIN，此后什么都做不了

2026-09-16 在 OPD2515 上实际发生，整轮验证卡死。为了等一个 full-screen intent 发了
`input keyevent 26`，屏幕熄灭即触发锁屏；ColorOS 的锁屏下：

- `screencap` 一律返回**全黑**（不是失败，是黑图，只看返回码发现不了）；
- `uiautomator dump` 出来的是 `com.android.systemui` 的树，应用的控件一个都找不到；
- `input tap` 打到锁屏上，不会到应用。

而这台配的是 6 位数字 PIN（`oplusos_kgd_security_pin_six_view`）+ 面部解锁，**密码属于用户，
不得代输**，只能请用户解锁。`keyevent 224`（WAKEUP）只点亮屏幕，解不开锁。

规则：**真机驱动脚本里不出现 `keyevent 26`**。需要判断是否被锁，在脚本开头 dump 一次，
命中 `com.android.systemui` 就直接退出并提示解锁，别让后面的步骤拿着黑图往下跑。

## `USE_FULL_SCREEN_INTENT` 默认是拒绝的，别指望真实闹钟拉起全屏 Activity

Android 14 起这个权限只自动授予通话/闹钟类应用。OPD2515 上：

```powershell
& $adb -s <serial> shell appops get com.ywwynm.everythingdone USE_FULL_SCREEN_INTENT
# USE_FULL_SCREEN_INTENT: default; rejectTime=+3m26s551ms ago
```

`rejectTime` 就是闹钟响的那一刻：通知正常发出（`dumpsys notification` 里能看到那条记录），
但 `setFullScreenIntent` 被拒，Activity 不会起。**不代为 `appops set`**（授权属于设备设置），
改用调试包里的入口 Activity 从应用进程内 `startActivity`。

## `run-as ... am start` 起不了本应用的非导出 Activity

`am` 把调用包写死为 `com.android.shell`，框架在
`assertPackageMatchesCallingUid` 处直接抛
`SecurityException: package=com.android.shell does not belong to uid=<app uid>`。
`run-as` 只换了 uid，换不了 `am` 自报的包名。要从应用进程内起非导出 Activity，
只能在 debug source set 里加一个 `exported="true"` 的中转 Activity。

## `dumpsys activity activities` 里不一定有 `topResumedActivity=`

活动落在另一个任务（本项目的 `NoticeableNotificationActivity` / `StartDoingActivity` 都有
自己的 `taskAffinity`）时，输出里只有 `ResumedActivity:` / `mResumedActivity=` 这几行，
按 `topResumedActivity` 过滤会一条都匹配不到，症状是「Activity 一直没起来」而实际上它就在前台。
过滤词用 `ResumedActivity`（同时覆盖 `topResumedActivity=`）。
