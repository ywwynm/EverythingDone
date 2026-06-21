# 主题强调色迁移偏好

## 2026-06-21 - 可渐变控件不得退回代表色

用户明确要求：只要控件能通过 shader、自绘 Drawable、bitmap mask、RemoteViews bitmap、包装 Drawable 等方式呈现渐变，就必须使用 `accent -> accent2` 或对应 Folder 背景渐变，不使用 `representativeColor()` 作为最终视觉 fallback。

`representativeColor()` 只允许保留在 Android 系统 API 明确只能接受单个 `int` 的边界，例如光标/文本选择手柄、EdgeEffect、状态栏颜色、前景对比判断、持久化/搜索/颜色命名等非最终渐变绘制路径。
