# 设计概览与验收标准 / Motion Photo 播放

术语见根目录 `CONTEXT.md` 的 **Motion Photo**；决策见 [decisions.md](decisions.md) 与 [ADR-0014](../../adr/0014-motion-photo-as-image-capability.md)。

## 设计概览

**一个文件、一种本性、按界面分级呈现。** Motion Photo 是被检测出携带内嵌视频的图片附件（`IMAGE` 类型不变）。所有"是否是 Motion Photo、内嵌视频在哪"的判断集中在一个检测器里，结果按文件签名缓存；其余界面复用既有管线。

### 数据流

```
选图导入（ACTION_GET_CONTENT）
  └─ 裸字节流拷贝（不重编码，内嵌视频保真）→ images/ 下的 .jpg/.heic
        └─ 首次显示时：MotionPhotoDetector.detect(path)
              ├─ 命中缓存 → 直接用
              └─ 未命中 → APP1 XMP 标记 / 三星 MotionPhoto_Data 标记 / 尾部 ftyp 扫描
                          → isValidMp4 校验候选偏移 → (isMotion, videoOffset, videoLength)
                          → 按 path+size+mtime 缓存
```

### 呈现（按界面）

| 界面 | 静态 | 动态 | 触发 | 实现 |
|---|---|---|---|---|
| 卡片封面 | 主图 | 派生 GIF | Cover Autoplay | 临时抠内嵌 MP4 → `VideoCoverPreviewManager` 派生 GIF → 删 temp |
| 详情列表 | 主图 | 派生 GIF | 无条件自动播 | 同上派生 GIF，走 Animated Image 的 Drawable 路径 |
| 全屏 | HDR 主图 | 真内嵌视频 | 按住 | `MediaPlayer(fd, offset, length)` + Surface，就地播放 |
| 裁切器 / RemoteViews / widget | 主图 | — | — | 不变 |

三处显示 **LIVE 徽标**。

### 关键复用点

- `VideoCoverPreviewManager` / Thing Card Video Preview：派生 GIF、缓存、屏外暂停、逐帧裁切。
- `MediaCropTransformation`：派生 GIF 逐帧套用裁切。
- `ImageViewerActivity` + PhotoView：全屏 HDR 静图保持不变，只叠加按住播放的 Surface。
- 导入拷贝（`FileUtil.copyUriToFile`）：已是裸字节流，不改。

### 新增/改动面（预估）

- **新增** `MotionPhotoDetector`（检测 + 偏移 + 校验 + 缓存）。
- **新增** 全屏按住播放的一小段 `MediaPlayer` + Surface 组件。
- **改** `FileUtil.getPostfixFromMimeType`（保留 `.heic/.heif`）、`AttachmentHelper` 图片扩展名允许列表（加 `heic/heif`）。
- **接** 检测结果进封面管线、详情适配器、全屏 Activity；LIVE 徽标。

## 验收标准

真机（OPPO、三星各一台）实测：

1. **OPPO JPEG（带 HDR）Motion Photo**：卡片封面在 Cover Autoplay 开时动、关时停在主图；详情列表自动播；全屏默认显示 HDR 静图（提亮），按住播放动态、松手回到 HDR 静图；LIVE 徽标可见。
2. **三星 JPEG Motion Photo**：同上（无 HDR 时静图为普通图）。
3. **三星 HEIC Motion Photo**（API 28+ 设备）：同上，扩展名保留 `.heic`，能检测并播放。
4. **普通图片**：行为完全不变，无 LIVE 徽标、不派生 GIF、不误判。
5. **VIVO 动态照片**：按静态图正常导入显示，无 LIVE 徽标，无动态，不崩溃。
6. **分享**：分享 Motion Photo 附件时发出的是原文件（对方设备仍为动态照片）。
7. **无新权限**：全程不弹出、不请求任何存储/媒体权限。
8. **保真**：导入后的文件字节级等于源文件（内嵌视频未被破坏）。
9. **性能**：塞满 Motion Photo 的列表滚动不明显卡顿（沿用屏外暂停；必要时加快滑暂停）。

## 非目标（v1）

拍摄/创建、互通格式转换、动态成分编辑、拆分附件、VIVO 动态、HEIC 的 `mpvd/sefd` box 精确解析、小米/Pixel 的声明支持。
