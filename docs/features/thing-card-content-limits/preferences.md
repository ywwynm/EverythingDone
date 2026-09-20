# Thing Card Content Limits 偏好

## 2026-09-19 验收方式与设备

- 本轮真机授权：魅族 20 Infinity `392QBFED2226B`、三星 SM-S9180 `R5CW20BLNKL`，
  两台都已连上。
- **必须看实际界面，不能只看日志。** 行数/条数这类改动的判据是屏幕上真的显示了
  几行几项，日志里的数值对上不代表渲染对上。
- **不得改动用户自己的记事。** 需要反复验证的场景一律先建虚构测试记事，测完移入
  回收站。见 memory 的 `never-test-destructively-on-real-data`。
- 同样不得改动设备上的设置（包括应用内的深色模式、动画偏好等）。

设备注意事项见 `.claude/rules/adb.md`：三星 One UI 上长按 `TextView` 会触发系统
拖放（别用原地 `input swipe` 截按下态），横向手势起点要离屏幕左右边缘 140px 以上。
