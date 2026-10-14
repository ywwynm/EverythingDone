# 华为安卓平板打开正在做事界面崩溃分析

以下分析记录修复前的状态。用户随后授权修复，现已将版本判断提高到 API 34；构建与发布结果见文末实施记录。

## 范围与输入

- 用户要求先分析，暂不修改代码。本轮仅核对提供的崩溃日志、当前及历史源码、本地 SDK API 元数据和 Android 官方文档，没有连接设备、构建、发布或实施修复。
- 输入文件：用户提供的 `crash_20261014004325.log`。日志报告应用 `2.0.0_43`，设备 `HUAWEI PCE-W30`，系统 `12_31`。`DeviceUtil.getAndroidVersion()` 的格式是 `Build.VERSION.RELEASE + "_" + Build.VERSION.SDK_INT`，因此后者表示 Android 12／API 31。
- 按用户的分析范围，使用已捕获的异常及 API 版本定义进行静态归因，没有建立或声称完成设备运行复现。

## 结论与证据

- 直接异常为 `NoSuchMethodError`：系统的 `android.view.WindowManager.LayoutParams` 不存在 `setWallpaperTouchEventsEnabled(boolean)`。第一个应用栈帧是 `DoingActivity.initBackground()`，旧版本第 273 行；页面尚处于 `onCreate → init → initUI` 阶段。
- 当前代码仍以 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.R`（API 30）保护该调用，位于 `DoingActivity.kt:287–288`。API 31 会进入分支。
- [Android 官方 API 文档](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#setWallpaperTouchEventsEnabled(boolean)) 标明该方法新增于 API 34。本地 `E:/AndroidSDK/platforms/android-36/data/api-versions.xml` 同样记录 `<method name="setWallpaperTouchEventsEnabled(Z)V" since="34"/>`，两项证据一致。
- 因此根因是应用对系统 API 的版本判断过低，不需要以华为框架缺陷解释这份日志，也不是图片权限或图片解码问题。API 30–33 均存在同类兼容风险；这台设备的日志已直接确认其系统缺少该方法。
- 该方法控制显示系统壁纸时是否向壁纸转发触摸事件，传入 `false` 意为关闭转发；它不是读取壁纸图片的接口。日志中的 `framework.jar!classes3.dex` 指系统类所在位置，不表示应用漏打包第三方依赖。

## 与本次背景功能的关系

- Git 历史确认，2026-05-07 的 Java 实现（`4ac19e0ab`）已经以 `VERSION_CODES.R` 判断后调用该方法；2026-05-21 Kotlin 迁移（`625bf3bdc`）原样保留。
- 对比自选背景提交 `4f9f42d85` 的父版本及当前版本，这一调用及判断均未改变，符合用户所述“本次背景修改之前已发生”。当前版本也仍有同一风险，自选图片加载位于此调用之后，选择图片不能绕过启动崩溃。
- 本轮背景功能验收的三台设备均高于 API 33，不能覆盖 API 31 缺少该方法的情形。编译 SDK 为 36，编译通过只说明编译 SDK 提供该方法，不代表旧版系统也提供。

## 修复方向

- 将调用范围限制为 API 34／`Build.VERSION_CODES.UPSIDE_DOWN_CAKE` 及以上；低版本跳过该项可选的壁纸触摸转发设置。无需为此增加图片权限或修改粒子、计时动画，也不应以宽泛捕获异常替代正确的 API 判断。
- 后续若实施，在 API 31 环境验证 DoingActivity 可启动，并在 API 34 及以上确认该设置继续执行。已有高版本设备结果不能替代低版本启动验证。

## 实施记录

- 用户确认按上述方案修复，`DoingActivity.initBackground()` 已改用 `Build.VERSION_CODES.UPSIDE_DOWN_CAKE`（API 34）判断，旧系统跳过调用。应用代码仅修改这一处条件。
- 当前未连接出现问题的华为平板，API 31 的实机启动结果仍需后续验证。本轮不重复运行只能覆盖高版本系统的三机动画回归。
- `:app:publishDebugUpdate` 构建及发布成功，版本 `202610140713`。远端完整元数据与本地一致，下载包大小和 SHA-256 通过核验，详情见 `debug-updates/update-20261014151219.md`。未创建 Git 提交。
