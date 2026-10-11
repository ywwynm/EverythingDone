param(
    [Parameter(Mandatory=$true)][string]$Serial,
    [string]$UiLanguages = 'zh-CN,ja,ru',
    [string]$RunId = 'l10n-wording-20261011',
    [string]$ArchiveName = '2026-10-11-wording',
    [string]$ApkPath
)
$ErrorActionPreference = 'Stop'
$l10nRoot = (Resolve-Path (Join-Path $PSScriptRoot '../../..')).Path
$l10nAdb = 'E:\AndroidSDK\platform-tools\adb.exe'
$l10nDevices = @('9018f404', 'R5CW20BLNKL', '192.168.100.67:42641')
if ($Serial -notin $l10nDevices) { throw '设备不在本轮授权名单内。' }
if ($RunId -notmatch '^[A-Za-z0-9_-]{1,64}$') { throw '无效运行标识。' }
if ($ArchiveName -notmatch '^[A-Za-z0-9_-]{1,64}$') { throw '无效归档标识。' }
$l10nSafeSerial = $Serial.Replace(':', '_')
$l10nLocal = Join-Path $l10nRoot "tmp/localization-implementation/device/$l10nSafeSerial"
New-Item -ItemType Directory -Path $l10nLocal -Force | Out-Null
$l10nServices = & $l10nAdb -s $Serial shell dumpsys activity services com.ywwynm.everythingdone
if ($LASTEXITCODE -ne 0) { throw '无法读取设备服务状态。' }
$l10nServiceRecords = [regex]::Matches(($l10nServices -join "`n"), '(?ms)^\s*\*\s*ServiceRecord\{.*?(?=^\s*\*\s*ServiceRecord\{|\z)')
foreach ($l10nServiceRecord in $l10nServiceRecords) {
    # 系统可能保留已经结束且没有进程的任务记录；它们不是正在做或录音服务。
    $l10nDormantJob = $l10nServiceRecord.Value -match '/(?:androidx\.work\.impl\.background\.systemjob\.SystemJobService|\.services\.PullAliveJobService)\b' -and
        $l10nServiceRecord.Value -match '(?m)^\s*app=null\s*$'
    if (-not $l10nDormantJob) { throw '应用仍有活动服务，需先核实，避免中断正在做的记事或录音。' }
}
if (-not $ApkPath) { $ApkPath = Join-Path $l10nRoot 'app/build/outputs/apk/debug/app-debug.apk' }
$l10nApk = (Resolve-Path -LiteralPath $ApkPath).Path
& $l10nAdb -s $Serial install -r $l10nApk
if ($LASTEXITCODE -ne 0) { throw '安装失败。' }
& $l10nAdb -s $Serial shell am instrument -w -e run_id $RunId -e ui_languages $UiLanguages com.ywwynm.everythingdone/.localization.LocalizationProbe |
    Tee-Object -FilePath (Join-Path $l10nLocal 'instrumentation.txt')
if ($LASTEXITCODE -ne 0) { throw '设备验证命令失败。' }
& $l10nAdb -s $Serial pull "/sdcard/Android/data/com.ywwynm.everythingdone/files/localization/$RunId" $l10nLocal
if ($LASTEXITCODE -ne 0) { throw '验证结果读取失败。' }
$l10nResultPath = Join-Path $l10nLocal "$RunId/result.json"
$l10nResult = Get-Content -LiteralPath $l10nResultPath -Raw -Encoding UTF8 | ConvertFrom-Json
$l10nArchive = Join-Path $PSScriptRoot "validation/$ArchiveName"
New-Item -ItemType Directory -Path $l10nArchive -Force | Out-Null
Copy-Item -LiteralPath $l10nResultPath -Destination (Join-Path $l10nArchive "$l10nSafeSerial.json")
[PSCustomObject]@{ Device=$Serial; Model=$l10nResult.model; Checks=$l10nResult.checks; Failures=$l10nResult.failures.Count }
if ($l10nResult.failures.Count -gt 0) { throw '存在失败检查，详见 JSON 结果。' }
