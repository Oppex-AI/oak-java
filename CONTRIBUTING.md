# Contributing to OAK

Thanks for wanting to help. OAK executes real operations against real infrastructure, so
we hold the bar a little higher than a typical library — especially for anything touching
execution, credentials, transport, or a tool's permission class. This page is how to work
with that bar without it getting in your way.

## The flow

You do **not** need write access to this repository. Everything goes through a fork and a PR:

```
fork  →  feature branch  →  implement  →  run tests locally  →  open PR
                                                                   ↓
                                            CI  →  maintainer / code-owner review  →  merge
```

1. **Fork** `Oppex-AI/oak-java` to your account.
2. **Branch** off `main`: `git checkout -b <type>/<short-description>`.
3. **Implement**, keeping the change focused — one concern per PR.
4. **Run the build locally** (below) until it's green, including formatting.
5. **Open a PR** against `main`. Fill in the PR template.
6. **CI runs** and a maintainer (plus a security reviewer for sensitive paths) reviews.
7. A maintainer **squash-merges** once it's approved and green.

## Supported Java and build

> First time in the codebase? [docs/DEVELOPING.md](docs/DEVELOPING.md) is the quickstart —
> prerequisites, clone, build, run, test, and project layout.

- **Java 17.** The `oak-tools` core must compile and run on a customer's JVM, so 17 is the
  floor and we don't use newer language features.
- Build and test everything:

  ```bash
  mvn verify          # compile + unit tests + oak-service build + Spotless + Checkstyle
  mvn spotless:apply  # auto-fix formatting + the Apache header before you commit
  mvn clean install   # also installs ai.oppex:oak-tools into your ~/.m2
  ```

- **`mvn verify` must pass before you push.** Spotless is enforced — a formatting or
  missing-license-header drift fails the build. Run `mvn spotless:apply` and commit the
  result. Checkstyle is report-only; please still read what it prints.

## Branch naming

`<type>/<short-description>`, lowercase, hyphenated. Types: `feat`, `fix`, `docs`,
`chore`, `refactor`, `test`. Example: `feat/aws-ec2-describe-tags`.

## Commits

- Present-tense, imperative subject that says what the change does
  (`oak-tools: add EC2 describe-tags tool`), not what you did.
- Keep commits coherent; don't mix a refactor with a behaviour change.
- Never commit secrets, credentials, or real infrastructure identifiers (account IDs,
  ARNs, endpoints, hostnames). `.gitignore` covers the common credential files, but the
  responsibility is yours — git history is permanent and this repo is going public.

## Pull requests

- Fill in the template. For a trivial docs/typo PR, "What" + "Why" + "No tool contract
  change" is enough — don't pad it.
- Keep PRs reviewable. Large, mixed-concern PRs get split.
- Expect **at least one approving review**, and a **code-owner** review on the paths listed
  in [`.github/CODEOWNERS`](.github/CODEOWNERS) (execution, credentials, transport,
  permissions). Resolve all review threads before merge.

## Testing expectations

See the matching depth in the code. At minimum, a change should cover the behaviour it
adds or alters. For tools, that means the cases that actually bite:

- valid input, invalid/missing input, and empty results
- provider/API failure and timeout
- the permission classification and the structured output shape
- for destructive tools: that the permission and any identity/targeting guard hold, and
  that a failure fails *safely*

Don't add tests purely to move a coverage number. Do add a focused test for any non-obvious
behaviour or fixed bug.

## Contributing a tool

A new tool is a new `capability`. Implement the `Tool` contract (`render` for the preview,
`execute` for the real run) — the README's "Add your own tool" section has a worked example.
In the PR, document, per [GOVERNANCE.md](GOVERNANCE.md § Tool contracts):

- capability name, description, provider
- inputs and outputs
- **permission class** (READ / WRITE / DESTRUCTIVE)
- failure semantics, timeout behaviour, retry/idempotency
- security implications

Tool rules that matter:

- **Validate input** — it came from outside your network. Build an argv; never interpolate
  a value into a shell string.
- **A non-zero exit is a normal outcome** (reported FAILED with output), not an exception.
- **Assume it can run twice** (steps retry) and **set a timeout** (steps run concurrently).

## Tool contract and breaking changes

The Tool contract and the `/v1/tools` wire protocol are a **cross-repository contract** with
the Oppex platform — see [GOVERNANCE.md](GOVERNANCE.md). Changing a tool's input, output, or
permission, or the execution/result protocol, can break the other side. Call it out
explicitly in the PR (the "Tool contract changes" section) so it lands in the release notes.

A **permission reclassification** (READ→WRITE, WRITE→DESTRUCTIVE) is treated as a
security-sensitive change and gets a security review.

## Reporting security issues

**Do not open a public issue or PR for a vulnerability.** Follow [SECURITY.md](SECURITY.md).
This is especially true for anything touching authentication, credentials, transport,
permission enforcement, or destructive/command/AWS/Docker/database execution.

## Licence

By contributing, you agree your contributions are licensed under the project's
[Apache 2.0](LICENSE) licence.
