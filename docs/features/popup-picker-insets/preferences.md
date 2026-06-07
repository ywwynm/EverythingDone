# Popup Picker And Insets Preferences

## Search ColorPicker Spacing

The home search ColorPicker is `Def.PickerType.HUE_BUCKET`, not
`Def.PickerType.COLOR_HAVE_ALL`. For this search picker, keep the gap between
the "all colours" row and the first bucket FAB row tighter than the original
16dp but not fully collapsed: the first bucket row uses an 8dp top margin. Keep
the bottom breathing room softer than the previous enlarged version: the final
bucket row uses a 12dp bottom margin after user-side visual tuning. The search
picker's fixed RecyclerView height remains 256dp.
