# Home Contextual Toolbar Decisions

## 2026-06-06 - Contextual toolbar switches system status-bar chrome

The ThingsActivity contextual toolbar is an overlay separate from the normal
home actionbar container, but its height should remain the standard
`?attr/actionBarSize`. The perceived mismatch in selecting mode is caused by
the system status bar staying on the normal home chrome while the contextual
toolbar turns app-accent yellow, and by the contextual overlay starting at the
window top instead of below the system statusbar. Do not compensate by adding
status-bar height or padding to the `Toolbar` child itself; those approaches
distort toolbar geometry. Android 15+ disables `Window#setStatusBarColor` for
apps targeting API 35+, so `ThingsActivity` must paint the existing
`view_status_bar` placeholder with the desired colour behind
`WindowInsets.Type.statusBars()`. While selecting, that placeholder should use
`app_accent`; when selecting ends, it should restore the normal home
`bg_activity_things`. The whole contextual-toolbar overlay should be placed
below the same statusbar inset with a root top margin, while the `Toolbar`
itself keeps a standard actionbar-height. Status-bar icons should use the
dark-icon appearance on the yellow accent surface.

All contextual-toolbar resource qualifiers must follow the same ownership
model. In particular, `layout-v19/include_contextual_toolbar_things.xml` must
not keep its legacy internal `view_status_bar` spacer, because the outer
`activity_things.xml` statusbar placeholder now owns that region. Keeping both
adds an extra statusbar-height strip below the real system statusbar and makes
the selecting toolbar visibly taller than the normal home actionbar.

The `activity_things.xml` `view_status_bar` is a `DrawerLayout` content child,
so it must not be recoloured for transient contextual state. `DrawerLayout`
measures content children against the full content viewport, which makes that
view behave like a page background even if its layout height is later set to
the statusbar height. Contextual statusbar colour should instead be drawn by
`view_contextual_status_bar` inside `rl_contextual_toolbar`; that strip must
share the same animation root as the contextual toolbar, so the statusbar area
and toolbar move together. The contextual toolbar root itself must stay
transparent, with `app_accent` applied only to the statusbar strip and toolbar
children, otherwise the transparent part of `actionbar_shadow` reveals a
yellow parent background and makes the shadow look yellow. The contextual
statusbar strip's child visibility must remain stable; mode changes should
show or hide only the `rl_contextual_toolbar` wrapper so enter and exit
animations keep the statusbar strip and toolbar visually locked together.
