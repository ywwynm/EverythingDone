# Kotlin Migration Plan

Migration of the `app/` module from Java to Kotlin. Result of a
grilling session on 2026-05-20. This document is the single source of
truth for all decisions; commit messages on the `kotlin` branch should
reference it by section.

---

## 1. Goal

**A — Behavior snapshot.** The Kotlin version must be bit-for-bit
behaviorally identical to the current Java version. Translation is
purely a syntax-layer change; no modernisation, no refactoring, no
fixing of pre-existing bugs. Idiomatic Kotlin (data classes, scope
functions, coroutines, etc.) is a separate, later phase — not in
scope here.

Rejected alternatives: B (modernise during translation) and C (use
translation as Kotlin learning). Both conflate translation with
refactoring and make bugs indistinguishable from translation errors.

---

## 2. Branch strategy

- **Long-lived branch named `kotlin`** branched off `master`.
- **`master` is frozen** for the duration of the migration. No
  feature work, no hotfixes land on `master` while this runs. If a
  user-visible bug forces an exception, the migration pauses and
  resumes from a freshly-rebased `kotlin` branch.
- Commits on `kotlin` are **per-group** (see §4). One commit per
  group, message prefix `kotlin(<group>): …`.
- Final merge back to `master` is a single fast-forward when all 17
  groups are green.

Rejected alternative: per-file PRs onto a moving `master`. Cleaner in
theory, but the user opted for a single long-lived branch with master
frozen — which sidesteps the rebase / re-translate-the-delta cost.

---

## 3. Translation rules

These rules are mechanical. The translator (AI or human) applies them
without per-site judgement. V2 verification (§5) greps for compliance
on every file.

### 3.1 Nullability (rule **N1**)

| Java | Kotlin |
|---|---|
| primitive `int x`, `boolean b`, … | `var x: Int`, `var b: Boolean` — non-null |
| reference field `private String s;` | `private var s: String? = null` |
| `private final String s = "literal";` (init at decl) | `private val s: String = "literal"` — provably non-null |
| reference param `void foo(Bar b)` | `fun foo(b: Bar?)` |
| reference return `String foo()` | `fun foo(): String?` |
| `String[]` | `Array<String?>?` |
| `List<String>` | `List<String?>?` |
| `@NonNull T` (25 sites, 19 files) | `T` — non-null |
| deref `obj.field` / `obj.method()` | `obj!!.field` / `obj!!.method()` |
| chain `a.b.c` | `a!!.b!!.c` |
| `if (x != null) x.foo()` | `if (x != null) x.foo()` — smart cast permitted (compiler-enforced equivalence) |

**Audit trail**: every `!!` is intentional — a marker that the
original Java was implicitly nullable here. Later refactor phase
greps `!!` to find sites needing nullability review. Do **not**
substitute `lateinit var` for Android view fields, even though it
would be behaviourally close — it destroys the audit trail.

### 3.2 Equality (rule **E1**)

| Java | Kotlin |
|---|---|
| `a == b` (primitive) | `a == b` |
| `a == b` (reference) | `a === b` — **mandatory**, behaviour changes silently otherwise |
| `a == null` | `a == null` |
| `if (i == 5)` (int) | `if (i == 5)` |
| `intVal == longVal` | `intVal.toLong() == longVal` — Kotlin requires explicit numeric widening |
| `a.equals(b)` | `a.equals(b)` — keep method form for textual mapping |
| enum `e1 == e2` | `e1 === e2` — uniform mechanical rule |

**Explicit numeric conversion** applies to all mixed-type arithmetic
(`int + long`, `char + int`, `byte + int`, `short + int`). Kotlin
will not compile without `.toLong()` / `.code` / `.toInt()`.

### 3.3 Statics (rules **S-1..S-4**)

- **S-1** Java static method → `companion object { @JvmStatic fun … }`.
  `@JvmStatic` preserves the JVM signature so Java callers (during
  mixed-state inside the kotlin branch) and reflection both work.
- **S-2** Java `public static final int X = 5;` → `companion object { const val X = 5 }`.
  Non-primitive `static final` → `companion object { @JvmField val X = … }`.
  Non-final `static` → `companion object { @JvmField var X: T? = null }`.
- **S-3** Java class with **only** static members and a private
  constructor → Kotlin `object X { … }` (e.g.
  `FrequentSettings.java`). Pure utility singletons get this
  treatment; classes with any instance members do not.
- **S-4** `static { … }` → `companion object { init { … } }` (or
  `object { init { … } }` under S-3). JVM `<clinit>` timing is
  preserved.

### 3.4 Modernisation adopted (A-class)

These are zero-behaviour-difference forms with no loss of textual
mapping; adopt directly.

- Omit `: Unit` for void returns.
- `x != null ? x : y` → `x ?: y`. Only this exact form; other ternary
  becomes `if (b) … else …`.
- `instanceof` → `is`; cast → `as`.
- `for (int i = 0; i < n; i++)` → `for (i in 0 until n)`, **only when
  `i` is not reassigned inside the loop**. Otherwise keep
  `var i = 0; while (i < n) { …; i++ }`.

### 3.5 Modernisation adopted with guard (B-class)

**SAM lambda for Java single-abstract-method interfaces** — adopt by
default, with a mechanical guard.

```java
view.setOnClickListener(new View.OnClickListener() {
    @Override public void onClick(View v) { … }
});
```

```kotlin
view.setOnClickListener { v -> … }
```

**Guard — keep `object : Listener { … }` form (no SAM lambda) when
any of these patterns appear inside the anonymous body**:

1. `removeXxxListener(this)` / `removeCallback(this)` /
   `(target).addX(this)` — self-deregistration / self-registration.
2. Access to a field declared inside the anonymous block.
3. `OuterType.this.member` (outer reference via the listener's
   `this`).

Identified files needing `object :` form (from grep audit):
`ThingsActivity`, `DetailActivity`, `DisplayUtil`, `BackgroundUtil`,
plus the broader `App`, `ModeManager`, `ThingsAdapter`,
`NoticeableNotificationActivity`, `GradientOrientationDialogFragment`,
`SettingsActivity`, `ThingsListWidgetConfiguration` set (10 self-`this`
sites across 8 files). All other listeners go to SAM lambda.

### 3.6 Modernisation deferred (C-class) — DO NOT do during this phase

These break textual line-by-line mapping with the Java original,
which destroys the per-line verifiability of the translation:

- Property syntax (`obj.getFoo()` → `obj.foo`).
- String templates (`"hi " + name` → `"hi $name"`).
- `if` / `when` as expression returning to outer scope.

Move to later "Kotlin-ize" refactor phase.

### 3.7 Modernisation rejected (D-class) — DO NOT do, ever, in this migration

- Scope functions (`let` / `run` / `apply` / `also` / `with`).
- `data class` (auto-generated `equals/hashCode/toString` may diverge
  from hand-written Java versions — behaviour change).
- `sealed class`.
- Coroutines / Flow replacing `AsyncTask` / `Handler`.

### 3.8 Mandatory Kotlin requirements

These are not choices — Kotlin's syntax forces them.

- `open` keyword on every non-private, non-static, non-final class and
  member that could ever be subclassed/overridden. Mechanical rule:
  **add `open` to all such candidates** — over-permissive but matches
  Java's default semantics.
- `inner` keyword on every non-static nested class. `static class` →
  Kotlin `class` (nested, no outer ref). `class` (inner) → Kotlin
  `inner class`.
- `override` keyword on every overriding member.
- `throws` clause becomes `@Throws(Foo::class, Bar::class)` annotation
  on the function, preserving Java-side checked-exception signature.

### 3.9 Other mechanical mappings

| Java | Kotlin |
|---|---|
| `Foo.class` | `Foo::class.java` |
| `OuterType.this.x` | `this@OuterType.x` |
| `a & b`, `\|`, `^`, `<<`, `>>`, `>>>`, `~a` | `a and b`, `or`, `xor`, `shl`, `shr`, `ushr`, `a.inv()` |
| `new int[]{1,2,3}` | `intArrayOf(1, 2, 3)` |
| `new String[]{…}` | `arrayOf<String?>(…)` |
| Java raw `List` | `List<*>` |
| Java `List<? extends Foo>` | `List<out Foo>` |
| Java `List<? super Foo>` | `List<in Foo>` |
| package-private (default) field/method | `internal` (closest mechanical match; widens from package to module, but this is single-module so the widening is harmless) |
| `switch` with fall-through | `when` with **explicit body duplication** for each fall-through case (no implicit fall-through in Kotlin `when`) |
| Java `this(…)` constructor delegation | secondary constructor `: this(…)` |
| `s.charAt(i)` on `String` | `s[i]` — Kotlin's `String` is `kotlin.String`, not `java.lang.String`; `.charAt(i)` is unresolved. Use indexed access. Behaviour identical. |
| `s.split(regex)` (Java's regex-based split) | `s.split(regex.toRegex())` returning `List<String>`. **Important**: Kotlin's `String.split(String)` overload is **literal split, not regex** — using it silently changes behaviour. Always wrap the delimiter in `.toRegex()` when the Java original used `String.split`. If the call site needs `String[]`, follow with `.toTypedArray()` (gives `Array<String>` — invariance prevents declaring as `Array<String?>?`, so let the type infer at local-var sites). |
| `Integer.MAX_VALUE`, `Integer.MIN_VALUE`, `Long.MAX_VALUE`, etc. | `Int.MAX_VALUE`, `Int.MIN_VALUE`, `Long.MAX_VALUE`. Java wrapper class names map to Kotlin primitive companion-style constants; same JVM constant pool entry, identical behaviour. Same applies to `Float`, `Double`, `Short`, `Byte`. |
| `String.valueOf(x)` where `x` is `int`, `long`, etc. | `x.toString()`. Kotlin's `kotlin.String` companion does **not** re-export Java's static `valueOf(int)` — calling `String.valueOf(int)` from Kotlin source is unresolved. Use the primitive's `.toString()` extension. Same JVM output. |
| `@IntDef({A, B, C})` where A/B/C are constants in this class | `@IntDef(0, 1, 2)` using integer **literals**, not symbol references. When the constants live in the same class's `companion object` and the annotation class is **also a nested member**, Kotlin's parser cannot resolve the forward reference and emits a misleading "val keyword is missing in annotation parameter" error. Hard-coding the int values is the simplest workaround. **Lose**: rename refactors no longer flow through the IntDef. Acceptable for goal A (annotation is metadata-only). |
| Nested annotation class inside a non-trivial class body | Place at end of class body (after methods, before `companion object`). Placing it **before** other class members (e.g. before constructors) confuses Kotlin's parser and produces "Expecting member declaration" errors on the next member. |
| `override fun equals(o: Any?)` | `override fun equals(other: Any?)` — Kotlin's `Any.equals` parameter is named `other`. Using a different name (`o`, `obj`, `that`) emits a warning. Rename mechanically. |
| Java getter call from Kotlin source (`obj.getFoo()`) when `Foo`'s field has been translated to a Kotlin property | **Must** become `obj.foo` (property syntax). Kotlin auto-generates `getFoo()` for Java consumers but does **not** expose it as a callable method from Kotlin source. This is a **hard requirement**, not the C-class deferred preference — see [§7.3 POJO accessor strategy](#73-pojo-accessor-strategy-clarified-before-group-2) for which fields qualify. Java callers of the same field continue to use `getFoo()` unchanged. |
| Kotlin property whose setter has side effects (other field assignment) | Cannot use Kotlin auto-property because the auto-generated setter would conflict with the side-effect setter; trying to define both yields "Platform declaration clash: same JVM signature `getFoo()`". **Workaround**: use a private backing field `_foo` + explicit `fun getFoo()` and `fun setFoo(value: Foo)` methods. Java callers see same signature as before. Kotlin callers must call `obj.getFoo()` / `obj.setFoo(v)` (method form, not property) — but this is unique to the side-effect fields, the bulk of POJOs stay on Option 3 property syntax. Example: `Thing.color` and `Thing.background` cross-sync each other; both moved to `_color` / `_background` + explicit accessors. |

### 3.10.5 Header date stamp (added 2026-05-20 mid-migration)

When translating a `.java` file with a top-of-file Javadoc
`Created by … on YYYY/M/D.` line, insert
`Translated to Kotlin by ywwynm and Claude Opus 4.7 on YYYY/M/D.`
immediately after it. Match the original `Created by` date format
(slash separators, no zero-padding, e.g. `2026/5/20` not
`2026-05-20`). Files without a creation comment (e.g.
`ThingBackground.java`, born after the convention died) get no
stamp. Group 1+2 were backfilled retroactively when this rule was
added.

### 3.11 Deprecated-API surfacing (added 2026-05-20 in Group 3)

Many Android-framework APIs the project intentionally calls
were deprecated upstream after the original Java was written
(`Display.getDefaultDisplay` / `getRealSize` / `getSize`,
`Drawable.setColorFilter(int, Mode)`, `Notification.PRIORITY_*`,
`Locale(String, String)`, `Resources.updateConfiguration`,
`InputMethodManager.SHOW_FORCED`,
`NotificationCompat.WearableExtender.setBackground`, etc.).
javac emits a single `-Xlint:deprecation` summary; the Kotlin
compiler emits one warning per call site, which trips V1's
"0 warnings" bar.

**Rule**: when a file makes intentional use of a deprecated Java
API (i.e. the original `.java` used the same API without
replacement), add `@file:Suppress("DEPRECATION")` at the top of
the `.kt` file. Do **not** swap the API for its replacement —
that's a behaviour change and out of scope for the migration
phase. The post-migration refactor phase can revisit those call
sites.

For Kotlin-specific "overrides a deprecated member" diagnostics
on a single override (e.g. `Drawable.getOpacity`), put
`@Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")` directly on
the override declaration; file-level suppression doesn't cover
override warnings on its own.

### 3.10 Performance hot-path constraints

Inside `onDraw` / `onLayout` / `onMeasure` of `View` subclasses **and
their direct callees**:

- **No SAM lambda** — each invocation allocates a closure.
- **No `?.let { … }`** — same reason.
- **No scope functions.**
- **No `by lazy`** in field declarations on these paths — synchronised
  lazy adds locking overhead per access.
- Numeric values stay primitive (`Int` not `Int?`, avoid boxing).

Files affected (preliminary): `PatternLockView`, `Snackbar`,
`RevealLayout`, `FloatingActionButton`, `VoiceVisualizer`,
`ShiningBorder`, `ActivityHeader`, `DrawerHeader`. Full list to be
re-confirmed per group.

---

## 4. Groups (translation units)

Order is bottom-up dependency. Each group is one commit.

| # | Group | Files / scope | LoC |
|---:|---|---|---:|
| 0 | **Setup** — smoke-test AGP 9 built-in Kotlin via empty `Dummy.kt`. **No `build.gradle` changes**: AGP 9.2.1 has a native Kotlin compiler (`built_in_kotlinc`) and any `org.jetbrains.kotlin.android` plugin attempt fails with `Cannot add extension with name 'kotlin'`. Glide's `annotationProcessor` keeps working for Java-side `@GlideModule`. kapt / KSP only become a concern if a `@GlideModule` class is translated — addressed before the relevant group, not in §0. | `app/src/main/java/.../Dummy.kt` only | 1 |
| 1 | Heat-up: `Def.java` + `permission/` + `collections/` | 3 + 3 + 1 files | ~491 |
| 2 | `model/` | 11 files | 2128 |
| 3 | `utils/` | 14 files | 4119 |
| 4 | `database/` | 6 files | 1689 |
| 5 | `App.java` + `FrequentSettings.java` | 2 files | 607 |
| 6 | `helpers/` | 18 files | 3633 |
| 7 | `managers/` | 2 files | 1078 |
| 8 | `appwidgets/` | 17 files | 2001 |
| 9 | `receivers/` | 13 files | 904 |
| 10 | `services/` | 3 files | 672 |
| 11 | `views/` | 19 files | 4298 |
| 12 | `adapters/` | 16 files | 3305 |
| 13 | `fragments/` | 18 files | 4013 |
| 14 | small `activities/` (excl. monsters) | 11 files | ~1864 |
| 15 | `DetailActivity.java` | 1 file | 3465 |
| 16 | `ThingsActivity.java` | 1 file | 2583 |
| 17 | `SettingsActivity.java` | 1 file | 1554 |

Total: 158 files, ~39,404 LoC.

**Heat-up note**: Group 1 is small on purpose. Every translation rule
in §3 must be exercised at least once on real code; if any rule is
ambiguous or wrong, we revise the rule **before** Group 2.

---

## 5. Verification protocol (per group)

Four levels. Required level per group is in the table below.

### V1 — Compile

- `./gradlew.bat :app:assembleDebug` exits 0.
- 0 Kotlin compiler warnings.
- 0 IDE inspection / lint red flags on the changed files.

### V2 — Static rule check (grep audit on changed files)

For each `.kt` file in the group, the translator runs and reports:

- All reference-type fields declared `T?` (N1).
- All reference-type `==` rewritten to `===` (E1).
- All `static` translated with `@JvmStatic` / `const val` / `@JvmField` per S-1/S-2.
- All `static {}` → `init {}` in companion / object (S-4).
- All non-final Java classes / methods have `open` (mandatory).
- All non-static nested classes have `inner` (mandatory).
- Performance hot-path rule respected in any `View` subclass (§3.10).

V2 is mechanical and runs without an emulator. Output is a per-file
table in the group's commit message.

### V3 — Install + visual smoke (emulator)

1. `adb -s emulator-5554 install -r app-debug.apk`
2. Cold-start app via `am start`.
3. Run the scripted scenario set (§6) for this group, capturing PNGs
   to `memory/screenshots/group<N>/`.
4. Diff against `memory/screenshots/baseline/` PNG-by-PNG. Differences
   classified:
   - **Ignorable** — status-bar clock, battery, signal, habit cards'
     "next reminder time" (depends on `now()`).
   - **Suspicious** — colour drift, text-position drift, list-order
     change. Block the group and investigate.
   - **Fatal** — white screen, crash, wrong activity launched. Revert
     and re-translate.

Screenshots and diff classifications are not committed (the directory
is `.gitignore`d), but the V3 verdict is recorded in the commit
message.

### V4 — Behaviour deep-compare

When required (see table), capture additional channels:

- `adb logcat -d --pid=$(adb shell pidof com.ywwynm.everythingdone)`
  with grep on `EverythingDone` tags, before and after, semantic-diffed
  (ignore timestamps, object hashes, Kotlin class-name suffixes).
- For data-layer groups: `adb shell run-as com.ywwynm.everythingdone
  sqlite3 …` dump the relevant SQLite tables before and after, diff.

### Required levels by group

| Group | V1 | V2 | V3 | V4 |
|---|:---:|:---:|:---:|:---:|
| 0 | ✓ | – | – | – |
| 1 (heat-up) | ✓ | ✓ | ✓ | ✓ — calibrates the rule set |
| 2 (model) | ✓ | ✓ | ✓ | sampled |
| 3 (utils) | ✓ | ✓ | ✓ | sampled |
| 4 (database) | ✓ | ✓ | ✓ | **SQLite dump diff required** |
| 5 (App + FrequentSettings) | ✓ | ✓ | ✓ | **logcat diff on cold-start required** |
| 6 (helpers) | ✓ | ✓ | ✓ | sampled |
| 7–10 | ✓ | ✓ | ✓ | sampled |
| 11 (views) | ✓ | ✓ | ✓ | sampled, +visual focus |
| 12–13 | ✓ | ✓ | ✓ | sampled |
| 14 (small activities) | ✓ | ✓ | ✓ | required (user-facing) |
| 15 (DetailActivity) | ✓ | ✓ | ✓ | **full V4 required** |
| 16 (ThingsActivity) | ✓ | ✓ | ✓ | **full V4 required** |
| 17 (SettingsActivity) | ✓ | ✓ | ✓ | **full V4 required** |

---

## 6. Baseline scenarios (Phase 0)

Captured once before Group 0, used as golden for all subsequent V3
diffs.

1. Cold-start to `ThingsActivity` (Underway tab).
2. Tab switch — Notes, Finished, Habits, Goals (whichever exist).
3. Drawer open (hamburger menu).
4. `DetailActivity` opened from a text Thing.
5. `DetailActivity` opened from a habit Thing.
6. `DetailActivity` opened from a Thing with a reminder.
7. `SettingsActivity`.
8. `ColorPicker` popup from `DetailActivity`.
9. `DateTimePicker` popup from `DetailActivity`.
10. FAB → new-Thing creation flow (first frame after tap).

All PNGs land in `memory/screenshots/baseline/`. The directory is
`.gitignore`d.

---

## 7. Roles & cadence

| Step | Done by |
|---|---|
| Translate a group (apply §3 rules) | Claude |
| V1 compile + V2 grep audit | Claude |
| V3 install + screenshot + diff vs baseline | Claude |
| V4 logcat / SQLite diff (when required) | Claude |
| Final verdict per group ("ship or revert") | User |
| Final merge of `kotlin` → `master` | User |

A group ships when all required V-levels pass and the user OKs the
visual diff report.

---

## 7.3 POJO accessor strategy (clarified before Group 2)

The C-class deferral in §3.6 covers **call-site** rewrites
(`obj.getFoo()` → `obj.foo`). The **declaration-site** translation
of `private long id; public long getId() { return id; } public void
setId(long id) { this.id = id; }` is a separate question — and
mechanical translation has two plausible answers:

- **Option 1 (mechanical)**: explicit `private var id: Long = 0`
  + `fun getId(): Long = id` + `fun setId(id: Long) { this.id = id }`.
  Preserves textual mapping line-by-line. Verbose: ~3 LoC per field
  instead of 1.
- **Option 3 (Kotlin property)**: `var id: Long = 0`. The Kotlin
  compiler emits the same bytecode as Java's `private field +
  public getter + public setter`. Java callers `obj.getId()` /
  `obj.setId(x)` continue to compile unchanged.

**Rule**: pick by the Java field-naming convention of the package.

- If the field name **matches the JavaBean getter** (e.g. Java
  `private long id` + `getId()`) — use **Option 3**. The Kotlin
  property name is `id`, the auto-generated getter is `getId()`;
  identical JVM contract, ~70% LoC reduction. **model/ falls
  here** — all fields are unprefixed.
- If the field name uses an Android-style **`m` prefix** (e.g.
  `private TextView mTvTitle` + `getTvTitle()` or no getter at
  all) — use **Option 1**. Otherwise Kotlin would auto-generate
  `getMTvTitle()`, breaking Java callers. (Workaround with
  `@get:JvmName("getTvTitle")` exists but adds 2 lines per field,
  defeating Option 3's benefit.) **activities/, fragments/,
  views/, adapters/ fall here.**

Audit trail: Option 3 still carries N1 information at the **type**
level — `var s: String?` declares nullable, callers see same
`getS(): String?` in Kotlin or `String` platform type in Java. The
`!!` markers at deref sites are the same in both options. No
audit loss.

## 7.4 N1 relaxation for local variables (clarified in Group 1)

The N1 table in §3.1 covers **fields, parameters, and return
types** — declarations whose nullability is observable across method
boundaries and which carry audit-trail value. For **local
`val` / `var` declarations**, prefer type inference over forcing
`T?`. Rationale:

- Local nullability is determined by the right-hand-side expression,
  not by our declaration choice. Re-declaring it doesn't add audit
  information that the `!!` on the next deref doesn't already
  carry.
- Strict `T?` on locals forces awkward conversions where the
  RHS is provably non-null (e.g. `.toTypedArray()` returns
  `Array<String>` non-null; coercing to `Array<String?>?` is
  unsafe and verbose).
- Audit trail is preserved by `!!` markers at deref sites, not by
  the declaration type itself.

**Rule**: at local-var sites where the RHS is a method call,
constructor, or other typed expression, let Kotlin infer
(`val x = …`). At declarations whose type is *constraining* the
RHS (annotated explicitly to control elements, etc.), apply N1.

## 7.5 Open risks discovered during Group 0

- **kapt unavailable while AGP 9.2.1 is in use.** Attempting to apply
  `org.jetbrains.kotlin.android` (any version 2.1.21 / 2.2.0 tested)
  fails because AGP pre-registers a `kotlin` extension and the
  standalone Kotlin Gradle Plugin tries to register the same name. As
  long as AGP 9.2.1 owns Kotlin compilation, kapt cannot be applied.
  - Today this is fine — every `@GlideModule` class (the only kapt
    consumer in the project) is still Java and the legacy
    `annotationProcessor` works.
  - When a Glide module gets translated, options are:
    (a) defer that one file to the very last;
    (b) switch Glide annotation processing to **KSP** (Glide 4.16
        has a community KSP processor; Glide 5.x has first-class KSP);
    (c) downgrade AGP to a version that tolerates standalone Kotlin
        plugin and re-enable kapt.
  - Decide before the group that touches `GlideApp` /
    `@GlideModule`. Current grep finds 0 such Kotlin candidates, so
    revisit when Group 6 (`helpers/`) approaches — Glide use sites
    are concentrated there.

## 8. Out of scope (explicitly)

- `timelytextview/` and `swirl/` library modules — not translated in
  this phase.
- `Everything-Android/` directory — abandoned per CLAUDE.md; not
  referenced as a source of translations.
- Library upgrades, dependency changes, AGP / Kotlin version bumps
  beyond the minimum needed to enable Kotlin.
- ProGuard / R8 rules changes.
- Test addition (no tests exist; adding them is a separate effort).

---

## 9. Open items resolved during the grilling

These were discussed and decided; documenting so they're not
re-litigated.

- **Reflection on project classes**: only 4 files use reflection
  (`DetailActivity`, `EdgeEffectUtil`, `SystemNotificationUtil`,
  `LineSpacingHelper`) and all reflect on **Android framework** types
  (`TextView`, `ScrollView`, `RecyclerView`, `ViewPager`, `Editor`).
  No project-internal reflection exists, so `@JvmStatic` is needed
  only for the standard interop reason, not to preserve any
  reflection contract.
- **`@NonNull` / `@Nullable` annotation density**: 25 sites across
  19 files of 158 — sparse. N1 cannot be driven from existing
  annotations; mechanical "every reference → `T?`" rule applies
  universally, with annotations as a localised exception.
- **`catch (NullPointerException)` / `catch (RuntimeException)`**:
  zero occurrences. The N2 alternative was rejected for audit-trail
  reasons (not behavioural ones) — N2's subtle exception-class
  difference would not bite this codebase.
- **`static { … }` blocks**: 5 sites
  (`PatternLockView`, `AutoNotifyHelper`, `CheckListAdapter`,
  `BaseThingsAdapter`, `FrequentSettings`). Three depend on `App`
  being initialised; the `<clinit>` timing is preserved by S-4.
