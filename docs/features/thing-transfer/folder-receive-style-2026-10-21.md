# 文件夹内接收入口配色

## 归属核对

`ThingsActivity.setupTapReceive()` 的 `Host.currentFolderId()` 返回当前列表投影的文件夹 ID。`TapReceiveController.enter()` 在进入接收时保存该 ID；`importInBackground()` 和 `importQuietly()` 都把它传入 `ThingFileImporter.import()`。导入器分别调用 `importThing` 与 `importFolder`，把来件顶层项目放入该父文件夹，内部层级按来件重建；根目录传 `null`。

因此文件夹内接收确实归入开始接收时所在的文件夹。原胶囊却固定使用 `App.defaultAccentBackground`，颜色无法表达当前目标。

## 实现与验证进度

首页通过 `TapReceiveController.Host.currentFolderBackground()` 传递当前文件夹完整背景，共用控件通过可选来源保留根目录及详情页默认样式。`refreshActivitySurfaceAndHeader()` 同步刷新入口；进入／退出接收时也重新取色。纯色／渐变及方向由 `BackgroundUtil.fillDrawable()` 完整保留，文件夹文字与图标使用 `onColor(..., ON_ALPHA_PRIMARY)`，波纹使用 `adaptiveRippleColor()`。

本地完整 debug 构建及 152 项相关测试通过，零失败／错误／跳过。沿用本会话明确授权的三台设备；当前 `3B1629006YC00000` 按设备档案称为一加 15T。实际导航使用已有非私密文件夹，不新建或修改记事、文件夹。

一加 15T 已检查浅色纯色、深色纯色、浅色纵向渐变、深色斜向渐变及返回根目录；截图确认浅底黑字／图标、深底白字／图标，根目录恢复原强调渐变。三星及魅族已检查父文件夹、子文件夹和返回根目录，目录刷新均正确。

一加的真实按下截图中，浅底波纹使覆盖区域变暗，深底波纹使覆盖区域变亮。首次深底截图抓到波纹尚未展开的首帧，没有将其计作通过；延迟 180 ms 捕获后确认白色波纹实际绘出。真实点击进入并退出接收后，胶囊恢复当前文件夹的纯色（RGB 72、128、30）。三机原有数据库各表摘要完全一致，检查结束均返回根目录。

证据保存在 `tmp/tap-folder-style-20261021/`：`appearance-contact.png` 为画面对照，`ripple-observations.json` 为按压前后像素观察，`validation.json` 记录数据核对和本地测试结果。归属结论来自生产调用链及导入器核查，本轮没有实际传入记事，也没有执行实体 NFC 互碰。

## 发布

已发布阿里云 debug `202610210401`，日志为 [update-20261021120032.md](debug-updates/update-20261021120032.md)。公开元数据及说明与本地一致；远端与本地 APK 均为 36,913,547 字节，SHA-256 为 `d731570828bac75c663e6ea620405f22eca36c0343e405a2285529fc0574a137`。核对结果在 `tmp/tap-folder-style-20261021/publish-verified.json`。未创建 Git 提交。
