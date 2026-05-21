# EverythingDone: Migration Plan to Android 16 (API 36)

## Current State (Before Migration)

| Component              | Current Version          |
|------------------------|--------------------------|
| Gradle                 | 3.3 (March 2017)         |
| Android Gradle Plugin  | 2.3.1 (April 2017)      |
| compileSdkVersion      | 25 (Android 7.1)         |
| targetSdkVersion       | 25                       |
| minSdkVersion          | 16 (Android 4.1)         |
| buildToolsVersion      | 25.0.2                   |
| JDK                    | 7/8                      |
| Support Library         | 25.3.1                  |
| Repository             | jcenter (deprecated)     |
| Dependency keyword     | `compile` (deprecated)   |

## Target State (Achieved ✅)

| Component              | Target Version           | Status |
|------------------------|--------------------------|--------|
| Gradle                 | 9.4.1                    | ✅     |
| Android Gradle Plugin  | 9.2.1                    | ✅     |
| compileSdkVersion      | 36                       | ✅     |
| targetSdkVersion       | 36                       | ✅     |
| minSdkVersion          | 23                       | ✅     |
| buildToolsVersion      | (removed, auto-managed)  | ✅     |
| JDK                    | 17                       | ✅     |
| AndroidX               | latest stable            | ✅     |
| Repository             | mavenCentral + google()  | ✅     |
| Dependency keyword     | `implementation`          | ✅     |

---

## Review Addendum (2026-05-06) — Status Update 2026-05-08

All review items have been addressed:

- ✅ `PendingIntent` usage — all occurrences now include mutability flags.
- ✅ `Uri.fromFile(...)` — replaced with FileProvider content:// URIs.
- ✅ Exact alarms — `USE_EXACT_ALARM` declared; app qualifies as alarm/calendar-style.
- ✅ `DoingService` — FGS type `specialUse` declared, `startForeground()` calls pass correct type on API 34+.
- ✅ `PullAliveJobService` — no longer relied on for guaranteed reminders; `exported="false"`.
- ✅ Predictive back — `onBackPressed()` migrated to `OnBackPressedDispatcher`.
- ⚠️ Large-screen adaptive layout — user deferred (not in current scope).
- ✅ No native `.so` — 16 KB page-size risk confirmed low.

---

## Phase 0: Prerequisites ✅

### Step 0.1 — Install Required Tools ✅

- [x] Install **JDK 17** (e.g. Eclipse Temurin 17 or Oracle JDK 17).
- [x] Install **Android Studio Meerkat** (2024.3.1) or later.
- [x] Via SDK Manager, install:
  - Android SDK Platform 36 (Android 16)
  - Android SDK Build-Tools (latest)
  - Android SDK Platform-Tools (latest)

### Step 0.2 — Create a Migration Branch ✅

Branch `migration/android-16` created and active.

---

## Phase 1: Build System — Get the Project Compiling ✅

### Step 1.1 — Upgrade Gradle Wrapper ✅

`gradle-9.4.1-bin.zip` configured.

### Step 1.2 — Upgrade Root `build.gradle` ✅

- `jcenter()` → `google()` + `mavenCentral()`
- AGP `2.3.1` → `9.2.1`
- Removed obsolete `import` and `tasks.withType(Compile)` block.

### Step 1.3 — Upgrade `app/build.gradle` ✅

- Added `namespace 'com.ywwynm.everythingdone'`
- `compileSdk 36`, `targetSdk 36`, `minSdk 23`
- `compile` → `implementation`
- Support Library → AndroidX equivalents
- Added `compileOptions` for Java 17
- All third-party libraries bumped to compatible versions

### Step 1.4 — Upgrade Library Module Build Files ✅

`swirl/build.gradle` and `timelytextview/build.gradle` updated with namespace, SDK versions, and compileOptions.

### Step 1.5 — Remove `package` from Manifests ✅

All three manifests updated.

### Step 1.6 — Add `gradle.properties` Settings ✅

```properties
android.useAndroidX=true
android.nonTransitiveRClass=true
org.gradle.java.home=E\:\\JDK17
org.gradle.jvmargs=-Xmx4096m -Dfile.encoding=UTF-8
org.gradle.parallel=true
org.gradle.caching=true
org.gradle.configuration-cache=true
```

### Step 1.7 — Migrate All Java Imports from Support Library to AndroidX ✅

All 91+ files migrated via automated tool + manual fixes.

### Step 1.8 — Update ProGuard Rules ✅

Updated for AndroidX and Glide 5.x.

### Step 1.9 — Fix Glide 3 → 5 API Changes ✅

All Glide API calls updated to Glide 5.x compatible forms.

### Step 1.10 — Build and Fix Remaining Compilation Errors ✅

`./gradlew.bat assembleDebug` passes.

---

## Phase 2: AndroidManifest — Satisfy API 31+ Requirements ✅

### Step 2.1 — Add `android:exported` to All Components ✅

All activities, services, and receivers now declare `android:exported`.

### Step 2.2 — Add Required Permissions ✅

All required permissions declared:
- `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_SPECIAL_USE`
- `POST_NOTIFICATIONS`
- `USE_EXACT_ALARM`
- `USE_FULL_SCREEN_INTENT`

### Step 2.3 — Update Permission Declarations for Scoped Storage ✅

- `WRITE_EXTERNAL_STORAGE` limited to `maxSdkVersion="28"`
- `READ_EXTERNAL_STORAGE` limited to `maxSdkVersion="32"`
- `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO` declared

### Step 2.4 — Update Biometric Permission ✅

`USE_BIOMETRIC` replaces deprecated `USE_FINGERPRINT`.

### Step 2.5 — Update Camera Feature Declaration ✅

Camera declared `required="false"`.

### Step 2.6 — Add Foreground Service Type ✅

`DoingService` declares `foregroundServiceType="specialUse"` with subtype property.

### Step 2.7 — Remove `android.max_aspect` Meta-data ✅

Removed.

### Step 2.8 — Add Package Visibility Queries ✅

`<queries>` entries added for camera, video, and content pickers.

### Step 2.9 — Re-check FileProvider Paths ✅

Provider class migrated to `androidx.core.content.FileProvider`.

---

## Phase 3: PendingIntent — Fix Crashes on API 31+ ✅

### Step 3.1 — Update `AlarmHelper.java` ✅

All `PendingIntent.getBroadcast()` calls include `FLAG_IMMUTABLE`.

### Step 3.2 — Update `SystemNotificationUtil.java` ✅

All notification PendingIntents include `FLAG_IMMUTABLE`.

### Step 3.3 — Search for Any Remaining PendingIntent Usage ✅

All occurrences across the codebase now include mutability flags (including AppWidgetHelper, AutoNotifyHelper, ReminderReceiver, SettingsActivity, and widget classes).

### Step 3.4 — Rework Exact and Repeating Alarm Semantics ✅

- Reminder and habit alarms use `setExactAndAllowWhileIdle()` one-shot with reschedule in receiver
- `DailyUpdateHabitReceiver` re-schedules itself
- `USE_EXACT_ALARM` permission declared

---

## Phase 4: Notification Channels — Fix Silent Notifications on API 26+ ✅

### Step 4.1 — Create Notification Channels in `App.java` ✅

Seven channels created: reminder, habit, goal, doing, quick_create, ongoing, auto_notify.

### Step 4.2 — Pass Channel ID to All NotificationCompat.Builder Calls ✅

All `NotificationCompat.Builder` calls include the appropriate channel ID.

### Step 4.3 — Request POST_NOTIFICATIONS at Runtime (API 33+) ✅

Runtime permission request added in `SettingsActivity.saveSettings()` when notification features are enabled. New request code `REQUEST_PERMISSION_NOTIFICATION = 20`.

---

## Phase 5: Foreground Service — Fix on API 26+ ✅

### Step 5.1 — Use `startForegroundService()` on API 26+ ✅

`ThingDoingHelper.java` updated.

### Step 5.2 — Ensure `startForeground()` Is Called Within 5 Seconds ✅

`DoingService.onStartCommand()` calls `startForeground()` immediately.

### Step 5.3 — Pass the Foreground Service Type at Runtime ✅

API 34+ path uses `FOREGROUND_SERVICE_TYPE_SPECIAL_USE`.

### Step 5.4 — Rework `PullAliveJobService` ✅

- `exported` changed to `false`
- Treated as best-effort; exact alarms are the primary reliability mechanism

---

## Phase 6: Storage Access — Fix for Scoped Storage (API 29+) ✅

### Step 6.1 — Audit All `Environment.getExternalStorageDirectory()` Calls ✅

- `Def.Meta.APP_FILE_DIR` constant removed
- All callers migrated to `Def.getAppFileDir(context)` (maps to `getExternalFilesDir(null)`)
- Attachment cleanup checks both old and new paths for backward compatibility

### Step 6.2 — Migrate Backup/Restore to SAF ✅

Already using SAF (`ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`) in SettingsActivity.

### Step 6.3 — Migrate Custom Ringtone Access ✅

Ringtone files served via FileProvider `content://` URIs on API 24+.

### Step 6.4 — Replace `Uri.fromFile(...)` Sharing ✅

Uses FileProvider `getUriForFile()` with `FLAG_GRANT_READ_URI_PERMISSION`.

### Step 6.5 — Replace Legacy Runtime Storage Permission Requests ✅

`WRITE_EXTERNAL_STORAGE` limited to API 28-. Granular media permissions used on API 33+.

---

## Phase 7: Dynamic BroadcastReceiver — Fix for API 34+ ✅

### Step 7.1 — Add Export Flag to `registerReceiver()` Calls ✅

All 4 files use `ContextCompat.registerReceiver(..., RECEIVER_NOT_EXPORTED)`.

---

## Phase 8: Biometric Authentication ✅

### Step 8.1 — Migrate to BiometricPrompt ✅

- Added `androidx.biometric:biometric:1.1.0`
- `FingerprintHelper` fully switched to `BiometricPrompt`
- Old `FingerprintManager` platform path removed
- `FingerprintDialogFragment.java` deleted

### Step 8.2 — Update `swirl` Library Module ✅

Kept as-is (purely visual, no API dependency).

---

## Phase 9: Activity Transition API ✅

### Step 9.1 — Replace `overridePendingTransition()` ✅

All 5 locations updated to use `overrideActivityTransition()` on API 34+ with legacy fallback.

---

## Phase 10: Edge-to-Edge ⚠️ Partially Complete

### Step 10.1 — Apply Window Insets to All Activities ⚠️

Top status bar insets handled. Bottom navigation bar insets not yet unified.

### Step 10.2 — Update Themes ✅

`darkStatusBar` / `cancelDarkStatusBar` migrated to `WindowInsetsControllerCompat`.

### Step 10.3 — Test All Screens ⚠️

Bottom nav bar occlusion testing pending.

---

## Phase 11: Predictive Back Gesture ✅

### Step 11.1 — Audit Back Navigation ✅

`onBackPressed()` overrides identified in: ThingsActivity, DetailActivity, ImageViewerActivity, BaseThingWidgetConfiguration, PopupPicker.

### Step 11.2 — Migrate to OnBackPressedCallback ✅

Main flows migrated to `OnBackPressedDispatcher`.

---

## Phase 12: Adaptive Layout ❌ Deferred

User confirmed: large screen adaptation not in current scope.

---

## Phase 13: Deprecated API Cleanup ✅

### Step 13.1 — `DeviceUtil.java` ✅

`isScreenOn()` → `isInteractive()` (API 20, safe with minSdk 23).

### Step 13.2 — Remove Pre-API-23 Code Branches ✅

~47 dead branches (`hasKitKatApi`, `hasLollipopApi`, `hasMarshmallowApi`) removed across 22 files.

### Step 13.3 — Replace `WakeLock` Tag ✅

Tag updated to `"EverythingDone:DoingService"`.

---

## Phase 14: Testing & Validation ⚠️ In Progress

### Step 14.1 — Test Matrix

| API Level | Android Version | Priority | Status        |
|-----------|-----------------|----------|----------------|
| 23        | 6.0 Marshmallow | Medium   | ⚠️ Not tested  |
| 26        | 8.0 Oreo        | High     | ⚠️ Not tested  |
| 31        | 12              | High     | ⚠️ Not tested  |
| 33        | 13              | High     | ⚠️ Not tested  |
| 34        | 14              | High     | ⚠️ Not tested  |
| 35        | 15              | High     | ⚠️ Not tested  |
| 36        | 16              | Critical | ✅ User tested |

### Step 14.2 — Feature Test Checklist

- [x] Create note, reminder, habit, goal
- [x] Receive reminder notification on time
- [x] Notification actions work (finish, delay, start doing)
- [x] Habit daily update fires at midnight
- [x] Doing mode countdown works correctly
- [x] Pattern lock / biometric unlock
- [ ] Backup and restore
- [x] All widgets display and update correctly
- [x] Share intent (receive text/image from other apps)
- [ ] Share attachments/screenshots/logs to other apps using `content://` URIs
- [ ] Add image/video/audio attachments
- [ ] Boot receiver re-creates alarms
- [ ] Custom ringtone plays in notifications
- [x] Quick-create ongoing notification
- [ ] App works in landscape on tablets
- [x] Back gesture works correctly on all screens
- [x] Exact alarm permission declared (policy review needed for Play Store)
- [x] `PullAliveJobService` treated as best-effort

---

## Summary: Dependency Version Reference

| Dependency                          | Old Version  | New Version  | Status |
|-------------------------------------|-------------|--------------|--------|
| Android Gradle Plugin               | 2.3.1       | 9.2.1        | ✅     |
| Gradle                              | 3.3         | 9.4.1        | ✅     |
| JDK                                 | 7/8         | 17           | ✅     |
| compileSdk / targetSdk              | 25          | 36 / 36      | ✅     |
| minSdk                              | 16          | 23           | ✅     |
| Support Library / AndroidX appcompat| 25.3.1      | 1.7.1        | ✅     |
| Support Library v4 / AndroidX core  | 25.3.1      | 1.18.0       | ✅     |
| Design / Material Components        | 25.3.1      | 1.13.0       | ✅     |
| RecyclerView                        | 25.3.1      | 1.4.0        | ✅     |
| CardView                            | 25.3.1      | 1.0.0        | ✅     |
| AndroidX Activity                   | —           | 1.13.0       | ✅     |
| AndroidX Fragment                   | 25.3.1      | 1.8.9        | ✅     |
| AndroidX ViewPager                  | 25.3.1      | 1.1.0        | ✅     |
| AndroidX DrawerLayout               | 25.3.1      | 1.2.0        | ✅     |
| Biometric (new)                     | —           | 1.1.0        | ✅     |
| PhotoView                           | 2.0.0       | 2.3.0        | ✅     |
| JodaTime                            | 2.9.9       | 2.14.2       | ✅     |
| Glide                               | 3.7.0       | 5.0.5        | ✅     |
| Blurry                              | 2.1.1       | 4.0.1        | ✅     |
| Gson                                | 2.8.0       | 2.13.2       | ✅     |

---

## Execution Order — Final Status

```
Phase 0  Prerequisites (JDK 17, SDK 36, branch)                ✅
  │
Phase 1  Build system (Gradle, AGP, AndroidX, deps)            ✅
  │
Phase 2  AndroidManifest (exported, permissions, FGS type)     ✅
  │
Phase 3  PendingIntent + exact alarm semantics                 ✅
  │
Phase 4  Notification channels + POST_NOTIFICATIONS runtime    ✅
  │
Phase 5  Foreground service + JobScheduler quota cleanup       ✅
  │
Phase 6  Storage access (scoped storage, SAF, FileProvider)    ✅
  │
Phase 7  Dynamic BroadcastReceiver (RECEIVER_NOT_EXPORTED)     ✅
  │
Phase 8  Biometric (FingerprintManager → BiometricPrompt)      ✅
  │
Phase 9  Activity transitions (overrideActivityTransition)     ✅
  │
Phase 10 Edge-to-edge (window insets)                          ⚠️
  │
Phase 11 Predictive back gesture                               ✅
  │
Phase 12 Adaptive layout (large screen orientation)            ❌ Deferred
  │
Phase 13 Deprecated API cleanup                                ✅
  │
Phase 14 Testing & validation                                  ⚠️ In Progress
```

---

## References Checked

- Android 16 SDK setup: https://developer.android.com/about/versions/16/setup-sdk
- Android Gradle Plugin current/stable releases: https://developer.android.com/reference/tools/gradle-api
- AGP 9.2.x compatibility: https://developer.android.com/build/releases/agp-9-2-0-release-notes
- Android 16 behavior changes: https://developer.android.com/about/versions/16/behavior-changes-all
- Android 16 feature/change summary: https://developer.android.com/about/versions/16/summary
- Exact alarms: https://developer.android.com/develop/background-work/services/alarms/schedule
- Foreground service types: https://developer.android.com/develop/background-work/services/fgs/service-types
- Partial photo/video access: https://developer.android.com/about/versions/14/changes/partial-photo-video-access
- Package visibility: https://developer.android.com/training/package-visibility
- FileProvider: https://developer.android.com/reference/androidx/core/content/FileProvider
- AndroidX release table: https://developer.android.com/jetpack/androidx/versions
- AndroidX Core release notes: https://developer.android.com/jetpack/androidx/releases/core
- AndroidX Activity release notes: https://developer.android.com/jetpack/androidx/releases/activity
- AndroidX RecyclerView release notes: https://developer.android.com/jetpack/androidx/releases/recyclerview
