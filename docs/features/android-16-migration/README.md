# Android 16 Migration

2026-10-15 补充：当前 APK 已包含原生库，OPPO PLZ110 的系统提示报告 16 KB 对齐问题；旧计划中“没有原生 .so”的判断已不适用。此项尚未修复，详见 [后续事项](followups.md)。

Status: implemented with some deferred adaptive-layout and release-policy
follow-ups.

## Documents

- `plan.md` - Android 16 / API 36 migration plan and final execution status.
- `review.md` - migration review report with verified fixes, residual risks,
  and command history.

## Related Global Records

- ADRs: none dedicated.
- Memory: see `memory/decisions.md`, `memory/sessions.md`, and
  `memory/followups.md` entries around 2026-05-07 through 2026-05-08.

## Notes

This directory owns Android platform migration planning and review material.
Operational Gradle or ADB invocation rules stay in `.agents/rules/`.
