# App Chrome Polish Sessions

## 2026-06-21 - Resync ActivityHeader layout after Detail return

Fixed an ActivityHeader state drift after opening a Thing detail screen and
returning to the list. `ThingsActivity.onResume()` refreshes the home surface
and header text, and `ActivityHeader.updateText()` rebuilds title constraints.
When the RecyclerView was still scrolled, those rebuilt constraints could stay
at the expanded-header defaults until the user scrolled again.

`refreshActivitySurfaceAndHeader()` now schedules a pre-draw
`ActivityHeader.updateAll(...)` using the current RecyclerView first visible
position after the text refresh. This reapplies the correct max lines, max
width, scale, and translation before the next frame is drawn.

Verification: `E:\projects\EverythingDone\gradlew.bat :app:assembleDebug`
completed with `BUILD SUCCESSFUL`; `git diff --check` reported only the
repository's existing LF/CRLF warnings. Published debug update `202606210420`
and verified remote `latest.json` points at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606210420.apk`
with SHA-256 `e519e4ef8c9ba0a9109d56f7d5baa4f4710391c29d37bb6adbd7dc2ddde7acdc`.

## 2026-06-18 - Unified compact dialog title and action styling
- Added shared `app_chrome_dialog_*` dimension resources and an `app_chrome_dialog_cancel` color token for light/dark cancel actions.
- Updated compact `DialogFragment` action buttons, the Thing/Folder Card Appearance panel actions, media crop dialog actions, and widget configuration dialog-like confirm actions to use common title/action sizing.
- Routed runtime action-button styling through `BackgroundUtil.installAppChromeDialogActionButton(...)`; surfaces that intentionally use action overflow keep explicit no-clip XML so pill ripples are not clipped.

## 2026-06-18 - Corrected divided dialog content bounds after style unification
- Preserved the user's `app_chrome_dialog_action_button_margin_end=2dp` adjustment.
- Removed the extra visual bottom gap from the Thing/Folder Card Appearance panel by letting its bottom action row wrap the shared 36dp button height instead of adding a 48dp row around it.
- Centered compact dialog action text vertically in `BackgroundUtil.installAppChromeDialogActionButton(...)` while preserving each button's horizontal gravity.
- Stopped `BaseDialogFragment` from disabling clipping on every compact-button ancestor; explicit no-clip XML remains only on surfaces that need overflow for pill ripples.
- Tightened `DateTimeDialogFragment` ViewPager clipping and page layout params so adjacent pages do not peek into the current page.
- Added `app_chrome_dialog_divided_action_row_margin_top` for scroll/divider dialogs and adjusted Color Info / Chooser / License / Long Text / Debug Update boundaries so scroll content sits between the separators and the bottom action area.
- Aligned the folder-name `EditText` in the Thing/Folder Card Appearance panel with the confirm action text by reusing `app_chrome_dialog_action_button_margin_end` as its trailing margin.
