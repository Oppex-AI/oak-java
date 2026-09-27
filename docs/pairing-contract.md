# OAK pairing contract — `POST /v1/tools/pair`

The wire contract a platform must implement so an OAK tool service (`oak-service`) can connect to it via
**approval-based pairing**. It is vendor-neutral: Oppex is the first platform, but anything implementing
these shapes works. The client side is already implemented in `oak-service`; this document is the
agreement the **server** side builds against.

`/pair` is the only endpoint pairing adds. Once a service is paired it uses the existing
`register` / `steps/next` / `steps/result` endpoints unchanged (summarised at the end).

## Model

A tool instance proves its identity with a one-time `pairingSecret`, an admin approves the pairing on
the platform's "OAK Connection" page, and the instance receives a **dedicated workspace API key**
(`token`) which it then sends as `X-API-KEY` on every authenticated call.

**The platform never dials the tool service** — the service always polls outbound. Authentication is
one-directional.

```
service ──▶ POST /v1/tools/pair  (FIRST)   → PENDING_APPROVAL {pairingId, pairingSecret}
service ──▶ POST /v1/tools/pair  (POLL, 5s→30s backoff)
                     …admin approves…       → APPROVED {connectionId, token}
service ──▶ POST /v1/tools/register         (X-API-KEY: token)
service ──▶ GET  /v1/tools/steps/next       (X-API-KEY: token)
service ──▶ POST /v1/tools/steps/result     (X-API-KEY: token)
```

## Auth

- `/pair` takes **no `X-API-KEY`** — it is how the service earns one. Identify and rate-limit by
  `clientId` + source IP.
- All other endpoints require `X-API-KEY: <token>`. A `401`/`403` on any of them means the admin
  disconnected this connection: the service discards the token and re-pairs.

## Envelope

Every response uses the platform's standard `APIResponse<T>` envelope:

```json
{ "success": true, "code": 200, "message": null, "data": { "...": "..." } }
```

For the normal pairing states, **return HTTP 200 with a non-null `data`** (the client treats a null
`data` on a 2xx as an error). Reserve non-2xx for genuine transport / auth / rate-limit failures — the
client logs those and retries after ~5s.

## Request — `PairRequest`

The client omits null fields on the wire, so there are two shapes:

**FIRST** — the service has no pairing yet:

```json
{ "clientId": "<workspace OAK client id>", "name": "oak-service", "version": "0.1.0" }
```

**POLL** — every subsequent call, until the pairing resolves:

```json
{ "clientId": "<same client id>", "pairingId": "<from FIRST>", "pairingSecret": "<from FIRST>" }
```

**Only `clientId` + `pairingId` + `pairingSecret` together may claim the token.** `clientId` alone must
not.

## Response — `PairResponse`

```
{ status, pairingId?, pairingSecret?, connectionId?, token? }
```

| `status` | When | Must include | What the client does |
|---|---|---|---|
| `PENDING_APPROVAL` | FIRST accepted, or still awaiting the admin | on FIRST: `pairingId` + `pairingSecret` (**returned once**) | persist the secret encrypted, then POLL with 5s→30s backoff |
| `APPROVED` | admin approved | `connectionId` + `token` (**returned once**) | persist the token encrypted, register, start polling for steps |
| `EXPIRED` | the pending pairing timed out (~15 min) | — | discard `pairingId`/`pairingSecret`, start a fresh FIRST |
| `REJECTED` | admin declined | — | discard and start a fresh FIRST |
| `DISCONNECTED` | admin disconnected this connection | — | discard and start a fresh FIRST |
| `UNKNOWN` | fallback | — | discard and start a fresh FIRST |

All of these are **HTTP 200**. An unrecognised status string is treated by the client as `UNKNOWN`
rather than an error, so adding a new status can never strand an older client. `pairingSecret` and
`token` are each returned **exactly once** — the client persists them the moment it receives them.

### Example responses

FIRST accepted:

```json
{"success":true,"code":200,"data":{"status":"PENDING_APPROVAL","pairingId":"pr_abc","pairingSecret":"ps_xyz"}}
```

POLL, still pending:

```json
{"success":true,"code":200,"data":{"status":"PENDING_APPROVAL"}}
```

POLL, approved:

```json
{"success":true,"code":200,"data":{"status":"APPROVED","connectionId":"conn_42","token":"<dedicated workspace API key>"}}
```

## Server-side requirements

- **`token` is a dedicated workspace API-key row** scoped to the workspace the `clientId` belongs to,
  separate from any incident-ingest key — so disconnecting the executor revokes only this.
- **Track `token_delivered` per pairing.** If the service crashes before persisting the token (it never
  polls again), the admin disconnects the stuck connection and the service re-pairs — never silently
  reissue the token for the same pairing.
- **Pending pairings expire** (~15 min).
- **Rate-limit** the anonymous `/pair` endpoint by `clientId` + source IP.
- **`pairingSecret` is a credential** — store it hashed, compare in constant time, and never log it.

## Client behaviour (for reference)

Implemented in `oak-service` (`ai.oak.service.connection.PlatformConnection`):

- Sends FIRST when it has no stored pairing; otherwise POLLs. Backoff doubles 5s → 30s (cap).
- On `APPROVED`, persists `token` + `connectionId` (encrypted at rest), then registers and polls.
- On any terminal status, discards the pairing and starts a fresh FIRST after a short delay.
- On a `401`/`403` on an authenticated call, discards the token and re-pairs.
- Secrets are stored encrypted (`SecretStore`, AES-256-GCM) and never logged; ids are masked in logs.

## Unchanged endpoints (post-pairing, `X-API-KEY: <token>`)

- `POST /v1/tools/register` — `{ name, version, capabilities: [ { capability, permission, description } ] }`
- `GET  /v1/tools/steps/next` — returns `200` with `data: null` when idle
- `POST /v1/tools/steps/result` — `{ workflowId, taskId, status, output, errorMessage }` (`status` is
  `SUCCESS` or `FAILED`)
