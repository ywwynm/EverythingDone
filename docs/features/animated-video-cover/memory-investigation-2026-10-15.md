# 复杂媒体列表内存归因

## 已有复现与本轮约束

此前三机相同复杂列表已复现高占用：低截图频率下 PSS 峰值约 646／921／1191 MiB，Java 已用峰值约 100–117 MiB，Glide 活动资源不超过 6、资源缓存约 25–34 MiB。详见 [前轮调查](complex-drag-investigation-2026-10-15.md)。这足以建立可重复测量的基线，但还不能说明具体持有者或分配来源。

用户要求继续核对 GIF 未释放、重复解码等问题，确认原因后再优化；拖动期间的其它卡片必须继续正常加载和播放，既有功能、裁切、质量和 UI 表现不能改变。继续使用已授权设备中的独立测试包，原应用只读取校验和。

先在占用最高的魅族上做一组归因：真实列表快照中的活动／缓存 GIF 与位图、解码身份、离屏持有者、native／图形内存，再据证据选择单变量对照。需要修改公共媒体路径时，增加同一输入的像素与播放行为回归。

## 假设与历史约束

1. 每帧裁切直接新建大位图而未使用 Glide 提供的池；若成立，分配栈应集中在 `MediaCropBitmapRenderer.renderCrop`，同尺寸的已归还目标位图也无法再次命中。
2. 离屏或回收的 GIF 仍被持有并解码；若成立，脱离窗口的 ImageView 对应 loader 仍有订阅者和帧变化，离屏释放后分配速率应下降。
3. 同一媒体重复请求／尺寸变动引起完整 GIF 重解码；若成立，稳定视口中同一 key 应持续产生不同 decoder，而不只是正常逐帧解码。
4. 图形／粒子缓冲或分配器保留占用较大；若成立，仅统计活动 GIF 不足以解释 PSS，分配栈与分类对照应指向其它持有者。

用户要求作决定前检索历史。已查 `c6e275ed4`（动图播放引入）及相关 ADR／决策：逐帧预裁切用于保留 center／scale／比例，明确避免恢复容易失效的 `ImageView.imageMatrix`；静态烘焙与 RemoteViews 单帧约束须保留。该变更从引入时就未使用传入的 BitmapPool，提交说明中未找到故意禁用位图池的理由，但不能据此直接认定可改，仍须用运行测量和像素回归证明。原“依赖 Glide 屏外暂停”的叙述也要用实际 loader 状态检验。

进一步查 `2da3f98df`：统一预裁切用来修复文件夹缩略图等场景的旧矩阵问题；`d4cc8429`：原生 GIF 生成替代纯 Java 是因为后者每帧约 12 秒，播放复用现有逐帧裁切与 Glide，磁盘缓存／后台生成／静态失败回退都有明确语义。本轮不改这些生成与显示契约。

## 已复现的位图复用缺失

`crop-reuse-red-1015` 在三星真实 BitmapTransformation／BitmapResource 边界处理 120 帧、每帧经正式 BitmapResource.recycle 归还输出：120 张不同的 1256×2300 ARGB 位图，复用为 0；64 MiB 位图池 hits=0、misses=0、puts=120、evictions=115。约 1.29 GiB 的输出像素分配在约 1.2 秒内完成，同尺寸的已归还位图完全未被查询。预设复用断言失败，原应用校验和一致。该最小测试定位了分配行为，但仍须以真实列表分配栈和持有者测量说明对总内存的贡献。

## 实际持有者与离屏解码

`memory-meizu-baseline-1015` 完成 97 卡单程遍历。弱引用采样总共见到 49 个 loader／48 个媒体 model；过程中存活通常 6–10 个，并未随 48 个 GIF 持续累积。仅回到顶部时一个 model 再建 decoder，符合缓存淘汰后再次显示，不能据此认定重复解码缺陷。活动／待交付 GIF 位图约 36–57 MiB，明显小于整体 native／graphics 数百 MiB。

但确实观察到一个离屏高卡片 GIF 持续运行：`view=181185549` 已 detached、shown=false，退出 Activity 后仍有一个 subscriber，frame 从 69 → 45 → 70（75 帧循环），其 1256×2649 输出每张 12.7 MiB。退出后约 6 秒，池 puts 继续从 5374 增至 5735；GC／clearMemory 均不能停止活动 loader。Glide RequestManager 只有一个，代码确认 ThingsAdapter 传入 App，故没有 Activity 生命周期；现有 holder 回收也未清除封面请求。原 D5 所说的“Glide 自动屏外暂停”至少在这条 RecyclerView 链路不成立，需继续用实际持有者缩小范围。

历史补查 `eeafddb0a`（2016）只为复用 RequestManager 提升列表性能；`f1696d568` 引入共用 BaseThingsAdapter 以渲染 widget，不能直接把所有 context 改成 Activity。`c6e275ed4` 和 D5 期待离屏暂停、可见卡片继续播放；后来的滚动策略也需保留。本轮优先在实际图片目标的 attach/detach 边界验证，不调整帧率、分辨率、裁切或生成策略。

第一份 heapprofd 因 8 MiB 共享缓冲溢出，只覆盖夹具准备，不能用来归因拖动。第二份提高采样间隔后无溢出，但启动插桩延长了长按，被魅族系统内容识别抢走手势，按覆盖断言失败；截图已归档。后续仅在按下前等待首屏稳定并降低采样频度，不改变拖动中的加载／播放策略。

## 分配栈确认与第一项修复

`memory-meizu-baseline3-1015` 全程完成，无 heapprofd 缓冲溢出，34805 个展开样本中 13 个空栈被忽略。约 70 秒 trace 累计 native 分配 16296 MiB，其中 `MediaCropBitmapRenderer.renderCrop` 13841 MiB（约 85%）；约 55.4 秒拖动窗口内该路径累计 10789 MiB。分析保留 setup／拖动区间，5 秒堆快照边界的分配归属有时间量化误差，数字用于同配置对照，不把累计分配当驻留内存。

修复只把动态 Transformation 的输出改为 `BitmapPool.get`，保留尺寸、逐帧预裁切、cache key、静态调用分配语义。`get` 清空透明像素，输出恢复 sRGB，避免池中其它广色域资源改变原来的颜色；裁切数学抽为公共 draw-into。

修改前在设备记录 24 个像素基线，覆盖透明／RGB565、三种横竖与奇数尺寸、边界／无效参数／比例裁切。修改后与静态输出及原基线 SHA-256 全部一致，包括先往池中放入带洋红脏像素的 Display-P3 位图。120 帧复用测试由 120 次分配变成 1 次、119 次复用，evictions 从 115 降到 0。真实列表对照仍单独验证，不用微基准推算 PSS。

单变量 `memory-meizu-pool-1015`：约 55.6 秒拖动内位图分配从 10789 MiB 降到约 2523 MiB（池 miss + 静态裁切），约降 77%；有采样工具的 PSS 峰值／中位数从 1226／1027 MiB 降到 1097／929 MiB。离屏 GIF 仍运行，符合只修分配、尚未修生命周期的预期。

## 回收池定位及图形内存

普通 FrameLayout 场景的晚交付、附着、移除、重新附着和 clear 均在三星／魅族通过，否定了“任何离屏晚交付都必然运行”的宽泛假设。保持同一复现链，`recycle-meizu-red-1015` 缩小为 25 卡后仍按“Activity 销毁后运行 loader 必须为 0”断言失败。实际 owner 证明运行中的 `ImageView=73880502` 属于 `card=123651294`，该 holder 已位于 RecyclerView 的 `RecycledViewPool`，不是等待重现的已绑定 cache。该对象没有调用清理请求，`GifDrawable.visible=true`，持续解码。拟仅在正式回收时 clear 两个媒体目标并清除旧 key／异步绑定 token，保留临时离屏 cache 的画面与正常重附着，不泛化修改滚动播放策略。历史 `d60ef2730` 已在详情附件适配器执行相同回收责任，首页 BaseThingsAdapter 尚缺失。

`memory-meizu-ancestry-1015` 的 gfxinfo：Skia 图形缓存约 378 MiB，其中约 370 MiB 可回收；系统给此分辨率的缓存上限约 419 MB。窗口与粒子 Surface 共 8 个显示缓冲约 138 MiB；这两类占图形内存大头，不能归类为 GIF decoder 泄漏。检查 `824589547`／`6c583e342`／`a34b03b09` 历史确认统一 SurfaceView、常驻 GL 及预热用于首帧和交接连续性，本轮不以拆除这些缓冲换取不等价的 UI。

## 位图池容量的单变量对照

补充检索历史未发现项目曾自定义 Glide 位图池。固定版本 Glide 4.16.0 的 MemorySizeCalculator 注释解释了 O+ 默认从四屏减为一屏的原因：普通图片多用 HARDWARE bitmap；本项目逐帧软件裁切不符合这一前提。保留其低内存设备策略及默认 memoryClass 总预算。

`memory-meizu-fixed-1015`（复用 + 回收，原一屏池）与 `memory-meizu-pool2x-1015`（仅测试参数把池设为两屏）均约 55.5 秒全列表移动、无失败／退出后运行 loader 为 0。池上限 16.7 → 33.3 MiB，miss 431 → 108、evictions 415 → 91；拖动窗口内池 miss 分配约 2423 → 620 MiB。PSS 峰值／中位数 1127／916 → 760／695 MiB，native 已分配峰值 424 → 234 MiB。扩大有界复用空间降低了大帧抖动，而非减少播放数量。

生产采用 `MediaGlideModule`：仅 O+、非 low-RAM 且两屏池不压缩既有资源缓存时使用官方计算结果，继续受原 memoryClass 总预算限制。其它设备维持原默认；不直接无条件翻倍，不修改图形缓存或显示缓冲。

OPPO 第一轮完整 97 卡往返与退出后 loader=0 已完成，但诊断显式 GC 后测试线程等待，重新前台启动隔离包后继续并通过。不能据此声称存在产品死锁；最后三机回归在 OPPO 使用 `memory_gc=false`，保留真实退出检查，免受额外后台诊断步骤影响。

## 最终回归

最终正常配置（没有 pool_multiplier 覆盖）三机全部通过，每机 97 卡／80 媒体，真实按住从顶到底再返回；每次采样都截取移动中的画面。

| 设备／运行 | 实际移动样本 | 显示媒体／变化帧 GIF | 全程 PSS 峰值 | 实际池上限 |
| --- | ---: | ---: | ---: | ---: |
| OPPO `memory-oppo-final-1015` | 366 | 80／48 | 475 MiB | 24.5 MiB |
| 三星 `memory-samsung-final-1015` | 386 | 80／48 | 875 MiB | 33.9 MiB |
| 魅族 `memory-meizu-final-1015` | 362 | 80／48 | 843 MiB | 33.3 MiB |

共 1114 个实际移动样本；无非源透明、像素疑点、失败请求、OOM／加载错误日志；拖动源占位及取消恢复通过。三机均观察到 48 张 GIF 的帧变化，Activity 退出后运行 loader 均为 0。代表性移动截图已人工复核。上述密集截图结果不能直接与稀疏截图／heapprofd 的 760 MiB 数字混用。

1496 项 JVM 测试 0 失败、0 错误、1 项既有跳过。三机从本轮初始到清理的原数据库／设置校验和一致，隔离包已全部卸载。最终构建生成的 `GeneratedAppGlideModuleImpl` 确认注册了 MediaGlideModule，实机池上限也与配置相符。

可读汇总：[三机回归](checks/memory-results-2026-10-15.json)、[同条件分配对照](checks/memory-heap-comparison-2026-10-15.json)。仍需保留的边界：未直接验证原卓易通环境与其内存限制；Android 压力测试通过不等于消除所有平台差异，原用户视频也不在夹具内。未发现历史 GIF 不断累积或异常的同一 key 大量重复创建 decoder；往返后因缓存淘汰产生的重新解码属正常行为。

## 发布与收尾

正常包构建及 `:app:publishDebugUpdate` 通过，使用 [本轮发布说明](debug-updates/update-20261015112512.md)，已发布 debug `202610150326`。远端完整元数据／说明与本地一致，下载 APK 为 30,920,253 字节，SHA-256 为 `e15bc5105eda4ac17e86d7924805afb9053b46cba73ab332314a48abff778cbd`；ZIP CRC、正常包名 `com.ywwynm.everythingdone`、无复杂列表测试媒体资产均核验通过。验证结果保存在 `tmp/media-memory-20261015/publish-verification.json`。

[下载本轮调试版本](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610150326.apk)。三台设备只安装过本轮隔离测试包，清理后原数据库及设置校验和一致；没有安装该正常包或创建 Git 提交。
