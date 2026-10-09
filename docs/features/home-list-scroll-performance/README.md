# 首页记事列表滚动性能

状态：2026-10-09 已按用户要求撤回粒子准备优化，恢复此前按下预热流程，仅保留共用清单解析优化。撤回后构建及 389 项回归通过，已发布阿里云 debug `202610090451`。随后按用户要求安装到三台设备并完成 48 次竖滑、36 次横滑／回拖验证，偏好及系统设置核验一致，设备临时文件已清理。
方向预判和后台准备取消方案已撤回。历史复验中的竖滑零截图、407 项测试和 debug `202610090418` 均描述撤回前的版本，不代表当前代码。
清单解析热点已减轻，但首次复杂卡片绑定仍有长帧，不能宣称所有掉帧已解决。
撤回后流体按住再横滑的响应回到约 38–61 ms；竖滑按下截图恢复，魅族追加样本仍出现约 523 ms 长帧，未证实整页滚动稳定改善。
三星当前动态封面因预览缺失而退回静态，预览生成又被低存储条件阻止，需避免把这种负载变化当作优化完成。

本次归档包含 Fable 于 9 月 26 日完成的源码分析、三星采集工具与实测结论；当时完成的是问题定位，没有实施首页滚动修复。10 月 9 日继续复查并保留共用清单解析优化，方向预判和后台准备取消只留下已撤回的实验记录。9 月 26 日的 60 Hz 限制属于当时版本，后续复查已不再适用，不能将这一历史问题计作本次代码修复的成果。

## 文档

- [analysis-2026-09-26.md](analysis-2026-09-26.md) — 四路并行审查（媒体链路、列表宿主与布局管理器、卡片视图与绘制、
  数据模型与导入落地）汇总：按可能性排序的原因、逐条 `文件:行号` 证据、真机验证方案、可选改动方向
- [findings-2026-09-26-device.md](findings-2026-09-26-device.md) — 三星 SM-S9180 实测结论：起手掉帧来自粒子左滑的按下预热，
  动图封面的常驻负载，粒子 SurfaceView 的 60 Hz 投票；滚动期间没有重绑或布局
- [analysis-2026-10-09.md](analysis-2026-10-09.md) — 三设备复查：90 次手势、按住／拖动分阶段统计、首次绑定热点、存储与媒体负载证据、建议的解决顺序及复验要求
- [design-discussion-2026-10-09.md](design-discussion-2026-10-09.md) — 清单在详情／编辑／组件等处的共用范围；兼顾横滑首帧延迟的早期方向预判与分层准备建议
- [checklist-optimization-2026-10-09.md](checklist-optimization-2026-10-09.md) — 共用清单线性归一化、兼容性回归及访问次数验证
- [swipe-preparation-2026-10-09.md](swipe-preparation-2026-10-09.md) — 已撤回方案的实现历史
- [verification-2026-10-09.md](verification-2026-10-09.md) — 撤回前两项优化的三机对照、首帧代价及残留瓶颈
- [verification-rollback-2026-10-09.md](verification-rollback-2026-10-09.md) — 当前撤回版的三机复测、横滑响应恢复与仍存在的长帧
- [decisions.md](decisions.md) — 撤回粒子准备优化、保留清单解析的决定
- [preferences.md](preferences.md) — 设备授权、先分析及后续先实施清单优化的范围要求
- [followups.md](followups.md) — 尚未实施的优化方向和验证事项
- [sessions.md](sessions.md) — 会话记录
- `device/` — 真机采集工具：`hls_device.py`（驱动：标记、竖向手势、按住再滑、framestats 逐手势转储、atrace、
  SurfaceFlinger、logcat 落盘）、`analyze_framestats.py`（逐帧分阶段、按手势归组）、`analyze_trace.py`
  （超时帧与应用线程切片、CPU 时间对齐）、`extra_metrics.py`（显示模式对照、50 ms 分格 CPU、纹理上传、logcat 逐手势计数）
- 10 月 9 日工具：`recheck_20261009.py` 和 `HlsGestureInput.java` 负责双时钟标记、风格／对照采集与偏好恢复；
  `review_20261009.py`、`trace_review_20261009.py`、`method_review_20261009.py`、`summarize_20261009.py`、`phase_metrics_20261009.py` 负责离线复核。
- `verify_optimization_20261009.py`、`verify_swipe_response_20261009.py`、`verify_home_cancel_20261009.py`、`verify_compiled_meizu_20261009.py` 和 `review_optimization_20261009.py` 保留为撤回前的采集／分析记录。其中探针进度和取消观测依赖已撤回的调试字段，不能直接用作当前版本的验收工具。

## 相关目录

- `docs/features/particle-animation-performance/` — 粒子管线的性能专项；本目录里"按下预热"一条的设计出处在那里
  （`analysis-2026-09-14.md`、`per-frame-phase2-results-2026-09-18.md`）
- `docs/features/animated-video-cover/`、`motion-photo-playback/` — 视频 / 动态照片封面派生 GIF 的机制
- `docs/features/thing-card-media-target-geometry/`、`thing-card-image-placement/` — 媒体背景与侧图排版
- `docs/features/thing-transfer/` — 导入落地方式（附件原样、文件名与 mtime 改变）
