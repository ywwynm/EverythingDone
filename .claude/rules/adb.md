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
