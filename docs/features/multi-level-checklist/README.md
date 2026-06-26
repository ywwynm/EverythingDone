# 多级清单项 / Multi-level Checklist Items

给记事的清单加入最多三级的层级结构。核心能力：缩进/反缩进改变清单项层级、分级排版、
按“组”运作的完成/取消迁移、组内同级拖拽，以及在所有展示清单的界面（首页列表、widget
配置页、文件夹缩略图、DoingActivity、单一记事 widget、列表 widget、通知）上的多级显示。

## 关键概念（见根 `CONTEXT.md`）

- **Checklist Item Level**：逐项显式存储的层级（1/2/3），用户通过缩进/反缩进直接编辑。
- **Checklist Item Owner**：派生父项 = 上方最近的、层级严格更浅的项；可不存在（孤儿）。
- **Checklist Group Root**：没有 owner 的项（通常是一级项，孤儿深层项也算）。
- **Checklist Item Group**：一个组根 + 它逐层归属下的所有项；是完成迁移、拖拽的单位。

## 文档

- `plan.md` —— 目标、决策摘要、实现分阶段、待调参数
- `execution.md` —— 实现阶段的勾选清单（随进度填写）
- `decisions.md` —— 设计决策（grill 过程逐条敲定，权威记录）
- `followups.md` —— 技术可行但本次不做的对称增强
- 存储编码见根 `docs/adr/0010-checklist-item-level-encoding.md`

## 状态

设计追问（grill）已完成，主干设计树全部敲定；尚未进入实现。
