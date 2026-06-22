# 主题强调色迁移偏好

## 2026-06-21 - 可渐变控件不得退回代表色

用户明确要求：只要控件能通过 shader、自绘 Drawable、bitmap mask、RemoteViews bitmap、包装 Drawable 等方式呈现渐变，就必须使用 `accent -> accent2` 或对应 Folder 背景渐变，不使用 `representativeColor()` 作为最终视觉 fallback。

`representativeColor()` 只允许保留在 Android 系统 API 明确只能接受单个 `int` 的边界，例如光标/文本选择手柄、EdgeEffect、状态栏颜色、前景对比判断、持久化/搜索/颜色命名等非最终渐变绘制路径。

## 2026-06-22 - 渐变文本与 compound drawable 的默认策略

当 TextView 同时有文字和 compound drawable 时，需要同时支持两种渐变策略：

- `SEPARATE`：compound drawable 和文字分别按自己的宽高与起止位置应用渐变。
- `COMBINED`：compound drawable 与文字作为一个整体区域共享同一段渐变。

默认使用 `SEPARATE`，因为图标和文字各自吃满渐变时最稳定；只有明确需要整体连续渐变的视觉时才使用 `COMBINED`。
