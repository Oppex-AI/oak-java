<!--
  Keep it proportionate: a docs-only or typo PR can leave most of this blank —
  fill in "What" and "Why", tick "No tool contract change", and you're done.
  The checklists exist for changes that touch behaviour, execution or the contract.
-->

## What does this PR do?


## Why?


## Changes
<!-- The notable changes, one per line. -->
-

## Testing
<!-- What you ran, and what a reviewer should run to see it work. -->
-

## Security impact
<!-- Execution, credentials, transport, permissions. "None" is a valid answer — say why. -->


## Breaking changes
<!-- API/behaviour consumers rely on. "None" is a valid answer. -->


## Tool contract changes

- [ ] No tool contract change
- [ ] New capability
- [ ] Input changed
- [ ] Output changed
- [ ] Permission classification changed (READ / WRITE / DESTRUCTIVE)
- [ ] Breaking change

<!-- Any box but the first ticked → the change can affect the Oppex ↔ OAK contract.
     Describe it here and make sure it lands in the release notes. See GOVERNANCE.md. -->


---

- [ ] Unit tests added/updated
- [ ] Integration tests added/updated where applicable
- [ ] Existing tests still pass (`mvn verify`)
- [ ] Documentation updated if needed
- [ ] No secrets, credentials, or real infrastructure identifiers committed
- [ ] Security implications reviewed (execution / credentials / transport / permissions)
- [ ] Backward compatibility considered
