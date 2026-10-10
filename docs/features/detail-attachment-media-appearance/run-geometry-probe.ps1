param(
    [Parameter(Mandatory = $true)][string]$DeviceSerial,
    [Parameter(Mandatory = $true)][ValidatePattern('^[A-Za-z0-9_-]{1,48}$')][string]$RunId,
    [switch]$Install,
    [ValidateSet('portrait', 'landscape')][string[]]$Orientations = @('portrait', 'landscape')
)

$ErrorActionPreference = 'Stop'
if ($DeviceSerial -notin @('9018f404', 'R5CW20BLNKL', '192.168.100.67:42641')) {
    throw '本轮仅允许用户明确授权的三台设备。'
}
$adbPath = 'E:\AndroidSDK\platform-tools\adb.exe'
$repoPath = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$deviceLabel = $DeviceSerial.Replace(':', '_')
$localDirectory = Join-Path $repoPath "tmp/detail-media-geometry/$RunId/$deviceLabel"
New-Item -ItemType Directory -Force -Path $localDirectory | Out-Null
$rotation = ((& $adbPath -s $DeviceSerial shell wm user-rotation) | Out-String).Trim()
if ($rotation -notmatch '^(free|lock [0-3])$') { throw "无法识别原旋转状态：$rotation" }
$totalFailures = 0
try {
    if ($Install) {
        & $adbPath -s $DeviceSerial install -r (Join-Path $repoPath 'app/build/outputs/apk/debug/app-debug.apk')
        if ($LASTEXITCODE -ne 0) { throw '安装失败' }
    }
    foreach ($orientation in $Orientations) {
        $rotationValue = if ($orientation -eq 'portrait') { 0 } else { 1 }
        & $adbPath -s $DeviceSerial shell wm user-rotation lock $rotationValue
        if ($LASTEXITCODE -ne 0) { throw '设置测试旋转状态失败' }
        $deviceRun = "$RunId-$orientation"
        $instrumentOutput = (& $adbPath -s $DeviceSerial shell am instrument -w -e run_id $deviceRun -e orientation $orientation com.ywwynm.everythingdone/.detail.DetailMediaGeometryProbe 2>&1 | Out-String)
        Write-Output $instrumentOutput
        if ($LASTEXITCODE -ne 0 -or $instrumentOutput -notmatch 'INSTRUMENTATION_RESULT: result=failures=') {
            throw '回归入口未正常返回，不能读取之前运行遗留的结果。'
        }
        & $adbPath -s $DeviceSerial pull "/sdcard/Android/data/com.ywwynm.everythingdone/files/detail-media-geometry/$deviceRun" $localDirectory | Out-Null
        if ($LASTEXITCODE -ne 0) { throw '读取验证结果失败' }
        $resultPath = Join-Path $localDirectory "$deviceRun/result.json"
        $result = Get-Content -LiteralPath $resultPath -Raw | ConvertFrom-Json
        $totalFailures += [int]$result.failures
        Write-Output "$DeviceSerial $orientation : failures=$($result.failures), samples=$($result.samples.Count)"
        if ($result.error) { Write-Output $result.error }
    }
} finally {
    if ($rotation -eq 'free') { & $adbPath -s $DeviceSerial shell wm user-rotation free }
    else { & $adbPath -s $DeviceSerial shell wm user-rotation lock $rotation.Substring(5) }
}
if ($totalFailures -gt 0) { throw "媒体比例回归有 $totalFailures 项失败，详见 $localDirectory" }
