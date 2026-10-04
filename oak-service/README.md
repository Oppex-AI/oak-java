# oak-service

The customer-side executor for **OAK (Oppex Agentic Kit)**: a Quarkus app you run inside your network
that registers with the orchestration platform, pulls steps, runs them via [`oak-tools`](../oak-tools),
and reports results — plus a small management UI. It opens outbound connections only (the UI is the
one thing it listens on).

## The loop

```
register  →  poll (/steps/next)  →  execute via ToolRegistry  →  report (/steps/result)
```

The client (`ToolServiceClient`) speaks the `/v1/tools` protocol with `X-API-KEY`. It is
**vendor-neutral**: the platform is a *configured* backend (`oak.platform.*`), not hardcoded — Oppex
is the first one. The wire DTOs are restated here (no dependency on the platform's internal modules);
if the two drift, regenerate this client from the platform's OpenAPI.

## Run

```bash
export OAK_PLATFORM_BASE_URL=https://api.oppex.example
export OAK_PLATFORM_API_KEY=...
mvn -pl oak-service quarkus:dev
```

Unconfigured, it boots idle (tools wired, not polling) rather than failing. See the
[root README](../README.md#configuration) for all `oak.*` keys.

## Container image

`src/main/docker/Dockerfile.jvm` ships the **`aws` and `docker` CLIs** in the image — a runtime
contract, because the built-in tools shell out to them (ProcessBuilder, no SDK). Versions are pinned
via `ARG` (`AWS_CLI_VERSION`, `DOCKER_CLI_VERSION`); bump them intentionally. Build from the repo root:

```bash
docker build -f oak-service/src/main/docker/Dockerfile.jvm -t oak-service .
```

When you run it:

- **Persist settings** — mount a volume and set `OAK_DATA_DIR` to it, or a restart loses the pairing
  token and regenerates the AWS external ID (`-v oak-data:/data -e OAK_DATA_DIR=/data`).
- **Docker capabilities** require the **host docker socket** mounted — a privileged deployment mode:
  `-v /var/run/docker.sock:/var/run/docker.sock` plus `--group-add $(stat -c '%g' /var/run/docker.sock)`
  so the non-root service user can use it.
- **AWS capabilities** assume a role via the host's identity; on EC2 the container must reach IMDS, so
  set the instance metadata hop limit to 2 (otherwise credentials can't be fetched from inside the
  container). See [AWS deployment](../docs/aws-deployment.md).

## Management UI

**http://localhost:9020/** — connection status and last-seen, advertised capabilities, recent steps
and outcomes, and the effective config. Read-only; never shows the API key. `GET /api/status` returns
the same as JSON. Use `OAK_UI_HOST=127.0.0.1` / `OAK_UI_PORT` to bind it down on a shared host.

## Add your own tool

Annotate a `Tool` implementation `@ApplicationScoped` and it is registered automatically after the
built-ins (so reusing a built-in capability id overrides it). See
[Add your own tool](../README.md#add-your-own-tool).

## Contributing & licence

Part of [oak-java](../README.md). How to contribute: [CONTRIBUTING](../CONTRIBUTING.md); how the
project is run: [GOVERNANCE](../GOVERNANCE.md). Report vulnerabilities privately per
[SECURITY](../SECURITY.md) — do not open a public issue. Licensed under Apache 2.0
([LICENSE](../LICENSE)).
