# Merge Path for Agent Pull Requests

Status: **decided** — owner selected **Option 3: owner approves, agent merges**
(OPE-79). Owner of the approval action: **qveys** (repository owner/admin).

## 1. Problem

Every agent PR is authored by the GitHub App installation
`my-paperclip-company` (integration id `3946016`), and that App is the only
GitHub identity agents have. The `main` ruleset (`id 23940009`) requires one
approving review (`required_approving_review_count: 1`,
`dismiss_stale_reviews_on_push`, `require_last_push_approval`) with
`enforce_admins: true` and `bypass_actors: []`. GitHub rejects self-approval
(`Review Can not approve your own pull request`), so an agent PR can never satisfy
the gate on its own and `gh pr merge` fails with "the base branch policy prohibits
the merge".

## 2. Decision and trade-off

- **Chosen:** Option 3 — the repository owner approves each PR on GitHub, then an
  agent performs the merge.
- **Trade-off recorded:** independent human approval stays in force and the
  charter text ("the owner is the sole merge authority") is unchanged in spirit:
  no agent may merge alone. The cost is one human approval per PR — the toil this
  record does not remove. Option 1 (App bypass on the ruleset) and Option 2
  (dedicated reviewer identity) were rejected in favour of keeping the GitHub
  review gate meaningful.

## 3. Workflow

```text
1. Agent opens the PR from its branch (all commits signed; see docs/git-workflow.md).
2. Owner reviews and approves on GitHub:
       gh pr review <n> --approve
3. Agent merges only after the gate is satisfied:
       scripts/merge-agent-pr.sh <n> [--method squash|merge|rebase] [--repo owner/repo]
```

`scripts/merge-agent-pr.sh` **fails closed**. It merges only when GitHub reports
`state=OPEN`, not a draft, `reviewDecision=APPROVED`, and
`mergeStateStatus=CLEAN` (no conflicts, CI green, branch up to date). It never
passes `--admin` and never bypasses branch protection. Anything else exits
non-zero without merging.

If the repository setting "Allow auto-merge" is enabled by the owner, an agent
may arm the merge with `--arm` (runs `gh pr merge --auto`); the merge then lands
when the owner's approval arrives. Auto-merge is currently disabled, so the
default two-step flow above applies.

## 4. Verification

```bash
# Helper behaviour (hermetic, no live API call):
scripts/test-merge-agent-pr.sh

# Live gate state for a PR:
gh pr view <n> --json reviewDecision,mergeStateStatus,state,isDraft
# Merge succeeds only with: reviewDecision=APPROVED and mergeStateStatus=CLEAN

# Confirm the ruleset still enforces the review gate:
gh api repos/qveys/OpenCodeMobile/rulesets/23940009 \
  --jq '{bypass_actors,current_user_can_bypass}'
```

## 5. Related

- `docs/BRANCH-PROTECTION.md` — protection settings and review tiers.
- `docs/git-workflow.md` — branch, commit, and merge steps.
- `docs/pr-conventions.md` — PR titles, bodies, and merge rules.
- `scripts/merge-agent-pr.sh`, `scripts/test-merge-agent-pr.sh`.