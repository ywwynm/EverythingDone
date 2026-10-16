# 卓易通 HDR 查看器接入

## 范围

用户暂停 APP5 曲线深挖，要求把当前模式 6 接入全屏查看器、优先只在卓易通启用，保留动态照片和缩放，授权 OPPO `3B1629006YC00000` 测试后发布阿里云。

- 环境复用 `TapGroupRolePolicy.isKnownCompatibilityEnvironment`，即厂商 HUAWEI、显示版本以 `System ` 开头，并要求 API 34 及以上。未改变碰一碰的判据或组网策略。
- 测试覆盖通过 debug 专用、进程内变量启用，不接受 Intent、系统属性或持久化设置；release 固定无覆盖。普通 Android 继续原来的 Glide／PhotoView／HDR Window。
- 原生 Gainmap 优先；APP5 复用严格容器检查、真实灰度纹理和参考公式。增益余量 4 倍、Surface 请求自动、PQ 参考白 203 nit 与此前模式 6 的自动值一致。没有进一步解释未知字段，也没有承诺与华为图库精确一致。

## 实现

- 将 APP5 解析、图像准备、增益数学、EGL Surface 与解码预算从 debug 目录移入 main，测试页与查看器共享，不复制第二套曲线。
- `HdrViewerController` 只拥有当前页的额外 GPU 数据。独立后台解码使用 PhotoView 当前解码尺寸（长边上限 4096、总像素上限 800 万，并按剩余内存收紧），不使用测试页固定 1280 像素预览、不额外扫描原图 SHA。预算不足时保留原图；前两张实际解码为 1980 × 2640，与 PhotoView 尺寸一致；准备约半秒，取决于设备。
- 不改变 Glide 位图、不清除 PhotoView Drawable，也不接管其矩阵监听器。`OnPreDraw` 读取 Android 矩阵，`HdrViewerGeometry` 转为 GPU 齐次坐标；合并重绘，只更新坐标，不在手势中重新解码或上传纹理。
- PQ Surface 位于 PhotoView 之后、视频与操作控件之前，首帧前 alpha 为 0；实际 EGL 通道位数／色彩空间不符、解码异常或首帧超时均撤回原显示。参考 [Android SurfaceView 文档](https://developer.android.com/reference/android/view/SurfaceView)：默认层位于窗口下方，视图顺序决定透明区域上方的控件，API 34 起支持 alpha。
- 切页滚动、HDR 关闭、动态播放、空间模式和后台时撤层；暂停保留当前 GPU 数据，切页使旧解码失效，销毁停止后台任务。EGL 访问与 Surface 销毁互斥，已关闭执行器拒绝排帧时安全退出。
- debug 查看器日志限约 1 MiB，异步写入文件，保留最近事件；导出先排空日志写入队列，再追加到对照页的 TXT。日志包含设备信息、源类型、解码尺寸、APP5 参数与参考假设、EGL 实际格式、输出／回退状态，不记录照片像素或路径。

## 验证

- 本地 assembleDebug 通过；HDR JVM 测试 31 项全部通过，显式提供原图目录后完成三张原图的容器回放。5 项新增矩阵测试涵盖留黑、方向、缩放平移及裁剪坐标、旋转、Drawable 密度和非法矩阵。
- 实际共享 GLSL 的 15 项离屏 GPU 数值用例通过，含 PQ、增益权重、色域矩阵与 APP5 参考公式。
- `HdrViewerRegressionProbe` 在指定 OPPO PLZ110／API 37 上执行真实 `ImageViewerActivity`，18 项断言全部通过：普通 Android 环境不启用 PQ；标准 Gainmap；双击缩放和平移；HDR 开／关均保留矩阵；两张 APP5；横竖屏；SDR；GIF 仍动画；动态照片自动播放；长按播放后恢复缩放；普通视频；前后台恢复；空间模式进入／退出；诊断导出。
- 动态照片测试使用真实 HDR JPEG 追加有效 H.264 MP4 的构造样本，经过现有 `MotionPhotoDetector` 和 `MediaPlayer`，未新增测试记事。播放前后矩形均为 `RectF(-1216.0005, 104.0, 2431.9993, 2536.0)`。
- 第一轮测试在缩放动画尚未完成时取了比较基准，误报动态照片矩阵变化。将等待条件从比例大于 2.9 修正为动画到达 3.0 后，矩阵逐项一致；没有为此改变产品交互。
- 截图复核增加了等待系统旋转过渡完成的条件。一次截图测试全部断言通过后，设备在 `Instrumentation.finish → UiAutomation.disconnect` 返回 `DeadObjectException`；属于测试截图连接的退出失败，不在查看器渲染路径。截图改为显式 `-e screenshots true` 才启用，常规回归不创建 UiAutomation 连接。最终回归 18 项全部通过，仪器正常返回 0，结果归档于 `viewer-device-result-20261016.json`。
- 查看器截图已人工检查图片方向、留黑、缩放裁切及操作层。HDR 截图经过系统转换，不用来判断屏幕高光或色彩是否与图库一致。空间模式仅验证公共状态路径，不运行模型生成，也不声明完成所有厂商动态照片覆盖。

## 待用户验证

卓易通自动判据是否命中；两张 APP5 与标准照片在普通查看器中的高光、颜色；双指缩放、连续翻页和动态照片切换的实际流畅度。若环境不支持正确 PQ 输出，保留原图并通过现有日志入口导出。

## 发布结果

阿里云 debug `202610160855`：[下载 APK](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610160855.apk)。已下载核对 SHA-256 `d6e74bc3a0b810b85473b2b128aea116c639513f94bb499eb9f5485d0df27434`、大小 30,760,777 字节及既有 debug 签名。线上元数据与本地安装包一致。
