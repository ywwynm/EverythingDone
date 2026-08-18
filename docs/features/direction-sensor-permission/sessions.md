# 会话记录

## 2026-08-17～18 - 跨四处的方向权限受限检测与提示，全部实机验证

盘点、决策与实现一并完成。盘点结论见 `decisions.md` 的第一节：实时读方向传感器的四处
（录音 Dialog、音频附件 Dialog、海浪动画设置预览、空间照片）加间接依赖的一处（导出保留
录音时的倾斜）。此前 f25e079e 只覆盖了录音 Dialog 的 MediaProjection 返回这一条路径。

### 实现

- 新增 `permission/DirectionSensorWatchdog`（与触发路径无关的首样本看门狗，T1 = 1.5 秒），
  替换并删除 `views/recording/DirectionSampleDelayHintGate` 及其 6 项单测；新增 10 项单测。
- 录音 Dialog：接入现有 `tv_audio_input_notice`，排在优先级链最底；`maxLines` 3→5。删除
  MediaProjection 专用状态机、采样序号与 `KEY_DIRECTION_SAMPLE_DELAY_HINT_SHOWN`。
- 音频附件 Dialog：新增 `tv_play_direction_notice`（**宽度必须定值 208dp**，见下）。
- 海浪动画设置 Dialog：「画面响应设备倾斜」下方新增条件说明行，点击跳应用详情页。
- 空间照片：`SpatialPhotoView` 内置看门狗并回调宿主，`ImageViewerActivity` 弹一次性 Toast，
  键为 `SpatialPreferences.direction_restricted_hint_shown`（与音频三处不共用）。
- 空间照片设置页：`onResume` 短暂注册 `TYPE_GAME_ROTATION_VECTOR` 探测一次，结论驱动
  `tv_spatial_direction_restricted`；开关**不置灰**。
- 重力轨迹：`Collector.start()` 在 `hasLast == false` 时不再种伪造的竖直 t=0。
- 文案：`audio_input_direction_samples_delayed` 改名 `direction_sensor_restricted`，去掉
  「音频海浪动画」主语与冗余从句，13 个语言地区全部重写。
- `PermissionUtil.openApplicationDetails()`：两处说明行共用的跳转，失败退回系统设置根页。

### 验证（OPD2515 `9018f404`，921 项 Debug 单测 0 失败）

前置结论：`TYPE_GAME_ROTATION_VECTOR` 同样被拦截，窗口 5.98 秒；详见
`analysis-2026-08-17-game-rotation-vector-intercepted.md`。

三档权限对照（录音 Dialog，用系统设置界面切档、`appops get` 读回核对）：

| AppOp | 拦截日志 | 提示行为 |
|---|---|---|
| `allow` | 0 条 | 全程不出现（零误报） |
| `default`（仅开屏时不允许） | 1 条，6.02 秒窗口 | t≈3.3s 出现，t≈9.7s 自行消失 |
| `ignore`（禁止） | — | 出现后保持（t≈12.2s 仍在） |

其余四处均在 `default` 档下用「`am start` 打开系统设置 → `KEYCODE_BACK` 返回」触发并截图确认：
音频附件 Dialog t≈3.0s 出现 / t≈9.1s 消失；海浪设置说明行 t≈2.9s 出现 / t≈8.9s 消失（两张
截图对比可见预览的太阳反光位置变化，即倾斜确已恢复）；空间照片 Toast t≈3.1s 出现且
`direction_restricted_hint_shown` 落盘，第二次往返仍被拦截但不再弹；空间照片设置页说明行
出现在「Control viewpoint by tilting the device」下方，开关未置灰。

重力轨迹（E）改为**解析产物**而非目视：在受限窗口内起录 5 秒，拉回 WAV 解析 `EDmo`——
253 格 @50Hz，`grid 0 = (-0.1764, 9.5309, 2.3025)`，**|v| = 9.8066 = g**，是真实重力读数。
旧的伪造种子是 `(0, 1, 0)`、模长恰好 1.0，两者用模长一眼可分。

### 同一轮的第二遍：可点「系统设置」与强调色（用户当晚反馈）

反馈三点：空间照片设置页那行用的不是提示性文本的颜色；两个设置页里点文案中的「系统设置」
要能跳转；那几个字要用当时的强调色并支持渐变。

- 新增 `permission/DirectionSensorHint`：按资源模板的 `%1$s` 定位锚点，装配可点 + 按强调色
  着色的片段，返回一个重新着色回调（海浪动画设置把它挂进 `mAccentChipPainters`，换色即跟随）。
- 主文案改为带 `%1$s` 的格式串，新增锚文本 `direction_sensor_restricted_settings`，13 个语言
  地区同步；两处 XML 的 `android:text` 改为代码赋值，避免屏幕上出现字面 `%1$s`。
- 空间照片设置页说明行的文字色改为 `app_chrome_on_surface_hint`。

渐变着色踩了两个坑，都是放大截图才看出来的：

1. **按整块文本宽度生成渐变** → 片段只取到渐变里很窄一段，落在 accent2 那端时在白底上几乎
   看不清。改为用 `Layout.getPrimaryHorizontal` 查出片段的 x 与所在行上下缘，再用 shader 的
   局部矩阵平移过去，让整段渐变正好铺在那几个字上。
2. **shader 的颜色仍会乘 paint 的 alpha**，而说明行本体是低 alpha 的 hint 色，于是强调色被
   冲成一片淡影。在 `updateDrawState` 里显式 `ds.alpha = 255` 后恢复满强度。

复验：四处提示与 Toast 文案均无字面 `%1$s`；两个设置页的锚点都是完整的 accent→accent2 渐变
（裁剪放大逐字确认），点击锚点分别落到 ColorOS 应用权限页与应用详情页；921 项单测 0 失败。

### 实机才暴露的两个问题

1. **音频附件 Dialog 的提示一显示就把对话框顶宽了。** 根 FrameLayout 的 `layout_width` 会被
   `BaseDialogFragment` 的 null-parent 充气丢掉，实测宽度由固有尺寸决定，因此 `match_parent`
   与 `wrap_content` **都不构成宽度约束**。改为定值 208dp（280 − 36 − 36）后两张截图宽度一致。
2. **`uiautomator dump` 在录音/播放/调参这三个 Dialog 上不可信。** 走秒时钟与连续动画使它等
   不到 idle，返回陈旧缓存树：第一次验证据此判定「提示没出现」，而同一时刻的截图显示提示就在
   屏幕上。这三处的验收一律用截图。
