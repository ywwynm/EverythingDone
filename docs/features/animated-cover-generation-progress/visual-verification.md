# 特殊交互录屏与视觉复核

## 2026-10-15：验收方法

- 使用已授权的两台设备及隔离包，复用正式首页、编码器和状态订阅。在调试 Worker 的准备、50% 帧边界及完整编码后的交付点等待，以使状态切换落在指定手势或粒子动画内；不修改生产动画速度或编码进度。
- 每个场景保存原始 MP4、录制过程中的状态事件时间、检查结果。录屏按本次 run_id 独立目录归档，避免覆盖或误读旧文件。
- 分别录制自然连续滑动、持续触摸、fling；长按拖动与边缘滚动；手指控制的粒子消散与自动拼合；生成中修改内容、配色、布局、比例、显示选项；动态来源 A→B→静态及动态照片／视频帧切换。
- 另录制从实际外观面板选择封面来源、修改卡片宽度和媒体位置、确认保存的过程。交互中的异步模型更新与真实面板操作分别标记，不混称为同一种操作。
- 使用蓝色 A、橙色 B 和静态图片，直接辨认来源。逐段查看录屏抽帧，并对状态切换及交接处加密取帧；自动化结果只提供状态与时机证据，不能替代视觉验收。

## 视觉检查项目

封面是否闪空或回到旧来源，状态行是否残留或被裁切，图标与文本是否对齐，颜色与遮罩是否跟随外观，卡片是否因进度发生尺寸变化，拖动／粒子快照及交接是否完整。

## 已复核的交互录像

录屏位于仓库 `tmp/cover-generation/visual-review/`。每个目录同时保留事件时间、状态采样及原始结果，不覆盖失败结果。以下以视频时间为准，事件日志与第一帧有约 0.2 秒录制启动差异。

| 场景 | OPPO | 三星 | 视觉复核结论 |
| --- | --- | --- | --- |
| 连续滑动、持续触摸、fling | [录像](../../../tmp/cover-generation/visual-review/oppo-final/scroll.mp4) | [录像](../../../tmp/cover-generation/visual-review/samsung-final/scroll.mp4) | 三类手势中均出现状态切换；封面保留、数量恢复，滑动继续，返回首卡显示正确。完成附近分别加密抽帧。 |
| 长按拖动与边缘滚动 | [录像](../../../tmp/cover-generation/visual-review/oppo-final/drag.mp4) | [录像](../../../tmp/cover-generation/visual-review/samsung-final/drag.mp4) | 悬浮快照保持，背景卡继续更新；拖动跨过列表后，目标自身恢复 GIF、内容和新布局，没有透明占位残留。 |
| 粒子消散与拼合 | [录像](../../../tmp/cover-generation/visual-review/oppo-final/particle.mp4) | [录像](../../../tmp/cover-generation/visual-review/samsung-final/particle.mp4) | 手指不松开时完成，背景卡更新；目标仍按快照消散，取消及拼合后交接最新封面。重点查看 OPPO 约 17／35.7 秒、三星约 13.6／29.5 秒，修复后未见完成时封面闪空。 |
| 生成中更新内容、配色、布局、比例及显示限制 | [录像](../../../tmp/cover-generation/visual-review/oppo-final/edit.mp4) | [录像](../../../tmp/cover-generation/visual-review/samsung-final/edit.mp4) | 新外观生效，完成不撑高；A→B→静态后旧任务不能覆盖新来源。此项通过真实模型更新触发页面响应，不冒称手工面板输入。 |
| 动态照片换源、背景比例、视频起始帧及播放选项 | [录像](../../../tmp/cover-generation/visual-review/oppo-final/motion.mp4) | [录像](../../../tmp/cover-generation/visual-review/samsung-final/motion.mp4) | 动态照片 A→B 与 0→500ms 帧来源保留正确，背景使用新比例。末段重开自动播放暴露闪空，单独修复与复验，不能把这两段原录像整段称为无问题。 |
| 实际外观面板内换宽度、位置、封面并保存 | [录像](../../../tmp/cover-generation/visual-review/oppo-panel-final/panel.mp4) | [录像](../../../tmp/cover-generation/visual-review/samsung-panel-final/panel.mp4) | 宽卡／右侧在生成中生效，B 与静态 S 的选择及保存正确，旧任务完成后没有改回。入口触发真实菜单点击，面板控件使用实际坐标触摸。 |
| 生成中关闭播放、完成后重开 | [录像](../../../tmp/cover-generation/visual-review/oppo-autoplay-v11/autoplay.mp4) | [录像](../../../tmp/cover-generation/visual-review/samsung-autoplay-v11/autoplay.mp4) | 两机关闭与重开均保留缩略图和背景；实际绘制空封面计数全部为 0，关键切换抽帧未见闪空。 |
| 详情往返、退到桌面再返回 | [录像](../../../tmp/cover-generation/visual-review/oppo-navigation-v10/navigation.mp4) | [录像](../../../tmp/cover-generation/visual-review/samsung-navigation-v10/navigation.mp4) | 准备、生成、离开期间完成后返回均正确；复核首页恢复及详情附件画面。本次没有杀进程或修改锁屏设置。 |

常规阶段按事件或 2–3 秒间隔抽帧查看；状态切换、粒子交接采用约 100ms 间隔加密抽帧。保存完整原始录像供回放，不将此方法表述成每一视频帧均由人工逐帧验收。新来源首次解码仍可能显示原有加载圈；这与同一封面完成后闪空的缺陷区分记录。

## 测试夹具校正

- 原生编码器在最后一帧处理后仍需执行 `close`。为精确覆盖短动画内的 READY，调试 Worker 完整编码后暂存产物，受控移回正常缓存路径并完成任务；不以 99% 等同于文件完成。无控制的实际生成由基础回归覆盖。
- 三星在长按识别后额外静止 1.2 秒，会触发系统识屏／拖放侧栏并取消应用触摸。该录屏作为无效场景保留，改为长按识别后立即移动，不修改设备系统设置。
- 状态采样不能把 RecyclerView 内容更新动画中的瞬时 alpha=0 直接判成背景卡片永久透明。保留原始数值，并结合录屏和交接后的恢复检查。
- Android 录屏为可变帧率。OpenCV 按平均帧率随机 seek 会取错时间，改为顺序解码、按实际 PTS 取帧，抽帧图标注实际时间。
- 完整组合录像中的面板失败有明确输入原因：OPPO `oppo-final/panel.mp4` 约 7 秒点击落到正式应用的悬浮提醒，展开提醒而没有打开外观面板；三星 `samsung-final/panel.mp4` 在面板粒子入场期间过早点击，录像中宽度仍为“正常”、位置仍为“上方”。后续单独重录面板：入口使用工具栏控件的点击事件，避免干扰正式应用悬浮提醒；面板内仍按实际控件矩形触摸，等待入场交接与草稿更新后再继续。不将这两份失败记录标记为通过。

## 发现并修复：完成交接闪空

- 三星 `samsung-v5-final/particle.mp4` 约 12.27 秒，粒子目标仍显示快照，右侧背景卡片恢复数量，但 A 封面暂时消失，文字仍显示。不是卡片整体透明或尺寸变化。
- 沿真实首页状态订阅新增绘制前回归：静态封面已显示后，统计 READY 到 GIF 就绪之间可见 ImageView 的空 Drawable。修复前 `samsung-handoff-red` 记录 14 次空封面绘制，检查失败，与录屏一致。
- 原因是 `onReady` 清除加载身份后重新加载 GIF，而首次 GIF 尚无动图占位缓存；`loadAnimatedThingCardThumbnail`／背景分支执行 Glide clear，产生解码空档。
- `BaseThingsAdapter` 在同源、同帧、同绑定身份核对成功后，把当前封面保存为该 GIF 的临时占位，再刷新媒体。复用现有有界缓存、裁切和来源身份；不改变粒子、拖动或滚动机制。
- 两机 `*-handoff-v6` 均观察到真实 READY，空 Drawable 绘制计数为 0。三星面板 `samsung-panel-v6` 已通过实际选源与保存；修复后完整组合录屏继续复验。
- 完整组合的动态照片与设置录像另发现：生成中关闭自动播放，完成后再开启，首卡短暂闪空（OPPO 约 62.51 秒、三星约 55.86 秒）。首次 GIF 占位尚未缓存，而静态／动态加载身份的 `:anim` 后缀不同。继续用独立缩略图与背景绘制前计数锁定此分支，不能仅凭最终恢复播放判定通过。
- 三星 `samsung-autoplay-red` 在真实首页复现缩略图 2 次、媒体背景 3 次空 Drawable 绘制；补充在来源、帧、尺寸、裁切完全相同而只改变播放标记时保存静态占位。
- 自动播放重开会使本次列表重绑回收 holder，实测加载入口的旧 key 与 Drawable 均为 null，因此仅保留当前 ImageView 无效。最终直接查找既有静态位图缓存的完整 key（或仅去掉 `:anim`），无需额外复制或解码；同身份才可命中。已移除临时诊断日志。
- 媒体背景在其 `OnPreDraw` 内按最终尺寸发起加载。原测试监听器先执行，会把同一帧稍后已经填入占位的中间态误记为空；自动播放回归改在所有预绘制完成后的 `OnDraw` 采样，并继续用录像核对。不能为了消除此类断言误报改动产品的布局时序。
- 两机 `*-autoplay-v10` 的重开播放检查与画面均通过；进一步把关闭播放时的静态缓存查找也按相同身份互通，避免已显示的同一静态帧在开关切换时重新加载。最终 `v11` 同时统计关闭和重开两段。
- 两机 `*-navigation-v10` 各 8 项检查通过，已人工查看阶段抽帧：准备中打开详情、返回仍准备；生成中进入详情并完成后返回；退到桌面后从准备进入生成、完成，再返回首页。详情附件、首页封面、进度／数量及首卡恢复正常。首次使用提示与权限提示只在隔离包初始化处理，不修改正式包数据或设置。

## 最终验收记录

- 两机主组合各 29 项检查通过；组合运行最后的面板输入失败保留为 `ok=false`，由独立的 `*-panel-final` 录像和通过结果补齐，不修改原始报告。
- `*-handoff-v8` 两机完成交接空 Drawable 计数均为 0；`*-autoplay-v11` 两机关闭播放、重开缩略图、重开背景的实际绘制空封面计数均为 0。导航两机各 8 项通过。
- 归档 18 段相关原始录像，共约 12 分 31 秒；[证据清单](../../../tmp/cover-generation/visual-review/acceptance-manifest.json) 包含每段文件大小、时长、SHA-256 与终端结果一致性。主组合中发现的问题及独立复验的对应关系明确保留。
- 最终源码 JVM 检查 1507 项，0 失败、0 错误、1 项既有跳过；`git diff --check` 通过。隔离构建成功，未增加测试素材到正常 APK。
- `oppo-panel-final` 与 `samsung-panel-final` 的实际面板操作及保存均通过，已查看两机完整阶段抽帧：A 生成中改宽卡／右侧，选择 B 后 A 完成仍保留 B，选择静态 S 后 B 完成仍保留 S，确认后首页保持静态 S 与新布局。

## 发布与清理

- 2026-10-15 已重新发布 [debug 202610151143](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610151143.apk)。本次发布日志为 [update-20261015194310.md](debug-updates/update-20261015194310.md)，通过 `debugUpdateNotesFile` 传入发布任务。
- 远端 `latest.json` 与本地发布元数据一致，更新日志与上述独立文件一致。重新下载 APK 为 27,414,857 字节，SHA-256 为 `1b54befa46a1b7a3e6cf9385ef82298ea9c96c6485fb6a84657924e59c918d5d`；ZIP CRC 通过，包名为 `com.ywwynm.everythingdone`，版本为 2.0.0／43。核验结果见 [visual-publish-verification.json](../../../tmp/cover-generation/visual-publish-verification.json)。
- 两台设备均已成功卸载 `com.ywwynm.everythingdone.covervalidation`，移除隔离测试记事、缓存和授权。正式安装包的记事和设置未修改。所有本地证据保留；未创建 Git 提交。
