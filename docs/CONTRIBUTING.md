# Contributing to OpenCode Mobile

Thanks for your interest in OpenCode Mobile. This project is a **specification-execution**
project: architecture, technology, and design decisions are already made and recorded in
`docs/`. Contributions implement or improve the documented design — they do not re-derive it.

Before you start, read:

- [ARCHITECTURE.md](ARCHITECTURE.md) — module map and enforced dependency rules.
- [API.md](API.md) — the OpenCode Server v2 contract.
- [git-workflow.md](git-workflow.md) and [pr-conventions.md](pr-conventions.md) — the full
  branch and review workflow.
- [BRANCH-PROTECTION.md](BRANCH-PROTECTION.md) — protected-branch rules and required checks.

## Development setup

Prerequisites and build commands are in the [README's Getting started section](../README.md#getting-started).
In short:

```bash
./gradlew build                              # compile the shared KMP graph
./gradlew test                               # unit tests
./gradlew :architecture-tests:test           # module-boundary rules (Konsist)
./gradlew :androidApp:installDebug           # run the Android app
```

## Branching and pull requests

- **Never commit directly to `main`.** Direct pushes are disabled. Every change, including
  documentation, ships through a pull request.
- Branch names follow `<prefix>-<N>/<short-description>`, for example
  `ope-12/initial-project-documentation`.
- Keep a PR scoped to one issue. Reference the issue in the PR body (`Closes OPE-XX`).
- All required checks must be green and up to date before merge. The maintainer is the sole
  merge authority.

## Commit conventions

Every commit message must start with a **Unicode emoji prefix**, then Conventional Commits
format:

```text
<emoji> <type>(<scope>): <short description>
```

Examples:

```text
📄 docs(architecture): add system overview and module map
✨ feat(sessions): add session fork action
🐛 fix(realtime): recover SSE stream after network change
```

- Types: `feat`, `fix`, `docs`, `chore`, `refactor`, `test`, `perf`.
- Lowercase description, no trailing period, subject under 72 characters.
- **No co-authors and no Git trailers** (`Co-authored-by:`, `Signed-off-by:`, etc.) in the
  subject or body.

Because the repository requires verified commits, agent and automation commits must be created
through the GitHub API or a signing identity. A plain local `git commit` produces an unverified
commit that branch protection rejects.

## Review tiers

Review intensity scales with the area you touch (see [pr-conventions.md](pr-conventions.md)):

- **Tier 1 — standard:** UI, design system, localization, public documentation (excluding
  security and privacy). Requires a code review and passing CI.
- **Tier 2 — enhanced:** `shared/security`, the OpenCode adapter and generated client,
  `shared/realtime`, the permissions feature, CI/CD and release signing, and any path that can
  leak prompts, source code, or credentials. Requires a code review **and** a security review
  **and** explicit maintainer approval.

## Testing expectations

- Add or update unit tests for behavior you change.
- Run `./gradlew :architecture-tests:test` whenever you add a module dependency or move code
  between modules; the rules in [ARCHITECTURE.md](ARCHITECTURE.md) are enforced, not advisory.
- Run the smallest set of checks that proves your change before opening the PR, and record what
  you ran in the PR body.

## Architectural rules

The dependency rules listed under **Dependency rules** in [ARCHITECTURE.md](ARCHITECTURE.md) are
non-negotiable. In particular:

- Keep `shared/domain` free of frameworks.
- Do not expose generated OpenAPI types outside `shared/networking` (rule R3).
- Do not hand-edit generated client code (rule R12).
- Do not import Ktor, SQLDelight, or platform secure-storage APIs from UI layers.

Any deviation from the architecture specification must be recorded in an ADR under
[`docs/adr/`](adr/) before or with the change.

## Security rules

- Never commit secrets, tokens, credentials, or signing material.
- Never log credentials or raw request/response bodies; use the sanctioned sanitizing logger.
  The `scripts/check-no-secret-logging.sh` gate enforces this.
- Treat the threat model in [THREAT-MODEL.md](THREAT-MODEL.md) as binding. Changes that affect
  a documented mitigation need the enhanced review tier.

## Documentation

- User- and operator-facing documentation lives in `docs/`; the project entry point is
  [`README.md`](../README.md).
- Update the relevant document in the same PR as the code change it describes.
- Keep [ROADMAP.md](../ROADMAP.md) and the ADRs current when scope or decisions change.