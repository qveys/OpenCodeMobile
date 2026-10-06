# Security review — initial review and post-landing re-verification

Date: 2026-09-25 (initial review) · 2026-09-29 (re-verification at the landing commit)
Origin: [OPE-8](/OPE/issues/OPE-8) · re-verification [OPE-78](/OPE/issues/OPE-78)
Status: re-verified at the landed `main`, then completed the three deferred SEC-05 follow-ups. SEC-01…SEC-05 resolved; SEC-06/SEC-07/SEC-08 remain open.
Threat model this review is measured against: [docs/THREAT-MODEL.md](/OPE/issues/OPE-7).

## 1. Reviewed baselines

The initial review ran against a scaffold that was not yet on `origin/main`. This re-verification ran at the commit that actually landed the KMP/CMP scaffold and the CI workflows ([OPE-74](/OPE/issues/OPE-74) → done).

| Baseline | Ref / hash | On `origin/main`? |
| --- | --- | --- |
| Initial review — bootstrap scripts + docs | `d61ea99ab13bb3685b34f08fce7ccdaab1086c24` | Yes (PR #12, squashed to `54b44e4`) |
| **Re-verification baseline** — `origin/main` tip | `54b44e4` (`🔒 fix(security): fail closed on signing setup and verify all protection controls (#12)`) | Yes |
| Live GitHub branch-protection / Actions settings | read-only API read on 2026-09-29 | n/a |
| KMP/CMP module scaffold | `b2435bc` (PR #14) | Yes |
| CI workflows on `main` | `build.yml`, `provision-runner-toolchain.yml`, `security-logging.yml`, `t1-device-validation.yml` | Yes |

`main` is now linear and the whole KMP/CMP module set (`shared/*`, `features/*`, `design-system`, `androidApp`) is present, so the deferred SEC-04…SEC-08 findings could be re-verified against a real baseline.

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

### SEC-03 — Resolved: required status checks are now configured on `main`
- Was: live `main` branch protection returned `required_status_checks.contexts: []` and `checks: []`, so merges were gated only by review, signed commits and conversation resolution.
- Now: `gh api repos/qveys/OpenCodeMobile/branches/main/protection` returns `required_status_checks.contexts: ["T4 static scan"]` and `checks: [{"context":"T4 static scan","app_id":15368}]`. The declared context matches a job that actually runs on every pull request (`T4 static scan` in `.github/workflows/security-logging.yml`), so the check is reportable and not permanently "Expected".
- Verified: `scripts/verify-branch-protection.sh` (read-only, from `origin/main` at `54b44e4`) exits **0** against the live repository, with every declared control passing.
- Drift reconciled: live `required_linear_history=true` and `allow_fork_syncing=false`. `scripts/setup-branch-protection.sh` on `main` now sends `required_linear_history: true` and `allow_fork_syncing: false`, matching live — the earlier script/live disagreement is gone. The repository ruleset (`23940009`) carries the same required check, so both protection layers agree.
- Note: the initial review proposed declaring `lint`/`test`/`build`. Those jobs do not exist as separate workflows on `main` — `build.yml` publishes `Build Android (APK/AAB)` and `Build iOS/macOS frameworks`, and no lint/test workflow has landed. Declaring a context no workflow emits would block every PR forever, so the declared set was corrected to the one real, always-reporting gate rather than weakened to `[]`. The verifier asserts exactly the declared set, so widening the gates later stays a visible, reviewable change.

### SEC-04 — Resolved: CI workflow used unpinned GitHub Actions
- Was: `.github/workflows/architecture-tests.yml` used `actions/checkout@v4`, `actions/setup-java@v4`, `gradle/actions/setup-gradle@v4` and `actions/upload-artifact@v4` — floating major tags, in violation of `docs/CI-CD-SECURITY.md` §7.
- Landing status: that workflow never reached `main`. It exists only on the unmerged branch `OPE-16-add-test-runner-and-initial-tests` (commit `dffa2bb`, not an ancestor of `origin/main`); PR #16 landed a different test-runner change. All four workflows on `main` are already fully SHA-pinned — 10 `uses:` references at the time of that verification, every one a 40-hex commit SHA, and 11 after `7fcddb6` added the lint workflow.
- Because fixing one file leaves the next workflow free to reintroduce the hole, the control is now structural: `scripts/check-workflow-action-pinning.sh` fails the build when any `uses:` is not a full commit SHA, and runs as a step of the required `T4 static scan` job. Because that job is already a required status check, the gate is blocking on every pull request **without any branch-protection change** — a new job name would not be required until protection was edited, and would be advisory-only until then.
- Verified: `scripts/tests/test-supply-chain-gates.sh` (119 assertions) covers floating tags, floating branch refs, 39- and 41-character SHAs, quoted refs, pinned sub-path actions and reusable workflows, digest-pinned and tag-referenced container actions, the local `./` exemption, and a fail-closed vacuous scan. Replaying the real `architecture-tests.yml` through the gate fails on all 4 original floating refs at lines 40, 43, 50 and 62, while the real `main` tree passes with every reference SHA-pinned (11 as of `7fcddb6`).
- Gate hardening (OPE-214, after an external static review of this pull request). Two of the gate's rules were not the rules they claimed to enforce. (a) Every `docker://` reference was waved through as "pinned", so `docker://alpine:latest` — the container equivalent of a floating tag, resolved at job time from a registry account an attacker may control — passed. Container actions are now accepted only as `docker://image@sha256:<64-hex>`, and an unpinned one is a finding on the pull request diff. (b) The pinning pattern accepted only `owner/repo@<sha>`, so a correctly pinned sub-path action (`aws-actions/amazon-ecr/amazon-ecr-login@<sha>`) or reusable workflow (`owner/repo/.github/workflows/build.yml@<sha>`) was reported as floating. Both are now matched; the pattern stays anchored, so 39- and 41-character values still fail. One fail-closed guard was also widened: the vacuity check counted exempt local `./path` steps toward "the pinning rule still matches something", so a tree whose every remote action had been deleted satisfied it. An exempt local step no longer counts; a digest-pinned container action does. This repository has no container action, so no workflow file changed.

### SEC-05 — Resolved: Gradle supply-chain hardening gaps
- Was: `distributionUrl` was set with no `distributionSha256Sum`; there was no `gradle/verification-metadata.xml`, no dependency lockfiles, and `gradle-wrapper.jar` (SHA-256 `2db75c40…448046`) had no committed checksum.
- Now: `gradle/wrapper/gradle-wrapper.properties` declares `distributionSha256Sum=31c55713e40233a8303827ceb42ca48a47267a0ad4bab9177123121e71524c26` for `gradle-8.10.2-bin.zip`; `gradle/wrapper/gradle-wrapper.jar.sha256` commits the wrapper-JAR digest; `gradle/verification-metadata.xml` pins a SHA-256 per resolved artifact (765 components / 1609 artifacts at this head, Android/JVM plus all three iOS targets); and `scripts/check-gradle-supply-chain.sh` gates all of it as a step of the required `T4 static scan` job, additionally rejecting an `http` `distributionUrl` downgrade and a verification-metadata file that pins nothing.
- Dependency-verification gate hardening (OPE-214, after an external static review of this pull request). Control 4 was `grep -q '<components>'`. A substring test cannot tell a document that pins 1609 artifacts from one that pins none, and three fail-open documents passed it: an empty `<components></components>` element, a file whose only occurrence of the string sits inside an XML comment, and a `<component>` with no artifact. All three were reported to the maintainer as `[ok] ... pins resolved dependency checksums`, so deleting every `<component>` block — the one edit that turns dependency verification off — left the required SEC-05 check green. The document is now parsed (python3, standard library, no network) and its content measured: well-formed XML, a `<verification-metadata>` root, at least one component, at least one SHA-256, no artifact left without a checksum or a signature entry, and `<verify-metadata>` not downgraded to false. The gate prints what it counted — on this tree 1609 checksums across 765 components / 1609 artifacts — and fails closed when python3 is absent rather than skipping the control. A signature-pinned artifact (no digest, a `<trusting-key>`) stays acceptable so the gate remains usable for a signature-verifying repository.
- Distribution publisher (OPE-216). Control 2 tested only that `distributionUrl` began with `https://`. That is a statement about the transport, not about who is at the other end, so it accepted any https host. `distributionUrl` and `distributionSha256Sum` were each well-formed on their own, which meant a single pull request could edit both — `distributionUrl=https\://attacker.example/…` alongside `distributionSha256Sum=<sha256 of the attacker's own archive>` — satisfy both controls and leave the gate green, after which `./gradlew` downloaded and executed the attacker's Gradle. That is threat T10 and the exact outcome SEC-05 claims to prevent; detection until now was a human reading the diff. The digest says *which bytes*; it never said *who published them*. Control 2 now binds the two together by requiring the URL's **host** to be a publisher this repository trusts: the official `services.gradle.org` (on a `/distributions/gradle-<version>-{bin,all}.zip` path only), or a host explicitly listed in the new `gradle/wrapper/gradle-distribution-allowlist.txt`. The list is empty by default, so this repository's effective policy is "official publisher only" while a mirror stays a supported configuration reached through a reviewed commit rather than through whatever a pull request happens to write. The URL is **parsed**, not prefix-matched: `https://services.gradle.org@attacker.example/…` (the approved string is userinfo; the connected host is the attacker's), `https://attacker.example/?next=https://services.gradle.org`, and `https://services.gradle.org.attacker.example/…` are all rejected, and a trailing-dot host is refused rather than silently matched. A redirect to another host is still covered — the wrapper follows it, but the bytes must then match the digest that control 2 bound to a trusted publisher. A missing allowlist file fails closed rather than degrading to "trust the pull request".
- Checksum provenance: confirmed from the publisher. `services.gradle.org` is not directly reachable from the sandbox, but its `/distributions/gradle-8.10.2-bin.zip.sha256` is readable through the allowlisted JetBrains cache-redirector mirror and returns `31c55713…24c26`; independently, the official `gradle/gradle-distributions` v8.10.2 release archive (136,715,430 bytes) was downloaded and hashed locally to the same digest. A wrong value fails loudly on the first `./gradlew` invocation rather than silently, and the committed wrapper-JAR digest matches the value independently recorded in the 2026-09-25 review.
- Dependency verification: resolved. `gradle/verification-metadata.xml` is committed with `<verify-metadata>true</verify-metadata>`; every artifact CI resolves is pinned, including the AGP/lint classpath POMs and the per-target Apple klib and Compose-resource artifacts. `./gradlew build`, `:androidApp:assembleDebug`/`assembleRelease`/`bundleRelease`, the shared-module unit tests and `:shared:security:assembleDebugAndroidTest` all pass under Gradle's strict dependency verification, and the gate now hard-fails if the metadata file is deleted, is not parseable, carries no `<components>`, has an empty `<components>` section, leaves an artifact without a checksum or a signature entry, or sets `<verify-metadata>false</verify-metadata>`. The Apple `linkDebugFramework*` tasks cannot execute on Linux, so their coverage was verified at dependency-resolution level (all iOS compile/test klib configurations resolve and pass strict verification). Pinning the resolved graph also unblocks a future resolved-graph advisory scan.
- Pin coverage and the Portal / Central divergence (OPE-238, after OPE-220). Control 4 answers *is the pin file well-formed and non-empty*; it cannot answer *does it cover what the build resolves*. That gap is the incident itself: `main` added `alias(libs.plugins.detekt)` to the root build, `gradle/verification-metadata.xml` was never regenerated, and one unpinned marker POM failed during plugin resolution — before any job ran a step — turning Build Android, Build iOS/macOS, `lint` and all four T1 jobs red at once. Every existing gate stayed green, because the pin file was still present and still pinned 1609 artifacts. A second, sharper defect sat behind it: `--write-verification-metadata` is only correct from a machine reaching **both** Maven Central and the Gradle Plugin Portal, and the two serve different bytes for six of the seven plugin markers in this file (`kotlin.multiplatform`, `kotlin.android`, `kotlin.jvm`, `compose`, `plugin.compose`, `plugin.serialization`; `app.cash.sqldelight` matches because the Portal proxies Central for plugins it does not publish itself). Regenerating from a Central-only host therefore rewrites those pins with a value CI rejects, silently. Three controls now close the class of bug. `scripts/check-verification-metadata-coverage.sh` (SEC-05b) resolves every `libs.` alias, bundle, literal coordinate and plugin marker the build scripts declare, and requires each to exist as a `<component>` in the pin file — 37 declared coordinates on this repository, all pinned. A declaration it cannot resolve to a coordinate must be registered in `gradle/verification-coverage-exemptions.txt` **with a reason**, so the hole cannot be widened silently; the reason is mandatory and a bare entry is refused, because an unjustified exemption is indistinguishable from a bypass. And no workflow under `.github/workflows/` may pass `--write-verification-metadata` at all, unless registered in `gradle/verification-regeneration-exemptions.txt` and shown from its own `on:` block to trigger on `workflow_dispatch` only — so the flag and a `pull_request` trigger cannot be added in one pull request. Both lists are reviewed commits, the regeneration procedure and the measured divergence table are written up in `docs/CI-CD-SECURITY.md` §8, and the gate runs in the same required `T4 static scan` job, so it is blocking without a branch-protection change and needs no JDK.
- Kotlin re-check for SEC-06: `kotlin = "2.1.0"` is unchanged and still in the affected range `< 2.4.20-Beta1` for GHSA-r937-wjx7-w2jp. No patched release exists yet, so the upgrade is not actionable; SEC-06 remains open.

### SEC-06 — Low: build-tooling CVE in Kotlin 2.1.0
- Location: `gradle/libs.versions.toml` (`kotlin = "2.1.0"`, also the Kotlin Gradle plugin).
- Evidence: GitHub Advisory **GHSA-r937-wjx7-w2jp** — "Unsafe Deserialization in Kotlin Build Cache Enables Code Execution", CVSS 6.7 (`AV:L/AC:H/PR:H/S:C`), affected range `< 2.4.20-Beta1`.
- Risk: build-time only. It requires local access plus a poisoned build-cache entry; it does not affect the shipped app and is not remotely reachable. Proportional severity: Low.
- Fix: upgrade Kotlin when a patched stable release is available; until then, do not share the Gradle build cache with untrusted inputs and clear it after building untrusted branches. This is a build-tool upgrade, not an app code change.
- Owner: Engineer.

### SEC-07 — Info (unchanged): OpenAPI spec checksum is documented but not enforced
- Location: `shared/networking/openapi/README.md:13`, `scripts/generate-openapi-client.py`.
- Re-verified at `54b44e4`: the README publishes SHA-256 `46db9860…40aa5c` and the vendored `opencode-server-v2.json` on `main` still matches it exactly (recomputed: `46db986090aae41846cd6dbe16225a1d883f0bbcb4c48814008d3f6ce140aa5c`). The generator still does not verify the hash before generating.
- Risk: provenance depends on review discipline (threat **T11**). Low.
- Fix: make the generator fail if the spec hash differs from the documented value, or record the hash in the generation script.
- Owner: Engineer.

### SEC-08 — Info (unchanged): host-absolute worktree links in a committed doc
- Location: `shared/networking/openapi/README.md:12,20`.
- Re-verified at `54b44e4`: both `file:///app/data/instances/…/worktrees/OPE-11-…/` links are still present on `main` and still leak host-local environment layout.
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
| CI/CD & config | SEC-03, SEC-04, SEC-05 resolved or structurally enforced. Fork-PR approval, workflow-permission restriction, no-secrets gate and required-signature posture verified live. The two new supply-chain gates run inside the required `T4 static scan` job, so they are blocking without a branch-protection change. |
| Injection / shell | Bootstrap scripts reviewed previously; no confirmed input-to-shell flaw. Not re-executed against GitHub. |

## 5. Limitations

- The Gradle distribution checksum is now confirmed from the publisher: the `.sha256` served on the `services.gradle.org` path (read through the sandbox's allowlisted JetBrains cache-redirector, which mirrors the publisher bytes) and the official `gradle/gradle-distributions` release archive both resolve to `31c55713…24c26` (see SEC-05).
- `gradle/verification-metadata.xml` now pins the **resolved** dependency graph (including transitive OkHttp/Netty/etc.) to SHA-256 checksums, so substitution is detectable. The advisory re-scan in §6 remains a declared-catalog scan; enumerating and re-scanning the resolved graph against the advisory database is now possible and remains follow-up work (not required by SEC-05).
- The Kotlin advisory (SEC-06) has no patched release, so the upgrade could not be actioned or disproven by upgrade.
- T1, T2, T3, T5, T8 controls are design-only at this baseline or reviewed on their own branches; this report does not re-certify them.
- Live GitHub settings were read on 2026-09-29; they can drift and are covered by the fixed verifier.

## 6. Dependency advisory re-scan (2026-09-29)

Re-queried the GitHub Advisory Database for every coordinate declared in `gradle/libs.versions.toml`, against the declared versions:

| Package | Declared | Result |
| --- | --- | --- |
| `org.jetbrains.kotlin:kotlin-gradle-plugin` | 2.1.0 | **GHSA-r937-wjx7-w2jp** affected (`< 2.4.20-Beta1`), no patched release — SEC-06 open |
| `org.jetbrains.kotlin:kotlin-stdlib` | 2.1.0 | Clear of both advisories (`≤ 1.5.32`) |
| `io.ktor:ktor-client-core` | 3.0.1 | Clear (GHSA-xwgq-pcqx-hpmv affects `≤ 1.2.6`) |
| `com.squareup.okhttp3:okhttp` | 4.12.0 | Clear (GHSA-3cqm-mf7h-prrj affects `< 4.9.2`; GHSA-4hc2-jh7r-wrc3 affects `≤ 3.1.1`) |
| `io.ktor:ktor-client-okhttp`, `ktor-client-darwin` | 3.0.1 | No advisories |
| `io.insert-koin:koin-core`, `koin-android` | 4.0.0 | No advisories |
| `app.cash.sqldelight:runtime` | 2.0.2 | No advisories |
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | 1.9.0 | No advisories |
| `org.jetbrains.kotlinx:kotlinx-serialization-json` | 1.7.3 | No advisories |
| `io.kotest:kotest-framework-engine` | 5.9.1 | No advisories |
| `com.android.tools.build:gradle` (AGP) | 8.7.2 | No advisories |
| `org.jetbrains.compose:compose-gradle-plugin` | 1.7.1 | No advisories |

No new advisory affects a declared version. The one open build-tool finding is SEC-06. This remains a declared-catalog scan; the committed dependency verification metadata (§SEC-05) now makes a resolved-graph scan possible, which remains follow-up work.

## 7. Disposition and next actions

- SEC-01 / SEC-02: resolved and independently re-verified.
- SEC-03: **resolved** — the required status check is configured, matches a real always-reporting job, and the verifier exits 0 live. No policy was weakened to achieve it.
- SEC-04: **resolved** — all `main` workflows are SHA-pinned, and a required-check gate now prevents regression structurally.
- SEC-05: **resolved** — the distribution checksum is confirmed from the publisher, `gradle/verification-metadata.xml` is committed and enforced by the `scripts/check-gradle-supply-chain.sh` gate, and `distributionUrl` must name a trusted publisher (official `services.gradle.org`, or a host in `gradle/wrapper/gradle-distribution-allowlist.txt`, which is empty) so the digest cannot be re-pointed at an attacker's host in the same pull request.
- SEC-06: open, not actionable (no patched Kotlin release). Build cache stays local; no remote build cache is configured, which is the documented mitigation.
- SEC-07 / SEC-08: open, Info, unchanged and re-verified against `main`.
- No operational repository policy was mutated during this re-verification; all GitHub API calls were read-only, and the verifier runs read-only by design.