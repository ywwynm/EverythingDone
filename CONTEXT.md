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

**Thing Folder**:
A user-created container that groups Things and may itself live inside another Thing Folder.
_Avoid_: note folder, category, tag

**Private Thing Folder**:
A Thing Folder whose folder identity, contained previews, and descendant content presentation are protected while the descendants remain inside it.
_Avoid_: secret category, hidden tag

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

**Animated Image**:
一个本身就是动图的图片附件——GIF 或动态 WebP;它能否逐帧播放取决于显示它的界面。
_Avoid_: GIF as the blanket term, video attachment

**Animated Playback**:
某个界面真的在逐帧播放一个 Animated Image,而不是只显示它的第一帧。
_Avoid_: GIF support as a blanket term, autoplay as a file property

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
- A **Thing** has one **Thing Card** presentation preference that can be reused by card-based surfaces.
- **Thing Card Appearance** is the stored presentation preference; a **Thing Card Surface Projection** is how one card-based surface renders that preference.
- An **AppWidget Size Preset** provides a launcher-picker default shape and does not change the identity of existing placed AppWidget instances.
- A **Full-Span Thing Card** is a presentation preference of a **Thing**, not of a home-list filter.
- **Thing Card Appearance** includes **Thing Card Span Mode**, **Thing Card Image Placement**, **Thing Card Media Source**, **Thing Card Media Target Aspect Ratio**, **Thing Card Media Crop**, and **Thing Card Media Background**.
- When **Thing Card Media** is placed left or right, it appears in a **Thing Card Side Media Panel** that spans the Thing Card's final visible content height.
- A **Thing** may have one **Thing Card Appearance Update Time**.
- A **Thing Card** has one **Thing Card Span Mode**.
- A **Thing Card** may have one **Thing Card Image Placement** when the Thing has Thing Card Media.
- A **Thing Card** may have one **Thing Card Media Source** when the Thing has image or video attachments.
- A video **Thing Card Media Source** may have one **Thing Card Video Frame**.
- **Thing Card Video Frame** changes Thing Card presentation only and does not change video playback.
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
- A **Thing Card Media** or **Detail Attachment** may be backed by an **Animated Image** file.
- A single **Animated Image** may receive **Animated Playback** on a surface that supports it while appearing as its first static frame on every other surface, mirroring how an **HDR Media** receives **HDR Display** only where supported.
- A crop editor, a RemoteViews surface (widget preview or placed widget), an HDR base frame, or a video thumbnail shows an **Animated Image** as a single frame; an in-app view surface that applies the same crop per frame still gives it **Animated Playback**.
- **Appearance Mode** applies to **App Chrome**.
- A **Button-like Control** can appear on **App Chrome** or directly on a **Thing Background**.
- **Thing Background Surfaces** do not recreate solely because **Appearance Mode** changes.
- **Hybrid Chrome Surfaces** apply **Appearance Mode** to their chrome shell, icons, and controls, while embedded Thing content continues to use its **Thing Background**.
- A **Drawer Header Image** is part of **App Chrome** and appears on both the navigation drawer and the statistic screen with one shared crop.
- A **Drawer Header Image** has at most one user-chosen image; when unset, each surface shows its own built-in default header.
- A **Drawer Header Image** has one **Drawer Header Image Crop** that determines its shape and framing identically on both surfaces.
- A **Drawer Header Image Crop** applies only to a user-chosen image; a built-in default header is shown at its own natural shape.
- A **Drawer Header Image** may be backed by an **Animated Image**; as an in-app view surface it gives it **Animated Playback** with the **Drawer Header Image Crop** applied per frame, while its crop editor shows a single frame.
- New installs and upgrades default to light App Chrome unless the user explicitly enables follow-system or forced dark Appearance Mode.
- Light App Chrome is compatibility-sensitive: dark-mode infrastructure must not change existing light-mode visuals.
- A **Selection** may contain both **Things** and **Thing Folders**, all siblings within the current projection.
- A **Batch Action** applies one action across a **Selection** by mapping each member to its own type's operation, so a **Thing Folder** member runs a content or structural operation rather than a Thing state change.

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
- "HDR support" conflates a file being high-dynamic-range (**HDR Media**) with a surface actually boosting its brightness (**HDR Display**); resolved as two distinct concepts, because one **HDR Media** is HDR-displayed on some surfaces and shown as its SDR base on others.
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
