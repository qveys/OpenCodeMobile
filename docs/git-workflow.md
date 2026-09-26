# Git Workflow

## Core Principles

- **No direct commits to `main`:** All changes, including documentation and minor fixes, must be submitted via a Pull Request from a dedicated branch.
- **Merge Authority:** The project owner/maintainer is the sole approval authority. No agent merges alone: an agent may perform the mechanical merge only after the owner approves, using `scripts/merge-agent-pr.sh` (see `docs/MERGE-PATH.md`).
- **Verified CI:** All commits must pass continuous integration (lint, unit tests, architecture tests, build) before merge.

---

## Commit Conventions

Use Conventional Commits format for all commit messages:

```text
<type>: <short description>
```

### Types
- `feat`: New feature or user-facing capability
- `fix`: Bug fix
- `docs`: Documentation updates or additions
- `chore`: Maintenance, dependencies, scaffolding
- `refactor`: Code change that neither fixes a bug nor adds a feature
- `test`: Adding or correcting tests
- `perf`: Performance improvement

### Rules
- Lowercase description after the colon and space
- No trailing period
- Under 72 characters for the subject line
- Reference issue identifier in commit body or trailers (e.g. `Closes OPE-10` or `Ref: OPE-10`)

---

## Development Workflow

1. **Pull Latest:** Synchronize local `main` with origin (`git checkout main && git pull origin main`).
2. **Branch Creation:** Create a feature branch following the naming convention:
   ```bash
   git checkout -b <prefix>-<N>/<short-description>
   ```
   Example: `git checkout -b ope-10/branch-protection-setup`
3. **Implement & Test:** Make changes, run local linters and tests.
4. **Commit:** Commit with Conventional Commits message. Commits authored by agents must be created through GitHub API (`createCommitOnBranch`) or signed.
5. **Push:** Push branch to remote:
   ```bash
   git push -u origin <branch-name>
   ```
6. **Open PR:** Submit pull request on GitHub referencing the issue (`Closes OPE-XX`).
7. **Review & Approval:** Follow the graduated review workflow (see `docs/pr-conventions.md` and `docs/BRANCH-PROTECTION.md`).
8. **Merge:** The owner approves the PR on GitHub (`gh pr review <n> --approve`). Once `reviewDecision=APPROVED` and `mergeStateStatus=CLEAN`, an agent merges with `scripts/merge-agent-pr.sh <n>` (fails closed otherwise; see `docs/MERGE-PATH.md`).
