param(
    [Parameter(Mandatory = $true)][ValidateSet('3B1629006YC00000', 'R5CW20BLNKL')][string]$DeviceSerial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_-]{1,48}$')][string]$RunId,
    [switch]$Install,
    [switch]$GateVisual,
    [ValidateSet('full', 'supplement', 'visual')][string]$Phase = 'full',
    [ValidateSet('portrait', 'landscape')][string[]]$Orientations = @('portrait', 'landscape')
)

$ErrorActionPreference = 'Stop'
$adbPath = 'E:\AndroidSDK\platform-tools\adb.exe'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$localDirectory = Join-Path $repoPath ('tmp/detail-toolbar/' + $RunId + '/' + $DeviceSerial)
New-Item -ItemType Directory -Force -Path $localDirectory | Out-Null
$rotation = ((& $adbPath -s $DeviceSerial shell wm user-rotation) | Out-String).Trim()
if ($rotation -notmatch '^(free|lock [0-3])$') { throw "无法识别原旋转状态：$rotation" }
$failures = 0
try {
    if ($Install) {
        & $adbPath -s $DeviceSerial install -r (Join-Path $repoPath 'app/build/outputs/apk/debug/app-debug.apk')
        if ($LASTEXITCODE -ne 0) { throw '安装失败' }
    }
    foreach ($orientation in $Orientations) {
        $rotationValue = if ($orientation -eq 'portrait') { 0 } else { 1 }
        & $adbPath -s $DeviceSerial shell wm user-rotation lock $rotationValue
        if ($LASTEXITCODE -ne 0) { throw '旋转设置失败' }
        $deviceRun = "$RunId-$orientation"
        $existing = if ($orientation -eq 'portrait' -and $Phase -eq 'full') { 'true' } else { 'false' }
        if ($GateVisual) {
            $remoteOutput = "/sdcard/Android/data/com.ywwynm.everythingdone/files/detail-toolbar/$deviceRun"
            $instrumentLog = Join-Path $localDirectory "$deviceRun-instrument.txt"
            $errorLog = Join-Path $localDirectory "$deviceRun-error.txt"
            $arguments = @('-s', $DeviceSerial, 'shell', 'am', 'instrument', '-w', '-e', 'run_id', $deviceRun,
                '-e', 'existing', $existing, '-e', 'phase', $Phase, '-e', 'visual_gate', 'true',
                'com.ywwynm.everythingdone/.detail.DetailToolbarContrastProbe')
            $process = Start-Process -FilePath $adbPath -ArgumentList $arguments -WindowStyle Hidden -PassThru `
                -RedirectStandardOutput $instrumentLog -RedirectStandardError $errorLog
            try {
                $deadline = [DateTime]::UtcNow.AddSeconds(30)
                do {
                    & $adbPath -s $DeviceSerial shell test -f "$remoteOutput/capture.ready"
                    if ($LASTEXITCODE -eq 0) { break }
                    if ([DateTime]::UtcNow -gt $deadline -or $process.HasExited) { throw '未等到截图核验入口' }
                    Start-Sleep -Milliseconds 100
                } while ($true)
                $uiFile = Join-Path $localDirectory "$deviceRun-window.xml"
                & $adbPath -s $DeviceSerial shell uiautomator dump /sdcard/detail-toolbar-window.xml | Out-Null
                & $adbPath -s $DeviceSerial pull /sdcard/detail-toolbar-window.xml $uiFile | Out-Null
                [xml]$window = Get-Content -LiteralPath $uiFile -Raw
                $notice = $window.SelectSingleNode('//node[@package="android" and contains(@text,"16 KB")]')
                if ($null -ne $notice) {
                    $confirm = $window.SelectSingleNode('//node[@package="android" and @text="确定" and @clickable="true"]')
                    if ($null -eq $confirm -or $confirm.bounds -notmatch '^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$') {
                        throw '系统提示没有已核实的确认按钮'
                    }
                    $tapX = [int](([int]$matches[1] + [int]$matches[3]) / 2)
                    $tapY = [int](([int]$matches[2] + [int]$matches[4]) / 2)
                    & $adbPath -s $DeviceSerial shell input tap $tapX $tapY
                    & $adbPath -s $DeviceSerial shell uiautomator dump /sdcard/detail-toolbar-window.xml | Out-Null
                    & $adbPath -s $DeviceSerial pull /sdcard/detail-toolbar-window.xml $uiFile | Out-Null
                    [xml]$window = Get-Content -LiteralPath $uiFile -Raw
                    if ($null -ne $window.SelectSingleNode('//node[@package="android" and contains(@text,"16 KB")]')) {
                        throw '确认后系统兼容性提示仍然可见'
                    }
                }
                & $adbPath -s $DeviceSerial shell touch "$remoteOutput/capture.continue"
                if (-not $process.WaitForExit(30000)) { throw '视觉核验未在预期时间内结束' }
                $instrumentOutput = Get-Content -LiteralPath $instrumentLog -Raw
            } finally {
                if (-not $process.HasExited) { Stop-Process -Id $process.Id }
            }
        } else {
            $instrumentOutput = (& $adbPath -s $DeviceSerial shell am instrument -w -e run_id $deviceRun -e existing $existing -e phase $Phase com.ywwynm.everythingdone/.detail.DetailToolbarContrastProbe 2>&1 | Out-String)
        }
        Write-Output $instrumentOutput
        if ($LASTEXITCODE -ne 0 -or $instrumentOutput -notmatch 'INSTRUMENTATION_RESULT: result=failures=') {
            throw '验证入口未正常完成。'
        }
        & $adbPath -s $DeviceSerial pull "/sdcard/Android/data/com.ywwynm.everythingdone/files/detail-toolbar/$deviceRun" $localDirectory | Out-Null
        if ($LASTEXITCODE -ne 0) { throw '读取结果失败' }
        $result = Get-Content -LiteralPath (Join-Path $localDirectory "$deviceRun/result.json") -Raw | ConvertFrom-Json
        $failures += [int]$result.failures
        Write-Output "$DeviceSerial $orientation : failures=$($result.failures), samples=$($result.samples.Count), existing=$($result.existing_notes)"
        if ($result.error) { Write-Output $result.error }
        $result.samples | Where-Object { -not $_.passed } | ConvertTo-Json -Depth 5 -Compress
        if ($result.failures -gt 0) { break }
    }
} finally {
    if ($rotation -eq 'free') { & $adbPath -s $DeviceSerial shell wm user-rotation free }
    else { & $adbPath -s $DeviceSerial shell wm user-rotation lock $rotation.Substring(5) }
}
if ($failures -gt 0) { throw "配色验证有 $failures 项失败，详见 $localDirectory" }
