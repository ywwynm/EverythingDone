# Android 16 Migration Sessions

Migrated from global `memory/sessions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-31 - Notification action color state review

Reviewed current notification action coloring after the user noticed that
reminder/habit notification actions appear to use one shared color.

Findings:
- System notifications are built through `SystemNotificationUtil`.
- Reminder, habit, ongoing thing, and doing notifications set a single
  notification-level accent with `NotificationCompat.Builder.setColor(...)`.
- Their actions are added with plain `builder.addAction(...)`; there is no
  per-action color, custom notification action layout, or `NotificationCompat.Action`
  styling in the current code.
- The color/background arguments in the reminder/habit action helper overloads
  are used for PendingIntent payloads and downstream dialogs, not for the visual
  style of the notification action buttons.
- The full-screen `NoticeableNotificationActivity` is separate from the system
  notification shade and tints its custom action icons with the neutral
  `app_chrome_control_unchecked` color.
