# 决策记录 / 详情页动态内容播放（Detail Animated Playback）

给记事详情页的动态内容加一个四档自动播放设置，并补齐全屏预览里视频的自动播放。术语 **Detail Autoplay** 见根目录 `CONTEXT.md`；本特性修订 [ADR-0007](../../adr/0007-animated-image-playback-scoped-per-surface.md)、[ADR-0012](../../adr/0012-thing-card-video-preview-derived-animated-image.md)、[ADR-0014](../../adr/0014-motion-photo-as-image-capability.md) 的相关结论，汇总于 [ADR-0017](../../adr/0017-detail-animated-playback-modes.md)。

## 2026-07-25

### 起点：详情页的现状是分裂的

| 内容 | Thing Card | 详情附件网格 | 全屏预览 |
|---|---|---|---|
| GIF / 动态 WebP | Cover Autoplay 门控，循环 | **无条件循环** | 无条件循环 |
| Live Photo | Cover Autoplay 门控，派生 GIF 循环 | **无条件**派生 GIF 循环 | HDR 静图 + 长按播真视频（打开时自动播一遍） |
| 视频 | Cover Autoplay 门控，派生 GIF 循环 | **只有单帧，从不派生** | 单帧 + 点播放按钮进外部播放器 |

两个结构性事实贯穿全部决策：

1. 详情附件 `RecyclerView` 的 `isNestedScrollingEnabled = false` 且高度按总行数写死（`AttachmentHelper.setImageRecyclerViewHeight`），**所有附件项一次性全部布局并 attach**，不存在按视口回收——Glide 的屏外自动暂停在此处失效。
2. 详情附件项的**长按已被 `ItemTouchHelper` 拖拽排序占用**（`isLongPressDragEnabled()` 在 `itemCount > 1` 时为 true）。

### D1 与 Cover Autoplay 的关系：独立新增，互不依赖

新增独立的四档 **Detail Autoplay**；`Cover Autoplay`（`KEY_AUTOPLAY_COVER_DYNAMIC`）保持布尔、继续只管 Thing Card 面。两者互不依赖：卡片关、详情开是合法组合，此时详情**照常**触发派生 GIF 生成。

- **否决"统一成一个四档设置"**：「逐一播放」在会滚动、会回收、条目数不定的卡片列表里没有可定义的语义（第几个算"一"？回收后重播吗？）；且要迁移已投产的布尔 key。
- **否决"总开关 + 子项"**：制造了一条没有必要的依赖，用户想"列表安静、详情热闹"就做不到。

### D2 默认档位：同时循环播放

四档为 **关闭自动播放 / 逐一播放 / 同时播放一次 / 同时循环播放**，默认**同时循环播放**。

沿用 ADR-0012 给 Cover Autoplay 定"默认开"的同一条理由：详情页的 GIF 与 Live Photo 本来就是无条件无限循环的，任何其它默认值都等于替用户把已有行为降级。选此默认后，GIF 与 Live Photo 行为零变化，视频从静帧升级为派生 GIF 循环——即用户最初的诉求开箱即得。

代价：默认档是最重的一档（见 D13 的实测计划）。

### D3 播放范围：全部档位按滚动视口生效

只有当前在 `NestedScrollView` 视口内的附件参与播放 / 排队，滚出视口暂停。

- 附件区是滚动容器顶部一个连续块，挂 `OnScrollChangeListener` 后按 `scrollY` 与行高算出可见行即可，不需要逐 view 求交。
- 这一个机制同时解决三件事：补上 Glide 屏外暂停在此处失效的窟窿；让「逐一」在长记事里不再"在屏幕外空转"；给 D8 的生成请求天然限流。

### D4 「一次」的计数边界：重新进入视口即重播

每次从不可见变为可见，就从第一帧重新播一次。

- **无状态**——不维护"已播过"集合，附件增删、旋转、Activity 重建都不用同步。
- 用户主动滚回来正是他想看的时刻，此时给一张死图违反直觉；「一次」的诉求是"别一直循环晃眼"，不是"这辈子只播一次"。
- 必须配套**可见性滞回**（例如进入 60% 才算可见、掉到 30% 以下才算不可见），否则手指停在边界会反复重播。
- 「同时循环播放」档不受此条影响：它没有次数概念，滚出暂停、滚回继续。

### D5 「逐一播放」的排队：按附件索引升序

只排当前可见项，新进入视口的项**按索引插入正确位置**（不是排队尾）。附件网格是二维的，用户期待"从左上到右下依次亮起"；按进入时间 FIFO 会在往回滚时出现倒序。

细则：正在播的项被滚出视口 → 立即放弃、让位给下一个可见项，它下次进入视口时按 D4 重新计一次；队列排空后静默，直到有新项从不可见变为可见。轮完即止，不循环轮转。

### D6 「一次」的时长：忠实播完一轮，不封顶

视频与 Live Photo 的派生 GIF 固定 3 秒（`VideoCoverPreviewManager.DURATION_MS`），真 GIF 则播完自身一轮，不设上限。

- 语义在四档之间保持一致（"一次"就是"一轮"），无需向用户解释隐藏常量。
- "播到一半突然停"的观感比"等久一点"更糟。
- 长 GIF 属边缘情况，且是用户自己放进来的。若实测证明「逐一」档会被长 GIF 阻塞，再考虑封顶（候选值 12 秒）。

### D7 播完停在哪一帧：回到静态代表帧

- 真 GIF → 回第一帧
- 视频 → 回 Thing Card Video Frame（**零成本**：派生 GIF 的第 0 帧就是它，见 animated-video-cover D3"首帧对齐"）
- Live Photo → 回**高画质静态主图**（其派生 GIF 首帧来自内嵌视频起点，不是主图）

与「关闭自动播放」档看到的画面完全一致——用户在四档之间切换，静止态永远是同一张图。Live Photo 回主图还顺带拿回全画质（派生 GIF 是降质 SDR）。

### D8 详情页的派生产物：复用卡片那份 720px GIF

不为详情单独生成高分辨率产物。

- GIF 的画质短板是 256 色色带而不是分辨率；在 3 秒循环的动态画面上，放大 1.5–2 倍带来的软化远没有静止图明显。
- 分辨率翻倍的代价是实打实的：生成瓶颈在取帧（约 90ms/帧 × 75 帧），存储要新开一套 key 维度，1GB LRU 缓存周转加快。
- **否决"把统一长边提到 1080"**：会外溢到已调优过的卡片滚动路径。

### D9 生成触发：详情页主动生成，但只为视口内的附件

- **否决"只消费已有缓存"**：卡片只展示一个 Thing Card Media Source，详情展示**全部**附件——第 2、3、4 个视频附件将永远不会动，功能基本作废。
- **否决"打开详情就为全部视频排生成"**：一条 6 个视频的记事会瞬间并发起多个取帧任务。
- 借 D3 的可见性机制天然限流："你看到哪个才生成哪个"。生成期间显示 Thing Card Video Frame 静帧，就绪后原地换成动图；与卡片端共用 `requestPreview`，同 key 由 `enqueueUniqueWork(KEEP)` 去重，卡片与详情不会重复生成。
- 设置为「关闭自动播放」时**不请求生成**。
- 派生 GIF 未就绪时**不阻塞**「逐一」队列：跳过，就绪后按"该项刚进入视口"处理。

### D10 全屏预览：不受四档管控，但视频补上自动播放

`ImageViewerActivity` 照旧无条件播放（GIF 循环、Live Photo 长按播），**四档设置不管到全屏**。理由：网格里的自动播放是"没请你就动了"所以需要开关；全屏是用户点进去说"我就要看这一张"，此时不动才叫失效。且全屏只有一张图，「逐一」与「同时」在那里没有区别。

在此前提下给视频补上与 Live Photo 同族的行为（见 D11）。

### D11 全屏视频播真视频，不播派生 GIF

复用 Motion Photo 那套 `MediaPlayer` + `TextureView`（矩阵对齐、生命周期、触感全部现成；普通视频比 Motion Photo 更简单，直接 `setDataSource(path)`，不需要 offset/length）。

依据是项目自己写下的两条结论：ADR-0012「GIF 画质差主要是**全屏**/大渐变才明显」；ADR-0014「**全屏是单界面、画质要紧，值得用真视频而非降质 GIF**」。720px + 256 色抖动铺到 1080/1440 宽的全屏上会现原形。

**不静音**——与 Live Photo 现状一致（`startMotionPlayback` 全程没有 `setVolume`）。

### D12 全屏视频的播放状态机

1. **自动播放**（打开或翻到该视频页）：从用户选定的关键帧（`videoFrameMs`）起播 **3 秒**，带触感；从关键帧到视频结尾不足 3 秒则有多少播多少。播完回静帧。
2. **首次长按**：从**视频开头**播真视频（不受 3 秒窗口限制）。松手回静帧。
3. **再次长按**：从上次松手处**继续**往后播。
4. 翻页或退出全屏则重置播放头。

自动播的 3 秒是勾子（与卡片/详情看到的是同一段内容，只是原画质），长按是正片。中央播放按钮点击进外部播放器的既有行为不变。

配套细则：
- **播放期间隐藏中央播放按钮**（`iv_video_signal`，绑了 `videoListener`、是操作入口）；详情页视频项的 `ivVideoSignal` 没有点击监听、是纯标识，**播放期间保留**（与 Live Photo"实况"徽标播放时不隐藏同规则：操作入口隐藏，身份标识保留）。
- **翻页防抖**：页面停稳后（约 300–400ms）才起播，避免快速左右滑动时产生声音碎片。
- **补齐音频焦点**：现有 Motion Photo 播放没有请求音频焦点，会直接盖在用户正在放的音乐上。新增 `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，并**同时补给 Live Photo**——这是同一个缺陷，没理由只修一半。

### D13 「同时」两档暂不设并发数量上限

手机竖屏非定制模式下详情附件是 **2 列方形网格**、每项边长 = 屏宽的一半（`AttachmentHelper.calculateImageSize`），1080p 上即 540×540；一屏典型可见 6–8 项。最坏情况是 8 路 540×540 GIF 软解同时跑，25fps 下位图填充量约 230 MB/s，且 GIF 无硬件解码。

仍然先不设上限：符合项目一贯的"真机实测定参"（ADR-0012 对帧率同样写"最终 fps 实测定"），且提前设上限会制造"为什么第 7 张不动"这种更难解释的现象。**发 debug 版真机实测帧率后再决定**；若需要护栏，候选是固定上限 6。

### D14 「关闭自动播放」档下保留手动播放：长按

长按即播放，**与拖拽排序共存**——真的触发了拖拽也无妨。

风险与化解：`ItemTouchHelper` 在长按瞬间就把项目"浮起来"跟手，若播放要求一直按住，几秒内手指微动就可能把附件挪位，而附件顺序会被自动保存写回记事。D15 的手感设计把这个风险消掉了。

### D15 详情页长按播放的手感：长按触发，松手继续播完

长按一下即可松手，播完一轮自动回静帧（不是"按住才动"）。

- **顺手消掉 D14 的风险**：拖拽刚起来就随松手结束，位置几乎不可能被挪动；"按住才动"则把手指按在拖拽最敏感的时间窗里。
- **语义与四档对齐**：四档已定义"播放一次 = 完整一轮"，长按就是手动触发同一件事。
- 长按播放在「关闭 / 逐一 / 同时播放一次」三档有效，「同时循环播放」档不响应（内容本来就在动）。
- 长按播放的内容与自动播放**完全相同**——GIF 播自身一轮，视频与 Live Photo 播那 3 秒派生 GIF。详情页**不引入真视频播放**。

### D16 文档落点：新开 ADR + 回指链接

新开一份 ADR 记录这次演进，并在 ADR-0007 / 0012 / 0014 末尾各加"更新"段落指过来。沿用项目已有先例（ADR-0007 末尾挂着指向 ADR-0012 的"更新（2026-06-30）"段）。

编号取 **0017**：设计会话中曾口头说"ADR-0015"，但 `docs/adr/` 里 0015（timely 倒计时数字）与 0016（FableSol 统一渲染器）已被占用。

**否决"只改现有三份"**：把"自动播放的界面分级"这套模型的第三次演进散在三处，读者读不到完整脉络；而 ADR-0012 那句"详情附件列表与全屏预览不受开关影响"会在原文里自相矛盾。

## 2026-07-25（首版真机反馈的四处修正）

### D17 逐一播放的队列必须按"此刻能否播"筛选

首版把**所有**可见项都排进队列，于是静态图片一旦排到队首就占住 `sequentialPlaying`——它永远不会发出"播完"的回调，队列就此卡死，它后面的动图再也轮不到。用户实测复现："可播放的附件中间穿插不能播放的图片时，走不完全部可播附件。"

修正：`pumpQueue` 改为循环出队，逐个用新增的 `ImageAttachmentAdapter.isPlayableNow(position)` 筛选，筛掉的直接丢弃；非逐一档在 `startForVisible` 里同样先筛一道。

- **判定必须以派生 GIF 是否真的存在为准**，不能只看"是不是候选"：`isMotionPhotoCandidate` 只看扩展名，普通 JPEG 也是候选，靠候选判定等于没筛。
- 视频 / Motion Photo 在生成期间被判为"此刻不能播"而落选，就绪时由新增的 `DetailAttachmentPlaybackController.onDerivedPreviewReady(position)` 重新入队——这正是 D9"未就绪不阻塞队列、就绪后按刚进入视口处理"的落地方式。
- 另加两道放行：Glide 加载失败、以及说好要播却拿回非 `GifDrawable`（解码退化成单帧）时，主动当作已播完放行。放行**必须 post 出去**——这些调用点都在 Glide 回调里，同步回调会重新绑定同一个 ImageView，而外层回调返回后 Glide 还要把旧资源塞进来，把刚绑好的画面顶掉。

### D18 静/动切换不清空当前画面

用户实测："逐一播放时上一个播完、下一个开始播放，下一个内容的画面会闪烁一下。"

原因是 Glide 起新请求时先 `onLoadCleared` / `onLoadStarted`，两者都把 ImageView 置成 placeholder（这里为 null），于是切换瞬间闪一下空白。修正：`.into()` 改用 `KeepCurrentImageTarget`——继承 `DrawableImageViewTarget` 并把这两个回调改成不动画面，旧图一直留到新资源就绪。

- **否决"把当前 Drawable 作为 placeholder 传进去"**：那个 Drawable 是上一个请求的资源，请求被清理时会被释放、其 Bitmap 可能回到 BitmapPool 被复用，而它还挂在 View 上。
- **否决"改用 CustomTarget 手动 setImageDrawable"**：会丢掉 Glide 的视图尺寸解析与随生命周期自动清理。
- 代价：Glide 清理请求时不再自动停动图。回收路径已由 `onViewRecycled` 与每次加载前的 `stopExistingGif` 兜住。

#### 2026-07-31 实现复盘

两份线上闪退日志和确定性回归证明，最后一条假设不成立：`stopExistingGif` 只能停止当前
`ImageView.drawable`，无法清空 `ImageViewTarget` 私有保存的 `animatable`。Glide 释放 GIF 后，
Activity 再次进入 `onStart` 时仍会对这个旧引用调用 `start()`，最终启动已经回收的 decoder。

D18 的产品目标“静/动切换不闪空白”仍然有效，但不得再通过吞掉
`DrawableImageViewTarget.onLoadCleared` 实现。最终修正恢复 `.into(imageView)` 的标准
`ImageViewTarget`，并在 holder 回收时显式调用 `Glide.clear(imageView)`；同一附件的静态/动态资源
切换使用独立 Bitmap 快照作 placeholder，快照不引用 Glide 管理的旧资源。

同时把“正在请求的 key”与“已经就绪的 key”分开：占位快照不能被误判为加载成功，失败后清掉请求
key 以允许重试，播放调度重复刷新也不会反复取消同一个在途请求。附件拖拽改用
`notifyItemMoved`，不再用 `notifyDataSetChanged` 清理整张网格的 target；重排后的播放状态只刷新
当前 attached holder，资源身份未变时走无加载快路径。

### D19 设置项的当前档位放标题下一行

首版是"标题在左、档位在右"的单行 56dp。中文下已经偏挤，德语/俄语等更长的译文会挤成两段。改为 `wrap_content` 的两行竖排（`minHeight="56dp"`，标题 16sp，档位 14sp / alpha 0.6 在下一行左对齐），控件也从 `RelativeLayout` 换成 `LinearLayout`（id 随之改为 `ll_autoplay_detail_dynamic_as_bt`）。

### D20 全屏视频页放开缩放

用户实测："视频在全屏预览时自动播放、长按播放都不支持缩放，但 live photo 可以。"

`ImageViewerActivity` 对视频页一直设着 `iv.isZoomable = false`——那时它只是一张不会动的封面帧，缩放没有意义。现在它会自动播放、长按还能接着看正片，且播放层本就通过 `setOnMatrixChangeListener` + `trackMotionZoom` 跟随 PhotoView 的 `displayRect`，缩放能力是现成的，只是被这一行挡着。改为 `true`，与 Motion Photo 一致。

按住播放期间的多指缩放也成立：`dispatchTouchEvent` 只在 `ACTION_UP` / `ACTION_CANCEL` 停止播放，第二根手指按下是 `ACTION_POINTER_DOWN`、第一根抬起是 `ACTION_POINTER_UP`，都不会误停。

### D21 播放状态重应用必须对运行中的 GIF 幂等（2026-07-31）

附件移动会重算“逐一播放”等调度状态；在 `RecyclerView` 布局、可见性监听与 posted 重排任务交错时，
同一个 attached holder 可能在很短时间内收到多次相同的播放决定。此时允许更新循环次数和播放完成
回调，但不得对仍在运行的同一个 `GifDrawable` 再调用 `startFromFirstFrame()`。

Glide 4.16.0 明确把该调用视为非法状态并抛出
`IllegalArgumentException: You cannot restart a currently running animation.`。因此：

- 已停止、从内存缓存取回的 GIF 仍从首帧启动，避免继承上次停止位置；
- 正在运行的 GIF 保持当前帧进度，只替换本次位置对应的动画回调；
- `refreshAttachedPlayback()` 等无加载快路径必须具备幂等性，不能把“重新应用状态”解释成
  “重新启动资源”。

这一约束与 D18 的 Glide 生命周期修复共同成立：前者保证运行态重入安全，后者保证资源释放后不会被
生命周期重新启动。

## 其它已定细则（未单列决策）

- **分享截图期间**（`mTakingScreenshot`）冻结到 D7 的静态代表帧。
- **设置项位置**：设置页放在 Cover Autoplay 那一项下方，用既有的 `ChooserDialogFragment` + `RadioChooserAdapter` 四选一，行内显示当前档位。
- **文案**：两项标题保持平行结构——现有"在记事列表中自动播放作为封面的 GIF 与视频"，新增"在记事详情中自动播放动态内容"。
- **生效时机**：`DetailActivity` 在 `onResume` 读取 `FrequentSettings` 重新应用（设置页与详情页一般不同时在栈上）。
- **视频"编辑外观"选帧**：只在编辑确认后触发派生 GIF 生成，拖动时间轴期间不触发，避免排一串作废的 WorkManager 任务。
