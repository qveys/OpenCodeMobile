# Branch Protection & PR Requirements

This document specifies the branch protection policies, PR requirements, and verification procedures for OpenCode Mobile on the `main` branch.

In accordance with `BOOTSTRAP.md` and the CI/CD Security Policy (`docs/CI-CD-SECURITY.md`, mitigating Threat T10), **no direct commits to `main` are permitted**. All changes—including small fixes and documentation updates—must pass through a pull request, pass automated CI checks, and receive the requisite approvals before merge.

---

## 1. Branch Protection Policy for `main`

The `main` branch is protected with the following enforcement rules configured in GitHub repository settings and applied via the GitHub REST API:

### 1.1 Pull Request Requirements
- **Require a pull request before merging:** Mandatory for all changes.
- **Required approving reviews:** At least 1 approval.
- **Dismiss stale pull request approvals when new commits are pushed:** `true`. Any new push to a PR branch automatically revokes prior approvals, ensuring new diffs are reviewed.
- **Require review from Code Owners:** `true` (when `CODEOWNERS` is present).
- **Require conversation resolution before merging:** `true`. All comments and review threads must be resolved before merge.
- **Require approval of the most recent reviewable push:** `true`.

### 1.2 Push Restrictions
- **Disallow direct pushes:** Direct pushes to `main` are completely blocked (`restrictions: null` with push access restricted, and `block_creations: false`).
- **Do not allow force pushes:** `allow_force_pushes: false`. History on `main` is append-only.
- **Do not allow branch deletion:** `allow_deletions: false`. `main` cannot be deleted.
- **Enforce for administrators:** `enforce_admins: true`. Neither bots nor repository administrators may bypass branch protection.

### 1.3 Required Status Checks
- **Require status checks to pass before merging:** Enabled.
- **Require branches to be up to date before merging (`strict`):** `true`. The PR branch must be rebased/merged with the latest `main` commit before merge is unlocked.
- **Required checks:**
  - `lint` (CI code quality and formatting)
  - `test` (Unit, architecture, and contract tests)
  - `build` (Compilation and artifact build verification)

### 1.4 Commit Signatures & Integrity
- **Require signed commits:** Commits landing on `main` must be cryptographically verified (`required_signatures: true`). Commits authored by agents must be created via GitHub GraphQL API (`createCommitOnBranch`) or signed locally with GPG/SSH.

---

## 2. Graduated Review Workflow

Reviews follow a graduated intensity model based on change sensitivity (`BOOTSTRAP.md`):

### 2.1 Standard / Light Review
- **Scope:** Pure UI changes, design system assets, translations, and non-security public documentation.
- **Required Approvals:** Code Reviewer + passing CI.
- **Owner Gate:** Maintainer approves on GitHub; an agent then merges via `scripts/merge-agent-pr.sh` once the gate is satisfied (see `docs/MERGE-PATH.md`).

### 2.2 Enhanced Review (Security & Core Infrastructure)
- **Scope:**
  - `shared/security` (Keychain/Keystore, biometrics, HTTP security policy, TLS/certificate handling).
  - OpenCode adapter and generated OpenAPI client (`shared/opencode`).
  - `shared/realtime` (EventProcessor, SSE, polling fallback, reconnection logic).
  - Permissions feature (tool call approvals, confirmation dialogs).
  - CI/CD workflows, release signing, and deployment automation (`.github/workflows/`, secrets).
  - Logging, telemetry, or any path touching potential leaks of prompts, source code, or credentials.
  - `SECURITY.md` and Privacy Policy.
- **Required Approvals:** Code Reviewer + Security Engineer + explicit maintainer approval.
- **Rejection Triggers:**
  - Violation of architectural rules (R1–R15).
  - Unauthorized dependencies between layers.
  - Sensitive data or credentials printed in logs.
  - Automatic replay of mutations upon reconnect.
  - Manual edits to generated OpenAPI client code.

---

## 3. First-Time Contributor Approval (T10 / OPE-23)

In compliance with `docs/CI-CD-SECURITY.md` (§3):
- GitHub Repository setting: **"Require approval for first-time contributors"** (`Settings` → `Actions` → `General` → `Fork pull request workflows`) must be enabled.
- **Effect:** Workflows triggered by external fork PRs will pause until an authorized maintainer explicitly approves the run, preventing arbitrary execution of untrusted code in Actions runners.

---

## 4. Verification Protocol (Acceptance Criteria)

As required by OPE-23 (`docs/CI-CD-SECURITY.md` §6), branch protection must not merely be documented as intended process; it **must be verified by reading it back from GitHub API**.

### 4.1 Automated API Verification Command

Read back and inspect branch protection on `main`:

```bash
gh api repos/:owner/:repo/branches/main/protection
```

### 4.2 Acceptance Criteria Checklist

| Requirement | Expected Setting in API Response | Status |
|---|---|---|
| Require PR reviews before merge | `.required_pull_request_reviews != null` and `.required_approving_review_count >= 1` | Verified via readback |
| Dismiss stale reviews on push | `.required_pull_request_reviews.dismiss_stale_reviews == true` | Verified via readback |
| Disallow direct pushes | Direct push rejected (`git push origin main` fails) | Verified via test push |
| Enforce for administrators | `.enforce_admins.enabled == true` | Verified via readback |
| Disallow force pushes | `.allow_force_pushes.enabled == false` | Verified via readback |
| Disallow deletions | `.allow_deletions.enabled == false` | Verified via readback |
| Required status checks strict | `.required_status_checks.strict == true` | Verified via readback |
| First-time contributor approval | `fork_pull_request_workflows` requires approval in Actions settings | Verified in Settings |

### 4.3 Test Branch Verification

To verify that the PR workflow is enforced:
1. Attempt a direct push to `main`:
   ```bash
   git checkout main
   git commit --allow-empty -m "test: direct push should fail"
   git push origin main
   # MUST BE REJECTED by GitHub remote with protected branch hook error
   ```
2. Create and push a test branch:
   ```bash
   git checkout -b test/verify-branch-protection
   git push -u origin test/verify-branch-protection
   ```
3. Open a pull request:
   ```bash
   gh pr create --title "test: verify branch protection" --body "Automated test PR" --base main
   ```
4. Verify merge button / CLI merge is blocked:
   ```bash
   gh pr merge --merge
   # MUST BE REJECTED: Required reviews and status checks not satisfied
   ```
5. Clean up test branch:
   ```bash
   gh pr close <pr-number>
   git push origin --delete test/verify-branch-protection
   ```

---

## 5. Automation Scripts

The repository includes scripts to apply and verify these protections:
- `scripts/setup-branch-protection.sh`: Applies the full protection configuration using `gh api`.
- `scripts/verify-branch-protection.sh`: Reads back the protection settings and validates each rule against acceptance criteria.
- `scripts/test-pr-workflow.sh`: Runs the test branch PR workflow validation.
