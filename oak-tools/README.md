# oak-tools

The framework-free core of **OAK (Oppex Agentic Kit)**: the `Tool` contract, the `ToolRegistry`, and
the generic AWS and Docker tools. Plain JDK 17, **no external dependencies** — it compiles and runs on
any consumer's JVM.

Published coordinate: **`ai.oppex:oak-tools`**.

## The contract

```java
public interface Tool {
    String capability();                         // e.g. AWS_EC2_START_INSTANCES
    ToolPermission permission();                 // READ / WRITE / DESTRUCTIVE (declaration, not enforcement)
    String description();                        // one line, for the catalog and preview
    List<String> inputKeys();                    // what this tool reads from a step's input
    String render(Map<String, Object> input);    // the command it WOULD run — preview only
    ToolResult execute(Map<String, Object> input); // actually run it → exit code / stdout / stderr
}
```

- **`CommandTool`** — base class for command-line tools. Define the command once in
  `command(input) → Cli`; you get `render()` (`build()`) and `execute()`
  (`CommandRunner.run(argv())`) from it, so the preview can never drift from what runs.
- **`Cli`** — a tiny argv builder. `build()` is the preview string; `argv()` is the exact tokens
  (a value with spaces stays one argument — never re-parse the preview).
- **`CommandRunner`** — JDK `ProcessBuilder`, no shell, streams drained concurrently, with a timeout.
  A missing binary / non-zero exit / timeout is a returned `ToolResult`, not a thrown exception.
- **`ToolRegistry`** — last-registration-wins by capability, so a layer overrides a generic tool by
  re-registering under the same capability.

## Built-in tools

Per-service subpackages under `ai.oak.tools.aws` (`ec2`, `rds`, `cloudwatch`, `s3`, `elasticache`,
`msk`) and `ai.oak.tools.docker`. Each has a `*Tools.registerAll(registry)` registrar; `AwsTools`
aggregates the AWS ones. See the [root README](../README.md#built-in-tools) for the full list.

## Use it directly

```java
ToolRegistry registry = new ToolRegistry();
AwsTools.registerAll(registry);
DockerTools.registerAll(registry);
registry.register(new MyOwnTool());              // yours wins if it reuses a capability id

ToolResult result = registry.execute("AWS_EC2_START_INSTANCES",
        Map.of("instanceIds", List.of("i-0abc"), "region", "us-west-2")).orElseThrow();
```

Keep this module framework-free (JDK only) — it is what lets it be public and drop onto any JVM.

## Contributing & licence

Part of [oak-java](../README.md). How to contribute: [CONTRIBUTING](../CONTRIBUTING.md); how the
project is run: [GOVERNANCE](../GOVERNANCE.md). Report vulnerabilities privately per
[SECURITY](../SECURITY.md) — do not open a public issue. Licensed under Apache 2.0
([LICENSE](../LICENSE)).
