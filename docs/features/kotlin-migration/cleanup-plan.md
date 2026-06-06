# Kotlin Cleanup Plan (post-migration)

Status: **in progress** — started 2026-05-21, branch `kotlin`.

After the 17-group Java→Kotlin migration (see
[plan.md](plan.md)) every source under
`app/src/main/java/com/ywwynm/everythingdone/` is `.kt` (158 files, 0
`.java`). The migration was deliberately mechanical and defensive
(rule N1: `T?` on every reference, `!!` on every deref; C-class idioms
deferred; D-class rejected). This phase removes the resulting IDE
inspection noise **without changing behaviour**.

## 1. Goal

Reduce the warnings + code suggestions Android Studio shows when opening
a `.kt` file, while keeping program logic and runtime behaviour
identical. No new bugs, no behavioural divergence.

## 2. Scope

- **In**: the 158 `.kt` files under
  `app/src/main/java/com/ywwynm/everythingdone/`.
- **Out**: `Everything-Android/` (slated for deletion), generated /
  build output, `.idea` config.

## 3. What we fix (target bar)

Fix **yellow warnings** + **safe grey suggestions**. A transform is
"safe" only if it is behaviour-neutral by construction:

- Redundant `!!` that AS proves redundant (compiler already knows
  non-null) — a no-op to remove.
- Purely-local `var x: T? = ...` narrowing — only when the var lives in
  one function and is provably never null (initializer + all
  assignments non-null). Removes its use-site `!!`.
- Lift `return` out of `if`/`when`; `if`/`when` as expression — only
  where AS flags it and no side-effect ordering changes.
- String templates (`"a" + x` → `"a$x"`) — only where no nullable
  `toString()` semantics change.
- `when` replacing an `if` chain — only when AS flags and branches are
  side-effect-order-preserving.
- Unused imports / unused symbols / redundant qualifier names /
  redundant explicit types / redundant `;`.

Final in/out call is made **per inspection category** against the
ground-truth inventory (§5), not in the abstract.

## 4. What we do NOT touch (boundaries)

- **Declared nullability** of fields, params, return types, or any
  cross-function value. The N1 audit trail and the crash surface stay
  intact (see [followups.md](../../memory/followups.md) — the
  `ShiningBorder.setOnProgressUpdateListener(null)` production crash
  came from getting this wrong).
- **C-class deferred / D-class rejected idioms** that change behaviour:
  `data class`, `sealed class`, scope functions (`let`/`run`/`apply`/
  `also`/`with`), coroutines, and any property-syntax change on a
  side-effect setter.
- **Existing `@file:Suppress(...)`** — deprecation-API swaps are a
  behaviour change, out of scope.
- **Java-interop ceremony** — `@JvmStatic`, `@JvmField`, explicit
  `getX()`/`setX()`, `companion object` statics. Not AS warnings;
  removing them is higher-risk (Parcelable `CREATOR` needs `@JvmField`;
  reflection; widget `RemoteViews`; manifest refs). Deferred follow-up.
- **Header date stamps** and translation comments.

## 5. Sourcing the work list (ground truth)

IDE inspections are NOT surfaced by `gradlew assembleDebug` (the
migration drove compiler warnings to 0 via suppressions).

**Headless `inspect.bat` does NOT work on this setup** (tried
2026-05-21): Android Studio Panda 4 / AGP 9 headless JPS fails with
`AndroidSdkAdditionalData mismatch for SDK: Android API 30 Platform`
(app module source roots never load → empty scope) and
`Descriptions are missed for tools: ComposePreview…` (result write
throws before flush). Command-line inspect uses the old JPS model, not
the Gradle-synced one, and is unreliable for Gradle Android projects.

**`gradlew lint` is the wrong tool** — its findings are Android
correctness checks (`MissingPermission`, deprecation), mostly
pre-existing from the Java, not Kotlin-idiom noise.

**Actual source: AS GUI export.** Run `Analyze → Inspect Code` in the
IDE (uses the live synced model), profile `Project Default`, then
Export results to XML. Parse that into the categorized inventory. The
`Project_Default.xml` profile already disables `TrivialIf`,
`SimplifiableIfStatement`, `JavaDoc`, etc. — so it matches what the
user sees.

The inventory is then triaged: each inspection type → in-scope or
out-of-scope per §3/§4.

## 6. Execution

- **One commit per module** (mirror the 17 migration groups, package
  order). Each commit applies all in-scope Tier-1 + vetted Tier-2 fixes
  to that module's `.kt` files. (~17 commits.)
- **+1 isolated commit** for `RedundantNullableReturnType` last — it
  narrows return types and ripples to callers' `?`/`!!`, so keep it
  separate.
- Each commit: `:app:assembleDebug` succeeds with **0 new warnings**.
- One consolidated V3/V4 (install APK on emulator-5554, smoke-test key
  scenes, logcat scan) at the end — not per-commit.
- Commit only after the user's explicit go-ahead (see
  [preferences.md](../../memory/preferences.md)).

## 7. Inventory (ground truth, AS GUI export 2026-05-21)

2837 problems on `app/.../everythingdone/**.kt`. Triaged against §3/§4:

**Tier 1 — IN, safe mechanical (~970)**: UsePropertyAccessSyntax 615,
RedundantExplicitType 270, ReplaceGetOrSet 170, ReplaceCallWithBinaryOperator 99,
LiftReturnOrAssignment 88, ConvertToStringTemplate 88, FoldInitializerAndIfToElvis 42,
RemoveExplicitTypeArguments 35, KotlinUnusedImport 25, RemoveRedundantQualifierName 24,
IfThenToSafeAccess 22, RedundantSamConstructor 19, ReplacePutWithAssignment 16,
RedundantVisibilityModifier 15, ConvertTwoComparisonsToRangeCheck 11,
JoinDeclarationAndAssignment 9, IfThenToElvis 8, K2TypeParameterFindViewById 8,
RemoveRedundantCallsOfConversionMethods 7, VerboseNullabilityAndEmptiness 7,
MayBeConstant 4, ReplaceSizeZeroCheckWithIsEmpty 3 + ReplaceSizeCheckWithIsNotEmpty 1,
EnumValuesSoftDeprecate 3, RedundantInnerClassModifier 2, CanBeVal 2,
RedundantIf 1, RedundantSuppression 1.

**Tier 2 — IN, per-item verify (~127)**: CascadeIf 52 (no fallthrough),
ObjectLiteralToLambda 36 (re-check §3.10 hot-path + B-class guard),
ReplaceJavaStaticMethodWithKotlinAnalog 34 (each analog identical?),
CanBePrimaryConstructorProperty 3, KotlinRedundantOverride 2.

**Tier 3 — IN, isolated commit (77)**: RedundantNullableReturnType.

**Tier 4 — OUT, intentional/cosmetic**: BooleanLiteralArgument 82,
LocalVariableName 34, FunctionName 6, PrivatePropertyName 5 (m-prefix +
helper names deliberate), SameParameterValue 5, EmptyMethod 6,
SameReturnValue 3, CanConvertToMultiDollarString 3.

**Tier 5 — OUT, spelling/grammar**: SpellChecking 189, GrazieStyle 67,
GrazieInspection 43.

**Tier 6 — OUT, investigate & report (possible bugs/dead code)**:
KotlinConstantConditions 6, EmptyRange 4, KotlinUnreachableCode 7,
UnusedSymbol 92 + unused 14 + "unused declaration" 102 (many false
positives — framework-instantiated classes).

**Tier 7 — OUT, Android Lint (separate concern)**: UseKtx 111,
WrongConstant 93, NotifyDataSetChanged 32, Range 29,
ClickableViewAccessibility 17, StaticFieldLeak 13, MissingPermission 7, etc.

Raw export: `memory/inspections-gui/*.xml` (one file per inspection).
