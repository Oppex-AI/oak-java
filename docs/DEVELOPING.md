# Developing OAK

The developer quickstart: prerequisites, build, run, test, and how to land your first change. For the
*contribution process* (fork → PR, review, tool-contract rules) see [CONTRIBUTING.md](../CONTRIBUTING.md);
for how the project is governed, [GOVERNANCE.md](../GOVERNANCE.md).

## Prerequisites

- **JDK 17** — `oak-tools` must compile and run on a customer's JVM, so 17 is the floor (newer language
  features aren't used). Check: `java -version`.
- **Maven 3.9+** — `mvn -v`.
- To actually **run tools** (not just build/test), the host needs the CLIs the built-in tools shell out
  to, on the `PATH` of the process that runs OAK:
  - **AWS CLI v2** — for the AWS and SSM tools, and discovery.
  - **Docker CLI** (+ access to a Docker daemon/socket) — for the Docker tools.
  - These are **not** needed to build or run the unit tests — only to execute real steps. The
    `oak-service` container image already ships them (see [aws-deployment.md](aws-deployment.md)).

## Get the code and build

```bash
git clone https://github.com/Oppex-AI/oak-java.git
cd oak-java
mvn clean install          # build both modules + install ai.oppex:oak-tools into ~/.m2
```

`mvn install` is enough for local work; publishing is only for releases (see [RELEASING.md](../RELEASING.md)).

## Run the service

```bash
export OAK_PLATFORM_BASE_URL=https://api.oppex.example   # optional — unconfigured, it boots idle
export OAK_PLATFORM_API_KEY=...                          # normally set from the management UI instead
mvn -pl oak-service quarkus:dev
```

Open the management UI at **http://localhost:9020/** (connection status, advertised capabilities, recent
steps, effective config). With no platform configured it still boots — idle, every tool wired. See the
[root README](../README.md) for all `oak.*` config keys, and [aws-deployment.md](aws-deployment.md) for
wiring up AWS access.

## Run the tests

```bash
mvn test                   # unit tests only (fast)
mvn verify                 # tests + the oak-service build + Spotless (enforced) + Checkstyle (report-only)
mvn -pl oak-tools test     # just one module
mvn spotless:apply         # auto-fix formatting + the Apache license header before committing
```

- Tests live under each module's `src/test/java` (`oak-tools/src/test/java`, `oak-service/src/test/java`)
  and run on JUnit 5 via Surefire.
- **`mvn verify` must pass before you push** — Spotless fails the build on a formatting or missing-header
  drift, so run `mvn spotless:apply` and commit the result. Checkstyle is report-only; still read it.
- What a change *should* cover (valid/invalid input, failures, timeouts, permission classification,
  structured output, safe-failure for destructive tools) is in
  [CONTRIBUTING.md → Testing expectations](../CONTRIBUTING.md#testing-expectations).

## Project layout

| Module | What it is |
|---|---|
| [`oak-tools`](../oak-tools/README.md) | **Framework-free core** — plain JDK 17, no external deps. The `Tool` contract, `ToolRegistry`, the CLI builder, and the generic AWS/Docker tools. |
| [`oak-service`](../oak-service/README.md) | **Quarkus executor** a customer runs — pairs with the platform, pulls steps, executes them via `oak-tools`, reports results, plus the management UI. Depends on `oak-tools`. |

Keep `oak-tools` **framework-free** (JDK only) — that's what lets it be published and drop onto any JVM.
Adding a tool? [CONTRIBUTING.md → Contributing a tool](../CONTRIBUTING.md#contributing-a-tool) and the
README's "Add your own tool" have a worked example.

## Your first change

No write access needed — everything is fork → branch → PR:

```bash
# fork Oppex-AI/oak-java on GitHub, then:
git checkout -b feat/short-description        # type: feat|fix|docs|chore|refactor|test
# ...implement...
mvn spotless:apply && mvn verify              # green locally first
git commit -am "oak-tools: add <thing>"
git push                                      # to your fork
# open a PR against main and fill in the template
```

CI runs on the PR; a maintainer (plus a security reviewer for execution/credential/transport/permission
paths) reviews; a maintainer squash-merges once it's approved and green. The full rules — branch naming,
commit style, PR expectations, security reporting — are in [CONTRIBUTING.md](../CONTRIBUTING.md).
