param(
    [ValidateSet('stable', 'staging')]
    [string]$Channel = 'stable',
    [string]$CatalogVersion = (Get-Date).ToUniversalTime().ToString('yyyyMMddHHmmss'),
    [string]$RuntimePackageVersion = '1.28.0-r7'
    ,
    [string]$QnnRuntimePackageVersion = '1.28.0-qnn-r2'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$localPropertiesPath = Join-Path $repoRoot 'local.properties'
$artifactRoot = Join-Path $repoRoot 'build\spatial-model-poc\artifacts'
$sourceRoot = Join-Path $repoRoot 'build\spatial-model-poc'
$outputRoot = Join-Path $repoRoot "build\spatial-model-publish\$Channel"
$runtimeRoot = Join-Path $repoRoot "build\spatial-runtime-publish\$RuntimePackageVersion"
$runtimeMetadataPath = Join-Path $runtimeRoot 'runtime-packages.json'
$qnnRuntimeRoot = Join-Path $repoRoot "build\spatial-qnn-runtime-publish\$QnnRuntimePackageVersion"
$qnnRuntimeMetadataPath = Join-Path $qnnRuntimeRoot 'qnn-runtime-packages.json'
$qnnPrecompiledRoot = Join-Path $repoRoot 'build\spatial-qnn-precompiled-publish'
# 版本必须与 App 里的常量一致，否则设备侧会静默判不兼容：
#   qnnRuntimes 的 packageVersion  ←→ SpatialRuntimeStore.QNN_PACKAGE_VERSION
#   预编译产物的 qairtVersion       ←→ SpatialRuntimeStore.QNN_QAIRT_VERSION
# 这两处此前是手工同步的，2026-08-14 实际漏改过一次：App 升到 r2 而脚本默认还是 r1，
# 发出去的 catalog 里运行组件是 2.42、预编译产物是 2.48，装上就是 error 5000。
$storePath = Join-Path $repoRoot 'app\src\main\java\com\ywwynm\everythingdone\spatial\SpatialRuntimeStore.kt'
$storeText = [IO.File]::ReadAllText($storePath)
if ($storeText -match 'QNN_PACKAGE_VERSION\s*=\s*"([^"]+)"') {
    $appQnnPackage = $matches[1]
    if ($appQnnPackage -ne $QnnRuntimePackageVersion) {
        throw "QNN 运行组件版本不一致：App 要求 $appQnnPackage，本次发布的是 $QnnRuntimePackageVersion"
    }
} else {
    throw '无法从 SpatialRuntimeStore.kt 读出 QNN_PACKAGE_VERSION'
}
if ($storeText -match 'QNN_QAIRT_VERSION\s*=\s*"([^"]+)"') {
    $appQairt = $matches[1]
} else {
    throw '无法从 SpatialRuntimeStore.kt 读出 QNN_QAIRT_VERSION'
}
$keyRoot = 'C:\Users\ywwynm\.everythingdone\spatial-model-signing'

function Read-LocalProperties([string]$Path) {
    if (-not (Test-Path -LiteralPath $Path)) {
        throw "local.properties 不存在：$Path"
    }
    $result = @{}
    foreach ($line in Get-Content -LiteralPath $Path -Encoding UTF8) {
        $trimmed = $line.Trim()
        if (-not $trimmed -or $trimmed.StartsWith('#') -or -not $trimmed.Contains('=')) {
            continue
        }
        $index = $trimmed.IndexOf('=')
        $name = $trimmed.Substring(0, $index).Trim()
        $value = $trimmed.Substring($index + 1).Trim()
        $result[$name] = $value
    }
    return $result
}

function Require-Property([hashtable]$Properties, [string]$Name) {
    $value = $Properties[$Name]
    if ([string]::IsNullOrWhiteSpace($value)) {
        throw "local.properties 缺少 $Name"
    }
    return $value
}

function Invoke-Checked([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "$Executable 执行失败，退出码 $LASTEXITCODE"
    }
}

function Shell-Quote([string]$Value) {
    if ($Value.Contains("'")) {
        throw '远端路径不能包含单引号'
    }
    return "'" + $Value + "'"
}

function Sha256([string]$Path) {
    return (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}

function Write-Utf8NoBom([string]$Path, [string]$Text) {
    [IO.File]::WriteAllText($Path, $Text, [Text.UTF8Encoding]::new($false))
}

$properties = Read-LocalProperties $localPropertiesPath
$hostName = Require-Property $properties 'everythingdone.update.host'
$userName = Require-Property $properties 'everythingdone.update.user'
$remoteRoot = (Require-Property $properties 'everythingdone.update.remoteDir').TrimEnd('/')
$baseUrl = (Require-Property $properties 'everythingdone.update.baseUrl').TrimEnd('/')
$sshKey = $properties['everythingdone.update.sshKey']
$port = $properties['everythingdone.update.port']

$privateKey = Join-Path $keyRoot "$Channel-private.pem"
$publicKey = Join-Path $keyRoot "$Channel-public.der"
if (-not (Test-Path -LiteralPath $privateKey) -or -not (Test-Path -LiteralPath $publicKey)) {
    throw "缺少 $Channel Ed25519 密钥；私钥必须由开发电脑离线保管"
}

$models = @(
    [ordered]@{
        id = 'zipdepth'
        version = '1.0.0'
        localPath = (Join-Path $artifactRoot 'zipdepth_base_npu_384.onnx')
        fileName = 'zipdepth_base_npu_384.onnx'
        expectedSize = [Int64]24591999
        expectedSha256 = '200e7bc60787c14b1fd01a5d26d19cf91fa83d2d41810cb857c7fa27a052988e'
        license = 'MIT'
        licensePath = (Join-Path $sourceRoot 'ZipDepth\LICENSE')
        minDeviceRamMb = 3072
    },
    [ordered]@{
        id = 'depth_anything_v2_small'
        version = '1.0.0'
        localPath = (Join-Path $artifactRoot 'depth_anything_v2_vits_518.onnx')
        fileName = 'depth_anything_v2_vits_518.onnx'
        expectedSize = [Int64]98941040
        expectedSha256 = 'eb022aabc0ef2da038223719207b69053c6996b10833336fdd0b82dbe7bb290e'
        license = 'Apache-2.0'
        licensePath = (Join-Path $sourceRoot 'Depth-Anything-V2\LICENSE')
        minDeviceRamMb = 6144
    },
    [ordered]@{
        # 深度 PoC 2026-08-01 胜出模型；单目分支导出，权重与代码均 Apache-2.0
        #（DA3 系列仅 SMALL 为 Apache-2.0，更大档位 CC BY-NC，不得误发）。
        id = 'depth_anything_3_small'
        version = '1.0.0'
        localPath = (Join-Path $repoRoot 'build\spatial-depth-poc\artifacts\da3_small_mono_518.onnx')
        fileName = 'da3_small_mono_518.onnx'
        expectedSize = [Int64]100479065
        expectedSha256 = '36277e27cabd0c37bc71ac302e885ff48fce4a895943360d75c20d8f033ec420'
        license = 'Apache-2.0'
        licensePath = (Join-Path $repoRoot 'build\spatial-depth-poc\Depth-Anything-3\LICENSE')
        minDeviceRamMb = 6144
    },
    [ordered]@{
        # MoGe-2 ViT-S（微软，MIT）。**唯一给米制深度与相机内参的一档**，是端上做
        # 真透视重投影的前提（D204/D205）。输出契约与其余三个不同，precision 必须显式给。
        id = 'moge_2_vits_normal'
        version = '1.0.0'
        localPath = (Join-Path $repoRoot 'tmp\MoGe-research\moge-2-vits-normal.onnx')
        fileName = 'moge-2-vits-normal.onnx'
        expectedSize = [Int64]140852051
        expectedSha256 = '24eacb5dc7a2c54c7bc98f7de085ffbed79ad006ea5b664c2c2cdc02ff3a52f0'
        precision = 'fp32-moge-pointmap'
        license = 'MIT'
        licensePath = (Join-Path $repoRoot 'tmp\MoGe-research\LICENSE')
        minDeviceRamMb = 6144
    },
    [ordered]@{
        # MoGe-2 ViT-B（微软，MIT）。与 ViT-S **同一套官方导出脚本、同一套输出契约**，
        # 只是换权重，所以 precision 与 license 都跟 ViT-S 一致（D216 裁定的质量档）。
        # 门槛 8192 必须与 SpatialDepthModel.MOGE_2_VITB_NORMAL 的 minimumTotalRamMb
        # 保持一致——两处是手工同步的，改一处忘另一处会让设备侧与目录侧判断打架。
        id = 'moge_2_vitb_normal'
        version = '1.0.0'
        localPath = (Join-Path $repoRoot 'tmp\MoGe-research\moge-2-vitb-normal.onnx')
        fileName = 'moge-2-vitb-normal.onnx'
        expectedSize = [Int64]419411850
        expectedSha256 = 'bbf14e07a30f11e69d36ab861590123f5598ababcbc8946a063eb4a966f35a21'
        precision = 'fp32-moge-pointmap'
        license = 'MIT'
        licensePath = (Join-Path $repoRoot 'tmp\MoGe-research\LICENSE')
        minDeviceRamMb = 8192
    }
)

$inpaintingModels = @(
    [ordered]@{
        id = 'migan_places2_512_pipeline'
        version = '2.0.0'
        localPath = (Join-Path $repoRoot 'build\spatial-ldi-lite-poc\migan_pipeline_v2.onnx')
        fileName = 'migan_pipeline_v2.onnx'
        expectedSize = [Int64]28079181
        expectedSha256 = '6f1f3530a1a2324b19752018ce756088b07973cda8d7d890034ace5c8a48c40b'
        precision = 'uint8-pipeline'
        license = 'MIT'
        licensePath = (Join-Path $sourceRoot 'MI-GAN\LICENSE')
        minDeviceRamMb = 4096
    },
    [ordered]@{
        id = 'aotgan_places2_512'
        version = '1.0.0'
        localPath = (Join-Path $artifactRoot 'aotgan_places2_512.onnx')
        fileName = 'aotgan_places2_512.onnx'
        expectedSize = [Int64]60989366
        expectedSha256 = '6b255797029da17f60ef1e8860c6a6ccad13a0de4f97ab877a69f937946388e4'
        precision = 'float32-aotgan-rgb-mask'
        license = 'Apache-2.0'
        licensePath = (Join-Path $sourceRoot 'AOT-GAN-for-Inpainting\LICENSE')
        minDeviceRamMb = 6144
    },
    [ordered]@{
        # Big-LaMa（LaMa，WACV 2022，Apache-2.0）。用户 2026-08-12 逐档目检后裁定它主观
        # 优于 MI-GAN，遂移植上端。空间维在导出时写死 512，端上按 512 原生分块推理
        # （SpatialInpaintingTiling），与桌面 inpaint_onnx_tiled 同规格。
        # 198 MiB 是三者中最大的，因此 minDeviceRamMb 抬到 8192。
        id = 'big_lama_places2_512'
        version = '1.0.0'
        localPath = (Join-Path $artifactRoot 'big_lama_places2_512_fp32.onnx')
        fileName = 'big_lama_places2_512_fp32.onnx'
        expectedSize = [Int64]208044816
        expectedSha256 = '1faef5301d78db7dda502fe59966957ec4b79dd64e16f03ed96913c7a4eb68d6'
        precision = 'float32-lama-rgb-mask-512'
        license = 'Apache-2.0'
        licensePath = (Join-Path $sourceRoot 'carve-lama\LICENSE')
        minDeviceRamMb = 8192
    }
)

$mattingModels = @(
    [ordered]@{
        # matting PoC 两轮选型（发丝 alpha 过渡 5/8.2px、26MB、0.05s）；上游 MODNet
        # Apache-2.0，ONNX 取自 Xenova/modnet 转换。opset 11，需 Runtime r4+。
        id = 'modnet_photographic'
        version = '1.0.0'
        localPath = (Join-Path $repoRoot 'build\spatial-matting-poc\modnet_photographic.onnx')
        fileName = 'modnet_photographic.onnx'
        expectedSize = [Int64]25888640
        expectedSha256 = '07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9'
        license = 'Apache-2.0'
        licensePath = (Join-Path $repoRoot 'build\spatial-matting-poc\MODNET-LICENSE')
        minDeviceRamMb = 4096
    }
)

$segmentationModels = @(
    [ordered]@{
        # RF-DETR Seg Nano（ICLR 2026），官方 Apache-2.0 权重与导出链。
        # 固定 312x312 ONNX ABI；只作为可选 ownership provider，不取代深度表面。
        id = 'rf_detr_seg_nano'
        version = '1.0.0'
        localPath = (Join-Path $repoRoot 'build\spatial-segmentation-poc\export\rfdetr_seg_nano_312.onnx')
        fileName = 'rfdetr_seg_nano_312.onnx'
        expectedSize = [Int64]122831761
        expectedSha256 = 'e126db3d03364ddad43299cdb354e0e85a12719a695e1ded3f271012b0d4fa97'
        license = 'Apache-2.0'
        licensePath = (Join-Path $repoRoot 'build\spatial-segmentation-poc\rf-detr\LICENSE')
        minDeviceRamMb = 6144
    }
)

$boundaryRefinementModels = @(
    [ordered]@{
        # EdgeTAM（CVPR 2025）三图 bundle；只接受 RF-DETR box prompt 并细化轮廓窄带。
        id = 'edgetam_boundary_refiner'
        version = '1.0.0'
        localPath = (Join-Path $repoRoot 'build\spatial-segmentation-poc\publish\edgetam_boundary_refiner_1.0.0.zip')
        fileName = 'edgetam_boundary_refiner_1.0.0.zip'
        expectedSize = [Int64]33502118
        expectedSha256 = '289cea7ff30df047f432ef9fd2c99a4554a3057a2ed28df03ff73f0aeeeef09d'
        format = 'zip-onnx-bundle'
        license = 'Apache-2.0'
        licensePath = (Join-Path $repoRoot 'build\spatial-segmentation-poc\EdgeTAM\LICENSE')
        minDeviceRamMb = 8192
    }
)

foreach ($model in $models + $mattingModels + $segmentationModels + $boundaryRefinementModels) {
    $file = Get-Item -LiteralPath $model.localPath
    if ($file.Length -ne $model.expectedSize) {
        throw "$($model.id) 字节数不符：$($file.Length)"
    }
    $hash = Sha256 $file.FullName
    if ($hash -ne $model.expectedSha256) {
        throw "$($model.id) SHA-256 不符：$hash"
    }
    if (-not (Test-Path -LiteralPath $model.licensePath)) {
        throw "$($model.id) 缺少许可文件"
    }
}
foreach ($model in $inpaintingModels) {
    $file = Get-Item -LiteralPath $model.localPath
    if ($file.Length -ne $model.expectedSize) {
        throw "$($model.id) 字节数不符：$($file.Length)"
    }
    $hash = Sha256 $file.FullName
    if ($hash -ne $model.expectedSha256) {
        throw "$($model.id) SHA-256 不符：$hash"
    }
    if (-not (Test-Path -LiteralPath $model.licensePath)) {
        throw "$($model.id) 缺少许可文件"
    }
}

New-Item -ItemType Directory -Path $outputRoot -Force | Out-Null

$sshArgs = @()
$scpArgs = @()
if (-not [string]::IsNullOrWhiteSpace($sshKey)) {
    $sshArgs += @('-i', $sshKey)
    $scpArgs += @('-i', $sshKey)
}
if (-not [string]::IsNullOrWhiteSpace($port)) {
    $sshArgs += @('-p', $port)
    $scpArgs += @('-P', $port)
}
$remote = "$userName@$hostName"

function Publish-ImmutableObject(
    [string]$LocalPath,
    [string]$RemotePath,
    [Int64]$ExpectedSize,
    [string]$ExpectedSha256
) {
    $remoteDirectory = $RemotePath.Substring(0, $RemotePath.LastIndexOf('/'))
    $temporary = "$RemotePath.uploading"
    $verifyExisting = 'if [ ! -e {0} ]; then exit 3; fi; ' +
        'test "$(stat -c %s {0})" = {1}; echo "{2}  {0}" | sha256sum -c -'
    $verifyExisting = $verifyExisting -f (
        Shell-Quote $RemotePath
    ), $ExpectedSize, $ExpectedSha256
    & ssh @sshArgs $remote $verifyExisting
    if ($LASTEXITCODE -eq 0) {
        return
    }
    if ($LASTEXITCODE -ne 3) {
        throw "远端不可变对象存在但校验失败：$RemotePath"
    }

    Invoke-Checked 'ssh' ($sshArgs + @(
        $remote,
        ('set -e; mkdir -p {0}' -f (Shell-Quote $remoteDirectory))
    ))
    Invoke-Checked 'scp' ($scpArgs + @($LocalPath, "${remote}:$temporary"))

    $command = 'set -e; test "$(stat -c %s {0})" = {1}; echo "{2}  {3}" | sha256sum -c -; ' +
        'if [ -e {4} ]; then echo "{2}  {4}" | sha256sum -c -; rm -f {3}; else mv {3} {4}; fi'
    $command = $command -f (
        Shell-Quote $temporary
    ), $ExpectedSize, $ExpectedSha256, (
        Shell-Quote $temporary
    ), (
        Shell-Quote $RemotePath
    )
    Invoke-Checked 'ssh' ($sshArgs + @($remote, $command))
}

if (-not (Test-Path -LiteralPath $runtimeMetadataPath)) {
    throw "缺少运行组件元数据：$runtimeMetadataPath"
}
$runtimeMetadata = Get-Content -LiteralPath $runtimeMetadataPath -Raw -Encoding UTF8 |
    ConvertFrom-Json
if ($runtimeMetadata.schemaVersion -ne 1 -or
    $runtimeMetadata.packageVersion -ne $RuntimePackageVersion) {
    throw '运行组件元数据版本不符'
}
$runtimePackages = @($runtimeMetadata.runtimes)
$expectedAbis = @('arm64-v8a', 'armeabi-v7a', 'x86', 'x86_64')
$actualAbis = @($runtimePackages.abi | Sort-Object)
if ($runtimePackages.Count -ne $expectedAbis.Count -or
    ($actualAbis -join ',') -ne (($expectedAbis | Sort-Object) -join ',')) {
    throw '运行组件元数据必须准确包含四套受支持 ABI'
}
$runtimeLicense = Join-Path $runtimeRoot 'LICENSE.txt'
if (-not (Test-Path -LiteralPath $runtimeLicense)) {
    throw '缺少 ONNX Runtime MIT 许可文件'
}

$catalogRuntimes = @()
foreach ($runtime in $runtimePackages) {
    $localPath = Join-Path $runtimeRoot $runtime.fileName
    $file = Get-Item -LiteralPath $localPath
    if ($file.Length -ne [Int64]$runtime.sizeBytes -or
        (Sha256 $file.FullName) -ne $runtime.sha256) {
        throw "$($runtime.abi) 运行组件包校验失败"
    }
    if ([Int64]$runtime.unpackedSizeBytes -ne
        [Int64]$runtime.coreSizeBytes + [Int64]$runtime.jniSizeBytes) {
        throw "$($runtime.abi) 运行组件解包字节数不一致"
    }

    $relativeDirectory =
        "models/spatial-depth/runtime/onnxruntime/$RuntimePackageVersion/$($runtime.abi)"
    Publish-ImmutableObject `
        -LocalPath $file.FullName `
        -RemotePath "$remoteRoot/$relativeDirectory/$($runtime.fileName)" `
        -ExpectedSize $file.Length `
        -ExpectedSha256 $runtime.sha256

    $catalogRuntimes += [ordered]@{
        id = 'onnxruntime'
        packageVersion = $RuntimePackageVersion
        ortVersion = '1.28.0'
        runtimeApiVersion = 1
        abi = $runtime.abi
        url = "$baseUrl/$relativeDirectory/$($runtime.fileName)"
        sizeBytes = [Int64]$runtime.sizeBytes
        sha256 = $runtime.sha256
        unpackedSizeBytes = [Int64]$runtime.unpackedSizeBytes
        coreSizeBytes = [Int64]$runtime.coreSizeBytes
        coreSha256 = $runtime.coreSha256
        jniSizeBytes = [Int64]$runtime.jniSizeBytes
        jniSha256 = $runtime.jniSha256
        license = 'MIT'
        enabled = $true
        disabledReason = $null
    }
}

$runtimeLicenseHash = Sha256 $runtimeLicense
$runtimeLicenseSize = (Get-Item -LiteralPath $runtimeLicense).Length
Publish-ImmutableObject `
    -LocalPath $runtimeLicense `
    -RemotePath "$remoteRoot/models/spatial-depth/runtime/onnxruntime/$RuntimePackageVersion/LICENSE.txt" `
    -ExpectedSize $runtimeLicenseSize `
    -ExpectedSha256 $runtimeLicenseHash

# ---------------------------------------------------------------- QNN 运行组件
# **不能并进 $catalogRuntimes**：那一组每条都要过 SpatialRuntimeCatalogEntry.isCompatible，
# 里面硬校验 packageVersion == REQUIRED_PACKAGE_VERSION，混入 QNN 条目会让所有已安装的
# 旧版 App 直接拒绝整个 catalog。所以走独立的 qnnRuntimes 字段，旧版忽略。
$catalogQnnRuntimes = @()
if (Test-Path -LiteralPath $qnnRuntimeMetadataPath) {
    $qnnMetadata = Get-Content -LiteralPath $qnnRuntimeMetadataPath -Raw -Encoding UTF8 |
        ConvertFrom-Json
    if ($qnnMetadata.packageVersion -ne $QnnRuntimePackageVersion) {
        throw 'QNN 运行组件元数据版本不符'
    }
    $qnnLicense = Join-Path $qnnRuntimeRoot 'LICENSE.txt'
    if (-not (Test-Path -LiteralPath $qnnLicense)) {
        throw '缺少 QAIRT 许可文件（QNN 库不是 MIT，必须随包下发）'
    }
    $qnnAbi = $qnnMetadata.abi
    foreach ($pkg in @($qnnMetadata.packages)) {
        $localPath = Join-Path $qnnRuntimeRoot $pkg.fileName
        $file = Get-Item -LiteralPath $localPath
        if ($file.Length -ne [Int64]$pkg.sizeBytes -or (Sha256 $file.FullName) -ne $pkg.sha256) {
            throw "$($pkg.dspArch) QNN 运行组件包校验失败"
        }
        $extraSum = [Int64]0
        foreach ($extra in @($pkg.extraFiles)) { $extraSum += [Int64]$extra.sizeBytes }
        $declared = [Int64]$pkg.coreSizeBytes + [Int64]$pkg.jniSizeBytes + $extraSum
        if ([Int64]$pkg.unpackedSizeBytes -ne $declared) {
            throw "$($pkg.dspArch) QNN 运行组件解包字节数不一致"
        }

        $relativeDirectory =
            "models/spatial-depth/qnn-runtime/onnxruntime/$QnnRuntimePackageVersion/$qnnAbi/$($pkg.dspArch)"
        Publish-ImmutableObject `
            -LocalPath $file.FullName `
            -RemotePath "$remoteRoot/$relativeDirectory/$($pkg.fileName)" `
            -ExpectedSize $file.Length `
            -ExpectedSha256 $pkg.sha256

        $extraEntries = @()
        foreach ($extra in @($pkg.extraFiles)) {
            $extraEntries += [ordered]@{
                name = $extra.name
                sizeBytes = [Int64]$extra.sizeBytes
                sha256 = $extra.sha256
            }
        }
        $catalogQnnRuntimes += [ordered]@{
            id = 'onnxruntime'
            packageVersion = $QnnRuntimePackageVersion
            ortVersion = '1.28.0'
            runtimeApiVersion = 1
            abi = $qnnAbi
            dspArch = $pkg.dspArch
            url = "$baseUrl/$relativeDirectory/$($pkg.fileName)"
            sizeBytes = [Int64]$pkg.sizeBytes
            sha256 = $pkg.sha256
            unpackedSizeBytes = [Int64]$pkg.unpackedSizeBytes
            coreSizeBytes = [Int64]$pkg.coreSizeBytes
            coreSha256 = $pkg.coreSha256
            jniSizeBytes = [Int64]$pkg.jniSizeBytes
            jniSha256 = $pkg.jniSha256
            extraFiles = $extraEntries
            license = 'Qualcomm-AI-Engine-Direct'
            enabled = $true
            disabledReason = $null
        }
    }
    Publish-ImmutableObject `
        -LocalPath $qnnLicense `
        -RemotePath "$remoteRoot/models/spatial-depth/qnn-runtime/onnxruntime/$QnnRuntimePackageVersion/LICENSE.txt" `
        -ExpectedSize (Get-Item -LiteralPath $qnnLicense).Length `
        -ExpectedSha256 (Sha256 $qnnLicense)
    Write-Output ("已准备 " + $catalogQnnRuntimes.Count + " 个 QNN 运行组件条目")
} else {
    Write-Output 'QNN 运行组件元数据不存在，跳过（catalog 不含 qnnRuntimes）'
}

# ------------------------------------------------------- NPU 预编译 context 产物
# 又一个独立字段（理由同 qnnRuntimes）：模型自己那几组的 isCompatible() 各有硬校验，
# 混入会让旧版 App 拒绝整个 catalog。
# qairtVersion 必须与 qnnRuntimes 打包的 QAIRT 一致，否则设备侧建 session 会报
# error 5000（D252）；App 端由 SpatialRuntimeStore.QNN_QAIRT_VERSION 兜底判定。
$catalogQnnPrecompiled = @()
if (Test-Path -LiteralPath $qnnPrecompiledRoot) {
    foreach ($modelDir in Get-ChildItem -LiteralPath $qnnPrecompiledRoot -Directory) {
        $metaPath = Join-Path $modelDir.FullName 'qnn-precompiled-packages.json'
        if (-not (Test-Path -LiteralPath $metaPath)) { continue }
        $meta = Get-Content -LiteralPath $metaPath -Raw -Encoding UTF8 | ConvertFrom-Json
        foreach ($pkg in @($meta.packages)) {
            $localPath = Join-Path $modelDir.FullName $pkg.fileName
            $file = Get-Item -LiteralPath $localPath
            if ($file.Length -ne [Int64]$pkg.sizeBytes -or (Sha256 $file.FullName) -ne $pkg.sha256) {
                throw "$($meta.modelId)/$($pkg.dspArch) 预编译包校验失败"
            }
            $declared = [Int64]$pkg.contextModelSizeBytes + [Int64]$pkg.contextBinarySizeBytes
            if ([Int64]$pkg.unpackedSizeBytes -ne $declared) {
                throw "$($meta.modelId)/$($pkg.dspArch) 预编译包解包字节数不一致"
            }

            $relativeDirectory =
                "models/spatial-depth/qnn-precompiled/$($meta.modelId)/$($meta.modelVersion)/$($pkg.dspArch)"
            Publish-ImmutableObject `
                -LocalPath $file.FullName `
                -RemotePath "$remoteRoot/$relativeDirectory/$($pkg.fileName)" `
                -ExpectedSize $file.Length `
                -ExpectedSha256 $pkg.sha256

            if ($meta.qairtVersion -ne $appQairt) {
                throw "预编译产物 QAIRT 版本不一致：App 要求 $appQairt，产物是 $($meta.qairtVersion)"
            }
            $catalogQnnPrecompiled += [ordered]@{
                modelId = $meta.modelId
                modelVersion = $meta.modelVersion
                dspArch = $pkg.dspArch
                qairtVersion = $meta.qairtVersion
                url = "$baseUrl/$relativeDirectory/$($pkg.fileName)"
                sizeBytes = [Int64]$pkg.sizeBytes
                sha256 = $pkg.sha256
                unpackedSizeBytes = [Int64]$pkg.unpackedSizeBytes
                contextModelName = $pkg.contextModelName
                contextModelSizeBytes = [Int64]$pkg.contextModelSizeBytes
                contextModelSha256 = $pkg.contextModelSha256
                contextBinaryName = $pkg.contextBinaryName
                contextBinarySizeBytes = [Int64]$pkg.contextBinarySizeBytes
                contextBinarySha256 = $pkg.contextBinarySha256
                license = $meta.license
                enabled = $true
                disabledReason = $null
            }
        }
    }
    Write-Output ("已准备 " + $catalogQnnPrecompiled.Count + " 个 NPU 预编译条目")
} else {
    Write-Output 'NPU 预编译目录不存在，跳过'
}

$catalogModels = @()
foreach ($model in $models) {
    $relativeDirectory = "models/spatial-depth/objects/$($model.id)/$($model.version)"
    $remoteDirectory = "$remoteRoot/$relativeDirectory"
    $remoteModel = "$remoteDirectory/$($model.fileName)"
    Publish-ImmutableObject `
        -LocalPath $model.localPath `
        -RemotePath $remoteModel `
        -ExpectedSize $model.expectedSize `
        -ExpectedSha256 $model.expectedSha256

    $licenseHash = Sha256 $model.licensePath
    $licenseSize = (Get-Item -LiteralPath $model.licensePath).Length
    Publish-ImmutableObject `
        -LocalPath $model.licensePath `
        -RemotePath "$remoteDirectory/LICENSE.txt" `
        -ExpectedSize $licenseSize `
        -ExpectedSha256 $licenseHash

    $catalogModels += [ordered]@{
        id = $model.id
        version = $model.version
        url = "$baseUrl/$relativeDirectory/$($model.fileName)"
        sizeBytes = $model.expectedSize
        sha256 = $model.expectedSha256
        format = 'onnx'
        # ABI 标识：单图模型是 fp32，MoGe-2 是 point map 契约（App 侧按
        # SpatialDepthOutputContract.catalogPrecision 校验，写死 fp32 会被拒）
        precision = $(if ($model.Contains('precision')) { $model.precision } else { 'fp32' })
        license = $model.license
        minDeviceRamMb = $model.minDeviceRamMb
        enabled = $true
        disabledReason = $null
    }
}

$catalogInpaintingModels = @()
foreach ($model in $inpaintingModels) {
    $relativeDirectory = "models/spatial-depth/objects/$($model.id)/$($model.version)"
    $remoteDirectory = "$remoteRoot/$relativeDirectory"
    Publish-ImmutableObject `
        -LocalPath $model.localPath `
        -RemotePath "$remoteDirectory/$($model.fileName)" `
        -ExpectedSize $model.expectedSize `
        -ExpectedSha256 $model.expectedSha256

    $licenseHash = Sha256 $model.licensePath
    $licenseSize = (Get-Item -LiteralPath $model.licensePath).Length
    Publish-ImmutableObject `
        -LocalPath $model.licensePath `
        -RemotePath "$remoteDirectory/LICENSE.txt" `
        -ExpectedSize $licenseSize `
        -ExpectedSha256 $licenseHash

    $catalogInpaintingModels += [ordered]@{
        id = $model.id
        version = $model.version
        url = "$baseUrl/$relativeDirectory/$($model.fileName)"
        sizeBytes = $model.expectedSize
        sha256 = $model.expectedSha256
        format = 'onnx'
        precision = $model.precision
        license = $model.license
        minDeviceRamMb = $model.minDeviceRamMb
        enabled = $true
        disabledReason = $null
    }
}

$catalogMattingModels = @()
foreach ($model in $mattingModels) {
    $relativeDirectory = "models/spatial-depth/objects/$($model.id)/$($model.version)"
    $remoteDirectory = "$remoteRoot/$relativeDirectory"
    Publish-ImmutableObject `
        -LocalPath $model.localPath `
        -RemotePath "$remoteDirectory/$($model.fileName)" `
        -ExpectedSize $model.expectedSize `
        -ExpectedSha256 $model.expectedSha256
    $licenseHash = Sha256 $model.licensePath
    $licenseSize = (Get-Item -LiteralPath $model.licensePath).Length
    Publish-ImmutableObject `
        -LocalPath $model.licensePath `
        -RemotePath "$remoteDirectory/LICENSE.txt" `
        -ExpectedSize $licenseSize `
        -ExpectedSha256 $licenseHash
    $catalogMattingModels += [ordered]@{
        id = $model.id
        version = $model.version
        url = "$baseUrl/$relativeDirectory/$($model.fileName)"
        sizeBytes = $model.expectedSize
        sha256 = $model.expectedSha256
        format = 'onnx'
        precision = 'fp32'
        license = $model.license
        minDeviceRamMb = $model.minDeviceRamMb
        enabled = $true
        disabledReason = $null
    }
}

$catalogSegmentationModels = @()
foreach ($model in $segmentationModels) {
    $relativeDirectory = "models/spatial-depth/objects/$($model.id)/$($model.version)"
    $remoteDirectory = "$remoteRoot/$relativeDirectory"
    Publish-ImmutableObject `
        -LocalPath $model.localPath `
        -RemotePath "$remoteDirectory/$($model.fileName)" `
        -ExpectedSize $model.expectedSize `
        -ExpectedSha256 $model.expectedSha256
    $licenseHash = Sha256 $model.licensePath
    $licenseSize = (Get-Item -LiteralPath $model.licensePath).Length
    Publish-ImmutableObject `
        -LocalPath $model.licensePath `
        -RemotePath "$remoteDirectory/LICENSE.txt" `
        -ExpectedSize $licenseSize `
        -ExpectedSha256 $licenseHash
    $catalogSegmentationModels += [ordered]@{
        id = $model.id
        version = $model.version
        url = "$baseUrl/$relativeDirectory/$($model.fileName)"
        sizeBytes = $model.expectedSize
        sha256 = $model.expectedSha256
        format = 'onnx'
        precision = 'fp32'
        license = $model.license
        minDeviceRamMb = $model.minDeviceRamMb
        enabled = $true
        disabledReason = $null
    }
}

$catalogBoundaryRefinementModels = @()
foreach ($model in $boundaryRefinementModels) {
    $relativeDirectory = "models/spatial-depth/objects/$($model.id)/$($model.version)"
    $remoteDirectory = "$remoteRoot/$relativeDirectory"
    Publish-ImmutableObject `
        -LocalPath $model.localPath `
        -RemotePath "$remoteDirectory/$($model.fileName)" `
        -ExpectedSize $model.expectedSize `
        -ExpectedSha256 $model.expectedSha256
    $licenseHash = Sha256 $model.licensePath
    $licenseSize = (Get-Item -LiteralPath $model.licensePath).Length
    Publish-ImmutableObject `
        -LocalPath $model.licensePath `
        -RemotePath "$remoteDirectory/LICENSE.txt" `
        -ExpectedSize $licenseSize `
        -ExpectedSha256 $licenseHash
    $catalogBoundaryRefinementModels += [ordered]@{
        id = $model.id
        version = $model.version
        url = "$baseUrl/$relativeDirectory/$($model.fileName)"
        sizeBytes = $model.expectedSize
        sha256 = $model.expectedSha256
        format = $model.format
        precision = 'fp32'
        license = $model.license
        minDeviceRamMb = $model.minDeviceRamMb
        enabled = $true
        disabledReason = $null
    }
}

$payload = [ordered]@{
    schemaVersion = 1
    channel = $Channel
    catalogVersion = [Int64]$CatalogVersion
    publishedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    runtimeVersion = 1
    models = $catalogModels
    # 旧 App 的 Gson 忽略未知字段；mattingModels 仅被含 matting 的新构建校验与使用。
    mattingModels = $catalogMattingModels
    segmentationModels = $catalogSegmentationModels
    boundaryRefinementModels = $catalogBoundaryRefinementModels
    # 旧版 App 会拒绝它不认识的补图 ABI，因此 MI-GAN 留在原字段，
    # 新增模型进入旧 Gson 可安全忽略的 schema 1 扩展字段。
    inpaintingModels = @(
        $catalogInpaintingModels |
            Where-Object { $_.id -eq 'migan_places2_512_pipeline' }
    )
    additionalInpaintingModels = @(
        $catalogInpaintingModels |
            Where-Object { $_.id -ne 'migan_places2_512_pipeline' }
    )
    runtimes = $catalogRuntimes
    qnnRuntimes = $catalogQnnRuntimes
    qnnPrecompiledModels = $catalogQnnPrecompiled
}
$payloadPath = Join-Path $outputRoot 'catalog-payload.json'
$payloadJson = $payload | ConvertTo-Json -Depth 8
Write-Utf8NoBom $payloadPath ($payloadJson + "`n")

$signaturePath = Join-Path $outputRoot 'catalog-signature.bin'
Invoke-Checked 'openssl' @(
    'pkeyutl', '-sign', '-rawin',
    '-inkey', $privateKey,
    '-in', $payloadPath,
    '-out', $signaturePath
)
Invoke-Checked 'openssl' @(
    'pkeyutl', '-verify', '-rawin', '-pubin',
    '-inkey', $publicKey, '-keyform', 'DER',
    '-in', $payloadPath,
    '-sigfile', $signaturePath
)

$envelope = [ordered]@{
    schemaVersion = 1
    keyId = "$Channel-2026-01"
    payloadBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($payloadPath))
    signatureBase64 = [Convert]::ToBase64String([IO.File]::ReadAllBytes($signaturePath))
}
$envelopePath = Join-Path $outputRoot 'catalog.json'
Write-Utf8NoBom $envelopePath (($envelope | ConvertTo-Json -Depth 4) + "`n")

$remoteCatalogDirectory = "$remoteRoot/models/spatial-depth/$Channel"
$remoteCatalog = "$remoteCatalogDirectory/catalog.json"
$remoteCatalogTemporary = "$remoteCatalog.tmp"
Invoke-Checked 'ssh' ($sshArgs + @(
    $remote,
    ('set -e; mkdir -p {0}' -f (Shell-Quote $remoteCatalogDirectory))
))
Invoke-Checked 'scp' ($scpArgs + @($envelopePath, "${remote}:$remoteCatalogTemporary"))
Invoke-Checked 'ssh' ($sshArgs + @(
    $remote,
    ('set -e; mv {0} {1}' -f (
        Shell-Quote $remoteCatalogTemporary
    ), (
        Shell-Quote $remoteCatalog
    ))
))

Write-Host "已发布 $Channel 空间模型 catalog：$baseUrl/models/spatial-depth/$Channel/catalog.json"
