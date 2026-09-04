# Oppex ADK Tools (Java)

Run Oppex runbook steps on **your** infrastructure, using **your** credentials, inside a process
**you** control.

> **Status: early development.** The API will change. Not yet published to Maven Central.

## Why this exists

Oppex automates operational runbooks. Most useful steps in a real runbook need production access —
listing the instances behind an auto-scaling group, reading query logs off a database, checking
which deploy went out before a spike.

Handing that access to an outside company is not a reasonable thing to ask. So Oppex doesn't ask.
Instead it sends you the *name* of a step, and this agent runs it locally against credentials that
never leave your account.

## What it does, and what it will not do

This is the part worth reading before you run anything.

**It receives a name and some parameters.** A step looks like this on the wire:

```json
{ "taskId": 57, "capability": "FIND_ASG", "input": { "serviceKey": "payment-service" } }
```

**It never receives code.** Not a script, not a binary, not an expression to evaluate. The agent
looks `FIND_ASG` up in its own registry of locally-compiled tools and runs your code. A name it
does not implement is refused and reported back as a failure.

That means the worst thing Oppex can ask this process to do is **something you compiled into it,
with parameters you can validate**. That holds even if the Oppex platform were compromised.

**It opens no ports.** Every connection is one this process makes outbound. Port-scan the host and
you will find nothing listening. Oppex has no address for you and no way to reach you when this
isn't running.

**Its blast radius is the credential you give it.** The IAM role, database grant or token on this
process is the real boundary. The `ToolPermission` labels (`READ`/`WRITE`/`DESTRUCTIVE`) are
declarations for display and planning — they are not enforcement, and nothing on the Oppex side
can verify them. Grant least privilege and treat the labels as documentation.

## How it connects

The agent dials out and keeps the connection open. Oppex then pushes steps down it.

```
1. agent  ──▶  POST /v1/tools/register     here is what I can run
2. agent  ──▶  POST /v1/tools/connect      returns a short-lived ticket
3. agent  ──▶  opens a WebSocket
4. Oppex  ──▶  pushes a step down it       ← Oppex initiates the work
5. agent  ──▶  POST /v1/tools/steps/result what happened
```

**Why the connection stays open.** A firewall does not block incoming *data* — it blocks strangers
*starting* conversations. That's why every web page you load works: you asked for it. So a
connection your agent opens is permitted, and traffic flows both ways on it for as long as it
lives. Oppex cannot open one to you, so if this closes there is no route for a step until the agent
dials again.

If an outbound proxy inspects TLS and refuses the WebSocket upgrade, the agent **falls back to
HTTP polling automatically** and says so in the log. Steps then arrive on a timer rather than
instantly. Nothing else changes.

## Run the server

If you'd rather not write your own service:

```bash
export OPPEX_BASE_URL=https://api.oppex.example
export OPPEX_API_KEY=...            # or use a credentials file, see below
mvn -pl oppex-adk-tools-server quarkus:dev
```

It listens on nothing, so there is no port to open and no health endpoint to expose.

### Credentials

Prefer the encrypted file over an environment variable — a key in the environment shows up in
process listings and diagnostic bundles.

```java
CredentialStore.save(Path.of("/etc/oppex/agent.oppex-credentials"), apiKey, passphrase);
```

```properties
oppex.adk.credentials-file=/etc/oppex/agent.oppex-credentials
```

```bash
export OPPEX_ADK_PASSPHRASE=...     # from your init system or secret manager
```

Be clear about what this buys: the agent must decrypt unattended, so the passphrase is reachable on
the same host. **It does not protect against an attacker who already has the host.** What it does
prevent is the key ending up in a backup, a support bundle, a screen share, or an accidental
`git add` — and it keeps the ciphertext and the passphrase in different places.

## Add your own tool

Implement one interface:

```java
public class CountPendingOrders implements Capability {

    public String name() { return "COUNT_PENDING_ORDERS"; }
    public ToolPermission permission() { return ToolPermission.READ; }

    public Map<String, Object> execute(Map<String, Object> input) throws Exception {
        try (var conn = dataSource.getConnection();
             var st = conn.prepareStatement("select count(*) from orders where status = ?")) {
            st.setString(1, (String) input.get("status"));      // never interpolate — this came from outside
            var rs = st.executeQuery();
            rs.next();
            return Map.of("count", rs.getInt(1));
        }
    }
}
```

In the Quarkus server, annotate it `@ApplicationScoped` and it is picked up automatically. Using the
core jar directly, `registry.register(new CountPendingOrders())`.

Then reference `COUNT_PENDING_ORDERS` from a runbook step in Oppex.

**Guidelines that matter:**

- **Validate the input.** It arrived from outside your network. Treat it like an HTTP request body.
- **Throw to fail.** The message reaches Oppex and the runbook decides what to do. Never swallow an
  error and return an empty success — a step that lies about working is worse than one that fails,
  because the runbook carries on regardless.
- **Return facts, not prose.** `{"asgName": "payment-asg", "instanceCount": 4}` is useful to the
  next step and to an LLM. `{"summary": "looks fine"}` is useful to neither.
- **Assume it can run twice.** Steps can be retried.
- **Set timeouts.** Several steps may run at once on a shared pool.

## Built-in capabilities

| Capability | Needs | Permission |
|---|---|---|
| `FIND_ASG` | `autoscaling:DescribeAutoScalingGroups` | `READ` |

`FIND_ASG` matches on tags first (`Service`, `service`, `app`, `Application`, or one you name),
then an exact group name, then a name containing the key. It returns **every** match with a
`matchedBy` field rather than guessing — two groups matching `payment` is a real situation worth
seeing, not a tie to break silently.

The AWS SDK is an **optional** dependency of the core. If your tools are all your own, you don't
have to ship it; the built-in is skipped with a log line rather than failing to start.

## Modules

| Module | Use it when |
|---|---|
| `oppex-adk-tools` | You have a service already, or want to build your own. Framework-free: plain Java, the JDK's HTTP and WebSocket clients, and Jackson. |
| `oppex-adk-tools-server` | You want something to run. Quarkus. |

Java 17, so it runs on the JVM you already have.

## Configuration

| Key | Default | |
|---|---|---|
| `oppex.adk.base-url` | — | Required. |
| `oppex.adk.credentials-file` | — | Encrypted key file. Preferred. |
| `oppex.adk.api-key` | — | Plain key. Use the file instead where you can. |
| `oppex.adk.agent-name` | `oppex-adk-tools-server` | Keep stable across restarts — Oppex keys the registration on it. |
| `oppex.adk.transport-mode` | `AUTO` | `AUTO`, `WEBSOCKET` (fail rather than degrade), `POLL`. |
| `oppex.adk.poll-interval` | `10s` | Only used when polling. |
| `oppex.adk.worker-threads` | `4` | Concurrent steps. |
| `oppex.adk.aws-capabilities-enabled` | `true` | Register the built-ins. |

## Build

```bash
mvn clean install
```

## Licence

Apache 2.0. See [LICENSE](LICENSE).
