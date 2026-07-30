param(
    [Parameter(Mandatory = $true)]
    [ValidateSet("OPPO", "Samsung")]
    [string]$Device,

    [Parameter(Mandatory = $true)]
    [string]$OutputRoot,

    [string]$CaseId,

    [int]$TimeoutSeconds = 1200
)

$ErrorActionPreference = "Stop"

$adb = "E:\AndroidSDK\platform-tools\adb.exe"
$package = "com.ywwynm.everythingdone"
$prefsPath = "shared_prefs/fablesol_tuning.xml"
$backupPath = "cache/codex-fablesol-export-matrix-original.xml"
$exportTool = Join-Path $PSScriptRoot "export_sample_via_adb.ps1"

function New-MatrixCase {
    param(
        [Parameter(Mandatory = $true)][string]$Id,
        [Parameter(Mandatory = $true)][string]$Description,
        [Parameter(Mandatory = $true)][string]$ColorMode,
        [Parameter(Mandatory = $true)][string]$Codec,
        [Parameter(Mandatory = $true)][int]$FrameRate,
        [Parameter(Mandatory = $true)][string]$RateControl,
        [string]$SdrMapping = "stable",
        [string]$SdrBitDepth = "auto",
        [string]$HlgRange = "auto-enhanced",
        [bool]$BFrames = $false,
        [bool]$HighComplexity = $true,
        [bool]$QpGuard = $true,
        [float]$KeyframeSeconds = 2.0,
        [Nullable[float]]$BitrateMbps,
        [Nullable[float]]$PqWhiteNits,
        [float]$ReferencePeakNits = 1000.0,
        [int]$HighlightStartPercent = 90
    )

    [pscustomobject]@{
        id = $Id
        description = $Description
        colorMode = $ColorMode
        codec = $Codec
        frameRate = $FrameRate
        rateControl = $RateControl
        sdrMapping = $SdrMapping
        sdrBitDepth = $SdrBitDepth
        hlgRange = $HlgRange
        bFrames = $BFrames
        highComplexity = $HighComplexity
        qpGuard = $QpGuard
        keyframeSeconds = $KeyframeSeconds
        bitrateMbps = $BitrateMbps
        pqWhiteNits = $PqWhiteNits
        referencePeakNits = $ReferencePeakNits
        highlightStartPercent = $HighlightStartPercent
    }
}

function Get-OppoCases {
    @(
        New-MatrixCase -Id "O01_HDR_VIVID_HEVC_120_VBR_STRESS" `
            -Description "HDR Vivid；HEVC 120 fps；强动态曲线、短 GOP、B 帧、高复杂度与 QP 保护" `
            -ColorMode "hdr-vivid" -Codec "hevc" -FrameRate 120 -RateControl "vbr" `
            -BFrames $true -HighComplexity $true -QpGuard $true -KeyframeSeconds 0.5 `
            -BitrateMbps 60 -ReferencePeakNits 400 -HighlightStartPercent 50
        New-MatrixCase -Id "O02_HDR_VIVID_HEVC_60_VBR_GENTLE" `
            -Description "HDR Vivid；HEVC 60 fps；350 尼特自定义白、长 GOP、关闭三个编码增强开关" `
            -ColorMode "hdr-vivid" -Codec "hevc" -FrameRate 60 -RateControl "vbr" `
            -BFrames $false -HighComplexity $false -QpGuard $false -KeyframeSeconds 10 `
            -PqWhiteNits 350 -ReferencePeakNits 4000 -HighlightStartPercent 99
        New-MatrixCase -Id "O03_HDR10PLUS_HEVC_120_VBR" `
            -Description "HDR10+；HEVC 120 fps；默认动态曲线、B 帧与质量保护" `
            -ColorMode "hdr10-plus" -Codec "hevc" -FrameRate 120 -RateControl "vbr" `
            -BFrames $true -HighComplexity $true -QpGuard $true
        New-MatrixCase -Id "O04_DV84_HEVC_60_VBR_NOMINAL" `
            -Description "杜比视界 8.4；HEVC 60 fps；名义 HLG 基层、固定码率与中等 GOP" `
            -ColorMode "dolby-vision-84" -Codec "hevc" -FrameRate 60 -RateControl "vbr" `
            -HlgRange "nominal" -BFrames $false -HighComplexity $false -QpGuard $false `
            -KeyframeSeconds 5 -BitrateMbps 24
        New-MatrixCase -Id "O05_HDR10_HEVC_120_VBR_MAXWHITE" `
            -Description "HDR10；HEVC 120 fps；800 尼特自定义白、60 Mbps、短 GOP 与 B 帧" `
            -ColorMode "hdr10" -Codec "hevc" -FrameRate 120 -RateControl "vbr" `
            -BFrames $true -HighComplexity $true -QpGuard $true -KeyframeSeconds 0.5 `
            -BitrateMbps 60 -PqWhiteNits 800
        New-MatrixCase -Id "O06_HLG_HEVC_120_VBR_EXTENDED" `
            -Description "HLG；HEVC 120 fps；自动增强信号范围、B 帧与质量保护" `
            -ColorMode "hlg" -Codec "hevc" -FrameRate 120 -RateControl "vbr" `
            -HlgRange "auto-enhanced" -BFrames $true -HighComplexity $true -QpGuard $true
        New-MatrixCase -Id "O07_HLG_HEVC_60_VBR_NOMINAL" `
            -Description "HLG；HEVC 60 fps；名义范围、12 Mbps、长 GOP、编码增强关闭" `
            -ColorMode "hlg" -Codec "hevc" -FrameRate 60 -RateControl "vbr" `
            -HlgRange "nominal" -BFrames $false -HighComplexity $false -QpGuard $false `
            -KeyframeSeconds 10 -BitrateMbps 12
        New-MatrixCase -Id "O08_HDR10_AV1_60_CQ" `
            -Description "HDR10；软件 AV1 60 fps；最高 CQ 与软件 AV1 HDR QP 上限保护" `
            -ColorMode "hdr10" -Codec "av1" -FrameRate 60 -RateControl "cq" `
            -BFrames $false -HighComplexity $true -QpGuard $true
        New-MatrixCase -Id "O09_HLG_AV1_60_VBR" `
            -Description "HLG；软件 AV1 60 fps；目标码率、自动增强范围、增强开关关闭" `
            -ColorMode "hlg" -Codec "av1" -FrameRate 60 -RateControl "vbr" `
            -HlgRange "auto-enhanced" -BFrames $false -HighComplexity $false -QpGuard $false `
            -BitrateMbps 24
        New-MatrixCase -Id "O10_SDR_NATIVE_HEVC10_120_VBR" `
            -Description "SDR 原生；HEVC 10-bit 120 fps；B 帧、短 GOP 与质量保护" `
            -ColorMode "sdr-native" -Codec "hevc" -FrameRate 120 -RateControl "vbr" `
            -SdrBitDepth "ten-bit" -BFrames $true -HighComplexity $true -QpGuard $true `
            -KeyframeSeconds 0.5
        New-MatrixCase -Id "O11_SDR_DTM_H2648_120_VBR" `
            -Description "SDR 动态保留高光；H.264 8-bit 120 fps；B 帧与 24 Mbps" `
            -ColorMode "sdr-tone-mapped" -Codec "avc" -FrameRate 120 -RateControl "vbr" `
            -SdrMapping "dynamic" -SdrBitDepth "eight-bit" -BFrames $true `
            -HighComplexity $true -QpGuard $true -BitrateMbps 24
        New-MatrixCase -Id "O12_SDR_STABLE_AV18_60_CQ" `
            -Description "SDR 稳定保留高光；软件 AV1 8-bit 60 fps；最高 CQ 与长 GOP" `
            -ColorMode "sdr-tone-mapped" -Codec "av1" -FrameRate 60 -RateControl "cq" `
            -SdrMapping "stable" -SdrBitDepth "eight-bit" -BFrames $false `
            -HighComplexity $true -QpGuard $true -KeyframeSeconds 10
        New-MatrixCase -Id "O13_SDR_NATIVE_AV110_60_VBR" `
            -Description "SDR 原生；软件 AV1 10-bit 60 fps；12 Mbps、编码增强关闭" `
            -ColorMode "sdr-native" -Codec "av1" -FrameRate 60 -RateControl "vbr" `
            -SdrBitDepth "ten-bit" -BFrames $false -HighComplexity $false -QpGuard $false `
            -BitrateMbps 12
        New-MatrixCase -Id "O14_HDR10_AV1_60_VBR_QP_GUARD" `
            -Description "HDR10；软件 AV1 60 fps；24 Mbps 目标码率，开启复杂帧保护验证 D191 不受开关影响" `
            -ColorMode "hdr10" -Codec "av1" -FrameRate 60 -RateControl "vbr" `
            -BFrames $false -HighComplexity $false -QpGuard $true -BitrateMbps 24
        New-MatrixCase -Id "O15_HLG_AV1_60_VBR_QP_GUARD" `
            -Description "HLG；软件 AV1 60 fps；12 Mbps、自动增强范围，开启复杂帧保护验证 D191 不受开关影响" `
            -ColorMode "hlg" -Codec "av1" -FrameRate 60 -RateControl "vbr" `
            -HlgRange "auto-enhanced" -BFrames $false -HighComplexity $false -QpGuard $true `
            -BitrateMbps 12
        New-MatrixCase -Id "O16_HLG_AV1_60_CQ" `
            -Description "HLG；软件 AV1 60 fps；最高 CQ，用于区分 HLG 路径与 VBR 码控问题" `
            -ColorMode "hlg" -Codec "av1" -FrameRate 60 -RateControl "cq" `
            -HlgRange "auto-enhanced" -BFrames $false -HighComplexity $true -QpGuard $true
    )
}

function Get-SamsungCases {
    @(
        New-MatrixCase -Id "S01_HDR10_AV1_60_CQ" `
            -Description "HDR10；软件 AV1 60 fps；最高 CQ 与软件 AV1 HDR QP 上限保护" `
            -ColorMode "hdr10" -Codec "av1" -FrameRate 60 -RateControl "cq" `
            -BFrames $false -HighComplexity $true -QpGuard $true
        New-MatrixCase -Id "S02_HDR10_AV1_60_VBR" `
            -Description "HDR10；软件 AV1 60 fps；24 Mbps 目标码率、编码增强关闭" `
            -ColorMode "hdr10" -Codec "av1" -FrameRate 60 -RateControl "vbr" `
            -BFrames $false -HighComplexity $false -QpGuard $false -BitrateMbps 24
        New-MatrixCase -Id "S03_HLG_AV1_60_CQ_NOMINAL" `
            -Description "HLG；软件 AV1 60 fps；最高 CQ、名义范围与 QP 保护" `
            -ColorMode "hlg" -Codec "av1" -FrameRate 60 -RateControl "cq" `
            -HlgRange "nominal" -BFrames $false -HighComplexity $true -QpGuard $true
        New-MatrixCase -Id "S04_HLG_AV1_60_VBR_EXTENDED" `
            -Description "HLG；软件 AV1 60 fps；12 Mbps、自动增强范围、编码增强关闭" `
            -ColorMode "hlg" -Codec "av1" -FrameRate 60 -RateControl "vbr" `
            -HlgRange "auto-enhanced" -BFrames $false -HighComplexity $false -QpGuard $false `
            -BitrateMbps 12
        New-MatrixCase -Id "S05_SDR_NATIVE_HEVC8_120_VBR" `
            -Description "SDR 原生；硬件 HEVC 8-bit 120 fps；B 帧、短 GOP 与高复杂度请求" `
            -ColorMode "sdr-native" -Codec "hevc" -FrameRate 120 -RateControl "vbr" `
            -SdrBitDepth "eight-bit" -BFrames $true -HighComplexity $true -QpGuard $true `
            -KeyframeSeconds 0.5
        New-MatrixCase -Id "S06_SDR_DTM_H2648_120_VBR" `
            -Description "SDR 动态保留高光；硬件 H.264 8-bit 120 fps；B 帧、长 GOP 与 24 Mbps" `
            -ColorMode "sdr-tone-mapped" -Codec "avc" -FrameRate 120 -RateControl "vbr" `
            -SdrMapping "dynamic" -SdrBitDepth "eight-bit" -BFrames $true `
            -HighComplexity $false -QpGuard $false -KeyframeSeconds 10 -BitrateMbps 24
        New-MatrixCase -Id "S07_SDR_NATIVE_AV110_60_CQ" `
            -Description "SDR 原生；软件 AV1 10-bit 60 fps；最高 CQ 与质量保护" `
            -ColorMode "sdr-native" -Codec "av1" -FrameRate 60 -RateControl "cq" `
            -SdrBitDepth "ten-bit" -BFrames $false -HighComplexity $true -QpGuard $true
        New-MatrixCase -Id "S08_SDR_STABLE_AV18_60_VBR" `
            -Description "SDR 稳定保留高光；软件 AV1 8-bit 60 fps；12 Mbps、编码增强关闭" `
            -ColorMode "sdr-tone-mapped" -Codec "av1" -FrameRate 60 -RateControl "vbr" `
            -SdrMapping "stable" -SdrBitDepth "eight-bit" -BFrames $false `
            -HighComplexity $false -QpGuard $false -BitrateMbps 12
        New-MatrixCase -Id "S09_SDR_STABLE_HEVC8_60_VBR" `
            -Description "SDR 稳定保留高光；硬件 HEVC 8-bit 60 fps；12 Mbps 与默认 GOP" `
            -ColorMode "sdr-tone-mapped" -Codec "hevc" -FrameRate 60 -RateControl "vbr" `
            -SdrMapping "stable" -SdrBitDepth "eight-bit" -BFrames $false `
            -HighComplexity $true -QpGuard $true -BitrateMbps 12
        New-MatrixCase -Id "S10_SDR_NATIVE_H2648_60_VBR" `
            -Description "SDR 原生；硬件 H.264 8-bit 60 fps；自动码率、默认 GOP 与 B 帧关闭" `
            -ColorMode "sdr-native" -Codec "avc" -FrameRate 60 -RateControl "vbr" `
            -SdrBitDepth "eight-bit" -BFrames $false -HighComplexity $true -QpGuard $true
        New-MatrixCase -Id "S11_HDR10_AV1_60_VBR_QP_GUARD" `
            -Description "HDR10；软件 AV1 60 fps；24 Mbps 目标码率，开启复杂帧保护验证 D191 不受开关影响" `
            -ColorMode "hdr10" -Codec "av1" -FrameRate 60 -RateControl "vbr" `
            -BFrames $false -HighComplexity $false -QpGuard $true -BitrateMbps 24
        New-MatrixCase -Id "S12_HLG_AV1_60_VBR_QP_GUARD" `
            -Description "HLG；软件 AV1 60 fps；12 Mbps、自动增强范围，开启复杂帧保护验证 D191 不受开关影响" `
            -ColorMode "hlg" -Codec "av1" -FrameRate 60 -RateControl "vbr" `
            -HlgRange "auto-enhanced" -BFrames $false -HighComplexity $false -QpGuard $true `
            -BitrateMbps 12
    )
}

function Invoke-Adb {
    param([Parameter(ValueFromRemainingArguments = $true)][string[]]$Arguments)

    $output = & $adb -s $script:serial @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "ADB 命令失败：$($Arguments -join ' ')"
    }
    return $output
}

function ConvertTo-AndroidPrefsXml {
    param([Parameter(Mandatory = $true)]$MatrixCase)

    $values = [ordered]@{
        export_prefs_version = @{ type = "int"; value = 1 }
        export_frame_rate = @{ type = "int"; value = $MatrixCase.frameRate }
        export_color_mode = @{ type = "string"; value = $MatrixCase.colorMode }
        export_sdr_mapping = @{ type = "string"; value = $MatrixCase.sdrMapping }
        export_sdr_bit_depth = @{ type = "string"; value = $MatrixCase.sdrBitDepth }
        export_hlg_range = @{ type = "string"; value = $MatrixCase.hlgRange }
        export_rate_control = @{ type = "string"; value = $MatrixCase.rateControl }
        export_codec_family = @{ type = "string"; value = $MatrixCase.codec }
        export_reference_peak = @{ type = "float"; value = $MatrixCase.referencePeakNits }
        export_highlight_start = @{ type = "int"; value = $MatrixCase.highlightStartPercent }
        export_b_frames = @{ type = "boolean"; value = $MatrixCase.bFrames }
        export_high_complexity = @{ type = "boolean"; value = $MatrixCase.highComplexity }
        export_qp_guard = @{ type = "boolean"; value = $MatrixCase.qpGuard }
        export_keyframe = @{ type = "float"; value = $MatrixCase.keyframeSeconds }
        export_tilt = @{ type = "boolean"; value = $true }
    }
    if ($null -ne $MatrixCase.bitrateMbps) {
        $values.export_bitrate = @{ type = "float"; value = $MatrixCase.bitrateMbps.Value }
    }
    if ($null -ne $MatrixCase.pqWhiteNits) {
        $values.export_pq_white = @{ type = "float"; value = $MatrixCase.pqWhiteNits.Value }
    }

    $invariant = [Globalization.CultureInfo]::InvariantCulture
    $lines = [Collections.Generic.List[string]]::new()
    $lines.Add("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>")
    $lines.Add("<map>")
    foreach ($entry in $values.GetEnumerator()) {
        $name = [Security.SecurityElement]::Escape([string]$entry.Key)
        $type = $entry.Value.type
        $value = $entry.Value.value
        switch ($type) {
            "string" {
                $escaped = [Security.SecurityElement]::Escape([string]$value)
                $lines.Add(('    <string name="{0}">{1}</string>' -f $name, $escaped))
            }
            "boolean" {
                $encoded = if ([bool]$value) { "true" } else { "false" }
                $lines.Add(('    <boolean name="{0}" value="{1}" />' -f $name, $encoded))
            }
            "float" {
                $encoded = ([single]$value).ToString("0.0###", $invariant)
                $lines.Add(('    <float name="{0}" value="{1}" />' -f $name, $encoded))
            }
            "int" {
                $lines.Add(('    <int name="{0}" value="{1}" />' -f $name, [int]$value))
            }
            default {
                throw "不支持的偏好类型：$type"
            }
        }
    }
    $lines.Add("</map>")
    return ($lines -join "`n") + "`n"
}

function Set-DevicePrefs {
    param([Parameter(Mandatory = $true)][string]$Xml)

    $bytes = [Text.Encoding]::UTF8.GetBytes($Xml)
    $base64 = [Convert]::ToBase64String($bytes)
    Invoke-Adb shell am force-stop $package | Out-Null
    $command = "run-as $package sh -c 'echo $base64 | base64 -d > $prefsPath'"
    Invoke-Adb shell $command | Out-Null
    $actual = (Invoke-Adb shell run-as $package cat $prefsPath) -join "`n"
    if ($actual.Trim() -ne $Xml.Trim()) {
        throw "设备端偏好回读与请求不一致"
    }
}

if (-not (Test-Path -LiteralPath $adb)) {
    throw "ADB 不存在：$adb"
}
if (-not (Test-Path -LiteralPath $exportTool)) {
    throw "导出工具不存在：$exportTool"
}

if ($Device -eq "OPPO") {
    $script:serial = "3B1629006YC00000"
    $noteText = "测试音频呀"
    $cases = @(Get-OppoCases)
} else {
    $script:serial = "RFCT90LSFGT"
    $noteText = "嘿嘿"
    $cases = @(Get-SamsungCases)
}
if ($CaseId) {
    $cases = @($cases | Where-Object { $_.id -eq $CaseId })
    if ($cases.Count -ne 1) {
        throw "没有找到矩阵项：$CaseId"
    }
}

$connected = & $adb devices
if (-not ($connected -match "(?m)^$([regex]::Escape($script:serial))\s+device\b")) {
    throw "目标设备未连接：$($script:serial)"
}

$resolvedOutputRoot = [IO.Path]::GetFullPath($OutputRoot)
New-Item -ItemType Directory -Force -Path $resolvedOutputRoot | Out-Null
$results = [Collections.Generic.List[object]]::new()
$backupCreated = $false

try {
    Invoke-Adb shell am force-stop $package | Out-Null
    Invoke-Adb shell run-as $package cp $prefsPath $backupPath | Out-Null
    $backupCreated = $true

    foreach ($matrixCase in $cases) {
        $startedAt = [DateTimeOffset]::Now
        $xml = ConvertTo-AndroidPrefsXml -MatrixCase $matrixCase
        $requestedPath = Join-Path $resolvedOutputRoot "$($matrixCase.id).requested.json"
        $prefsSnapshotPath = Join-Path $resolvedOutputRoot "$($matrixCase.id).prefs.xml"
        [IO.File]::WriteAllText(
            $requestedPath,
            ($matrixCase | ConvertTo-Json -Depth 5),
            [Text.UTF8Encoding]::new($false)
        )
        [IO.File]::WriteAllText(
            $prefsSnapshotPath,
            $xml,
            [Text.UTF8Encoding]::new($false)
        )

        Write-Output "===== $($matrixCase.id)：$($matrixCase.description) ====="
        try {
            Set-DevicePrefs -Xml $xml
            $outputPath = Join-Path $resolvedOutputRoot "$($matrixCase.id).mp4"
            & $exportTool -OutputPath $outputPath -Serial $script:serial `
                -NoteText $noteText -TimeoutSeconds $TimeoutSeconds
            $results.Add([pscustomobject]@{
                id = $matrixCase.id
                success = $true
                startedAt = $startedAt.ToString("o")
                finishedAt = [DateTimeOffset]::Now.ToString("o")
                outputPath = $outputPath
                error = $null
            })
        } catch {
            $message = $_.Exception.ToString()
            $failurePath = Join-Path $resolvedOutputRoot "$($matrixCase.id).failure.txt"
            [IO.File]::WriteAllText(
                $failurePath,
                $message,
                [Text.UTF8Encoding]::new($false)
            )
            Write-Output "矩阵项失败：$message"
            $results.Add([pscustomobject]@{
                id = $matrixCase.id
                success = $false
                startedAt = $startedAt.ToString("o")
                finishedAt = [DateTimeOffset]::Now.ToString("o")
                outputPath = $null
                error = $message
            })
        }
    }
} finally {
    if ($backupCreated) {
        try {
            Invoke-Adb shell am force-stop $package | Out-Null
            Invoke-Adb shell run-as $package cp $backupPath $prefsPath | Out-Null
            Invoke-Adb shell run-as $package rm $backupPath | Out-Null
            Write-Output "已恢复 $Device 的原始 FableSol 导出偏好。"
        } catch {
            Write-Output "警告：恢复原始偏好失败，必须人工检查：$($_.Exception.Message)"
        }
    }
    $summaryPath = Join-Path $resolvedOutputRoot "matrix-results.json"
    [IO.File]::WriteAllText(
        $summaryPath,
        ($results | ConvertTo-Json -Depth 5),
        [Text.UTF8Encoding]::new($false)
    )
}

$successCount = @($results | Where-Object success).Count
$failureCount = $results.Count - $successCount
Write-Output "矩阵完成：成功 $successCount，失败 $failureCount；结果目录 $resolvedOutputRoot"
