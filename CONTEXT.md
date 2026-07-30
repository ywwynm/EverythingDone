# EverythingDone

EverythingDone is a personal task and note application where each thing carries its own colour identity while app chrome provides the surrounding navigation and settings experience.

## Language

**Thing**:
A user-created note, task, reminder, habit, goal, or related item whose own background colour is part of its identity.
_Avoid_: note as the blanket term for all things

**Trashed Thing**:
已进入回收站、可恢复或永久删除的 Thing。
_Avoid_: completed Thing

**Pre-Trash Thing State**:
Thing 进入回收站前的正在进行或已完成状态；从回收站恢复单个 Thing 时应回到这个状态。
_Avoid_: always restore to underway

**Doing Thing**:
当前唯一正在被计时专注的 Thing，由全局 `App.doingThingId` 标识；进入计时（DoingActivity）时设定，停止计时时清除。它在列表中的 Thing Card 上覆盖一层"正在做"蒙层。它与位置和文件夹归属无关，移动或重排不改变其计时状态。
_Avoid_: ongoing-notification Thing, every underway Thing, 把"正在做"当成一种状态筛选

**Ongoing Thing**:
被固定为常驻通知的 Thing，由 `KEY_ONGOING_THING_ID` 标识；它与 Doing Thing 是两个独立概念，状态变更时清理的是它的常驻通知，而不是 Doing Thing 的计时。
_Avoid_: 把 Ongoing Thing 与 Doing Thing 混用

**Private Thing**:
内容被保护、需通过身份验证才能查看的 Thing；标题（若有）仍然可见，列表中显示为带锁的彩色卡片，凭锁与颜色辨识。
_Avoid_: 隐藏记事、把"私密"理解为连标题一起隐藏

**Thing Folder**:
A user-created container that groups Things and may itself live inside another Thing Folder.
_Avoid_: note folder, category, tag

**Private Thing Folder**:
A Thing Folder whose folder identity, contained previews, and descendant content presentation are protected while the descendants remain inside it.
_Avoid_: secret category, hidden tag

**Effectively Private**:
因自身私密、或位于某个 Private Thing Folder 之内而被保护展示的 Thing 或 Thing Folder；保护持续到所属私密范围通过身份验证为止。
_Avoid_: 把"位于私密文件夹内的条目"误解为它自身已被标记私密

**Trashed Thing Folder**:
已进入回收站、保留子结构并可恢复或永久删除的 Thing Folder。
_Avoid_: archived Thing Folder, removed category

**Projection Folder**:
在状态或类型筛选列表中因为子树存在命中内容而显示的 Thing Folder；它可以只是路径容器，不代表该文件夹本身拥有同一状态。
_Avoid_: completed folder, deleted folder as blanket terms

**Trash Root Entry**:
回收站根列表中直接显示的 Thing 或 Thing Folder 投影条目；它可以是真正进入回收站的对象，也可以是因为子树包含回收站内容而显示的路径容器。
_Avoid_: every trashed descendant as a root item

**Empty Thing Folder**:
A Thing Folder with no child Things and no child Thing Folders.
_Avoid_: invalid folder, deleted folder

**Thing Folder Path**:
The ordered chain of Thing Folders from the top-level list to the current nested Thing Folder.
_Avoid_: breadcrumb as the domain term

**Thing Scope**:
用户查看 Things 时选定的容器范围，可以是全部记事范围，也可以是某个 Thing Folder 范围。
_Avoid_: underway root, status destination

**All Things Scope**:
不限定 Thing Folder 的 Thing Scope，中文界面显示为“全部记事”。
_Avoid_: all statuses, all types, underway

**Trash**:
展示 Trashed Things 和 Trashed Thing Folders 的独立区域，不是 Thing Scope，也不是日常状态筛选项。
_Avoid_: finished status, deleted status filter

**Thing Background**:
The colour or gradient owned by a Thing and used as the highest-priority visual background when displaying that Thing.
_Avoid_: app theme colour, page background

**Thing Background Information**:
A user-facing description of a Thing Background's colour identity, including its recognised colour name and numeric colour values.
_Avoid_: app theme information, debug colour data

**Thing Foreground**:
Text, icons, and other adaptive foreground content drawn directly on top of a Thing Background or Thing Card Media Background. Its colour is chosen from the visible Thing-owned background and does not depend on Appearance Mode.
_Avoid_: dark-mode foreground

**Thing Background Glyph Colour**:
字形本体直接使用 Thing Background 的纯色或渐变来表达 Thing 身份；可读性辅助层应接近宿主背景的反向明暗（偏白或偏黑），但可混入 Thing Background 的色味，不能替代主字形色。
_Avoid_: 把它当成普通 Thing Foreground、为了对比度把主字形色直接改成黑色或白色、使用完全脱离 Thing 身份的辅助光

**Thing Card**:
A compact card representation of a Thing, used by the home list and embedded single-card surfaces such as Doing and noticeable reminder surfaces. Its layout may differ from the Detail screen while preserving the Thing's identity.
_Avoid_: note card as the blanket term

**Thing Folder Card**:
A compact card representation of a Thing Folder on a list surface.
_Avoid_: folder row, note folder card

**Legacy Placeholder Thing**:
A system-created Thing used historically to show welcome or empty-list guidance instead of user-owned content.
_Avoid_: welcome note, empty-list note, ordinary Thing

**Empty-List Guidance**:
An app-owned guidance message shown when a Thing and Thing Folder list projection has no visible user-owned content.
_Avoid_: placeholder Thing, empty note

**Full-Span Thing Card**:
A Thing Card that is intentionally presented with a wider card span as a persistent presentation preference of that Thing.
_Avoid_: temporary wide row, per-filter layout state

**Thing Card Span Mode**:
The persistent presentation choice that determines whether a Thing Card uses normal span or full span in surfaces that support wider cards.
_Avoid_: complete layout style, image placement mode

**Thing Card Appearance**:
The set of persistent presentation choices that control how a Thing Card is visually arranged and how its media is shown.
_Avoid_: media settings only, card content editing

**Thing Card Surface Projection**:
The rendered form of a Thing Card Appearance after a card-based surface applies its own size and content constraints.
_Avoid_: rewriting Thing Card Appearance, widget-specific saved appearance

**AppWidget Size Preset**:
A launcher-visible default size choice for an AppWidget entry.
_Avoid_: runtime widget size, stored widget instance identity

**Thing Card Appearance Update Time**:
The time when a Thing's Thing Card Appearance was last changed.
_Avoid_: content update time, reminder update time

**Thing Card Image Placement**:
The persistent presentation choice that determines where Thing Card Media appears within a Thing Card relative to the card's other visible content.
_Avoid_: attachment order, image crop ratio, image crop focus

**Thing Card Media**:
The image or video thumbnail used as the visual media for a Thing Card.
_Avoid_: image-only card thumbnail, attachment preview as a blanket term

**Thing Card Media Source**:
The persistent presentation choice that determines which image or video attachment provides Thing Card Media.
_Avoid_: first attachment, attachment order

**Thing Card Video Frame**:
The video frame selected to provide Thing Card Media for a video attachment.
_Avoid_: playback position, video attachment itself

**Thing Card Video Preview**:
由一个视频 Thing Card Media Source 派生、在 Thing Card 上循环播放的动态预览；以该来源的 Thing Card Video Frame 为循环起点。它能否真正逐帧播放，取决于显示它的界面是否支持，以及用户是否开启了封面动态内容的自动播放。
_Avoid_: 把视频本身当成 Animated Image、把它与静态的 Thing Card Video Frame 混为一谈、把它误解为改变了视频文件本身的播放

**Thing Card Media Crop**:
The persistent presentation choice that determines the crop center, user zoom, and when applicable source crop shape of Thing Card Media.
_Avoid_: image placement, centerCrop, editing the attachment file

**Thing Card Media Target**:
The area of a Thing Card where Thing Card Media is drawn.
_Avoid_: source crop, attachment file size

**Thing Card Media Target Aspect Ratio**:
The persistent presentation choice that determines the shape of the Thing Card Media Target.
_Avoid_: crop ratio, image width percent, card height percent

**Thing Card Thumbnail Crop**:
The Thing Card Media Crop used when Thing Card Media is displayed as a top or bottom foreground thumbnail within a Thing Card.
_Avoid_: background crop, side media crop, image placement

**Thing Card Side Media Crop**:
The Thing Card Media Crop used when Thing Card Media is displayed in a Thing Card Side Media Panel.
_Avoid_: thumbnail crop, background crop, side width

**Thing Card Media Background Crop**:
The Thing Card Media Crop used when Thing Card Media is displayed as a Thing Card Media Background.
_Avoid_: thumbnail crop, side media crop, replacing the Thing Background

**Thing Card Media Background**:
A Thing Card presentation choice where Thing Card Media is drawn as the card's visual background behind Thing Foreground.
_Avoid_: app background image, replacing the Thing

**Thing Card Media Background Mask**:
The overlay that sits on top of Thing Card Media Background so Thing Foreground remains readable.
_Avoid_: app dark overlay, selection cover

**Thing Card Side Media Panel**:
The full-height media target used when Thing Card Media is placed on the left or right side of a Thing Card.
_Avoid_: intrinsic image-size thumbnail, partial side thumbnail

**Thing Card Bottom Status**:
A Thing Card 上承载音频、提醒/目标、习惯信息的区域。当卡片内容区高于正文自然高度时（侧栏媒体撑高、媒体背景按比例撑高等），它锚定在卡片内容区底部，而不是紧跟正文向下排。提醒类与目标类共用同一段状态显示，习惯是另一段。
_Avoid_: 把它当成正文的一部分、把它固定排在标题/正文正下方、把"目标"当成独立于"提醒"的状态块

**Detail Attachment Media Appearance**:
The persistent presentation choices that control how a Thing's image and video attachments are shown inside the Detail screen attachment list.
_Avoid_: Thing Card Appearance, attachment file editing

**Detail Attachment Media Crop**:
The persistent presentation choice that determines crop center and user zoom for an image or video attachment shown inside the Detail screen attachment list.
_Avoid_: Thing Card Media Crop, editing the attachment file

**HDR Media**:
An image or video attachment whose file carries high-dynamic-range information — for an image, an embedded gain map; for a video, an HDR transfer signal (HLG/PQ).
_Avoid_: wide-gamut image, bright image, edited photo

**HDR Display**:
A surface actually rendering an HDR Media's full brightness boost, as opposed to showing only its SDR base image.
_Avoid_: HDR support as a blanket term, wide colour gamut

**HDR UI 渲染**:
界面实时生成的图形在兼容设备上保持 SDR 基线不变，并让受几何与光照约束的局部高光区域使用
SDR reference white 以上亮度的渲染方式。它不要求存在 HDR Media，也不等同于 Display P3 等
广色域输出。
_Avoid_: 整体提亮 SDR 界面、把广色域称为 HDR、按设备改变几何或身份色

**平均画面亮度（APL）**:
FableSol 可见区域在一段画面中的平均亮度，用于发现整体意外变暗或变亮；它不表示局部高光峰值，
也不直接代表水体是否显得晶莹。
_Avoid_: 把 APL 当作 HDR 峰值、把“整体更亮”默认解释为抬高全部水体亮度

**主观晶亮感**:
水体由清晰而集中的局部反射、受光浪峰及其明暗对比形成的视觉感受。它可以在平均画面亮度不升、
甚至小幅下降时增强，不等同于全局补光、宽光晕或 HDR 能力本身。
_Avoid_: 整体泛白、扩大柔光、把 APL 上升当作晶亮感上升

**Animated Image**:
一个本身就是动图的图片附件——GIF 或动态 WebP;它能否逐帧播放取决于显示它的界面。
_Avoid_: GIF as the blanket term, video attachment

**Animated Playback**:
某个界面真的在逐帧播放一个 Animated Image 或 Thing Card Video Preview,而不是只显示它的第一帧 / 单帧。
_Avoid_: GIF support as a blanket term, autoplay as a file property

**Cover Autoplay**:
用户偏好:是否在 Thing Card 封面上真正对动态内容(Animated Image 与 Thing Card Video Preview)进行 Animated Playback。它统一管控所有应用内 Thing Card 面的封面动态播放;关闭时封面停在静态首帧 / Thing Card Video Frame。它不作用于详情附件网格(那里归 **Detail Autoplay**)与全屏预览。
_Avoid_: 把它当成 GIF/视频的文件属性、把范围扩大到附件查看界面、把它与逐帧裁切混为一谈、与 Detail Autoplay 混为一谈

**Detail Autoplay**:
用户偏好:详情附件网格里的动态内容(Animated Image、Motion Photo、Thing Card Video Preview)以何种方式自动 Animated Playback。它有四档——关闭自动播放 / 逐一播放 / 同时播放一次 / 同时循环播放,默认同时循环播放;全部档位只对当前在滚动视口内的附件生效。它与 **Cover Autoplay** 互相独立,不作用于 Thing Card 面与全屏预览。
_Avoid_: 把它当成 Cover Autoplay 的子项或总开关、当成布尔开关、把范围扩大到 Thing Card 面或全屏预览

**Detail Static Representative Frame**:
详情附件网格里一个动态附件"不播放时"应当显示的那一帧:Animated Image 是首帧,视频是 Thing Card Video Frame,Motion Photo 是其高画质静态主图。播放一次结束、滚出视口、以及关闭自动播放档下,显示的都是它。
_Avoid_: 与"派生 GIF 的第 0 帧"划等号(对 Motion Photo 不成立)、让它停在播放结束时的尾帧

**Motion Photo**:
一个静态图片附件，其文件本身同时携带一段内嵌的短视频动态成分（对应 Android 阵营各厂商的“动态照片/实况照片”）。它默认在所有界面显示为静态图，只有支持的界面才呈现其动态成分；它是图片附件被检测出的一种本性，而非独立于 IMAGE/VIDEO 的附件类型，与 **HDR Media**、**Animated Image** 同属“同一个文件、额外能力按界面分级呈现”。
_Avoid_: 把它当成视频附件、当成新的附件类型、用苹果商标 Live Photo 作为词汇表术语、把 VIVO 那种独立配对视频文件默认当作内嵌

**Sync Account**:
以全局唯一用户名标识、绑定已验证邮箱的服务端账号；同一账号登录的所有设备共享同一份同步数据。登录方式挂在可扩展的 identity 层下（一期仅用户名+密码）。
_Avoid_: 把邮箱当登录名、把本地无账号使用称为"账号"、user 泛称

**Conflict Copy**:
两台设备并发修改同一条 Thing 的内容域（标题、正文/清单、附件列表）时，为落后一方生成的完整 Thing 副本，放入同一 Thing Folder、标题带冲突标记，由用户手动取舍；结构域（状态、文件夹归属、置顶、卡片外观）的并发修改按字段级最后写入赢静默收敛，不产生它。
_Avoid_: 静默覆盖任何一方的内容、把结构域并发也当成冲突、conflict file

**App Chrome**:
The surrounding interface outside a Thing Background, including home, settings, help, popups, dialogs, drawers, and other navigation or configuration surfaces.
_Avoid_: thing UI

**Drawer Header Image**:
A single user-chosen image used as the header of App Chrome surfaces — both the navigation drawer and the statistic screen — sharing one crop across them. Despite the name it is not limited to the drawer.
_Avoid_: per-surface header image, drawer-only image, attachment

**Drawer Header Image Crop**:
The single shared aspect ratio, crop center, and zoom applied to the Drawer Header Image, determining its shape and framing identically on both the navigation drawer and the statistic screen. It is one setting for the image, not a separate value per surface.
_Avoid_: per-surface crop, Thing Card Media Crop, centerCrop

**Thing Background Surface**:
A screen whose primary visual identity comes from a Thing Background rather than App Chrome.
_Avoid_: dark-mode screen

**Hybrid Chrome Surface**:
A surface that wraps Thing-owned content in App Chrome, such as a reminder dialog whose shell should follow Appearance Mode while the embedded Thing keeps its Thing Background.
_Avoid_: treating the whole surface as either pure chrome or pure thing UI

**Voice Waveform**:
录音对话框与音频附件播放对话框里随实时音量起伏的可视化，呈现为一片由所属记事的 Thing Background 派生配色的“水体”：多层半透明波浪叠成水面，音量越大水位越高、浪越大。两处都由同一条实时分析链驱动，只是输入不同——录音取麦克风 PCM，播放取正在播出的那段 PCM（按已播出的采样位置对齐喂入），都不做整曲前瞻分析；配色取自 Thing Background——主体水体保留 **记事身份色锚点**，并叠一层克制的竖直明暗（波峰稍提亮、最下方深水区稍压暗）呈现自然水的上亮下暗：纯色记事用同色系提亮/压暗，渐变记事保留其横向渐变关系；表面反射与薄层透射不得被统一拉向固定青蓝/蓝绿色，只有最深传播路径可相对身份色产生不超过约 2°的轻微冷移；更远波浪使用 **水层景深阶梯** 表现景深，与 Appearance Mode 无关。
_Avoid_: 把它当成整段音频的静态波形图或整曲前瞻分析的产物、当成 App Chrome 的中性控件配色、竖直柱子可视化、把它与 **Voice Waveform Video** 混为一谈

**记事身份色锚点**:
Voice Waveform 中由最靠近观察者的首层水体低频主体保留的被录音记事原始纯色或横向渐变视觉基准。
_Avoid_: 要求首层全部最终像素严格等于原色、把白色高光或灰暗阴影当成记事身份色、让远层取代首层成为唯一颜色参照

**水层景深阶梯**:
Voice Waveform 中由第 0 层记事身份色与第 1～8 层低频主体共同形成的稳定远近关系：九层保持有序、八个相邻边界独立可辨，且近三层拥有最高辨识优先级；该术语只约束最终层序，不限定具体感知色空间路径。
_Avoid_: 按输入颜色切换向暗、随录音或 HDR 状态改写阶梯、依赖局部光影/HDR/闪点/阴影才能看见层界、用身份色粗边代替主体分离、让相邻主体收敛

**随层保色阴影**:
Voice Waveform 中以当前位置已经完成景深阶梯和 Thing 渐变、但尚未加入反射、透射、高光与 HDR 的水层主体色为颜色来源，在保持该层颜色身份的同时形成更深受光结构的负向明暗响应。
_Avoid_: 黑色或灰色 overlay、从第一层或原始 Thing 色派生所有远层阴影、乘暗最终材质总和

- **Voice Waveform** 的随层保色阴影锚定当前层当前横向位置的主体色；物理遮挡只削弱对应的直射光分量。

**身份色体积吸收**:
Voice Waveform 中只作用于穿过水体介质的透射光路、由记事身份色关系约束的有界颜色衰减；光程增加时可以自然变深和改变饱和度，但不压暗主体、环境反射或高光。
_Avoid_: 把体积吸收当作阴影、乘暗最终材质总和、以黑色覆盖制造介质深度

**Voice Waveform Video**:
由一个音频附件重新渲染得到的视频文件，画面是该音频的 Voice Waveform，并自带该音频的声音。它是被生成出来的作品，不是对任何界面的屏幕录制；具备条件时它本身就是一个 **HDR Media**。
_Avoid_: 屏幕录制、录制界面上的控件、把它当成 Voice Waveform 本身、认为它必须在播放或录音的同时产生

**整曲前瞻分析**:
先扫描整段音频取得段落、节拍等结构，再用这些尚未播到的信息驱动画面的做法。它让画面"预知"后续内容，破坏因果性。
_Avoid_: 把它与 **非实时驱动** 混为一谈、把任何脱离挂钟的渲染都称为前瞻

**非实时驱动**:
同一条因果实时链按音频时间逐样本喂入、按固定步长推进，只是不跟随挂钟。每一帧掌握的信息量与实时完全相同，因此不构成 **整曲前瞻分析**。
_Avoid_: 称之为离线分析、认为它可以顺带取得未来信息

**重力轨迹**:
录音期间与声音一同记录下来的设备重力方向序列，使日后重新渲染能复现当时的水体倾斜。它是源数据，丢失即不可再生。
_Avoid_: 当成可重算的派生缓存、当成录音本身的一部分内容、期望历史录音也具备

**导出画框**:
**Voice Waveform Video** 画面中包裹水体卡片的那一圈背景、投影与描边。它属于 **App Chrome**，跟随 **Appearance Mode**；画框之内的水体与时钟墨色属于 Thing。
_Avoid_: 让画框携带记事身份色、把画框计入 **平均画面亮度**、用画框弥补水体对比不足

**HDR 输出格式**:
一个 **Voice Waveform Video** 把高动态范围信息写进文件所用的那一套约定，包含亮度曲线与随附的元数据（当前为 HDR10、HDR10+、HLG、杜比视界 8.4、HDR Vivid 五种）。它由设备是否**真的编得出来**决定可选与否，与画面内容无关；同一段水体用哪一种输出，画的东西不变，变的是文件如何描述自己的亮度。
_Avoid_: 把 PQ 与 HDR10 当作两种并列格式、把它与 **HDR UI 渲染** 或 **HDR Display** 混为一谈、以设备广告支持代替实测可用、认为格式更"高级"就意味着高光余量更大

**Activity Header**:
首页 Thing 列表顶部的标题区，显示当前 Thing Scope 的名称（All Things Scope 或某个 Thing Folder 名）与其子项计数；随列表滚动从展开的大标题折叠为 actionbar 内的小标题，只有滚动回列表顶部才重新展开。
_Avoid_: 把它等同于 actionbar 或系统状态栏、把它当成 App Chrome 的全部

**Immersive Thing List**:
NORMAL 或 MOVING 模式下首页 Thing 列表的一种呈现态：Activity Header 完全折叠进 actionbar 后继续滚动，顶部 App Chrome 收起，使 Thing Card 从状态栏一直铺到导航栏；SELECTING 与搜索态永不进入。它不同于"窗口本就绘制到系统栏之下"的通用 edge-to-edge。
_Avoid_: 与窗口级 edge-to-edge 混用、把它当成一种状态或类型筛选

**Home Chrome Retraction**:
让首页顶部 App Chrome（不透明的状态栏底色条与 actionbar——二者连体、同色，加上其阴影与折叠后的 Activity Header）作为一个整体随滚动上移隐藏、下移回落的行为；由滚动方向驱动（enterAlways），与由滚动位置驱动的 Activity Header 折叠相互独立。它是进入 / 离开 Immersive Thing List 的动作。
_Avoid_: 与 Activity Header 折叠混为一谈、作用到 SELECTING/搜索态、连带隐藏创建 FAB、把状态栏底色条当成独立于 actionbar 的常驻保护罩

**Appearance Mode**:
The user's light/dark preference for App Chrome, where following the system setting takes priority over a manual dark-mode choice.
_Avoid_: independent dark-mode booleans

**Button-like Control**:
A local command control that represents one action without occupying an entire row or card.
_Avoid_: full-row clickable surface, full-card clickable surface, full-row action surface

**Selection**:
用户在选择模式下一并标记的 Things 与 Thing Folders 的集合；其成员始终是当前投影里的同级兄弟，因此被选中的 Thing Folder 的后代不会同时属于这个 Selection。
_Avoid_: selected Thing only, single highlighted card

**Batch Action**:
作用于整个 Selection 的单个动作，集合中的每一项按自身类型执行对应操作——Thing 执行状态操作，Thing Folder 执行对应的内容操作或结构操作。
_Avoid_: Thing-only bulk action, treating a Selection as one object

**Checklist**:
一个 Thing 的内容被表达为一列可勾选完成的条目时的整体形态；它存储在 Thing 自身的内容里，而不是独立实体。
_Avoid_: 把清单当成独立于 Thing 的对象、todo list 作为泛称

**Checklist Item**:
清单中的一个可完成条目，包含自己的文本、完成状态和层级。
_Avoid_: note line, 把控制行（添加项、分隔、已完成头部）当成 Checklist Item

**Checklist Item Level**:
逐项显式存储的缩进深度，取值一级、二级或三级；由用户通过缩进/反缩进直接编辑，是清单项自身的属性而非由归属推导。缩进按钮带门控，只在该项存在“同级的上一个兄弟”时才启用——从而既禁止没有归属的二/三级项（孤儿），也禁止“层级跳空”（如一级项直接管着三级项）。反缩进、删除等操作产生的跳空，会被层级归一化自动收回到合法层级。
_Avoid_: 由父子关系反推层级、无限层级、孤儿二三级项、层级跳空

**Checklist Item Owner**:
某个清单项的派生父项——它上方最近的、层级严格更浅的清单项；不存父指针，完全由位置加层级算出。二、三级项必有 owner，且 owner **恰好浅一级**（不允许层级跳空）；一级项没有 owner，即为组根。
_Avoid_: 存储的父 ID、把归属当成持久关系、owner 浅不止一级、层级跳空

**Checklist Group Root**:
没有归属（owner）的清单项。由于禁止孤儿，组根恒等于一级项。整组是下沉到底部已完成区还是停在上方，由组根的完成态决定。
_Avoid_: 把组根当成可以是孤儿深层项、把连续两个一级项当成同一组

**Checklist Item Group**:
一个组根（即一个一级项）连同它逐层归属下的所有清单项构成的整体；它是完成迁移、拖拽这类整组操作的单位。两个相邻的一级项属于各自独立的组。
_Avoid_: 把组当成持久实体、跨组拖拽、按层级而非按组根判断迁移

## Relationships

- A **Thing** has one **Thing Background**.
- A **Thing** may be a **Trashed Thing**.
- A **Trashed Thing** has a **Pre-Trash Thing State** while it remains restorable.
- A **Thing** may belong to one **Thing Folder**.
- A **Thing Folder** may contain zero or more **Things**.
- A **Thing Folder** may contain zero or more child **Thing Folders**.
- A **Thing Folder** may have one parent **Thing Folder**.
- A **Thing Folder** may be a **Private Thing Folder**.
- A **Thing Folder** may be a **Trashed Thing Folder**.
- A **Thing Folder** may be an **Empty Thing Folder**.
- A **Private Thing Folder** protects descendant **Things** and child **Thing Folders** while they remain inside it.
- A **Trashed Thing Folder** preserves descendant **Things** and child **Thing Folders** while it remains restorable.
- A **Projection Folder** may represent a **Thing Folder** without changing that folder's own status.
- A **Trash Root Entry** may contain descendant **Trashed Things** or **Trashed Thing Folders** without making those descendants separate root entries.
- A **Legacy Placeholder Thing** is not user-owned content.
- **Empty-List Guidance** replaces **Legacy Placeholder Things** for welcome and empty-list messaging.
- A **Thing Folder Path** identifies the current nested **Thing Folder** location.
- A **Thing Scope** is selected independently from Thing status and Thing type filters.
- The **All Things Scope** includes Things from every non-deleted **Thing Folder** scope for the active status and type filters.
- **Trash** is selected independently from **Thing Scope** and does not change the stable Thing Folder scope list.
- A **Thing Background** can be described by **Thing Background Information**.
- A **Thing Background** overrides **Appearance Mode** for Thing-owned surfaces.
- A **Thing Foreground** adapts to the visible Thing-owned background, not to **Appearance Mode**.
- On ordinary Thing-owned surfaces, **Thing Foreground** adapts to **Thing Background**.
- On **Thing Card Media Background**, **Thing Foreground** adapts to the masked media background.
- A **Thing Background Glyph Colour** preserves the Thing Background as the main glyph colour while using separate black/white support only for readability.
- A **Thing** has one **Thing Card** presentation preference that can be reused by card-based surfaces.
- **Thing Card Appearance** is the stored presentation preference; a **Thing Card Surface Projection** is how one card-based surface renders that preference.
- An **AppWidget Size Preset** provides a launcher-picker default shape and does not change the identity of existing placed AppWidget instances.
- A **Full-Span Thing Card** is a presentation preference of a **Thing**, not of a home-list filter.
- **Thing Card Appearance** includes **Thing Card Span Mode**, **Thing Card Image Placement**, **Thing Card Media Source**, **Thing Card Media Target Aspect Ratio**, **Thing Card Media Crop**, and **Thing Card Media Background**.
- When **Thing Card Media** is placed left or right, it appears in a **Thing Card Side Media Panel** that spans the Thing Card's final visible content height.
- **Thing Card Bottom Status** anchors to the bottom of a Thing Card's content area on surfaces that render it; surfaces that re-present reminder/goal/habit another way (such as the noticeable reminder surface and Doing) may hide it instead.
- A **Thing** may have one **Thing Card Appearance Update Time**.
- A **Thing Card** has one **Thing Card Span Mode**.
- A **Thing Card** may have one **Thing Card Image Placement** when the Thing has Thing Card Media.
- A **Thing Card** may have one **Thing Card Media Source** when the Thing has image or video attachments.
- A video **Thing Card Media Source** may have one **Thing Card Video Frame**.
- **Thing Card Video Frame** changes Thing Card presentation only and does not change video playback.
- A video **Thing Card Media Source** may have one **Thing Card Video Preview** derived from it, looping from its **Thing Card Video Frame**.
- A **Thing Card Video Preview** receives **Animated Playback** only on in-app Thing Card surfaces that support it and only when cover autoplay is enabled; otherwise the video shows its static **Thing Card Video Frame**, mirroring how an **Animated Image** falls back to its first frame.
- A **Thing Card Video Preview** is a derived presentation artifact; it does not make the video an **Animated Image** and does not change the underlying video file or its playback.
- **Cover Autoplay** is the user preference that gates **Animated Playback** of cover dynamic content (**Animated Image** and **Thing Card Video Preview**) across all in-app **Thing Card** surfaces uniformly.
- **Cover Autoplay** does not affect the detail attachment grid, which is gated by **Detail Autoplay**, nor the full-screen viewer, where animated attachments keep playing unconditionally because the user opened them to view.
- **Detail Autoplay** is the user preference that selects how dynamic attachments (**Animated Image**, **Motion Photo**, **Thing Card Video Preview**) automatically receive **Animated Playback** in the detail attachment grid, among four modes: off, one at a time, all once, all looping.
- **Detail Autoplay** applies only to attachments currently inside the scroll viewport, because the detail attachment grid lays out every item at once and therefore defeats off-screen pausing.
- A dynamic attachment that is not playing in the detail attachment grid shows its **Detail Static Representative Frame**.
- A video attachment receives **Animated Playback** in the detail attachment grid through the same **Thing Card Video Preview** artifact used by **Thing Card** surfaces; no separate higher-resolution artifact is derived for the detail surface.
- The full-screen viewer plays a video attachment as the real video rather than as its **Thing Card Video Preview**, mirroring how it plays a **Motion Photo**'s embedded video rather than a derived artifact.
- A **Thing Card Media Source** may have separate **Thing Card Media Target Aspect Ratio** values for foreground thumbnail, side panel, and media background presentations.
- A **Thing Card Media Source** may have separate **Thing Card Media Crop** values for foreground thumbnail, side panel, and media background presentations.
- A **Thing Card** may have one **Thing Card Media Crop** when the Thing has Thing Card Media.
- **Thing Card Media Crop** is applied to a **Thing Card Media Target**.
- **Thing Card Media Target Aspect Ratio** determines the shape of the **Thing Card Media Target** before **Thing Card Media Crop** is applied inside it.
- **Thing Card Media Target Aspect Ratio**, **Thing Card Media Crop**, and **Thing Card Video Frame** belong to a **Thing Card Media Source**.
- **Thing Card Thumbnail Crop**, **Thing Card Side Media Crop**, and **Thing Card Media Background Crop** can all adjust crop center and user zoom, but they apply to different Thing Card media presentations.
- **Thing Card Media Crop** changes Thing Card presentation only and does not modify the underlying attachment file.
- A **Thing Card** may use **Thing Card Media Background** when the Thing has Thing Card Media.
- A **Thing Card Media Background** may have one **Thing Card Media Background Mask**.
- **Thing Card Media Background Mask** belongs to a **Thing Card Media Source**.
- **Thing Card Media Background** does not replace a Thing's **Thing Background**.
- **Thing Card** presentation choices are shared card preferences, not home-list-only preferences.
- Hidden private **Thing Cards** do not expose **Thing Card Media**.
- A **Thing** may have **Detail Attachment Media Appearance** for image and video attachments shown in its Detail screen.
- **Detail Attachment Media Crop** changes Detail attachment presentation only and does not modify the underlying attachment file.
- A **Thing Card Media** or **Detail Attachment** may be backed by an **HDR Media** file.
- A single **HDR Media** may receive **HDR Display** on a surface that supports it while appearing as its SDR base on every other surface.
- **HDR Display** depends on the surface, the device, and the display, so it is never guaranteed by the **HDR Media** file alone.
- FableSol 可以在能力满足时使用 **HDR UI 渲染**；其非 HDR 输出与 HDR 输出共享几何、动画、材质语义、ThingBackground 身份色和 SDR 基线，录音 HDR 态可以增强受控的局部反射、透射及其它受光结构，但不得通过大面积中性提亮抹平水层边界。
- **HDR UI 渲染** 依赖 Android 版本、窗口、渲染 surface、显示器和实时可用 headroom；任何条件不满足时都回退为同源 SDR 输出。
- FableSol 只有在 **Voice Waveform** 表达正在录音或正在播放音频附件时才获得 **HDR UI 渲染** 的额外局部光学响应；准备态、暂停态与停止态保持 SDR。渲染 **Voice Waveform Video** 时全程按录音态处理，因为产物没有准备与停止这两个状态。
- **Voice Waveform** 出现在录音对话框与音频附件播放对话框两处；两处的输入都是实时 PCM 流，播放侧按 AudioTrack 已播出的采样位置喂入以保证声画同步，不得改用整曲前瞻分析。
- **Voice Waveform** 的声音输入通过改变水面与光学条件间接改变 HDR 高光分布，不直接控制单个高光的 HDR 增益。
- **Voice Waveform** 的最高 HDR 镜面核心可以接近中性白；较低亮度的受光浪峰与薄层透射仍按层级保留 **Thing Background** 身份色。
- A **Voice Waveform Video** is produced from an audio attachment by rendering it again, never by capturing a screen; its content never depends on what a live **Voice Waveform** is doing at the time. A surface showing a live **Voice Waveform** does, however, yield its own resources while the production's progress dialog is in front of it — it freezes and its playback pauses, because both run in the same process (D187).
- **Voice Waveform Video** 使用 **非实时驱动**，这不违反"不得整曲前瞻分析"——两者的分界是画面是否使用了尚未播到的信息，而不是渲染是否跟随挂钟。
- **Voice Waveform Video** 的 HDR 亮度上限取用户设定的 HDR 强度，不取导出设备当时的显示余量；因此一台 SDR 显示器上也可以导出 **HDR Media**，而观看条件由播放端自行还原。
- 当设备无法编码 **Voice Waveform Video** 所需的 HDR 信号时，产物改用 FableSol 自己的 SDR 输出重新渲染，而不是把 HDR 结果压回 SDR。
- **Voice Waveform Video** 的水体倾斜来自录音时记录的 **重力轨迹**；没有轨迹的历史录音按竖直渲染，其余表现不变。
- **Voice Waveform Video** 的构图与触发它的界面无关：同一个音频附件从任何入口导出都得到相同构图。
- A **Thing Card Media** or **Detail Attachment** may be backed by an **Animated Image** file.
- A single **Animated Image** may receive **Animated Playback** on a surface that supports it while appearing as its first static frame on every other surface, mirroring how an **HDR Media** receives **HDR Display** only where supported.
- A crop editor, a RemoteViews surface (widget preview or placed widget), an HDR base frame, or a video thumbnail shows an **Animated Image** as a single frame; an in-app view surface that applies the same crop per frame still gives it **Animated Playback**.
- A **Thing Card Media** or **Detail Attachment** may be backed by a **Motion Photo** file.
- A single **Motion Photo** shows its still image by default and presents its embedded motion only on surfaces that support it, mirroring how an **HDR Media** receives **HDR Display** and an **Animated Image** receives **Animated Playback** only where supported.
- A **Motion Photo** is backed by an image attachment and does not change that attachment's type; its still image may itself be an **HDR Media**.
- **Appearance Mode** applies to **App Chrome**.
- A **Button-like Control** can appear on **App Chrome** or directly on a **Thing Background**.
- **Thing Background Surfaces** do not recreate solely because **Appearance Mode** changes.
- **Hybrid Chrome Surfaces** apply **Appearance Mode** to their chrome shell, icons, and controls, while embedded Thing content continues to use its **Thing Background**.
- The audio recording dialog is a **Hybrid Chrome Surface**: its shell, controls, and text follow **Appearance Mode**, while its **Voice Waveform** carries the recording Thing's **Thing Background** identity.
- **Voice Waveform** 从被录音记事的 **Thing Background** 派生颜色：主水体保留 **记事身份色锚点**，其余区域可按材质响应产生有界明暗与色度变化；远层主要通过 **水层景深阶梯** 表达纵深，只有最深传播路径可产生不超过约 2°的相对冷移。它与 **Thing Foreground** 一样依赖当前可见的 Thing-owned background，而不依赖 **Appearance Mode**。
- **Voice Waveform** 最靠近观察者的首层水体以低频主体基线承载 **记事身份色锚点**；局部材质响应可以偏离该基线，其余八层从锚点形成 **水层景深阶梯**。
- **水层景深阶梯** 必须由九层低频主体外观独立保持近到远的有序关系与相邻层可辨识度；局部光影、HDR、闪点和阴影只丰富层内材质，不承担层界存在与否。
- A **Hybrid Chrome Surface** may contain a **Thing Background Glyph Colour** readout; the readout carries Thing identity while the surrounding shell remains App Chrome.
- An **Activity Header** collapses from an expanded title into the actionbar as the home Thing list scrolls and re-expands only near the top.
- An **Immersive Thing List** is a home-list presentation state available only in NORMAL and MOVING modes; SELECTING and searching never enter it.
- **Home Chrome Retraction** hides and restores the home's top **App Chrome** as one unit, driven by scroll direction, independently of **Activity Header** collapse which is driven by scroll position.
- **Home Chrome Retraction** moves the opaque status-bar backdrop and the actionbar together as one conjoined unit; the home Thing list is drawn behind them, so retracting the unit exposes **Thing Cards** from the status bar to the navigation bar.
- Only when the chrome is fully retracted, a status-bar scrim layered beneath the conjoined backdrop is uncovered, protecting the system status-bar icons over the exposed **Thing Cards**; while the chrome is shown the scrim stays hidden behind the opaque backdrop.
- **Home Chrome Retraction** does not hide the create FAB, which keeps its own scroll-driven show and hide.
- A **Drawer Header Image** is part of **App Chrome** and appears on both the navigation drawer and the statistic screen with one shared crop.
- A **Drawer Header Image** has at most one user-chosen image; when unset, each surface shows its own built-in default header.
- A **Drawer Header Image** has one **Drawer Header Image Crop** that determines its shape and framing identically on both surfaces.
- A **Drawer Header Image Crop** applies only to a user-chosen image; a built-in default header is shown at its own natural shape.
- A **Drawer Header Image** may be backed by an **Animated Image**; as an in-app view surface it gives it **Animated Playback** with the **Drawer Header Image Crop** applied per frame, while its crop editor shows a single frame.
- New installs and upgrades default to light App Chrome unless the user explicitly enables follow-system or forced dark Appearance Mode.
- Light App Chrome is compatibility-sensitive: dark-mode infrastructure must not change existing light-mode visuals.
- A **Selection** may contain both **Things** and **Thing Folders**, all siblings within the current projection.
- A **Batch Action** applies one action across a **Selection** by mapping each member to its own type's operation, so a **Thing Folder** member runs a content or structural operation rather than a Thing state change.
- A **Sync Account** 的所有已登录设备共享同一份 **Things** 与 **Thing Folders** 数据；未登录设备的数据只存在于本地。
- A **Conflict Copy** 是一条普通 **Thing**，与原 Thing 放在同一 **Thing Folder**，二者此后无持久关联。
- **Thing Folder** 的并发修改不产生 **Conflict Copy**，按字段级最后写入赢收敛。
- A **Thing** whose content is a **Checklist** owns an ordered list of **Checklist Items**.
- A **Checklist Item** has one **Checklist Item Level** of one, two, or three.
- A **Checklist Item** may have one **Checklist Item Owner**, derived as the nearest preceding item of a shallower level; a level-two or level-three item always has one because indenting is only enabled when a same-level previous sibling exists.
- A **Checklist Item Owner** is exactly one level shallower than the item it owns; level gaps (a level-one item owning a level-three item) are not allowed and are removed by level normalization.
- A **Checklist Item** with no **Checklist Item Owner** is a **Checklist Group Root**, which is therefore always a level-one item.
- A **Checklist Item Group** consists of one **Checklist Group Root** plus every **Checklist Item** it transitively owns.
- A **Checklist Item Group** sits in the finished area when its **Checklist Group Root** is finished, and in the unfinished area otherwise; finishing or unfinishing a non-root item never relocates the group on its own.
- A finished **Checklist Item** always has all of its descendants finished; if it gains an unfinished descendant (by indenting one under it, unfinishing one of its subitems, or a delete that re-parents one under it), it and its finished ancestor chain revert to unfinished, while non-ancestor finished items keep their state.

## Example Dialogue

> **Dev:** "When dark mode is enabled, should a red reminder thing become dark?"
> **Domain expert:** "No - the reminder keeps its Thing Background; only the App Chrome around it changes."

## Flagged Ambiguities

- "Dark mode settings" could mean two independent booleans; resolved as **Appearance Mode**, where follow-system has priority and disables manual dark-mode selection.
- "Button" can mean either a local command control or an entire clickable row/card; resolved as **Button-like Control** for local command controls only.
- "Note card" can mean only a Note-type Thing or any card representation; resolved as **Thing Card** when discussing shared card presentation.
- "Home card" can mean a card shown only in the home list or a shared card presentation preference; resolved as **Thing Card** for reusable card presentation choices.
- "Note folder" sounds like it only contains Note-type Things; resolved as **Thing Folder** because EverythingDone's user-created items are Things across notes, reminders, habits, and goals.
- "WELCOME/NOTIFY_EMPTY note" can sound like user-owned content; resolved as **Legacy Placeholder Thing** when discussing the old stored rows and **Empty-List Guidance** when discussing the replacement message.
- "Image thumbnail" can exclude video thumbnails; resolved as **Thing Card Media** when discussing image or video thumbnails used by Thing Cards.
- “HDR 支持”可能混淆文件本身携带高动态范围信息（**HDR Media**）、界面显示该文件的完整亮度增益（**HDR Display**），以及实时生成图形使用额外高光亮度（**HDR UI 渲染**）；三者必须分别表述，广色域也不属于其中任何一种。
- “去雾”可能同时指减少宽柔光累计造成的材质泛白，或取消用于区分远近水层的 **水层景深阶梯**；这里只允许前者，后者的层序结果必须保留。阶梯当前可以采用统一向白点提亮的感知色路径，但不能随录音/HDR 状态变化，也不能被理解成必须永久绑定某一种颜色算法。
- “支持 GIF 显示”混淆了文件本身是动图(**Animated Image**)与界面真的在播放它(**Animated Playback**);已解析为两个独立概念,因为同一个 Animated Image 在某些界面播放、在另一些界面只显示第一帧。
- "Card background image" can mean replacing the Thing's identity background or only changing a card presentation; resolved as **Thing Card Media Background**, which does not replace **Thing Background**.
- "Side image width", "cover image ratio", and "card height" can describe different controls for the shape of **Thing Card Media Target**; resolved as **Thing Card Media Target Aspect Ratio**.
- "First attachment" can mean the first stored attachment or the card's chosen media source; resolved as **Thing Card Media Source** when discussing which attachment a Thing Card uses.
- "Card media settings" can be too narrow when the same entry also controls span and image placement; resolved as **Thing Card Appearance** for the whole card-presentation editor.
- "update time" can mean content changes or card appearance changes; resolved as **Thing Card Appearance Update Time** when only Thing Card Appearance changed.
- “全部记事”可能被误解为忽略状态和类型的总览；已解析为 **All Things Scope**，只表示不限定 Thing Folder，仍然受当前状态和类型筛选控制。
- “归档”容易和“完成”形成重复概念；已解析为不引入独立归档状态，由完成语义承担从日常进行列表中收起的作用。
- “已删除”曾同时表示代码状态和用户可见区域；用户语义中解析为 **Trash** / **Trashed Thing**，并且不能与完成语义混用。
- “Drawer Header Image”名字听起来只作用于导航抽屉，但同一张图与同一套裁切同时驱动统计界面头部；已解析为一个跨界面共享的 **Drawer Header Image** 概念，而非每个界面各自一张头图。
- “Drawer Header Image”的比例与裁切是否像 Thing Card Media 那样按界面各存一份，曾不明确；已解析为**单一共享**的 **Drawer Header Image Crop**，抽屉与统计强制同形，因为两者是分时查看的独立屏幕，不需要在同一处并存多种取景。
- “需要固定裁切的界面只显示单帧”曾被读成“凡是显示裁切结果的界面都单帧”；已按 ADR-0007 收紧：单帧只针对裁切编辑器、RemoteViews、HDR 基帧、视频缩略图；应用内视图界面通过逐帧套用裁切仍然给 Animated Image 以 Animated Playback，**Drawer Header Image** 因此在抽屉与统计上会播放动图。
- “让视频封面也能动”曾被读成“把视频当作 Animated Image 播放”或“在卡片里播放真实视频”；已解析为 **Thing Card Video Preview**：从视频派生一个动图预览产物，复用既有 Animated Playback 管线逐帧套用裁切，而视频本身仍不是 Animated Image。这修订了 ADR-0007“任何视频缩略图都停在单帧”的结论——应用内 Thing Card 面在开启封面自动播放时改播派生预览，而 RemoteViews、裁切编辑器、HDR 基帧仍为单帧。
- "edge-to-edge" 既可指窗口本就绘制到系统栏之下（本 App 全局早已 `setDecorFitsSystemWindows(false)`、导航栏透明、列表底部已铺到导航栏之下），也可指首页顶部 chrome 随滚动收起让列表全铺；已解析为后者专用 **Immersive Thing List** 与 **Home Chrome Retraction**，避免与窗口级 edge-to-edge 混用。
- "Live Photo"（苹果商标，HEIC+MOV 双文件配对）与 Android 的"动态照片"（多为单个 JPEG/HEIC 尾部内嵌视频）常被混用；已解析为词汇表统一使用 **Motion Photo**，并明确它是图片附件被检测出的一种本性、而非新的附件类型，除 VIVO 外均为单文件内嵌。
- "录音动画的颜色"曾在"属于 App Chrome 的中性控件配色"与"属于记事身份"之间含糊；已解析为 **Voice Waveform** 承载被录音记事的 **Thing Background** 身份（以 **记事身份色锚点** 保留颜色身份，并由九层低频主体外观独立保持 **水层景深阶梯**），而录音对话框外壳仍是 **App Chrome**——即该对话框是一个 **Hybrid Chrome Surface**。
- “记事颜色本身”曾在“全部水体像素必须严格等于原色”与“画面必须保留可识别的记事颜色身份”之间含糊；已解析为 **记事身份色锚点**：它落在最靠近观察者的首层低频主体基线上，局部材质响应及其余水层可以有界变化。
- “录音 HDR 态”曾被收窄为只能增强镜面和闪点；已解析为可以增强局部反射、薄峰透射及其它受光结构，但必须保留九层水体的相对边界和记事颜色身份，不得把第二、第三层或其它主体区域整体推成乳白。
- “随层保色界面肩”曾指在主体色阶不足时补充分离度的常驻宽软层界；当前已退出现行 **Voice Waveform**，层界必须由 **水层景深阶梯** 的九层低频主体独立成立，不得恢复身份色粗边、白边或黑边。
- “点音频附件”曾只有一种含义（就地播放）；已解析为两个入口：点卡片本身进入带 **Voice Waveform** 的音频附件播放对话框（计时器、进度滑杆、上一曲/播放暂停/下一曲，播完按附件顺序续播下一个），点右侧按钮仍是卡片上的就地播放/暂停。两者互斥，不同时出声。
