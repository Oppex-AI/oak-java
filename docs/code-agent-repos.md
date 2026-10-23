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

## Incident fixes

`CODE_INCIDENT_FIX` (an OAK tool) forwards a step to `POST /v1/code/incident-fix` on the same agent. The
step carries the `repoUrl`; the agent resolves it against the repositories it already holds (configured
above) and works on its own checkout. OAK adds no local path — it holds no checkout.
