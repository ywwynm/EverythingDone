# System Bar Insets Followups

## 2026-06-07 - Broader top-gravity popup positioning review

`ColorPicker` and non-`AFTER_TIME` `DateTimePicker` still use
`DisplayUtil.getStatusbarHeight(...)` in top-gravity popup placement before the
first cleanup pass. The first pass may replace that specific legacy height read
with current runtime top inset so `getStatusbarHeight(...)` can be deleted.

Any broader popup positioning redesign remains deferred. Preserve the existing
window-relative positioning decisions from `popup-picker-insets`.
