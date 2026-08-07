param(
    [string]$SourceDirectory = '',
    [string]$OutputDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
if ([string]::IsNullOrWhiteSpace($SourceDirectory)) {
    $SourceDirectory = Join-Path $repoRoot 'build\spatial-segmentation-poc\edgetam-onnx-export'
}
if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $repoRoot 'build\spatial-segmentation-poc\publish'
}
$archiveName = 'edgetam_boundary_refiner_1.0.0.zip'
$components = @(
    [ordered]@{
        fileName = 'edgetam_image_encoder_1024.onnx'
        sizeBytes = [Int64]19755129
        sha256 = 'b7b39202e8ff7330d89da5a19bde09936b338858d2c4729472b71dd56e6021fe'
    },
    [ordered]@{
        fileName = 'edgetam_box_prompt_encoder.onnx'
        sizeBytes = [Int64]52939
        sha256 = '0bdf0bf63bb3e142fb4180bf4884f9cbde59b8bc79202774febe9864063638a0'
    },
    [ordered]@{
        fileName = 'edgetam_mask_decoder.onnx'
        sizeBytes = [Int64]16384875
        sha256 = 'b2b85965a9e30392d671957cf5bab73acb75f2eecf25a76d2780d8786f5b8208'
    }
)

function Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

Add-Type -AssemblyName System.IO.Compression
New-Item -ItemType Directory -Path $OutputDirectory -Force | Out-Null
$archivePath = Join-Path $OutputDirectory $archiveName
if (Test-Path -LiteralPath $archivePath) {
    Remove-Item -LiteralPath $archivePath
}

foreach ($component in $components) {
    $path = Join-Path $SourceDirectory $component.fileName
    $file = Get-Item -LiteralPath $path
    if ($file.Length -ne $component.sizeBytes -or (Sha256 $path) -ne $component.sha256) {
        throw "EdgeTAM 组件不匹配：$($component.fileName)"
    }
}

$stream = [IO.File]::Open($archivePath, [IO.FileMode]::CreateNew)
try {
    $archive = [IO.Compression.ZipArchive]::new(
        $stream,
        [IO.Compression.ZipArchiveMode]::Create,
        $false
    )
    try {
        foreach ($component in $components) {
            $entry = $archive.CreateEntry(
                $component.fileName,
                [IO.Compression.CompressionLevel]::Optimal
            )
            $entry.LastWriteTime = [DateTimeOffset]::new(
                1980, 1, 1, 0, 0, 0, [TimeSpan]::Zero
            )
            $entry.ExternalAttributes = 0
            $input = [IO.File]::OpenRead((Join-Path $SourceDirectory $component.fileName))
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

$result = [ordered]@{
    fileName = $archiveName
    sizeBytes = [Int64](Get-Item -LiteralPath $archivePath).Length
    sha256 = Sha256 $archivePath
    format = 'zip-onnx-bundle'
    precision = 'fp32'
    components = $components
}
$metadataPath = Join-Path $OutputDirectory 'edgetam-bundle.json'
[IO.File]::WriteAllText(
    $metadataPath,
    (($result | ConvertTo-Json -Depth 5) + "`n"),
    [Text.UTF8Encoding]::new($false)
)
Write-Host ($result | ConvertTo-Json -Depth 5)
