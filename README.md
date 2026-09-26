# OAK — Oppex Agentic Kit (Java)

Run runbook steps on **your** infrastructure, using **your** credentials, inside a process **you**
control.

> **Status: early development.** The API will change. Not yet published to Maven Central (available
> from GitHub Packages — see [Publishing](#publishing)).

OAK is the open-source tools SDK for runbook automation. The tool code is a single implementation
that runs on two sides — the orchestration platform's infrastructure and yours — and the platform's
planner decides which running instance executes each step. This repo is that implementation, plus a
small service you run to execute steps on your side. **You always call the platform; the platform
never dials into your network.** The first supported platform is Oppex; it is a *configured* backend,
not a hardcoded one.

## Why this exists

Most useful steps in a real runbook need production access — listing the instances behind an
auto-scaling group, reading query logs off a database, restarting a container. Handing that access to
an outside company is not a reasonable thing to ask. So OAK doesn't ask. The platform sends you the
*name* of a step, and this service runs it locally against credentials that never leave your account.

## What it does, and what it will not do

**It receives a name and some parameters.** A step looks like this on the wire:

```json
{ "workflowId": 42, "taskId": 57, "capability": "AWS_EC2_START_INSTANCES",
  "input": { "instanceIds": ["i-0abc"], "region": "us-west-2" } }
```

**It never receives code.** Not a script, not a binary, not an expression to evaluate. The service
looks the capability up in its own registry of locally-compiled tools and runs your code. A name it
does not implement is refused and reported back as a failure. The worst thing the platform can ask
this process to do is **something you compiled into it, with parameters you can validate** — and that
holds even if the platform were compromised.

**It opens no ports for the platform.** Every connection to the platform is one this process makes
outbound. The one thing it does listen on is the local [management UI](#management-ui).

**Its blast radius is the credential you give it.** The IAM role, database grant or token on this
process is the real boundary. The `ToolPermission` labels (`READ`/`WRITE`/`DESTRUCTIVE`) are
declarations for display and planning — not enforcement, and nothing on the platform side can verify
them. Grant least privilege and treat the labels as documentation.

## Render and execute

Every tool does two things from one definition:

- **`render(input)`** builds the exact command it *would* run — a preview used to verify a generated
  runbook before anything happens.
- **`execute(input)`** actually runs it and returns a `ToolResult` (exit code, stdout, stderr).

Both sides — platform and customer — call the same `execute`, so a step runs identically wherever it
lands. Command-line tools extend `CommandTool` and define their command once; the preview and the
real run can never drift apart. Execution is plain JDK `ProcessBuilder` (no shell, no cloud SDK), so
the built-in tools invoke the `aws` and `docker` CLIs you already have, using *their* configured
credentials.

## How it connects

The service loops, dialling out every time:

```
1. service ──▶ POST /v1/tools/register       here is what I can run
2. service ──▶ GET  /v1/tools/steps/next      anything for me?  (poll; empty when idle)
3. service      runs the tool locally          resolve capability → execute(input)
4. service ──▶ POST /v1/tools/steps/result    what happened (SUCCESS / FAILED + output)
```

Authentication is one-directional: the service authenticates to the platform with a workspace API
key (`X-API-KEY`); the platform has no address for you and no way to reach you when this isn't
running. Polling is the baseline; a WebSocket push path is a later optimisation.

## Run the service

```bash
export OAK_PLATFORM_BASE_URL=https://api.oppex.example
export OAK_PLATFORM_API_KEY=...
mvn -pl oak-service quarkus:dev
```

With no platform configured it still boots — it stays idle and says so, with every tool wired and
ready. The only thing it listens on is the management UI.

### Management UI

Open **http://localhost:9020/** for a read-only monitor: connection status and last-seen, the
advertised capabilities, recent steps and their outcomes, and the effective config. It never shows
the API key — only whether one is set. `GET /api/status` returns the same data as JSON. Bind it to
localhost on a shared host with `OAK_UI_HOST=127.0.0.1`.

## Built-in tools

34 generic tools, each a command builder over the `aws` / `docker` CLIs:

| Service | Capabilities |
|---|---|
| EC2 | describe instances, describe instance status, start, stop, reboot |
| RDS | describe DB instances, describe events, start, stop, reboot, create snapshot |
| CloudWatch | describe alarms, get metric statistics, logs filter, logs tail |
| S3 | list buckets, list objects, head object, delete object |
| ElastiCache | describe cache clusters, describe replication groups, describe events, reboot cache cluster |
| MSK | list clusters, describe cluster, list nodes, get bootstrap brokers, reboot broker |
| Docker | ps, inspect, logs, start, stop, restart |

Turn a set off with `oak.tools.aws-enabled=false` / `oak.tools.docker-enabled=false` to advertise
only your own tools.

## Add your own tool

Implement the `Tool` contract — `render` for the preview, `execute` for the real run:

```java
public final class DiskUsageTool implements Tool {

    public String capability()  { return "DISK_USAGE"; }
    public ToolPermission permission() { return ToolPermission.READ; }
    public String description() { return "Disk usage for a path."; }
    public List<String> inputKeys() { return List.of("path"); }

    public String render(Map<String, Object> input) {
        return "du -sh " + input.getOrDefault("path", "<path>");
    }

    public ToolResult execute(Map<String, Object> input) {
        return CommandRunner.run(List.of("du", "-sh", String.valueOf(input.get("path"))));
    }
}
```

In `oak-service`, annotate it `@ApplicationScoped` and it is registered automatically (after the
built-ins, so naming yours the same as a built-in overrides it). Using `oak-tools` directly, call
`registry.register(new DiskUsageTool())`. Then reference `DISK_USAGE` from a runbook step.

**Guidelines that matter:**

- **Validate the input.** It arrived from outside your network. Treat it like an HTTP request body —
  never interpolate a value into a shell string (that is why `execute` uses an argv, not a shell).
- **A non-zero exit is a normal outcome**, reported as FAILED with its output. Reserve exceptions for
  a genuine inability to attempt the work.
- **Assume it can run twice.** Steps can be retried. **Set timeouts** — several steps may run at once.

## Modules

| Module | Use it when |
|---|---|
| `oak-tools` | You have a service already, or want to build your own. Framework-free: plain JDK 17, no external dependencies. The `Tool` contract, `ToolRegistry`, and the generic AWS/Docker tools. |
| `oak-service` | You want something to run. A Quarkus executor plus the management UI. |

## Configuration

All under the `oak` prefix (`oak-service`):

| Key | Default | |
|---|---|---|
| `oak.platform.name` | `platform` | Which backend this is, for logs and the UI (set `OAK_PLATFORM_NAME=oppex`). |
| `oak.platform.base-url` | — | The platform base URL. Absent → the executor stays idle. |
| `oak.platform.api-key` | — | Workspace API key, sent as `X-API-KEY`. |
| `oak.service.name` | `oak-service` | Reported at registration; keep it stable across restarts. |
| `oak.service.version` | `0.1.0` | Reported at registration. |
| `oak.poll.interval` | `10s` | How often to ask for the next step. |
| `oak.poll.worker-threads` | `4` | Concurrent steps. |
| `oak.tools.aws-enabled` | `true` | Advertise the built-in AWS tools. |
| `oak.tools.docker-enabled` | `true` | Advertise the built-in Docker tools. |

## Build

```bash
mvn clean install       # build + install ai.oppex:oak-tools into ~/.m2
mvn verify              # + Spotless (formatting & Apache header) and Checkstyle
mvn spotless:apply      # auto-fix formatting and headers before committing
```

The project uses the shared Oppex Eclipse formatter (`config/eclipse-java-formatter.xml`); Spotless
enforces it and the Apache 2.0 header on every source file, and Checkstyle reports on the Oppex
ruleset.

## Publishing

Published to GitHub Packages as **`ai.oppex:oak-tools`**. Consumers add the repository and a
`~/.m2/settings.xml` server `github` (a PAT with `read:packages`):

```xml
<repository>
  <id>github</id>
  <url>https://maven.pkg.github.com/Oppex-AI/oak-java</url>
</repository>
```

For local development no publish is needed — `mvn install` puts it in `~/.m2`.

## Licence

Apache 2.0. See [LICENSE](LICENSE).
