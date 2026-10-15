param(
    [Parameter(Mandatory = $true)][string]$DeviceSerial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_-]{1,48}$')][string]$RunId,
    [switch]$Install,
    [ValidateSet('portrait', 'landscape')][string[]]$Orientations = @('portrait', 'landscape')
)

$ErrorActionPreference = 'Stop'
if ($DeviceSerial -notin @('3B1629006YC00000', 'R5CW20BLNKL')) {
    throw '仅允许本会话明确授权的两台设备。'
}
$adbPath = 'E:\AndroidSDK\platform-tools\adb.exe'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$localDirectory = Join-Path $repoPath ('tmp/detail-overlay/' + $RunId + '/' + $DeviceSerial.Replace(':', '_'))
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
        $existing = if ($orientation -eq 'portrait') { 'true' } else { 'false' }
        $instrumentOutput = (& $adbPath -s $DeviceSerial shell am instrument -w -e run_id $deviceRun -e existing $existing com.ywwynm.everythingdone/.detail.DetailMediaOverlayProbe 2>&1 | Out-String)
        Write-Output $instrumentOutput
        if ($LASTEXITCODE -ne 0 -or $instrumentOutput -notmatch 'INSTRUMENTATION_RESULT: result=failures=') {
            throw '验证入口未正常完成。'
        }
        & $adbPath -s $DeviceSerial pull "/sdcard/Android/data/com.ywwynm.everythingdone/files/detail-overlay/$deviceRun" $localDirectory | Out-Null
        if ($LASTEXITCODE -ne 0) { throw '读取结果失败' }
        $result = Get-Content -LiteralPath (Join-Path $localDirectory "$deviceRun/result.json") -Raw | ConvertFrom-Json
        $failures += [int]$result.failures
        Write-Output "$DeviceSerial $orientation : failures=$($result.failures), samples=$($result.samples.Count), existing=$($result.existing_notes)"
        if ($result.error) { Write-Output $result.error }
        if ($result.failures -gt 0) { break }
    }
} finally {
    if ($rotation -eq 'free') { & $adbPath -s $DeviceSerial shell wm user-rotation free }
    else { & $adbPath -s $DeviceSerial shell wm user-rotation lock $rotation.Substring(5) }
}
if ($failures -gt 0) { throw "配色验证有 $failures 项失败，详见 $localDirectory" }
