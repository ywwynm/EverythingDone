# 弹窗与面板动画接入检查

## 需求与复现入口

补齐开始做事、更突出的通知、首页调整外观，以及录音、音频播放、FableSol 参数弹窗。继续使用已认可的共同模型，出现从下方进入、播放 0.6 秒。

用 `ParticleCoverageProbeActivity` 在宿主首帧前提交真实 DialogFragment，分别加入普通内容、隐藏 SurfaceView、TextureView 和真实 FableSol 海浪。只用固定素材，不读取记事、不启动录音。`analysis/verify_dialog_coverage.py` 检查实际动画层、保存实际输入，验证窗口最终清理；原始结果在忽略跟踪的 `analysis/dialog-coverage/`。

## 已定位的接入路径

- 详情里的开始做事有两个入口：提醒／习惯使用 `ThingDoingDialogFragment`；普通记事经 `StartDoingActivity` 显示 Chooser。后者在透明宿主首帧前提交弹窗，宿主未附着时出现入口直接返回；弹窗关闭又立即 `finish()`，会移除尚在播放的消散层。
- `NoticeableNotificationActivity` 自身使用浮动 Activity 主题，不经过 DialogFragment。
- 首页调整外观是 `ThingsActivity` 内部面板，仍调用纵向滑入滑出。
- `containsLiveSurface` 递归命中任意 SurfaceView／TextureView 就跳过整张弹窗，未排除隐藏子树。海浪使用独立 SurfaceView；媒体裁剪使用 TextureView，后者实际上属于普通窗口合成内容。
- 同类待核对入口包括延迟提醒／认证透明宿主、相机取色以及列表小组件的浮动配置页面。

## 平台依据

- [Android PixelCopy](https://developer.android.com/reference/android/view/PixelCopy)：支持分别复制 Window 和 SurfaceView 的缓冲；SurfaceView 的独立内容需要另行合成，并处理首帧尚未到达的失败。
- [Android TextureView](https://developer.android.com/reference/android/view/TextureView)：TextureView 参与普通 View 合成，不应与 SurfaceView 一律排除。
- [SurfaceView 源码](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/main/core/java/android/view/SurfaceView.java)：窗口层通过透明区域暴露下方独立 Surface，叠加文字必须保留在 Surface 上方。

## 验证状态

修复前首次真实窗口测试已复现普通弹窗在宿主尚未附着时遗漏出现动画。当前 `9018f404` 在线，`R5CW20BLNKL` 未连接；不将未执行的双设备检查记为通过。

## 实施与增量检查

- 透明宿主等待首帧附着再捕获；开始做事、延迟提醒和认证在粒子消散结束后才释放宿主。浮动通知与小组件配置由薄 DialogFragment 承载原有内容，业务及结果仍由原 Activity 管理。
- 首页调整外观通过 `ParticlePanelAnimator` 复用同一快照和运动入口，保留取消／确认回调、列表空间恢复和用户动画开关；返回使用左上方向。
- 捕获可见 Surface 的独立缓冲并置于窗口文字下方；隐藏 Surface 不参与。首轮合成测试定位到 `getGlobalVisibleRect` 的 ViewRoot 坐标误用，修正后真实海浪和覆盖文字均进入截图。
- FableSol 捕获持有独立临时冻结状态，释放后仍遵守导出冻结及用户暂停。普通弹窗保留原离屏捕获，避免改变已确认的文字栅格化和准备时间。
- `capture2` 五组全部进入出现和消散；`coverage3` 增加内嵌面板、浮动内容与真实音频播放弹窗（固定静音 WAV），仍全部进入两向动画并清理窗口。后续继续验证实际产品入口，不以测试夹具替代。
- 窗口捕获与离屏捕获的初次交叉比较出现少量字缘像素差；恢复普通弹窗原捕获方式后，`coverage-reg2` 的选择、更新日志及颜色信息整幅图和标题／按钮／正文像素差均为零，尺寸／位置一致。

## 产品入口与最终回归

- `9018f404` 从实际界面打开开始做事、调整外观、录音、FableSol 参数、列表小组件配置和通知，均记录到约 0.6 秒出现和约 1 秒消失。录音只打开准备界面，未开始录制；小组件只取消，未创建配置；通知使用已有普通记事的同一入口，不触发提醒动作。
- 浮动内容迁入 Dialog 后，窗口接管会重写根节点 LayoutParams。提前保存原宽度后，小组件恢复 840 px、通知恢复 672 px；没有把原浮动内容拉伸成全屏。透明宿主额外携带的系统 DIM 已移除，通知背景通道比从约 0.16 恢复约 0.40。
- 真实 Surface、Texture、浮动内容、内嵌面板、音频播放均检查实际动画输入及 GPU 完成日志。Surface 与前景文字正确合成；出现后 100 ms 立即关闭的 Surface 用例完成消失并清理，出现被打断不计为完整播放。
- 最终普通选择／更新日志／颜色信息三组继续整图零像素差，位置和尺寸一致。独立 Surface 冷启动仍可能等待首帧，不能把普通弹窗的准备时间推广到所有海浪场景。
- 进一步检查发现认证等路径可能在异步捕获刚开始时直接 finish，此时尚没有动画层可等待；增加同调用顺序的退出探针，验证捕获准备期也包含在透明宿主生命周期中。

捕获期退出探针修复前 `dismissal=false`，宿主退出且 GPU 未播放消失；增加待完成捕获计数后，`final-lifecycle` 的同步退出、浮动内容、Surface 快速关闭和普通 Surface 四组全部完整消失并移除。等待只覆盖已启动的捕获／动画，没有额外固定播放延迟。22 项 JVM／实际 GLSL 检查通过。

### 其余入口审计

- 当前 29 个直接继承 `BaseDialogFragment` 的类统一经过动画入口，没有子类关闭粒子或绕过 `onCreateDialog`。选择、确认、更新日志、日期时间、颜色、文件夹、习惯、下载及导出进度等沿用共同入口。
- 延迟提醒和认证与开始做事共享透明宿主修复；相机取色、媒体裁剪不再被 Surface／Texture 类型判断整体排除，但本轮没有实际打开相机验证取色，也没有改变媒体内容。
- `PopupPicker` 是选择菜单的 `PopupWindow`，保留原菜单动画；系统权限、系统文件选择器不属于应用自绘弹窗。
- 本轮不修改共同粒子规则、着色器或桌面序列，不重渲染既有视频。原始截图、探针结果、构建输出及 APK 继续忽略跟踪。

### 发布复核

当前只在 `9018f404` 执行实机检查；`R5CW20BLNKL` 返回未连接，待恢复后补海浪及浮动页面。独立 Surface 冷启动可能增加准备时间，捕获失败走恢复真实窗口的回退，不能承诺所有设备瞬时开始。

阿里云更新 `202609130540` 已发布，完整 APK 为 26,547,272 字节，SHA-256 `9a538fbd4ff6e213e2709163b33c0ba25769860ba8ba66c0abd26769fb36bd81`；远端包、本地发布包与 9018f404 已安装包散列一致。发布包的真实海浪、音频播放、浮动内容和捕获期间同步退出四组均完整出现／消失，GPU 完成与清理通过；发布包更新日志面板的整图像素、尺寸和位置回归仍一致。证据在 `analysis/dialog-coverage/published-coverage/`、`published-layout/` 与 `publish-verification.json`。本轮未新增 Git 提交。
