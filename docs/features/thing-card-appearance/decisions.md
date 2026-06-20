# Thing Card Appearance Decisions

## 2026-06-19 - Appearance panel colour editing is draft-only until confirm

The Thing Card Appearance panel and Thing Folder Card Appearance panel should
offer a colour button in the title row. The button icon uses the current Thing
or Folder background as an opaque tint or drawable treatment so it does not
look greyed out.

Tapping the button opens the same ColorPicker content used by Detail's
change-colour popup. On each show, the popup compares available space above
and below the colour button. If the upper space is larger, the popup's
bottom-right corner is pinned to the button's bottom-right corner and the
surface grows from lower-right toward upper-left. Otherwise, the popup's
top-right corner is pinned to the button's top-right corner and the surface
grows from upper-right toward lower-left. The popup always includes world
colour sampling. When the current background is a gradient, it also includes
the gradient-direction control.

Colour changes made from the panel are preview state only. They must update the
selected Thing or Folder Card, the panel controls, and all related foreground
or indicator colours live, but persistent storage is updated only when the user
confirms the panel. Cancelling the panel restores the previous saved background.

Changing a Folder Card from normal size to large thumbnail size is also live
preview state. The selected Folder Card should immediately switch to the large
thumbnail projection and show descendant preview content before the user
confirms.
