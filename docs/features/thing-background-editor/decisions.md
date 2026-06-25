# Thing Background 编辑器重构 — 决策

grill-with-docs 过程中逐条确认。日期为决策达成日。

## 2026-06-25 — 容器：BottomSheet + 复用首页面板，内容块可复用

新颜色编辑器不再用旧的 128dp PopupWindow，因为「纯色/渐变」选项卡 + 颜色条 +
RGB/Hex 输入装不下，且需要软键盘避让。

- 颜色编辑器 UI 抽成一个**与宿主无关的可复用内容块**（布局 + 控制器），
  对外暴露「当前 ThingBackground + 变更回调」，由宿主决定如何提交。
- 详情页：用一个外观类似卡片外观面板（圆角抬升底部面）的 BottomSheet 承载该内容块。
- 首页卡片外观面板：**复用现有面板**，点击「调整颜色」图标时，面板内容**就地切换**
  为颜色编辑控件，自适应面板高度，并可返回到其它外观设置。不允许「面板之上再叠面板」。
- 间距等布局参数统一写在 XML，不在 kt 里设置（本次重构的硬性要求）。

## 2026-06-25 — 颜色条：黑→彩虹→白复合带，handle 内显真实色

一维颜色条无法精确覆盖三维颜色空间，故定位为**粗选**，RGB/Hex 为**精确源**。

- 颜色条 = 一条「纯黑 → 全色相饱和彩虹 → 纯白」的复合 `LinearGradient`，
  用多个色相停靠点（hue 每 30°~60° 一个 stop）保证插值后仍是干净彩虹。
- 拖拽：handle 位置 `t` → 沿曲线插值出颜色（拖拽结果恒在曲线上）。
- 反向（颜色 → handle 位置）：曲线采样 ~256 点，取加权 RGB 距离最近者；
  浑浊色（含 10 个预置色）落在近似位置，可接受。
- handle 圆点内部填充**当前真实颜色**（带细描边），即使当前色不在曲线上也精确显示当前色；
  因此不再额外加独立的当前色预览方块。
- 不采用「纯色相彩虹（不含黑白）」基线方案，因为它违背"颜色条要含黑白"的要求。

## 2026-06-25 — 提交/undo：实时预览 + 每会话提交一次，内容块对 undo 无感

- 可复用内容块只对外抛「当前 ThingBackground 变了」的回调，**不关心 undo**。
- 详情页：打开时快照 `bgFrom`；编辑过程实时套用到屏幕背景做预览、**不记 undo**；
  关闭（返回/点外部）时若最终色与 `bgFrom` 不同，**只记一条** `UPDATE_COLOR(bgFrom→最终色)`。
  与相机取色判例一致：一次编辑会话一条 undo。
- 详情页 BottomSheet **不加**独立取消/确认，纯靠实时生效 + 全局 undo。
- 首页面板：颜色编辑只更新草稿，跟随面板原有的取消/确认提交，颜色页内不单独提交。

## 2026-06-25 — 选项卡 ↔ Mode 映射与跨选项卡颜色接力

- 纯色 tab = `Mode.PURE`，渐变 tab = `Mode.GRADIENT`；打开时按 Thing 当前 mode 选中对应 tab。
- 编辑器会话内同时维护**两套工作态**：纯色工作态（一个颜色）、渐变工作态（起始/结束/方向）。
  来回切 tab 只切换显示并实时预览，**不重新随机**。
- 打开时若为 PURE：纯色工作态 = 当前色；渐变工作态预置为（起始 = 当前色，结束 = 随机种子色，方向 = 默认 `L_R`）。
- 打开时若为 GRADIENT：渐变工作态 = 当前渐变；纯色工作态 = 渐变的**起始色**。
- PURE→GRADIENT：起始接力当前纯色，结束用随机种子色（与 App 既有随机渐变审美一致）。
- GRADIENT→PURE：纯色接力渐变的**起始色**。

## 2026-06-25 — RGB/Hex 输入：单一数据源 + 防回环，实时联动

- 编辑器内部只有一个 `currentColor: Int`，任何输入改它，再由它单向回刷其它视图；
  程序化回刷时用标志位让 `TextWatcher` 不反向触发（防无限递归）。
- RGB：3 个 EditText（R/G/B），0–255，`InputFilter` 钳制；样式复用「某个时刻」的 `InputLayout`
  （浮动 label + accent 下划线），accent 用当前色（渐变 tab 下各区用本区颜色）。
- Hex：1 个 EditText，格式 `#RRGGBB`（6 位 + 前导 `#`，**不带 alpha、不支持 3 位简写**），
  因 Thing 背景恒不透明（代码统一 `or -0x1000000`）。
- 生效时机：RGB 框逐字符实时生效（合法 0–255 即生效）；Hex 凑满 6 位合法 hex 才生效；
  字段清空/非法时不生效、保留上一有效色，不当作 0。

## 2026-06-25 — 颜色条右侧两个图标：随机取色 / 从世界取色

- 两个图标 = ①随机取色 ②从世界取色（= 既有相机取色 `CameraColorSamplingDialogFragment`，取回单个纯色）。
- **取消旧的"随机渐变"单按钮**：渐变由两个色区各自的随机 + 方向组合而成。
- 按区定向：渐变 tab 下每个色区各有这两个图标，只改本区颜色（起始/结束）；纯色 tab 改唯一颜色。
- 回流副作用：更新 `currentColor` → 移动 handle + 回刷 RGB/Hex + 实时套背景预览 + 用取到色 tint 这两个图标自身。
- 与单会话 undo 整合：从世界取色确认回来后**不再单独提交 `UPDATE_COLOR`**，与拖拽/输入一样只更新编辑器状态，
  由"关闭编辑器记一条 undo"统一兜底；相机采样过程仍只在相机 dialog 内预览，确认才回流。
- 图标用圆形 ripple（`BackgroundUtil.circularRipple()`）。
- **用户可见标签改为"从世界取色"**（替换原"从相机取色"）；英文/其它语言文案需同步改为对应表达。

## 2026-06-25 — 顶部选择器：预置色（纯色 tab）与渐变方向（渐变 tab）

- 纯色 tab 顶部：10 个预置色 **5 列 × 2 行** 圆形按钮（复用 `color_picker_fab.xml` 的圆形 + 圆形 ripple + 选中对号）；渐变 tab **不放**预置色。
- 渐变 tab 顶部：8 个方向 **4 列 × 2 行**（行 1 正向 `L_R/T_B/R_L/B_T`，行 2 斜向 `LT_RB/RT_LB/LB_RT/RB_LT`，沿用现有 `ORDER`）；
  每个方向按钮内部**实时渲染当前两色按该方向的小渐变**，两色变则同步刷新；选中方向打对号。
- 预置色对号规则：**对号 = 当前色恰好等于某预置色**，每次颜色变化都重新判定——
  点预置打勾；拖条/改 RGB/Hex/随机/取色挪走则自动取消；精确调回某预置值则重新打勾。与现有 `pickForBackground` 一致。
- 渐变两色区：上 = 起始色（`color`），下 = 结束色（`endColor`），各加"起始色 / 结束色"小标题。

## 2026-06-25 — 首页面板就地切换 + RGB/Hex 并一行（覆盖初始设定）

- **RGB 三框 + Hex 框并到同一行**（4 个 EditText 同排），省出一行——覆盖用户最初"RGB 一行、Hex 下一行"的描述。
  详情页 BottomSheet 与首页面板**都**用这个一行布局。
- 因为省了一行，颜色页中部**不做限高滚动**，先实现看实际效果再评估（用户："先实现下看看效果"）。
- 首页面板就地切换：颜色页有自己的标题行，左侧**返回箭头**回到外观设置页（仅页内导航，不提交）。
- 面板底部的**取消/确认保留**，代表整个面板会话（含颜色草稿）的提交/丢弃；返回箭头不提交。
- 两页切换配轻量高度过渡（`animateLayoutChanges`），不生硬跳变。
- 渐变 tab 两个色区都会因此变矮（各 = 颜色条+图标行 + 一行 RGB/Hex）。

## 2026-06-25 — 组件结构与清理范围

组件拆分（逻辑可复用 + 间距全写 XML）：

- `ColorSpectrumBar`（自定义 View）：画"黑→彩虹→白"复合渐变 + 可拖 handle（内显真实色），get/set 颜色 + 变更回调。
- `ColorAreaView`（自定义 ViewGroup）：颜色条 + 随机/从世界取色两图标 + 一行 R/G/B/Hex；自管一个 `currentColor` 并抛回调。
  纯色 tab 用 1 个、渐变 tab 用 2 个（避免 `<include>` id 冲突）。
- `ThingBackgroundEditor`（自定义 ViewGroup）：选项卡 +（纯色页：预置色 + 1 个 `ColorAreaView`）/
  （渐变页：8 方向 + 2 个 `ColorAreaView`）；维护两套工作态；抛 `onBackgroundChanged(ThingBackground)`。
- 宿主：详情页用薄 `ThingBackgroundEditorBottomSheet`（`BottomSheetDialogFragment`）包它；首页面板内联为"颜色页"。
- 搜索用旧 `ColorPicker.kt`（`HUE_BUCKET`）原样保留。

清理范围：

- 独立 `GradientOrientationDialogFragment`（仅被颜色编辑器流程调用）：渐变方向已并入渐变 tab，
  **新编辑器跑通后删除**，同时改 DetailActivity/ThingsActivity 两处调用点。
- 死代码 `Def.PickerType.COLOR_HAVE_ALL` / `COLOR_NO_ALL`：与本次无关，**单独留作 followup**，不混进本次改动。

## 命名/领域语言说明

本次产物（`ThingBackgroundEditor`、`ColorSpectrumBar` 等）均为 UI 实现，不是新的领域概念；
领域层仍由 `CONTEXT.md` 的 **Thing Background**（纯色或渐变）承载，故本次**不新增 CONTEXT.md 术语**。

## 2026-06-25 — 首版测试反馈修正

- **选项卡样式**：不用原生 TabLayout，改自定义 TextView：胶囊 ripple、无下划线、选中态文本用当前
  实际颜色着色（渐变用渐变着色），未选中用提示色。
- **标题跟随颜色**：编辑器提供 `setTitleView(TextView)`，让宿主的"调整颜色"标题随当前色实时着色；
  详情 BottomSheet 与首页颜色页都接入。
- **图标重设计**：随机取色 = 骰子（`ic_color_random`）；从世界取色 = 地球 globe（`ic_pick_from_world`），
  突出"世界"语义。
- **颜色条裁切**：`ColorSpectrumBar` 的 track 两端内缩量加上 `handleStroke`，并相应增加测量高度，
  使 handle 圆（含 halo）在最右端完整可见。
- **可滚动**：新增 `MaxHeightNestedScrollView`（封顶 `tbe_editor_max_height`），详情 BottomSheet 与
  首页颜色页都用它包裹编辑器；渐变页过高或软键盘弹出时内容可滚动，标题/取消/确认固定在滚动区外。
  覆盖此前"不做限高滚动"的暂定。
- **详情面板边距/最大宽度**：详情 BottomSheet 在 onStart 设宽度 = min(屏宽 − 2×`thing_card_outer_spacing`,
  `thing_card_appearance_max_width`)，并居中，适配平板，与首页卡片外观面板一致。

## 2026-06-25 — 第二轮测试反馈：面板展开/滚动行为重做

- **详情面板不再用可拖拽 Material BottomSheet**：改为继承 `BaseDialogFragment` 的底部固定对话框
  （窗口 gravity bottom + 计算宽度/最大宽度 + y 边距 + adjustResize）。彻底解决"打开不自动展开、
  需上滑""切 tab 缩回底部"的问题。`ThingBackgroundEditorBottomSheet` 类名保留但已非 BottomSheet。
- **内容自适应 + 溢出才滚动**：新增 `ScrollAwareColumn`（竖向容器），默认按内容全展开；仅当总高超过
  可用高度（键盘弹出）时，收缩其中的 `NestedScrollView` 子项为可滚动，标题/分割线/取消确定固定不被裁切、
  不与滚动内容重叠。替换上一轮的 `MaxHeightNestedScrollView`（已删除）。两个宿主都用 `ScrollAwareColumn` +
  普通 `NestedScrollView`。覆盖上一轮"渐变页默认限高滚动"的做法——现在默认全展开。
- **详情页补取消/确定**：取消 = 放弃改色、回到打开时的 `bgFrom`（不记 undo）；确定 = 提交一条 `UPDATE_COLOR`；
  返回/点外部按取消处理。`setOnResult(confirmed)` 回调驱动。
- **返回箭头跟随颜色**：`ThingBackgroundEditor` 新增 `setTitleIcon(ImageView)`，用当前色（含渐变）tint 该图标；
  首页颜色页把返回箭头传入。
- **滚动感知分割线**：标题下/操作上各一条分割线，按 `canScrollVertically(-1/1)` 显隐（仿 `ChooserDialogFragment`），
  不可滚动时都不显示；详情对话框与首页颜色页都接入（滚动监听 + 全局布局监听）。
- **从世界取色图标**：改为"取景框四角 + 中心取色点"，兼具"眼前所见的世界"与"取色"，中心点被当前色 tint。
