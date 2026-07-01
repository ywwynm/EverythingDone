# 待办 / Motion Photo 播放

技术上可行但当前刻意推迟的项。术语见根目录 `CONTEXT.md`。

## VIVO 动态照片支持（2026-07-01 推迟，见 decisions.md D4）
VIVO 把动态照片的视频存成同目录下独立 MP4，JPEG 里只有 `com.android.camera.livephoto` 的 UUID。v1 不支持其动态、按静态图导入。

将来若要支持：
- 导入时检测 VIVO 标记（JPEG 尾部的 `vivo{...}` JSON 含 UUID）。
- 申请 `READ_MEDIA_VIDEO`（Android 13+，更老为 `READ_EXTERNAL_STORAGE`），用 MediaStore 按"同目录 + 同前缀名"查兄弟 MP4；或提供"手动选配对视频"入口兜底。
- 拿到 MP4 后拼接到我们复制的 JPEG 尾部，归一化为标准内嵌 Motion Photo——ftyp 尾部扫描即可识别，下游零特判。
- 需如实标注"尽力而为"：同名启发式可能匹配失败（只拷了 JPEG / 改名 / 不在同目录），失败降级静态图。

## 扩展到小米 / Pixel（乃至更多厂商）
v1 实测范围收窄为 OPPO + 三星（仅有的测试机）。检测的 ftyp 尾部扫描是厂商无关的，小米（MicroVideo）、Pixel（MotionPhoto/Container）很可能已顺带可用，但未测试、未声明。有对应测试机后：实拍验证、把它们纳入声明支持列表、必要时补各家 XMP 标记的识别。

## HEIC 动态照片的 box 精确解析
v1 对 HEIC 走"尾部 ftyp 扫描 + 校验"定位内嵌视频。若发现某些 HEIC 动态照片扫描不稳，再补 `mpvd`(Google) / `sefd`(三星) box 的精确解析。

## 卡片封面的实况（LIVE）小徽标
详情网格已有"实况 / HDR / GIF"标识（`ImageAttachmentAdapter.ivBadgeLive` 等）；但**卡片封面**（首页 / 文件夹 / Doing / Noticeable 等记事卡片列表）目前动态照片只播派生 GIF、无任何标识（`BaseThingsAdapter` 无 badge）。可在封面角落加一个小 LIVE 徽标表明其为动态照片。
推迟原因：需评估卡片尺寸下徽标是否过挤、与现有卡片元素（时间 / 类型角标、附件数等）的位置冲突；且封面 GIF 本就在动，标识优先级低。

## 全屏之外的真视频播放 / 更高画质
v1 详情附件列表用派生 GIF（256 色、~720px）。若日后觉得详情里 GIF 画质不够，可考虑详情也上真播放器，但要重新评估列表内 live player 的性能（ADR-0012 当初否决卡片内真播放的理由）。
