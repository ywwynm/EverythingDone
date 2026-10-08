# 实现决策

## 2026-10-08：统一窗口输入与切换受理

- 涟漪不产生点击，不修改 ripple 绘制代码。
- 公共主题设置 `android:splitMotionEvents=false`；系统可能提前创建 DecorView，公共窗口接入时额外设置一次。动态 ViewGroup 自动继承，不在触摸时遍历界面树；完整多指事件仍交给同一子控件。
- `UiTransitionState` 仅保存每个 Activity 的窗口身份、出现／可交互／关闭阶段和离开请求，主线程同步受理。竞争请求直接拒绝，不排队、不设置固定毫秒间隔。
- `UiInteractions` 从窗口输入分发记录来源，覆盖 View 投递的点击回调。下层窗口输入被中止后发送 CANCEL，并丢弃该手势的剩余事件。暂时隐藏下层无障碍节点，Activity 销毁时还原，兼容系统复用 DecorView 的配置重建。
- 普通弹窗经 `BaseDialogFragment.show/tryShow/showNow` 登记；有字段写入等副作用时放入 `tryShow` 的受理回调。FragmentTransaction 展示重载不再用于此基类，现有生产代码没有该调用。
- 同一类且同一 tag 的活动弹窗避免重复；正常父子、先关闭再替换、不同 tag 的顺序流程仍可进行。已受理的权限后续操作按 Activity 生命周期执行，不要求原确认框保持可交互，详见下方权限回调修正。
- Activity 导航经 `UiInteractionActivity`，包含旧 forResult、ActivityResult 使用的系统入口及 IntentSender；失败按身份撤销。外部结果早于 `onResume` 到达时，按 requestCode 释放对应请求后再交给业务回调。
- 粒子消散在真实窗口已移除后仍持有自己的交互身份，直到动画完成。旧动画只能释放自己的身份，不能解除新窗口的保护。
- PopupPicker 在进入过渡完成后启用内容，在退出内容真正 detach 后释放；轻量 LinearLayout 子类保留输入来源，内容布局不变。
- 首页外观编辑器属于页内面板，稳定显示后仍允许既有列表操作；只在开启过渡登记，不能把整个可见期当作模态弹窗。

## 验证边界

- 两台设备覆盖真实设置页与空白记事详情页；不修改已有记事，不启动录音。临时动画档位在测试 finally 中恢复。
- 父子／替换、菜单与导航流程使用 debug 夹具配合生产公共组件；菜单后继另覆盖控件的无障碍点击路径。图片缩放使用生产依赖 PhotoView。
- 真实权限授权界面及首页面板的全部业务分支未逐项操作；公共接入已静态核对。后台业务操作的幂等不由窗口控制替代。
- 现有普通弹窗均使用 Activity 的 FragmentManager；后续若引入独立子 FragmentManager 或新的窗口类型，应接入对应宿主，不能绕过公共入口。

## 2026-10-08：权限回调修正

- 用户批准修正复查发现的权限回调问题，范围限定为这条正常流程。
- 已受理的权限请求先释放等待状态，再在 Activity 未结束、未销毁时执行授权／拒绝回调；确认框主动关闭不取消已确认的后续操作。
- 回归使用真实 AlertDialogFragment、EverythingDoneBaseActivity 权限封装与结果分发，通过 ActivityCompat.PermissionCompatDelegate 模拟系统结果；覆盖粒子动画关闭／开启、授权／拒绝，以及等待期间拒绝重复请求。测试不更改设备权限、不启动下载。
- 修正前在魅族稳定复现回调丢失；修正后两台授权设备各 4 项定向回归通过，8 项状态单元测试通过。
