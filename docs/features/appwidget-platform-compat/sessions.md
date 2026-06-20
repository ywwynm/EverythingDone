# AppWidget Platform Compatibility Sessions

## 2026-06-20 - Align AppWidget doing cover icon with Thing Cards

- Updated `app_widget_item_thing.xml` and `app_widget_thing.xml` so Things
  AppWidget doing covers use `vec_ic_doing_thing`, matching the home-list Thing
  Card doing/right-swipe cover.
- Tightened the doing-cover icon-to-label compound drawable gap from 12dp to
  8dp, then to 4dp in both Things AppWidget layouts, matching the home-list
  Thing Card gap.
- Did not add new resource ids or change AppWidget update bookkeeping. The
  change only swaps an existing compound drawable resource reference to the new
  vector.

Verification: source search confirmed no `@drawable/ic_doing_thing` layout
references remain under `app/src/main`. `git diff --check` passed with only the
repository's existing LF/CRLF warnings. `.\gradlew.bat :app:assembleDebug
--console=plain --no-configuration-cache` completed with `BUILD SUCCESSFUL`.
Published debug update `202606200558` and verified remote `latest.json` points
at
`http://120.25.194.207/everythingdone-updates/debug/apk/app-debug-202606200558.apk`.
Remote SHA-256:
`f1775e6462875b3bc17a40a6eaa8de155696b09ffd2b9ffcdf99c0f6c1de4936`.
