# DialogFragment 迁移到 AndroidX（AndroidX DialogFragment Migration）

全项目 27 个对话框的基类 `BaseDialogFragment` 从平台的 `android.app.DialogFragment`
（API 28 起弃用）迁到 `androidx.fragment.app.DialogFragment`，随之把宿主侧的
`fragmentManager` 全部换成 `supportFragmentManager`。

## 背景

平台 `DialogFragment` 在 API 28（2018）就被弃用，官方替代是 Jetpack 的
`androidx.fragment.app.DialogFragment`。它到 API 36 一直没被移除，因此不迁不会突然坏掉——
代价是拿不到 AndroidX 的生命周期修复，且对话框上没有 predictive back 的正确行为。

迁移的前提本来就已经具备，只是一直没做：

- 全部 Activity 都是 `AppCompatActivity`（直接继承，或经 `EverythingDoneBaseActivity`），
  也就是 `FragmentActivity` 子类，`supportFragmentManager` 处处可用。
- `androidx.fragment:fragment` 早已在依赖里。
- 普通 Fragment 这一支早就在 AndroidX 了：`HelpDetailFragment` 是
  `androidx.fragment.app.Fragment`，`HelpActivity` 用的也是 `supportFragmentManager`。
  只有 DialogFragment 这一支留在平台实现上。

项目里**没有**用 `retainInstance`、`setTargetFragment`、`setUserVisibleHint`、
`onActivityCreated`、`FragmentPagerAdapter`——这些才是平台→AndroidX 迁移真正会出事的地方。
因此这次是一次纯机械的横切替换，没有行为设计问题。

## 结论

一次做完，不分批：平台与 AndroidX 的 `DialogFragment` 无法共用一个基类，
分批只能靠维护两套基类，比一次换完更糟。

改动范围见 [decisions.md](decisions.md)，逐步执行记录见 [sessions.md](sessions.md)。

## 状态

已完成（2026-07-26）。`:app:assembleDebug` 通过，且编译警告数与迁移前完全一致——
没有引入新警告，也没有残留任何 `android.app.*Fragment` 引用。真机行为未验证。
