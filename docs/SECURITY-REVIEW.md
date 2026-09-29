# Initial security review

Date: 2026-09-25
Origin: [OPE-8](/OPE/issues/OPE-8)
Status: initial review complete — SEC-01/SEC-02 resolved; open findings listed below.
Threat model this review is measured against: [docs/THREAT-MODEL.md](/OPE/issues/OPE-7).

## 1. Reviewed baselines

This repository is still in bootstrap: application code is not yet on `origin/main`. This review pins every artifact it inspected.

| Baseline | Ref / hash | On `origin/main`? |
| --- | --- | --- |
| Repository bootstrap scripts + docs | `d61ea99ab13bb3685b34f08fce7ccdaab1086c24` (branch `OPE-8-perform-initial-security-review`, PR #12) | No (PR open) |
| Live GitHub branch-protection / Actions settings | read-only API read on 2026-09-25 | n/a |
| Application scaffold (OPE-30 deliverable) | `dcc3e97ab083578b1131efb413881f4196c75f09` | No (unpushed; landing tracked by [OPE-74](/OPE/issues/OPE-74)) |
| Current checkout snapshot (scaffold + T4 networking redaction + CI workflows + version catalog) | aggregate tree SHA-256 `11def0ecb32b8203c00cecdd1992f4526248039a1e31d2f8fb53c3d54e23e287` | No |

Because the application baseline is not yet merged, findings against it (SEC-04…SEC-08) must be re-verified at the commit that lands the scaffold and CI on `main` ([OPE-74](/OPE/issues/OPE-74)). This report does not constitute security sign-off for shipping.

## 2. Findings

Severity: Critical / High / Medium / Low / Info.

### SEC-01 — Resolved: signing enforcement failed open
- Was: `scripts/setup-branch-protection.sh` ignored a failed "required signatures" enable and still reported success.
- Now: `scripts/setup-branch-protection.sh:57-76` aborts nonzero on a failed POST and requires `enabled=true` readback before printing completion.
- Verified: independent re-review [OPE-75](/OPE/issues/OPE-75) PASS at `d61ea99`; live API read confirms `required_signatures.enabled=true` on `main`.

### SEC-02 — Resolved: verifier reported full compliance on partial evidence
- Was: `scripts/verify-branch-protection.sh` checked only a subset of controls yet printed "ALL ACCEPTANCE CRITERIA VERIFIED".
- Now: `scripts/verify-branch-protection.sh:74-145` checks every declared control (required contexts by set membership, last-push approval, conversation resolution, signatures readback, fork-approval policy, Actions workflow permissions) and treats unreadable/malformed evidence as FAIL/INCOMPLETE.
- Verified: [OPE-75](/OPE/issues/OPE-75) PASS at `d61ea99`. The fixed verifier now correctly **fails** against the live repo (see SEC-03), which is the intended behavior.

### SEC-03 — Medium: required status checks are not configured on `main`
- Location: live `main` branch protection; declared in `scripts/setup-branch-protection.sh:24-31`.
- Evidence: `gh api repos/qveys/OpenCodeMobile/branches/main/protection` returns `required_status_checks.contexts: []` and `checks: []`. The fixed verifier run on 2026-09-25 reports:
  `FAIL: required status check context 'lint' is missing`, `'test' is missing`, `'build' is missing` (exit 1).
- Related drift: live `required_linear_history=true` and `allow_fork_syncing=false`, while the setup script sends `false` and `true` respectively; the verifier does not assert these two controls.
- Risk: merges to `main` are gated only by review, signed commits and conversation resolution — the declared CI gates (`lint`, `test`, `build`) do not exist, so a build-breaking or unlinted change can merge once approved.
- Fix: after the CI workflows land on `main` (blocked by [OPE-74](/OPE/issues/OPE-74); see [OPE-14](/OPE/issues/OPE-14)/[OPE-16](/OPE/issues/OPE-16)/[OPE-18](/OPE/issues/OPE-18)), re-run `scripts/setup-branch-protection.sh` so contexts match real check names, then re-run the verifier to green. If the strict-check names cannot exist yet, keep the verifier red rather than lowering the declared controls.
- Owner: Engineer. Re-verification owner: Security Engineer.

### SEC-04 — Medium: CI workflow uses unpinned GitHub Actions
- Location: `.github/workflows/architecture-tests.yml:40,43,50,62`.
- Evidence: `actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4`, `actions/upload-artifact@v4` use floating major tags. The sibling `.github/workflows/security-logging.yml` pins full commit SHAs.
- Risk: a moved/compromised tag (or a hijacked upstream ref) executes unreviewed code in CI on untrusted PRs — threat **T10** (Critical). `docs/CI-CD-SECURITY.md` §7 requires SHA pinning for every `uses:` step; this workflow violates it.
- Fix: pin each `uses:` to a full commit SHA with a `# vX.Y.Z` comment, matching `security-logging.yml`. Apply before the workflow is merged to `main`.
- Owner: Engineer/DevOps.

### SEC-05 — Low: Gradle supply-chain hardening gaps
- Location: `gradle/wrapper/gradle-wrapper.properties`, repository-wide.
- Evidence: `distributionUrl` is set but no `distributionSha256Sum` is present; there is no `gradle/verification-metadata.xml` and no dependency lockfiles; `gradle-wrapper.jar` (SHA-256 `2db75c40…448046`) has no committed checksum. `org.gradle.caching=true` is enabled in `gradle.properties` (local cache only — no remote cache configured).
- Risk: distribution/artifact substitution is not cryptographically checked (threat **T10**). Impact limited because all dependencies resolve from Maven Central over TLS and no CI signing secrets exist yet.
- Fix (incremental): add `distributionSha256Sum`; commit a wrapper-JAR checksum; enable Gradle dependency verification; keep the build cache local until Kotlin is upgraded per SEC-06.
- Owner: Engineer.

### SEC-06 — Low: build-tooling CVE in Kotlin 2.1.0
- Location: `gradle/libs.versions.toml` (`kotlin = "2.1.0"`, also the Kotlin Gradle plugin).
- Evidence: GitHub Advisory **GHSA-r937-wjx7-w2jp** — "Unsafe Deserialization in Kotlin Build Cache Enables Code Execution", CVSS 6.7 (`AV:L/AC:H/PR:H/S:C`), affected range `< 2.4.20-Beta1`.
- Risk: build-time only. It requires local access plus a poisoned build-cache entry; it does not affect the shipped app and is not remotely reachable. Proportional severity: Low.
- Fix: upgrade Kotlin when a patched stable release is available; until then, do not share the Gradle build cache with untrusted inputs and clear it after building untrusted branches. This is a build-tool upgrade, not an app code change.
- Owner: Engineer.

### SEC-07 — Info: OpenAPI spec checksum is documented but not enforced
- Location: `shared/networking/openapi/README.md:13`, `scripts/generate-openapi-client.py`.
- Evidence: the README publishes SHA-256 `46db9860…40aa5c` and the vendored `opencode-server-v2.json` currently matches it exactly (verified). The generator script does not verify the hash before generating.
- Risk: provenance depends on review discipline (threat **T11**). Low.
- Fix: make the generator fail if the spec hash differs from the documented value, or record the hash in the generation script.

### SEC-08 — Info: host-absolute worktree links in a committed doc
- Location: `shared/networking/openapi/README.md:12,20`.
- Evidence: the doc links to `file:///app/data/instances/.../worktrees/OPE-11-.../<path>` — a host-local agent-worktree path that does not exist for a reader and leaks environment layout.
- Fix: replace with repo-relative links.
- Owner: Engineer.

## 3. Controls observed as adequate at this baseline

- Android host (`androidApp/src/main/AndroidManifest.xml`): `allowBackup="false"`; no `usesCleartextTraffic` (secure default); only the launcher `MainActivity` is exported; no permissions requested; all library manifests are empty `<manifest />`.
- iOS (`iosApp/iosApp/Info.plist`): no `NSAppTransportSecurity` exceptions (ATS default hardened); no URL schemes / device-capability disclosures.
- T4 (token leakage via logging): `shared/networking/.../logging/LogRedactor.kt`, `SanitizingHttpLogger.kt`, `SanitizingLogging.kt` redact credential headers, bearer/basic values and sensitive JSON body fields; `scripts/check-no-secret-logging.sh` is a dedicated CI gate (with redaction unit tests). Design and implementation reviewed as part of [OPE-27](/OPE/issues/OPE-27).
- No embedded credential, key or token found in the reviewed tree. The `token`/`key`/`credential` strings in `shared/networking/openapi/opencode-server-v2.json` are API schema field names, not values.
- Repository settings: `required_signatures=true`, `enforce_admins=true`, required review count 1 with stale-review dismissal and last-push approval, conversation resolution required, force-push/deletion disabled, Actions default token permissions `read`, workflows cannot approve PRs, fork-PR approval = `first_time_contributors`.

## 4. Coverage

| Area | Result |
| --- | --- |
| Secrets exposure | No credential/key/token in the reviewed tree; redaction controls present (T4). No historical/object-store scan performed. |
| Dependencies / CVEs | Declared versions in `gradle/libs.versions.toml` checked against the GitHub Advisory Database (Maven). One build-tool advisory (SEC-06); Ktor, OkHttp and other app dependencies not affected in the declared ranges. Transitive versions are not lockfile-pinned, so this is catalog-level, not resolved-graph-level. |
| OWASP Top 10 | Largely not yet assessable at this baseline: no application logic, no authn/authz code, no API handler. The threat model covers the relevant mobile surfaces (T1/T2/T3/T5/T8) as design, not implementation. |
| TLS / transport | Android and iOS have no cleartext/ATS exceptions. No certificate/SPKI pinning in code yet (T1 is design-only at this baseline; implementation reviewed separately under [OPE-35](/OPE/issues/OPE-35)). |
| CI/CD & config | SEC-03, SEC-04; fork-PR/no-secrets/permissions posture otherwise consistent with `docs/CI-CD-SECURITY.md`. |
| Injection / shell | Bootstrap scripts reviewed previously; no confirmed input-to-shell flaw. Not re-executed against GitHub. |

## 5. Limitations

- The application baseline is not on `origin/main`. SEC-04…SEC-07 must be re-verified at the landing commit ([OPE-74](/OPE/issues/OPE-74)).
- No dependency lockfile means the resolved dependency graph (including transitive OkHttp/Netty/etc.) was not enumerated.
- T1, T2, T3, T5, T8 controls are design-only at this baseline or reviewed on their own branches; this report does not re-certify them.
- Live GitHub settings were read once (2026-09-25); they can drift and are covered by the fixed verifier.

## 6. Disposition and next actions

- SEC-01/SEC-02: resolved and independently re-verified.
- SEC-03/SEC-04 must be fixed before `main` is considered CI-gated and before the new workflows merge.
- Follow-up re-verification (post-landing) is tracked as a separate Security Engineer issue blocked by [OPE-74](/OPE/issues/OPE-74); this initial review is otherwise complete. No operational repository policy was mutated during this review; all GitHub calls were read-only.