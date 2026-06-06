# 颜色系统迁移方案

把 Everything-Android 的"随机颜色 + 渐变"模型迁移到完事儿主工程的设计方案。

---

## 完成状态(2026-05-18)

✅ **整个迁移已完成**,Phase 1-8 全部落地。

| Phase | 内容 | Commit | 状态 |
|-------|------|--------|------|
| 1-3 | 派生色算法化 + `BackgroundUtil` 抽象 + DB v9 + 随机色(PURE) | `3315533` | ✅ |
| 4 | 渐变开启 + 真渐变 UI(卡片 / 详情 / reveal / shining / widget 预览) | `42d48d7` | ✅ |
| 4 跟进 | Widget + NoticeableNotification 渐变跟进、STYLE_BLACK 移除 | `3ea635b` | ✅ |
| 5-7 | 色相 bucket 搜索 + ColorPicker 拆分 + 端到端 ThingBackground | `be951e4` | ✅ |
| 6 polish | Gradient orientation picker + 色相 bucket 搜索 + fake-FAB cells | `c061a0a` | ✅ |
| 7 polish | Detail bottom-bar fake-FAB doing button + pill quick-remind ripple | `153e292` `1c3261b` | ✅ |
| 8 | 渐变端到端(进入 dialog 内部) | `79714c2` | ✅ |
| 8 cont'd | DateTime/Habit/AudioRecord dialog 下游 + EditText 渐变下划线 + ImageViewer 链路 + 卡片亮度自适应 | `aa80e64` | ✅ |
| 4.7.3 | AppWidget Bitmap 预渲染(渐变 widget 卡片背景) | 合并于 Phase 4 系列 | ✅ `AppWidgetHelper.java:559` |

### 风险 / 待决问题处理状态(§6)

| # | 风险点 | 状态 |
|---|--------|------|
| 1 | CardView + GradientDrawable + corner radius 裁切 | ✅ `applyCardBackground` 走 `setBackground(gd)`;EndOfMonth pill 用 `cv.getRadius()` 自带圆角 GradientDrawable |
| 2 | 老数据是否全部随机化迁移 | ⏸ 决策**不迁移**,老 PURE 完全保留 |
| 3 | AppWidget 主色降级 | ✅ 升级为 Bitmap 预渲染真渐变(`renderBackgroundBitmap`) |
| 4 | 图片附件背景在渐变下视觉 | ⏸ 测试性,Phase 4 后未发现回归 |
| 5 | DetailActivity 颜色切换动画 | ✅ `BackgroundUtil.animateBackground` 双 `ArgbEvaluator` |
| 6 | ShiningBorder 派生 ordinary | ✅ commit `8179a16`,PURE 用 `blend(c, white_45p)` / GRADIENT 用 start↔end |
| 7 | 保活通知 / 进度条颜色 | ✅ 走 representativeColor 降级(`Notification.setColor` / `ProgressBar` tint 单 int API 限制) |
| 8 | ColorUtils.HSLToColor 精度 | ⏸ 测试性,未发现可见色偏 |
| 9 | `thing_dark` / `thing_light` 数组去留 | ⏸ 暂保留,无 XML 引用阻碍 |

### 已知后续迭代项(`memory-followups.md`)

- **真渐变 ripple 波纹**(`RecurrencePickerAdapter` NORMAL 单元格):当前 fake-FAB 底色真渐变,但 `RippleDrawable` 的水波纹色仍 representative 单 int(API 限制)。改进路径:hand-rolled onTouch + GradientDrawable scale 动画。
- **GradientEditText 子类**(EditText 全选 highlight 渐变):`setHighlightColor` 单 int 限制。改进路径:子类 override `onDraw` 自己画 selection 路径 + Paint.setShader。

---

## 0. 阅读须知

本文档目的：**先把所有跟颜色有关的存储 / 查询 / 渲染 / 派生 / UI 切入点全部列出来，定位每一个需要决策的地方，再决定实现策略。**

包含：现状盘点 → 关键差异 → 需要你拍板的设计决策 → 推荐方案 → 分阶段实施计划 → 风险清单。

---

## 1. 现状盘点

### 1.1 完事儿（当前）

| 维度 | 实现 |
|------|------|
| 调色板 | `res/values/colors.xml` 的 `R.array.thing`，**固定 10 色**：blue_grey_500、my_cyan、blue_grey_deep_blue、blue_grey_deep_grey、brown、blue_deep、pine_green、my_purple、elegant_orange、Aein_red |
| 暗 / 亮变体 | `R.array.thing_dark`（叠 36% 黑）+ `R.array.thing_light`（叠 66% 白），都是预先算好的 10 个 |
| 数据库 | `things.color` 列：`INTEGER`，nullable，存 ARGB int |
| 随机选色 | `DisplayUtil.getRandomColor()` → 数组里随机选；`App.updateNewThingColor()` 还会避开列表中临近项的颜色 |
| 按颜色查询 | `ThingDAO.getThingsCursorForDisplay(..., int color)`：`WHERE color = <int>`（精确匹配） |
| ColorPicker | 10 个 FAB，每个对应一种固定颜色；返回 int |
| `getLightColor` / `getDarkColor` | `getColorIndex()` 线性查表，找到 index 后从 `thing_light` / `thing_dark` 数组取——**调色板之外的颜色直接返回 0（黑）** |
| 卡片文字颜色 | `card_thing.xml` 里**硬编码** `@color/white_86p`、`white_76p`、`white_66p`、`white_54p`；从不自适应 |
| DetailActivity 文字 | `activity_detail.xml` 同样硬编码白色系 |
| 派生色用途 | 卡片浅色变体（未选中态）、EditText highlight、ShiningBorder 的 ordinary 色、widget 半透明背景 |
| widget | `AppWidgetHelper` 用 `RemoteViews.setInt("setBackgroundColor", ...)` ，只接受单 int |
| 系统通知 | `Notification.Builder.setColor(int)`，只接受单 int |

### 1.2 Everything-Android（目标模型）

| 维度 | 实现 |
|------|------|
| 随机颜色 | `ColorUtil.newRandomColor()`：`Color.rgb(rand 0-255, rand 0-255, rand 0-255)` — **整个 RGB 空间，无饱和度 / 亮度限制**，能出黑、白、灰、亮粉等极端色 |
| 双模式 | `ThingBackground.makeRandomBackground()`：**50% 概率纯色，50% 概率双色线性渐变**（hardcoded 50/50） |
| 渐变模型 | `GradualColor(startColor: Int, endColor: Int, orientation: GradientOrientation)`：仅支持**两色**线性渐变，无多 stop；`orientation` 是 8 个方向的 enum |
| 存储 | `Thing.background: String`，一个 JSON 字段。多态序列化：`"0"` PureColor / `"1"` GradualColor / `"2"` PresetImage / `"3"` Image |
| 文字色判定 | `Int.isLightColor()`：Rec.601 luminance `R*0.299 + G*0.587 + B*0.114 > 150` → 选深色文字；否则浅色 |
| 派生色 | HSL 亮度调整、XYZ 亮度调整、`getBlendColorOverlappedBy(overlay)`（叠加半透明色）、`computeGradientColor(c1, c2, fraction)`（gamma 2.2 RGB 插值） |
| ShiningBorder | 纯色：shining = 原色，ordinary = 原色叠 white_45p；渐变：shining = end，ordinary = start |
| 状态栏 / 导航栏 | 根据 `isLightColor()` 切换 icon 明暗 |
| ColorPicker | **没有用户选色 UI**——颜色完全由算法生成，用户不参与挑色（仅可整体刷新新建色） |
| 按颜色搜索 | （未发现，目测随机色模型下也没法 SQL 精确匹配） |

---

## 2. 关键差异 / 不能直接搬过来的点

| # | 差异 | 影响 |
|---|------|------|
| **D1** | 完事儿是**用户可选**的 10 色，目标是**完全算法生成**且不可选 | 你要决定 ColorPicker 留还是不留 |
| **D2** | 完事儿按颜色精确 `WHERE color = ?` 搜索 | 随机 ARGB 下精确匹配几乎不命中，搜索功能要重新设计或砍掉 |
| **D3** | 完事儿全部假设**白文字 on 深背景**，硬编码在 layout XML | 改成自适应需要 layout / code 双侧改 |
| **D4** | `getLightColor` / `getDarkColor` 通过**查表**实现 | 调色板外的颜色返回 0（黑），随机色全部命中这条路径 → 卡片浅色态、ShiningBorder ordinary 色等会变成黑 |
| **D5** | 渐变 → `setBackgroundColor(int)` / `setCardBackgroundColor(int)` API 不收 | 卡片、详情、widget、通知等都要重写为 Drawable 或采用主色降级 |
| **D6** | widget 和系统通知只能接收 int 颜色 | 必须从渐变中提取代表色 |
| **D7** | 老数据全部是固定 palette int | 需要兼容读取，要么"在位升级"，要么保留双字段 |
| **D8** | 完事儿的"暗色 / 亮色变体"是**预先算好的整数颜色**（覆盖透明度） | 算法化派生色要保证视觉上跟原来近似，不然老用户会觉得颜色"变了" |

---

## 3. 已确认的设计决策

| # | 问题 | **选择** | 备注 |
|---|------|---------|------|
| **Q1** | 渐变支持？ | ✓ **要做** | 所有能真渐变的 UI 都做真渐变；widget / 系统通知因 API 限制必须降级到代表色 |
| **Q2** | ColorPicker 怎么办？ | ✓ **保留现有 10 色 + 追加"随机色"按钮** | 现有用户的选色习惯完全保留；多一个"骰子"按钮触发随机生成 |
| **Q3** | 按颜色搜索？ | ✓ **按色相 bucket（蓝色系 / 红色系 等）** | 桶定义见 4.5 节 |
| **Q4** | 文字色策略？ | ✓ **自适应**（同 Everything-Android） | 用 Rec.601 luminance 阈值 150 决定黑 / 白文字 |
| **Q5** | 随机色范围？ | ✓ **不限定，完全随机 RGB** | 照搬 Kotlin 的 `Color.rgb(rand, rand, rand)` |

---

## 3.1 已决策的子问题

ColorPicker "随机"按钮（Q2 追加的）形态：✓ **B. 两个按钮：「随机纯色」「随机渐变」**。用户可控、点哪个出哪个。

---

## 4. 实施方案（按上面 5 个决策的具体落地）

### 4.1 数据模型 & 持久化

#### 4.1.1 现在的状态（DB 现状）

| 项 | 值 |
|----|----|
| 表 | `things` |
| 颜色列 | `color`，类型 **`integer`**，nullable，无默认值（`DBHelper.java:26`） |
| 存储内容 | ARGB int（例如 header 行硬编码 `-14784871`） |
| 当前 DB 版本号 | **8**（`Def.java:36`） |
| 已发布过的版本号 | 1 / 3 / 5 / 6 / 7 / 8（`DBHelper.java:237` 注释） |
| 老版本升级路径 | `DBHelper.onUpgrade()` 已经 case 到 v7→v8，新加列要扩 case 8→9 |

派生表（`reminders` / `habits` / `habit_reminders` / `habit_records` / `app_widget` / `doing_records`）**都没有 color 列**——它们都用 `thing_id` 外键，需要颜色时 join `things` 表临时取，所以**它们的 schema 不会被这次迁移影响**。

#### 4.1.2 新存储设计

**两列并存策略**（不是替换）：

| 列 | 类型 | 含义 | 何时读 |
|----|------|------|--------|
| `color` | integer | **代表色**（PURE 时 = 真实色；GRADIENT 时 = `representativeColor()` 缓存值） | 老代码、降级场景（widget / 通知）、SQL 排序、Cursor 读老数据 |
| `background` | text | JSON：`{"mode":"PURE/GRADIENT","color":<int>,"endColor":<int>,"orientation":"LB_RT"}` | 渲染层，需要完整 background 信息时 |

为什么不直接把 `color` 列改成 TEXT、把 background 塞进去？
- 老代码大量直接读 `c.getInt(3)`（`Thing.java:163`），改成 TEXT 要全部改读
- SQL 排序 / WHERE 还能用 int 比较（虽然 Phase 5 后搜索改 bucket，但其它地方比如 widget 通过 join 取 color 仍然受益）
- 节省 JSON 解析（卡片列表大量 bind，避免每次 parse JSON）

**新建 thing 行为**：
- `ThingDAO.create()` 同时写两列：`color = background.representativeColor()`、`background = json(background)`
- `Thing(Cursor)` 读两列：如果 background 是 null → 回退 `ThingBackground.pure(color)`；否则解析 JSON

**`ThingBackground` POJO**：
```java
public final class ThingBackground {
    public enum Mode { PURE, GRADIENT }
    public enum Orientation { L_R, T_B, LT_RB, RT_LB, LB_RT, RB_LT, R_L, B_T }

    public final Mode mode;
    public final int  color;        // PURE 用；GRADIENT 时 = startColor
    public final int  endColor;     // GRADIENT 用
    public final Orientation orientation;

    public int representativeColor() { ... }
    public static ThingBackground pure(int color) { ... }
    public static ThingBackground gradient(int s, int e, Orientation o) { ... }

    public String toJson() { ... }            // 用 org.json 或现成的 JSONObject
    public static ThingBackground fromJson(String json) { ... }
    public static ThingBackground fromLegacy(int color) { return pure(color); }
}
```

JSON 用 `org.json.JSONObject`（Android 自带，不引入新依赖）。也可以用 Gson 但项目里目前没看到引入。

#### 4.1.3 DB 升级：v8 → v9

在 `DBHelper.java` 加：

```java
private static final String SQL_ADD_COLUMN_BACKGROUND_THINGS =
        "alter table " + Def.Database.TABLE_THINGS
        + " add column " + Def.Database.COLUMN_BACKGROUND_THINGS + " text";
```

`Def.java` 加 `COLUMN_BACKGROUND_THINGS = "background"`，`DATABASE_VERSION` 从 8 改成 **9**。

`SQL_CREATE_TABLE_THINGS` 也要加上 background 列（onCreate 场景，新装用户走这条路）。

`onUpgrade()` 加：
```java
} else if (oldVersion == 8) {
    db.execSQL(SQL_ADD_COLUMN_BACKGROUND_THINGS);
}
```

也要把 v1~v7 的级联路径补好——如果用户跳版本（比如从 v3 直接到 v9），现在的 `onUpgrade` 是 if/else if 单分支，**不会级联执行所有中间步骤**。这是个**已存在的 bug**（如果有用户从 v1 升到 v8 也会遇到）。

> 建议：**不在这次迁移里修这个 bug**。只在自己的 `else if (oldVersion == 8)` 分支里加 background 列。如果担心跨版本，**改成 if 链而不是 else if 链**（每个版本判断 `if (oldVersion < N)` 都执行），但这是一个独立改动。

**新加列 `background TEXT` 是 nullable 无默认值**——SQLite 的 `ALTER TABLE ADD COLUMN` 不会动老行，老行的 `background` 是 NULL。这正是我们想要的：老数据"自然"是 PURE 模式（回退到 color 字段）。

#### 4.1.4 老数据兼容（向后）

```java
public Thing(Cursor c) {
    // ...
    int legacyColor = c.getInt(3);
    int backgroundCol    = c.getColumnIndex(Def.Database.COLUMN_BACKGROUND_THINGS);
    String backgroundJson = backgroundCol >= 0 ? c.getString(backgroundCol) : null;
    if (backgroundJson != null) {
        this.background = ThingBackground.fromJson(backgroundJson);
        this.color = legacyColor;  // 保留代表色字段
    } else {
        this.background = ThingBackground.pure(legacyColor);
        this.color = legacyColor;
    }
}
```

老用户升级后：
- 全部 thing 还是固定 10 色（不变）
- 新建 thing 出渐变是可能的（看用户偏好）
- ColorPicker 的 10 色 FAB 选中状态在老 thing 上还能正常 highlight（因为它们都是 palette 内的 int）

#### 4.1.5 Parcelable

`Thing implements Parcelable` 当前 `writeToParcel` 写 `color (int)`，`Thing(Parcel)` 读 `int`。改造后要顺带写 background。

```java
@Override
public void writeToParcel(Parcel dest, int flags) {
    dest.writeLong(id);
    dest.writeInt(type);
    dest.writeInt(state);
    dest.writeInt(color);             // 保持原位
    // ...其它字段不动...
    dest.writeString(background != null ? background.toJson() : null);   // 末尾追加
}

public Thing(Parcel in) {
    // ...
    color = in.readInt();
    // ...
    String backgroundJson = in.readString();
    background = backgroundJson != null ? ThingBackground.fromJson(backgroundJson) : ThingBackground.pure(color);
}
```

**注意**：必须把新字段加在末尾，老的 readInt/Long 顺序不能动——否则跨进程 / 跨服务的 Parcel（比如 widget 进程读 thing）会错位。

影响：通过 Intent extra 传 Thing 的所有地方（DetailActivity 启动、通知 PendingIntent 等）。只要 writeToParcel / Parcel 构造函数对称更新，调用方不用改。

#### 4.1.6 备份 / 恢复

`BackupHelper.backup()` 把整个 `dataDir` zip 起来（`BackupHelper.java:53`），SQLite 文件就在里面。Restore 是反向解压回去。

- v8 用户备份的 zip 包含 schema v8 的 .db 文件
- 安装新版（v9）后恢复 v8 备份：恢复完 db 文件后，DBHelper 打开 db，发现 version=8，触发 onUpgrade(8, 9) → 加 background 列 → 完事，所有老 thing 是 PURE
- v9 用户备份的 zip 包含 schema v9 的 .db 文件（含 background 列）
- 不会跨版本破坏

**唯一风险**：如果 v9 用户卸载装回老 v8 app（降级）——SQLiteOpenHelper.`onDowngrade` 默认抛异常。看代码 `DBHelper.onDowngrade` 是**反过来调 onUpgrade**：

```java
public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
    if (newVersion < oldVersion) {
        onUpgrade(db, newVersion, oldVersion);
        ...
    }
}
```

这个写法实际上**会错**：`onUpgrade(8, 9)` 是加列，跑在 v9 的 db 上会"add column ... duplicate column name"。但这是**已存在的 bug**，不归这次迁移背锅；用户实际场景里 v9 → v8 降级几乎不会发生。

#### 4.1.7 单 thing 导出（ThingExporter）

`ThingExporter` 把单条 thing 导出为人类可读的 .txt + 附件 zip，**不输出 color**（只输出 title / content / attachment 等内容字段）。不需要改。

#### 4.1.8 影响汇总（DB 层视角）

| 模块 | 是否受影响 | 改动 |
|------|------------|------|
| `things` 表 schema | ✓ | 加 `background TEXT` 列，version 8→9 |
| `things` 表的读写（ThingDAO） | ✓ | create / update / updateState 多写 background；getThingsCursor 多 select background |
| `Thing` model | ✓ | 加 background 字段，构造 / Cursor / Parcel 全改 |
| `reminders` 表 | ✗ | 无 color 列，不变 |
| `habits` / `habit_reminders` / `habit_records` 表 | ✗ | 无 color 列，不变 |
| `app_widget` 表 | ✗ | 用 thing_id join 拿色，schema 不变；只是 Widget 渲染降级到 representativeColor（4.7 已讨论） |
| `doing_records` 表 | ✗ | 无 color 列，不变 |
| 通知（Reminder / Habit / Foreground） | △ | schema 不变，但读 thing.color 设 Notification.setColor 时改读 `representativeColor`（4.7 已讨论） |
| 备份 / 恢复 | ✗ | zip 整个 dataDir，自带 onUpgrade 兼容 |
| 单 thing txt 导出 | ✗ | 不输出 color |
| Thing 跨 Intent 传递（Parcelable） | ✓ | writeToParcel / Parcel 构造函数末尾追加 background JSON |
| SQL 按颜色查询 | ✓ | `WHERE color=?` 改为 Java 内存里按色相桶过滤（4.5 已讨论） |
| `App.updateNewThingColor` 算法 | ✓ | 改为按 representativeColor 距离避邻 |
| `DBHelper.SQL_INSERT_HEADER` | ✗ | header 行的 color = `-14784871`，依然合法 PURE 颜色，background 字段 NULL 即可 |
| `DBHelper.generateInsertInitialSQL` | △ | 初次安装时插入的 7 条 WELCOME / NOTIFY_EMPTY 用 `DisplayUtil.getRandomColor()`，新模型下应该改为 `ThingBackground.fromRandom().toJson()`——但这是新装用户专属代码路径，可以先保持 random color (PURE)，Phase 3 再切 |
| Dialog / Picker / Chooser / Loading 等 16 个 fragment | △ | **内部代码 0 改动**；只需 `DetailActivity.getAccentColor()` 改为返回 `mThing.background.representativeColor()`。详见 4.9.0 |
| `StartDoingActivity` / `DelayReminderActivity` Intent KEY_COLOR | ✗ | 仍传 int 代表色；这俩是透明 activity 包 ChooserDialog，没有全屏背景 |
| `NoticeableNotificationActivity` 图标 / 文字 tint | ✗ | 用 `thing.getColor()` 即代表色；不动 |
| **`DetailActivity.getAccentColor()` ClassCast 风险** | ✓🔴 | 当前实现是从 `mFlRoot` ColorDrawable 反读；Phase 4 必须改为从 `mThing` 取，否则根背景一旦换 GradientDrawable 就崩。详见 4.9.4.1 |
| `DetailActivity` 颜色切换动画 from-color | ✓ | 同上，from-color 也得从 `mThing` 取，不再反读 Drawable |
| `DetailActivity:687` ColorPicker 高亮当前色 | ✓ | `getColorIndex(randomColor)` 返 -1，picker 选中态需新设计 |
| `AuthenticationActivity` / `DoingActivity` | ✗ | 用 `thing.getColor()` 即代表色；不动 |
| `BaseThingWidgetConfiguration`（widget 配置 activity 预览 + Finish 按钮） | ✓ | 预览背景走 `BackgroundUtil.applyBackground` **真渐变**；按钮文字色走代表色（Shader 渐变文字工作量大、收益小，归 4.7.3 备选） |
| `ThingDoingDialogFragment` 内部 CardView 铺事项色 | ✓ | 走 BackgroundUtil **真渐变**（用户已确认"能用就用"） |
| AppWidget 卡片背景（4.7.3） | △ | RemoteViews 限制下需 Bitmap 预渲染才能渐变；放 Phase 4 之后单独迭代 |
| 老版本 onUpgrade 跨版本级联 bug | ✗ | 不在本迁移范围内；不引入新风险 |

### 4.2 颜色应用统一入口

新增 `BackgroundUtil` 工具类：

```java
BackgroundUtil.applyBackground(View v, ThingBackground background);
BackgroundUtil.applyCardBackground(CardView cv, ThingBackground background);
BackgroundUtil.applyTextColors(BaseThingViewHolder holder, ThingBackground background);
int BackgroundUtil.foregroundColorFor(ThingBackground background);  // 白 or 黑
ThingBackground BackgroundUtil.lightVariant(ThingBackground background);  // 替代 getLightColor
```

**所有现在直接调 `setBackgroundColor(int)` / `setCardBackgroundColor(int)` / `getLightColor(int)` 的地方都改用 `BackgroundUtil`。** 这样后续如果加多 stop / radial 等，只改一个工具类。

`BackgroundUtil.applyBackground` 内部：
- PURE → `view.setBackgroundColor(color)`
- GRADIENT → `view.setBackground(new GradientDrawable(orientation, new int[]{start, end}))`

`BackgroundUtil.applyCardBackground`：
- PURE → `cv.setCardBackgroundColor(color)`
- GRADIENT → 包一层 ContentView 设置 GradientDrawable + `cv.setCardBackgroundColor(Color.TRANSPARENT)`（注意 elevation 和 corner radius）

### 4.3 派生色算法化

替换 `DisplayUtil.getLightColor` / `getDarkColor`：

```java
int lighter(int color, float amount)  // HSL L += amount，cap 0..1
int darker(int color, float amount)
boolean isLight(int color)            // Rec.601 luminance > 150
int onColor(int color)                // isLight ? black86p : white86p
```

调色板表查变成 HSL 调整。**视觉对齐**：把现有 `thing_light` 数组里每个颜色和算法输出对比，确保差距可接受（应该差不多，因为 `thing_light` 本来就是叠白）。

### 4.4 随机色生成（Q5：不限定，照搬 Kotlin）

```java
ThingBackground nextRandomBackground() {
    if (rng.nextFloat() < 0.5f) {
        return ThingBackground.pure(randomColor());
    } else {
        return ThingBackground.gradient(randomColor(), randomColor(), randomOrientation());
    }
}

int randomColor() {
    // 完全随机 RGB，无 HSL / luminance 限制（Everything-Android 也是这么干的）
    return Color.rgb(rng.nextInt(256), rng.nextInt(256), rng.nextInt(256));
}
```

`App.updateNewThingColor()` 改为 `updateNewThingBackground()`：生成 `ThingBackground` 并避开列表相邻项（按 representativeColor RGB 距离 > 阈值判断；阈值可调，先用 30）。`App.newThingColor` 字段重命名为 `App.newThingBackground`。

**注意**：因为不限范围，极端色（接近全黑、全白、纯灰）都可能出现，所以 4.6 的文字自适应是**必须**的，不是 nice-to-have。

### 4.5 按颜色搜索（Q3：色相 bucket）

**桶定义**（8 个 bucket）：

| 桶 | 色相区间（HSL hue, 度） | 备注 |
|----|--------------------------|------|
| 红色系 | 345° ~ 360°, 0° ~ 15° | 跨过 0° |
| 橙色系 | 15° ~ 45° | |
| 黄色系 | 45° ~ 70° | |
| 绿色系 | 70° ~ 165° | 区间偏宽，因为人眼对绿敏感、绿色变化大 |
| 青色系 | 165° ~ 195° | |
| 蓝色系 | 195° ~ 255° | |
| 紫色系 | 255° ~ 345° | 包括品红 |
| 无色（灰阶） | — | 当 S < 0.15 时归这里（不看 hue） |

**搜索实现**：`ThingDAO.getThingsForDisplay` 的 `int color` 参数语义改为 "bucket index"（0 = 不筛选，1~8 = 上面的桶）。SQL 仍然 `SELECT * FROM things ...`，**bucket 过滤在 Java 内存层做**——因为：
- SQL 没法算 hue
- `getThingsForDisplay` 本来就是全量加载式（依赖 limit / state 等条件）

渐变事项的桶归属：取 `representativeColor()` 算 hue。也可以更激进——把 start 和 end 各自的桶都算上（这样"含蓝"的渐变能被蓝色系搜到），但实现稍复杂。**默认按 representativeColor 单桶判定**，简单可控；后续可加配置。

**Picker 上的色相桶 UI**：见 4.9。

### 4.6 文字色自适应

策略：把 `card_thing.xml` 里硬编码白色文字**全部去掉**，运行时在 `BaseThingsAdapter.setContentViewAppearance` 里根据 `representativeColor()` 调 `BackgroundUtil.foregroundColorFor()` 设置。

需要新增的 color 资源：
```xml
<color name="text_on_color_light_86p">#DD000000</color>  <!-- 用在浅色背景上的"白" -->
<color name="text_on_color_light_76p">#C2000000</color>
...
```

DetailActivity 同理。同时**状态栏 / actionbar icon** 也要切：用 `WindowInsetsControllerCompat.setAppearanceLightStatusBars(isLight)`。

### 4.7 渐变在各 UI 切入点的策略

**原则（用户决策）**：**"如果记事是渐变色，所有地方能用渐变就用渐变。"**

每一项按"技术能不能渲染渐变"分类：

#### 4.7.1 ✓ 真渐变（GradientDrawable 直接设 View 背景）

这类是 `view.setBackground(GradientDrawable)`，一行就能渲染渐变，工作量小、视觉收益大。**全部做**。

| UI 切入点 | 实现 | 备注 |
|-----------|------|------|
| 卡片背景 | `BackgroundUtil.applyCardBackground(cv, bg)` | CardView 内部 content view 设 GradientDrawable；CardView 自身 `setCardBackgroundColor(TRANSPARENT)`。注意 elevation / corner radius |
| DetailActivity 根背景（`mFlRoot`） | `setBackground(GradientDrawable)` | 详情页主背景 |
| DetailActivity ActionBar | `setBackground(GradientDrawable)` | 当前是 `setBackgroundColor`，切成 Drawable |
| 状态栏（`mStatusBar` view） | `setBackground(GradientDrawable)` | 顶部一条 |
| reveal 动画 (`mViewToReveal`) | view 背景设 GradientDrawable | clip 不影响 drawable 类型 |
| ColorPicker FAB（显示当前渐变色） | FAB 背景设 GradientDrawable | 让用户在 picker 里直观看到挑的渐变 |
| `ThingDoingDialogFragment` 内 CardView (`mCvStartAsBt`) | `BackgroundUtil.applyCardBackground(cv, bg)` | 唯一在 dialog 内部铺事项色的 View，做渐变 |
| `BaseThingWidgetConfiguration` 预览背景 | `BackgroundUtil.applyBackground(view, bg)` | widget 配置 activity 的"事项预览"区域 |
| `NoticeableNotificationActivity` 半透明叠加背景区 | `setBackground(GradientDrawable)` | L324 那块 `getTransparentColor(thing.getColor(), 16)` 现在是单色填充，可以改成 transparent gradient drawable |

#### 4.7.2 ✓ 真渐变（双色驱动，组件自带支持）

`ShiningBorder` 已经支持 shining / ordinary 两套颜色，能"原生"表达双色渐变效果。

| UI 切入点 | 实现 |
|-----------|------|
| ShiningBorder（FAB 全屏 + 卡片 per-item） | PURE 模式：`shining = color`, `ordinary = blend(color, white_45p)`<br>GRADIENT 模式：`shining = endColor`, `ordinary = startColor` |

#### 4.7.3 ✓ 真渐变（需要预渲染 / Shader，工作量较大）

技术上能做但成本高的几项。按"能用就用"原则**做**，但放在 Phase 4 之后单独迭代（不阻塞 MVP）。

| UI 切入点 | 实现 | 工作量 |
|-----------|------|------|
| AppWidget 卡片背景 | RemoteViews `setBackgroundColor(int)` 只收单 int；要做渐变得**预渲染 Bitmap** → `setImageViewBitmap()` 替换原 ImageView，并管理 bitmap 缓存 + widget 更新时间触发重渲染 | 中（~半天） |
| `BaseThingWidgetConfiguration` "完成"按钮文字（如果想要渐变文字） | 用 `TextPaint.setShader(LinearGradient)` 给文字铺渐变 | 小（~10 行），但视觉收益存疑 |
| 通知 large icon / 文字 ForegroundColorSpan | Bitmap 预渲染图标背景；文字 ForegroundColorSpan 只接受单 int → 文字无法渐变 | 部分可行 |

#### 4.7.4 ✗ 必须降级为代表色（API 强制单 int，无法绕过）

这些是 Android 系统 API 强制只收 ARGB int，没有任何方式渲染渐变。一律用 `background.representativeColor()`。

| UI 切入点 | 为什么不能 |
|-----------|-----------|
| `Notification.Builder.setColor(int)` | NotificationBuilder API 写死 int |
| `setColorFilter(int, PorterDuff.Mode)`（图标 tint） | PorterDuff 滤镜本质上是单色乘法，渐变无意义 |
| `ProgressBar` indeterminate drawable tint | PorterDuff SRC_IN，单色 |
| `EditText` 光标 / 文本 highlight tint | `setHighlightColor(int)`, cursorTint 都是单 int |
| Ripple drawable 颜色 | RippleDrawable.setColor 单色 ColorStateList |
| `RecyclerView` edge effect | `EdgeEffect.setColor(int)` 单 int |
| dialog 标题 / 内容 / 按钮文字 `setTextColor(int)`（16 个 fragment） | 理论上 Shader 可做，但视觉细微 + 16 个 fragment 改起来不值；归这一类 |
| `PatternLockView.setCorrectColor` 等 picker 内部 setter | 自定义 view 现在收单 int；改 view 实现成本高 |
| `App` 标题栏 / Toolbar 等被复用的非 thing-color 区域 | 与本次迁移无关 |

#### 4.7.5 不变（与颜色无关）

| UI 切入点 | 备注 |
|-----------|------|
| 卡片 doing 蒙层 (`fl_thing_doing_cover`) | 黑色蒙层，跟事项色无关 |
| 卡片 sticky / ongoing 图标 tint | 跟事项色无关 |

#### 4.7.6 DetailActivity 颜色切换动画

详情页改色（用户在 ColorPicker 选另一个色）的过渡动画，要从 ColorDrawable / GradientDrawable 平滑过渡到 ColorDrawable / GradientDrawable。

**实现**：把 PURE 当作 `(color, color)` 的"退化渐变"——背景永远是 `GradientDrawable(orientation, new int[]{startColor, endColor})`。动画时启两个 `ArgbEvaluator` 同步插值 startColor 和 endColor。这样 PURE ↔ PURE / PURE ↔ GRADIENT / GRADIENT ↔ GRADIENT 都走同一套代码。

```java
ValueAnimator anim = ValueAnimator.ofFloat(0f, 1f);
anim.addUpdateListener(a -> {
    float f = (float) a.getAnimatedValue();
    int s = (int) argbEvaluator.evaluate(f, fromBg.startColor(), toBg.startColor());
    int e = (int) argbEvaluator.evaluate(f, fromBg.endColor(),   toBg.endColor());
    ((GradientDrawable) mFlRoot.getBackground()).setColors(new int[]{s, e});
});
```

需要先把 `mFlRoot.background` 初始化为 GradientDrawable（即使 PURE 也是 start=end=color 的"渐变"）。这样动画 update 可以原地 `setColors()`，不用每帧 new Drawable。

`representativeColor()` 实现：
```java
int representativeColor() {
    if (mode == PURE) return color;
    return Color.rgb(
        (Color.red(color)   + Color.red(endColor))   / 2,
        (Color.green(color) + Color.green(endColor)) / 2,
        (Color.blue(color)  + Color.blue(endColor))  / 2
    );
}
```
RGB 算术平均够用；后续可换 OkLab 或感知均匀空间。

**DetailActivity 颜色切换动画**（现在用 `ArgbEvaluator` 在 600ms 内 fade）：
- 渐变 → 渐变：两个 ArgbEvaluator 同步跑（start 和 end 各一个）
- 纯色 ↔ 渐变互转：把 PURE 当成 `(color, color)` 的渐变即可统一处理
- 抽到 `ThingBackground.lerp(a, b, fraction)` 工具方法

### 4.8 ShiningBorder 颜色

照搬 Everything-Android 的逻辑：

```java
if (background.mode == PURE) {
    shining = background.color;
    ordinary = blend(background.color, white_45p);
} else {
    shining = background.endColor;
    ordinary = background.color;  // start
}
```

替换 `ThingsActivity.setFabEvents` 和 `playNewItemShiningBorder` 中现在直接 `setShiningColor(color) + setOrdinaryColor(lightColor)` 的部分。

### 4.9.0 弹窗 / Dialog / Popup / 通知前景 Activity 的颜色注入

完事儿里所有跟事项颜色挂钩的 dialog / picker / 全屏 overlay 都遵循同一个模式：构造完后调一个 setter 把"主色"（int）传进去，dialog 内部把它应用到标题文字、按钮文字、loading 圈、EditText 焦点 / 光标、RecyclerView edge effect 等。

迁移到 `ThingBackground` 模型后，**只在这一层"取代表色"就够了**——不在 dialog 内部画渐变。理由：
- 这些 dialog 都是模态弹窗，主体是白卡片，只在标题 / 按钮 / 焦点上点缀一抹"主色"；做渐变反而视觉混乱
- Loading 圈 / 进度条 / 焦点反馈这种**点状强调色**渲染成渐变没意义
- 工作量最小：每个 dialog 内部不动，只在调用方传 `background.representativeColor()` 即可

#### 4.9.1 涉及的 dialog / overlay 清单

| 组件 | 接收颜色入口 | 内部染色目标 |
|------|--------------|--------------|
| `AlertDialogFragment` | `setTitleColor / setContentColor / setConfirmColor` | 标题、内容、确认按钮 |
| `ThreeActionsAlertDialogFragment` | `setTitleColor / setContentColor / setContinueColor` | 标题、内容、三个动作按钮 |
| `ChooserDialogFragment` | `setAccentColor` | 标题、确认 / 更多按钮、单选 radio（`RadioChooserAdapter` 用）、RV edge effect |
| `LongTextDialogFragment` | `setAccentColor` | 标题、确认按钮、ScrollView edge effect |
| `LoadingDialogFragment` | `setAccentColor` | 标题、ProgressBar tint（`PorterDuff.SRC_IN`） |
| `PatternLockDialogFragment` | `setAccentColor` | 标题、右键按钮、`PatternLockView.setCorrectColor()` |
| `AttachmentInfoDialogFragment` | `setAccentColor` | 标题、确认按钮 |
| `HabitDetailDialogFragment` / `HabitRecordDialogFragment` | 从 activity `getAccentColor()` 取 | 标题、按钮 |
| `AddAttachmentDialogFragment` | 从 activity 取 | 标题（其余 icon 不染色） |
| `AudioRecordDialogFragment` | 从 activity 取 | VoiceVisualizer renderColor / 底色、EditText 光标 / highlight / tint |
| `ThingDoingDialogFragment` | 用 `mThing.getColor()` 直接取 | 标题、CardView 背景、内嵌 3 个 `ChooserDialogFragment` 的 accent |
| `DateTimeDialogFragment` | `mAccentColor` 从 activity 取 | TabLayout 文字 / 指示器、确认按钮、ViewPager edge、5 个 `InputLayout` 实例、`DateTimePicker` 实例、`RecurrencePickerAdapter` 实例、`TimeOfDayRecAdapter` 实例 |
| `InputLayout` | 构造函数参数 | EditText 文字 / 光标 / highlight / tint，焦点态色 |
| `DateTimePicker` | `setAccentColor` | RecyclerView edge effect |
| `LicenseDialogFragment` | 内部 `getRandomColor()` 自取 | 标题、按钮、链接色（**与事项颜色无关**，不在迁移范围） |
| `TwoOptionsDialogFragment` | 无（仅 icon） | — |

**调用方**（向 dialog 传色的地方）：
- `DetailActivity.getAccentColor()`（1532、1590、2477 等多处） — 这里**改为返回 `mThing.background.representativeColor()`** 即可，下游所有 dialog 不动
- `StartDoingActivity.onCreate`（用 Intent extra `KEY_COLOR`）
- `DelayReminderActivity.onCreate`（同上）
- `NoticeableNotificationActivity`（用 `thing.getColor()` 直接给图标 PorterDuff）

#### 4.9.2 迁移做法（修正：能渐变就传 `ThingBackground`）

> 这一节早先写成"dialog 内部完全不改，只在调用方传 representativeColor 即可"——那是个**保守**的过渡方案，前提是 dialog 内部的所有颜色出口都只是文字 / 图标 tint。但凡 dialog 内部有**色块大区域**（如 `ThingDoingDialogFragment` 那个 `mCvStartAsBt` CardView），降级成 representativeColor 就**永久丢失渐变信息**——这不行。Phase 7 的方向是：**只要 UI 元素能真渐变，就把信号沿着 `ThingBackground` 一路传到那里，不在中间降级成 int**。

**入口适配**：
- `DetailActivity` 暴露 `getAccentBackground(): ThingBackground` 作为主信号；`getAccentColor(): int` 保留为"我只需要单色 tint"场景的便利方法（内部 `getAccentBackground().representativeColor()`）。
- `Thing.getBackground(): ThingBackground` 是模型层的主入口；`Thing.getColor(): int` 是 representative 别名，留给 API 强制单 int 的消费方。

**Dialog 内部**：
- API 强制单 int 的 dialog（AlertDialog、Chooser、Loading 等 16 个 fragment 中绝大多数）保持 `setAccentColor(int)`，由 caller 传 representative。
- **但凡有可渐变 UI 的 dialog（眼下只看到 `ThingDoingDialogFragment` 的 CardView）**，新加一个 `setAccentBackground(ThingBackground)` 入口，内部用 `BackgroundUtil.applyCardBackground` 真渐变渲染。原 `setAccentColor` 仍可用，等同于 PURE 渐变。

**Intent extra 传颜色的地方**：
- 创建型 Intent（FAB → DetailActivity）**永远成对带 KEY_COLOR + KEY_BACKGROUND**；接收方按需读取。Phase 4 已落地。
- 模态型 Intent（StartDoingActivity / DelayReminderActivity 这些只用色 tint 装饰的）保持只传 KEY_COLOR——它们没有可渐变的大区域。

**核心原则**：**`int` 是 representative 的别名，不是 canonical 数据**。只要消费方有能力渲染渐变，就走 `ThingBackground` 路径。

#### 4.9.3 关于 `StartDoingActivity` / `DelayReminderActivity` / `NoticeableNotificationActivity` 的更正

之前我在这里说它们是"全屏 modal 用事项色铺满"——**这不对**，看了源码后澄清：

- `StartDoingActivity`：是**透明背景**的 activity，`onCreate` 立刻 `new ChooserDialogFragment().show()`。屏幕中间只是一个 ChooserDialog；事项颜色用在 `cdf.setAccentColor(color)`（dialog 标题 / 按钮）+ 如果当前在做别的事则再弹 AlertDialog（也用 `mThing.getColor()` 染色）。**没有任何全屏背景需要画事项色**。
- `DelayReminderActivity`：完全同上。
- `NoticeableNotificationActivity`：是全屏 activity，但事项色只用在**图标 `setColorFilter(SRC_ATOP)`**、**文字 `ForegroundColorSpan`** 和某个半透明叠加色 (`getTransparentColor(thing.getColor(), 16)`)。**没有任何区域整块铺事项色**。

所以这三个 activity 全部归入"取代表色塞进现有 API"那一类，**不需要任何特殊渐变设计**。Q-Dialog-1 撤回。

#### 4.9.4 通过 `thing.getColor()` grep 补漏

调查 agent 漏了下面这几处。直接 grep `thing.getColor()` 全部调用点扫一遍后，确认还需要关注的：

| 文件:行 | 用途 | 严重程度 | 改动 |
|---------|------|---------|------|
| **`DetailActivity.java:2242-2243`** | `getAccentColor()` 实现是 `((ColorDrawable) mFlRoot.getBackground()).getColor()` —— 从根 View 反向读 ColorDrawable | 🔴 **关键** | 见下方"4.9.4.1 ClassCast 风险" |
| **`DetailActivity.java:2376 / 2384`** | 颜色切换动画里也是 `((ColorDrawable) view.getBackground()).getColor()` 取 from-color | 🔴 同上 | 同上 |
| `DetailActivity.java:687` | `mColorPicker.pickForUI(DisplayUtil.getColorIndex(thing.getColor(), this))` — 进入详情时让 picker 高亮"当前选中色" | 🟡 | 随机色 `getColorIndex()` 返 -1，picker 没法选中任何 FAB；需在 `BackgroundPicker` 里支持"none/custom"选中态 |
| `AuthenticationActivity.java:79 / 132 / 139` | 指纹 / Pattern 验证页面用 thing.getColor() | 🟢 | 直接走代表色，零改动（`thing.getColor()` 返代表色） |
| `DoingActivity.java:538 / 578 / 579` | 做事计时页面里弹 AlertDialog 用 thing.getColor() | 🟢 | 同上 |
| `BaseThingWidgetConfiguration.java:264 / 324` | 建 widget 时配置页：预览背景 + Finish 按钮文字色 | 🟡 | widget 配置 activity 之前调查完全没覆盖；走代表色，但**预览要不要支持渐变?** 见 4.9.4.2 |
| `ThingDoingDialogFragment.java:91` | dialog 里有个 CardView `setCardBackgroundColor(mThing.getColor())` —— 整块铺事项色 | 🟡 | 这是**唯一**真正"整块铺事项色"的 dialog 内 view；可考虑用 `BackgroundUtil.applyCardBackground` 让它支持渐变 |
| `App.java:490` | `existedColors[j++] = temp.getColor()` 用于 `updateNewThingColor` 避开列表相邻项 | 🟢 | 改为 `temp.getBackground().representativeColor()` |
| 各种 `Receiver` (HabitNotificationAction / ReminderNotificationAction / ReminderReceiver / HabitReceiver) | 通知触发 → 启 StartDoingActivity 时 Intent 透传 `thing.getColor()` | 🟢 | Intent 仍传 int 代表色，零改动 |
| `ThingDoingHelper.java:230` | 启 StartDoingActivity 时透传 color | 🟢 | 同上 |

##### 4.9.4.1 关键 ClassCast 风险（必须 Phase 4 第一步修）

`DetailActivity.getAccentColor()`：

```java
public int getAccentColor() {
    return ((ColorDrawable) mFlRoot.getBackground()).getColor();
}
```

它**不是从 `mThing` 字段取色**，而是从根 View 的 background Drawable 反向读 int。

颜色切换动画 (L2376 / 2384) 同样依赖：

```java
int colorFrom = ((ColorDrawable) mFlRoot.getBackground()).getColor();
int colorFrom = ((ColorDrawable) mActionbar.getBackground()).getColor();
```

**风险**：一旦 Phase 4 把根背景 / actionbar 改成 `GradientDrawable`，这三处直接 `ClassCastException` 崩溃。

**修法（Phase 4 第一步）**：
```java
public int getAccentColor() {
    return mThing.getBackground().representativeColor();
}
```

颜色切换动画的 from-color 也从 `mThing` 取（在动画启动前缓存 `mPreviousBackground` 字段），不再从 Drawable 反读。

##### 4.9.4.2 Widget 配置 activity（`BaseThingWidgetConfiguration`）

用户安装 widget 时进入这个 activity 选要绑哪条 thing；预览区用 `thing.getColor()` 作背景，"完成"按钮文字色也用 `thing.getColor()`。

- 预览背景：和卡片一样，渐变 / 纯色都要支持 → 用 `BackgroundUtil.applyBackground()`
- "完成"按钮文字色：用代表色（按钮文字渐变没意义）

这个 activity 应该跟卡片 / 详情走同一套渐变策略。**Phase 4 单独列一项**。

#### 4.9.5 工作量评估（修正版）

| 类别 | 数量 | 改动 |
|------|------|------|
| Dialog 内部代码（16 个 fragment） | — | 0 行改动 |
| `DetailActivity.getAccentColor()` 改成从 `mThing` 取 | 1 处 | ~5 行（含 colorFrom 缓存） |
| `DetailActivity` 颜色切换动画 from-color 改成从 `mThing` 取 | 2 处 | ~10 行 |
| `DetailActivity:687` 进入详情时 picker 选中态适配 | 1 处 | 视 `BackgroundPicker` 设计 |
| `AuthenticationActivity` / `DoingActivity` | 0 行（thing.getColor() 自动返代表色） | 0 |
| `BaseThingWidgetConfiguration` 预览支持渐变 | 1 处 | ~20 行（背景 + 颜色动画） |
| `ThingDoingDialogFragment` 的 CardView 支持渐变（可选） | 1 处 | ~5 行 |
| `App.updateNewThingColor` 改成按 representativeColor 避邻 | 1 处 | 已在 4.4 |
| 各种 receiver 透传 KEY_COLOR | 0 行 | 0 |

**结论**：dialog 层基本零成本，但**详情页和 widget 配置页的 ClassCast 风险 + 颜色动画 from-color 重构是不可省的硬骨头**。

#### 4.9.6 Phase 1 实际已实现的 luminance-adaptive 清单

> Phase 1 (`BackgroundUtil` 引入) 之后，下面这些 UI 元素已经**按 thing 颜色的 luminance 自适应（黑色侧 vs 白色侧）**——Phase 4 引入渐变后，它们的 `representativeColor()` 自然继续走同一套自适应路径。**修改 thing 颜色时务必确保以下都同步更新**（目前都由 `DetailActivity.applyForegroundColors(color)` 在 `initUI()` 和 `changeColor()` 末尾统一驱动）。

| 位置 | 元素 | Phase 1 处理方式 |
|------|------|------------------|
| **卡片（`card_thing.xml` via `BaseThingsAdapter`）** | tv_thing_title / content / reminder_time / habit_summary / habit_next_reminder / habit_last_five / habit_finished_this_t | 运行时 `setTextColor(textColorPrimary/Secondary/Tertiary/Disabled(thing.getColor()))` |
|  | tv_thing_audio_attachment_count + iv_thing_audio_attachment_count | text 用 tertiary；icon 切 `card_audio_attachment` / `_black` |
| **卡片内 checklist（`CheckListAdapter` TEXTVIEW）** | tv 文字色（未完成/完成）+ checkbox 图标（card / card_black） | `dark()` 切换；BaseThingsAdapter 调 `setThingColor` |
| **DetailActivity 标题区** | mEtTitle text + hint | `setTextColor(primary)` + `setHintTextColor(primary)` |
| **DetailActivity 内容区** | mEtContent text + hint | secondary |
| **DetailActivity 时间区** | mTvUpdateTime / tv_finish_time / tv_type_info | tertiary |
| **DetailActivity 类型图标** | iv_icon_type_info | `ImageViewCompat.setImageTintList` |
| **DetailActivity 编辑区 checklist（`CheckListAdapter` EDITTEXT_*）** | et 文字色（未完成/完成/finished pill）+ et hint（"New item"） | `textColorSecondary/Finished` |
|  | iv_check_list_state / ivDelete / ivExpandShrink | `tintRowIcon` → `ImageViewCompat.setImageTintList(BLACK or null)` |
| **DetailActivity Arrange-items 按钮** | mTvMoveChecklistAsBt text + compound drawable | tertiary + `TextViewCompat.setCompoundDrawableTintList` |
| **DetailActivity 底部 quick-remind** | tv_remind_me + tv_quick_remind text | secondary |
|  | tv_quick_remind 下划线 background drawable | `getBackground().setColorFilter(BLACK, SRC_IN)` |
|  | cb_quick_remind | `CompoundButtonCompat.setButtonTintList` |
| **DetailActivity 顶栏** | mIbBack（返回按钮） | `ImageViewCompat.setImageTintList` |
|  | toolbar overflow icon（"⋮"） | `mActionbar.getOverflowIcon().setColorFilter` + `setOverflowIcon` |
|  | toolbar menu item icons | `item.setIcon(icon.mutate())` + `MenuItem.setIconTintList` (minSdk 26) |
| **系统栏** | 状态栏 icon 明暗 | `DisplayUtil.darkStatusBar / cancelDarkStatusBar`，包在 `mFlRoot.post(...)` 里延后调用 |

**修改颜色时的 single source of truth**：所有上述同步都在 `DetailActivity.applyForegroundColors(int color)` 一个方法里。任何新增的"也用 thing 颜色"的 UI 元素都应该加进这个方法（外加在 `tintMenuIcons` 等子函数里），不要散落到别处。

**Phase 1 未覆盖（留给 Phase 4）**：
- `dashed_line_card` / `dashed_line_check_list_separator` 虚线分隔符（白色 drawable）
- 卡片列表 `iv_thing_reminder` / `iv_thing_habit` / `iv_thing_sticky_ongoing`（BaseThingsAdapter 而非 DetailActivity）
- ColorPicker FAB 选中态的视觉、ripple 颜色
- `AudioAttachmentAdapter` 内部图标 / 文字
- 4.9.4.1 的 `getAccentColor()` ClassCast 风险（Phase 4 第一步要修）
- 4.9.4.2 的 `BaseThingWidgetConfiguration` 预览（widget 配置页）

---

### 4.9 ColorPicker（Q2：保留 10 色 + 追加随机按钮）

布局调整：

```
+----+----+----+----+----+
| C1 | C2 | C3 | C4 | C5 |    ← 现有 10 色 FAB
+----+----+----+----+----+
| C6 | C7 | C8 | C9 | C10|
+----+----+----+----+----+
| 🎲 |                       ← 新增：随机按钮（一个 FAB，icon 是骰子或 shuffle）
+----+
```

随机按钮行为（3.1 节默认按 A）：点一次 → 调 `nextRandomBackground()`，50% 出纯色 / 50% 出双色渐变。被选中后：
- FAB 自己的背景用 `GradientDrawable`（或纯色）显示当前 background，让用户直观看到挑了什么
- 同时把 picker 当前选中态切到这个随机 background
- 再点一次随机按钮 → 重投一次

实现：
- `ColorPicker.java` 加 `mRandomFab` 字段、`mRandomBackground` 字段
- 新增 `getPickedBackground(): ThingBackground`，10 色 FAB 选中时返 `ThingBackground.pure(color)`，随机 FAB 选中时返 `mRandomBackground`
- `getPickedColor()` 保留，返 `getPickedBackground().representativeColor()`，向后兼容
- DetailActivity 的 `setColorPickerEvent` 改 `changeColor(int)` 为 `changeBackground(ThingBackground)`

**搜索场景下的 picker**（按 4.5 色相 bucket 搜索）：
- 用同一个 `ColorPicker` 控件，但参数告诉它"搜索模式"
- 搜索模式下隐藏 10 色 FAB，显示 8 个色相 bucket FAB（每个 FAB 颜色用桶的代表色）+ "无色"FAB
- 返回的不是 int color 而是 `bucketIndex`

或者：搜索 picker 和编辑 picker 拆成两个类。**默认拆成两个**，简单清晰。

ColorPicker 改动比较大，可以新建 `BackgroundPicker.java`（含随机按钮，DetailActivity 用），保留 `ColorPicker.java` 不变直到迁移完——或者直接改 `ColorPicker.java`。**先做并存，后期合并**。

---

## 5. 实施阶段（建议顺序）

每个 Phase 都应独立 commit、独立可回退。

### Phase 1：派生色算法化 + 文字自适应

**不改数据模型**，先把对调色板的硬依赖去掉。

- `DisplayUtil.getLightColor / getDarkColor` 改算法实现（HSL 亮度调整）
- 新增 `BackgroundUtil` 工具类（先只放 `isLight / foregroundColorFor / lighter / darker`）
- 删除 `card_thing.xml` 里硬编码的白色文字，改在 `BaseThingsAdapter` 运行时设置
- `DetailActivity` 文字色同步改运行时设置
- 状态栏 / actionbar icon 明暗用 `WindowInsetsControllerCompat.setAppearanceLightStatusBars(isLight)` 切
- 验证：现有 10 色看起来视觉一致；找一个最浅色（如 `elegant_orange` #D28656）确认文字色没变成不可读

**完成判据**：用现有 10 色测试无视觉回归。

### Phase 2：抽 `BackgroundUtil` 抽象（仍只跑纯色）

- 新增 `ThingBackground` POJO（含 `mode = PURE` 一种状态）
- 所有现有 `setBackgroundColor(int)` / `setCardBackgroundColor(int)` / `getLightColor(int)` 调用改为通过 `BackgroundUtil.applyXxx(view, background)`
- `App.newThingColor` 改为 `App.newThingBackground`（PURE 模式包装老 int）
- DB 不动，DAO 不动
- 此阶段行为应与现在**完全一致**

**完成判据**：app 行为没变化，但代码里"颜色作为 int 流通"已经收敛到 ThingBackground 这一个类型。

### Phase 3：随机色 + DB 新列

- DB 迁移：`ALTER TABLE things ADD COLUMN background TEXT;`
- `Thing` 加 `background` 字段（nullable，向后兼容）
- `Thing(Cursor)` 读 background 字段；为 null 时回退到 `ThingBackground.pure(color)`
- `ThingDAO.create / update` 双写 `color` (= representativeColor) 和 `background` (= JSON)
- `nextRandomBackground()` 此阶段**强制只出 PURE 模式**（暂不开渐变），完全随机 RGB
- `App.updateNewThingBackground()` 替换 `updateNewThingColor`，按 RGB 距离避开列表相邻项

**完成判据**：新建事项颜色随机；老事项颜色未变；DB 加 background 列存了 JSON。

### Phase 4：开启渐变 + 真渐变 UI

**🔴 Phase 4 第一步（先修风险）**：
- `DetailActivity.getAccentColor()` 改成 `return mThing.getBackground().representativeColor();`（不再 cast `ColorDrawable`）
- 颜色切换动画的 from-color 改成从 `mThing` 字段缓存读，不再 `((ColorDrawable) view.getBackground()).getColor()`
- 这两处不修就直接 ClassCast 崩，详见 4.9.4.1

**Phase 4 主要工作**（按 4.7 表分类执行）：
- `nextRandomBackground()` 改回 50/50（纯色 / 渐变）
- **4.7.1 真渐变（一行 setBackground）**：卡片 / 详情根 / actionbar / 状态栏 / mViewToReveal / ColorPicker FAB / ThingDoingDialog CardView / BaseThingWidgetConfiguration 预览 / NoticeableNotificationActivity 半透明区
- **4.7.2 ShiningBorder**：用 start/end 双色驱动
- **4.7.4 API 强制单 int**：通知 setColor / 图标 PorterDuff / ProgressBar tint / EditText 光标 / Ripple / edge effect / dialog 16 个 fragment 内的文字与按钮色 —— 全部用 `representativeColor()` 降级
- **4.7.6 颜色切换动画**：背景统一用 `GradientDrawable`（PURE = start==end 退化渐变），双 ArgbEvaluator 同步插值 start / end；from-color 从 `mThing` 取
- **`DetailActivity:687`**：`mColorPicker.pickForUI(...)` 改为传 ThingBackground，picker 内部支持"非 palette 色"选中态
- **顺带改动**：`getAccentColor()` 改完后，**16 个 dialog 0 改动**自动适配新模型

**Phase 4 之后单独迭代（4.7.3，不阻塞 MVP）**：
- AppWidget 卡片背景渐变（Bitmap 预渲染 + 缓存策略）

**测试要点**：
- CardView + GradientDrawable + corner radius + elevation 的裁切
- dialog 标题 / 按钮在新随机色（可能极暗 / 极亮）下文字对比度
- 切换颜色动画在 GradientDrawable ↔ GradientDrawable / ColorDrawable ↔ GradientDrawable 互转下是否正常
- widget 配置 activity 选中渐变 thing 后预览是否正确

**完成判据**：新建事项可能出渐变；详情、卡片、reveal、shining、widget 配置预览各处都正确显示渐变；widget / dialog 显示代表色，无视觉回归；颜色切换动画不崩。

### Phase 5：搜索按色相 bucket

- `ThingDAO.getThingsForDisplay` 的 `int color` 参数语义改为 `int bucket`（0 = 不筛选，1~8 = 8 个桶）
- SQL 仍然全量加载，Java 内存里按 hue 过滤
- `App.java` 改对应的 limit / search 路径

**完成判据**：搜索 picker 选"红色系"能搜到红色事项，包括纯红和含红的渐变。

### Phase 6：ColorPicker 加随机按钮 + 拆出搜索 picker

- 新增 `BackgroundPicker.java`（编辑用，含 10 色 FAB + 随机 FAB），DetailActivity 切过去
- 新增 `BucketPicker.java`（搜索用，8 桶 FAB），搜索流程切过去
- 旧 `ColorPicker.java` 删或保留作为兼容入口

**完成判据**：DetailActivity 改色界面有"随机"按钮；搜索界面是色相桶。

---

**总工作量估算**：Phase 1 + 2 + 3 大致 1 ~ 2 个工作日；Phase 4 单独 1 ~ 2 天（渐变各种坑）；Phase 5 + 6 合计 1 天。一周内完整跑完是可行的。

---

## 6. 风险 / 待决问题

1. **CardView + GradientDrawable + elevation 的坑**：CardView 给 child 的渐变 drawable 在 round corner 处可能被裁掉，需要测试 `setClipToOutline` 行为。
2. **老数据兼容**：Phase 3 上线后老用户的事项还是 PURE 模式，看起来不变；但新事项变了，可能引起"为什么我以前的事都一个色，新的全花"的疑问 → 是否要给一个"把所有事项颜色随机化"的迁移开关？
3. **AppWidget 主色降级**：渐变 widget 只能取 representativeColor，跟卡片视觉会不一致。可接受吗？
4. **图片附件背景**：卡片有图片附件时，`card_thing.xml` 顶部就是 image + 半透明 cover。渐变在这种情况下视觉效果如何？需要测试。
5. **DetailActivity 的颜色切换动画**（现在用 `ArgbEvaluator` 做颜色淡入）：如果切换到渐变，需要双 ArgbEvaluator 同时跑（start 和 end），或者降级为整体淡入。
6. **ShiningBorder 派生 ordinary color**：当前代码 `getLightColor()` 调色板外返 0（黑）→ 随机色启用后，老逻辑直接挂。这个其实是 Phase 1 必须修的硬伤。
7. **保活通知 / 进度条颜色**：`SystemNotificationUtil.setColor()` 只接收 int，渐变只能降级。
8. **ColorUtils.HSLToColor / colorToHSL 的精度**：往返转换可能有色偏，关键派生需要测试。
9. **`thing_dark` / `thing_light` 数组的去留**：算法化后可以删，但 layout XML 里如有直接引用 `@color/thing_dark` 之类的需要排查（应该没有，因为是 array）。

---

## 7. 不在本方案内但相关的事

- **Backgrounds with image**（Kotlin 那边的 `PresetImage` / `Image` 模式）：本方案**不涉及**。如果后续要加，再开新计划。
- **暗夜模式**：当前完事儿 UI 默认就是亮主题；如果加暗夜模式，颜色策略可能需要 invert。**这次先不考虑**。
- **ColorPicker 在 widget 配置 activity 的露出**：如果 widget 配置允许用户选颜色，要单独处理。先看现状。

---

## 8. 给我反馈

Q1 ~ Q5 + 渐变全覆盖原则 都已经定。还剩下：

1. **3.1 节**：ColorPicker 上"随机"按钮按一次出什么？默认选 A（一个按钮 50/50 纯色 / 渐变）。要 B（两个按钮分别）或 C（短按 / 长按）？
2. **4.7.3 AppWidget 渐变**：要不要在 Phase 4 之后单独做一轮"widget Bitmap 预渲染支持渐变"？默认要（按"能用就用"原则）；只是放在 MVP 之后，不阻塞 Phase 4。
3. **Phase 顺序**：是否 OK？想合并 / 拆分？
4. **章节 6 的风险**：哪些必须先解决再开工？
   - 风险 #2（老数据要不要"全部随机化"迁移） — 默认**不迁移**，老事项颜色完全不动
   - 风险 #4（图片附件卡片在渐变下的视觉） — 计划做完 Phase 4 后再看效果
5. **新关键风险（4.9.4.1）**：`DetailActivity.getAccentColor()` ClassCast — 这个不修 Phase 4 直接崩，已经标 🔴 在 Phase 4 第一步
6. **遗漏的颜色使用点**：你自己用得多但 grep `thing.getColor()` 没覆盖到的地方？

确认后开始 Phase 1。
