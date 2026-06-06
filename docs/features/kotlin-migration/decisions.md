# Kotlin Migration Decisions

Migrated from global `memory/decisions.md` on 2026-06-06. This file keeps feature-scoped history out of startup memory while preserving the original notes.

## 2026-05-21 - Post-migration Kotlin cleanup: scope + risk boundaries (grilling session)
After the 17-group Java→Kotlin migration completed, a cleanup phase
targets the IDE inspection noise (warnings + suggestions) AS shows on
`.kt` files, while keeping behaviour identical. Decisions, captured via
a grilling session and written up in
[Kotlin cleanup plan](../docs/features/kotlin-migration/cleanup-plan.md):

- **Target bar**: fix yellow warnings + *safe* grey suggestions. Skip
  the migration plan's C-class / D-class items that risk behaviour
  (`data class`, scope functions, behaviour-changing property syntax).
- **Nullability boundary**: remove AS-flagged redundant `!!`, and narrow
  *purely-local* `var x: T? = ...` (used in one function, provably never
  null) to `T`. **Do NOT** touch declared nullability of fields /
  params / cross-function values — the N1 audit trail and the crash
  surface (see [followups.md](followups.md) ShiningBorder incident)
  stay intact.
- **Work-list source**: ground-truth from AS's own engine —
  `E:\software\Android Studio\bin\inspect.bat` against
  `.idea/inspectionProfiles/Project_Default.xml`, plus `gradlew lint`.
  NOT `assembleDebug` (migration drove compiler warnings to 0 via
  suppressions; IDE inspections are a different, larger set).
- **Verification**: each commit must `:app:assembleDebug` with 0 new
  warnings; one consolidated install + smoke-test + logcat (V3/V4) at
  the end, not per-commit (screenshot frugality).
- **Commit granularity**: one commit per **module** (mirror the 17
  migration groups → ~17 commits), each applying all in-scope fixes to
  that module's files. (Revised from per-category mid-session: too many
  commits. Trade-off accepted: harder to bisect a regression to a
  specific transform type, mitigated by behaviour-neutrality + final
  smoke test.) Plus **one isolated commit** for
  `RedundantNullableReturnType` (return-type narrowing — crosses the N1
  boundary and ripples to callers; done last).
- **RedundantNullableReturnType (77) opted IN** (revises the original
  "don't touch return types" boundary): AS proves the function never
  returns null, so narrowing `T?`→`T` is runtime-safe and is the only
  real lever to reduce nullability noise (AS flags **zero** redundant
  `!!` — the migration's `!!` are all genuinely needed given `T?`
  declarations).
- **Tier 6 — investigate & report, do NOT fix**:
  `KotlinConstantConditions` (e.g. "cast always fails",
  `Habit.kt` "total always zero"), `EmptyRange` (downTo? bugs),
  `KotlinUnreachableCode`, `UnusedSymbol` (many false positives —
  `App.kt` is manifest-instantiated). "Fixing" these would change
  behaviour; surface as findings, preserve behaviour per goal-A.
- **Out of scope (kept untouched)**: existing `@file:Suppress(...)`
  (deprecation-API swaps are behaviour changes); Java-interop ceremony
  (`@JvmStatic` / `@JvmField` / explicit `getX()`/`setX()`) — not AS
  warnings, higher risk (Parcelable CREATOR, reflection, widgets);
  header date stamps. Logged as follow-ups, not this pass.

## 2026-05-20 - Kotlin migration: branch `kotlin`, frozen master, behavior-snapshot semantics
After a grilling session, all strategy + rule decisions for the
Java→Kotlin migration of `app/` are captured in
`docs/features/kotlin-migration/plan.md`. Highlights:

- Goal **A** — behavior snapshot, not modernisation. Later refactor
  phase removes `!!`, `@JvmStatic`, etc.
- **Long-lived `kotlin` branch** off master. **Master frozen** for
  the migration's duration.
- **17 groups**, bottom-up dependency order, one commit per group.
- Translation rules: **N1** (every Java reference → `T?`, every
  deref → `!!`, audit trail preserved), **E1** (`===` for reference
  equality, explicit numeric widening), **S-1..S-4** (`@JvmStatic`,
  `const val`/`@JvmField`, `object` for pure-static singletons,
  `companion object { init }` for `static {}`), **A-class
  modernisations** adopted (Elvis, `is`/`as`, `for in`, void
  omission), **B-class** SAM lambdas with guard rule (10 sites
  identified that must keep `object : Listener` form), **C-class
  deferred**, **D-class rejected**.
- **V1+V2+V3** required on every group; **V4** required on groups 1,
  4, 5, 14, 15, 16, 17.
- **V3 closed-loop**: Claude installs APK on emulator-5554, takes
  PNG screenshots, diffs against `memory/screenshots/baseline/`.
  Approach validated on 2026-05-20 — baseline home screenshot
  captured successfully via the PowerShell-safe `screencap` →
  `pull` workflow (see [preferences.md](preferences.md)).

**Don't reintroduce** anything from the C/D-class modernisation
list during the migration phase — those are explicitly deferred or
rejected. They are revisit candidates for the post-migration
refactor only.

## 2026-05-20 - Group 0 surprise: AGP 9.2.1 ships a built-in Kotlin compiler
Trying to apply `org.jetbrains.kotlin.android` (tested 2.1.21 and
2.2.0, both classpath and `plugins {}` forms) fails on this
codebase with `Cannot add extension with name 'kotlin', as there is
an extension already registered with that name`. AGP 9 owns the
`kotlin` DSL extension and ships its own compiler
(`built_in_kotlinc`) that picks up `.kt` files dropped into the
java source set with **zero build.gradle changes**.

Verified by `:app:compileDebugKotlin` task running successfully on
just the `Dummy.kt` file, with `Dummy.class` ending up in the dex
output. Group 0 commit therefore contains a single new file — no
`build.gradle` edits — and the APK installs and cold-starts
identically to baseline `01_home_underway.png`.

**Don't try** `apply plugin: 'kotlin-android'` or
`id 'org.jetbrains.kotlin.android'` while AGP 9.x is in use — it
will fail. kapt and any plugin that requires the standalone Kotlin
Gradle Plugin are also unavailable. See
[Kotlin migration plan §7.5](../docs/features/kotlin-migration/plan.md)
for the kapt-replacement decision tree (KSP / defer / downgrade).

## 2026-05-20 - Kotlin migration Group 3 (utils/) — deprecation suppression as file-level

14 utility classes translated cleanly; the only mechanical
challenge was the wave of Java-API deprecation warnings the
Kotlin compiler emits where the original Java already used the
deprecated API (Display.getDefaultDisplay/getRealSize/getSize,
Drawable.setColorFilter(Int, Mode), Notification.PRIORITY_*,
Locale(String, String), Resources.updateConfiguration,
InputMethodManager.SHOW_FORCED, etc.). Group 2's model/ classes
had none of these because Cursor / Parcel are still un-deprecated.

Decision: `@file:Suppress("DEPRECATION")` at the top of any
`.kt` file whose Java original called a since-deprecated API.
This preserves V1's "0 warnings" bar without changing which
API is called (behaviour snapshot), and the post-migration
refactor pass can revisit those call sites with intent.

Documented as new rule in
[Kotlin migration plan §3.11](../docs/features/kotlin-migration/plan.md).

## 2026-05-21 - Cleanup execution: hybrid (IDE batch + agent judgment)
After Group 1 + Habit.kt by-hand (cost: ~1 edit per fix, ~1100 fixes
total = too slow + transcription risk on structural transforms),
switched to a hybrid: the **user batch-applies** the safe high-volume
Tier-1 idiom inspections via AS Inspection Results → "Apply fix to all"
(the IDE's own refactoring engine — behaviour-exact, zero transcription
risk). The **agent** does the judgment-required items (Tier-2:
ObjectLiteralToLambda hot-path/guard, ReplaceJavaStaticMethodWithKotlinAnalog,
CanBePrimaryConstructorProperty, KotlinRedundantOverride), Tier-3
(RedundantNullableReturnType isolated commit), Tier-6 investigation, and
**all compile-verify + per-module commits** (stage package-by-package
from the en-masse batch result to keep ~17 module commits reviewable).

## 2026-05-20 - Kotlin migration: header date stamp convention (added mid-migration)

Each `.java` → `.kt` translation now stamps
`Translated to Kotlin on YYYY-MM-DD.` immediately after the
existing `Created by … on YYYY/M/D.` Javadoc line. Files without
a `Created by` comment skip the stamp (e.g. ThingBackground was
born post-convention). Group 1+2 backfilled. Plan §3.10.5
captures the rule.
