# 2026-10-20 GIF 首帧重播崩溃排查

## 输入与已确认事实

- 用户在首页看到一次崩溃，提供 `crash_20261020091302.log`；日志记录版本 `2.0.0_43`、OPPO PMA110、Android 17／API 37。
- 调用链来自详情附件：`applyItemsChanged → evaluate → pumpQueue → refreshPlayback → onResourceReady → applyGifPlaybackState`。仅凭崩溃日志不能确定当时的页面切换顺序，也不能把异常归为首页卡片加载失败。
- 异常为 `IllegalArgumentException: Can't restart a running animation`，抛于 Glide 4.16.0 的 `GifFrameLoader.setNextStartFromFirstFrame`。它与 7 月同一个 `GifDrawable` 重复启动时的另一条异常不同。
- 当前代码保留 `!gif.isRunning` 检查，仍可进入底层异常；Glide 的 `GifDrawable` 与共享 `GifFrameLoader` 分别维护运行状态。需要用多实例缓存复用测试验证，不能再次仅增加同一个判断或吞掉异常。
- 本轮使用本地 JVM 验证，不连接设备。已有 7 月源码契约测试只检查判断存在，不能证明不同实例间的播放状态独立。

## 确定性复现与对照假设

新增 Robolectric JVM 测试，直接通过 Glide 4.16.0 的 File 模型加载同一份合成双帧 GIF。第一个请求得到的 drawable 启动后，第二个请求命中活动资源，返回另一个 drawable；此时第一个 `isRunning=true`，第二个 `isRunning=false`。对第二个执行现有的首帧启动判断，稳定抛出与用户日志相同的异常，连 Glide 中的抛出行号也一致。失败记录位于忽略目录 `build/gif-isolation-red.log`。

对照按以下优先级进行：

1. 不同显示位置共享 `GifFrameLoader`：隔离需要首帧重播的请求，其它位置仍在播放时也应能正常开始。
2. 同一个 drawable 重复调度：保持现有 `isRunning` 判断，重复应用时应保留正在运行的进度。
3. 页面退出后的回调：本地复现不创建或切换 Activity，证明页面退出不是该异常的必要条件；日志仍不足以还原用户实际操作序列。

## 修复决策

只对需要从首帧播放一次的动态请求增加 holder 所有者身份；默认同时循环、静态资源仍使用原有共享缓存。身份通过 Glide 的 `Option.memory` 参与内存缓存、活动资源与在途任务的区分，不改变文件和裁切对应的磁盘缓存身份。所有者是独立小对象，不引用 View、Activity 或 Adapter，随 holder 保持稳定。

View 的加载身份额外区分独立播放与共享循环，避免切换模式时无加载快路径继续使用上一种共享资源；Glide 的文件内容签名保持原样。保留现有首帧重播、单对象运行检查、有界位图占位和标准 `Glide.clear`。不捕获并忽略这次异常，也不停止其它显示位置来满足重播条件。

## 验证

- 修复前真实 File 请求测试以用户日志中的 `Can't restart a running animation` 失败。
- 修复后 6 项真实 Glide 测试通过：两个独立显示位置播放、保留旧路径异常对照、与共享循环同时存在、确定性并发加载、同一所有者停止并释放请求后的缓存重播、磁盘缓存身份不变。
- 原有 5 项 `ImageAttachmentGifLifecycleSourceTest` 通过，保留同实例重复启动保护与标准清理约束。Robolectric 只作为 `testImplementation`，不进入 APK。
- 全量 `:app:testDebugUnitTest` 共 1692 项，0 failure、0 error、3 skip，记录位于 `build/gif-isolation-suite.log`。
- 没有连接物理设备或模拟器；本轮证明日志对应的缓存共享机制和修复，不声称还原用户首页出现崩溃时的完整操作顺序或完成真机滑动验收。

## 发布

debug `202610200156` 已于北京时间 2026-10-20 09:56 发布。远端 `latest.json` 版本与 SHA-256
对应本轮；回下载 APK 大小为 31,681,061 字节，SHA-256 为
`392f4a88fefe51e30329d1f1a4f7c8b011295cf9e2ccfeafab5315fdecfa6ef7`，与本地发布记录一致。
发布日志为 [update-20261020095651.md](debug-updates/update-20261020095651.md)。

## 上游依据

- [Glide 4.16.0 GifDrawable](https://github.com/bumptech/glide/blob/v4.16.0/library/src/main/java/com/bumptech/glide/load/resource/gif/GifDrawable.java)：drawable 的运行状态与 `GifState` 中帧加载器是不同对象；`newDrawable` 复用状态。
- [GifFrameLoader](https://github.com/bumptech/glide/blob/v4.16.0/library/src/main/java/com/bumptech/glide/load/resource/gif/GifFrameLoader.java)：有其它订阅者时，停止当前 drawable 不会停止帧加载器，不能从首帧重启。
- [EngineKey](https://github.com/bumptech/glide/blob/v4.16.0/library/src/main/java/com/bumptech/glide/load/engine/EngineKey.java) 与 [Option](https://github.com/bumptech/glide/blob/v4.16.0/library/src/main/java/com/bumptech/glide/load/Option.java)：内存选项参与请求共享身份，但不改变磁盘 key。
