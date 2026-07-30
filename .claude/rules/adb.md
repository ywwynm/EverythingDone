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
