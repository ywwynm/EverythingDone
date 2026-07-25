# 设计概览 / 详情页动态内容播放（Detail Animated Playback）

决策与理由见 [decisions.md](decisions.md)（D1–D16）与 [ADR-0017](../../adr/0017-detail-animated-playback-modes.md)。本文只讲**做成什么样**与**怎么算做对了**。

## 一、设置项

| 项 | 值 |
|---|---|
| Key | `Def.Meta.KEY_AUTOPLAY_DETAIL_DYNAMIC = "autoplay_detail_dynamic"` |
| 类型 | Int（0–3），存 SharedPreferences，经 `FrequentSettings` 缓存 |
| 默认 | `3` = 同时循环播放（与改动前的无条件循环对齐，见 D2） |
| 位置 | 设置页「界面」分组，紧跟在 Cover Autoplay 那一行下方 |
| 交互 | 整行可点 → `ChooserDialogFragment` 四选一；行右侧灰字显示当前档位 |

档位枚举 `DetailAutoplayMode`：

| 值 | 常量 | 中文 | 行为 |
|---|---|---|---|
| 0 | `OFF` | 关闭自动播放 | 全部停在 Detail Static Representative Frame；长按可手动播一遍 |
| 1 | `ONE_BY_ONE` | 逐一播放 | 可见项按索引升序排队，各播一轮，轮完静默 |
| 2 | `ALL_ONCE` | 同时播放一次 | 可见项同时各播一轮后停 |
| 3 | `ALL_LOOP` | 同时循环播放 | 可见项同时无限循环（**默认**） |

## 二、详情附件网格的行为

### 可见性（全部档位的公共基础）

- 判定对象：`mRvImageAttachment` 的每一行（不是每一项——同一行的项可见性相同）。
- 判定方式：`NestedScrollView` 的 `scrollY` 与 RV 在其中的 top/行高换算，不逐 view 求交。
- **滞回**：可见面积比 ≥ 0.6 判为进入视口，≤ 0.3 判为离开。中间区间保持上一状态。
- 触发点：`NestedScrollView.setOnScrollChangeListener`、RV 布局完成、附件增删/重排、`onResume`、软键盘导致的尺寸变化。

### 各档调度

- **进入视口** → 按当前档位启动：`OFF` 不动；`ONE_BY_ONE` 按索引插入队列；`ALL_ONCE` 立即播一轮；`ALL_LOOP` 立即无限循环。
- **离开视口** → 停止并回 Detail Static Representative Frame；`ONE_BY_ONE` 下若正在播则立刻让位给队列中下一个。
- **播完一轮**（非 `ALL_LOOP`）→ 回 Detail Static Representative Frame；`ONE_BY_ONE` 下推进队列。
- **重新进入视口** → 重新算作一次（D4），因此需要滞回防抖。
- **长按**（`OFF` / `ONE_BY_ONE` / `ALL_ONCE` 三档）→ 手动播一轮，松手后继续播完；`ALL_LOOP` 档不响应。
- **截图分享**（`mTakingScreenshot`）→ 全部立即冻结到 Detail Static Representative Frame。

### 三类内容的接入方式

| 内容 | 播放什么 | 静止时显示 |
|---|---|---|
| Animated Image（GIF / 动态 WebP） | 文件本身，`GifDrawable` | 第一帧 |
| Motion Photo | `MotionPhotoCoverHelper` 派生 GIF | **高画质静态主图**（需切回，不是 GIF 首帧） |
| 视频 | `VideoCoverPreviewManager` 派生 GIF（复用卡片那份 720px） | Thing Card Video Frame（= 派生 GIF 第 0 帧，零成本） |

定制模式（用户设过 `DetailAttachmentMediaAppearance`）下动图仍走 `MediaCropTransformation` 逐帧套裁切，非动图仍走 `MediaCropBitmapRenderer` 烘焙——这条既有分流不动。

### 生成

- 只为**视口内**的视频/Motion Photo 请求派生 GIF；`OFF` 档不请求。
- 与卡片端共用 `requestPreview` / `enqueueUniqueWork(KEEP)`，同 key 不重复生成。
- 未就绪 → 本次显示静帧、**不阻塞** `ONE_BY_ONE` 队列；就绪回调到达时若该项仍在视口，按"刚进入视口"处理。

## 三、全屏预览（`ImageViewerActivity`）

不受四档管控。新增视频页的播放状态机：

```
翻到视频页 / 打开该页
  └─ 停稳 ~360ms（防抖）
       └─ 自动播真视频：videoFrameMs 起 3s（不足则播到结尾）→ 回静帧
长按（第一次）
  └─ 从视频开头播 → 松手 → 回静帧（播放头留在松手处）
长按（之后）
  └─ 从播放头继续 → 松手 → 回静帧（播放头更新）
翻页 / 退出全屏
  └─ 播放头重置
```

配套：

- 播放期间隐藏中央 `iv_video_signal`（操作入口），播完恢复；详情页的 `ivVideoSignal`（身份标识）**始终保留**。
- 不静音，与 Motion Photo 一致；**补齐音频焦点** `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK`，Motion Photo 播放路径一并补上。
- 触感沿用 Motion Photo 自动播放那套。

## 四、验收标准

**设置项**

1. 全新安装后档位为「同时循环播放」，详情页 GIF / Live Photo 的观感与本次改动前完全一致。
2. 四档均能选中、退出设置页后保持，重启应用后仍保持。
3. Cover Autoplay 关、Detail Autoplay 开时，卡片静止而详情播放（反之亦然）。

**详情页**

4. 含视频附件的记事，详情页首次打开时视频显示静帧，后台生成完成后原地变成动图；再次打开直接是动图。
5. 「逐一播放」：可见附件从左上到右下依次亮起，每个播一轮；轮完全部静止。
6. 「同时播放一次」：可见附件同时动一轮后全部静止。
7. 任一「一次」档下，把附件滚出屏幕再滚回，该附件重新播一次；手指停在视口边界缓慢移动时**不**反复重播。
8. 「关闭自动播放」：全部静止；长按任一动态附件播一轮，松手后仍播完；附件数 > 1 时长按同时触发拖拽，松手后附件位置未改变。
9. 播完/静止时：GIF 显示首帧、视频显示所选帧、Live Photo 显示高画质主图（与「关闭」档所见一致）。
10. 分享截图产出的图片里，所有动态附件都停在静态代表帧。

**全屏预览**

11. 翻到视频页，停稳后自动播 3 秒真视频并有触感，播完回所选帧；快速左右连续翻页不产生声音碎片。
12. 长按从视频开头播，松手回静帧；再长按从上次位置继续。
13. 播放期间中央播放按钮消失，播完恢复；点它仍能打开外部播放器。
14. 播放时正在放的音乐被 duck 而非被无视；播放结束后音乐恢复。
15. Live Photo 的既有行为（打开自动播一遍、长按循环）未回归，且同样 duck 音乐。

**性能**

16. 默认档下打开一条含 6–8 个动态附件的记事，滚动无明显掉帧（真机实测，掉帧则按 D13 加并发上限）。
