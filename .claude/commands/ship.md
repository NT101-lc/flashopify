---
description: Run tests, review the diff, then commit using Conventional Commits.
---

Ship the current changes:

1. **Test** — use the `tester` agent to run backend and frontend tests for what changed. Stop and report if anything fails.
2. **Review** — use the `reviewer` agent on `git diff`. Stop and report if the verdict is CHANGES REQUESTED.
3. **Commit** — stage the relevant files (never `.env` or secrets) and commit with a Conventional Commits message:
   `<type>(<scope>): <summary>` where type ∈ feat, fix, refactor, test, docs, chore, perf, build, ci and scope is the module/feature.
4. Show the commit hash and summary. Do not push.

$ARGUMENTS
