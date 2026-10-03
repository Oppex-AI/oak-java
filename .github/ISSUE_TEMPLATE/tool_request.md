---
name: New tool request
about: Propose a new tool / capability for OAK to execute
title: "[tool] "
labels: tool-request
assignees: ''
---

<!--
  OAK is an execution SDK: a "tool" is a capability it can run against infrastructure.
  The more of this you can answer, the faster it can be designed and reviewed. A tool
  that executes with WRITE or DESTRUCTIVE permission gets a security review (GOVERNANCE.md).

  Describe shapes, NOT real values — no account IDs, ARNs, endpoints, or credentials.
-->

## Capability name
<!-- Proposed, in the existing style, e.g. AWS_EC2_START_INSTANCES, DB_LIST_ACTIVITY. -->


## Provider
<!-- AWS / Docker / Database / host command / other. -->


## Use case
<!-- The runbook step this enables. Why it's worth adding. -->


## Expected inputs
<!-- Keys and their meaning. Shapes only. -->
-

## Expected outputs
<!-- What execute() should return (structured fields, exit/stdout/stderr semantics). -->


## Permission class
- [ ] READ — observes only
- [ ] WRITE — changes state, non-destructive
- [ ] DESTRUCTIVE — deletes / terminates / irreversible

## Failure semantics
<!-- What counts as a normal failure vs. an exception; what the error should carry. -->


## Timeout considerations
<!-- Expected duration; a sane default timeout; anything long-running. -->


## Why can't an existing tool do this?
<!-- Which existing capability is closest, and why it doesn't cover this. -->
