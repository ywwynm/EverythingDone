# 从既有的单 arch QNN 运行组件包合成一个「全 arch」包。
#
# 为什么需要它（D267）：判定表按 SoC 型号全等匹配，新骁龙上市时必然查不到，
# 后果是设置页整块不可见。全 arch 包里带每一档的 Skel+Stub，由 QNN 自己按探测到的
# SoC 挑——OPD2515 实测四份同时在目录里时只 dlopen 了 libQnnHtpV81Stub.so，
# 且该机的硅是 v85，QNN 取的是「不超过硬件档的最高可用 Skel」，因此比我们打包的
# 任何一档更新的芯片也能工作。
#
# 共享库（libonnxruntime / libQnnHtp / libQnnSystem / libQnnHtpPrepare）在四个单 arch
# 包里 sha256 完全相同，合并时只保留一份；只有 Skel+Stub 是每档一份。
# 代价实测：压缩后 50.25 MB → 64.80 MB，解包后 136.81 MB → 191.87 MB。
#
# 用法：
#   .\build-qnn-all-arch-package.ps1 -PackageVersion 1.28.0-qnn-r2
# 产物直接追加进同目录的 qnn-runtime-packages.json，随后照常跑 publish-spatial-models.ps1。

[CmdletBinding()]
param(
    [string]$PackageVersion = '1.28.0-qnn-r2'
)

$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem

$repoRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$root = Join-Path $repoRoot "build\spatial-qnn-runtime-publish\$PackageVersion"
$metadataPath = Join-Path $root 'qnn-runtime-packages.json'
if (-not (Test-Path -LiteralPath $metadataPath)) { throw "缺少 $metadataPath" }

function Sha256([string]$path) {
    (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
}

$metadata = Get-Content -LiteralPath $metadataPath -Raw -Encoding UTF8 | ConvertFrom-Json
if ($metadata.packageVersion -ne $PackageVersion) { throw '元数据版本不符' }

$singleArch = @($metadata.packages | Where-Object { $_.dspArch -ne 'all' })
if ($singleArch.Count -lt 2) { throw "至少需要两个单 arch 包才有合并的意义" }

$work = Join-Path $root '.all-arch-work'
if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
New-Item -ItemType Directory -Path $work | Out-Null

# ---- 逐包校验并展开，共享文件只留一份，同名不同内容一律报错 ----
$fileHashes = @{}
$archOwned = @{}
foreach ($pkg in $singleArch) {
    $zipPath = Join-Path $root $pkg.fileName
    if (-not (Test-Path -LiteralPath $zipPath)) { throw "缺少 $($pkg.fileName)" }
    if ((Sha256 $zipPath) -ne $pkg.sha256.ToLowerInvariant()) {
        throw "$($pkg.dspArch) 包 SHA-256 与元数据不符"
    }
    $zip = [System.IO.Compression.ZipFile]::OpenRead($zipPath)
    try {
        foreach ($item in $zip.Entries) {
            if (-not $item.Name.EndsWith('.so')) { continue }
            $dest = Join-Path $work $item.Name
            if (Test-Path -LiteralPath $dest) {
                # 已由前一个 arch 写入：确认确实是同一份，否则合并不成立
                $tmp = Join-Path $work ".cmp-$($item.Name)"
                [System.IO.Compression.ZipFileExtensions]::ExtractToFile($item, $tmp, $true)
                $same = (Sha256 $tmp) -eq $fileHashes[$item.Name]
                Remove-Item -LiteralPath $tmp -Force
                if (-not $same) {
                    throw "$($item.Name) 在不同 arch 包里内容不一致，不能合并"
                }
                continue
            }
            [System.IO.Compression.ZipFileExtensions]::ExtractToFile($item, $dest, $true)
            $fileHashes[$item.Name] = Sha256 $dest
            if ($item.Name -match 'V\d+(Skel|Stub)\.so$') { $archOwned[$item.Name] = $pkg.dspArch }
        }
    } finally { $zip.Dispose() }
}

# 每一档都必须 Skel 与 Stub 齐全，缺一份等于那一档静默不可用
foreach ($pkg in $singleArch) {
    $upper = $pkg.dspArch.ToUpperInvariant()
    foreach ($suffix in @('Skel', 'Stub')) {
        $name = "libQnnHtp$upper$suffix.so"
        if (-not (Test-Path -LiteralPath (Join-Path $work $name))) { throw "缺少 $name" }
    }
}

# ---- 打包 ----
$outName = "onnxruntime-qnn-$PackageVersion-arm64-v8a-all.zip"
$outPath = Join-Path $root $outName
if (Test-Path -LiteralPath $outPath) { Remove-Item -LiteralPath $outPath -Force }
[System.IO.Compression.ZipFile]::CreateFromDirectory(
    $work, $outPath, [System.IO.Compression.CompressionLevel]::Optimal, $false)

$template = $singleArch[0]
$core = Join-Path $work 'libonnxruntime.so'
$jni = Join-Path $work 'libonnxruntime4j_jni.so'
# 用 foreach 而不是 ForEach-Object：管道里的 `$extra +=` 改的是副本，收不到结果。
# 求和也不能用 Measure-Object -Property——[ordered] 是 OrderedDictionary，
# 它的键不是 PS 意义上的属性。
$extra = @()
$extraBytes = 0L
foreach ($file in (Get-ChildItem -LiteralPath $work -Filter *.so | Sort-Object Name)) {
    if ($file.Name -eq 'libonnxruntime.so' -or $file.Name -eq 'libonnxruntime4j_jni.so') {
        continue
    }
    $extra += [ordered]@{
        name = $file.Name
        sizeBytes = $file.Length
        sha256 = Sha256 $file.FullName
    }
    $extraBytes += $file.Length
}
# App 侧 SpatialQnnRuntimeCatalogEntry.isCompatible() 硬校验这两条
if ($extra.Count -gt 16) { throw "extraFiles 超过 App 允许的 16 个：$($extra.Count)" }
$unpacked = (Get-Item $core).Length + (Get-Item $jni).Length + $extraBytes

$allEntry = [ordered]@{
    dspArch = 'all'
    fileName = $outName
    sizeBytes = (Get-Item $outPath).Length
    sha256 = Sha256 $outPath
    unpackedSizeBytes = $unpacked
    coreSizeBytes = (Get-Item $core).Length
    coreSha256 = Sha256 $core
    jniSizeBytes = (Get-Item $jni).Length
    jniSha256 = Sha256 $jni
    extraFiles = $extra
}

$kept = @($metadata.packages | Where-Object { $_.dspArch -ne 'all' } | ForEach-Object { $_ })
$newPackages = $kept + $allEntry
$out = [ordered]@{
    packageVersion = $metadata.packageVersion
    abi = $metadata.abi
    packages = $newPackages
}
$json = $out | ConvertTo-Json -Depth 8
[System.IO.File]::WriteAllText($metadataPath, $json + "`n", (New-Object System.Text.UTF8Encoding($false)))

Remove-Item -LiteralPath $work -Recurse -Force

Write-Output ("全 arch 包: {0}" -f $outName)
Write-Output ("  压缩后 {0:N2} MB（单 arch 为 {1:N2} MB）" -f ($allEntry.sizeBytes / 1e6), ($template.sizeBytes / 1e6))
Write-Output ("  解包后 {0:N2} MB（单 arch 为 {1:N2} MB）" -f ($unpacked / 1e6), ($template.unpackedSizeBytes / 1e6))
Write-Output ("  extraFiles {0} 个" -f $extra.Count)
Write-Output "元数据已更新，接着跑 publish-spatial-models.ps1"
