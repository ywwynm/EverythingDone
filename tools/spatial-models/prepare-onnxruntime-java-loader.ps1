param(
    [string]$InputAar = '',
    [string]$AsmVersion = '9.9'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$outputJar = Join-Path $repoRoot 'app\libs\onnxruntime-java-1.28.0-everythingdone.jar'
$buildRoot = [IO.Path]::GetFullPath(
    (Join-Path $repoRoot 'build\onnxruntime-java-loader')
)
$expectedAarSha256 =
    'f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9'

function Resolve-OneFile([string]$Description, [object[]]$Candidates) {
    $files = @($Candidates | Where-Object { $_ -and (Test-Path -LiteralPath $_) })
    if ($files.Count -ne 1) {
        throw "$Description expected exactly one file; found $($files.Count)"
    }
    return (Get-Item -LiteralPath $files[0]).FullName
}

function Resolve-Aar([string]$RequestedPath) {
    if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        return (Get-Item -LiteralPath $RequestedPath).FullName
    }
    $root = Join-Path $env:USERPROFILE (
        '.gradle\caches\modules-2\files-2.1\com.microsoft.onnxruntime\' +
        'onnxruntime-android\1.28.0'
    )
    return Resolve-OneFile 'ONNX Runtime 1.28.0 AAR' @(
        Get-ChildItem -LiteralPath $root -Recurse -File `
            -Filter 'onnxruntime-android-1.28.0.aar' -ErrorAction SilentlyContinue |
            ForEach-Object FullName
    )
}

function Resolve-AsmJar([string]$Artifact) {
    $root = Join-Path $env:USERPROFILE (
        ".gradle\caches\modules-2\files-2.1\org.ow2.asm\$Artifact\$AsmVersion"
    )
    return Resolve-OneFile "ASM $Artifact $AsmVersion" @(
        Get-ChildItem -LiteralPath $root -Recurse -File `
            -Filter "$Artifact-$AsmVersion.jar" -ErrorAction SilentlyContinue |
            ForEach-Object FullName
    )
}

function Resolve-JavaTool([string]$ToolName) {
    $candidates = @()
    if (-not [string]::IsNullOrWhiteSpace($env:JAVA_HOME)) {
        $candidates += Join-Path $env:JAVA_HOME "bin\$ToolName.exe"
    }
    $command = Get-Command "$ToolName.exe" -ErrorAction SilentlyContinue
    if ($null -ne $command) {
        $candidates += $command.Source
    }
    $candidates += @(
        "E:\JDK17\bin\$ToolName.exe",
        "E:\software\Android Studio\jbr\bin\$ToolName.exe",
        "$env:ProgramFiles\Android\Android Studio\jbr\bin\$ToolName.exe"
    )
    $resolved = @(
        $candidates |
            Where-Object { $_ -and (Test-Path -LiteralPath $_) } |
            Select-Object -Unique
    )
    if ($resolved.Count -lt 1) {
        throw "Cannot find $ToolName; set JAVA_HOME to JDK 17+"
    }
    return (Get-Item -LiteralPath $resolved[0]).FullName
}

function Extract-ClassesJar([string]$AarPath, [string]$TargetPath) {
    Add-Type -AssemblyName System.IO.Compression
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [IO.Compression.ZipFile]::OpenRead($AarPath)
    try {
        $entry = $archive.GetEntry('classes.jar')
        if ($null -eq $entry) {
            throw "AAR does not contain classes.jar: $AarPath"
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
    } finally {
        $archive.Dispose()
    }
}

$resolvedAar = Resolve-Aar $InputAar
$actualAarSha256 = (
    Get-FileHash -LiteralPath $resolvedAar -Algorithm SHA256
).Hash.ToLowerInvariant()
if ($actualAarSha256 -ne $expectedAarSha256) {
    throw (
        "ORT AAR hash does not match the pinned upstream artifact: actual=$actualAarSha256 " +
        "expected=$expectedAarSha256"
    )
}

$expectedBuildParent = [IO.Path]::GetFullPath((Join-Path $repoRoot 'build'))
if (-not $buildRoot.StartsWith(
    $expectedBuildParent + [IO.Path]::DirectorySeparatorChar,
    [StringComparison]::OrdinalIgnoreCase
)) {
    throw "Build staging directory escaped the repository build root: $buildRoot"
}
if (Test-Path -LiteralPath $buildRoot) {
    Remove-Item -LiteralPath $buildRoot -Recurse -Force
}
$compilerOutput = Join-Path $buildRoot 'patcher-classes'
New-Item -ItemType Directory -Path $compilerOutput -Force | Out-Null
New-Item -ItemType Directory -Path (Split-Path $outputJar -Parent) -Force | Out-Null

$originalJar = Join-Path $buildRoot 'onnxruntime-original-classes.jar'
Extract-ClassesJar $resolvedAar $originalJar
$originalJarSha256 = (
    Get-FileHash -LiteralPath $originalJar -Algorithm SHA256
).Hash.ToLowerInvariant()
if ($originalJarSha256 -ne
    'e671acbff1bb86ffbbbe7701cf9351a04e1f58c51ae5b031912451e5988471b6') {
    throw "classes.jar hash does not match pinned ORT 1.28.0: $originalJarSha256"
}

$asmJar = Resolve-AsmJar 'asm'
$asmTreeJar = Resolve-AsmJar 'asm-tree'
$javac = Resolve-JavaTool 'javac'
$java = Resolve-JavaTool 'java'
$classpath = "$asmJar;$asmTreeJar"
$patcherSource = Join-Path $PSScriptRoot 'PatchOnnxRuntimeLoader.java'

& $javac -encoding UTF-8 -cp $classpath -d $compilerOutput $patcherSource
if ($LASTEXITCODE -ne 0) {
    throw "Failed to compile ORT loader patcher: $LASTEXITCODE"
}

& $java -cp "$compilerOutput;$classpath" PatchOnnxRuntimeLoader `
    $originalJar $outputJar
if ($LASTEXITCODE -ne 0) {
    throw "Failed to generate ORT Java loader: $LASTEXITCODE"
}

$nativeEntries = @(
    & tar -tf $outputJar |
        Where-Object { $_ -match '\.(so|dll|dylib)$|/native/' }
)
if ($nativeEntries.Count -ne 0) {
    throw "Java-only ORT jar unexpectedly contains native files: $($nativeEntries -join ', ')"
}

$outputSha256 = (
    Get-FileHash -LiteralPath $outputJar -Algorithm SHA256
).Hash.ToLowerInvariant()
$outputSize = (Get-Item -LiteralPath $outputJar).Length
Write-Host (
    "Generated Java-only ORT API: $outputJar; " +
    "bytes=$outputSize; sha256=$outputSha256"
)
