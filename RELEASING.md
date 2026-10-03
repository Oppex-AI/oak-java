# Releasing OAK

The release **policy**. Nothing here is automated yet — there is no release workflow and
nothing is published. This documents how OAK *will* release so the repository is ready for it.

> Current state: version `0.1.0-SNAPSHOT`, **0 tags, 0 releases, not published** to any
> repository. Do not publish artifacts or cut a release until the maintainers decide to.

## Versioning

OAK uses [Semantic Versioning](https://semver.org): `MAJOR.MINOR.PATCH`.

**Pre-1.0 (now).** While `0.y.z`, the API is still settling. Breaking changes are allowed but
must be deliberate: bump the **MINOR** (`0.1.z → 0.2.0`) and document the break in the release
notes and CHANGELOG. Patch releases (`0.1.0 → 0.1.1`) stay backward-compatible.

**1.0 and after.**

- **MAJOR** — a breaking change (API, Tool contract, or wire protocol)
- **MINOR** — a backward-compatible feature
- **PATCH** — a backward-compatible bug or security fix

## Git tags

- Release tags look like `v0.1.0`.
- A tag **must point to a commit on `main`**. Never release from a feature branch.
- Tags are created only as part of the release flow below, by a maintainer.

## CHANGELOG

[`CHANGELOG.md`](CHANGELOG.md) follows [Keep a Changelog](https://keepachangelog.com). Notable
changes land under `## [Unreleased]` as they merge; at release time that section is renamed to
`## [x.y.z] - YYYY-MM-DD` and a fresh `Unreleased` is opened. Trivial internal changes
(formatting, a test tweak) don't need an entry — the changelog is for things a consumer cares
about.

## GitHub Releases

Each GitHub Release carries the human-readable notes for a tag:

- version and release date
- highlights
- bug fixes
- **breaking changes**
- **security changes**
- **tool / capability changes** (new tools, input/output/permission changes)
- dependency changes
- upgrade / migration notes where applicable

## Maven publishing (future)

Local development needs **no** publishing — `mvn install` puts `ai.oppex:oak-tools` in your
`~/.m2` and the other modules build against it.

When publishing is enabled, the intended artifacts are the project's **actual** coordinates
from `pom.xml` (groupId `ai.oppex`):

- `ai.oppex:oak-tools` — the framework-free core
- `ai.oppex:oak-service` — the Quarkus executor

The root `pom.xml` currently targets **GitHub Packages**
(`https://maven.pkg.github.com/Oppex-AI/oak-java`) via `distributionManagement`. **Maven
Central is not configured and is a later step** (it needs a Central account, a `groupId`
namespace we control, and GPG signing). Do not publish to Central until that exists.

> Discrepancy to be aware of: an earlier planning note referred to
> `io.oppex:oppex-adk-tools` / `io.oppex:oppex-adk-tools-server`. Those are **stale** — they
> predate the module rename. The coordinates above (`ai.oppex:oak-tools` / `ai.oppex:oak-service`)
> are what the POMs actually declare, and are authoritative.

## Release workflow (future — to be implemented)

Releases must **not** publish automatically from an ordinary push to `main`. The intended
flow, triggered deliberately on a tag (or a manual dispatch) by a maintainer:

```
approved commit on main
        ↓
decide version  →  update CHANGELOG  →  create tag vX.Y.Z
        ↓
CI checks out the EXACT tag
        ↓
full build + complete test suite (mvn -B -ntp verify)
        ↓
publish artifacts   (only when publishing is enabled)
        ↓
create the GitHub Release from the notes
```

### Reproducibility requirements for a release build

- build from the **exact git tag**, not a moving branch
- run the **complete** applicable test suite
- produce versioned, as-deterministic-as-practical artifacts
- preserve build logs / status (CI run retained)
- **never** depend on customer credentials or infrastructure to build or test

### Security

A release must never contain credentials, customer configuration, internal infrastructure
data, secrets, or test credentials. The secret-scan / Dependabot checks (see the security
setup in the repository report) should be clean before tagging.

## Release checklist

```
[ ] main is green (CI passing on the release commit)
[ ] required reviews complete
[ ] dependency / security checks clean (Dependabot, secret scanning)
[ ] version decided (SemVer; pre-1.0 rules if < 1.0)
[ ] CHANGELOG.md updated (Unreleased → x.y.z - date)
[ ] breaking changes documented
[ ] Oppex ↔ OAK compatibility checked (input/output/permission/auth/protocol)
[ ] release commit on main, tag vX.Y.Z created
[ ] full release CI passed against the tag
[ ] GitHub Release created with notes
[ ] Maven artifacts published (ONLY when publishing is enabled)
[ ] post-release verification completed (artifact resolves / service boots from the tag)
```
