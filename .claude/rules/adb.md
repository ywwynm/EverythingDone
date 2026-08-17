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
