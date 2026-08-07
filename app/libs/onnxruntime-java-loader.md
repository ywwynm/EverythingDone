# ONNX Runtime Java loader

`onnxruntime-java-1.28.0-everythingdone.jar` 来自官方
`com.microsoft.onnxruntime:onnxruntime-android:1.28.0` 的 `classes.jar`，不含任何 native
library。上游 AAR 的固定 SHA-256 为
`f351a0638696f54b35184290dbc001d66daae17281ad0b548d2c70347d53b8a9`。

项目只对 `ai.onnxruntime.OnnxRuntime.load(String)` 做一个受结构校验的字节码补丁：Android
进程若已设置 `onnxruntime.native.path`，则复用上游已有的绝对路径加载分支；否则保持官方
`System.loadLibrary` 行为。补丁由
`tools/spatial-models/prepare-onnxruntime-java-loader.ps1` 可重复生成，脚本会同时核对上游
AAR、原始 `classes.jar`、目标方法结构以及输出中不存在 `.so`。

当前输出 jar 的 SHA-256 为
`a8caeb716273a2bd795cdf1360dba15f71ea51b10191a6f64e0e75ffe3fdc7d2`。

许可见同目录 `onnxruntime-LICENSE.txt`。
