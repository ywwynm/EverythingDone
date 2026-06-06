# EverythingDone Android 应用保活策略深度分析

> **分析时间**：2026-05-11
> **分析范围**：根目录下 `app/`、`Everything-Android/`、`swirl/`、`timelytextview/` 全部模块
> **分析结论**：该项目采用**多层防御式保活架构**，核心思路不是"让进程一直活着"，而是通过**多种机制确保闹钟/提醒 reliably 被重建**，整体策略合规且相对优雅，未使用双进程守护、Native fork 等灰色手段。

---

## 目录

1. [总体架构概览](#一总体架构概览)
2. [前台服务保活](#二前台服务保活)
3. [AlarmManager 闹钟保活](#三alarmmanager-闹钟保活)
4. [JobScheduler 周期保活](#四jobscheduler-周期保活)
5. [WorkManager 健康检查](#五workmanager-健康检查)
6. [系统广播触发重建](#六系统广播触发重建)
7. [厂商白名单与电池优化](#七厂商白名单与电池优化)
8. [应用自重启机制](#八应用自重启机制)
9. [无障碍服务保活（新模块）](#九无障碍服务保活新模块)
10. [未使用的保活手段](#十未使用的保活手段)
11. [策略评价与风险](#十一策略评价与风险)

---

## 一、总体架构概览

EverythingDone 的保活体系可以归纳为**"1 个前台服务 + 2 个周期性任务 + 4 个系统广播 + 1 套自愈机制 + 1 个无障碍服务"**的组合防御架构：

```
┌─────────────────────────────────────────────────────────────┐
│                      保活策略全景图                           │
├─────────────────────────────────────────────────────────────┤
│  前台层  │  DoingService（specialUse 前台服务 + WakeLock）    │
├─────────────────────────────────────────────────────────────┤
│  闹钟层  │  AlarmManager.setAlarmClock（最强闹钟）            │
│          │  AlarmManager.setExactAndAllowWhileIdle（备用）    │
├─────────────────────────────────────────────────────────────┤
│  周期层  │  PullAliveJobService（JobScheduler，30分钟）       │
│          │  AlarmHealthWorker（WorkManager，4小时）           │
├─────────────────────────────────────────────────────────────┤
│  事件层  │  BOOT_COMPLETED（开机）                           │
│          │  USER_PRESENT（解锁）                             │
│          │  MY_PACKAGE_REPLACED（升级）                      │
│          │  Alarm 自愈（每6小时检查）                         │
├─────────────────────────────────────────────────────────────┤
│  引导层  │  电池优化白名单申请 + 10+ 厂商自启动页面引导        │
├─────────────────────────────────────────────────────────────┤
│  重启层  │  killMeAndRestart（AlarmManager 延时重启）        │
├─────────────────────────────────────────────────────────────┤
│  新模块  │  EtherealNotificationService（无障碍服务）        │
└─────────────────────────────────────────────────────────────┘
```

---

## 二、前台服务保活

### 2.1 服务声明

**文件**：`app/src/main/AndroidManifest.xml`

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_SPECIAL_USE" />

<service
    android:name=".services.DoingService"
    android:exported="false"
    android:foregroundServiceType="specialUse">
    <property
        android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
        android:value="countdown_timer_for_active_task_tracking" />
</service>
```

**策略分析**：
- 使用 Android 14 引入的 `specialUse` 前台服务类型，需声明 `PROPERTY_SPECIAL_USE_FGS_SUBTYPE`
- `exported="false"` 避免被外部应用调用
- 该服务**不是空转保活**，而是真正用于"正在做"任务的倒计时业务

### 2.2 启动与保活逻辑

**文件**：`app/src/main/java/com/ywwynm/everythingdone/helpers/ThingDoingHelper.java`

```java
// 使用 startForegroundService 启动（Android O+ 要求）
mContext.startForegroundService(serviceIntent);
```

**文件**：`app/src/main/java/com/ywwynm/everythingdone/services/DoingService.java`

```java
@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    // ... 业务逻辑 ...

    // 立即调用 startForeground，避免 ForegroundServiceDidNotStartInTimeException
    Notification initialNotification = SystemNotificationUtil.createDoingNotification(
            this, mThing, STATE_DOING, getInitialLeftTimeStr(), sHrTime, 0);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground((int) mThing.getId(), initialNotification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    } else {
        startForeground((int) mThing.getId(), initialNotification);
    }

    // 不依赖系统 sticky 重启
    return START_NOT_STICKY;
}
```

**策略分析**：
- `START_NOT_STICKY` 表明不依赖系统在 Service 被杀后自动重启，而是依靠闹钟机制在需要时重新触发
- 每秒通过 Handler 更新通知内容，持续维持前台状态

### 2.3 安全兜底机制

```java
/**
 * 当系统 sticky restart（返回 START_STICKY 时可能触发）或 null intent 启动时，
 * 先调用 startForeground 占位，避免 ANR / ForegroundServiceDidNotStartInTimeException
 */
private void promoteToForegroundPlaceholder() {
    Notification placeholder = new NotificationCompat.Builder(this, "doing")
            .setSmallIcon(R.drawable.act_create_white)
            .setContentTitle(getString(R.string.title_activity_doing))
            .setOngoing(false)
            .build();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startForeground(0, placeholder, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
    } else {
        startForeground(0, placeholder);
    }
}
```

### 2.4 WakeLock 保持 CPU 运行

```java
private PowerManager.WakeLock mWakeLock;

@Override
public int onStartCommand(Intent intent, int flags, int startId) {
    PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
    mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EverythingDone:DoingService");
    // ...
}

// 在每秒 Handler 触发时：
if (mWakeLock != null && !mWakeLock.isHeld()) {
    mWakeLock.acquire();
}
```

**策略分析**：
- `PARTIAL_WAKE_LOCK` 确保即使屏幕熄灭，CPU 仍能继续执行倒计时逻辑
- 这是前台服务保活的重要补充，防止 Doze 模式下计时中断

---

## 三、AlarmManager 闹钟保活

### 3.1 核心：setAlarmClock — 最强闹钟方式

**文件**：`app/src/main/java/com/ywwynm/everythingdone/helpers/AlarmHelper.java`

```java
private static void scheduleUserVisibleAlarm(
        Context context, AlarmManager am, long notifyTime, PendingIntent fireIntent) {
    try {
        // 优先使用 setAlarmClock —— 这是 Android 中免疫 Doze 和 OEM 电池优化最强的闹钟方式
        am.setAlarmClock(buildAlarmClockInfo(context, notifyTime), fireIntent);
    } catch (SecurityException e) {
        // 部分设备/权限场景下 setAlarmClock 可能被拒绝，降级到 setExactAndAllowWhileIdle
        Log.w(TAG, "setAlarmClock denied; falling back to setExactAndAllowWhileIdle", e);
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, notifyTime, fireIntent);
    }
}
```

**策略分析**：
- `setAlarmClock` 会在状态栏显示"下一个闹钟"图标，系统会给予最高优先级
- 该 API **免疫 Doze 模式**和绝大多数国产 ROM 的电池优化
- 被 Google Play 认为是合法使用场景，审核风险极低

### 3.2 降级策略：setExactAndAllowWhileIdle

当 `setAlarmClock` 不可用时，降级到 `setExactAndAllowWhileIdle`：
- 可在 Doze 模式的"空闲窗口"触发
- 每个应用每 9 分钟有配额限制，但对提醒类应用通常足够

### 3.3 新模块的 Alarm 封装（较弱）

**文件**：`Everything-Android/app/src/main/java/com/ywwynm/everything/feature/Alarm.kt`

```kotlin
object Alarm : Feature {
    fun setExactAlarm(
        context: Context,
        time: Long,
        requestCode: Int,
        intent: Intent,
        pendingIntentFlags: Int = PendingIntent.FLAG_UPDATE_CURRENT
    ) {
        val alarmManager = context.getSystemService<AlarmManager>()
        val pendingIntent = PendingIntent.getBroadcast(context, requestCode, intent, pendingIntentFlags)
        alarmManager?.setExact(AlarmManager.RTC_WAKEUP, time, pendingIntent)
    }
}
```

**注意**：新模块仅使用 `setExact`，**未使用** `setExactAndAllowWhileIdle` 或 `setAlarmClock`，在 Doze 模式下可能无法触发，保活强度弱于主模块。

---

## 四、JobScheduler 周期保活

### 4.1 PullAliveJobService

**文件**：`app/src/main/java/com/ywwynm/everythingdone/services/PullAliveJobService.java`

```java
public class PullAliveJobService extends JobService {
    @Override
    public boolean onStartJob(final JobParameters params) {
        Log.i(TAG, "Pull Alive job is starting by JobScheduler.");
        mWorker = new Thread(new Runnable() {
            @Override
            public void run() {
                Context context = getApplicationContext();
                // 核心：重建所有闹钟
                AlarmHelper.createAllAlarms(context, true);
                // 恢复常驻通知
                SystemNotificationUtil.tryToCreateQuickCreateNotification(context);
            }
        });
        mWorker.start();
        return true;
    }
}
```

**调度位置**：`app/src/main/java/com/ywwynm/everythingdone/App.java`

```java
private void startPullAliveJob() {
    ComponentName componentName = new ComponentName(this, PullAliveJobService.class);
    JobInfo.Builder builder = new JobInfo.Builder(Integer.MAX_VALUE, componentName);
    builder.setPeriodic(30 * 60 * 1000); // 30 分钟周期
    builder.setPersisted(true);          // 设备重启后保留任务
    JobScheduler jobScheduler = (JobScheduler) getSystemService(JOB_SCHEDULER_SERVICE);
    jobScheduler.schedule(builder.build());
}
```

**策略分析**：
- 30 分钟周期执行，任务很轻量（重建闹钟 + 恢复通知）
- `setPersisted(true)` 确保重启后任务仍在
- 明确注释说明这是为 **EMUI / MIUI 等国产 ROM** 设计的 best-effort 安全网
- JobScheduler 在 Android 12+ 会受到更严格的限制，但仍可作为补充手段

---

## 五、WorkManager 健康检查

### 5.1 AlarmHealthWorker

**文件**：`app/src/main/java/com/ywwynm/everythingdone/services/AlarmHealthWorker.java`

```java
public class AlarmHealthWorker extends Worker {
    @NonNull
    @Override
    public Result doWork() {
        // 核心：重建所有闹钟
        AlarmHelper.createAllAlarms(getApplicationContext(), false);
        return Result.success();
    }

    public static void schedule(Context context) {
        PeriodicWorkRequest request = new PeriodicWorkRequest.Builder(
                AlarmHealthWorker.class, 4, TimeUnit.HOURS)
                .addTag(UNIQUE_WORK_NAME)
                .build();
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request);
    }
}
```

**调度位置**：`App.java:126`

```java
AlarmHealthWorker.schedule(this);
```

**策略分析**：
- WorkManager 拥有**独立于 JobScheduler/AlarmManager 的持久化层**
- 即使应用被 force-stop，WorkManager 在特定条件下仍可恢复任务
- 4 小时周期比 JobScheduler 更长，资源消耗更低
- 作为 JobScheduler 的**互补机制**，形成双重保险

### 5.2 Alarm 自愈机制

**文件**：`app/src/main/java/com/ywwynm/everythingdone/App.java`

```java
private void selfHealAlarmsIfStale() {
    final SharedPreferences sp = getSharedPreferences(Def.Meta.PREFERENCES_NAME, MODE_PRIVATE);
    long lastRebuild = sp.getLong(Def.Meta.KEY_LAST_ALARM_REBUILD, 0L);
    long now = System.currentTimeMillis();
    // 6 小时内未重建过才执行
    if (now - lastRebuild < ALARM_SELF_HEAL_INTERVAL_MS) {
        return;
    }
    new Thread(new Runnable() {
        @Override
        public void run() {
            AlarmHelper.createAllAlarms(App.this, false);
            sp.edit().putLong(Def.Meta.KEY_LAST_ALARM_REBUILD, System.currentTimeMillis()).apply();
        }
    }, "alarm-self-heal").start();
}
```

**策略分析**：
- 每次应用启动时检查，如果超过 6 小时未重建闹钟，则触发重建
- 这是一个**被动自愈**机制，防止长期未启动导致闹钟丢失

---

## 六、系统广播触发重建

该项目注册了多个系统广播接收器，在关键系统事件触发时重建闹钟和通知，形成**事件驱动的保活网**。

### 6.1 BOOT_COMPLETED — 开机自启

**文件**：`app/src/main/java/com/ywwynm/everythingdone/receivers/BootReceiver.java`

```java
if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
    Log.i(TAG, "Device boot, EverythingDone is responding...");
    final Context appContext = context.getApplicationContext();
    new Thread(new Runnable() {
        @Override
        public void run() {
            AlarmHelper.createAllAlarms(appContext, true);
            SystemNotificationUtil.tryToCreateQuickCreateNotification(appContext);
            SystemNotificationUtil.tryToCreateThingOngoingNotification(appContext);
            AppWidgetHelper.updateAllAppWidgets(appContext);
        }
    }).start();
}
```

**权限声明**：

```xml
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
```

**策略分析**：
- 这是最基础也是最可靠的保活手段之一
- 设备重启后自动恢复所有闹钟和通知
- 几乎所有 ROM（包括华为、小米）都允许此权限

### 6.2 USER_PRESENT — 解锁屏幕

**文件**：`app/src/main/java/com/ywwynm/everythingdone/receivers/UserPresentReceiver.java`

```java
if (Intent.ACTION_USER_PRESENT.equals(intent.getAction())) {
    Log.i(TAG, "Screen is on, EverythingDone is responding...");
    final Context appContext = context.getApplicationContext();
    new Thread(new Runnable() {
        @Override
        public void run() {
            AlarmHelper.createAllAlarms(appContext, false);
            SystemNotificationUtil.tryToCreateQuickCreateNotification(appContext);
        }
    }).start();
}
```

**策略分析**：
- 用户每次解锁屏幕时触发，是一个非常频繁的重建时机
- 代码注释明确说明这是为 **EMUI、MIUI 等第三方 ROM** 设计
- 这些 ROM 经常在后台杀死应用，用户解锁使用手机时恰好是重建闹钟的最佳时机

### 6.3 MY_PACKAGE_REPLACED — 应用升级

**文件**：`app/src/main/java/com/ywwynm/everythingdone/receivers/AppUpdateReceiver.java`

```java
if (Intent.ACTION_MY_PACKAGE_REPLACED.equals(intent.getAction())) {
    AlarmHelper.createAllAlarms(appContext, false);
    SystemNotificationUtil.tryToCreateQuickCreateNotification(appContext);
    AppWidgetHelper.updateAllAppWidgets(appContext);
}
```

**策略分析**：
- 应用升级后，之前的 AlarmManager 设置的 PendingIntent 可能会丢失
- 此广播确保升级后立即重建所有闹钟

### 6.4 其他业务广播

| 接收器 | 用途 |
|--------|------|
| `ReminderReceiver` | 自定义提醒闹钟触发 |
| `HabitReceiver` | 习惯提醒触发 |
| `AutoNotifyReceiver` | 自动提醒触发 |
| `DailyUpdateHabitReceiver` | 每日习惯状态更新 |
| `DailyCreateTodoReceiver` | 每日自动创建待办 |
| `LocaleChangeReceiver` | 语言切换后更新小部件 |

---

## 七、厂商白名单与电池优化

### 7.1 电池优化白名单申请

**文件**：`app/src/main/java/com/ywwynm/everythingdone/helpers/NotificationReliabilityHelper.java`

```java
public static boolean isBatteryOptimizationIgnored(Context context) {
    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
    return pm != null && pm.isIgnoringBatteryOptimizations(context.getPackageName());
}

@SuppressLint("BatteryLife")
public static boolean requestIgnoreBatteryOptimization(Context context) {
    Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
    intent.setData(Uri.parse("package:" + context.getPackageName()));
    return startSafely(context, intent);
}
```

**权限声明**：

```xml
<uses-permission android:name="android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" />
```

### 7.2 国产厂商自启动页面引导

```java
public static boolean openVendorAutostartSettings(Context context) {
    for (ComponentName candidate : autostartCandidates()) {
        Intent intent = new Intent();
        intent.setComponent(candidate);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (startSafely(context, intent)) return true;
    }
    return openAppDetailsSettings(context);
}
```

**覆盖的厂商白名单页面**：

| 厂商 | 包名/Activity |
|------|--------------|
| **小米/MIUI** | `com.miui.securitycenter` / `AutoStartManagementActivity` |
| **华为/EMUI** | `com.huawei.systemmanager` / `StartupNormalAppListActivity` |
| **OPPO/ColorOS** | `com.coloros.safecenter` / `StartupAppListActivity` |
| **Vivo/iQOO** | `com.iqoo.secure` / `AddWhiteListActivity` |
| **魅族** | `com.meizu.safe` / `PermissionMainActivity` |
| **三星** | `com.samsung.android.lool` / `BatteryActivity` |
| **一加** | `com.coloros.safecenter` |
| **中兴** | `com.zte.heartyservice` / `AppAutoRunManager` |

**策略分析**：
- 通过跳转厂商自带的白名单设置页面，引导用户手动将应用加入自启动/后台运行白名单
- 这是应对国产 ROM 后台限制最有效的方式之一
- 如果所有厂商页面都无法打开，则降级到系统应用详情页

---

## 八、应用自重启机制

### 8.1 killMeAndRestart

**文件**：`app/src/main/java/com/ywwynm/everythingdone/App.java`

```java
public static void killMeAndRestart(Context context, Class toLaunch, long time) {
    // 构建启动 Intent
    Intent intent;
    if (toLaunch == null) {
        intent = context.getPackageManager().getLaunchIntentForPackage(
                context.getPackageName());
    } else {
        intent = new Intent(context, toLaunch);
    }
    intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);

    // 用 AlarmManager 设置一个精确闹钟，延迟重启
    PendingIntent pendingIntent = PendingIntent.getActivity(context,
            0, intent, PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    AlarmManager am = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + time + 100, pendingIntent);

    // 延迟结束当前进程
    Handler handler = new Handler(Looper.getMainLooper());
    handler.postDelayed(new Runnable() {
        @Override
        public void run() {
            System.exit(0);
        }
    }, time);
}
```

**策略分析**：
- 这是一种**可控的自重启**机制，不是被动保活
- 通常用于主题切换、语言切换等需要重启应用生效的场景
- 利用 `AlarmManager` 设置精确闹钟，确保应用能在指定时间被拉起
- 相比双进程守护，这种方式更轻量且合规

---

## 九、无障碍服务保活（新模块）

### 9.1 EtherealNotificationService

**文件**：`Everything-Android/app/src/main/java/com/ywwynm/everything/background/service/EtherealNotificationService.kt`

```kotlin
class EtherealNotificationService : AccessibilityService(), MetaversalLoggable {
    override fun onServiceConnected() {
        // 初始化无障碍服务
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 监听无障碍事件
    }
    override fun onInterrupt() {
        // 服务中断处理
    }
}
```

**Manifest 声明**：

```xml
<service
    android:name=".background.service.EtherealNotificationService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
</service>
```

**策略分析**：
- AccessibilityService 是**系统级服务**，通常比普通 Service 更难被系统杀死
- 该服务在新模块（Kotlin 重构模块）中用于"空灵通知"功能
- 无障碍服务需要用户在系统设置中手动开启，有一定使用门槛
- Google Play 对无障碍服务的使用有严格审核，必须用于真正的无障碍功能

### 9.2 新模块的 AlarmReceiver

**文件**：`Everything-Android/app/src/main/java/com/ywwynm/everything/background/receiver/AlarmReceiver.kt`

```kotlin
class AlarmReceiver : BroadcastReceiver(), MetaversalLoggable {
    override fun onReceive(context: Context?, intent: Intent?) {
        using feature EtherealNotification {
            eventTunnel.triggerFor<EtherealNotificationService>("")
        }
    }
}
```

---

## 十、未使用的保活手段

该项目**明确没有使用**以下常见（但通常被视为灰色或不推荐的）保活手段：

| 手段 | 是否使用 | 说明 |
|------|---------|------|
| **双进程守护** | ❌ | 未设置任何 `:remote` 进程 |
| **Native 层保活** | ❌ | 无 C/C++ 代码，无 JNI，无 fork 子进程 |
| **START_STICKY** | ❌ | DoingService 返回 `START_NOT_STICKY` |
| **一像素 Activity** | ❌ | 未找到相关代码 |
| **AccountSyncAdapter** | ❌ | 未使用账号同步机制 |
| **粘性广播** | ❌ | 未发送/接收粘性广播 |
| **音频播放保活** | ❌ | 未使用无声音频保持前台 |
| **蓝牙/GPS 扫描保活** | ❌ | 未滥用硬件扫描 |

**这体现了开发团队对合规性和用户体验的重视**。

---

## 十一、策略评价与风险

### 11.1 核心设计哲学

该项目的保活策略遵循一个非常清晰的设计哲学：

> **"不追求进程永远存活，而是确保提醒/闹钟在任何情况下都能可靠触发。"**

这是 Android 官方推荐的方向，也是 Google Play 审核最友好的方式。

### 11.2 多层防御效果

| 层级 | 机制 | 触发条件 | 可靠性 |
|------|------|---------|--------|
| 第一层 | `setAlarmClock` | 定时触发 | ⭐⭐⭐⭐⭐ 几乎不可阻挡 |
| 第二层 | `BOOT_COMPLETED` | 开机 | ⭐⭐⭐⭐⭐ 非常可靠 |
| 第三层 | JobScheduler（30min） | 周期性 | ⭐⭐⭐⭐ 高（Android 12+ 受限） |
| 第四层 | WorkManager（4h） | 周期性 | ⭐⭐⭐⭐ 高（独立持久化层） |
| 第五层 | `USER_PRESENT` | 解锁屏幕 | ⭐⭐⭐ 中（依赖用户使用频率） |
| 第六层 | Alarm 自愈 | 应用启动 | ⭐⭐⭐ 中（被动触发） |
| 第七层 | 厂商白名单 | 用户手动 | ⭐⭐⭐⭐⭐ 一旦设置非常可靠 |

### 11.3 潜在风险与改进建议

1. **前台服务类型限制**：
   - `specialUse` 前台服务在 Android 14+ 有严格限制，必须确保业务场景符合要求
   - 建议保留详尽的说明文档，以备 Google Play 审核询问

2. **新模块 Alarm 强度不足**：
   - `Everything-Android` 新模块使用 `setExact` 而非 `setAlarmClock`
   - 在 Doze 模式下可能无法触发，建议统一使用与主模块相同的策略

3. **WorkManager 依赖 Google Play 服务**：
   - 在部分无 GMS 的国产设备上（如华为新机型），WorkManager 的可靠性会下降
   - 建议在这些设备上增加额外的 JobScheduler 补偿机制

4. **无障碍服务的审核风险**：
   - `EtherealNotificationService` 作为 AccessibilityService，Google Play 审核时可能要求证明其用于真正的无障碍目的
   - 建议准备完整的功能说明文档

### 11.4 总结

EverythingDone 的保活策略是**业内较为成熟和合规的方案**：

- ✅ 不依赖灰色手段（双进程、Native 层、一像素等）
- ✅ 充分利用官方高优先级 API（`setAlarmClock`、前台服务）
- ✅ 多层防御，互为备份
- ✅ 针对国产 ROM 有特殊适配（白名单引导、`USER_PRESENT`）
- ✅ 业务与保活结合紧密，不是"为了保活而保活"

这是一个**值得参考的 Android 提醒类应用保活架构**。

---

> **免责声明**：本分析仅用于技术学习和研究，不构成任何开发建议。Android 系统的后台策略持续变化，请始终遵循 Google Play 政策和中国大陆各应用商店的上架规范。
