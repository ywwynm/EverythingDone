# App Chrome Polish Preferences

Migrated from global `memory/preferences.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## Button-like control ripple shape

When ImageView, TextView, FrameLayout, LinearLayout, RelativeLayout, or similar
plain views are used as button-like controls, their press/ripple feedback
should match the visual control shape instead of staying square. Text or
icon+text controls should use a pill-shaped rounded rectangle whose radius is
half of the control height. Icon-only controls should use a circular ripple.
Changing the ripple shape must not shift the visual position of existing text
or icons, and icon-only controls should keep the icon's visual size unchanged.
Ripple colours must adapt to Appearance Mode and to Thing Background ownership
where the control sits directly on a Thing Background.

Full-row, full-card, and full-width dialog action-row surfaces are not included
in this preference; they should not be reshaped as part of button-like control
ripple work.

Compact dialog text buttons, including affirmative "Got it" style buttons and
cancel/confirm pairs, are included in button-like control ripple work.

For gradient Thing Backgrounds, the ripple waveform can remain representative
single-colour feedback; do not introduce a custom gradient touch animation for
this button-like control pass.

Treat shaped ripple drawables as dynamic UI state. Reinstall or retint them
when a Thing Background changes and when an Activity handles light/dark
Appearance Mode changes in place.
