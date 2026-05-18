**使用专业、简洁的中文与我对话，涉及到中英文同时出现的场合（例如要提到某个代码文件、或者不太需要翻译的英文概念），仔细斟酌二者的连接和组织方式，不要出现比喻。不过，在你更新下述的文件时，使用英文。**

This is an Android application named "EverythingDone" or "完事儿". The project contains a directory "Everything-Android", which had been an upgraded version of EverythingDone written in kotlin. However, I decide to still use EverythingDone project for any update instead of Everything-Android. We can borrow some designs/codes/new-functionalities from Everything-Android, but that directory may be deleted after some time.

### Auto-Update Memory (MANDATORY)

**Update memory files AS YOU GO, not at the end.** When you learn something new, update immediately. If any following file does not exist, create it at the first time.

| Trigger | Action |
|---------|--------|
| User shares a fact about themselves | → Update `memory/profile.md` |
| User states a preference | → Update `memory/preferences.md` |
| A decision is made | → Update `memory/decisions.md` with date |
| Completing substantive work | → Add to `memory/sessions.md` |
| A non-trivial task is technically possible but deferred | → Add to `memory/followups.md` |

Project planning / review / analysis docs live under `docs/plans/`.

**Skip:** Quick factual questions, trivial tasks with no new info.

**DO NOT ASK. Just update the files when you learn something.**

## Agent skills

### Issue tracker

Issues live in the `ywwynm/EverythingDone` GitHub repo, managed via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default vocabulary: `needs-triage` / `needs-info` / `ready-for-agent` / `ready-for-human` / `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context repo — `CONTEXT.md` and `docs/adr/` at the repo root. See `docs/agents/domain.md`.