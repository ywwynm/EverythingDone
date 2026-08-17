# 录音来源切换后 FableSol 暂不响应倾斜：真机根因分析

日期：2026-08-17
设备：OPD2515，Android 16 / ColorOS `OPD2515_16.0.9.401(CN01)`
设备序列号：`9018f404`

## 最终结论

根因不是 FableSol、EGL/Surface、音频重配或 6 秒静音提示，而是 ColorOS 的“设备动作与方向”开屏保护。

该设备把“完事儿”的 `DIRECTION_SENSORS` AppOp 保持为 `default`，在权限界面对应“仅开屏时不允许”。ColorOS 的说明原文为：

> 应用启动时 6 秒内将无法通过摇一摇跳转，此选项浮窗和分屏模式下不生效

应用从 MediaProjection 系统授权页或其他全屏 Activity 返回前台时，ColorOS 会把它重新置于 application launch stage。native `SensorService` 随后在约 6 秒内过滤重力/加速度等方向传感器事件，并记录：

```text
SensorInterceptByDirectionOp ... type=android.sensor.gravity, stage=2
```

因此这段时间内应用根本收不到新的姿态样本。FableSol 渲染线程仍在连续绘制，只能维持最后一个姿态，看起来像“动画无法响应倾斜”。门禁结束后的第一个样本立即进入 FableSol，画面突然追到当前姿态，于是表现为恢复时卡一下。

## 决定性证据

### 1. GL 渲染从未停止

复现窗口内 GL 帧摘要持续出现在 `14:36:48.141`、`49.143`、`50.139`、`51.710`、`53.713`、`55.703`。`eglSwapBuffers()` 的 p50 约为 0.27～0.51 ms，没有 EGL failure，也没有数秒 swap 阻塞。

这排除了 Surface 重建、帧循环失活和音频批次积压作为本次问题的原因。代码中另有 transient `EGL_BAD_SURFACE` 后恢复状态不完整的潜在缺陷，但本次实机复现没有走到该分支，应与本问题分开处理。

### 2. MediaProjection 返回后，首个重力事件恰好延迟约 6 秒

- `14:43:11.506`～`11.514`：应用重新注册 QTI gravity sensor，注册成功；
- `14:43:11.589`：系统音频配置完成；
- `14:43:17.513`：首个 `SensorEventListener` 回调，距注册约 6.0 秒；
- `14:43:17.523`：FableSol 在 10 ms 后消费该样本；
- `14:43:17.714`：静音提示引起的 `surfaceChanged` 才发生，比首个样本晚约 200 ms。

所以提示文本和 Surface 变化不是恢复触发点；恢复发生在它们之前。

### 3. 与录音和 MediaProjection 无关的 Activity 往返也能复现

在来源为“麦克风”且不改变任何录音配置时，仅打开 Android 设置再返回：

- `14:45:13.297`：方向传感器重新注册成功；
- `14:45:13.307`：ColorOS 记录 `SensorInterceptByDirectionOp ... stage=2`；
- `14:45:19.334`：首个传感器回调，延迟约 6.04 秒。

这证明共同触发条件是“从另一个全屏 Activity 回到应用”，不是音频来源切换。

### 4. 更换传感器和保留监听器都不能绕过

- 强制从 `TYPE_GRAVITY` 改用 `TYPE_ACCELEROMETER`：普通初次注册 16 ms 即有回调；但从 MediaProjection 授权页返回后，ColorOS 同样记录 `android.sensor.accelerometer, stage=2`，首个回调仍延迟约 6 秒。
- 在 `onPause()` 不注销监听器、让同一 sensor connection 跨授权页存活：返回时 ColorOS 仍对已有连接执行 stage 2 过滤，事件仍中断约 6 秒。

因此“换成加速度计”“提前预热”“不注销监听器”均不是解决方案。

### 5. 更改 ColorOS 权限是唯一控制变量

通过系统界面把“设备动作与方向”从“仅开屏时不允许”临时改为“允许”后，`DIRECTION_SENSORS` 从 `default` 变为 `allow`。再次打开 Android 设置并返回：

- `14:59:03.968`：ColorOS 仍上报 application launch `stage=2`；
- `14:59:04.009`：FableSol 已消费新的倾斜样本，仅相隔约 41 ms；
- 全程没有 `SensorInterceptByDirectionOp`。

随后已把权限恢复为“仅开屏时不允许”，AppOp 也恢复为 `default`。

## 两类用户现象如何对应同一根因

### 首次切换来源

- 从“麦克风”切到“系统/麦克风+系统”需要打开 MediaProjection 授权页；返回后触发 6 秒方向传感器开屏保护。
- Dialog 初始来源就是系统类来源时，也会先经过授权页。若紧接着切回“麦克风”，看到的停顿是前一次授权返回所遗留的 6 秒门禁，不是“系统 → 麦克风”这次切换造成的。
- 在门禁已经结束后直接执行“系统 → 麦克风”，诊断样本序列保持连续，未出现新的停顿。

这也解释了为什么没有静音提示的方向仍会自行恢复。

### 重新录音

录音结束后，MediaProjection 授权可能已经失效。`restartRecording()` 不能复用授权时会再次调用 `requestMediaProjection()`，重新经历系统 Activity 往返，也就再次触发同一个 6 秒门禁。若随后马上切换来源，用户会把仍在生效的门禁感知为“切换来源导致失灵”。

## 代码层面的边界

应用的重力路径本身没有来源门控：

1. `AudioRecordDialogFragment` 的 sensor listener 收到样本后调用 `dispatchGravityToVisualizer()`；
2. 它先调用 FableSol 的 `setContainerGravity()`，再把样本交给录音服务；
3. `FableSolGlRenderer` 每帧消费 latest-value gravity inbox，和音频分析通道相互独立；
4. 6 秒系统静音检测只更新提示状态，不参与上述路径。

问题发生在第 1 步之前：ColorOS native `SensorService` 没有把事件送进应用进程。

## 解决方案

### 立即且彻底的设备侧方案

在 ColorOS 中进入：

`设置 → 应用 → 应用管理 → 完事儿 → 权限管理 → 设备动作与方向 → 允许`

该设置会把 `DIRECTION_SENSORS` 设为 `allow`，实测可消除从授权页或其他 Activity 返回后的 6 秒停顿。

应用不能自行静默修改这一项。ColorOS 相关内部安全权限是 `signature|privileged`，普通三方应用也没有 `MANAGE_APP_OPS_MODES`；仅在 Manifest 中声明 Oplus 权限、反复注册传感器或改用加速度计都无法获得 `allow`。

### 已采用的产品侧处理

若不要求用户修改系统权限，应用无法在受限窗口内获得真实倾斜数据。当前采用一次性提示：

1. 仅在 MediaProjection 授权成功返回后监测方向样本；取消授权、普通页面往返及关闭实时倾斜均不触发。
2. 返回后 1.5 秒仍没有任何新样本时显示长 Toast，说明倾斜响应可能暂时不可用，并建议在系统设置中把“设备动作与方向”权限设为“允许”。
3. 任一方向样本在等待期内到达便静默取消，不把提示绑定到静音检测、Surface 或音频配置。
4. Toast 文案不出现 ColorOS 或其他厂商品牌；提示展示状态持久化，安装期间最多出现一次。

若产品目标是“任何系统默认权限下都必须全程实时响应倾斜”，当前公开应用权限模型下仍无法保证；只能由用户允许相应权限。

## 本轮状态

诊断探针和生命周期实验代码均已撤销。后续已实现上述一次性 Toast，并用纯状态门禁覆盖授权结果、样本竞态和持久已提示状态。实机第一次受限返回在约 1.50 秒显示提示；第二次同样被系统拦截但不再显示。测试结束后已恢复完整偏好快照，设备仍保留一次真实提示机会；“设备动作与方向”权限保持测试前状态。
