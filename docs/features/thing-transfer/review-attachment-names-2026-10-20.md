# 附件原名实现复查

用户要求复查上轮附件命名改动。本轮覆盖清单序列化、读取、TCP 下载／去重复制、普通导入、更新准备、复用所有权、取消清理和外观映射，不操作设备。

## 检查进展

- 原名通过 `PendingThing.attachmentNames` 与临时路径分开传递，类型／名称／外观键按同一附件循环填充，普通导入和更新共用同一个文件操作实现。
- 新文件原子占用名称后才移动／复制，并加入本次清理集合；已有同名文件和借用文件不会加入该集合。复用路径和修改时间保持不变，外观映射继续按来件键对应最终路径。
- 确认遗漏：TCP 的 `download-<序号>_<条目名>`、`copy-<序号>_<条目名>` 和 ZIP 的扁平条目名均给原名添加前缀。合法 255 字节原名在正式导入之前就超过文件系统名称上限。真实 TCP 测试在 `TapFileTransfer.receive` 打开临时文件时抛出 `FileNotFoundException`；真实 ZIP 读取与更新准备测试返回 `Broken`，均完成复现。该限制存在于原临时落盘逻辑，上轮只直接测试了最终文件名长度，未覆盖前置环节。

## 修正与新增检查

- 新增纯文件工具 `AttachmentTempFiles`，通过 `File.createTempFile` 原子分配短临时名；仅保留长度有界的常见扩展名以供预览识别，正式文件名仍以清单为准。
- TCP 下载、重复内容的本地复制及 ZIP 解压统一调用该工具，不调整传输协议、摘要匹配和最终重名编号。
- 新增 3 项 Robolectric 集成测试，调用真实 `ThingFileReader`、`ThingFileUpdate.prepare` 和外观映射，只替代应用启动与测试资源。修复前，普通原名映射和借用文件取消保护两项通过，长文件名 ZIP 一项失败；真实 TCP 长名回归另行失败。测试沿用当前已加入的 Robolectric 依赖，不增加运行时依赖。
- 完整失败记录分别保存在 `tmp/tap-attachment-names-review-red.txt` 和 `tmp/tap-attachment-import-review-red.txt`。没有吞掉异常，也没有通过缩短用户的合法原名来规避问题。

## 本地验证结果

修复后 `:app:testDebugUnitTest` 中记事文件、全部碰一碰、外观映射及既有 GIF 检查共 174 项通过，0 失败、0 错误、0 跳过，`:app:assembleDebug` 成功。新增 ZIP 普通原名、ZIP 最长合法原名、协商复用附件取消保护和真实 TCP 最长原名下载／去重复制四项均通过。输出保存在 `tmp/tap-attachment-names-review-tests.txt`。

本轮在主工作区验证；应用代码差异仍只包含本次附件命名及此前已发布的 GIF 修复，其它既有修改为功能文档或桌面实验。未执行 ADB，也未改动数据库或设备数据。除已修正的临时文件名长度问题外，未发现本次命名改动的其它确定性错误。

## 发布与状态

已使用 `debug-updates/update-20261020101716.md` 发布阿里云 debug `202610200217`。公开元数据及中文说明与本地一致，重新下载 APK 的大小 `31,681,061` 字节与 SHA-256 `e1292c93307ea41fa8147b733a9f7abbde0a98f4f666af83a1e4114f28e307bd` 均核对通过。产物、XML 测试结果及校验记录保存在 `tmp/tap-attachment-names-review-published/`。改动保留在工作区，未提交 Git。
