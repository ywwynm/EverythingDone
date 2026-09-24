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

### 整份**写回**一个 `shared_prefs` 文件：别用 PowerShell 的管道，会带上 BOM

`Get-Content -Raw <本地xml> | & $adb -s <serial> shell "run-as <包名> sh -c 'cat > shared_prefs/x.xml'"`
写出来的文件头是 **`ef bb bf`**（2026-09-18 实际踩到）。`cat` 打出来一切正常，
`od` 一查才看得见，而 SharedPreferences 的解析器不认它——症状是「设置莫名其妙回到默认」。
要写原文就用 Python 之类把 **UTF-8 原始字节**喂进标准输入：

```python
subprocess.run([ADB, '-s', SERIAL, 'shell', 'run-as', PKG, 'sh', '-c',
                "'cat > shared_prefs/x.xml.tmp && mv shared_prefs/x.xml.tmp shared_prefs/x.xml'"],
               input=text.encode('utf-8'), check=True)
```

写完一律 `run-as <包名> od -An -tx1 -N 8 shared_prefs/x.xml` 核一遍文件头，
第一个字节必须是 `3c`（`<`）。

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

**也可能整段一帧都不出。** 2026-09-24 在三星上录弹窗的粒子出现：录像里 2.0–2.88 秒之间没有任何帧，
而应用日志显示这 0.53 秒里粒子层出了 33 帧；同一条命令重录一次就完整了。看动画之前先把时间戳列出来，
在动画该出现的那一段找空洞，有空洞就重录，不要拿前后两帧去推断中间发生了什么。

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

`a or b` 同样中招：`find(tree, text='确定') or find(tree, text='解散')` 在找到「确定」这个叶子节点时
仍会去求 `b`，返回的是标题「解散当前文件夹」那一格（2026-09-24 确认框因此没点到）。改用
`next((n for n in ... if ...), None)` 取第一个匹配。

## 魅族（Flyme）不输出 `Log.d`

2026-09-24 实测：同一个调试包里 `Log.d` 的诊断在三星上有、在魅族 `logcat` 里一条都没有，`Log.i`
正常。要在魅族上看的诊断日志一律用 `Log.i`（仍放在 `BuildConfig.DEBUG` 之下）。

## 轮询设备上的产物文件：先删旧的，否则读回上一轮

2026-09-15 实际发生，整批场景的数据全是空的却毫无报错。应用侧的观察器是「收尾 + 300 ms 才写
result.json」，而驱动脚本收尾后立刻开始轮询同一个路径。**同名 run 重跑时上一轮的文件还在**，
第一次轮询就命中它，脚本一路当成本轮结果，差异只有内容对不上——很容易误判成应用没记录。

规则：每次 begin 之前 `adb shell rm -rf <设备上的本轮输出目录>`，不要只建本地目录。
同理适用于任何「写文件 + 轮询读取」的探针链路。

**还有一种变体：轮询时扫了整个父目录。** 2026-09-17 实际发生：驱动脚本按
`ls <父目录>` 遍历所有 run 目录去找 `segment-bench.json`，第一次轮询就命中**上一轮**那个 run
目录里的旧文件，拿回来的整份结果是上一轮的，而条件表都已经换过了（症状是「新加的条件不见了」）。
轮询一律**限定到本轮那一个 run 目录**，不要按文件名扫父目录。

## 设备上的 GPU 基准：不能逐帧 `glFinish`

2026-09-17 在魅族 20 Infinity（Adreno 740）上实测。离屏跑同一条渲染序列、**每帧末尾
`glFinish`** 取逐帧耗时时，同一个条件重复两三遍能差到 **2–3 倍**，而且形状是「先快后慢再稳住」
（不是热漂移：更早跑完的重负载段反而更快）。原因是逐帧排空让 GPU 在每次 CPU 提交期间空转，
调速器据此降频并稳在低档。

可用的做法：

1. **整条序列只在末尾 `glFinish` 一次**，口径取整段墙钟；逐帧分布改由分段探针另外给。
2. **先跑一遍完整序列丢掉**（热身），再开始计数。
3. 条件之间**成对执行**（每个条件紧挨着它自己的基线跑一遍），比值按对取中位数。
   顺序按「外层配对、内层条件」轮转，让剩余漂移平摊到所有条件上。

这三条齐全之后，同一条件三遍的离散度从 ±100% 降到 ±3%。

## 消融归因只对「不回灌被测系统」的段成立

同一轮的另一条。粒子管线里跳掉压力求解的任何一级（splat / blur / Jacobi / project）都会改变
压力场的内容，进而改变活动 tile 列表与粒子的散布范围——`no-jacobi` 与 `no-project` 因此比基线
还**慢** 9%–23%。这类消融测的不是「那一段值多少」，而是「那一段坏掉之后整条管线变成什么样」。

开消融条件之前先问一句：**跳掉它之后，后面那些段的输入还一样吗**。不一样就换口径
（分段计时、或在固定的中间结果上单独跑那一级）。

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
`input tap` 与 `uiautomator dump` 的 bounds 都用 **override** 那一组。

**但 override 那一组不随朝向变**：同一台设备横屏时，实际可用的坐标系是 2880 宽 × 1920 高，
而 `wm size` 仍然报 `Override size: 1920x2880`。按它算出的 y（比如「屏高的 78%」= 2246）
落到屏幕外，`input swipe` 于是**静默地什么也不做**，症状是「页面滚不动、找不到目标行」
（2026-09-17 实际浪费一轮）。

可靠的做法是**从 `uiautomator dump` 的节点 bounds 取**——它一定是当前朝向下的真实坐标：

```python
width = max(x2 for 每个节点的 bounds)
height = max(y2 for 每个节点的 bounds)
```

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

## `input swipe` 驱动列表手势：快的会触发完成，慢的会变成长按

2026-09-16 在 OPD2515 上两头都踩到，代价是把用户的两条真实记事滑成了完成。

- **快**（约 ≤1.3 s 走 228 px）：`ItemTouchHelper` 按 `getSwipeEscapeVelocity` 判定，**位移没到
  一半也会触发 `onSwiped`**，也就是真的执行完成／删除。想「滑一半看中间态」不能靠缩短时长。
- **慢**（约 ≥6 s 走 290 px）：前 500 ms 的位移不到 touch slop，会被当成**长按**进入拖拽，
  松手后还可能误触打开详情页。
- 实测可用的区间：**3000 ms 走 280 px**（约 0.48 的进度、不触发完成），想停在中间就在这个
  窗口里截图。

**任何会改数据的重复验证，一律先建测试记事**（前几轮建过 `PROBE-HABIT-0916` / `PROBE-NOTE-0916`
这种，测完移入回收站），不要拿列表里现成的记事来滑。误完成之后不要自己驱动 UI 去撤销——
那是在用户数据上再赌一次，如实告知即可。

## 华为 MatePad Pro 13.2：`input text` 的拉丁字母会被输入法改写

2026-09-17 实测（`5JB0224328002836`，Android 12）：`adb shell input text PerfB9-20260917-1`
打进去的是 **`P儿0260917－1`**——拉丁字母被中文输入法当拼音吃掉，`-` 也变成全角 `－`。
**输入法属于设备设置，不得代为切换**（`ime set` / `settings put secure default_input_method`
都不行）。数字实测原样进去，所以那台的专用测试记事一律用纯数字标题（本项目用 `909170xx`）。
清理脚本的标题正则要同时收得住数字标题与已经被改写过的那一条。

## `ParticleGestureInput` 在 Android 12/13 上要换注入入口

`android.hardware.input.InputManagerGlobal` 是 **Android 14** 才有的；Android 12/13 上只有
`android.hardware.input.InputManager.getInstance()`。找不到类时 `app_process` 直接以 **137**
收场（看起来像被杀，其实是异常退出），整条驱动脚本崩在注入那一步。
`docs/features/swipe-complete-animation/ParticleGestureInput.java` 已带两条路径的回退；
改完要重新 `javac` + `d8` 打包到 `tmp/thing-animation-device/input-driver/input.jar`。

## 观察器按 `run_id` 变没变换采集器：同一个 id 写第二次什么也不会发生

`ThingAnimationPerformanceObserver` 的 `ObserverManager.poll` 里是 `if (next != run)`。
上一轮如果中途出错、没走到 `end()`（也就没写空配置），进程里还记着同一个 `run_id`，
**再写同一个 id 不会新建采集器**，症状是 `ready did not become true; last=None`，
而且重跑多少次都一样，很容易误判成「设备/应用出问题了」。
`device_particle_perf.begin` 已改成**先写一份空配置、等过一个轮询周期（0.35 s）再写本轮的**。
自己写新的驱动脚本时同一条纪律要照搬。

## Git Bash 里 adb 的 `/sdcard/...` 会被 MSYS 改写成 Windows 路径

2026-09-18 实际踩到：Bash 工具里 `adb push <本地> /sdcard/x.xml` 报
`failed to copy ... to 'C:/Program Files/Git/sdcard/x.xml': remote secure_mkdirs() failed`。
MSYS 把看起来像绝对路径的参数转成了 Windows 路径。三种解法：加 `MSYS_NO_PATHCONV=1`、
写成 `//sdcard/...`，或者干脆走 `run-as <包名> sh -c 'cat > 私有路径'` 用标准输入喂内容
（写应用私有目录本来就该走这条）。PowerShell 工具没有这个问题。

## 平板的朝向不一定改得动：`settings put system user_rotation` 可能被 ROM 忽略

华为 MatePad Pro 13.2（HarmonyOS）上 `settings put system accelerometer_rotation 0` +
`user_rotation 1` 写完之后 `dumpsys window` 仍是 `mUserRotationMode=USER_ROTATION_FREE`、
`mCurrentRotation=ROTATION_270`，截图还是横屏。设备物理上怎么放就是什么朝向，代为翻转做不到。
需要「竖屏 + 横屏」两组截图时，按设备分工（手机出竖屏、平板出横屏），别在一台上死磕；
改过的两个设置项验证完要写回去。


## EMUI 会拦掉发给清单接收器的广播：改用导出的 Activity 中转

adb.md 上面已经写了「`-a <action>` 发不到清单声明的接收器，必须 `-n` 指定组件」。补一条更狠的：
**华为 MatePad Pro 13.2（EMUI，Android 12）上连 `-n` 指定组件也不行。**

```powershell
& $adb -s 5JB0224328002836 shell am broadcast -n <包>/<接收器> --ez someFlag true
# Broadcasting: Intent { flg=0x400000 cmp=... }
# Broadcast completed: result=0        <- 看起来成功
```

而系统日志里是

```
ActivityManager: App 10216/<包> targets O+, restricted
BroadcastQueue: enter shouldPreventSendBroadcast second BroadcastRecord{...}
```

**接收器一次都没跑**，产物目录连建都没建。加 `--receiver-include-background --receiver-foreground`
也没用。同一条命令在魅族 20 Infinity（Android 15）上正常。

可用的替代是**在 debug source set 里加一个 `exported="true"` 的 Activity**，由它
`sendBroadcast` 给同进程的接收器：

```kotlin
class XxxProbeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(Intent(this, XxxProbeReceiver::class.java).putExtra("flag", true))
        finish()
    }
}
```

`am start -n <包>/<Activity>` 不受这条限制。本项目的实例是
`ParticleShaderMatrixActivity`（着色器变体的编译矩阵）。

**判据**：广播发完之后**必须去看产物文件在不在**，不能看 `result=0`。凡是「探针没输出」
先查这一条，再查探针本身。

## `input swipe` 的**起点**离屏幕边缘太近会被判成系统手势

2026-09-18 在三星 SM-S9180 上实测：驱动粒子动画设置页的示例卡左滑，卡片最宽 640 dp、
在这台上几乎顶到屏幕右缘，起点取 `卡片右边界 − 24 px`（距屏幕右缘约 80 px）正好落进
**系统返回手势区**——手势被系统吃掉，整个 Activity 被退回上一页，而驱动脚本毫不知情，
后面每一步都在别的页面上瞎点。

规则：任何横向手势的**起点**都要离左右屏幕边缘 **140 px 以上**（不是离控件边缘）。
写成 `start = min(控件右边界 - 24, 屏宽 - 140)`。落点（终点）不受这条限制。

## One UI 上「按住不放」会触发系统的拖放，别用它截按下态

想截一个按钮的按下态 / 涟漪时，很自然会写 `input swipe x y x y 1800`（原地按住）。
在三星 SM-S9180（One UI）上，长按 `TextView` 会触发系统的**拖放**功能，实测直接把
`com.samsung.android.onetouch` 的引导页拉到前台，整轮驱动作废（2026-09-18）。

魅族上同一条命令正常。做法：这一步只在不会中招的设备上跑，并给驱动脚本留一个跳过的开关；
按下态的证据由那一台提供。真要在所有设备上拿到，改用 `screenrecord` + 逐帧取。

## 只会往下滚的定位函数，每次调用前要先滚回顶部

`find_rid` 这类「找不到就往下滚一点再找」的函数有个隐含前提：目标在当前位置**下方**。
上一次为了找底部的控件滚下去之后，页面顶部的控件就落在屏幕外，再找它们会一路滚到底也找不到，
报的是「页面上找不到 <id>」，看上去像控件不存在（2026-09-18 在三星上实际发生）。

规则：定位之前先把滚动容器**滚回顶部**，再往下找。代价是几次多余的 swipe，换来与当前
滚动位置无关的确定性。

## 「开系统深色」在不同设备上不是同一件事

应用如果有自己的深色开关（EverythingDone 是 `follow_system_dark_mode` / `force_dark_mode`），
`cmd uimode night yes` 得到的结果取决于那个开关：

- 应用跟随系统 ⇒ 真的进 `values-night`，资源全部换成夜间那一套；
- 应用固定浅色 ⇒ 系统只能对它做**强制深色**（force-dark），那是渲染层的反色，
  **不改任何资源值**，也**不反转 `ImageView` 的内容**。

判据是量页面底色：本项目 `values-night` 的 `app_chrome_surface` 是 `#121212`，
而强制反色把浅色的 `#EEEEEE` 变成 `#292929`——两者一眼可分。**不要代为改应用内的深色设置**
（那是用户设置），改用一台本来就开着「跟随系统」的设备取深色证据。


## 滑动手势：**两边**的边缘都是系统返回手势区

adb.md 上面已经写了「左滑的起点必须离屏幕右边缘足够远」。镜像的那一条同样成立：
**右滑（从左往右推）的起点也必须离左边缘足够远**。2026-09-18 在三星 SM-S9180（One UI）上实测：
粒子动画设置页的示例卡最宽 640 dp、几乎顶满屏宽，从卡片左缘 `x1 + 24`（约 80 px）起手正好落进
返回手势区，整页被直接退掉，后面每一步都在上一层页面上瞎点——而脚本本身不会报错，
症状是几步之后才在某个 `find` 上抛「找不到控件」。两端一律留 **140 px**。

魅族（Flyme）上同一串坐标不会触发，**这条只在某些 ROM 上暴露**，不要因为一台过了就当它安全。

## 持续播动画的页面：`uiautomator dump` 会给出「缺了一支」的树

adb.md 上面已经写了「带动画的 Dialog 返回陈旧缓存树」和「首页启动动画期间缺 actionbar 与 FAB
那一支」。再补一处同类：粒子动画设置页上一直有 GL 动画在播，dump 等不到 idle，
2026-09-18 在三星上实测**选项卡那一整行不在树里**，`node(tree, rid=…)` 直接抛「实际 0 个」。

规则不变：**这类页面上的控件一律轮询到出现为止**，一次 dump 找不到不等于控件不存在。

```python
def poll_rid(rid, tries=40):
    for _ in range(tries):
        for n in dump().iter('node'):
            if (n.get('resource-id') or '').endswith('/' + rid):
                return n
        time.sleep(.3)
    raise RuntimeError('页面上找不到 ' + rid)
```

## `ImageView` 的着色：`SRC_ATOP` 会把 alpha 丢掉

2026-09-18 定位「换色图标在亮色模式下仍是纯黑」时量出来的，属于取证方法而不只是这一处缺陷：

- `ImageView.setColorFilter(int color)` 的模式是 **`SRC_ATOP`**，`android:tint` 在
  `AppCompatImageView` 上走的也是它。`SRC_ATOP` 只做 `Sa×Sc + (1−Sa)×Dc`、**alpha 取 dst 的**，
  于是「半透明黑」叠在「纯黑矢量」上仍然是**实黑**，看上去像没生效。
- 真正压低亮度的是 `imageAlpha`（`setImageAlpha`），它乘的是整张图的 alpha。
- 判据只能是**核心像素**：取图标区域里出现次数最多的那个非背景灰阶，换算成
  `(底色 − 像素) / 底色` 即有效不透明度。本次三组：第 7 批 (0,0,0) = 1.00、
  第 8 批 (74,74,74) = 0.689、参照页那枚按钮 (79,78,77) = 0.690。

「照搬某个页面的同一份颜色」这种要求，**必须先把参照物量出来**——布局属性写着什么不代表
渲染出来是什么。

## `uiautomator` 的 bounds 被裁到可见区域内：贴屏幕边的节点高度是假的

adb.md 上面已经写了「dump 只输出屏幕上真的可见的节点」。补一条更隐蔽的：**部分可见的节点会
照常出现在树里，但它的 bounds 被裁到可见区域**。2026-09-18 在三星 SM-S9180 上实测：一段折行
的说明文本底边正好压在屏幕下沿，报出来 41.87 dp，真值 67.6 dp——按它折算行数会多数一行，
单行高算成 15.93 dp 而不是 19.27 dp，看上去就像「字号不对」。

凡是按 bounds 量尺寸的采集，都要**丢掉贴住屏幕上下边的节点**（`y2 >= 屏高 - 2` 或 `y1 <= 0`），
滚过去让它完整可见之后再收一次：

```python
box = bounds_of(node)
if box[3] >= height - 2 or box[1] <= 0:
    continue        # 这一份是被裁过的，等滚过去再收
```

同一条纪律的两个推论：

- **别拿 `match_parent` 的 `TextView` 量字高或文本左缘**。它的 bounds 是整行，内边距都算在里面
  （本项目的参照页里「清除全部已生成空间效果」就是这样一行）。要量字高就找 `wrap_content` 的
  那一个。
- **参照页上要量的那一行可能整块 `gone`**（按条件显示，例如「深度细节」只在选中某类模型时出现），
  采集脚本要允许它缺席并换一个同档取值的行，不能死等。

## 在截图上量控件：底色取单点会整块判反，bounds 与截图必须同一时刻

2026-09-18 在粒子动画设置页量对齐时各踩一次，两条都会给出**看似合理但完全错误**的读数。

**其一：底色不能取单点。** 「控件区域里不是底色的像素」这种判定，底色若取自某个坐标
（例如「控件上沿那一行的中点」），一旦那一点落在抗锯齿边或字形上，整块就会反过来被判成墨迹，
读数变成探测框自己的边。`Widget.Material.SeekBar` 的上下内边距是 **0**，滑块停在正中时
「上沿中点」正好压在滑块圆顶上。底色一律取**区域里出现次数最多的颜色**，并断言它的占比：

```python
colors, counts = np.unique(patch.reshape(-1, 3), axis=0, return_counts=True)
bg, share = colors[counts.argmax()], counts.max() / len(patch.reshape(-1, 3))
assert share >= .5
```

**其二：`uiautomator` 的 bounds 与截图必须来自同一个滚动状态。** 「先把所有控件的 bounds 收齐 →
缺的滚下去再收 → 最后截一张图」在需要滚动时必然错位：第一屏那几个控件的 y 已经变了。
改成**逐屏**：每一屏先 dump、再截图、**再 dump 一次核对 bounds 没变过**，才认这一屏的读数。

顺带一条口径：`bounds` 的右缘是**开区间**（`x2` 是第一个不属于控件的像素），而扫出来的墨迹
`span[1]` 是**最后一个**墨迹像素，比较之前要 `+1`，否则恒差 1 px。

## 「找到控件 → 点击」之间必须重新 dump：swipe 的惯性会让页面继续滚

2026-09-20 在魅族 20 Infinity 的设置页上实际踩到。脚本一边 `input swipe` 下滚一边
dump 找目标，找到后按记下的 bounds 去点——而 swipe 抬手后的惯性滚动还在继续，点下去
时目标早已挪走，落在了别的分组上。症状是「点了没反应」，而脚本毫不知情，后面每一步
都在错误的页面上进行。

做法是对同一目标**连续 dump 两次、bounds 一致才认**：

```python
def stable_find(sub, tries=6):
    for _ in range(tries):
        a = find_text(sub)
        if a is None: return None
        ba = bounds(a)
        time.sleep(0.6)
        b = find_text(sub)
        if b is not None and bounds(b) == ba: return b
    return None
```

## 「按控件在不在」判定操作成功，先确认没有浮层遮挡

同一天的另一条。清理脚本按「首页还能不能找到测试记事的标题」判断是否清理干净，而判定
那一刻**溢出菜单正盖住那张卡片**，dump 里自然没有标题——脚本报告「已清空」，实际一条
都没删。`uiautomator dump` 只输出可见节点，这条纪律在**否定式判定**上尤其致命：
「找不到」既可能是「真没了」，也可能是「被挡住了」。

凡是用「控件消失」当成功判据的地方，都要先把浮层收掉（或改用别的正向判据，例如列表
头部的「N 件记事」计数）。

## PowerShell 工具的沙箱会把 adb 参数里的 `rm` 当成本地删除拦下来

2026-09-21 实际踩到两次。下面这条命令直接被拒，连 adb 都没跑起来：

```powershell
& $adb -s $s shell "rm -f /sdcard/win.xml; uiautomator dump /sdcard/win.xml"
# Remove-Item on system path '\d+' is blocked. This path is protected from removal.
```

`rm` 是 PowerShell 里 `Remove-Item` 的别名，沙箱做静态扫描时不区分它是 adb 的参数还是本地
命令，报错里那个 `'\d+'` 还是从同一条命令**别处的正则**里抓来的，看上去与 adb 毫无关系。
短命令有时能过、长命令就被拦，很容易误判成偶发。

adb.md 上面写了「每次 dump 前先 `rm -f` 目标文件」，在 PowerShell 工具里改用**每次换一个
随机文件名**，同样能保证读到的不是上一轮的残留：

```powershell
$n = Get-Random
& $adb -s $s shell uiautomator dump "/sdcard/w$n.xml" | Out-Null
& $adb -s $s pull "/sdcard/w$n.xml" "$env:TEMP\w$n.xml" 2>&1 | Out-Null
```

## 代码构建的界面没有 `resource-id`：按 `class` 顺序取第 N 个控件

验证程序图省事用代码建 UI 时，`uiautomator dump` 里所有按钮的 `resource-id` 都是空的，
只能按文案定位——而文案定位在别的语言/ROM 上会失效，PowerShell 里匹配中文还要过编码这关。

改按 `class` 出现顺序取，与语言无关：

```powershell
$ms = [regex]::Matches($xml, 'class="android\.widget\.Button"[^>]*bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"')
$m = $ms[0]   # 布局里的第 1 个 Button
```

前提是布局顺序固定。改了布局要同步改索引，所以**索引含义必须写在驱动脚本的注释里**。

## `targetSdk 35+` 的界面不处理 insets 就会被系统栏盖住

2026-09-21 实际发生：验证程序用 `Theme.Material.Light`（带 ActionBar）+ 代码建的
`LinearLayout`，在 SM-S9180（Android 16）上**第一行按钮整行被 ActionBar 盖死**，第二行也切掉
上半截——用户根本看不到那两个按钮是干什么的。

根因是 targetSdk 35 起强制 edge-to-edge，窗口内容从屏幕物理顶端开始，状态栏、导航栏、挖孔
全压在上面。任何自己建的根布局都要主动让开：

```java
root.setOnApplyWindowInsetsListener((v, insets) -> {
    Insets bars = insets.getInsets(
            WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
    v.setPadding(bars.left + pad, bars.top + pad, bars.right + pad, bars.bottom + pad);
    return WindowInsets.CONSUMED;
});
```

顺带两条同批的：

- **临时工具也要截图看一眼再交给用户**。这次是用户先看到的，而我本来就能自己跑。
- **Flyme 不认 `android:forceDarkAllowed="false"`**：硬编码浅色背景配黑字会被渲染层整片反过来
  （状态条设的浅绿显示成深紫）。可靠的做法是主题继承
  `@android:style/Theme.DeviceDefault.DayNight`、颜色交给主题，应用自己声明支持深色之后
  系统就不再强制反色。

## 编译成功不等于设备上跑的是新代码

2026-09-21 实际发生，代价是整整一轮「验证通过」的假结论：改完代码 `assembleDebug`
成功，直接接着在设备上驱动 UI 做验证，**忘了 `adb install -r`**。那一轮跑的全是上一版
APK，被测的新逻辑一行都没执行，而界面上完全看不出来——是逐字段核对数据库才发现导入结果
里的键还是旧值。

规则：**任何「改代码 → 真机验证」的循环，编译之后必须紧接着装**，并且把这两步写在同一条
命令里，不要分开发：

```powershell
$out = & "E:\projects\EverythingDone\gradlew.bat" :app:assembleDebug 2>&1 | Out-String
if ($out -match "BUILD SUCCESSFUL") {
    & $adb -s <serial> install -r "E:\projects\EverythingDone\app\build\outputs\apk\debug\app-debug.apk"
}
```

存疑时用 `dumpsys package <包名>` 的 `lastUpdateTime` 与 APK 的 `LastWriteTime` 对一遍。
**多设备场景尤其容易漏**：这次就是只装了三星、忘了魅族，导致在魅族上查 `pm query-activities`
时新 Activity 全部「未命中」，差点误判成 manifest 写错。

## 界面看不出来的改动，验收要读数据层

同一轮的另一条。被测的是「附件外观设置在导入后是否仍挂在正确的附件上」——这种键值映射
在界面上完全看不出来（图片照样显示、裁剪差异肉眼难辨）。可靠的判据是把数据库拉出来逐字段
核对。

设备上通常没有 `sqlite3`，把库整个取出来在本机读：

```powershell
# 多数 ROM：run-as 复制到应用的外部目录，chmod 后 pull
& $adb -s $s shell "run-as <包名> sh -c 'mkdir -p $ext && cp databases/X.db $ext/'"
& $adb -s $s shell "run-as <包名> chmod 644 $ext/X.db"
& $adb -s $s pull "$ext/X.db" <本地路径>
```

```bash
# Flyme 上 run-as 写不了那个外部目录（Permission denied），改用 exec-out 直读。
# 重定向必须在 Bash 工具里做，PowerShell 的 > 会把二进制转成 UTF-16。
"$ADB" -s $S exec-out run-as <包名> cat databases/X.db > local.db
od -c local.db | head -1   # 必须是 "SQLite format 3"
```

设计测试数据时要让「错位」可分辨：本次给三个附件中的**第 1 个和第 3 个**设外观、中间那个
不设，映射一旦偏一格，外观就会落到中间那个上。全设或全不设都验不出 off-by-one。

## 运行时权限：申请要放在 `onCreate`，不要等用户点按钮

同一轮踩到的。把 `requestPermissions` 放在按钮的点击处理里，用户点一次只会弹框、什么也不
发生，得再点一次才真的开始——而按钮被系统栏盖住时，连那一次都点不到，症状是「没有授权
弹窗」。工具类程序一律在 `onCreate` 里先查再申请。

至于**能不能代点「允许」**：adb.md 上面那条「不要代为授予运行时权限」针对的是用户的真实
应用。对**本会话自己装的一次性验证程序**，在用户明确要求「你自己跑一跑」的前提下代点是
合理的（验完卸载，权限随之消失），但要在报告里写明这一步是谁做的。设备级开关不在此列：
**NFC 总开关、输入法、朝向、锁屏密码一律不代改**，查到是关的就停下来请用户开。

## 首页进入选择模式、拖放生成文件夹：用 `input motionevent` 分段发手势

2026-09-23 在三星 SM-S9180 上实测。「正在进行」列表里长按进入的是拖动排序，**要拖一下再回到
原位松手**才进入选择模式（`ThingsActivity.enterSelectionMode`）。`input swipe x y x y 700`
原地按住会被当成点击，直接打开详情页。多段手势要拆开发：

```powershell
& $adb -s $s shell input motionevent DOWN $cx $cy
Start-Sleep -Milliseconds 800            # 超过 500 ms 的长按阈值，拖动已开始
& $adb -s $s shell input motionevent MOVE $cx ($cy + 80)
Start-Sleep -Milliseconds 250
& $adb -s $s shell input motionevent MOVE $cx $cy
Start-Sleep -Milliseconds 250
& $adb -s $s shell input motionevent UP $cx $cy
```

总共按住约 1.5 秒，没有触发 One UI 长按 1.8 秒的系统拖放。

**拖到另一张卡片上生成文件夹**：`ThingListOverlayDragController` 的命中判定看的是**拖动浮层的
左上角**，不是手指位置；判定区还要在目标卡片四边各内缩 `folder_drop_target_inset`（16dp），
在目标上停留至少 130 ms、两次移动。只有标题的矮卡片判定区只剩约 86 px 高，手指停在目标中心
时浮层左上角会落在判定区上方，结果变成排序。终点要按「手指在源卡片里的偏移 + 放大 1.11 倍带来
的偏移」去算，实测终点比目标中心右移 185 px、下移 108 px 才命中。生成后会弹「命名记事文件夹」，
默认名处于选中状态，直接 `input text` 即可替换。

测试文件夹用完用「解散文件夹」处理：文件夹本身直接删除、不进回收站，里面的记事移回上一级，
再按测试记事的清理流程移入回收站。

## 应用崩溃后，下次启动会先弹「完事儿刚刚好像爆炸了QAQ」

2026-09-24 实际遇到：一次崩溃之后重新启动应用，首页先弹出应用自己的崩溃反馈对话框，挡住后面
所有的驱动步骤（症状是「按 id 找不到控件」）。按文案精确匹配点**「取消」**关掉；**不要点「现在反馈」**
（那会拉起邮件发送）。驱动脚本在启动应用后先 dump 一次，命中「现在反馈」就先处理它。

## Flyme 的强制深色：逐视图的 `setForceDarkAllowed(false)` 也不认

上面已经写了 Flyme 不认主题里的 `forceDarkAllowed="false"`。2026-09-24 在魅族 20 Infinity（深色）
上进一步确认：**逐视图的 `View.setForceDarkAllowed(false)` 同样无效**。它改的是**画笔颜色、渐变
（`LinearGradient` / `RadialGradient`）与颜色滤镜**，于是白色的光晕与圆环被反成黑色、用
`setColorFilter` 着色的图标被压成黑色；**不改位图着色器（`BitmapShader`）与位图的内容**，卡片截图、
`drawVertices` 画的粒子都正常。

需要在深色下保持白色或指定颜色的自绘内容：预先画成位图，经 `BitmapShader` 绘制；图标把颜色烘进
位图，不用颜色滤镜。验收一律在魅族深色下截图核对，三星浅色看不出这类问题。

## 魅族（Flyme）上别用 motionevent 长按：会被系统的「识屏」浮层截走

2026-09-24 在魅族 20 Infinity 上实测：`input motionevent DOWN` → 等 0.9 秒 → `UP` 长按首页卡片，
Flyme 的 Aicy 识屏浮层（一个「T」按钮和一个彩色圆球）弹出来截走了手势，应用没有进入选择模式，
随后的几下点击落到了详情页上；列表还停在「只有被按住的那张是原色、其余变浅」的样子（应用自己
已回到普通模式，按返回直接退出，数据没变，强行停止后恢复）。三星 One UI 上的同类问题见上面
「按住不放会触发系统的拖放」。

魅族上需要删测试记事时，逐条从详情页的菜单「删除」，每条先核对详情页标题，删后核对首页条数减一。

## 选择模式里卡片的选中状态读不到：用计数 + 截图颜色双重核对

本项目的卡片选中与否只体现在「未选中的卡片换成浅色、内容半透明」，`uiautomator dump` 里没有
`selected` / `checked`。批量删测试记事之前要确认选中的恰好是它们，两项同时成立才删：

1. 顶栏的「N / M」里 N 等于刚才点过的样例数；
2. 截图上这些样例卡片区域里出现最多的颜色饱和度都高于 0.45（实测选中 0.59 / 1.0，未选中 0.18 / 0.22）。

只看计数不够：一下点偏选中了别的卡片，计数照样对得上。删除还有一个确认框「将删除所选记事，共 N 件，
可在回收站恢复」，件数也要核对。

## 验证「切到后台再回来」要从最近任务切回，别走启动器入口

首页 `ThingsActivity` 是 `singleTask`：`monkey -p <包名> -c android.intent.category.LAUNCHER 1`
会把它上面的详情页整个清掉（详情页的 `ON_DESTROY` 随之执行），测的就不是「回到同一个页面」了。
要回到原来的页面：`input keyevent 3` 回桌面 → `input keyevent 187` 打开最近任务 → dump 找到
文字或描述含应用名、宽度较大的那张任务卡片 → 点它的中心。

## 演示录屏：录制期间不要同时截图，静帧事后从视频里抽

`screencap` 与 `screenrecord` 同时跑会让录屏掉帧、动画看着一顿一顿。交付用的录屏一律只录不截，
联系表从视频里按时间抽帧。录屏是变帧率的（见上文），**最后一帧的时间戳可能比容器时长早好几百毫秒**，
按时间点抽帧要取「不晚于该时间点的最近一帧」的时间戳去 seek，否则超出最后一帧的时间点抽不出图。
