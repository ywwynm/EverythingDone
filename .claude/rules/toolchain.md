# Toolchain paths

## ADB
- `E:\AndroidSDK\platform-tools\adb.exe`
- Not on system `PATH`; must invoke by absolute path.
- A physical device (`BYZL...`) and an emulator (`emulator-5554`) coexist —
  always pass `-s <serial>` to disambiguate.
- Standard invocation pattern (PowerShell):
  ```powershell
  $adb = "E:\AndroidSDK\platform-tools\adb.exe"
  & $adb -s emulator-5554 shell ...
  ```

## Android SDK root
- `E:\AndroidSDK\`

## Gradle wrapper
- `E:\projects\EverythingDone\gradlew.bat`
- Not on `PATH`; must invoke by absolute path or after `cd` into the repo root.
- See [gradle.md](gradle.md) for invocation patterns.

## pip 安装依赖

优先用阿里云镜像，不需要走代理（2026-08-08 用户指定）：

```powershell
& <python.exe> -m pip install <包> -i http://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com
```

仅当镜像缺包或需要直连（如 git+https 源）时才回退 `HTTP_PROXY/HTTPS_PROXY=http://127.0.0.1:7890`。
## InfiniSplat / gsplat：必须经 WSL2 运行（2026-08-08 用户验证）

gsplat 首次渲染要 JIT 编译 CUDA 扩展；本机原生 Windows 无 MSVC（cl.exe 不存在），
官方预编译 wheel 只到 pt24cu124（5090 需 cu128+）——**原生 Windows 此路不通**。
既有可用环境在 WSL2 Ubuntu-24.04：

- venv：`/home/ywwynm/.venvs/everythingdone-infinisplat`（torch 2.9.1+cu128、
  xformers 0.0.33.post2、gsplat 1.5.3）
- CUDA Toolkit：`/home/ywwynm/.local/cuda-12.8`
- 已编译扩展：`/home/ywwynm/.cache/torch_extensions/py312_cu128/gsplat_cuda/gsplat_cuda.so`

标准调用（Windows 侧 PowerShell）：

```powershell
wsl.exe -d Ubuntu-24.04 -- env CUDA_HOME=/home/ywwynm/.local/cuda-12.8 PATH=/home/ywwynm/.venvs/everythingdone-infinisplat/bin:/home/ywwynm/.local/cuda-12.8/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin LD_LIBRARY_PATH=/home/ywwynm/.local/cuda-12.8/lib:/home/ywwynm/.local/cuda-12.8/targets/x86_64-linux/lib MAX_JOBS=1 TORCH_CUDA_ARCH_LIST=12.0 /home/ywwynm/.venvs/everythingdone-infinisplat/bin/python <脚本的 /mnt/e/... 路径> <参数>
```

- Windows 路径换 `/mnt/e/...`；`MAX_JOBS=1` 防并行编译吃爆内存；
- 首次真实调用 rasterizer 可能安静编译几分钟；
- InfiniSplat 仓库/权重/评测脚本都在 `tmp\InfiniSplat-research`、`tmp\InfiniSplat-eval`。

## 桌面 GPU 推理（原生 Windows，2026-08-12 确认）

Python 环境 `spatialtuning`（torch 2.11.0+cu128、diffusers 0.39.0）在 RTX 5090 上直接
可用，D193 实测 Moebius 约 16 秒/视角。此前「本机 torch 2.3.1+cpu、扩散类必须先搭
WSL」的记录是激活了错误环境导致的误判，已作废。仅 gsplat 类需 JIT 编译 CUDA 扩展的
任务仍走 WSL2（见上节）；纯 torch/diffusers 推理直接用 `spatialtuning`。

### ONNX 模型也要上 GPU（Big-LaMa 等，2026-08-12 用户指出后配好）

`spatialtuning` 里原先装的是**纯 CPU 版** `onnxruntime`，`get_available_providers()`
只有 `['AzureExecutionProvider', 'CPUExecutionProvider']`。Big-LaMa 512² 单窗 CPU
1.09 秒、CUDA 0.26 秒（**4.2×**），扫描类任务差别是小时量级。配置两步：

1. **必须装 CUDA 12 那一档**：`onnxruntime-gpu==1.28.0` 的 CUDA EP 要求 **CUDA 13.\***，
   与本机 torch 自带的 12.8 不匹配，建 session 时**静默回落 CPU**（只看跑通与否发现
   不了，只能看单窗耗时）。可用的是 **`onnxruntime-gpu==1.22.0`**：
   ```powershell
   & <python.exe> -m pip uninstall -y onnxruntime
   & <python.exe> -m pip install "onnxruntime-gpu==1.22.0" -i http://mirrors.aliyun.com/pypi/simple/ --trusted-host mirrors.aliyun.com
   ```
   卸载前先停掉所有加载过 onnxruntime 的 python 进程，否则 DLL 被占、卸载留下
   `~nnxruntime` 残目录。
2. **导入前挂上 torch 的 DLL 目录**：`onnxruntime-gpu` 不带 CUDA 运行时，本机的
   CUDA 12.8 + cuDNN 9 是 torch 随包带的。
   ```python
   import os, torch
   os.add_dll_directory(os.path.join(os.path.dirname(torch.__file__), "lib"))
   import onnxruntime as ort      # 这之后才有 CUDAExecutionProvider
   ```
   建完 session 一定要 `sess.get_providers()` 核一遍——回落是静默的。

**跨 EP 的输出不是逐位相同**（Big-LaMa 实测 CPU vs CUDA 平均 0.036 级、最大 6.9 级）。
同一组 A/B 里的所有条目必须跑在**同一个 EP** 上，换了 EP 要整组重跑。