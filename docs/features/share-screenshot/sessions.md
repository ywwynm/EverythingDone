# Share Screenshot Sessions

## 2026-07-26 - 动态附件分享可行性评估

- 用户提出：长截图分享后，记事里的视频、Motion Photo、GIF 等附件只是截图的一部分，
  不会动，问是否有办法让它们在分享后的内容里也动起来。
- 盘点现状：分享产物是 `ScrollView` 一次性 `draw(Canvas)` 得到的单张 JPEG，没有时间
  维度；`ImageAttachmentAdapter` 的 `mTakingScreenshot` 还会刻意冻结动画到静态代表帧
  （`:270`、`:275`），在单帧产物的前提下这个设计是对的。
- 盘点基础件：帧提取（`VideoCoverPreviewManager.decodeFrame`、Glide 的
  `StandardGifDecoder`）、GIF 编码（`androidndkgif`）、逐帧裁切
  （`MediaCropTransformation`）全部现成；唯一缺口是动态 WebP 无公开逐帧 API。
- 确认合成方式可行且不必逐帧重绘视图树：`rv_image_attachment` 是 `wrap_content` +
  关闭嵌套滚动（`DetailActivity.kt:830`），长截图时全部 ViewHolder 齐全，可取矩形后
  在静态底图上做局部换帧，全程后台线程。
- 用 ffmpeg + 合成长截图做了体积基准（脚本落在 `tools/`）。关键数字：整图 GIF 在
  1080×4400 下达 10.65 MB，拆解后确认 GIF 首帧只占 1.12 MB、帧差分本身有效，
  9.53 MB 全部来自动态附件的差分帧（约 64 KB/附件/帧），单位成本约是 H.264 的 14 倍；
  同内容 H.264 整片 0.75 MB，720 宽 / 15fps 的滚动视频 0.61 MB。
- 结论写入 [animated-share-evaluation.md](animated-share-evaluation.md)：整图 GIF
  （体积）、整图 MP4（1:4 竖长形态）、动态 WebP（无系统编码 API + IM 不认）三条排除；
  推荐先做多文件分享（`ACTION_SEND_MULTIPLE`，视频挂已缓存的派生 GIF 或原视频），
  再评估「分享为滚动视频」作为并列入口。
- 用户追问两点：多文件分享是否该挂原视频、能否用 MediaCodec 硬编码更快；以及能否生成
  「布局与高度和长截图一致、附件位置在自动播放」的整图长视频。追加评估写在同一份文档的
  「追加评估」一节：
  - 挂原视频**不需要任何编码**，直接挂 FileProvider URI 即可，比挂派生 GIF 还快；
    `file_provider_paths.xml` 现有配置已完整覆盖，无需改动。代价是语义变化（长截图只呈现
    选定的 Thing Card Video Frame，原片是全部）、体积时长、多附件时的文件数。
  - 整图长视频可行。实测 H.264 标准层面 1080×4400 是 Level 5.0、1080×8000 是 Level 5.1，
    1080 宽下高度上限约 8600 px；真正限制在硬编器（常见 4096）与 `GL_MAX_TEXTURE_SIZE`，
    需运行时查询后等比降级。必须走 `createInputSurface()` + EGL/GL。
  - 关键结论：**瓶颈在取帧不在编码**。硬编 75 帧 2–4 秒，而 `getFrameAtTime` 是 ~90 ms/帧；
    改用已缓存的派生 GIF 作帧源（规格正好是 3s / 25fps / 长边 720）后每帧 5–10 ms，
    整体 3–6 秒。这条同时补掉了动态 WebP 无逐帧 API 的缺口。
  - 形态问题量化后修正了上一轮的判断：正文 20dp、标题 22sp，density 3.0 / 1080×2400 屏上
    全屏 fit 的缩放 `k = 2400/H`，按正文 12dp 作下限得临界长图高度 **4000 px**（阈值取 4096
    与编码器/texture 上限对齐）。典型记事高 1700–3000 px，多在范围内，故整图长视频对多数
    记事可用，仅超长记事需降级为滚动视频。
    （2026-07-26 追加：用户追问 `k` 的来历，B5 补上完整推导
    `k = min(播放区宽/视频宽, 播放区高/视频高)` → 视频恒比屏幕更竖故 `k = 2400/H` →
    `正文有效 dp = 20k = 48000/H`，并列出三个隐含假设。要点：**结果只取决于接收方屏幕的
    dp 尺寸、与 px 分辨率无关**——720×1600@2.0 与 1080×2400@3.0 同为 360×800 dp 故同样得
    12 dp，而 1440×3200@3.5 是 411×914 dp、`k=0.8`、正文约 13.7 dp 反而更清楚。故 4000
    这条线对应「800 dp 高的主流屏」，在更高的屏上偏保守。
    用户续问「内容宽度为什么非缩不可、不能保留原始宽度」，补充说明：**缩放是接收方播放器
    按 contain 做的，我们对那一侧没有控制权**——1080×4000 要完整显示在 1080×2400 屏上就
    必须压到 0.6，硬保 1080 宽则高度超屏、内容看不全。故该缩放是「一次性呈现全部内容」的
    内在代价、规避不掉，而滚动视频只要视口高 ≤ 接收方屏高，`k` 就被宽度项钳在 1、文字是
    原始 20 dp。「内容一高就走滚动视频」由此从权宜之计变成形态上的正解。）
- 用户澄清「挂原视频」指的是**在生成的长视频内部**用原视频画面替代派生 GIF，而非
  `ACTION_SEND_MULTIPLE`；并追问 HDR 能否保留、动态照片是否 HDR 与动态二选一、分享是否
  需要多个选项。第二轮追加评估：
  - **内嵌原视频可行且管线更顺**：解码器 → `SurfaceTexture` → GL external texture → 编码器
    input surface，全程在 GPU、不落 Bitmap，比解派生 GIF 再上传还快。新增四个约束：并发
    解码器实例数（需按 `getMaxSupportedInstances()` 降级）、总时长决策（建议上限 12–15 秒）、
    音轨（v1 建议不带）、帧率对齐。代价是耗时体积随时长线性放大，估 20–40 秒 / 4–6 MB，
    必须换成带百分比的进度。
  - **HDR 结论：长视频一律 SDR**，是把 ADR-0006 的既有立场延伸到分享面。三层理由：
    gain map HDR 与视频 HDR 是两套体系、需自己实现 UltraHDR 合成 shader；必须 HEVC Main10
    HDR10 编码 + EGL BT2020 扩展，设备支持面窄；混合内容编成 PQ/HLG 后在 SDR 播放器与
    IM 转码后会发灰偏色，比出 SDR 更差。
  - **动态照片确实 HDR 与动态二选一，且比「选择」更绝对**：ADR-0014 已确认该取舍，根因是
    Motion Photo 静态主图可带 gain map 而**内嵌视频本身是 SDR**；`MediaMetadataRetriever`
    取帧也永远是 SDR。所以动态源里根本没有 HDR 版本可保。
  - **唯一可能保住 HDR 的是静态长截图本身**（现在走软件 `Canvas` 必然丢 gain map），
    改硬件 Canvas + 局部 gain map 可出 UltraHDR JPEG，记入待办、本轮不做。
  - **分享入口收敛为四个**（长截图 / 长视频 / 滚动视频 / 附件单独分享），动态源、编码尺寸、
    长视频与滚动视频的取舍、HDR 全部由代码自动决策不问用户；只有长视频时长上限与是否带
    音轨值得进设置。
- 用户定下两条硬约束并要求梳理选项：**分享产物与中间产物一律不得使用 GIF**、
  **并发解码器超限时排队等待而非降级**；同时要求把播放时长、音轨、附件选择、编码器、
  CRF/QP 等做成动态可见的选项，放进分享对话框。选项梳理写入 [plan.md](plan.md)，
  本 feature 范围由「长截图」扩展到分享产物整体。要点：
  - 四个产物入口（长截图 / 长视频 / 滚动视频 / 附件单独分享），现有
    `TwoOptionsDialogFragment` 只能放两个 action，需替换。
  - 选项分四组：附件选择、动态内容（时长 3 秒或原时长、编排、音轨）、编码
    （编码器 / 画质 / 输出宽度 / 帧率）、音轨细节。
  - **CRF/QP 无法直接暴露**：MediaCodec 只有 `BITRATE_MODE_CQ|VBR|CBR`，CQ 的硬编
    支持面窄且 `KEY_QUALITY` 各厂商语义不一。改为给画质档位，内部映射 CQ 或退回 VBR
    按像素×帧率×bpp 算码率；想要数字入口就暴露目标码率。
  - **动态 WebP 逐帧是禁用派生 GIF 后暴露的现存缺口**：`AnimatedImageDrawable` 无
    seek API，项目也无 WebP 解码依赖（已核对 build.gradle）。解法是加
    `com.github.zjupure:webpdecoder`，或用 `ImageDecoder` 实时采样。是第一期前置依赖。
  - **排队与「同时播放」在长视频里矛盾**（输出是单一时间轴，同一输出帧需要所有附件
    的同刻画面）。给出三层策略：直接并发（附件上限 9、视频通常 1–3 个，
    `getMaxSupportedInstances()` 常报 16+，绝大多数够用）→ 超限则串行预转码成 cell
    尺寸小 MP4 再并发合成（这才是「排队」的落点）→ 仍超限则预渲染 JPEG 帧序列落盘、
    零解码器实例。全程无 GIF。「逐一播放」档天然只需一路解码器。
  - 「原时长」的代价按 1080×4400 / 30fps 列表：120 秒 = 3600 帧、编码 90–180 秒、
    体积 30 MB，须在 UI 上如实呈现并换成带百分比与取消的进度。
  - 播放编排直接复用 `DetailAutoplayMode` 的 `ALL_*` / `ONE_BY_ONE` 语义。
- 用户追问帧率与分辨率差异如何处理，并定下三条规则。补进 plan.md 的「合成管线」一节：
  - **帧率不同不需要预解码**：输出定固定帧率，各附件维护「下一帧 PTS」，渲染每个输出帧前
    推进到 `PTS <= t_out` 的最后一帧（nearest-previous / frame-hold），即任何播放器以
    60Hz 播 24fps 视频的做法。不插值、无对齐算法，只比较 PTS。因此「先一个个解码再对齐」
    可行但不必要——预解码的唯一用途是绕开并发上限，与帧率问题正交，常态走流式零中间产物。
  - **分辨率 / 宽高比差异由纹理坐标矩阵消化**：附件矩形是布局定死的，
    `SurfaceTexture.getTransformMatrix()` 已处理 crop rect 与 stride padding，
    左乘既有裁切矩阵即得采样坐标，任意源尺寸到目标尺寸由 GPU 采样免费完成。唯一必须做的是
    读 `KEY_ROTATION` 合进矩阵，否则竖屏拍摄的视频会躺倒；`KEY_PIXEL_ASPECT_RATIO` v1 忽略。
  - 已定：**动态 WebP 不做**（v1 动态源只覆盖视频 / Motion Photo / GIF）；**短视频播完停尾帧**
    （frame-hold 的自然结果，零额外代码）；**滚动视频「播完再滚」**——滚到附件行完全进入
    视口即停、并发播完该行最长者再续滚，相邻行合并停顿点，到底后停留 1 秒收尾。
- 用户指出保留布局并不省事：详情附件网格是 `CENTER_CROP`、cell 由布局定死，1920×1080 的
  视频裁进方形 cell 左右各切 420 px，**43.75% 的画面被切掉**。据此新增「**是否保留记事布局**」
  选项（组 2b）：
  - 澄清**不保留布局并不能消除分辨率差异**——输出仍是单一 MP4、尺寸固定，编码器不能中途
    改分辨率，只是把「适配到哪里」从方形小 cell 换成自选画布，裁切损失从 43.75% 降到近 0。
  - 画布策略经两轮修正定稿。初稿写「方向按选中附件多数决」「居中留底」，既含糊又方向错误；
    用户指出**画布永远是竖的**——接收方竖着拿手机看，横画布在竖屏里只会缩成中间一条。
    定稿：**画布固定 9:16（1080×1920），不提供任何比例选项，不做方向推断**；每个附件
    **按宽度填满**（`scale = 1080 / 源显示宽`，须先按 `KEY_ROTATION` 校正显示宽高）、垂直居中；
    源宽小于 1080 也放大到填满，糊是「填满宽度」的既定代价；比画布更竖的源（如 1080×2400）
    填满宽度后超高，居中裁上下；上下空出部分填记事强调色 / 渐变背景。「原始分辨率」实现上
    理解为「不做超出必要的裁切」而非按源尺寸输出。
  - 中途把「不保留布局」误解成**时间上的轮播**（一次一个附件全屏、播完切下一个），据此推出
    「固定 9:16 画布」，被用户否掉。澄清后的正确模型：**「保留 / 不保留布局」只决定内容
    怎么排，与「整图画布 / 滚动视口」正交**，是 2×2 而非并列的三种形态；「一个个放」是
    **空间上的垂直堆叠**，不是时间轮播。文字部分两种排法都原样留在顶部，此前「文字如何处理」
    那个待拍板问题随之消失。
  - **不保留布局 = 满宽垂直堆叠**：每个附件占满 1080 宽、按原比例依次向下排，零裁切。
    代价是内容显著变高（文字按 500 估）：3 个横屏视频 2324，1 横 2 竖 4948，
    3 个竖屏 6260，而保留布局一律 1540。
  - **长视频可用性改为按设备实际编码能力判定**（用户裁定）：查
    `VideoCapabilities.getSupportedHeightsFor(1080)`，超上限就禁用长视频入口并提示改用
    滚动视频，**不做降宽度规避**（会把文字一起缩糊）。另有一条 4000 px 的线性质不同：
    技术上编得出来但成品文字跌破 12 dp，**警告但不阻止**（用户可能只在乎附件动不动）。
    该线的实际价值取决于设备——编码器上限 4096 时警告区间只有 96 px，上限 8192 时才常用。
    因此不保留布局天然配滚动视频，配长视频只在全横屏或附件很少时成立。
  - **滚动视频视口高度 = 所有附件满宽后的最大高度，不设下限**（用户否掉了初稿的 1920 下限）；
    矮于视口的附件居中、上下填黑。后果：全横屏素材会得到 1080×608 的扁视口，接收方竖屏上
    上下大片黑边、滚动总时长变长，但文字清晰度不受影响（视口宽恒为 1080，全屏播放是 1:1 映射）。
  - **不单列「轮播」形态**：时间上逐个全屏的效果，滚动视频配合「播完再滚」已覆盖。
  - 分期据此重排：一期长视频 + 保留布局（几何现成、内容最矮、最不易撞上限），一期半加
    满宽堆叠，二期滚动视频，之后才是选项面板、音轨、多文件分享、并发兜底。
- **用户裁定第三条硬约束：不以接收方观感设限。** 产物在接收方屏幕上显示多大、看不看得清
  是接收方的事——他可以双指放大、可以保存后换播放器，不构成我们生成什么的约束。只有
  **生成侧的真实成本**（编不出来、体积、耗时）与**功能性失败**（如 IM 把大动图转成静态、
  动画直接没了）才算约束。据此回溯并推翻了四处决策：
  - **4000 px 那条可读性警告线整条删除**；长视频入口的可用性只剩一个条件——内容高度
    ≤ `getSupportedHeightsFor(1080)`，没超就可用、多高都不拦。
  - **第一轮「方案 2 — 整图 MP4 ❌」的排除理由作废**（原文「1:4 竖长视频缩略图被裁、
    播放被 letterbox 成一条、文字不可读」全是观感）。它就是后来的「长视频」主入口。
  - **滚动视频的「天然 1:1、文字更清楚」这一卖点作废**；其真实价值收敛为两条：内容高度
    超过编码器上限时的唯一选择，以及提供另一种呈现节奏。
  - **HDR 的 D3 理由（SDR 播放器上发灰偏色）不再单独作数**，但结论不变——D1 实现成本、
    D2 设备支持面已足够，最硬的是源里根本没有 HDR（Motion Photo 内嵌视频与
    `MediaMetadataRetriever` 取帧都是 SDR）。
  - 不受影响的：GIF 的排除（体积 + IM 转静态属功能性失败）、画布竖屏与满宽堆叠（结论对，
    理由改成「内容本身就是竖的」而非「接收方竖着拿手机」）、全部体积与耗时判断。
  - evaluation 文档中被推翻的段落一律保留原文并加引用块标注作废，不删除，便于追溯。
- 本次仅评估与规划，未改动任何应用代码。

## 2026-06-15 - Re-share screenshot into EverythingDone

- User reported that after sharing a Thing as a long screenshot and choosing
  EverythingDone's create-Thing target from the system share dialog,
  EverythingDone could not read the generated long screenshot.
- Diagnosis narrowed the issue to the receiving side rather than screenshot
  generation. `ScreenshotHelper.ShareCallback` now exposes the screenshot as a
  FileProvider `content://` URI, but `DetailActivity.setupThingFromIntent()`
  only tried `UriPathConverter.getLocalPathName(...)` before adding the shared
  media as an attachment.
- `UriPathConverter` depends on filesystem paths or `_data` columns. A
  FileProvider URI is stream-oriented and does not expose `_data`, so the old
  receive path could drop the attachment even though the screenshot file and
  URI grant were valid.
- Changed `DetailActivity` to read incoming share URIs from `EXTRA_STREAM`,
  `Intent.data`, or `ClipData`, deduplicate multiple sources, and convert each
  URI through a helper shared by single and multiple media shares.
- The helper now keeps the old direct-local-path path when available. When the
  path cannot be resolved, it infers a media postfix from
  `ContentResolver.getType(uri)` or the share intent MIME type, copies the
  shared URI stream into the app's temp media file area with
  `FileUtil.copyUriToFile(...)`, and then creates the existing type/path/name
  attachment token.
- Verification: `git diff --check` passed with the repository's existing
  LF/CRLF warning for `DetailActivity.kt`, and
  `.\gradlew.bat :app:assembleDebug --console=plain --no-configuration-cache`
  passed before publishing. `.\gradlew.bat :app:publishDebugUpdate
  "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain
  --no-configuration-cache` then passed and published debug update
  `202606150622`; both the local and remote `latest.json` parsed as JSON
  objects with `debugUpdateCode=202606150622`. No device-side chooser loop was
  available in this agent environment, so the app-published build should be
  used to verify the exact OEM/system share dialog path.

## 2026-06-15 - Sharesheet preview URI compatibility

- User reported that after sharing a Thing as a long screenshot, the system
  share dialog's image preview looked blank on OPPO ColorOS.
- Checked Android's official Sharesheet and FileProvider documentation. The
  app already shared a `content://` URI through `Intent.EXTRA_STREAM` with
  `image/jpeg` and `FLAG_GRANT_READ_URI_PERMISSION`, but the target intent did
  not explicitly set `ClipData`, `data`, `EXTRA_TITLE`, or read permission on
  the chooser intent itself.
- Hypothesis: the receiving share sheet can send the screenshot correctly, but
  the system preview path is less compatible than target apps because it has to
  open and decode the image before the final share target is chosen. ColorOS may
  be stricter about `ClipData`/chooser grants, or it may fail while decoding a
  very tall JPEG preview.
- Changed `ScreenshotHelper.ShareCallback` to attach the generated screenshot
  URI as `EXTRA_STREAM`, `ClipData`, and intent `data`, keep the concrete
  `image/jpeg` MIME type, set `EXTRA_TITLE` when available, and add
  `FLAG_GRANT_READ_URI_PERMISSION` to both the send intent and chooser intent.
- If ColorOS still shows a blank preview after this compatibility pass, the
  next mitigation is to generate a separate small preview thumbnail URI for the
  sharesheet while continuing to share the full long screenshot through
  `EXTRA_STREAM`.
- Verification: `git diff --check` passed with the repository's existing
  LF/CRLF warnings, and
  `.\gradlew.bat :app:publishDebugUpdate "-PdebugUpdateNotesFile=memory/debug-update-notes.md" --console=plain --no-configuration-cache`
  passed and published debug update `202606150347`. The remote `latest.json`
  was re-read as a JSON object with `debugUpdateCode=202606150347`.
