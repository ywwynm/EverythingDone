# 真实首页批量动画回归

须有用户对目标设备的明确 ADB 授权，再执行这里的脚本。脚本接受三台本轮授权设备的序列号，不会自行选择设备。终端调用方法遵守 `.agents/rules/`。

测试应用使用独立包名 `com.ywwynm.everythingdone.batchvalidation`，生产源代码保持一致，实际运行 `ThingsActivity`、业务确认弹窗、DAO、RecyclerView 和 SurfaceView。探针拒绝在原应用包中运行；只创建、删除自己的固定编号虚构数据。三星／魅族由脚本清理隔离包数据，OPPO 在隔离应用内重置数据。原应用与既有记事不用于破坏性测试。

在仓库根目录运行 `prepare_clone.py`，随后执行 `:app:assembleDebug -I docs/features/selection-batch-actions/checks/clone.init.gradle`。将所得隔离 APK 安装到明确授权的目标设备，运行 `matrix.py <设备序列号> <本次名称>`。不得给 `publishDebugUpdate` 传入此 init 脚本；发布必须使用正常包名。

`run_case.py <设备序列号> <唯一 run_id> key=value ...` 可单独回放。支持：

- `entry=toolbar` 经普通 Toolbar 菜单执行五种范围动作，校验业务状态、退场粒子、Doing／范围外／其它状态记事和文件夹容器；回收站夹具混合删除前状态。默认仍走选择入口。`protect_doing=true` 为范围完成加入受保护记事。
- `toolbar_matrix.py <序列号> <唯一名称>` 为 2026-10-15 合并回归，每机八例：五种动作、根目录／文件夹、两种粒子、普通淡出和既有多选；代表性场景录屏。当前脚本仅接受该轮明确授权的 OPPO `3B1629006YC00000`、三星 `R5CW20BLNKL`、魅族 `192.168.100.67:40807`。其它设备必须先取得授权再更新白名单。
- 样式参数统一转小写后写入正式设置，并从实际卡片动画的 `spec.style` 核验。追加 `--flow-only` 可只跑三个流体场景，用于修正旧脚本大写参数导致的回退；不得将旧结果直接标记为流体验证。

- `action=finish/restore/delete/forever/untrash`；`scope=root/folder`。
- `scroll=top/middle/bottom`；`selection=visible/mixed/offscreen/all`。
- `style=DRIFT/FLOW`；`particles=false` 检查普通淡出。
- `complex=true` 加入长卡、整行卡和保留的文件夹缩略图。
- `appearance=day/night`、`gradients=true`、`inspect_colors=true` 检查颜色；不修改设备或原应用的外观设置。
- `interrupt=back` 在批量开始后按返回；`screenshots=true` 采实际屏幕图片，影响性能采样，不用于帧率结论。
- `record_video=false` 跳过录屏；OPPO 平板的 screenrecord 未可靠生成视频，使用截图和实际 View 逐帧记录，不能宣称录像等价。
- `audit_appearance=true` 在隔离包生成本地图片、GIF、视频与音频，覆盖四种媒体位置、媒体背景、隐藏媒体计数、私密、清单、提醒、习惯及特殊标识。进入选择前采正常参考，批量退出后从当前位置连续往返滚动，对比子控件可见性、alpha、清单可点击状态和静态图标像素指纹；可见媒体须解码就绪。
- `scroll_warm=true rounds=90 probe_timeout=330` 走按住后纵向滚动的真实输入循环，逐次检查可见虚构卡片的屏幕颜色，覆盖上下滚动及 holder 复用。`press_ms=90` 固定停留时长；不传则轮换六种时长。`gesture=horizontal press_ms=90` 检查左右短滑粒子与返回，必须实际出现粒子；每轮结束继续滚动以换绑。此路径只支持纯色滚动夹具，不同时传媒体／渐变参数。
- `measure_gfx=true` 在 ready 后重置隔离包的 gfxinfo 计数，结束后保存系统窗口帧统计；实际截图会影响采样，应只在相同场景、相同截图频率之间比较，不能用来宣称整套 GL 帧率完全一致。
- `select_doing=true` 把正在做的虚构记事一起选中，断言业务动作跳过它；`cache_exit=true` 在退出中途把 Doing holder 移出屏幕，确认同一 holder 脱离后重新挂回且恢复 alpha=1。清单直接勾选默认开启，可用 `checklist_taps=false` 覆盖关闭设置；正常批量用例会实际点击清单并核对隔离数据库的内容变化。

`appearance_matrix.py <序列号> <唯一名称> [起始下标]` 为上述扩展矩阵，每台 9 例：六组媒体批量操作、一组缓存返回、一组返回中断、一组全部移空。普通阶段仍需另跑 `validate.py`，不能因最终外观相等而忽略过程中保留卡片隐藏／位置跳变。中断用例按其即时结束语义单独核验。

媒体权限只授予隔离测试包。OPPO 的 shell 权限授予受限，探针通过该包自己的标准运行时权限窗口操作，不修改系统的调试权限设置。

所有产物写入被忽略的 `tmp/selection-transition-20261013/<run_id>/`。`result.json` 含业务结果、工具栏／系统图标／布局／输入锁逐帧状态、稳定 ID 的位置和透明度；`before.png`／`after.png` 为屏幕截图。失败日志保留，不覆盖为成功。

`validate.py <run_id> ...` 校验阶段顺序、菜单与系统栏时序、补位连续性和颜色中间值。不能单靠它判定实际阴影或像素正确，须结合代表性录像逐帧检查。中断及系统动画关闭的结果须按其预期单独检查，不能套用“至少五个工具栏中间帧”的正常动画断言。纯色 Canvas 对照必须保留设计色，避免厂商额外反色蒙混过关。
