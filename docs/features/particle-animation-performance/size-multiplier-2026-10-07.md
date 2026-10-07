# 粒子大小统一为倍率（2026-10-07）

## 用户决定与语义

在核对飘散软点与流体圆边的实际输出后，用户决定两种设置均使用倍率，不再显示 dp／px，并要求发布阿里云调试版本。这取代上一轮拟议的可见直径绝对值标定。

- 两种样式共用 0.5～6× 的 31 档，保留原先指定的数值并补上 1.0×。
- 1.0× 对应当前样式原始默认大小；手动选中 1.0× 与未设置的行为一致。
- 倍率缩放单颗粒子的线性尺寸；逐粒随机、生长和消退仍保留，两种样式的 1.0× 不表示相同的绝对直径。
- 大小与数量分别保存，改大小不改变发射数量；流体的默认基准仍随内容面积自动计算。

## 实现

- `ParticleTuning` 共用大小梯度，以倍率百分位整数保存两种大小；`ParticleTuningActivity` 仅显示倍率及默认标记。13 个语言目录同步说明。
- 飘散直接向 `ParticleDriftModel.sizeScale` 传倍率，不再先换成 dp。
- 流体在 `ParticleMicroflakeModel` 完成数量补偿后缩放 `particleScaleX/Y`，不改变材料数量与轨迹输入；大小倍率纳入 `ParticleMaterialShare` 和 `ParticleCreationPrewarm` 的匹配条件。
- 保留内部调试工具的 dp 覆盖入口，设置页不再使用。性能自动调节仍默认关闭；启用后连续两轮掉帧最多降低一次粒子数量与流动精细度，不再改大小。

## 旧设置迁移

- 新倍率键优先，旧键在迁移后删除，重复执行不会再次转换。
- 旧飘散 dp 按本机密度换回原模型倍率；更早的倍率档直接按原值迁移。
- 旧流体显式 dp 按规则基准 0.88 dp 换算倍率；从未显式设置的默认值保持 1.0×。
- 换算后取最近档，超出范围取端点。旧流体显式大小曾影响发射网格，而本版大小只影响绘制；因此旧设置迁移后不保证数量和画面逐像素不变，尤其是自动基准较大的内容。

## 验证

- 233 项相关 JVM 测试通过，涵盖跨版本迁移、默认值、样式独立保存、31 档大小、流体数量与轨迹不变、缓存匹配、自动调节边界，以及飘散播放去重的回归。
- 128 组正式 GLSL 离屏检查通过，覆盖低数量、不同尺寸、出生及后续阶段的近圆轮廓和四条绘制路径一致性；原表面的端点与连续淡出检查通过。
- 调试 APK 构建成功。测试日志：`tmp/particle-size-multiplier-tests.log`；GLSL 结果：`tmp/particle-size-multiplier-20261007/round-check/result.json`。
- 本轮未连接 ADB、未创建 Git 提交。

## 发布

- 阿里云调试版本：`202610062344`，APK：[下载](http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202610062344.apk)。
- 发布说明：[update-20261007074346.md](debug-updates/update-20261007074346.md)，通过 `:app:publishDebugUpdate` 显式传入，并同步记录到 `memory/debug-update-notes.md`。
- 远端元数据与本地完全一致；下载的 APK 为 27,730,747 字节，SHA-256 为 `0e1f9921812a5fb18d9cfc917ba01b8f8e8fb970c7adfbfcca62de96673c4aed`，与本地发布包一致。
- 校验记录：`tmp/particle-size-multiplier-20261007/publish-verification.json`。
