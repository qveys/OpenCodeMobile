# PR Conventions & Review Workflow

## Branch Naming

Branches must follow the standard naming convention:

```text
<prefix>-<N>/<short-description>
```

- `<prefix>`: Company issue prefix in lowercase (e.g. `ope`)
- `<N>`: Issue number
- `<short-description>`: Brief kebab-case description

Examples:
- `ope-10/set-up-branch-protection`
- `ope-13/ci-cd-pipeline`
- `ope-23/harden-supply-chain`

---

## PR Title & Description

### PR Title
Conventional Commits format with the **mandatory Unicode emoji prefix** that
[my-housekeeper `commitlint.config.mjs`](https://github.com/qveys/my-housekeeper/blob/main/commitlint.config.mjs)
and the repo-local `scripts/check-commit-message.sh` enforce:

```text
<emoji> <type>(<scope>): <short description>
```

Rules:

- Picking the emoji and the type follows
  [docs/CONTRIBUTING.md § Emoji pool by type](../CONTRIBUTING.md#emoji-pool-by-type).
- Max **100 characters** total for `<emoji> <type>(<scope>): <description>`.
- The PR title **must not include** ` (#N)` — GitHub appends it to the
  squash-merged commit message automatically, and `normalize-title` (the
  bot's handler that renames PR/issue titles) explicitly strips a
  user-supplied ` (#N)` so the resulting commit does not end up with
  ` (#12) (#12)`. Use `Closes OPE-XX` in the PR body to link the issue.
- Lowercase after the colon, no trailing period.
- Breaking change: append `!` to the type (`feat!:`, `fix(api)!:`).
  Do not write `BREAKING CHANGE:` in the body — it is rejected.

### PR Body Template
```markdown
## What changed
<Clear summary of changes introduced in this PR>

## Why
<Context, architectural motivation, and problem solved>

## How to test
<Step-by-step verification instructions, test commands, or API queries>

## Security & Architectural Checklist
- [ ] No direct commits to main
- [ ] Architectural boundaries and rules R1–R15 respected
- [ ] No secrets, tokens, or credentials in code or logs
- [ ] CI passed (lint, unit/arch tests, build)
- [ ] Verification readback completed (for security/infra changes)

## Related
Closes [OPE-XX]
```

---

## Graduated Review Workflow

Every PR requires review before merge. Review requirements depend on change scope:

### Tier 1: Standard Review
- **Applies to:** UI, design system, localization/translations, public documentation (excluding security/privacy).
- **Required Reviewers:** Code Reviewer + passing CI.
- **Merge Gate:** Maintainer approves on GitHub; an agent then merges via `scripts/merge-agent-pr.sh` (see `docs/MERGE-PATH.md`).

### Tier 2: Enhanced Review (Security & Core Infrastructure)
- **Applies to:**
  - `shared/security` (Keychain/Keystore, biometrics, HTTP security policy, TLS/certificate handling).
  - OpenCode adapter and generated OpenAPI client (`shared/opencode`).
  - `shared/realtime` (EventProcessor, SSE, polling fallback, reconnection logic).
  - Permissions feature (tool call approvals, confirmation dialogs).
  - CI/CD workflows, release signing, and deployment automation (`.github/workflows/`, secrets).
  - Logging, telemetry, or any path touching potential leaks of prompts, source code, or credentials.
  - `SECURITY.md` and Privacy Policy.
- **Required Reviewers:** Code Reviewer + Security Engineer + explicit maintainer approval.

---

## Merge Rules

- Direct pushes to `main` are disabled and forbidden.
- Approvals from required review roles must be recorded.
- CI pipeline (`lint`, `test`, `build`) must pass and be strictly up to date.
- Maintainer is the sole approval owner. No agent merges alone: after the owner approves (and only then), an agent may merge with `scripts/merge-agent-pr.sh`, which fails closed unless `reviewDecision=APPROVED` and `mergeStateStatus=CLEAN` (see `docs/MERGE-PATH.md`).
