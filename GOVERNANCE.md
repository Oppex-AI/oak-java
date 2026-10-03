# Governance

How OAK is maintained, who reviews what, and the lightweight rules that exist because OAK
executes operations against real infrastructure. The goal is a low-ceremony project that is
still careful where carelessness would be expensive.

## Project

OAK (Oppex Agentic Kit) is an open-source execution SDK. It is developed alongside, but
separately from, the Oppex platform. Oppex is the **brain** (it plans and orchestrates);
OAK is the **execution layer** (it runs steps on infrastructure you control). The two meet
at a versioned contract — see [Cross-repository contract](#cross-repository-contract-oppex--oak).

## Roles

| Role | Can | Access |
|---|---|---|
| **Contributor** | Fork, open PRs, review informally | No write access — fork + PR ([CONTRIBUTING.md](CONTRIBUTING.md)) |
| **Reviewer** | Review, approve / request changes, contribute | Triage / write on PRs; no bypass |
| **Maintainer** | Merge PRs, repo maintenance, cut releases | Write; no branch-protection bypass |
| **Security reviewer** | Required review on execution / auth / permission / transport paths | As reviewer, scoped to sensitive paths |
| **Admin** | Rulesets, security settings, secrets, repo administration | Admin — kept to as few people as possible |

Normal flow for everyone, maintainers included:

```
change  →  PR  →  CI  →  review  →  merge
```

No one pushes to `main` directly. Branch-protection **bypass is not a maintainer perk** — it
is limited to repository/org administrators for genuine emergencies only.

## Teams and roles

> ⚠️ These teams **do not exist yet** and must be created in the `Oppex-AI` org (requires an
> org owner — `admin:org`). Until they exist, [`.github/CODEOWNERS`](.github/CODEOWNERS)
> references them but GitHub **silently ignores** unknown owners, so code-owner review is not
> actually enforced. Create them, give them repo access, then CODEOWNERS takes effect.

| Team | Purpose | Repo access |
|---|---|---|
| `oak-maintainers` | Merge PRs, repo maintenance, releases | Maintain / Write |
| `oak-reviewers` | Code review, approvals | Triage / Write |
| `oak-security` | Review of security-sensitive execution / auth / permission / transport changes | Write (review-scoped via CODEOWNERS) |

Admin rights are a **repository/org administrator** concern, intentionally not granted to a
whole team.

## How review works

- Every PR needs **at least one approving review**, from **someone other than the author**.
- Paths in [`.github/CODEOWNERS`](.github/CODEOWNERS) additionally require a **code-owner**
  review. These are the sensitive surfaces: the tool contract and permission model, command /
  AWS / Docker / database execution, transport and pairing, and credential handling.
- **Review threads must be resolved** before merge.
- **Squash merge**, linear history. One commit per PR on `main`.

## Tool contracts

A tool is a capability OAK will execute against infrastructure, so a change to one is more
than an internal refactor. A new tool, or a change to an existing one, should document:

- capability name, description, provider
- inputs and outputs
- **permission class** — `READ`, `WRITE`, or `DESTRUCTIVE`
- failure semantics, timeout behaviour, retry / idempotency
- security implications

### Permission classes are security-sensitive

`READ` / `WRITE` / `DESTRUCTIVE` is the author's declaration about a tool. Nothing in OAK
enforces it (the credential granted to the process is the real boundary — see
[SECURITY.md](SECURITY.md)), which is exactly why the **declaration** must be reviewed.

A **reclassification** is a security-review item, called out in the PR and the release notes:

- `READ → WRITE` — the tool now changes state
- `WRITE → DESTRUCTIVE` — the tool now does something irreversible

### Output contract changes

Changing a tool's **output** shape is a contract change. The platform may parse it. Flag it
in the PR's "Tool contract changes" section and in the release notes.

## Cross-repository contract (Oppex ↔ OAK)

Oppex and OAK are developed in separate repositories and released on their own cadence, but
they share a contract. Changes to any of these can affect the other side and **must** be
identified in release notes when compatibility may be affected:

- tool **input**
- tool **output**
- **permission** classification
- **authentication**
- **execution protocol** (`/v1/tools` register / poll / result)
- **result protocol**

OAK restates the `/v1/tools` DTOs rather than depending on the platform's modules. If the
contract moves, regenerate the client from the platform's OpenAPI rather than hand-syncing.

## Releases

Versioning, tagging, and the release checklist live in [RELEASING.md](RELEASING.md). In
short: SemVer, tags on `main` only, nothing published automatically from an ordinary push.

## Changing this document

`GOVERNANCE.md`, `CONTRIBUTING.md`, `CODEOWNERS`, and the CI/ruleset config are owned by
maintainers (see CODEOWNERS) and change through the same PR + review flow as code.
