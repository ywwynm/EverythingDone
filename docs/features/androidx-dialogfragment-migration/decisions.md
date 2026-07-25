# AndroidX DialogFragment 迁移决策

## 2026-07-26 - `GestureAnchoredDialog` 继承 `ComponentDialog` 而非 `Dialog`

androidx 的 `DialogFragment.onCreateDialog` 默认返回的是
`androidx.activity.ComponentDialog`（fragment 1.6 起），它带的 `OnBackPressedDispatcher`
是返回键与 predictive back 的落点。`BaseDialogFragment` 覆写了 `onCreateDialog` 以返回
`GestureAnchoredDialog`（见 [app-chrome-polish/decisions.md](../app-chrome-polish/decisions.md)
的「点击外部取消以手势起点为准」），若它继承普通 `Dialog`，等于把 dispatcher 一起丢掉，
迁移就成了功能退化。因此它继承 `ComponentDialog`。

## 2026-07-26 - 宿主一律用 `supportFragmentManager`，对话框内部用 `parentFragmentManager`

`fragmentManager` 这个名字在两边都存在但指向不同的类，替换时必须按调用位置区分：

- Activity 内（含内部类、ViewHolder）→ `supportFragmentManager`，共 103 处。
- 对话框内部再开对话框（`ThingDoingDialogFragment` 三处）→ `parentFragmentManager`；
  androidx 的 `Fragment.getFragmentManager()` 本身已弃用，正解是 `parentFragmentManager`。
- helper / adapter 持有的 activity → 先把签名从 `Activity?` 改成 `FragmentActivity?`，
  再取 `supportFragmentManager`。涉及 `AttachmentHelper`、`AppUpdateHelper`、
  `AuthenticationHelper`、`FingerprintHelper`、`ThingExporter`（含
  `WeakReference<Activity?>`）、`AudioAttachmentAdapter`；`DebugApkUpdateHelper` 持有的是
  `AboutActivity`，本身已是 `FragmentActivity` 子类，只换取值方式。

## 2026-07-26 - 平台 `DialogFragment` 的类型检查必须一起改，编译器不会提醒

四个 Activity 里有 5 处按平台类型做的检查与强转：

```kotlin
if (fragment is android.app.DialogFragment) { ... }
(supportFragmentManager.findFragmentByTag(TAG) as? android.app.DialogFragment)
    ?.dismissAllowingStateLoss()
```

`supportFragmentManager.findFragmentByTag` 返回的是 `androidx.fragment.app.Fragment`，
与 `android.app.DialogFragment` 毫无继承关系，于是 `is` 恒为 false、`as?` 恒为 null——
**那三处 `dismissAllowingStateLoss()` 会静默地永不执行**，而 Kotlin 对这种不相交类型的
运行时检查既不报错也不告警，编译完全通过。只把 `fragmentManager` 换成
`supportFragmentManager` 就收工的话，`MediaCropAppearanceDialogFragment` 与
`AttachmentInfoDialogFragment` 的关闭路径会静静失效。

结论：这类迁移的收尾检查必须是「grep 残留的旧包名」，不能以「编译通过」为准。
涉及 `DetailActivity`（2 处）、`ImageViewerActivity`、`SettingsActivity`、`ThingsActivity`。

## 2026-07-26 - 迁移后清掉对话框文件的 `OVERRIDE_DEPRECATION` 抑制

19 个对话框文件顶部是 `@file:Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")`，
其中 `OVERRIDE_DEPRECATION` 抑制的正是「覆写了平台 `DialogFragment` 的弃用成员」。
迁移后全部改回 `@file:Suppress("DEPRECATION")`，编译警告数不变，证实这些抑制项确实只为
平台实现而存在。留着它们会掩盖将来真实的覆写弃用警告。

`"DEPRECATION"` 保留不动：它们服务的是各文件里别的弃用 API（MediaStore、Sensor 等），
与本次迁移无关，一并处理会无谓扩大改动面。
