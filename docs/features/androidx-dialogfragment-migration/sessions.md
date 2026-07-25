# AndroidX DialogFragment 迁移会话记录

## 2026-07-26 - 一次做完的全量迁移

起因：修完「对话框从内部滑到外面松手会误关闭」（见
[app-chrome-polish/decisions.md](../app-chrome-polish/decisions.md)）之后，用户注意到
`BaseDialogFragment` 用的是弃用的 `android.app.DialogFragment`，问替代方案与影响面。
调研结论是前提已具备、无高危 API，遂授权一次做完。

执行顺序（刻意固定，避免中途夹入别的改动）：

1. **基类** `BaseDialogFragment.kt`：`android.app.DialogFragment` →
   `androidx.fragment.app.DialogFragment`，`android.app.FragmentManager` →
   `androidx.fragment.app.FragmentManager`；`GestureAnchoredDialog` 基类 `Dialog` →
   `ComponentDialog`；`activity!!` → `requireContext()`；删掉文件级
   `@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")`。
2. **对话框内部** 6 处：`ThingDoingDialogFragment` 三处改 `parentFragmentManager`；
   `AddAttachmentDialogFragment` 改 `mActivity!!.supportFragmentManager`；
   `AudioPlayDialogFragment` 的 `FragmentManager` import 换包；两处 KDoc 里的
   「平台 DialogFragment」措辞更正。
3. **helper / adapter 签名**：6 个文件的 `Activity?` → `FragmentActivity?`。
   `FingerprintHelper` 因 BiometricPrompt 早就 import 过 `FragmentActivity`，
   机械替换制造了重复 import，是本次唯一的编译错误。
4. **调用点** 103 处 `fragmentManager` → `supportFragmentManager`（activities 全量 +
   7 个 helper/adapter）。用 `sed -i` 批量执行：sed 是字节流处理，ASCII 模式替换不会碰
   UTF-8 中文，也不改内容字节；它把 CRLF 写成了 LF，但仓库 `core.autocrlf` 会在比较时
   规范化，`git diff --numstat` 验证 ThingsActivity 是 27 改 27 行，不是整文件重写。
5. **残留检查**（关键一步）：grep `android.app.*Fragment` 抓出 5 处平台类型检查/强转，
   编译器不报的静默退化，详见 decisions。
6. 清 19 个对话框文件的 `OVERRIDE_DEPRECATION` 抑制。

验证：`:app:assembleDebug` BUILD SUCCESSFUL；编译警告去重后仅 3 条，全部是既有的
`ThingsActivity` 问题（两处 `Drawable.getOpacity()` 覆写、一处恒真条件），与 fragment 无关，
迁移前后一致。残留 `android.app.*Fragment` 引用为 0，残留裸 `fragmentManager` 为 0。
`app/proguard-rules.pro` 里 `-keep public class * extends androidx.fragment.**` 迁移后反而
真正覆盖到了这批对话框，无需改动。布局里没有 `<fragment>` 标签，项目也没有
`onAttachFragment` 回调，均无需跟进。

未做：真机验证（按项目规则未连接设备）。需要人工过一遍的点是各对话框的打开/关闭与
旋转恢复，尤其是 `MediaCropAppearanceDialogFragment` 与 `AttachmentInfoDialogFragment`
的关闭路径——它们正是第 5 步修掉的静默退化所在。

已发布 debug 版 202607251708（APK 21108633 bytes，SHA-256
`38df42a5fdea8770ccb45aa01ab9230ba0a63e07fe23c85ce0b6166fc86eb5c9`），
日志见 [debug-updates/update-20260726010756.md](debug-updates/update-20260726010756.md)。
