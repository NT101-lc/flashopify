---
description: Classify the task (SIMPLE/CORE), run the matching pipeline (gate, tests, review), then commit using Conventional Commits. Add --dry-run to walk the flow without editing code or committing.
---

Ship the current task. Arguments: `$ARGUMENTS`
(task description and/or `--dry-run`; with no description, infer the task from `git diff` and `git status`).

You (the main session) dispatch every agent; agents do not call each other.

## 0. Classify

Use the `orchestrator` agent (Mode 1) on the task and the current diff. It returns `Task type: SIMPLE|CORE` and the plan. Not sure → CORE.

## SIMPLE branch

1. **Implement** — if the task is not implemented yet, use one `coder` agent end to end.
2. **Gate** — run `bash scripts/gate.sh`. Fail → send the output back to the `coder`, re-run; repeat until green or the coder reports it is stuck (then stop and report).
3. **Commit** — see *Commit* below.
4. Hand to the user for review. No separate tester, no Opus review.

## CORE branch (sequential)

a. **Plan** — the orchestrator plan must contain steps, files affected, acceptance criteria and the `TC-n` list, including the four concurrency cases (N >> M no oversell, duplicate user request, consumer crash midway, Redis↔DB drift). Missing → send back to the orchestrator.
b. **Implement** — `coder` agent implements the plan.
c. **Gate** — `bash scripts/gate.sh` (compile, test, spotless/checkstyle). Must pass before going on. Fail → output back to `coder`, re-run gate. Never start LLM review on a red gate.
d. **Test** — `tester` agent implements exactly the plan's `TC-n` list (none dropped) and runs it. Compare its table with the plan; any missing TC → send back. Failures → back to `coder`, then repeat c–d.
e. **Review** — in one message, launch `reviewer-concurrency`, `reviewer-security` and `reviewer-performance` in parallel on `git diff` + the plan. Each finding is BLOCKER, MAJOR or MINOR.
f. **Merge** — `orchestrator` (Mode 2) merges the findings:
   - BLOCKER → do not commit.
   - MAJOR → fix list to `coder`.
   - MINOR → report only.
g. **Rounds** — fix → (c, d) → e → f, at most **2 review rounds** in total. After round 2, BLOCKER still open → **STOP**, do not commit, report with a summary of the problems. Do not loop further. MAJOR still open after round 2 → also stop and report.
   APPROVE (no BLOCKER/MAJOR) → *Commit*.

Parallel work inside a CORE task follows `CLAUDE.md` → *Parallel work* (contract first, one agent per `order`/`inventory`, one worktree per parallel agent).

## Commit

Stage the relevant files (never `.env` or secrets) and commit with a Conventional Commits message:
`<type>(<scope>): <summary>` where type ∈ feat, fix, refactor, test, docs, chore, perf, build, ci and scope is the module/feature.
Show the commit hash and summary. Do not push.

## --dry-run

Walk the same branch without side effects:
- Run step 0 for real (orchestrator classification + plan).
- Do **not** launch `coder`, `tester` or reviewers, do not edit files, do not commit. For each of those steps print what would be sent to which agent (model, input, expected output).
- Run the gate for real (`bash scripts/gate.sh`) and show the result; a red gate shows where the real run would stop.
- Validate the plan: task type present, acceptance criteria present, `TC-n` list covers the four concurrency cases for CORE.

## Report (always, also on stop and dry-run)

```
/ship report
Task type     : SIMPLE | CORE  (reason)
Gate          : PASS | FAIL (step that failed)
Tests         : <passed>/<total> TC  (CORE only)
Review rounds : <n>/2
Findings left : BLOCKER=<n> MAJOR=<n> MINOR=<n>
Result        : COMMITTED <hash> | STOPPED | DRY-RUN
Your action   : <what the user must do next>
```
List remaining findings by level under the report (MINOR ones included).
