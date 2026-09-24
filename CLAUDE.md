# oak-java — CLAUDE.md (kickoff)

> Copy this into the **`oak-java`** repo as its root `CLAUDE.md`. It is self-contained — a fresh
> Claude terminal here needs nothing from oppex-ai except the two seams described below.

## What this repo is

The **open-source tools SDK** for Oppex runbook automation. Private in the `Oppex-AI` org for now;
public + Maven Central later, once the tool contract is stable. Java, Maven, multi-module,
Apache-2.0.

The one sentence that explains everything: **the tool CODE is a single implementation that runs on two
sides — Oppex's own infrastructure and the customer's — and the Oppex runbook planner decides which
running instance executes each step.** This repo is that single implementation, plus the small service
a customer runs to execute steps on their side. **The customer always calls Oppex; Oppex never dials
into the customer's network.**

## Current state (already scaffolded — do not start from scratch)

The repo already exists with a scaffold from ~3 weeks ago: two modules named **`oppex-adk-tools`** (the
core library) and **`oppex-adk-tools-server`** (the Quarkus server), a `FIND_ASG` starting tool,
Apache-2.0 `LICENSE`, `SECURITY.md`, `.gitignore`, and a root `pom.xml`.

### ⭐ First task — rename the two modules

Rename **`oppex-adk-tools` → `oak-tools`** and **`oppex-adk-tools-server` → `oak-service`** (decided by
the team). From the repo root:

```bash
git mv oppex-adk-tools        oak-tools
git mv oppex-adk-tools-server oak-service
# ORDER MATTERS: replace the longer name first (macOS/BSD sed → note the '' after -i)
sed -i '' -e 's/oppex-adk-tools-server/oak-service/g' -e 's/oppex-adk-tools/oak-tools/g' \
  pom.xml oak-tools/pom.xml oak-service/pom.xml
git diff          # eyeball before committing
mvn -q -DskipTests validate
git add -A && git commit -m "Rename modules: oak-tools, oak-service"
```

Before committing, check two things in the diff:
- the **parent/root `<artifactId>`** (if it was `oppex-adk-tools-parent`, sed made it
  `oak-tools-parent` — keep only if you want that; it is not published),
- **`groupId`** — keep it **`ai.oppex`**, so the published coordinate is **`ai.oppex:oak-tools`**
  (that is what the oppex-ai consumer and the dummy-customer repo will reference).

## Target modules (after the rename)

```
oak-java/
├── oak-tools    FRAMEWORK-FREE core. Plain JDK, Java 17, NO Quarkus/CDI, no external deps.
│               Package: ai.oppex.oak.tools
│               The Tool contract + ToolRegistry + the generic tools (AWS/docker/...).
└── oak-service  Quarkus service a customer runs + a small UI. Depends on oak-tools.
                Registers with Oppex, pulls steps, EXECUTES them, reports results.
```

### `oak-tools` (the core) — merge in the code that already exists in oppex-ai

A more complete version of this core was built in **oppex-ai's `core-tools`** module (same package
`ai.oppex.oak.tools`, so it drops in with no import churn). Bring it into `oak-tools` and reconcile with
the existing `FIND_ASG` scaffold. What it has:
- `Tool` — `capability()`, `permission()` (READ/WRITE/DESTRUCTIVE), `description()`, `inputKeys()`,
  `render(Map input)` → the command the step would run.
- `ToolRegistry` — last-registration-wins (a layer overrides a generic tool by re-registering the same
  capability).
- `cli.Cli` — fluent command builder (`aws`, `docker`; opt/optList/required/flag/positional).
- 34 generic tools: **AWS** EC2(5)/RDS(6)/CloudWatch(4)/S3(4)/ElastiCache(4)/MSK(5) + **Docker**(6),
  each a `render`-only command builder, registered via per-service registrars
  (`Ec2Tools`/`RdsTools`/.../`DockerTools`).

**The one thing to add here: `Tool.execute(Map input) -> ToolResult`.** Today a tool only *renders* the
command (for Oppex's verify/preview step). To actually run it, add `execute` — for AWS/docker tools that
means running the built command (ProcessBuilder) or the AWS SDK, capturing stdout/stderr/exit. Keep
`render` (the preview) and add `execute` (the real thing) side by side; both sides call the same code, so
Oppex-side and customer-side execution are identical by construction. **Keep oak-tools framework-free and
Java 17** — it compiles on a customer's JVM.

Rules for this module (do not break — they are what let it be public):
- No Quarkus, no CDI, no Jackson-required core (a tool works on `Map<String,Object>` — JSON is the
  service's concern). Java 17 source.
- A tool is generic. Anything Oppex-specific stays out of here (it lives in the Oppex layer inside
  oppex-ai). Customer-specific config (an AWS role, a bucket, a docker host) is passed in, never baked.

### `oak-service` (the customer-side executor + UI)

A Quarkus app the customer deploys inside their network. The loop (all HTTP, customer → Oppex):

1. **Register** — `POST {oppex}/v1/tools/register` with `{name, version, capabilities:[{capability,
   permission, description}]}`. The capabilities are the tools this instance provides (a subset of
   oak-tools + any customer tools). Auth: `X-API-KEY` (its OWN Oppex API-key row — see auth below).
2. **Poll** — `GET {oppex}/v1/tools/steps/next` → `{workflowId, taskId, capability, input,
   referenceType, referenceId, sequenceOrder}` or `200` with null when idle. (WebSocket push is a later
   optimisation; poll is the baseline.)
3. **Execute** — resolve `capability` in the oak-tools registry, call `execute(input)`.
4. **Report** — `POST {oppex}/v1/tools/steps/result` with `{workflowId, taskId, status, output,
   errorMessage}` (`status` is Oppex's `TaskStatus`: SUCCESS / FAILED).

**Restate the client + DTOs here — do NOT depend on oppex-ai's `core-spi`** (cross-repo boundary; the
"SDK restates DTOs" rule). If the two drift, generate this client from oppex-ai's OpenAPI rather than
hand-syncing. The endpoint shapes above come from oppex-ai `RemoteToolEndpoint` +
`core-api/.../workflow/{RemoteStep,RemoteStepResultRequest,ToolServiceRegistrationRequest,...}`.

**Auth:** an Oppex **workspace API key** minted for this tool service (its own row, separate from any
incident-ingest key, so revoking the executor doesn't stop ingestion). Config: Oppex base URL, the API
key, which capabilities to advertise, and per-tool credentials the tools need (AWS profile/role, docker
socket, etc.).

**The small UI:** manage/monitor this instance — connection status + last-seen, the advertised
capabilities, recent steps received and their results, and the config (Oppex URL / key / tool creds).
Think "is my executor healthy and what has it run", not a full product UI.

## Publishing — GitHub Packages + local `~/.m2`

- **Local dev:** `mvn install` `oak-tools` → `~/.m2`; oppex-ai and dummy-customer build against it
  locally. No publish needed to iterate.
- **CI/deploy:** publish `ai.oppex:oak-tools` to GitHub Packages. Root `pom.xml`:
  ```xml
  <distributionManagement>
    <repository><id>github</id><url>https://maven.pkg.github.com/Oppex-AI/oak-java</url></repository>
  </distributionManagement>
  ```
  Consumers add that repo + a `~/.m2` `settings.xml` server `github` (a PAT with `read:packages`).
- Maven Central: later, when public + stable.

## First tasks (ordered)

1. **Rename the modules** to `oak-tools` / `oak-service` (commands above); confirm `mvn validate`.
2. Ensure GitHub Packages `distributionManagement` + `mvn install` of `oak-tools` into `~/.m2` works.
3. Bring `oak-tools` up to the oppex-ai `core-tools` set (34 tools; package unchanged) and reconcile
   with the existing `FIND_ASG` tool.
4. Add `Tool.execute(...)` + a `ToolResult`, and real executors for the AWS + Docker tools.
5. Build `oak-service`: the register → poll → execute → report loop against a running Oppex (nightly),
   API-key auth, config.
6. The management UI.

## Seams to keep stable
- **oak-tools API** (`Tool`/`ToolRegistry`) — also consumed by oppex-ai. Version it; a change ripples.
- **`/v1/tools` contract** — owned by oppex-ai. This service restates it; regenerate from OpenAPI if it
  moves.

## Design authority
Confluence **366051329** (design/why) and **367165442** (LLD). Record only implementation state here,
not the design.
