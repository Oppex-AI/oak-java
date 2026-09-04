# Security

This agent runs inside customer infrastructure and executes operational steps against production
systems. We take reports about it seriously.

## Reporting a vulnerability

Email **security@oppex.ai** with enough detail to reproduce. Please do not open a public issue for
anything exploitable.

We will acknowledge within three working days and keep you updated until it's resolved.

## Design properties worth knowing when assessing this

These are deliberate, and a report that one of them is *not* holding is exactly what we want to
hear about.

**No code is transported.** A step carries a capability name and a parameter map. The agent
resolves the name against its own registry of locally-compiled tools; an unknown name is refused.
There is no path by which Oppex — or anyone impersonating Oppex — can cause this process to execute
code that was not compiled into it.

**No inbound listener.** Every connection is opened outbound by the agent. The process binds no
port (`quarkus.http.host-enabled=false` in the server module). If you find it listening on
anything, that's a bug.

**One-directional authentication, on purpose.** The agent authenticates to Oppex with an API key.
Oppex does not authenticate to the agent with a shared secret, because the agent dialled out to a
known hostname and TLS already proves the server's identity. Using one symmetric secret in both
directions would be weaker — a leak on either side would compromise both.

**The credential file is not a sandbox.** `CredentialStore` encrypts the API key at rest with
AES-256-GCM, keyed by PBKDF2-HMAC-SHA256 (600,000 iterations) over a passphrase read from the
environment. Since the agent decrypts unattended, the passphrase is reachable on the same host —
so this does not defend against an attacker who already has the host. It defends against the key
leaking through backups, snapshots, support bundles and accidental commits.

**Permission labels are declarations, not controls.** `READ`/`WRITE`/`DESTRUCTIVE` on a capability
is the author's claim about their own tool. Nothing verifies it. The credential granted to the
process is the real boundary.

## Scope

In scope: anything in this repository — the agent, the transport, credential handling, the built-in
capabilities.

Out of scope here: the Oppex platform itself (report those to the same address; they are handled
separately), and capabilities you wrote yourself.
