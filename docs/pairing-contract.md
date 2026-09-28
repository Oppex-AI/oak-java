# OAK pairing contract — `POST /v1/tools/pair`

The wire contract a platform implements so an OAK tool service (`oak-service`) connects to it via
**approval-based pairing**. Vendor-neutral: Oppex is the first platform, but anything implementing these
shapes works. Both sides of this are implemented — Oppex `service-workflow` on the server, `oak-service`
on the client; this document is the agreement between them.

`/pair` is the only endpoint pairing adds. Once paired, a service uses the existing
`register` / `steps/next` / `steps/result` endpoints unchanged (summarised at the end).

## Model

The admin issues two things together, shown on the platform's OAK/Tools page: a **workspace client id**
(e.g. `oak_0cd990…`) and a **pre-shared pairing secret**. Both are configured into OAK. OAK proves its
identity by presenting the secret on every pairing call; an admin approves the pending connection; the
platform then hands back a **dedicated workspace API key** (`token`) which OAK uses as `X-API-KEY` on
every authenticated call.

The pairing secret is long-lived (rotate only when the admin regenerates it) and is **distinct from the
runtime token**. **The platform never dials OAK** — OAK always polls outbound.

```
OAK ──▶ POST /v1/tools/pair  CREATE {clientId, pairingSecret, name, version}
                                     → PENDING_APPROVAL {pairingId}
OAK ──▶ POST /v1/tools/pair  POLL   {clientId, pairingId, pairingSecret}   (every 3–5s, backoff)
                     …admin approves… → APPROVED {connectionId, token}
OAK ──▶ POST /v1/tools/register      (X-API-KEY: token)   … then steps/next, steps/result
```

## Auth

- `/pair` is **anonymous** — no `X-API-KEY`. Identify and rate-limit by `clientId` + source IP; the
  pre-shared secret is what authenticates the instance.
- All other endpoints require `X-API-KEY: <token>`. A `401`/`403` there means the admin disconnected
  this connection (the key was revoked): OAK discards the token and re-pairs automatically.

## Envelope

Every response uses the platform's standard `APIResponse<T>`:

```json
{ "success": true, "code": 200, "message": null, "data": { "...": "..." } }
```

All pairing states are **HTTP 200** with a non-null `data`. Reserve non-2xx for genuine transport /
rate-limit failures — OAK treats those as transient and retries.

## Request — `PairRequest`

`{ clientId, name, version, pairingId, pairingSecret }` (OAK omits null fields on the wire). Two forms:

**CREATE** — no `pairingId`:

```json
{ "clientId": "oak_0cd990…", "pairingSecret": "<pre-shared secret>", "name": "oak-service", "version": "0.1.0" }
```

**POLL** — every subsequent call, until it resolves:

```json
{ "clientId": "oak_0cd990…", "pairingId": "<from CREATE>", "pairingSecret": "<pre-shared secret>" }
```

`clientId` alone never yields a token — the secret is required and verified on **every** call.

## Response — `PairResponse`

`{ status, pairingId, connectionId, token }` — the server does **not** return a secret (OAK already holds
the pre-shared one).

| `status` | When | Includes | OAK's action |
|---|---|---|---|
| `PENDING_APPROVAL` | CREATE accepted, or still awaiting the admin | on CREATE: `pairingId` | persist `pairingId`, POLL (3–5s → ~30s backoff) |
| `APPROVED` | admin approved | `connectionId` + `token` (**first approved poll only**) | persist the token (encrypted), register, poll for steps |
| `EXPIRED` | the pending request timed out (~15 min) | — | discard `pairingId`, CREATE again |
| `DISCONNECTED` | admin disconnected this connection | — | stop and surface; re-pair only on operator action |
| `REJECTED` | unknown/disabled client id, or wrong secret | — | fail with a clear message; do **not** retry blindly |
| `UNKNOWN` | fallback | — | treat as a failure; an unrecognised status is mapped here, never thrown |

`token` appears **only on the first approved poll** — persist it immediately.

### Example responses

```json
{"success":true,"code":200,"data":{"status":"PENDING_APPROVAL","pairingId":"pr_abc"}}
{"success":true,"code":200,"data":{"status":"PENDING_APPROVAL"}}
{"success":true,"code":200,"data":{"status":"APPROVED","connectionId":"conn_42","token":"<dedicated workspace API key>"}}
```

## Server-side requirements

- Store the pairing secret **hashed**; verify it in constant time on every CREATE and POLL. Never log it.
- **`token` is a dedicated workspace API-key row** scoped to the client id's workspace, separate from any
  incident-ingest key — so disconnecting the executor revokes only this.
- Track token delivery: the token is returned **once**. If OAK crashes before persisting it, the admin
  disconnects the stuck connection and OAK re-pairs — never silently reissue for the same pairing.
- Pending pairings **expire** (~15 min); cap pending pairings per workspace.
- **Rate-limit** the anonymous `/pair` endpoint by `clientId` + source IP.

## Client behaviour (for reference)

Implemented in `oak-service` (`ai.oak.service.connection.PlatformConnection`):

- Configured with base URL + client id + pairing secret (`oak.platforms.<name>.*`, or the management
  page). Pairing is the only way to a token — there is no pre-set-key bypass.
- CREATE when it has no `pairingId`; otherwise POLL, backing off 5s → 30s.
- `APPROVED` → persist token + connectionId (encrypted), register, poll for steps.
- `EXPIRED` → re-CREATE. `REJECTED`/`UNKNOWN` → fail and surface (no blind retry). `DISCONNECTED` →
  surface and wait for the operator to re-pair. A `401` on register/steps → discard token, auto re-pair.
- Secrets (pairing secret, token) are stored encrypted (`SecretStore`, AES-256-GCM) and never logged;
  ids are masked.

## Unchanged endpoints (post-pairing, `X-API-KEY: <token>`)

- `POST /v1/tools/register` — `{ name, version, capabilities: [ { capability, permission, description } ] }`
- `GET  /v1/tools/steps/next` — returns `200` with `data: null` when idle
- `POST /v1/tools/steps/result` — `{ workflowId, taskId, status, output, errorMessage }` (`status` is
  `SUCCESS` or `FAILED`)
