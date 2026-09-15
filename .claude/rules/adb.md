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
