# Deployment smoke tests

OPE-20. After every deployment, a basic smoke test verifies that the deployed
environment is healthy, that it serves the version that was actually deployed,
and alerts when either check fails.

## What is checked

| Check | What it proves |
| --- | --- |
| Key endpoints | Every configured health path answers with the expected HTTP status (`200` by default), after bounded retries to absorb rollout warm-up. |
| Correct version | The version reported by the deployed environment matches the version built from the deployed revision (`versionName` in `androidApp/build.gradle.kts`, or an explicit override). |
| Alert on failure | A failed check exits non-zero, emits `::error::` GitHub annotations, writes a JSON report, and — when configured — POSTs an alert to a webhook. |

## Files

- `scripts/smoke/deployment-smoke-test.sh` — the smoke test (bash + curl + python3 only).
- `scripts/tests/test-deployment-smoke-test.sh` — self-test against a throwaway local HTTP server; exercises healthy, version-drift, unhealthy, warm-up, alert and bad-config paths.
- `.github/workflows/smoke-test.yml` — runs the smoke test after each CD deployment and runs the self-test on PRs.
- `docs/DEPLOYMENT-SMOKE-TESTS.md` — this document.

## When it runs

- **After each deployment.** The workflow listens for `workflow_run` completion
  of the `CD` workflow (`.github/workflows/cd.yml`, landed by OPE-161) and runs
  when that run succeeded and was triggered by a `push` or manual CD dispatch.
  It checks out the exact revision CD deployed. The `CD` workflow name is the
  contract between the two files: GitHub silently never fires a `workflow_run`
  whose referenced workflow name does not exist.
- **On demand.** `workflow_dispatch` takes a `base_url` and an optional
  `expected_version`.
- **On PRs touching the smoke scripts.** The `self-test` job keeps the smoke
  test itself honest.

Both jobs run on the company self-hosted pool
(`runs-on: [self-hosted, hostinger]`, see `docs/CI-RUNNER-STRATEGY.md` §2);
the workflow needs only bash, curl and python3, so no toolchain step is
required.

The `self-test` job executes script code from the checked-out revision. To keep
that code off the pool shared with deployments, it runs only for same-repository
PRs (`github.event.pull_request.head.repo.full_name == github.repository`), plus
`push` and `workflow_dispatch`; fork-PR runs are skipped. Turning the pool into
ephemeral, per-job runners is tracked by OPE-212.

## Configuration

Repository variables (Settings → Secrets and variables → Actions → Variables):

| Variable | Purpose | Default |
| --- | --- | --- |
| `SMOKE_BASE_URL_STAGING` | Base URL of the staging deployment (e.g. `https://staging.example.test`). Used when the triggering CD run was a push to `main`. | empty — the staging smoke job skips with a notice until set |
| `SMOKE_BASE_URL_PRODUCTION` | Base URL of the production deployment. Used when the triggering CD run was a `v*` tag push. | empty — the production smoke job skips with a notice until set |
| `SMOKE_ENDPOINTS` | Comma-separated health paths. | `/health` |
| `SMOKE_VERSION_PATH` | Path returning deployment version metadata. | `/version` |

Staging and production have **separate** targets, so a smoke run can never pass
by checking the wrong environment. `SMOKE_BASE_URL` (no suffix) is a deprecated
fallback used only when the environment-specific variable is unset; it emits a
`::warning::` and should not be relied on to serve both environments.

Repository secret:

| Secret | Purpose |
| --- | --- |
| `SMOKE_ALERT_WEBHOOK` | Optional. On failure the script POSTs a small JSON alert (`text`, `status`, `baseUrl`, `expectedVersion`) here. Works with Slack-style incoming webhooks and generic receivers. Never logged. |

The version endpoint may return JSON (default field `version`; nesting is
supported, e.g. `build.version`) or plain text. A leading `v` is ignored on
both sides, so `v0.1.0` and `0.1.0` match.

If no smoke target exists yet, the workflow is a no-op with a notice rather
than a false failure; set `SMOKE_BASE_URL_STAGING` and/or
`SMOKE_BASE_URL_PRODUCTION` to start guarding deployments.

## Running locally

```bash
# Self-test (starts its own mock server; no network needed):
bash scripts/tests/test-deployment-smoke-test.sh

# Against a real environment:
bash scripts/smoke/deployment-smoke-test.sh \
  --base-url https://staging.example.test \
  --expected-version 0.1.0 \
  --endpoint /health --endpoint /ready
```

Scripts are checked in as `100644` (the repo convention) and invoked with
`bash`, matching how the other `scripts/**` helpers are called from workflows.

Useful options: `--retries N`, `--retry-delay SECONDS`, `--timeout SECONDS`,
`--report FILE`, `--alert-webhook URL`, `--dry-run`, `--help`. Every option
also has a `DEPLOY_SMOKE_*` environment variable.

Exit codes: `0` healthy, `1` check failed, `2` invalid configuration.

## Security notes

- The smoke test reads no signing or store credentials, and the workflow sets
  `permissions: contents: read` only.
- The alert webhook URL is treated as a secret; it is never echoed into logs or
  the JSON report.
- The workflow uses only `actions/checkout`, matching the repo Actions
  allowlist documented in `docs/CI-CD-SECURITY.md`.
