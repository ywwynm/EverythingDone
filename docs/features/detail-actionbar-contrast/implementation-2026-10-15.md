# 详情页顶部按钮配色实施记录

本文记录逐按钮独立配色的首版实现。用户试用后要求统一颜色，最新实现及验证见[统一颜色修订](unified-color-2026-10-15.md)。

首版发布时状态：实现及回归完成，debug `202610150800` 已发布、校验并安装到两台授权设备；当时尚未提交 Git。

## 实施范围

- 返回、操作菜单及更多按钮分别选择黑白，保留图形、透明度、触摸范围及既有顶部遮罩；状态栏图标、底部按钮与弹出菜单不扩大本次修改范围。
- 复用附件静态小图，按当前按钮位置与图片裁剪结果映射；纳入透明图片、记事底色、顶部暗色渐变和操作栏半透明背景的合成。
- 无媒体覆盖时沿用记事背景明暗规则。图标横跨图片边缘时按实际图形样点分别取得媒体或底色，不按整个操作栏的平均颜色决定。
- 图标与波纹同次更新；复用波纹实例，换色不重启动画。保持既有黑色 16%、白色 36% 波纹强度。
- 菜单重建、静态媒体就绪、裁剪/比例/选帧改变、布局与滚动、记事改色均刷新；播放帧变化不重新取样。
- 切换余量为覆盖率优势 6 个百分点；覆盖率相同时，对比度几何平均值优势达到 12% 才切换。已通过细碎纹理、均匀中灰、透明合成的 JVM 回归和两机滚动核验。

## 代码组织与调度

- `DetailToolbarContrast` 统一管理返回、菜单及更多按钮。绘制前比较几何、静态小图身份、背景与覆盖层；页面静止、只有播放帧变化时不重新评分。
- 复用 `DetailMediaOverlayColors.staticRaster`；图形样点缓存，每个按钮最多 128 点。每次有效变化只绘制三个宽 128 像素的背景层小图，后台单线程评分，取消过期队列任务，应用前核验页面状态；没有整页抓图或滚动解码。
- `DetailActivity` 删除原有统一着色入口，在菜单重建、记事改色及销毁时通知控制器。`GradientRippleDrawable.updateAdaptiveColor()` 原位改变颜色与峰值透明度，不重建正在播放的波纹。
- debug 专用 `DetailToolbarContrastProbe` 驱动真实页面并输出检查结果。`run-probe.ps1` 限定本次两台设备，保存并恢复系统旋转设置；测试使用临时记事，已有记事只读核对。

## 验证计划

- JVM 验证临界区域稳定性、明显明暗变化立即切换、混合背景与透明合成，保留既有附件配色回归。
- 两台授权设备合并验证：黑白/左右分区/上下分区图片、滚动中换色与离开媒体、记事改色、透明图片、菜单重建、裁剪、GIF/视频静态规则、横竖屏及波纹实例连续性。
- 记录主线程取样准备、后台计算和颜色应用开销，以及滚动期间实际更新次数；已有记事只读核对，修改类用例使用专用临时记事。

## 首轮验证

- `:app:assembleDebug` 成功；9 项 `MediaOverlayContrastTest`、5 项 `ImageAttachmentGifLifecycleSourceTest`、18 项 `ParticleBatchFourTest` 均通过。
- `toolbar-first` 在 `3B1629006YC00000`（PLZ110）与 `R5CW20BLNKL`（SM-S9180）的横竖屏共 120 项通过：每台竖屏 32 项、横屏 28 项。
- 覆盖黑白、透明、左右分区、上下分区图片、慢速真实滚动、反向回滚、离开媒体、记事改色、菜单重建、裁剪和撤销/重做、多列网格复用、波纹配色与按住时实例连续性。
- 两台各查看 2 条已有记事，前后完整行 SHA-256 相同；四次运行合计清理 36 条专用临时记事，并恢复原系统旋转状态。
- 滚动窗口按应用次数去重后，OPPO 竖/横屏记录 65/68 次应用，主线程准备和应用 P95 为 954/715 微秒；三星为 68/69 次，P95 为 1003/959 微秒。后台评分 P95 分别为 4297/2314、2120/2244 微秒。以上不包含完整图片首次解码、后台排队和整个页面帧耗时，不能解释为整页流畅度指标。
- 首轮播放期稳定断言尚未强制验证媒体正在运行，需补充实际播放状态和帧变化核验。
- OPPO 的首轮截图被系统 16 KB 对齐兼容性提示遮挡并压暗；这些截图不能作为无遮挡视觉验收。逻辑断言通过仍保留，但需关闭该次提示后补截图，不选择“不再显示”以改变系统偏好。

## 补充验证结果

- `toolbar-supplement` 在两台设备的横竖屏各 7 项通过。其中每组先确认 GIF、视频预览处于运行状态且帧编号推进，再验证页面静止时图标配色、静态加载次数和配色计算次数不变；共 16 项真实播放检查。其余 12 项是黑白及分区图片的重复检查。
- 视觉补测在截图前等待波纹绘制提交：三星黑底白图标、浅色波纹及左右分区独立配色均无遮挡；OPPO 横竖屏关闭当次系统提示后，白底黑图标及深色波纹均无遮挡并已人工查看。
- OPPO 每次新建测试页面仍可能再次出现 16 KB 提示。`toolbar-visible` 只为首张截图关闭当次提示；其黑底与分区截图仍被遮挡，不算无遮挡视觉验收，也未改变“不再显示”偏好。平台问题单独记录在[Android 平台迁移后续事项](../android-16-migration/followups.md)。
- 13 份原始结果合计 163 次检查、0 失败，其中包括重复截图检查，不代表 163 个独立场景。累计 71 条临时记事全部清理；4 条已有记事的完整行哈希均保持不变。原始 JSON 和[汇总](validation/2026-10-15/summary.json)已归档，截图保留在 `tmp/detail-toolbar/` 对应运行目录。
- 本次不包含系统状态栏图标配色，也未修复设备原有的 16 KB 原生库兼容性提示。性能数据仅表示配色工作的主线程准备/应用及后台评分耗时，不包含整页帧耗时。

## 复现方式

```powershell
.\gradlew.bat :app:assembleDebug :app:testDebugUnitTest --tests 'com.ywwynm.everythingdone.helpers.MediaOverlayContrastTest' --tests 'com.ywwynm.everythingdone.adapters.ImageAttachmentGifLifecycleSourceTest' --tests 'com.ywwynm.everythingdone.views.particledismiss.ParticleBatchFourTest'
.\docs\features\detail-actionbar-contrast\run-probe.ps1 -DeviceSerial 3B1629006YC00000 -RunId toolbar-full -Install
.\docs\features\detail-actionbar-contrast\run-probe.ps1 -DeviceSerial R5CW20BLNKL -RunId toolbar-playback -Phase supplement
```

默认合并横竖屏；`-Phase visual` 只补视觉检查。OPPO 首张截图需处理系统提示时可加 `-GateVisual`，脚本只根据系统窗口 XML 确认按钮并关闭当次提示。

## 发布与安装

- 通过 `:app:publishDebugUpdate` 发布 debug `202610150800`，使用[独立中文发布日志](debug-updates/update-20261015155915.md)。发布时间为 2026-10-15 16:00:10（上海时区）。
- 下载线上 APK，确认 SHA-256 为 `fc916a48399ffbfc8e45ae848ddbe2e1823543f095428f2b4c72e2b2f2c04839`、大小为 30,953,133 字节，与本地安装包一致；线上元数据全部字段及发布说明一致，见[校验记录](validation/2026-10-15/publish.json)。
- 16:01:42 在 `3B1629006YC00000` 与 `R5CW20BLNKL` 覆盖安装成功；`versionCode=43`、`versionName=2.0.0`。发布构建只更新 debug 更新码，未在回归后改动生产逻辑；不重复执行已通过的整套测试。
- 所有本次改动保留在工作区，未执行 Git 提交。
