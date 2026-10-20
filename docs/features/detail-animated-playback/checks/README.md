# GIF 闪烁集成回归

## 2026-10-20 独立解码与真实首页性能

本组使用不同的独立包 `com.ywwynm.everythingdone.gifownervalidation`。运行前仍需本会话的
设备授权；不得安装同名正式包，也不得修改正式数据库。源码、私有媒体与原始日志在忽略
目录 `tmp/gif-owner-perf-20261020/`，结果摘要见
[owner-perf-results-2026-10-20.json](owner-perf-results-2026-10-20.json)。

1. `prepare_owner_perf.py` 冻结源文件和已有压力夹具；只在副本增加 owner 隔离开关。
2. 用 `:app:assembleDebug -I docs/features/detail-animated-playback/checks/owner-perf.init.gradle`
   构建，并复制 APK 到上述目录的 `fixture.apk`。正常源码与隔离源码切换后若出现增量编译
   的缺失类问题，可按已验证方式加带引号的 `'-Pkotlin.incremental=false'`。
3. `run_owner_perf.py <授权序列号> <唯一轮次名>` 执行同 APK 共享／独立 A/B，包含真实首页、
   12 个 GIF 的三种播放模式及重复返回；增加 `--large-home --after-only --reverse
   --require-mapped` 用于四个 35 MB 首页 GIF 的最终复核。`--trace` 可采集 FrameTimeline。
4. `analyze_owner_perf.py` 按实际输入区间统计窗口帧耗时、内存及 GIF 帧推进；不把窗口耗时
   或采样间隔反推为屏幕 FPS。OOM 即使被框架捕获、场景继续完成，也必须判失败。
5. 测试结束卸载本轮隔离包，并与最初测试前的原应用数据库／设置校验值比较。正常发布
   不能带此 init script，须核对正式 APK 包名、探针与私有媒体均未混入。

完整范围、三轮失败／成功证据及性能结论限制见
[复核记录](../owner-performance-2026-10-20.md)。以下为此前闪烁专项的独立流程。

只在用户明确授权的 `3B1629006YC00000`、`R5CW20BLNKL`、`192.168.100.67:40807` 上运行。测试包为 `com.ywwynm.everythingdone.gifflickervalidation`，探针拒绝在正式包名中运行。所有合成媒体和原始结果位于忽略目录 `tmp/detail-gif-flicker/`。

## 执行

在仓库根目录按 `.agents/rules/` 的工具路径调用：

1. `python docs/features/detail-animated-playback/checks/prepare_flicker.py`：生成首尾连续的 480×480／25fps 视频和隔离清单；Python 使用 `everythingdone` 环境。
2. `gradlew.bat :app:assembleDebug -I docs/features/detail-animated-playback/checks/flicker.init.gradle`。
3. `python docs/features/detail-animated-playback/checks/run_flicker.py <设备序列号> <唯一轮次名> --extended`。OPPO 的系统媒体权限须经实际权限界面授予；不会修改开发者权限开关或锁屏设置。
4. `python docs/features/detail-animated-playback/checks/analyze_flicker.py <轮次名> --verify`。旧实现应失败，新实现必须通过；仅 instrumentation 成功退出不代表像素断言通过。
5. 完成后卸载隔离包。正常构建及发布时不传 init 脚本，不打入测试媒体，不注册该探针。

## 判据与范围

- 真实详情页加载普通视频、动态照片与直接 GIF。动态照片的静态主图为黄色，内嵌视频为青色，用于明确识别静动资源切换。
- 每次预绘制读取实际 ImageView 的像素、Drawable 身份、帧索引和播放状态；循环至少跨两个边界，三次部分离屏返回，正式回收并清空 Glide 内存缓存后返回，再重新打开详情。
- 扩展检查验证离屏暂停且保持同一实例、正式回收停止、Activity stop/start、四档播放及重排。截图供界面核对，像素断言不以截图采样频率替代真实绘制回调。
- 每轮校验原应用数据库及偏好文件的 SHA-256，必须一致。媒体权限、测试记事和生成文件均位于隔离包。
- `inspect_existing_gifs.py` 只读抽查每台设备最多三个现有 v10 派生文件，排除先前大小固定的合成视频；私有媒体不进入 Git，仅归档帧统计。离线无空帧不能替代针对具体用户媒体的实际循环播放验收。
- 目前像素回归以默认详情布局为主，不宣称覆盖所有裁切、所有媒体或所有低内存淘汰场景。占位缓存未命中时仍需要正常异步加载。
- 补查已播过的 Glide 内存缓存：重新打开后记录首帧是否仍存在，再触发回收／返回。`warm-return` 同样不得出现透明帧。
- 在完成授权的现有文件抽检后，可用 `prepare_flicker.py --existing` 将三份媒体副本临时加入隔离包；探针随后追加 12 秒真实 GIF 连续循环。它们只进入忽略目录，不纳入 Git 或正常发布 APK。
- 另用 `run_flicker.py <设备序列号> <唯一轮次名> --large` 检查默认比例的通栏媒体，包括冷重载和已播过的内存缓存返回；大尺寸仍须通过同一像素断言。
- OPPO 首次启动可能先出现系统「Android 应用兼容性」提示，须按实际界面点击「确定」后再授予测试包媒体权限。该轮若因此超时，应保留失败结果并换轮次重测，不能当作播放测试通过。
- 探针仅在隔离包将旧版撤销／重做引导标为已看，避免首次使用提示遮住截图；原应用偏好仍逐文件校验不变。

## 逐一播放、真实键盘与大 GIF

- `run_flicker.py <序列号> <轮次> --sequential --record` 使用真实 IME、真实触摸注入，比较键盘关／开。每组 56 次快速甩动，40ms 间隔，含惯性中反向；记录实际时长、滚动位移、IME 与 settling 状态。触摸避开工具栏覆盖区，IME 稳定且确实观测到媒体才算覆盖。
- `prepare_flicker.py --existing --complex` 追加 960×720／25fps 高细节视频，经设备生产编码生成约 32–35 MB 的 GIF；`run_flicker.py ... --sequential --complex --fullspan --record` 使用 12 个动态附件、17 张静态图、80 段正文和首项通栏。九个较大 GIF 是三份已授权真实派生文件的独立路径副本，测试资源仍只存在 tmp 和隔离包。
- 通栏只支持第一个附件。`fullspan-measured` 必须确认实际宽度超过列表的 90%；不能仅凭 fullSpanEnabled 设置判定通栏已覆盖。
- `analyze_sequential.py <轮次...> --verify` 检查所有可见动态项的空白、透明度、误运行和同帧并发；确认 IME 快滑阶段样本与多附件覆盖。先遍历复杂页面加载媒体，再采集回收后的画面；首次解码等待不与已显示后闪空白混为一谈。
- `run_flicker.py ... --sequential --queue` 在真实 RecyclerView 中替换解码边界，逐次记录生产控制器发出的起播决策；检查批量离屏不启动已离屏项、返回按索引、正常推进以及完成后仍可见不重排。旧实现前向起播 `[1,2,3,4]`、反向 `[6,7,0]`，修复后分别为 `[4]`、`[0]`。
- `run_flicker.py ... --extended --complex` 补验大文件默认循环、四档、回收、Activity stop/start 与重排；仍用 `analyze_flicker.py --verify` 判定。
- `archive_sequential.py` 汇总本轮证据到 `sequential-results-2026-10-15.json`；发布校验完成后再传 `--publication`。原录屏和逐帧记录不进入 Git。
- runner 和分析脚本均把 OOM、致命异常、已回收资源使用当作失败；无空白不能掩盖没有真正起播。动态采样记录 buffer.isDirect，确认本地大 GIF 实际使用文件映射。循环采样先等待全部可见项完成首次加载，再以实际两次回绕作为结束条件，避免首载等待误报和固定墙钟时间覆盖不足。

## 大 GIF 混排的循环交界与窗口像素

- `run_flicker.py <序列号> <轮次> --complex --boundary` 把静态图穿插在 12 个动态附件之间，上／中／下三处视口各持续循环 45 秒，再观察逐一播放完成。当前探针先清空隔离包 Glide 磁盘缓存，保留派生 GIF；避免反复测试仅因静态磁盘缓存变热而通过。
- 加 `--fullspan` 后首图通栏，三处各循环 25 秒；随后切到逐一播放，打开真实键盘并执行 74 次快速触摸，最后检查通栏重新进入与播放完成。
- `DetailGifWindowCapture` 读取实际应用窗口，宽度缩放为 480 像素，在每个媒体内部取 16×16 点，记录循环帧索引与请求／完成时刻。背景色超过 10% 为疑点，保留附近窗口 PNG；同时保留 ImageView 空白记录。PixelCopy 不含独立 IME 窗口，也不保证捕获每次物理屏幕刷新；录屏用于确认实际键盘与滚动场景。
- `analyze_boundary.py <轮次...> --verify` 要求稳定循环、逐一完成、快速返回和后续甩动均无控件空白、无窗口露底疑点，并核对原应用数据和异常日志。滚到从未加载的附件时出现的初次加载底色单列；已经看过的附件跨播放模式返回时不能据此排除空白。
- PixelCopy 的 `ERROR_SOURCE_NO_DATA` 单列为采样缺失，不计为空白或成功画面；未知错误、没有成功截图、任一实际回绕或完成交界前后 160ms 缺少窗口采样均使验证失败。`captureComplete` 另行报告截图请求是否全成功，不能用功能判定通过掩盖采样缺口。
- `archive_boundary.py` 汇总 `boundary-results-2026-10-15.json`；带 `--publication` 时补充本轮发布校验。原始 GIF、录像、窗口截图和逐次像素记录均保留在忽略目录。
