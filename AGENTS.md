**使用专业、简洁的中文与我对话，涉及到中英文同时出现的场合（例如要提到某个代码文件、或者不太需要翻译的英文概念），仔细斟酌二者的连接和组织方式，不要出现比喻。在你更新下述的文件时，同样使用中文（此前有的文件是英文，没关系，之后统一用中文即可）。**

This is an Android application named "EverythingDone" or "完事儿". The project contains a directory "Everything-Android", which had been an upgraded version of EverythingDone written in kotlin. However, I decide to still use EverythingDone project for any update instead of Everything-Android. We can borrow some designs/codes/new-functionalities from Everything-Android, but that directory may be deleted after some time.

**When a new session begins, read the lightweight memory index files and files
under `.agents/rules/` at first. When a task touches an existing feature, also
read the relevant `docs/features/<kebab-case-feature-slug>/` directory before
planning or editing.**

**当你被要求将debug版本的APP发布到阿里云的时候，如果当前正在做某项功能，那么就在docs/features/<kebab-case-feature-slug>/debug-updates/目录下新建一个命名格式形如update-20260519012916.md的文件，将发布日志用中文写入，并在调用相应的gradle发布任务时将该文件传入。如果没有这个文件夹，就新建它。如果当前并没有在做某个具体的功能，也就是说找不到对应的docs/features/<kebab-case-feature-slug>/目录，那么就在memory/debug-updates/目录下新建相应的md文件。发布日志文件在一般情况下都不需要进行读取，只有在用户要求、或者检查相关功能实现或迭代情况时读取，读取时可以先读取标题以检查是否是对当前任务有用的信息。**

**Git提交标题和信息需要同时使用中英文，英文在前。不要使用"English:"、"中文："这样的区分，直接用中文和英文即可。**

### Operational rules

Tool paths, ADB invocation patterns, Gradle invocation patterns, and
other "how to call the toolchain" knowledge live under `.agents/rules/`.
Read those files when you need to invoke a tool — not `memory/`, which
holds user preferences and session history, not operational rules.

Gradle wrapper invocations may require sandbox escalation in Codex sessions.
When an in-sandbox Gradle run is blocked, interrupted, or behaves as if the
sandbox is preventing normal execution, rerun it with elevated permissions and
the appropriate Gradle command prefix.

### Auto-Update Memory And Feature Docs (MANDATORY)

**Update memory or feature docs AS YOU GO, not at the end.** When you learn
something new, update immediately. Keep `memory/*.md` lightweight and
cross-feature; put feature-specific details under
`docs/features/<kebab-case-feature-slug>/`.

| Trigger | Action |
|---------|--------|
| User shares a fact about themselves | → Update `memory/profile.md` |
| User states a cross-feature preference | → Update `memory/preferences.md` |
| User states a feature-specific preference | → Update `docs/features/<slug>/preferences.md` |
| A cross-feature decision is made | → Update `memory/decisions.md` with date |
| A feature-specific decision is made | → Update `docs/features/<slug>/decisions.md` with date |
| Completing cross-feature or documentation-system work | → Add a concise entry to `memory/sessions.md` |
| Completing feature-specific substantive work | → Add to `docs/features/<slug>/sessions.md` |
| A cross-feature task is technically possible but deferred | → Add to `memory/followups.md` |
| A feature-specific task is technically possible but deferred | → Add to `docs/features/<slug>/followups.md` |

Feature-specific planning, review, analysis, execution, and debug-note archive
docs live under `docs/features/<kebab-case-feature-slug>/`. Create one
directory per new feature or substantial technical initiative. Do not add new
feature plans to `docs/plans/`.

**Skip:** Quick factual questions, trivial tasks with no new info.

**DO NOT ASK. Just update the files when you learn something.**

## Agent skills

### Issue tracker

Issues live in the `ywwynm/EverythingDone` GitHub repo, managed via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repo — `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.
