param(
    [Parameter(Mandatory = $true)]
    [string]$OutputPath,

    [string]$Serial = "3B1629006YC00000",

    [string]$NoteText = "测试音频呀",

    [int]$TimeoutSeconds = 900
)

# Windows PowerShell 5 会把 adb pull 写入 stderr 的正常进度误报为 NativeCommandError；
# 所有 ADB 调用均显式检查 LASTEXITCODE，因此此处不依赖原生命令的 stderr 判定成败。
$ErrorActionPreference = "Continue"

$adb = "E:\AndroidSDK\platform-tools\adb.exe"
$package = "com.ywwynm.everythingdone"
$mainActivity = "$package/.activities.ThingsActivity"
$remoteUi = "/sdcard/fablesol-export-ui.xml"
$remoteDirectory = "/sdcard/Movies/EverythingDone"
$safeSerial = $Serial -replace "[^A-Za-z0-9._-]", "_"
$localUi = Join-Path ([System.IO.Path]::GetTempPath()) "fablesol-export-ui-$safeSerial.xml"

if (-not (Test-Path -LiteralPath $adb)) {
    throw "ADB 不存在：$adb"
}

$connected = & $adb devices
if (-not ($connected -match "(?m)^$([regex]::Escape($Serial))\s+device\b")) {
    throw "目标设备未连接：$Serial"
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $output = & $adb -s $Serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "ADB 命令失败：$($Arguments -join ' ')"
    }
    return $output
}

function Get-Ui {
    for ($attempt = 0; $attempt -lt 3; $attempt++) {
        & $adb -s $Serial shell uiautomator dump $remoteUi 2>$null | Out-Null
        if ($LASTEXITCODE -eq 0) {
            & $adb -s $Serial pull $remoteUi $localUi 2>$null | Out-Null
            if ($LASTEXITCODE -eq 0 -and (Test-Path -LiteralPath $localUi)) {
                return [xml](Get-Content -LiteralPath $localUi -Raw -Encoding utf8)
            }
        }
        Start-Sleep -Milliseconds 200
    }
    throw "无法读取设备 UI 层级"
}

function Get-Center {
    param([Parameter(Mandatory = $true)][string]$Bounds)

    if ($Bounds -notmatch "^\[(\d+),(\d+)\]\[(\d+),(\d+)\]$") {
        throw "控件边界格式无效：$Bounds"
    }
    return @(
        [int](([int]$matches[1] + [int]$matches[3]) / 2),
        [int](([int]$matches[2] + [int]$matches[4]) / 2)
    )
}

function Tap-Node {
    param([Parameter(Mandatory = $true)][System.Xml.XmlElement]$Node)

    $center = Get-Center -Bounds $Node.bounds
    Invoke-Adb shell input tap $center[0] $center[1] | Out-Null
}

function Get-TopActivity {
    $line = Invoke-Adb shell dumpsys activity activities |
        Select-String "topResumedActivity" |
        Select-Object -First 1
    if ($null -eq $line) {
        # Activity 切换的极短窗口内，dumpsys 可能暂时没有 topResumedActivity。
        return ""
    }
    return $line.ToString()
}

function Wait-TopActivity {
    param(
        [Parameter(Mandatory = $true)][string]$Pattern,
        [int]$Attempts = 25
    )

    for ($attempt = 0; $attempt -lt $Attempts; $attempt++) {
        $top = Get-TopActivity
        if ($top -match $Pattern) {
            return
        }
        Start-Sleep -Milliseconds 200
    }
    throw "未进入预期页面：$Pattern"
}

function Get-RemoteVideos {
    $items = & $adb -s $Serial shell ls -1 $remoteDirectory 2>$null
    if ($LASTEXITCODE -ne 0) {
        return @()
    }
    return @($items | Where-Object { $_ -match "\.mp4$" })
}

# 无论脚本从设置页、详情页还是已有 Dialog 开始，都先返回主列表。
Invoke-Adb shell input keyevent KEYCODE_WAKEUP | Out-Null
Invoke-Adb shell wm dismiss-keyguard | Out-Null
Invoke-Adb -Arguments @("shell", "am", "start", "-W", "-n", $mainActivity) | Out-Null
for ($attempt = 0; $attempt -lt 30; $attempt++) {
    $top = Get-TopActivity
    if ($top -match "ThingsActivity") {
        $ui = Get-Ui
        $listedNote = @(
            $ui.SelectNodes('//node[@text!=""]') |
                Where-Object { $_.text -eq $NoteText }
        ) | Select-Object -First 1
        if ($listedNote) {
            break
        }
        # 冷启动时列表可能尚未绑定；此时返回键会直接退出主界面，应等待数据加载。
        Start-Sleep -Milliseconds 300
        continue
    }
    Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
    Start-Sleep -Milliseconds 300
}

$ui = Get-Ui
$note = @(
    $ui.SelectNodes('//node[@text!=""]') |
        Where-Object { $_.text -eq $NoteText }
) | Select-Object -First 1
if (-not $note) {
    throw "主列表中未找到测试记事「$NoteText」"
}
$clickable = $note
while ($clickable -and $clickable.clickable -ne "true") {
    $clickable = $clickable.ParentNode
}
if (-not $clickable) {
    throw "测试记事没有可点击的父节点"
}
Tap-Node -Node $clickable
Wait-TopActivity -Pattern "DetailActivity"

$ui = Get-Ui
$audioCard = $ui.SelectSingleNode(
    '//node[@resource-id="com.ywwynm.everythingdone:id/cv_audio_attachment"]'
)
if (-not $audioCard) {
    throw "未找到音频附件卡片"
}
Tap-Node -Node $audioCard

$exportButton = $null
for ($attempt = 0; $attempt -lt 20; $attempt++) {
    $ui = Get-Ui
    $exportButton = $ui.SelectSingleNode(
        '//node[@resource-id="com.ywwynm.everythingdone:id/iv_export_fablesol_video"]'
    )
    if ($exportButton) {
        break
    }
    Start-Sleep -Milliseconds 200
}
if (-not $exportButton) {
    throw "音频附件 Dialog 中未找到导出操作"
}

$before = @(Get-RemoteVideos)
Tap-Node -Node $exportButton
Write-Output "已开始导出：$OutputPath"

$watch = [System.Diagnostics.Stopwatch]::StartNew()
$completionUi = $null
while ($watch.Elapsed.TotalSeconds -lt $TimeoutSeconds) {
    $ui = Get-Ui
    $texts = @($ui.SelectNodes('//node[@text!=""]') | ForEach-Object { $_.text })
    $allText = $texts -join "`n"
    if ($allText -match "确认导出规格") {
        throw "导出进入规格变化确认态：`n$allText"
    }
    if ($allText -match "导出失败") {
        throw "导出失败：`n$allText"
    }
    if ($allText -match "导出完成") {
        $completionUi = $ui
        Write-Output "设备端导出完成，耗时 $([math]::Round($watch.Elapsed.TotalSeconds, 1)) 秒"
        break
    }
    if (($watch.Elapsed.Seconds % 15) -lt 3) {
        $status = $texts | Where-Object {
            $_ -match "准备|渲染|%|正在验证"
        } | Select-Object -First 1
        if ($status) {
            Write-Output "导出状态：$status"
        }
    }
    Start-Sleep -Milliseconds 500
}
if (-not $completionUi) {
    throw "导出在 $TimeoutSeconds 秒内未完成"
}

$after = @(Get-RemoteVideos)
$newVideos = @($after | Where-Object { $_ -notin $before })
if ($newVideos.Count -eq 0) {
    throw "导出完成后未发现新的 MP4 文件"
}
$remoteName = $newVideos[-1]
$remotePath = "$remoteDirectory/$remoteName"

$outputDirectory = Split-Path -Parent $OutputPath
if ($outputDirectory) {
    New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
}
Invoke-Adb pull $remotePath $OutputPath | Out-Null

$completionPath = [System.IO.Path]::ChangeExtension($OutputPath, ".completion.xml")
$completionUi.Save($completionPath)
Write-Output "已拉取：$remotePath -> $OutputPath"

# 完成 Dialog 留给下一轮会妨碍导航，拉取成功后关闭即可；产物仍保留在公共相册。
Invoke-Adb shell input keyevent KEYCODE_BACK | Out-Null
