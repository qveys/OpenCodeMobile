# Git Workflow

## Core Principles

- **No direct commits to `main`:** All changes, including documentation and minor fixes, must be submitted via a Pull Request from a dedicated branch.
- **Merge Authority:** The project owner/maintainer is the sole approval authority. No agent merges alone: an agent may perform the mechanical merge only after the owner approves, using `scripts/merge-agent-pr.sh` (see `docs/MERGE-PATH.md`).
- **Verified CI:** All commits must pass continuous integration (lint, unit tests, architecture tests, build) before merge.

---

## Commit Conventions

Use Conventional Commits format for every commit message. The format is the
same as [my-housekeeper `commitlint.config.mjs`](https://github.com/qveys/my-housekeeper/blob/main/commitlint.config.mjs):

```text
<emoji> <type>(<scope>): <short description>
```

### Types
- `feat`: New feature or user-facing capability
- `fix`: Bug fix
- `docs`: Documentation updates or additions
- `style`: Code style changes that do not affect behavior (formatting, lint)
- `refactor`: Code change that neither fixes a bug nor adds a feature
- `perf`: Performance improvement
- `test`: Adding or correcting tests
- `build`: Build system or dependency changes
- `ci`: CI/CD configuration changes (workflows, runners, scripts/ci)
- `chore`: Maintenance, scaffolding, housekeeping

### Rules

- **Emoji prefix is mandatory** — every commit header **must** start with a
  Unicode emoji followed by a single space, then the Conventional Commits
  type and (optional) scope. The same rule is applied by the
  [my-housekeeper](https://github.com/qveys/my-housekeeper)
  `commit-message-check` handler (canonical) and by the repo-local
  `.github/workflows/commit-message.yml` (mirror), both of which call
  `scripts/check-commit-message.sh --range base..head` on every push
  and pull-request. See `docs/CONTRIBUTING.md` for the full emoji pool
  by type and the back-to-back emoji rule.
- **Types** — `feat`, `fix`, `docs`, `style`, `refactor`, `perf`,
  `test`, `build`, `ci`, `chore` (same enum as my-housekeeper
  `commitlint.config.mjs`).
- **Breaking changes** — append `!` to the type (`feat!:`, `fix(api)!:`).
  Do **not** write `BREAKING CHANGE:` in the commit body; the rule check
  fails on that prose marker.
- **No git trailers** — `Co-authored-by:`, `Signed-off-by:`,
  `Reported-by:`, `Suggested-by:`, etc. are all rejected. Use `<type>!`
  for breaking change attribution instead.
- Lowercase description after the colon and space.
- No trailing period.
- Subject under **100 characters** total (the bot raises the older
  72-char bound for richer scopes).
- Different emojis back-to-back — two adjacent commits with the same
  leading emoji (e.g. `✨ feat` twice in a row) will be flagged. Vary
  the emoji using the pool table in `docs/CONTRIBUTING.md` or batch
  related work into a single commit.
- Reference issue identifier in commit body or PR body
  (`Closes OPE-XX` or `Ref: OPE-XX`).

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
