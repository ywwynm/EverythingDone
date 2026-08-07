param(
    [string]$AarPath = '',
    [string]$PackageVersion = '1.28.0-r6'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$buildRoot = Join-Path $repoRoot 'build\onnxruntime-custom\package\output\aar_out'
$outputRoot = Join-Path $repoRoot "build\spatial-runtime-publish\$PackageVersion"
$licenseSource = Join-Path $repoRoot 'build\onnxruntime-custom-tool\LICENSE'
$abis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
$libraries = @('libonnxruntime.so', 'libonnxruntime4j_jni.so')

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

function Resolve-Aar([string]$RequestedPath) {
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        return (Get-Item -LiteralPath $RequestedPath).FullName
    }
    $candidates = @(Get-ChildItem -LiteralPath $buildRoot -Recurse -File -Filter '*.aar')
    if ($candidates.Count -ne 1) {
        throw "预期找到一个自定义 AAR，实际为 $($candidates.Count)：$buildRoot"
    }
    return $candidates[0].FullName
}

function Copy-ZipEntry(
    [IO.Compression.ZipArchive]$Archive,
    [string]$EntryName,
    [string]$TargetPath
) {
    $entry = $Archive.GetEntry($EntryName)
    if ($null -eq $entry) {
        throw "AAR 缺少 $EntryName"
    }
    $input = $entry.Open()
    try {
        $output = [IO.File]::Create($TargetPath)
        try {
            $input.CopyTo($output)
            $output.Flush($true)
        } finally {
            $output.Dispose()
        }
    } finally {
        $input.Dispose()
    }
}

function Assert-Elf([string]$Path) {
    $bytes = [IO.File]::ReadAllBytes($Path)
    if ($bytes.Length -lt 4 -or
        $bytes[0] -ne 0x7f -or
        $bytes[1] -ne 0x45 -or
        $bytes[2] -ne 0x4c -or
        $bytes[3] -ne 0x46) {
        throw "文件不是 ELF：$Path"
    }
}

function New-DeterministicArchive(
    [string]$Path,
    [string]$SourceDirectory
) {
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path
    }
    $stream = [IO.File]::Open($Path, [IO.FileMode]::CreateNew)
    try {
        $archive = [IO.Compression.ZipArchive]::new(
            $stream,
            [IO.Compression.ZipArchiveMode]::Create,
            $false
        )
        try {
            foreach ($library in $libraries) {
                $source = Join-Path $SourceDirectory $library
                $entry = $archive.CreateEntry(
                    $library,
                    [IO.Compression.CompressionLevel]::Optimal
                )
                $entry.LastWriteTime = [DateTimeOffset]::new(
                    1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero
                )
                $entry.ExternalAttributes = 0
                $input = [IO.File]::OpenRead($source)
                try {
                    $output = $entry.Open()
                    try {
                        $input.CopyTo($output)
                    } finally {
                        $output.Dispose()
                    }
                } finally {
                    $input.Dispose()
                }
            }
        } finally {
            $archive.Dispose()
        }
    } finally {
        $stream.Dispose()
    }
}

$resolvedAar = Resolve-Aar $AarPath
if (-not (Test-Path -LiteralPath $licenseSource)) {
    throw "缺少 ONNX Runtime MIT 许可文件：$licenseSource"
}

New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null
$stagingRoot = Join-Path $outputRoot '.staging'
if (Test-Path -LiteralPath $stagingRoot) {
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}
New-Item -ItemType Directory -Path $stagingRoot | Out-Null

$aar = [IO.Compression.ZipFile]::OpenRead($resolvedAar)
try {
    $runtimeEntries = @()
    foreach ($abi in $abis) {
        $abiDirectory = Join-Path $stagingRoot $abi
        New-Item -ItemType Directory -Path $abiDirectory | Out-Null
        foreach ($library in $libraries) {
            $target = Join-Path $abiDirectory $library
            Copy-ZipEntry $aar "jni/$abi/$library" $target
            Assert-Elf $target
        }

        $core = Get-Item -LiteralPath (Join-Path $abiDirectory 'libonnxruntime.so')
        $jni = Get-Item -LiteralPath (Join-Path $abiDirectory 'libonnxruntime4j_jni.so')
        $archiveName = "onnxruntime-$PackageVersion-$abi.zip"
        $archivePath = Join-Path $outputRoot $archiveName
        New-DeterministicArchive $archivePath $abiDirectory
        $package = Get-Item -LiteralPath $archivePath

        $runtimeEntries += [ordered]@{
            id = 'onnxruntime'
            packageVersion = $PackageVersion
            ortVersion = '1.28.0'
            runtimeApiVersion = 1
            abi = $abi
            fileName = $archiveName
            sizeBytes = [Int64]$package.Length
            sha256 = Sha256 $package.FullName
            unpackedSizeBytes = [Int64]($core.Length + $jni.Length)
            coreSizeBytes = [Int64]$core.Length
            coreSha256 = Sha256 $core.FullName
            jniSizeBytes = [Int64]$jni.Length
            jniSha256 = Sha256 $jni.FullName
            license = 'MIT'
        }
    }
} finally {
    $aar.Dispose()
    Remove-Item -LiteralPath $stagingRoot -Recurse -Force
}

[IO.File]::Copy($licenseSource, (Join-Path $outputRoot 'LICENSE.txt'), $true)
$metadata = [ordered]@{
    schemaVersion = 1
    generatedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    sourceAar = [IO.Path]::GetFileName($resolvedAar)
    packageVersion = $PackageVersion
    runtimes = $runtimeEntries
}
Write-Utf8NoBom (
    Join-Path $outputRoot 'runtime-packages.json'
) (($metadata | ConvertTo-Json -Depth 6) + "`n")

Write-Host "已生成四套 ABI 运行组件：$outputRoot"
