# 执行记录

## 2026-08-17 - 外部审查第九轮修复批（偏好确认收口与错误分类补全）

第九轮外部审查 1 项 P2 + 2 项 P3，全部核实属实并修复：

- [x] **配置期转后台仍确认系统偏好（P2）**：`prepareConfiguredMode` 里 Dialog 转后台会跳过 `startListening`（`previewRequested=false`），但完成回调仍无条件确认偏好——raw 打开与 AudioRecord 启动都没验证过。这是第八轮"全就绪才提交"修复在转后台分支上的残留。修复：确认条件收窄为 `previewRequested`（预览真验证过）；`startPreview` 成功回调补记偏好（覆盖被推迟的确认，重复写入幂等）。正式录音必然先经预览启动，补记点覆盖完整。
- [x] **回落分支漏查 `lastStartFailedOnOutput`（P3）**：`fallbackToMicrophoneInternal` 完成分支对 `previewStarted=false` 一律报"无法启动麦克风"，raw 文件失败时把用户引向错误排查方向。修复：加 `previewRequested && !previewStarted && lastStartFailedOnOutput → FILE_OUTPUT_FAILED` 分流——带 `previewRequested` 前置条件防止 `startListening` 未被调用时读到标志残值（顺带核对第七轮三处消费点，均已天然满足该前置）。
- [x] **恢复预览的启动窗口假静音（P3）**：迟到 silent=true 在 `stopPreview` 清零后写回、`startPreview` 只在成功完成时清零——恢复预览的启动期间（数百毫秒~数秒）显示假警告，违反"持续静音约 6 秒才提示"的语义。修复：`startPreview` 入口快照即清 `systemSilent`（迟到事件必然在用户回到 Dialog 的操作之前入队，入口清零完全闭环）。
- [x] OPD2515 真机回归：预览、录停丢弃、服务收尾全部正常；来源偏好保持默认（补记路径无误写）。911 例单测通过。系统类"配置期转后台"场景需授权弹窗交互无法自动化，以代码审查保证。

## 2026-08-17 - 外部审查第八轮修复批（收尾闸、持久化时序与迟到事件兜底）

第八轮外部审查 2 项 P2 + 1 项 P3。按用户要求逐项评估实机可发生性后全部修复：

- [x] **收尾仍可被准备态回调作废（P2，窄但真实）**：上一轮的 busy 闸只挡了 `stopPreview`，输入故障与投影撤销回调仍会在收尾窗口（几十~几百毫秒）内抢占代次——实机触发面：预览态关 Dialog 与用户从系统状态栏撤销投屏并发、或恰逢输入流死亡。修复：Service 级 `sessionClosing` 标志（`finishSessionInternal` 置位，完成回调**先清除再查代次**，不会因作废卡闸），`onCaptureFailure` / `handleProjectionStopped` / `onSystemSilenceChanged` 三个外部事件入口收尾期间早退；投影撤销仍置空引用（无害部分保留）。
- [x] **系统偏好在 raw 打开前被持久化（P2，实机罕见但违反自家设计原则）**：`startListening` 启动线程即返回 true，`FileOutputStream` 在线程里才打开——"预览全部就绪后才提交来源偏好"的既定原则被架空（存储故障时一次没成功的授权会话把默认来源改成"系统"）。修复：raw 流同步在 `startListening` 里打开，失败置 `lastStartFailedOnOutput` 并清理半成品文件；采集线程改收已打开的流，线程内 open 逻辑删除。返回 true 从此意味着输入与输出链路全部就绪。
- [x] **静音迟到事件覆盖清零（P3，窄）**：引擎身份闸挡不住"发出时线程还活着、事件已入主线程队列"的窗口。兜底：`startPreview` 与 `prepareConfiguredMode` 的成功回调把 `systemSilent` 一并清零——迟到事件必然排在新采集启动操作之前执行，新启动沿的清零必然覆盖污染，此后由新线程的检测接管。收尾场景由 `sessionClosing` 闸一并覆盖。
- [x] OPD2515 真机回归：预览（open 同步化主链）、预览态 dismiss 后服务死透（第七轮核心验证重跑）、录停丢弃全部正常。911 例单测通过。三项均为异步窗口，无法注入，以代码审查+回归保证。

## 2026-08-17 - 外部审查第七轮修复批（异步时序与资源收尾清扫）

第七轮外部审查 4 项 P2 + 3 项 P3，逐条核实全部属实，全部修复：

- [x] **dismiss 抢占会话收尾（P2）**：AndroidX 先回调 `onDismiss`（发起 `finishSession`）再走 `onStop`（同步可见性），后者触发的 `stopPreview` 抢占 `operationGeneration`、作废收尾完成回调——留下假 PREPARED 快照，系统模式泄漏投影/授权通知/started 服务。双防御：Fragment `onStop` 在 `mSessionClosing` 时跳过可见性同步；服务 `stopPreview` 分支补 `!snapshot.busy`（busy 的 PREPARED 表示配置或收尾在途，不叠加操作）。真机实证：预览态关闭 Dialog 后服务死透、零残留通知。
- [x] **超时后迟到任务的副作用（P2）**：8 秒超时只作废结果回调，任务若在超时后才从 `configure/startListening` 返回，会留下无人认领的活麦克风。麦克风分支超时处置后追加一个串行清理任务（必然排在迟到任务之后）`stopListening` 兜底；用户随后重试时新 `configure` 进门也会停监听，清理幂等无害。系统分支的 `fallbackToMicrophoneInternal` 入队的 configure 本身就承担同样职责，无需追加。
- [x] **预览停止不消费 lastInputFaulted（P2）**：`stopPreview` 完成快照的 `configured` 补 `&& !engine.lastInputFaulted`；纯麦克风回到 Dialog 时（`setDialogVisible(true)` 新增分支）自动 `prepareConfiguredMode` 完整重建，不需要用户手动重选。边界：系统类来源死亡后回 Dialog 不自动重建（需要重新走授权流程，由用户从来源选择器发起），记录为已知边界。
- [x] **raw 目录创建失败误报输入源故障（P2）**：引擎新增 `lastStartFailedOnOutput`（`startListening` 里 `createTempAudioFile` 返回 null 时置位），三处 `started=false` 消费点（配置完成、预览启动、起录）优先分流 `FILE_OUTPUT_FAILED`，不再回落麦克风、不再改写来源偏好。
- [x] **系统静音回调无代次绑定（P3）**：引擎实时回调（`onSystemSilenceChanged` 两处、`onRecordingSizeLimitReached`）加 `captureThread === this` 身份闸，被替换/停止的旧线程不得再发状态——旧预览迟到的 silent=true 无法再污染新预览。
- [x] **纯系统来源权限说明（P3）**：`audio_input_system_summary` 补"麦克风权限"（AudioPlaybackCapture 的 AudioRecord 构造要求 RECORD_AUDIO），四语言，与混合模式表述对齐。
- [x] **提示无 TalkBack 主动播报（P3）**：`tv_audio_input_notice` 加 `android:accessibilityLiveRegion="polite"`，异步提示（授权撤销、存储失败、输入死亡、系统静音）出现即播报。
- [x] OPD2515 真机验证：预览态 dismiss → 服务完全停止、无残留通知（#1 直接证据）；HOME 触发的日常 stopPreview 正常、launcher 清栈后无泄漏（busy guard 无误伤）；新会话预览、录停、丢弃回归全过。全量 911 例单测通过。异步竞态类（#2/#3/#5）无法注入，以代码审查+既有单测保证。

## 2026-08-16 - 外部审查第六轮修复（停止与输入死亡并发的竞态窗口）

第六轮外部审查 1 项 P2 窄竞态，核实属实并修复：

- [x] **停止抢先时输入死亡信号丢失（P2）**：输入线程 read 到 `ERROR_DEAD_OBJECT` 的同一窗口内若手动/容量停止先执行，`requestStop` 把 `shouldRun` 拍成 false → 采集线程退出时的故障回调被 `if (shouldRun)` 抑制；即使回调抢先送达，服务侧 `onCaptureFailure` 在 STOPPED 态也直接忽略。停止原因保持 NONE/SIZE_LIMIT_REACHED → 上一轮按 `stopNotice` 的判定失效 → 纯麦克风仍 `configured=true` 复用失效对象。修复：真相记录在数据源头——引擎新增 `@Volatile lastInputFaulted`，在三个 `read < 0` 分支**即刻置位**（不依赖回调送达与服务状态；OUTPUT 文件故障不置位），`configure` 重建后复位；`stoppedSessionRemainsConfigured` 增加第三参数 `inputFaulted`，两路信号（停止原因、引擎标志）任一命中都判未配置。可见性：完成回调在 `stopListening`（join 采集线程）之后读取，read 失败处的写入必可见。
- [x] `StoppedSessionConfigurationTest` 增加竞态用例（stopNotice=NONE/SIZE_LIMIT + inputFaulted=true → 必重建），共 4 例。全量 911 例单测通过。
- [x] OPD2515 真机冒烟：常态复用路径无损（录→停→重录瞬回准备态→再录→停→丢弃全链正常）。竞态窗口本身无法注入，以单测+审查保证。

## 2026-08-16 - 外部审查第五轮修复（麦克风硬故障后重录复用失效对象）

第五轮外部审查 1 项 P2，核实属实并修复：

- [x] **输入流死亡停止后 `configured` 误标（P2）**：纯麦克风录音中 `AudioRecord.read` 返回 `ERROR_DEAD_OBJECT` 等负值触发自动停止后，完成快照 `configured = !requiresSystemAudio` 把纯麦克风标为仍已配置；而 `stopListening` 不释放 AudioRecord、失效对象的 `state` 仍是 INITIALIZED（`recordsReady` 复用检查拦不住），点"重新录音"直接在失效对象上开预览，再次报"麦克风不可用"。修复：判定抽为纯函数 `stoppedSessionRemainsConfigured(mode, stopNotice)`——停止原因为 `CAPTURE_FAILED`（输入流死亡）时纯麦克风也判未配置，重录走 `prepareConfiguredMode` 完整重建（`configure` 进门即 `releaseAudioRecords`，失效对象一并释放；该重建路径第二轮已真机验证）。`FILE_WRITE_INTERRUPTED`（存储问题，采集对象健康）与正常停止保持快速复用。
- [x] 新增 `StoppedSessionConfigurationTest`（3 例：输入死亡必重建、健康停止保持复用、系统类模式恒重建）。全量 910 例单测通过。
- [x] OPD2515 真机回归（复用路径不受影响）：正常停止 → 重录瞬时回健康准备态（cd='Start recording'、enabled=true）→ 再录再停成功（导出出现）→ 丢弃清理干净。`ERROR_DEAD_OBJECT` 本身无法从 adb 注入（Android 12+ 并发录音策略对后台应用静音而非杀流），失效路径以单测+代码审查保证。

## 2026-08-16 - 外部审查第四轮修复批（收尾期诚实化与错误分类重构）

外部审查第四轮 4 项发现逐条核实全部属实（其中 CaptureSource 一项还叠加了上一批修复引入的错上加错），全部修复，并按同族问题自查扩大了修复面：

- [x] **收尾期提前宣称"已保留"（P2）**：停止的中间快照原直接携带 `SIZE_LIMIT_REACHED` 等成功文案，而 WAV 还在异步封装、可能失败。重构：中间快照持新枚举 `FINALIZING`（"正在保存录音…"，四语言），停止**原因**只在封装成功后进入完成快照；前台通知同步换"正在保存录音"（停掉仍在走秒的 chronometer）；保存与重录按钮收尾期间淡化（0.4），取消保持可用（不必等大文件封装完才能放弃，`ControlPolicy` 新增 `cancelEnabled`）；完成快照按结果恢复。副按钮出现动画保留在转换沿（布局稳定），alpha 终值由完成沿拉正。
- [x] **通知决策固定于停止瞬间（P2）**：`fromNotification` 参数删除，是否发停止完成通知改在完成回调按当下 `dialogVisible` 判定——长封装期间用户切前后台不再收错方向的通知。附带：停止完成通知的正文在自动停止时带上原因文案（容量上限、授权撤销、输入中断、写入中断），此前只显示来源标签。
- [x] **CaptureSource 被忽略（P2，含上批引入的错上加错)**：`CaptureSource.OUTPUT` 实际是 raw 文件打开/写入失败（与输入源无关），上一批把它并进"回落麦克风"分支属于误改。重构为按 source 三分类：OUTPUT → `FILE_OUTPUT_FAILED`（"录音文件写入失败"，不回落、不改写来源偏好）；SYSTEM → 回落麦克风（文案吻合）；MICROPHONE → `MICROPHONE_UNAVAILABLE`（含混合模式——不再回落到刚失败的麦克风）。录音中 OUTPUT 失败停止原因用新枚举 `FILE_WRITE_INTERRUPTED`（"录音写入中断，已保留此前录音"，仅封装成功时显示）。
- [x] **无障碍 enabled 语义（P3）**：`updateControlsEnabled` 对主/重录/取消三按钮同步 `isEnabled`（原只设 `isClickable`，TalkBack 与键盘焦点仍宣告可用）；封装失败态保存按钮 `contentDescription` 换"录音未能保留，无法保存"（新字符串四语言），成功态恢复标准描述。
- [x] **提示文本对齐（用户追加）**：notice 控件改 wrap_content + 水平居中放置，块内多行按 start 对齐（不再行级居中）。
- [x] OPD2515 真机验证（audios 目录占位注入）：失败停止态 dump 实测 `iv_record_main_action enabled=false`、重录/取消 `enabled=true`、cd="Recording was not kept. Saving is unavailable."；Dialog 宽度 772px 恒定、提示两行左对齐；恢复目录后成功停止态四按钮全 `enabled=true`、cd 恢复、保存按钮满色 accent（数值与基准一致）；后台"Stop and keep"→"Recording stopped and kept / Microphone"通知→点击回 Dialog 全链正常。`FINALIZING` 中间态毫秒级封装无法目视捕捉，以单测+审查保证。全量 907 例单测通过（新增 `finalizing stop keeps only the cancel action available`）。

## 2026-08-16 - 准备态失败文案错位与提示撑宽 Dialog

用户追问"Dialog 可能显示哪些失败信息"时发现三处文案-场景错位（"已保留此前录音"出现在从未录音的场景），随后又在失败态截图中发现提示文本会把 Dialog 撑宽。四处全修：

- [x] **准备态投影撤销文案（新枚举 `SYSTEM_CAPTURE_ENDED`）**：准备态（尚未录音）投影被系统撤销时回落麦克风，原复用 `SYSTEM_CAPTURE_REVOKED` 的"系统音频授权已结束，已保留此前录音"，后半句无中生有。新文案"系统音频授权已结束，音频输入已恢复为'麦克风'"（四语言），录音中撤销的原文案不变。
- [x] **准备态采集流死亡改回落**：准备态系统流（OUTPUT）死亡原走 `failPreparedCapture(CAPTURE_FAILED)` 进 ERROR 死路且文案带"已保留此前录音"；改与相邻分支（混合模式麦克风流死亡）对称——需要系统音频的模式下任一流死都回落麦克风（`SYSTEM_INITIALIZATION_FAILED`，文案"无法启动系统音频，已恢复为麦克风"完全吻合）。纯麦克风模式流死仍走 `MICROPHONE_UNAVAILABLE` 不变。
- [x] **起录失败文案（新枚举 `RECORDING_START_FAILED`）**：`startRecordingInternal` 里 `engine.startRecording()` 返回 null（附件文件创建失败等）原用 `CAPTURE_FAILED`，一个字节没录却说"已保留"。新文案"无法开始录音"（四语言）。
- [x] **提示文本撑宽 Dialog（用户发现）**：Dialog 窗口 WRAP_CONTENT，宽度实际由 TimelyClockView 的测量宽决定（OPD2515 实测 771px≈294dp；布局声明的 280dp minWidth 从未生效）。notice 长文本参与首轮测量时把 Dialog 撑到 855px。修复：显示提示前设 `notice.maxWidth = 定型宽 - 两侧 36dp 边距`，超长自动换行（maxLines=3）；首次布局未完成时（跨进程恢复首帧带提示）延后一帧显示。
- [x] OPD2515 真机验证（audios 目录占位注入封装失败）：失败停止态宽度 772px 与正常态 771px 一致（1px 为量测抗锯齿误差），提示两行居中换行，紧凑布局保持；恢复目录后正常停止回归照旧（宽度 771px、文件名/导出/满色主按钮）。`SYSTEM_CAPTURE_ENDED` 与 `RECORDING_START_FAILED` 无法从 adb 注入（投影撤销无 shell 通道、附件目录在外部存储无法制造 mkdirs 失败），以代码审查保证——两处均为单点 notice 值替换，显示通道与已验证的 `FINALIZE_FAILED` 同构。全量 906 例单测通过。
- 注：本批验证顺带确认 audios 目录被占位时录音可正常开始（raw 写服务专用 temp 目录）、停止封装才失败并诚实报告 `FINALIZE_FAILED`——第三轮修复的失败路径在真实存储故障形态下再次闭环。

## 2026-08-16 - 外部审查第三轮修复批

外部审查第三轮 4 项发现逐条核实全部属实（全部集中在"封装失败"路径的诚实性），全部修复：

- [x] **后台封装失败仍通知"已停止并保留"（P1）**：`showStoppedNotification` 按 `savedFile` 分支：失败时标题换 `audio_recording_stop_not_kept_notification_title`（"录音已停止，未能保留"）、正文说明原因，不再宣称保留成功（四语言）。
- [x] **失败停止态 UI 误导（P2）**：`AudioRecordingControlPolicy` 的 STOPPED 态主按钮 enabled 改为 `savedFile != null`；Dialog 侧文件 UI 重构为水平触发 `applyStoppedFileUi(kept)`（在 `STOPPED && !busy` 快照处判定，幂等标志防重复动画）：失败时文件名行不显示、导出按钮不出现、主按钮 alpha 0.4 且禁用。首轮真机验证暴露转换沿时序缺陷——stop 过程的中间快照（`STOPPED && busy`）仍带旧 `savedFile`，转换动画据此渲染了文件 UI，而完成快照不再触发转换——由转换沿渲染改为水平触发后闭环。
- [x] **非 IOException 封装失败不删 raw（P2）**：`saveToWaveFile` 捕获范围 `IOException` → `Exception`（覆盖 `PcmWaveHeader` 超限时的 `IllegalArgumentException`），任何封装失败都删除半成品 WAV 与 raw 并正常回报完成结果，服务不再停在 `STOPPED + busy`。
- [x] **非 ENOSPC 封装失败文案相反（P2）**：失败停止的 notice 判定改为 `file != null → 采集侧 notice；lastFinalizeNoSpace → STORAGE_FULL；else → FINALIZE_FAILED`（新增 `FINALIZE_FAILED` 文案"录音收尾失败，未能保留"，四语言），不再把封装失败误说成"已保留此前录音"。
- [x] **四语言文案完整性审计**：录音相关 27 键在 values / zh-rCN / zh-rTW / zh-rHK 全部齐备。
- [x] **失败停止态紧凑布局（用户验收发现）**：用户指出失败态截图里时钟上方与提示文本上方的留白过大。查明两处空隙都来自"为成功态预留"的固定位置——时钟停止位 80dp 是给文件名行让位的，停止态 notice 位 180dp 是按"文件名行+下移时钟"占满上方设计的；失败态两者都不出现，留白就露出来。修复：`applyStoppedFileUi(false)` 把时钟收回原位（36dp），notice 定位在 `STOPPED && !busy && savedFile == null` 时改用录音态的 88dp（与录音期提示同位，视觉连续；直接从快照判定，不依赖调用顺序）。成功态零变化。真机注入复验：失败态时钟中心实测 56dp、notice 实测 93dp 起，均与设计吻合；正常停止回归照旧。
- [x] OPD2515 真机注入验证（录音中移走 raw 强制封装失败）：失败通知标题/正文正确；失败停止态 Dialog 文件名行不显示、导出不出现、主按钮淡化系数实测 0.40 且点击无响应、notice 正确；X 丢弃后持久化/通知/临时目录全清。正常停止回归：文件名行、导出按钮、满 alpha 主按钮照常出现（水平触发未破坏成功路径）。全量 `:app:testDebugUnitTest` 906 例通过（含新增 `stopped without saved file disables the save action`）。

## 2026-08-16 - 外部审查第二轮修复批

外部审查第二轮 6 项发现逐条核实全部属实（其中 #1 是上一批修复自身引入的组合缺陷），裁定后全部修复：

- [x] **进程恢复后停止态可被错误认领（P1）**：会话存在性的全部判定点（附件入口拦截、"前往"跳转、通知/详情返回入口）统一为"进程内活跃 或 持久化停止态"双查询；`setSessionSource` 增加服务侧归属防御（会话非 IDLE 且已有有效归属时，其他记事的绑定不得改写）。回归中还发现并修复了一个残余缺陷：CLEAR_TOP|SINGLE_TOP 把目标 intent 投给栈顶其他记事实例时，进程死场景下返回入口早退（不重启不开 Dialog）——该入口同样接入双查询并以持久化记事 id 兜底重启。
- [x] **新建记事会话归属升级（P1）**：`saveAfterOnPause` 首次把新建记事入库后，通过 Dialog 的 `onHostThingSaved` → `upgradeSessionThing` 把会话归属从 -1 升级为正式 id，返回入口换成按 id 的 UPDATE intent，已持久化的停止记录同步升级。onPause 先于一切后台化动作，升级窗口覆盖录音后台化之前。真机验证：新建页（有标题）录音 → HOME → 图标接力直达**已保存的该记事**而非新开新建页。边界：`KEY_AUTO_SAVE_EDITS` 关闭（本机默认）或内容全空时归属保持 -1，行为与用户已认可的残余豁口一致（singleTask 清栈丢新建内容是固有行为，录音不丢、挂到接力新页）。
- [x] **恢复后重录失败（P2）**：恢复快照 `configured` 改为诚实的 false；`restartRecordingInternal` 对未配置的麦克风会话走 `prepareConfiguredMode` 完整配置。真机验证：跨进程恢复的停止态点重录直接回到健康准备态，不再"麦克风不可用"。
- [x] **4GiB 收尾空间（P2，用户裁定 B 改版）**：封装失败（含磁盘写满）时半成品 WAV 与 raw 一并删除、不保留数据；按 `ErrnoException.ENOSPC` 识别空间不足并给专属提示（`STORAGE_FULL`，四语言）。
- [x] **绑定竞态（P2）**：`mBindRequested` 在 `bindService` 调用处置位、`onDestroyView` 按它配对 unbind；`onServiceConnected` 增加 `isAdded` 迟到回调防御。
- [x] **停止原因跨进程（P3）**：持久化补 `notice` 字段，恢复后 Dialog 仍能说明停止原因（容量上限、投影撤销等）。
- [x] OPD2515 真机回归：进程死后跨记事拦截 ✓、"前往"经重启直达原记事停止态 ✓、恢复后重录 ✓、新建记事归属升级全链 ✓（临时开启自动保存验证后还原）；测试记事已删除。全量 `:app:testDebugUnitTest` 905 例通过。

## 2026-08-16 - 外部审查修复批（grill-me 裁定后实施）

用户引入外部静态审查（10 项发现，逐条核实全部属实、无误报），经 grill-me 逐项裁定后实施：

- [x] **深浅色切换丢录音（P1）→ 原地换肤**：`DetailActivity` 外观处理不再 dismiss 录音 Dialog（旧行为触发 `finishSession(false)` 静默丢录音），改调 `onHostAppearanceChanged()`。Chrome 层取色集中为 `applyChromeAppearance()`（窗口背景、按钮面/图标/ripple/scrim、文字色、时钟 hostDark），Popup 重建；水面与记事色元素经查证不依赖深浅色，零改动。焦点监听里的 chrome 色改为实时取。真机：录音跨两次深浅切换持续未断，chrome 正确跟随，无渲染回归。
- [x] **第二记事接管会话（P1）→ 入口拦截**：附件录音入口检查活跃会话属其他记事时不打开 Dialog，改 UNDO 型 Snackbar（"正在为另一条记事录音"+"前往"直达原会话）。真机：新建页尝试录音被拦、GO 直达。
- [x] **停止态只在内存（P1）→ 轻量持久化**：停止成功持久化 {WAV 路径、记事 id、时长、模式}；服务 `onCreate` 从记录恢复 STOPPED 快照（returnIntent 用 `getOpenIntentForUpdate` 按 id 重建，不序列化含 Parcelable 的原 intent）；`finishSession` 与文件失效时清除；详情页 onResume 匹配记录自动恢复（划掉通知后的兜底入口）。真机最恶劣场景（force-stop 杀进程+通知全清）：打开记事即恢复停止态，时长与持久化值一致，丢弃闭环清理干净。
- [x] **授权状态跨重建（P2）**：`pendingProjectionMode`/`inFlight` 入 `onSaveInstanceState`，授权页期间进程回收后结果不再被按默认麦克风误处理。
- [x] **4GiB 上限（P2）**：`AudioRecordingLimits`（上限 = UInt32 - 64MiB 余量）+ 采集线程字节计数，逼近即自动"停止并保留"（`SIZE_LIMIT_REACHED` 提示，四语言）；`PcmWaveHeader` 对溢出显式 require 拒绝（防线失效时宁可失败不产坏文件）。按用户裁定仅单测覆盖（4 例），不真录 6 小时。
- [x] **raw 及时删（P2 附带）**：WAV 封装成功立即删 raw（双倍空间驻留从分钟级缩到复制瞬间）；预览停止的空占位 raw 一并即时清理。
- [x] **无障碍（P2，部分）**：Popup 选中项经 accessibility delegate 注入 `isSelected`（不调 `View.setSelected`，不碰 drawableState/ripple）；uiautomator 无障碍树实测 selected 正确。触摸目标保持 36dp（用户裁定）。
- [x] **temp 目录隔离（P3）**：服务专用 `temp/audio_raw_service`，与旧 `AudioRecorder`（调参工具）互不相扰；会话收尾只删自己的目录（真机确认 force-stop 残留也被下次收尾捎带清掉）。
- [x] **文档修正**：README/plan 的"停止态只读显示来源"旧文案更正为现状（隐藏）。
- 不修（用户裁定）：引擎任务真卡死自恢复（记 followups 已知边界）、Popup 首项滚出可视区、胶囊 48dp 触摸目标。
- [x] 全量 `:app:testDebugUnitTest` 905 例通过（含新增 `AudioRecordingLimitsTest`）。
- 注：回归中一度怀疑"通知返回复用 Dialog"路径下 HDR 未随 surface 重建复原，经用户质疑后以受控实验核实为**误判**（HDR 日志显示重建后 headroom 700ms 内拉回满值；最初的"偏灰"对比样本实为旧构建 Canvas 回退中的画面）。详见 followups 已撤销条目。

## 2026-08-15

- [x] 完成现有录音 Dialog、`AudioRecorder`、FableSol、附件保存和 `PopupPicker` 链路检查。
- [x] 完成产品决策与 Android 平台约束整理。
- [x] 编写 `plan.md` 与本执行清单。
- [x] 新增音频输入模型、持久化和可单测的 PCM 混音逻辑。
- [x] 新增服务专用 `AudioCaptureEngine`，支持麦克风、系统、混合采集及可变声道 WAV；保留旧 `AudioRecorder` 给调参工具使用。
- [x] 新增 `AudioRecordingService`，实现前台服务、通知、后台持续录音和异常收尾。
- [x] 接入 MediaProjection Activity Result、默认屏幕范围和撤销回调。
- [x] 新增继承 `PopupPicker` 的双行音频输入 Popup，并完成 Dialog 三态布局。
- [x] 增加 Manifest 权限/服务声明及中英文、繁体中文资源。
- [x] 增加 PCM 混音、WAV 头和 FableSol 隐藏期队列清理单元测试，并完成源码审查。
- [x] 运行相关单测、全量 `:app:testDebugUnitTest` 和 `:app:assembleDebug`。
- [x] 回填实现结果、平台限制与人工验收清单。

## 实现结果

- `AudioRecordDialogFragment` 改为绑定 `AudioRecordingService`，Dialog 只负责授权、状态呈现和 FableSol View 的可见期连接。
- `AudioCaptureEngine` 统一产出最终 PCM：麦克风单声道、系统立体声、麦克风+系统立体声；FableSol 消费最终 PCM 的单声道副本，WAV 与动画来源一致。
- 系统类输入使用 `AudioPlaybackCaptureConfiguration`，仅匹配 `USAGE_MEDIA`、`USAGE_GAME`、`USAGE_UNKNOWN`，不创建视频或屏幕编码链路。
- 自定义 `AudioInputPicker` 继承项目 `PopupPicker`，沿用圆角 surface、高程、缩放转场、项目色彩和 ripple，没有使用原生 `PopupMenu`、`Spinner` 或 Material exposed dropdown。
- 来源选择跨 Dialog 持久化；Android 8/9 自动归一为麦克风；系统授权拒绝或初始化失败后回退并持久化麦克风。
- 正式录音离开 App 后由前台服务继续，通知显示来源、计时和“停止并保留”；返回 Dialog 后 FableSol 丢弃隐藏期队列，只接收新的实时特征。
- 正式录音期间投影撤销或必需输入硬故障会停止整次录音并封装已有 WAV。准备态主动改选来源时先停止旧采集再结束投影，避免误报硬故障。
- Android 13+ 在录音入口按需请求一次通知权限；拒绝不阻止录音，并明确告知通知栏能力受限。

## 自动验证

- `:app:testDebugUnitTest`：通过（包含项目全量 Debug JVM 单测）。
- 定向测试 `Pcm16AudioMixerTest`、`PcmWaveHeaderTest`、`FableSolAnalysisBatchInboxTest`：通过。
- `:app:assembleDebug`：通过；APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
- 合并 Manifest 已确认包含 `FOREGROUND_SERVICE_MICROPHONE`、`FOREGROUND_SERVICE_MEDIA_PROJECTION`，服务类型为 `microphone|mediaProjection`。
- `:app:lintDebug` 执行完成分析但被代码库既有的 512 个错误和 1268 个警告判定失败；首个错误位于既有 `AutoNotifyReceiver.kt`。按本功能文件筛选报告后未发现音频捕获或前台服务 API 错误；报告命中的 `AddAttachmentDialogFragment` 旧 `view!!` 与录音布局原有两个 `android:tint` 均非本次引入，新增布局 tint 和多余版本判断已清理。

## 人工验收清单

按仓库约束未使用 ADB。以下项目需要在 Android 10+ 真机上完成：

1. 分别选择三种来源，确认准备态 FableSol、正式录音 FableSol 和保存后回放内容一致。
2. 验证麦克风 WAV 为单声道，系统/混合 WAV 为立体声；混合模式左右声场保留且麦克风居中。
3. 正式录音中切换到播放器或视频 App，确认录音继续、通知计时继续；返回后动画直接响应当前声音，不快速回放历史。
4. 从通知执行“停止并保留”，确认返回后为停止态、来源只读、部分录音可保存。
5. 从系统屏幕共享状态入口撤销，或锁屏触发系统结束投影，确认录音停止并保留，Dialog 显示原因。
6. 使用禁止 playback capture 的 App，确认录音不自动切换来源，持续无系统信号约 6 秒后出现提示。
7. Android 8/9 确认系统类选项可见但禁用；Android 14+ 确认授权页请求整个屏幕，而应用不产生任何屏幕视频文件。
8. 拒绝通知权限后确认录音仍可进行，并显示一次能力受限提示；允许后确认通知栏显示来源和停止操作。

当前状态：代码实现、自动测试和 Debug 构建已完成；剩余工作仅为上述真机平台行为验收。

## 2026-08-15 - 来源胶囊与 Popup 反馈修正

- [x] 将整行点击改为右侧 36dp 胶囊独立点击，左侧“音频输入”标签不再响应触摸。
- [x] 胶囊底色按 FableSol 未录音态同一 `0.16` 系数淡化，来源文字保持完整记事色，并替换为项目已有的下三角图标。
- [x] 把 Popup 目标宽度收窄为 272dp，按 16dp 可见屏幕边距动态收敛并恢复窗口裁剪。
- [x] 把可用选项的渐变触摸 ripple 移至前景层，并在点击后保留 90ms 反馈窗口再提交选择。
- [x] `:app:processDebugResources` 通过，布局和四套字符串资源均可正常链接。
- [x] 并行的空间照片设置改动稳定后，`:app:testDebugUnitTest` 与 `:app:assembleDebug` 复验均通过；APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
- [x] 保留标签后固定 8dp、胶囊自身权重填充和内部下三角靠右的结构；通过全局布局监听读取 `TimelyClockView.contentLeftPx()` / `contentRightPx()`，把整行两端改为当前字体的稳定实际着墨边界，并在 View 销毁时移除监听。胶囊边缘本身就是可见边缘，不套用播放进度条手柄的 2dp 或保存图标路径的 1dp 光学校正。
- [x] 实际着墨边界修复后再次运行 `:app:testDebugUnitTest` 与 `:app:assembleDebug`，均通过。

## 2026-08-15 - 修复系统授权返回后的无提示锁死

- [x] 删除 Fragment 在点选系统来源和启动授权时的提前持久化；Service 只在系统投影、`AudioRecord` 和准备态预览全部成功后记忆系统类来源。
- [x] 为来源偏好增加成功确认标记；旧版本遗留的未确认系统类值在升级后首次读取时一次性迁移为麦克风，直接修复已经进入异常默认值的设备。
- [x] 将开始按钮与来源胶囊的可用条件拆分：`IDLE`、未配置 `PREPARED` 和 `ERROR` 状态仍保留来源选择恢复入口，开始按钮继续等待完整配置。
- [x] 授权结果返回时立即重算控件状态，并捕获系统投影创建、前台服务启动、回调注册及音频引擎配置的未预期异常，统一落成带提示的麦克风回退。
- [x] 为准备操作增加 8 秒收尾保护，避免厂商音频栈异常时快照永久停在 `busy=true`；麦克风自身失败会进入可重新选择来源的 `ERROR`。
- [x] 新增 `AudioRecordingControlPolicyTest` 与 `AudioInputSelectionPersistencePolicyTest`，覆盖失败恢复入口和系统来源提交时机。
- [x] 全量 `:app:testDebugUnitTest` 与 `:app:assembleDebug` 通过；按仓库约束未使用 ADB，授权和厂商音频栈行为仍由测试设备验收。
- [x] 通过 `:app:publishDebugUpdate` 发布最终 Debug 更新 `202608151432`；远端 `latest.json` 已确认完整日志、APK URL 和 SHA-256，本地 APK 哈希与远端一致。

## 2026-08-16 - 真机定位授权结果丢失的真正根因并修复

- [x] 在 OPD2515（OPPO 平板，Android 16）复现"授权允许/拒绝后来源胶囊永久不可点"：uiautomator dump 显示胶囊 `clickable=false enabled=false`，而主按钮可点、值显示"System"。
- [x] 日志探针证明 `registerForActivityResult` 的回调从未执行：`requestProjection` 之后再无任何日志，`mProjectionRequestInFlight` 永久为 true，服务从未收到授权结果。
- [x] 根因：`DetailActivity.onActivityResult` 覆写后从未调用 `super.onActivityResult`，androidx `ActivityResultRegistry` 的分发链被掐断。此前一轮"授权返回后锁死"修复（2026-08-15）调整的全部是状态机下游，未触及该上游根因，故三个症状（锁死、无系统声音、不记忆）全部保留。
- [x] 修复一：`DetailActivity.onActivityResult` 首行补 `super` 调用，旧式附件逻辑限定到 `REQUEST_TAKE_PHOTO`/`REQUEST_CAPTURE_VIDEO`/`REQUEST_CHOOSE_MEDIA_FILE`，图片查看器分支限定 `REQUEST_ACTIVITY_IMAGE_VIEWER`——否则授权允许返回的 `RESULT_OK` 会误触发一次 `addAttachment(0)`。`ThingsActivity`、`SettingsActivity` 的同类覆写一并补上 `super`（registry 的 requestCode 从 0x10000 起，与旧式常量不冲突）。
- [x] 修复二：`AudioCaptureEngine` 捕获线程退出时不再抢引擎锁（`captureThread` 改 volatile + 自引用条件清理）。原实现下 `stopListening` 持锁 `join` 与线程 finally 的 `synchronized` 互斥，每次停止预览/切换来源固定烧满 600ms join 超时。
- [x] 保留三处低频诊断日志（授权结果、prepareSystemMode、fallback），包 `BuildConfig.DEBUG`。

### 真机验证（OPD2515，Android 16）

1. 允许授权：回调到达（`result=-1`），`configured mode=SYSTEM rate=48000 channels=2`，胶囊解锁，偏好提交为 `system`。
2. 关闭 Dialog 重开：记忆生效，自动弹出系统授权（README 设计行为）。
3. 拒绝授权：回调到达（`result=0`），回退麦克风 + 提示"授权被取消"，胶囊保持可点，偏好回退 `microphone`（设计内）。
4. 系统声音采集：OPPO 音乐后台播放（`USAGE_MEDIA`、无禁捕获 flag）时录 12 秒，WAV 立体声 48kHz，mean -17.8dB / max -2.4dB；对照无播放时录得 -91dB 数字静音。此前"授权了也采不到声音"是回调丢失的下游表现，采集链路本身无缺陷。
5. 录音中来源行隐藏、切走后前台服务（类型 mediaProjection）继续录音、重开 Dialog 直接连上进行中的会话，均符合设计。
6. 授权成功且系统预览运行中，把来源切回"麦克风"成功：值与偏好即时更新、`configured mode=MICROPHONE`、胶囊保持可点——即用户报告中被锁死的那个操作。
7. 全量 `:app:testDebugUnitTest`：142 个套件、901 个测试全部通过（结果文件时间戳确认本次真实执行）。

## 2026-08-16 - 图标接力与通知返回录音 Dialog

- [x] 用户选定方案 3a（点图标直达录音）与"通知先做最小改动"。
- [x] 通知第一步（重要度+显式分组）实测**不足**：channel 升 `IMPORTANCE_DEFAULT`（新 id `audio_recording_v2`，旧 channel 删除——importance 只能降不能升）+ `setGroup` 后，Android 16 仍强制把 groupKey 改写为 `Aggregate_AlertingSection`，聚合组行继续吞点击。
- [x] 第二步生效：按 SDK 36 源码 `Notification.hasPromotableCharacteristics()` 的条件（ongoing + 标题 + 无自定义视图 + colorized + 默认样式）给三个进行中通知加 `setColorized(true)` + `setColor(app_accent)`。实测通知**独立成卡**、自定义 groupKey 得以保留，点击 `triggerClick=true` 并正确送达 contentIntent。
- [x] 图标接力：`ThingsActivity`（singleTask）在 `onCreate`/`onNewIntent` 收到 launcher intent 且 `AudioRecordingService.activeSession` 时，用服务保存的 `activeReturnIntent` 直接跳回 `DetailActivity` 并附 `EXTRA_OPEN_RECORDING_DIALOG`。仅处理 `MAIN + LAUNCHER`，不劫持部件与通知入口。
- [x] 落点加固：服务以 `setSessionSource(intent, thingId)` 记录会话所属记事（新建记事记 -1 跳过校验，防"新建 id 每次重新生成"导致重启循环）；`DetailActivity.openAudioRecordingDialogFromNotification` 在复用实例的记事与会话不一致时按 returnIntent 重启自身。`AudioRecordDialogFragment.onDismiss` 仅在宿主未 finishing 时结束会话，摆脱对 `onDestroyView` 先清 binder 的隐性顺序依赖。
- [x] OPD2515（Android 16）真机验收：录音中回桌面点图标 → 自动重建 `DetailActivity` 直达录音 Dialog（时钟接续、FableSol 实时渲染）；点通知（colorized 卡片本体）→ SystemUI `triggerClick=true`，落点 [Things → Detail] 且 Dialog 完整恢复（截图存证，时长 00:02:00 接续）。
- [x] 全量 `:app:testDebugUnitTest`：901 例通过（结果文件时间戳确认本次真实执行）。
- 注：录音通知的秒表（`setUsesChronometer`）每秒刷新会让 uiautomator 在通知栏上永远等不到 idle、dump 吐陈旧缓存——通知栏上的自动化定位要改用截图。

## 2026-08-16 - 通知记事色、返回卡顿根修与倾斜空窗

- [x] **通知返回卡顿（用户报告"时钟/FableSol/按钮全卡"）根因**：`FableSolGlRenderThread.renderFrame` 对 `eglSwapBuffers` 返回 false 一律按致命错误处理，永久降级 Canvas 软件渲染。而 surface 销毁（HOME、任务切换）与 GL 在途帧存在竞争：主线程 `surfaceDestroyed→detachBlocking` 时，GL 线程当前帧的 swap 恰好撞上已销毁 surface → `EGL_BAD_SURFACE (0x300d)` → 回退。回退后水面模拟+绘制全在主线程（OPD2515 实测 100% janky、中位帧 150ms、主线程 100% CPU + JIT 96%），且回退状态随复用的 Dialog 永久存在——图标接力路径不卡只因它新建 View 重置了状态。
- [x] 修复：swap 前二次检查 `acceptingFrames`（收窄竞争窗口）+ swap 失败按 `eglGetError` 分级——`EGL_BAD_SURFACE`/`EGL_BAD_NATIVE_WINDOW` 为瞬态（停帧循环等下一次 attach，成功帧清零计数，同周期上限 6 次），其余或超限才回退。修复后同场景 0% janky、P50=5ms、GL 大波浪渲染正常、停止态导出按钮回归。
- [x] **通知底色改用记事颜色**：`setSessionSource` 增传 `ThingBackground`，`setColor` 取 `representativeColor()`；colorized 底色 API 只收单色、自定义视图会破坏 promotable 条件，真实渐变以 largeIcon 圆形渐变徽章（144px，按 `Orientation` 八向映射 `LinearGradient`）呈现，纯色记事不加徽章。真机确认绿色记事的通知即为记事绿。
- [x] **倾斜空窗**：录音引擎在 `offerGravitySample` 时保存最后一次已换算样本；Dialog 绑定服务时先用它 seed 水面姿态（GL/Canvas 首样本 snap 机制直接摆正），消除传感器融合预热期（可达数百毫秒）的"刚返回不响应倾斜"。新建记事会话与倾斜关闭时无样本、自然跳过。
- [x] 诊断期临时探针收敛为 `BuildConfig.DEBUG` 下的 `FableSolSurfProbe`（surface 生命周期 + attach/detach + swap 失败分级）保留。
- [x] 全量 `:app:testDebugUnitTest` 901 例通过（结果时间戳确认本次执行）。
- 注 1：`cmd statusbar expand-notifications` 在 OPD2515 上有确定性 artifact——展开后约 8 秒系统自动回桌面 3 秒再回 app（正是本次触发 swap 竞争的外因）；真实下拉手势无此现象，用户不会遇到。
- 注 2：录音通知的 `setUsesChronometer` 每秒刷新使 uiautomator 在通知栏上拿不到 idle、dump 输出陈旧缓存，通知栏自动化定位一律用截图。两条均已写入 `.claude/rules/adb.md`。

## 2026-08-16 - 停止并保留后的返回路径验收与时长修复

- [x] 澄清：通知内的操作是"停止并保留"（封装 WAV、换发一条可点的"已停止"通知），没有丢弃入口；丢弃仅在 Dialog 内。
- [x] OPD2515 逐条验收停止后的返回路径：点"已停止"通知（`triggerClick=true`，直达停止态 Dialog，四按钮含导出齐全）；点桌面图标（singleTask 清栈后接力重建，停止态完整恢复）；最近任务切回（同实例原样恢复）。"已停止"通知虽非 colorized（服务已退前台），但带显式分组后未被 Android 16 聚合——app 仅剩一条无分组常驻通知，不满足聚合条件。
- [x] 修复验收中发现的缺陷：从通知停止后重建的 Dialog 停止态时钟不显示实际录音时长（快照缺最终时长）。`AudioRecordingSnapshot` 增加 `recordedDurationMillis`，`stopRecordingInternal` 在停止时刻计算填充，`recordingToStopped` 用它设置时钟；准备与回退路径显式清零。真机复验：录约 14 秒经通知停止再从通知返回，时钟正确显示 00:00:14。
- [x] 保存/丢弃闭环以丢弃路径收尾验证（文件即时删除）；全量 `:app:testDebugUnitTest` 901 例通过。

## 2026-08-15 - 停止态隐藏来源行并恢复紧凑布局

- [x] 删除停止态 `showAudioInputForStopped()` 路径，录音态和停止态都将来源行设为 `GONE`。
- [x] 把停止态时钟目标顶部从 `132dp` 恢复为加入停止态来源行之前的 `80dp`，同步收回预留空白。
- [x] 新增 `AudioInputRowPresentationPolicy` 及五阶段回归测试，确保只有录音前状态显示来源行。
- [x] 定向回归测试和全量 `:app:testDebugUnitTest` 通过；未使用 ADB。
- [x] 发布阿里云 Debug 更新 `202608151505`；远端说明、APK URL 与 SHA-256 已回读，本地 APK 哈希一致。
