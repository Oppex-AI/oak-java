# Code Agent — repository contract

The **Code Agent** is a separate service that proposes code fixes as pull requests. It may run on the
same host as OAK or on another host entirely. It does **all** code handling: it pulls repositories and
holds the git / GitHub credentials. **OAK only manages settings** — where the agent is, and which
repositories it should pull — and relays those to the agent over HTTP. OAK stores no checkout and no
credentials.

OAK reaches the agent at a base URL that is an operator setting (`oak.code-agent.base-url`, overridable
from the management UI under **Settings → Code Agent**; default `http://localhost:9030`). The endpoints
below are what the agent implements; OAK is only a client.

## Repository management

### `POST /v1/code/repos`

Register a repository (or re-point an existing one). The agent **starts pulling asynchronously** and
returns promptly.

```json
// request
{ "url": "https://github.com/acme/widgets.git", "branch": "main" }   // branch may be null → repo default

// response
{ "slug": "acme_widgets", "url": "https://github.com/acme/widgets.git", "branch": "main",
  "status": "CLONING", "commit": "", "detail": "pull started", "updatedAt": "2026-10-23T12:00:00Z" }
```

### `GET /v1/code/repos`

List the repositories the agent holds and their current state.

```json
{ "repos": [
  { "slug": "acme_widgets", "url": "https://github.com/acme/widgets.git", "branch": "main",
    "status": "READY", "commit": "abc1234", "detail": "", "updatedAt": "2026-10-23T12:01:10Z" }
] }
```

### `POST /v1/code/repos/refresh` — `{ "slug": "acme_widgets" }`

Pull the repository again. Returns the updated entry (or `{ "status": "CLONING" }`).

### `POST /v1/code/repos/delete` — `{ "slug": "acme_widgets" }`

Forget the repository (and drop the agent's local checkout).

### `status` values

| status    | meaning                                             |
|-----------|-----------------------------------------------------|
| `NONE`    | registered, not pulled yet                          |
| `CLONING` | a pull is in progress (OAK's UI polls until it settles) |
| `READY`   | a checkout exists; ready for a fix                  |
| `FAILED`  | the last pull failed; `detail` says why             |

OAK's UI shows `READY` as the "repo is ready" confirmation and surfaces `FAILED` with the agent's
`detail`. Access problems (a private repo the agent can't reach) are fixed on the **agent's** side — that
is where the credentials live.

## Model credentials

The agent calls a model (Claude now; Codex etc. later) and **holds the credentials**. A credential is a
**provider** — there is no default; a backend with no valid provider selection fails incident-fix with
`ERROR`. OAK's Settings page is a **write-only** way to set one: OAK forwards the choice (and a key, for a
key-based provider) and **never persists or returns the key**. The agent stores it. `GET` reports only what
is configured — never the key.

**Providers**

| provider         | backend  | apiKey                                          |
|------------------|----------|-------------------------------------------------|
| `claude-api-key` | `claude` | required                                        |
| `aws-bedrock`    | `claude` | omit (keyless — uses the host's AWS role / SSO) |
| `codex-api-key`  | `codex`  | required                                        |

### `GET /v1/code/credentials`

```json
{ "providers": [ "claude-api-key", "aws-bedrock", "codex-api-key" ],
  "configured": [ { "backend": "claude", "provider": "claude-api-key", "configured": true,
                    "updatedAt": "2026-10-23T12:00:00Z" } ] }
```

### `POST /v1/code/credentials`

```json
{ "backend": "claude", "provider": "claude-api-key", "apiKey": "sk-ant-…" }      // key-based provider
{ "backend": "claude", "provider": "aws-bedrock" }                               // keyless — omit apiKey

{ "ok": true, "backend": "claude", "provider": "claude-api-key", "configured": true } // never echo the key
```
- Missing/unknown `provider`, a provider used for the wrong `backend`, or a blank `apiKey` for a key-based
  provider → `400 { "ok": false, "error": "…" }`, nothing stored. OAK validates the same rules before relaying.
- Replacing the selection is just another `POST` — this is how an operator rotates a key or switches provider.

### `POST /v1/code/credentials/delete` — `{ "backend": "claude" }`

Remove the stored credential for that backend.

> Transport: if the agent is on another host, serve it over **HTTPS** — the key crosses the network on
> `POST /v1/code/credentials`. The agent must never log the key or return it from any endpoint.

## Incident fixes

`CODE_INCIDENT_FIX` (an OAK tool) forwards a step to `POST /v1/code/incident-fix` on the same agent. The
step carries the `repoUrl`; the agent resolves it against the repositories it already holds (configured
above) and works on its own checkout. OAK adds no local path — it holds no checkout.

### Response

```json
{ "outcome": "PR_CREATED", "prUrl": "https://github.com/acme/widgets/pull/42",
  "branch": "fix/INC-123", "commitSha": "abc1234",
  "rationale": "why this change", "detail": "any extra context",
  "usage": { "inputTokens": 1200, "outputTokens": 300, "totalTokens": 1500,
             "cacheReadInputTokens": 800, "cacheCreationInputTokens": 0 } }
```

`usage` (optional) is the model token usage for the run. OAK forwards it **verbatim** under `output.usage`
for every outcome (a run spends tokens whether it opens a PR, finds no fix, or errors) — keys pass through
unchanged, so new usage fields need no OAK change.

`outcome` is the agent's **verdict**, and is distinct from a transport error:

| outcome         | meaning                                                   | OAK → platform            |
|-----------------|-----------------------------------------------------------|---------------------------|
| `PR_CREATED`    | a fix was proposed as a PR (`prUrl` set)                  | SUCCESS, `fixApplied=true`  |
| `NO_FIX_NEEDED` | analysed, deliberately changed nothing (`rationale` says why) | SUCCESS, `fixApplied=false` |
| `ERROR`         | could not do the work — bad input, clone/model failure    | FAILED (`CODE_FIX_ERROR`) |

A **missing/blank** `outcome` is a failure (`CODE_AGENT_NO_OUTCOME`) — the agent must state a verdict.
`NO_FIX_NEEDED` is **not** `ERROR`: "I looked and nothing needs changing" is a successful step, not a fault.
Reserve `ERROR` for cases where the agent genuinely couldn't complete the analysis. OAK forwards unknown
non-`ERROR` outcomes as success (forward-compatible) and adds a human `summary` for the platform to display.
