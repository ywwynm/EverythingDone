# 待办与验证门槛 / 空间照片效果

## 2026-08-02 关系约束与 EdgeTAM 集成后的剩余门槛

- [x] 修正 RF-DETR slot 0/1..90 分类 ABI，并以测试覆盖保留位和最后一个有效类别。
- [x] 建立类别、接触、相对面积、深度和布局共同约束的 attachment graph；人与人不自动
  合并，12 张跨场景图片结合三种深度输出的离线复算通过。
- [x] 完成 EdgeTAM 官方权重三图 ONNX 导出、数值运行、跨场景 RF box prompt PoC、窄带安全
  融合、按需下载/删除/自检/失败回退、派生版本记录和设置入口。
- [x] 以十个实际 ONNX 图的算子并集构建 `1.28.0-r6` 四 ABI runtime，并发布 EdgeTAM、
  runtime 与签名 stable catalog；APK 保持无模型、无 ORT 原生库。
- [ ] 在至少两台 arm64 Android 设备记录 EdgeTAM 冷/热 encoder、逐实例 decoder、完整生成
  P50/P95、峰值 PSS、低内存回退、温升和电量；同时验证取消、删除、重下载和 r5→r6 原子升级。
- [ ] 用人物发丝、脸部、文字、栏杆、车辆、动物、透明/反光物和拥挤多实例做倾斜盲评，记录
  每实例质量门命中与回退率。桌面 12 图结果只证明方向，不作为真机质量结论。

## 2026-08-01 模型栈更新后的优先项

- **EdgeTAM Android PoC（桌面与产品集成已完成，真机性能待验）**：官方 Apache-2.0
  checkpoint 已导出三图 ONNX，桌面数值、跨场景少 prompt 质量、裁剪 ORT 算子和受约束融合
  已验证；按需下载项与 r6 已发布。仍须完成上节 Android PSS、时延、温升和失败矩阵后，才可
  考虑默认启用。
- **MobileSAM2/µMatting/MetaDepth-CPU 观察**：仅在官方代码、权重和许可可核验后进入准入，
  不根据论文摘要预留具体下载项。
- **Matting provider 重构**：MODNet 标记为人像 Legacy fallback；建立通用窄边界 alpha
  refiner 契约，先比较确定性 RGB/depth refinement，再决定是否用合法数据蒸馏轻量模型。
- **生成式隐藏层升级**：MI-GAN/AOT-GAN 降为 Legacy/Lite。建立 `GenerativeFill` 输出
  RGB/depth/alpha/confidence 的统一契约；任务专用少步 W8A8 student 需要单独的数据、许可、
  teacher、训练与端侧 profiling 项目，不能直接采用 DreamLite/M2SVid 等非商用或 GB 级权重。
- **DA3 语义迁移**：把 `outputMetricDepth` 改为仅表达“输出为 depth 而非 disparity”的字段，
  清除设置、manifest、日志和阈值中的 metric 误称；需要真实尺度时只能使用经过焦距校准的
  metric 型号。旧派生数据必须保持可读。

## 当前仍未完成（2026-07-31）

以下项目不阻塞本次 debug 纵向体验，但在正式版发布、远程模型升级或扩大设备范围前必须处理：

- **P0/P1 与补图真机质量矩阵**：P0 单层、P1 LDI-lite 双层、显式遮挡、一次性背景补全以及
  MI-GAN/AOT-GAN 双模型都已实现。仍需在 Android 上记录 MI-GAN、AOT-GAN 512/768/1024 的
  初始化耗时、推理耗时、峰值 PSS，以及 P0/P1 的 GPU 帧时间；用人脸、头发、文字、栏杆、车体
  直线和屏幕截图做同图盲评，不能仅用桌面 CPU 数据替代。
- **软边界与多视角一致性**：当前 P1 使用 RGB 引导硬断边和双层前向网格，已经避免让显式遮挡边
  压低整图视差，但头发、半透明物体和运动模糊仍需要 soft alpha／matting 表示。后续应同时优化
  颜色、背景深度和边界覆盖，不能把更大的单帧补图模型当成多视角几何一致性的替代品。
- **裁剪 Runtime 的设备验收**：四模型算子并集、四 ABI 构建和 `1.28.0-r2` 兼容协议已经建立；
  发布包还需在 arm64 之外覆盖 armv7、x86/x86_64 安装与模型初始化，并补充 API 26 附近设备、
  低内存压力、删除、重下载及 r1→r2 原子升级测试。未经这些验证，不把“构建成功”写成全 ABI
  真机兼容结论。r2 已确认缺少图优化器新造算子的 kernel（AOT-GAN 真机自检失败，见 D43），
  当前 App 端以 NO_OPT 会话规避。
- **重建并发布 Runtime r3**（D43；2026-08-01 深度 PoC 第二轮已完成 arm64 单 ABI
  预演）：`ort-required-operators.config` 已是五模型并集（含 DA3 的 10 个新
  opset-18 算子与 `com.microsoft` 四融合算子）。arm64 r3-PoC 构建已验证：复用 r2
  Docker 镜像 `everythingdone-ort-android-custom:1.28.0-py310` 与增量树
  `build/onnxruntime-custom/workspace-build`，容器调用方式与 r2 相同（见
  `docker inspect everythingdone-ort-r2-final`），仅换 settings 为 arm64 单 ABI +
  新算子清单，约 20 分钟出包；产物 `build/onnxruntime-custom/package-r3poc/`，
  核心库较 r2 +266 KB，JNI 逐位相同，真机上 DA3/DAV2/ZipDepth 三模型 NO_OPT 与
  EXTENDED 全部通过、数值与 r2 逐位一致。剩余步骤：四 ABI 全量构建（settings 用
  仓库 `ort-android-build-settings.json`）；`prepare-spatial-runtime.ps1
  -PackageVersion 1.28.0-r3` 打包；离线 Ed25519 签名并发布新 catalog；App 端把
  `SpatialRuntimeStore.REQUIRED_PACKAGE_VERSION` 提升到 r3 并把引擎会话恢复
  EXTENDED/ALL；四模型（+DA3 共五）真机自检 + 性能重测通过后才算完成。
- **NO_OPT 真机验证（OnePlus 已过，其余设备待覆盖）**：2026-07-31 在 OnePlus PLZ110 上
  完成 r2 Runtime 安装（旧 `1.28.0-full-r1` 完整版被正确清理）、四模型 NO_OPT 推理
  （ZipDepth P0 生成、DAV2+MI-GAN 重新生成、AOT-GAN 768 双层生成、MI-GAN/AOT-GAN 下载
  自检）、P0-only 回退、移除效果与设置页新样式，全程无 ORT 错误，已随
  `202607311215` 发布。仍待覆盖：Samsung/OPPO 等其余设备、AOT-GAN 512/1024 两档、
  NO_OPT 推理耗时的系统重测（PoC 文档数字是完整 Runtime + ALL_OPT 的，本轮生成体感在
  秒级但未逐段计时）。
- **r1 悬案（已解）**：OnePlus 真机标记显示 r1 是 `1.28.0-full-r1` 完整版 ORT，不是裁剪
  包；深度模型当时通过 ALL_OPT 自检靠全量 kernel，与桌面转储不矛盾。r2 是首个真正的
  裁剪包。
- **zh-rHK / zh-rTW 空间字符串缺口**：两个繁体语言文件只有少量空间字符串（其余回退
  英文），需要补全整组翻译。
- **扩大兼容矩阵**：当前只在两台 API 36、arm64、高内存设备上完成真机链路。仍需覆盖 API 26
  附近、3/4/6 GB 内存、armv7、低端 GPU、长图、极端横竖比、内存压力与热降频。
- **实现模型版本升级协议**：v1 的模型 ABI 与 `1.0.0` 版本被 App 精确固定，远端 catalog 不能单独
  换权重。D17 所述“用户主动更新、验证后原子替换、失败保留旧版”需要 App 支持版本列表、旧新包
  并存事务和 catalog 状态语义后才能启用。
- **补齐计费网络切换矩阵**：未授权计费网络的任务会被 `NetworkType.UNMETERED` 暂停，已避免
  Wi‑Fi 切到移动网络后静默续传；仍需验证已授权下载在不同计费网络、流量节省模式和进程重启之间
  的产品语义。
- **补齐分发故障矩阵**：已覆盖发布 catalog 的签名成功与 payload 篡改单测；仍需在 UI/Worker
  层验证 catalog 超时、错误 key、回滚、截断模型、磁盘耗尽和 App 重启，并确认本地有效模型不受影响。
- **派生数据生命周期**：全屏删除附件、按图移除与清除全部已接入；详情页可撤销的附件删除以及源
  文件被外部清理后的孤儿扫描仍需统一到附件持久化事务，避免提前破坏撤销语义或长期遗留派生目录。
- **许可可见性**：模型对象旁已发布 ZipDepth MIT 与 DAV2 Apache-2.0 许可文件，设置页也显示许可
  名称；正式分发前应让用户能从 App 直接查看完整许可文本或签名 catalog 中的许可 URL。
- **动态内容另立功能**：Animated Image、Motion Photo 和普通视频的跨帧一致深度、解码同步与缓存
  设计在 `docs/features/spatial-video-effect/` 推进，不在静态图片 v1 中逐帧硬套。

## 已执行的设计与验证清单

以下条目保留为实现依据和后续回归矩阵；其中基础纵向链路已完成，尚未覆盖的设备/故障组合以上一节
为准。

## 模型可行性 PoC

- 把 ZipDepth mobile/NPU checkpoint 导出为 ONNX，在 Android 上核对算子支持、数值一致性、模型初始化
  时长、推理时长和峰值内存。
- 用同一批真实附件图片与 Depth Anything V2 Small 做盲测，重点观察人物轮廓、头发、栅栏、树叶、
  文字和低光图片。
- 分别产出 ZipDepth 与 Depth Anything V2 Small 的 Android 模型包，记录格式、精度、真实字节数、
  算子覆盖、初始化/推理时长、峰值内存和数值偏差。
- 为两个模型分别形成发布门槛报告；任一失败时停止后续正式集成，并提交可复现证据供范围重决策，
  不以单模型构建冒充完成 v1。
- 画质通过后再评估 LiteRT 转换；不要预先承诺任一模型可无损转换为 `.tflite`。
- 把质量指标分为“硬有效性”与“软风险”：前者决定是否可发布 derivative，后者只影响默认强度和
  提示；用测试固定两者边界。
- 根据真实测试定义逐模型准入矩阵：Android/API 与 ABI、运行时和算子、可用内存、初始化/推理耗时
  及热状态；不要使用主观机型白名单。
- 将准入拆为下载前静态预检与下载后模型初始化自检；验证一个模型失败时另一个仍可独立安装使用，
  两者失败时不出现云端或无界 CPU 回退。

## 双模型管理

- 建立版本化 manifest：模型家族、版本、运行时兼容版本、URL、字节数与 SHA-256。
- 使用独立、不可变的阿里云模型对象路径；模型 manifest 更新到新 URL，不原地覆盖旧版本。
- 建立 stable/staging 两套 catalog；debug/release 默认 stable，只有 debug 显式开发配置允许
  staging，release 构建必须硬性拒绝 staging 配置。
- 为 stable/staging 分别生成并离线保管 Ed25519 私钥；仓库、APK、模型服务器、发布日志和构建
  产物均不得包含私钥。
- 定义单一、版本化的签名封装与确定性 payload 字节格式，避免不同 JSON 序列化方式造成验签差异；
  用固定测试向量覆盖合法签名、篡改字段、未知 `keyId`、错误编码与截断内容。
- 构建配置必须把 catalog URL 与唯一对应公钥成对固定：release 只允许 stable；debug 默认 stable，
  显式 staging 只信任 staging，不接受由远端 catalog 扩展信任根。
- 发布任务先验证模型对象、生成 catalog payload、使用本地私钥签名，再原子发布签名封装；不得把
  私钥上传到阿里云或交给常驻服务器进程。
- 设计正式密钥轮换演练：App 先发布新旧公钥并存的过渡版本，catalog 切换签名后再由后续 App 移除
  旧公钥；catalog 本身不得授权新公钥。
- 覆盖 catalog 不可用、验签失败和回滚攻击时的 fail-closed 行为，确认已安装模型与已有 derivative
  仍可查看，且失败不会覆盖本地最后一次有效状态。
- 复用 `publishDebugUpdate` 的服务器连接配置与 SSH/SCP/原子 manifest 模式，但使用独立模型发布
  任务；不得放入 `/debug/apk` 或继承“只保留最近 5 个 APK”的清理命令。
- 在开发电脑从官方上游获取原始模型；外网需要时使用 `127.0.0.1:7890`，保存上游版本与原始
  SHA-256。
- 上传前验证 ZipDepth MIT 与 Depth Anything V2 Small Apache-2.0 的再分发要求，并随 App/模型
  manifest 提供 LICENSE/NOTICE。
- 下载使用 `.part`、可取消进度、校验后原子完成；中断和校验失败不得留下可选模型。
- 比较 DownloadManager 与持久 Worker 在 app-private 存储、HTTP Range、跨重启恢复、进度通知和
  SHA-256 后处理上的实现；选择不改变 D15 产品语义的方案。
- 定义取消/中断 `.part` 的保留期限与磁盘空间上限，并覆盖过期清理测试。
- 下载完成后通过前台 request token 判断是否承接生成；Activity 已离开时只提示模型就绪。
- 覆盖 Wi‑Fi→移动网络、移动网络→Wi‑Fi、流量节省模式切换和续传时的二次确认；未经确认不得在
  新计费网络上自动恢复。
- 设置中分别展示下载、更新、选择、删除和占用空间状态。
- 派生深度记录模型家族与版本，避免跨模型误用缓存。
- 补齐两个模型及权重的开源许可/NOTICE。
- catalog 声明 `minRuntimeVersion`、模型格式/精度和废弃状态，App 在下载前做兼容性判定。
- catalog 区分“有更新”“不再推荐”“禁止生成”，禁止生成必须带用户可读原因与替代版本。
- 更新验证失败时继续保留旧模型；只有新包初始化自检也通过后才能原子切换。
- 删除模型包不得连带删除已生成结果；查看路径不得依赖生成模型仍然存在。
- 重新生成使用临时目录，成功后原子替换；失败或取消时保留旧结果。

## 渲染可行性 PoC

- 先做原图 + 深度纹理 + 触摸位移的最小 OpenGL 演示，验证全屏 60 fps 与边缘伪影。
- 对比单层 UV 位移和深度网格，记录空洞比例、前景边缘拉伸和 GPU 帧时间。
- 验证 `TYPE_GAME_ROTATION_VECTOR` 的中心校准、阻尼、旋转方向与无传感器触摸回退。
- 覆盖全局设备倾斜开关的持久化、进入/恢复时注册、关闭/暂停/退出时注销，并确认关闭后没有残留传感器
  采样而单指拖动保持可用。
- 按屏幕像素、图片比例和最大过扫描计算颜色纹理尺寸；覆盖低内存设备、超长图和横竖屏，不为不存在
  的空间模式缩放分配完整原图纹理。
- 覆盖系统返回键、返回手势与 Android 预测性返回：空间模式第一次返回到同一图片的普通全屏状态，
  传感器和空间渲染停止，PhotoView/ViewPager/HDR 恢复；第二次返回才离开查看器。

## 前台生成事务

- 阶段进度只报告真实边界：解码/规范化、模型推理、深度后处理、派生文件写入与验证。
- Activity 返回、切页、取消和进程终止时，不得发布半成品。
- 重新生成写入临时目录，完整校验后原子替换；旧结果直到替换成功前始终可查看。
- 记录每阶段耗时与峰值内存，验证“数秒级前台任务”的假设；若不成立，重新进行产品决策，不静默改成
  后台任务。

## Spatial Photo Derivative 存储

- 使用 App 私有 files/no-backup 类持久目录并明确排除系统 Auto Backup，不放入普通 cache。
- 设计源附件稳定身份与 derivative manifest；每张源图片最多一份，记录模型家族/版本、生成算法
  版本、尺寸、Spatial Effect Strength 和完整性校验。
- 生成前以临时与正式结果的峰值需求检查可用空间；空间不足不启动。
- 删除附件、按图片移除、清除全部、孤儿扫描和进程中断分别覆盖事务与清理测试。
- 设置显示模型包、完整 derivatives、临时/下载文件的分项占用，避免把不同生命周期混为一个数字。

## Spatial Effect Badge

- 在现有 `ll_media_badges` 增加 16dp icon-only `ImageView`，复用 Motion Photo 图标的视觉层级。
- 图标使用自有矢量资源，不使用 Apple 标记，也不采用容易误解为 AR/真实 3D 重建的品牌化图形。
- `contentDescription` 表达“已生成空间照片效果”，即使无文字也满足无障碍。
- derivative 创建、原子替换、移除、源附件删除和 RecyclerView holder 回收后，徽标状态必须即时正确。
- Thing Card 不增加徽标；全屏入口另行表达 ready 状态。

## 全屏空间效果入口

- 在 `menu_image_viewer.xml` 增加不进入 overflow 的独立图形 action，并处理窄屏、横屏、字体放大及
  三个 Toolbar action 共存时的布局。
- 复用 Spatial Effect Badge 的分层视觉语言，但分别输出符合 Toolbar 与详情网格尺寸、状态和对比度
  要求的矢量资源。
- 按当前页能力与 derivative 状态刷新 action；翻页、生成完成、移除结果和附件删除后不得沿用上一页
  状态。
- 覆盖 Toolbar chrome 显隐、TalkBack action title/状态说明，以及静态图、Animated Image、
  Motion Photo、视频之间切页时的可见性。
- 激活态图标必须同时具有视觉选中状态与无障碍 checked/selected 语义；再次点击退出模式，Toolbar
  导航直接回详情，系统返回先退出模式，三条路径分别覆盖测试。

## Spatial Effect Strength 控制条

- 在空间渲染层之上增加底部紧凑控制条，并与现有 `mSystemUiVisible`/chrome 状态同步；隐藏后设为
  不可见且不参与命中测试。
- 使用项目统一着色的原生 SeekBar，但按每张图片的安全强度范围映射，不直接暴露可越界的渲染参数。
- 滑杆移动实时更新 uniform/渲染参数，停止拖动后持久化；覆盖取消触摸、Activity 暂停及进程恢复时
  不写入半个状态。
- 验证滑杆区域、画面视点拖动、系统边缘返回手势和底部导航手势之间的命中优先级。
- 为 TalkBack 提供名称、当前相对值、范围与标准增减操作；字体放大时不得遮挡关键画面或超出安全区。

## P1 LDI-lite / 生成式补图（已实现，待真机质量验收）

- [x] 用真实 RGB + depth fixture 验证显式断边网格、完整请求视差与前向深度合成；
- [x] 证明确定性背景传播在主体边缘肉眼不合格；
- [x] 用官方 MI-GAN ONNX pipeline 验证窄带生成式背景补全的质量收益；
- [x] 将 600px 质量上界网格拆为 GLES2 `uint16` 重叠行块；
- [x] 定义并实现 Spatial Photo Derivative v2，保留 v1/P0 查看兼容；
- [ ] 在 Android 上测量 MI-GAN 模型大小、运行时兼容、峰值内存、延迟和真实 corpus 质量；
- [x] 实现生成式补图组件的签名下载、删除、更新、兼容性门禁与失败不替换行为；
- [ ] 用人脸、头发、文字、栏杆、车体直线和屏幕截图做 P0/P1 盲评与 GPU 帧时间回归。

## 补图模型与 Flow Matching 候选（2026-07-31）

完整依据见
[补图、Flow Matching 与任务专用生成模型调研](research-2026-07-31-inpainting-flow-matching.md)。

- [x] 为 schema v2 增加同图、同强度、同视点的 P0/P1 即时切换，并保持逐图选择；
- [x] 把最大允许相机轨迹各方向的显露区域合并为 union disocclusion mask；
- [x] 为前景/背景边界增加 soft alpha 或 matting 表示，并区分纹理边缘与深度遮挡边缘；
- [ ] 建立至少 30 张真实显露带 corpus，并加入一组可从双目/相邻帧取得 ground truth 的样本；
- [x] 在桌面固定输入、遮罩和硬合成，统一比较 MI-GAN Places2、AOT-GAN Places2
  512/768/1024 与 Big-LaMa Places2；AOT 与 Big-LaMa 都没有在真实人物显露带和规则室内样例上
  稳定胜过 MI-GAN，因此 stable catalog 只增加体积和内存仍可控、先验不同的 AOT-GAN；
- [x] AOT-GAN 使用官方仓库直接发布的 Places2 checkpoint，仓库 Apache-2.0 未对权重声明额外
  条款，随模型发布完整 LICENSE；Big-LaMa 不发布，若未来重新准入仍须重新核验当时采用权重的
  来源与独立条款；
- [x] 优先做 AOT-GAN Places2 ONNX PoC，记录实际权重大小、算子、数值、推理时延与峰值内存；
- [ ] Moebius 只进入实验 PoC：确认官方权重许可、VAE 版本、ONNX 算子、FP16/INT8 质量、20 步
  取消语义、峰值 PSS 和热负载；未经验证不上传 stable catalog；
- [ ] 把 `SpatialInpaintingModel` 从单一 MI-GAN 枚举升级为可版本化 provider/descriptor，支持多文件
  模型、输入契约、精度、scheduler、steps、seed、许可和逐模型内存门槛；
- [x] 当前补图模型选择只影响新生成/重新生成；derivative 记录模型 ID、版本与 AOT 工作分辨率，
  删除模型不影响已有结果；确定性单次模型没有随机 seed，后续生成式 provider 再把 seed 设为必填；
- [x] 暂不提供名为 “Flow Matching” 的用户选项；等待出现通用自然场景、可再分发且 Android 过线的
  遮罩补图权重，或进入自研 RGB + mask + depth + camera 条件 student；
- [ ] 为 Spatial Video Effect 设计 keyframe 隐藏背景、跨帧传播与稀疏置信度修复，不逐帧独立运行
  静态 diffusion/Flow Matching。

仍未进入本轮实施：

- HDR Gain Map 感知的空间重投影与 HDR 输出 surface；
- 超过 2048 px 的分块处理。
- 手动深度画笔、前景蒙版与局部遮挡修正编辑器。

2026-07-31 的用户反馈、真实素材 Jacobian/网格复算、MI-GAN 窄带对照以及 Apple 官方公开的
生成式多视角路线，已经构成升级表示层的画质证据。HDR 与手动编辑不能随分层无条件引入。

## 动态内容的空间化

用户希望后续让 Animated Image、Motion Photo 等动态内容获得 **Spatial Video Effect**。该方向不
纳入静态图片 v1，已建立独立功能目录
[spatial-video-effect](../spatial-video-effect/README.md)，后续需解决：

- 视频深度的跨帧一致性，不能逐帧独立估深；
- 解码、深度推理、遮挡处理与显示时间戳同步；
- 交互视点变化时的实时重投影性能；
- 长内容的缓存规模与是否需要预生成；
- Animated Image、Motion Photo 和普通视频是否共享同一能力；
- 派生缓存如何与原动态媒体版本和时长绑定。

## 明确不在范围内

- Apple Spatial Photo、Spatial HEIC、MV-HEVC；
- 双目/SBS 媒体导出；
- 把生成结果宣称为真实三维重建。
- **视差幅度提升**：调研见
  [视差幅度与生成效果提升调研](research-2026-07-31-parallax-uplift.md)；短期三项已按
  D45 实施（统一斜率尺子 `MAX_RENDER_SLOPE=9` + P1 连通面钳制、预算 P99.5 分位 +
  0.32、幅度/过扫描 0.09），JVM 测试通过，OnePlus 真机验证后随 `202607311418` 发布。
  「立体」高强度扭曲随后按 D46 修复：陡而未断的连通边升格为断边（渲染时生效，debug
  logcat tag `SpatialWarpBudget` 提供双路径实测梯度与位移上限）。中期项的生成端部分
  （深度边缘锐化、软边界 matting）与长期项（小型 MPI / 单图 3DGS）未启动。
- **小型 MPI PoC（首轮已点亮，进入调优）**：见
  [小型 MPI 与单图 3DGS 可行性调研](research-2026-07-31-mpi-3dgs-feasibility.md) 与
  D48。10 层 800 长边软切已在真机端到端渲染，头发轮廓无锯齿（软 alpha 假设成立）；
  待调优：3×3 羽化只作用于 alpha 变化区（当前误及不透明内部造成整体偏软）、不透明
  像素单层指派（消除跨层重影）、平面分辨率与层数扫描、帧时间/显存正式测量。验收
  门槛不变：柔性边缘优于 P1、帧时间 ≤1.5×P1、显存增量 ≤40 MB、斜面无明显分层。
  单图 3DGS 全部候选被许可（UniDepth/SHARP 非商用）或领域（Splatter Image 单物体）
  挡死，列入观察不启动；自训 student 需独立立项。
- **深度模型升级（已随 202608010539 发布，完结）**：三轮 PoC + D53 适配结论见
  depth-poc-2026-08-01.md 与 decisions D52/D53；真机立体满强度六方向 + 稳定双向
  复验通过（证据 `build/spatial-depth-poc/out/device-ab-20260801/`）。残留优化
  线：断边路径格级台阶（候选网格加密或 matting alpha 纹理接管）、显露区补图
  保真度（暗景 MI-GAN 平涂，AOT-GAN 档可缓解，归补图质量线）。MoGe-2 ViT-S
  （MIT）留作备选。
- **Matting（桌面两轮已过，第三轮=r4 + App 融合实现）**：结论见
  matting-poc-2026-08-01.md——选型 MODNet（发丝过渡 5/8.2 px、26 MB、0.05 s；
  BiRefNet_lite 躯干更稳但重两个量级、发缘更硬，记为备选）；融合公式定案
  「depthMask + 边缘带 k=9px 内 alpha、带外深度」，蓝本验证了躯干安全性，发缘
  决定性收益需真机渲染器实现后验证。第三轮：MODNet 导出协议与数值核对 → 算子
  并入清单（opset 11 缺口）→ **r4 Runtime 重建** → App 端 alpha 纹理融合
  （LDI 断边显示接管、MPI 层 alpha、派生 schema 扩展落盘 alpha）→ 真机发丝
  验收（放大 + 方向矩阵）→ 按 D7/D12/D13 发布。

## 2026-08-01（视觉质量根治路线）

- [x] **P0 / 参考视点恒等**：零位原图纹理直通，固定 overscan/inset 改为逐轴动态边界；
  已完成普通查看器同区域截图量化与两张探针真机回归。后续基准仍需把同变换 golden
  自动化，并解释 Canvas/GL 采样造成的 PSNR 差异。
- [x] **P0 / 混合拓扑 tracer bullet**：全分辨率 RGB + 网格深度样本、z-buffer、连续面
  共享顶点网格与 cut 两侧边界 splat 已完成；同设备两张探针的左右端点已按脸型、五官
  相对位置、直线、断边显露和发缘放大复验。独立 splat 与 4% 钳制的失败方案不再保留。
- [~] **P0 / 物理倾斜终验**：用户已在真实陀螺仪输入下用小圆轨迹发现旧取景边距导致的
  四次缩放呼吸；D76 已改为旋转不变的径向边距，并增加 3 档半径、每档 72 个方向的回归。
  发布后仍需用户复核真实传感器下的环形移动、端点驻留和回中；ADB 拖动不能替代该时序终验。
- [ ] **P0.1 / 完整可见性解析**：增加显式 confidence、hole mask、覆盖率统计与
  per-pixel/EWA 质量路径；建立左/右/上/下、环形与回中自动回归，不把两张探针当阈值集。
- [x] **P1-A / 对象中心 tracer bullet**：已撤掉 `applySoftRenderLayer` 的宽深度坡，以
  MODNet + 隐藏背景建立独立人物层，人物按代表深度整体运动，轮廓只在 alpha 域软合成；
  详见 D63 与 `ldi-lite-v5-object-layer`。
- [ ] **P1-B / 通用 ownership graph**：已完成 RF-DETR 自动实例提议、互斥 label 图和
  多实例刚性层；尚需补齐场景平面/homography、unknown region 图，以及手持、佩戴、倚靠
  等对象的 parent/attachment 关系。禁止按脸、头发、衣服语义标签直接拆成独立运动层。
- [ ] **P1-C / 真正隐藏层**：cut 两侧复制样本而非删三角形；构建带
  RGB+depth+alpha+confidence 的 H1，必要时添加 H2；按每条遮挡边补实际可显露区域，
  移除 MPI `compositeBehind` 后重复 over 的路径。完整方案见
  `research-2026-08-01-object-centric-layering.md`。
- [x] **P1 前置 / Matting 合成语义**：已加入近似 F/B color decomposition 与预乘 over，
  不再把 composite source RGB 直接乘 alpha。真正 ownership/confidence 仍随 H1/H2 实现。
- **跨场景基准**：建立 50～100 张 held-out 静态图及真实相邻视角子集；当前两张附件只
  作为两条回归，不参与阈值拟合。加入 reference identity、源纹理锐度、拓扑、hole/
  confidence 曲线、loop closure 与性能门槛。
- **P2 / 端侧 student PoC**：用多视角/视频监督比较 feed-forward、few-step diffusion、
  rectified-flow student，输出持久 layered splat 而非独立目标帧；通过质量、许可、模型
  大小、峰值内存和真机耗时后，才加入按需下载 catalog。
- **Spatial Video Effect 预留**：禁止逐帧独立补图；设计时序深度稳定、关键帧持久层、
  flow/特征传播和联合相机轨迹测试，确保静态表示能扩展到时间维。

## 2026-08-02（实例 ownership 模型接入后）

- [ ] 在用户真机上对“关闭实例分层 / RF-DETR Seg Nano”做真实陀螺仪大视角 A/B，记录
  冷启动、P50/P95、峰值 PSS、温升、取消和恢复；本轮没有 ADB 授权，桌面 ORT 与静态算子
  验证不能替代该项。
- [ ] 建立 50～100 张 held-out 静态集与真实相邻视角子集；现有两张附件继续只作回归探针，
  不参与置信度、面积和支撑面阈值拟合。
- [~] RF-DETR box 少 prompt 驱动 EdgeTAM 的桌面边界细化已通过：71 个实例中 62 个过门，
  窄带融合后 59 个边界梯度对齐提升，进程峰值 RSS 约 758 MiB；正在接入按需模型与 r6
  Runtime，仍待 Android P50/P95、峰值 PSS、取消和低内存回退验收。
- [x] 已为手持物、穿戴物和相互接触对象建立 parent/attachment ownership 规则；只有类别、
  接触、相对面积、深度与布局共同成立才合并，人与人及偶然轮廓接触保持独立。
- [ ] 把 EdgeTAM tracking 纳入 Spatial Video Effect PoC，验证跨帧 ID、alpha、depth 和隐藏
  纹理稳定；禁止逐帧独立 RF-DETR/补图冒充时序一致。

## 2026-08-02（v18 真机回归后）

- [ ] 建立拓扑保持的 ownership 轮廓细化：只处理外轮廓的格级台阶，必须保持发丝、人物
  相接边界和衣物内部孔洞；用 50～100 张 held-out 静态集验证，禁止按本次两张附件调阈值。
- [ ] 重新评估可商用、已发布权重且能在 Android 端运行的 2025～2026 matting/boundary
  refinement 模型；MODNet 继续作为 Legacy fallback，不把一个 texel 以上的全局平滑当作
  模型升级替代品。
- [ ] 切换深度、补图、matting、实例分割或边界细化模型时，应明确提示并自动使不匹配的
  旧派生失效，避免设置已切换而查看器仍复用旧模型生成结果。

## 2026-08-04（第一性原理复审后的 vNext 待办）

- [x] 冻结 `ldi-lite-v19-segmentation-prior`，建立独立 vNext renderer/schema；旧
  derivative 保持可查看、旧 schema 保持可读，不因开发中 A/B 自动迁移。用户重新生成
  同一附件后会原子覆盖该附件的旧派生；如需长期逐附件 A/B，仍要另做双派生存储。
- [ ] 已把硬深度台阶、连续斜坡转置、宽过渡 ridge、孤立尖峰、空间感下限、视点包络
  插值和采样率无关滤波加入永久回归；仍需补薄结构、文字网格、分层遮挡、参考视点
  像素保真、局部相似残差和环形视点闭合。目标仍包括
  参考视点像素保真、局部 Jacobian 奇异值、去除最佳相似变换后的局部残差、转置/
  旋转等变性、环形视点闭合和全局 scale breathing 指标。
- [x] 在 vNext 主路径移除组引导深度修正、同实例无条件禁断、实例残差限幅、先行后列
  深度吸附和固定约 15% 取景裁切；`0.12` 只保留为生成期请求上限，运行时改读派生中的
  安全视点包络。
- [x] RF-DETR/EdgeTAM 退出 vNext 生成和设置选择路径；保留旧安装删除、进行中下载取消
  和底层模型基础设施，不自动删除用户已经下载的文件。
- [x] 传感器低通改用事件时间戳和 65 ms 时间常数；中心视点零裁切、零 matting 显露，
  背景网格扩到照片画框外，禁止 z/dolly 缩放呼吸。
- [ ] 实现“可断开的 adaptive textured charts/surfels + 持久隐藏背景”tracer bullet：
  低频几何驱动视差/遮挡，高频原图作为局部纹理，图元重叠覆盖裂缝，不跨遮挡边
  拉伸连续网格。
- [ ] derivative 已保存 16 方向包络并由运行时连续插值，第一阶段先用最终 chart 几何的
  4% Jacobian 门限求对称上限；仍需把隐藏区覆盖、画外背景质量、法线／深度置信度和
  局部相似残差纳入逐方向搜索。
- [ ] 完成干净 renderer 后，以同输入、同视点和同质量门槛 A/B 官方完整 DA3 Small
  与 MoGe-2 ViT-S point map/内参/法线；不得以深度图主观观感代替最终渲染验收。
- [ ] 建立 50—100 张 held-out 静态 corpus 与真实相邻视角子集；当前两张附件只保留
  为回归探针，不参与阈值拟合。
- [ ] 持续观察 MoGe-3 官方代码和权重发布、许可与端侧尺寸；截至 2026-08-04 仍不可
  作为实施依赖。
- [ ] 单独评估自研或取得商业许可的轻量 layered textured surfel/2DGS/3DGS student；
  SHARP、Flash3D、LGTM 只作架构/教师参考，不直接分发受限或许可不明权重。
- [ ] 几何稳定后再评估 2025—2026 diffusion/Flow Matching 隐藏背景 provider；不得
  将逐帧生成接入实时交互，也不得用补图升级掩盖可见表面形变。

## 2026-08-05（学习式新视图表示门禁）

- [x] InfiniSplat 已在原单人零视角硬门槛淘汰：保持比例、关闭 floater filter 和官方固定横屏
  预处理三组对照均有明显人物涂抹／重影，仓库自带室内阳性对照正常；不再做全量九图或 Android
  迁移，也不以其作为可发布 student 的 teacher。
- [x] 用原单人、原双人和相同留出 corpus 评估许可受限的 SHARP，仅作为当前质量上限对照；先
  检查零视角，再录水平、垂直、对角与圆周连续轨迹。官方权重仅限研究，不得上传阿里云、并入
  App 或用于产品 student 蒸馏。
- [ ] 以九场景同轨迹门禁验证高分辨率 textured micro-surfel：图元内部保持源纹理刚性，图元
  之间使用真实深度和 z-buffer 产生可感知视差，重叠仅补采样裂缝且不得跨深度边平均；若仍有
  马赛克、孔洞、轮廓重影或主体内部退回纸片，直接否决，不迁移 Android。
- [~] 九场景动态反馈脚本和单人／双人快速极值对照已建立。实例整体位移暂以 `12 px` 为工作
  基线；`6 / 12 px` 全局实例深度 Gaussian 平滑因压缩宏观体积而淘汰。当前最小化到固定
  `2×2` footprint 在手、透明酒杯和细轮廓处的覆盖／可见性错误，下一步用局部 Jacobian 控制的
  椭圆 EWA splat 做单变量 A/B；通过双探针后再生成完整九场景连续视频。
- [ ] 独立训练或取得商业许可的轻量多层 textured Gaussian student；训练数据使用有授权的
  多视图／合成场景，不使用 SHARP 研究权重作为 teacher。输出 scene/direction confidence、
  隐藏层及安全视点包络，并分别门控零视角、主体内部视差、脸／文字形状和显露区质量。
- [ ] 跟踪 PixWorld 官方 `PixWorld-480P-4steps` 权重与推理代码。其 2026-07 论文把
  Flow Matching 直接监督在多视图渲染上，单图生成方向可能补足隐藏区；截至 2026-08-05，
  官方仓库仍只有论文与素材，发布计划中的模型和代码尚未提供，不能实施、复现或用于产品。
- [ ] LagerNVS 不进入人物通用路线：官方明确列出 humans/animals 与高频区域为已知弱项，
  且采用 FAIR Noncommercial Research License；TokenGS 面向多视图重建，不替代单图门禁。
- [x] EWA、放宽 z、扩大 footprint、目标域填充与源空间持久 LDI 已完成隔离；均未通过手、
  透明杯、人物轮廓和躯干拓扑门禁，不迁移 Android，不再继续调传播／膨胀常量。
- [x] Moebius 隐藏纹理 PoC 与 PVSDNet Lite 九场景／连续轨迹门禁已完成并淘汰。前者只允许
  未来用于隐藏层低置信度纹理候选，后者因 256 输出、全局形变、周期位置编码退化和体积失败，
  不进入产品链。
- [~] 建立独立训练的 Gaussian 残差资产 tracer bullet：Charge 相机／深度语义和单层／规则
  K 层／非规则三维真值对照已完成，规则 `K=4/8` 在 K2 后几乎不再增加覆盖，故实现改为
  “原图可见表面 + 可自由偏移的稀疏三维 Gaussian 残差”。451,640 参数 RGB-only 与真实
  depth-supervised 两个 checkpoint 均能提高覆盖且不损坏原有稳定区，但显露区分别只有
  `16.70 / 17.17 dB`，均已淘汰。下一步扩展逐图许可白名单的 MegaScenes 真实照片切片，加入
  曝光／瞬态内容鲁棒筛选和场景级 held-out，再决定是否引入一次性极值 keyview teacher；质量
  门禁仍覆盖原单人、原双人和多类留出图，未通过前不改 Android 主渲染链。
- [x] MegaScenes train/test 许可切片已完成逐图白名单、外观一致性和三维重投影三重筛选；最终
  保留 13 个 train、7 个官方 test 场景。DA3 输出已确认应解释为 depth，错误相机／裁剪 pair
  已剔除，训练与测试 manifest 均保存逐 pair 尺度校准和误差报告。
- [x] 真实照片自由 Gaussian 残差探针已淘汰：test 新增覆盖仅 `1.98` 个百分点，新显露区
  `13.64 dB / MAE 0.245`，低容量点预测器不能生成隐藏纹理；checkpoint 不进 Android 或云端。
- [x] 实现图像空间 masked keyview 训练集：先用源 depth/camera 生成极值 warp 与精确显露 mask，
  target 只监督 mask 内 RGB/depth，mask 外必须逐像素复制 source warp；把输出重新融合为持久
  自由 Gaussian，并单独报告可见区改动率、新显露区感知质量和跨方向一致性。当前已完成数据、
  硬锁定合成和分域指标；持久三维融合留在 keyview 真实域质量通过之后。
- [~] 在同一协议上训练两档自有权重：快速确定性 masked decoder 与高质量少步 conditional
  flow；先用 Charge 和通过许可审计的 MegaScenes 扩大数据，再决定是否使用许可允许的
  WorldStereo 等 teacher。首轮 109/39 pair 探针已淘汰：确定性模型仅在 Charge 明显提升，flow
  在真实域下降并产生噪声。正在把三重门禁后的真实照片扩到百场景级，再重新训练；任何非商用／
  科研权重不得用于产品蒸馏或阿里云分发。
- [x] 对四／八方向真实 RGB-D keyview 的 point、mesh 和混合 z-buffer 做表示上界；严格断边覆盖
  不足，放松断边产生跨遮挡拉伸，均已淘汰。RIFE 4.26 在真实中间视角上仍有重影且梯度能量
  只有 `59.6%`，也不再作为空间照片主路径。
- [ ] 建立联合多视图显式资产 tracer：同一次前向预测完整极值环和共享几何，以固定数量自由
  primitive 输出位置、尺度、颜色、alpha、方向置信度和安全包络；原图纹理在参考视点硬旁路。
  训练／验证必须同时约束零视点身份、目标视图、跨方向循环、深度／遮挡排序和连续相机轨迹。
- [ ] 扩大许可明确的多视图训练集到可训练规模。Charge 增加同场景多帧以学习遮挡拓扑，
  MegaScenes 继续执行逐图许可、外观和重投影三门禁；保持类别和场景级 train/test 隔离。
- [ ] 快速档先训练确定性联合生成器；只有它在真实域和连续轨迹通过后，才以同资产 schema 训练
  少步 flow／diffusion 高质量档。显露区 refiner 只允许处理模型生成区域，不能改动源可见像素。
- [x] 完成 OVIE v1.0 九场景联合视角门禁：半径 0.2 有真实连续视差，但 256 输出不足；直接
  512 外推、重叠分块、PLKSR-Rep 超分、DIS 细节迁移和中心交叉淡化均因纹理、人脸或连续性失败。
- [x] Charge 512 适配已完成并淘汰。相机／身份适配能提高中心重建，但非零视角仍有周期纹理、
  拖影或整幅发糊；真实跨视图像素训练也不能消除。停止扩大该 decoder 训练，不进入 Android。
- [~] 建立二维联合视角 flow 资产：256 OVIE 生成外圈几何引导，NeuFlow v2 生成双向对应与
  confidence，原图高分辨率纹理直接采样，显露区保留低分辨率 target。九场景、79 帧的非刚性
  视差、缩放漂移与时间二阶差分已量化；逐帧独立 flow 的环形抖动比为 `0.33--2.53`，未通过
  连续性门禁。下一步用 8／12／16 个固定外圈 key-view 构造周期共享运动场，对完整 79 帧比较
  遮挡准确性、闭环和二阶差分，再确定 flow 量化与显露 RGB 格式。
- [ ] 导出并审计 OVIE／NeuFlow 的 ONNX 或其它 Android 运行图，检查现有裁剪 ORT 算子并在
  FP16／INT8 下测峰值内存、单视图耗时和模型体积。模型仍按需下载；门禁通过前不修改 stable
  catalog、不上传阿里云。

## 2026-08-06（36 px vNext6 真机门禁）

- [ ] 在当前指定设备 `R5CW20BLNKL` 上重新生成原单人、原双人及至少四个额外场景的当前 schema 派生，
  分别录制水平、垂直、对角和完整圆周轨迹；同时覆盖默认强度与最大强度。
- [ ] 逐帧检查脸宽／五官比例、人物上身内部体积、文字和直线、头发／手指细边、前后景断边、
  隐藏背景显露及回中逐像素 source lock；不以单张静态截图替代连续动态门禁。
- [ ] 记录每个场景 16 方向实际 p5--p95 投影跨度及触发的非相似／scale 安全限幅，区分“达到
  36 px”与“因形状门禁低于目标”，不得把受限场景误报为 36 px。

## 2026-08-07（vNext10 连续深度可见性真机收口）

- [x] 在设备 `R5CW20BLNKL` 上分别打开“测试空间效果”的单人、双人附件，在默认与最大强度下
  录制水平、垂直、对角和完整圆周连续轨迹；不得用离线近似预览或静态截图替代 GPU 动态门禁。
- [x] 补录至少四个非当前附件场景，覆盖人物／宠物、交通直线、办公室、文字或细结构；检查脸型、主体内部
  深度响应、头发白边／锯齿、补图显露、前后层运动同步、回中 source lock 与持续帧率。
- [x] 若 `36 px` 单人候选在真实 GPU 上出现可辨局部拉伸，先比较同一运动场的 `32 / 36 px`，并利用新增
  可见性视差判断空间感，不得重新启用实例刚性层、压平主体内部深度或静默把所有场景降回低视差。

## 2026-08-07（vNext11 自适应多尺度真机收口）

- [x] 在 `R5CW20BLNKL` 上以最终 schema 13 派生重新录制单人、双人、室内、办公、交通、插画六类素材的默认／最大强度，共 12 段完整二维连续轨迹，并完成逐帧全局、局部网格及人脸 ROI 分析。
- [x] 核对最大档 16 方向有效视差和几何门禁：六类最小方向跨度分别约 `48.0 / 33.5 / 40.1 / 37.3 / 33.6 / 28.6 px@720`，派生 p99.5 非等比形变均未越过 `8%`；困难场景按门禁低于 48 px，不伪报目标值。
- [ ] 等用户以真实握持传感器在自己的单人、双人和更多附件上主观验收默认／最大档。自动触摸圆周已经覆盖轨迹与画质门禁，但不能替代用户对空间感强弱的最终判断；未收到明确请求前不发布阿里云。
