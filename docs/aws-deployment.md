# OAK AWS deployment — role setup

How to give an OAK instance the AWS access its built-in tools and discovery need, **without ever
granting OAK itself any IAM-write permission**. OAK only ever calls `sts:AssumeRole` on one role you
create for it and then runs the AWS CLI with the assumed credentials — it holds no keys of its own and
can change no IAM. That property is the whole security model; do not trade it away for convenience
(e.g. giving the host `iam:PutRolePolicy` on the runner role is a full-account privilege-escalation
path — never do it).

## The two roles involved

| Role | Who creates it | What it's for |
|---|---|---|
| **Host instance role** | Infra (when the host is provisioned) | The identity OAK's process runs as. Needs **only** permission to assume the runner role. |
| **`oak-runner`** (fixed name) | Infra (up front) | The role OAK assumes. Holds the actual tool permissions (describe/start/stop/SSM/…). |

OAK's process picks up the host instance role automatically via the default AWS credential chain — no
SSO, no access keys, no CLI profile needed when OAK runs on an EC2/ECS host with an instance role.
(A named CLI profile is only for running OAK on a laptop/dev box; set it in **Settings → AWS → CLI
profile**.)

## Who does what

Creating the role and granting the assume permission is safe to do up front — do it at provisioning
time. The only value that OAK produces is its **external ID**, generated on first boot; that (and the
permissions policy) is what the operator fills in afterward.

**Infra, up front (safe):**
1. Create the **`oak-runner`** role (fixed name).
2. Grant the **host instance role** `sts:AssumeRole` on `oak-runner` — and nothing else IAM-related.

**Operator, after OAK is installed and running:**
3. Open OAK → **Settings → AWS**, set **Role ARN** (`…:role/oak-runner`) + **Region**, Save. Copy the
   **External ID** OAK shows.
4. Attach the **permissions policy** to `oak-runner` (below), and set its **trust policy** to allow the
   host account to assume it with that external ID (below).

That's the deliberate "2-hop": infra provisions the structure, OAK emits its external ID, the operator
closes the trust. See [Collapsing the 2-hop](#collapsing-the-2-hop) for when it's safe to skip.

---

## Reference policies

### Host instance role — assume policy (infra, up front)
The only AWS grant the host needs:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Sid": "OakAssumeRunnerRole", "Effect": "Allow",
      "Action": "sts:AssumeRole",
      "Resource": "arn:aws:iam::<ACCOUNT_ID>:role/oak-runner" }
  ]
}
```

### `oak-runner` — permissions policy (operator)
The actions OAK's built-in tools call. Discovery uses the `Describe*` + `ssm:*` subset; the rest back
the WRITE/action tools. Trim to taste (minimal discovery-only set is noted after).

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow", "Resource": "*", "Action": [
      "ec2:DescribeInstances", "ec2:DescribeInstanceStatus", "ec2:StartInstances", "ec2:StopInstances", "ec2:RebootInstances",
      "autoscaling:DescribeAutoScalingGroups",
      "rds:DescribeDBInstances", "rds:DescribeEvents", "rds:StartDBInstance", "rds:StopDBInstance", "rds:RebootDBInstance", "rds:CreateDBSnapshot",
      "cloudwatch:DescribeAlarms", "cloudwatch:GetMetricStatistics", "logs:FilterLogEvents", "logs:GetLogEvents", "logs:DescribeLogGroups",
      "s3:ListAllMyBuckets", "s3:ListBucket", "s3:GetObject", "s3:DeleteObject",
      "elasticache:DescribeCacheClusters", "elasticache:DescribeReplicationGroups", "elasticache:DescribeEvents", "elasticache:RebootCacheCluster",
      "kafka:ListClusters", "kafka:DescribeCluster", "kafka:ListNodes", "kafka:GetBootstrapBrokers", "kafka:RebootBroker",
      "ssm:SendCommand", "ssm:ListCommandInvocations", "ssm:GetCommandInvocation"
    ] }
  ]
}
```

Discovery-only minimum: `ec2:DescribeInstances`, `autoscaling:DescribeAutoScalingGroups`,
`rds:DescribeDBInstances`, and the three `ssm:*` (for container/process host-scan).

### `oak-runner` — trust policy (operator)

**Cross-account (real customer, prod):** keep the external-ID condition — it's what prevents the
confused-deputy problem.

```json
{
  "Version": "2012-10-17",
  "Statement": [
    { "Effect": "Allow",
      "Principal": { "AWS": "arn:aws:iam::<HOST_ACCOUNT_ID>:root" },
      "Action": "sts:AssumeRole",
      "Condition": { "StringEquals": { "sts:ExternalId": "oak-<external-id-from-OAK>" } } }
  ]
}
```

Multiple OAK instances assuming the same role → make `sts:ExternalId` a **list** (OR semantics):

```json
"sts:ExternalId": [ "oak-<id-A>", "oak-<id-B>" ]
```

Tighten later by replacing `:root` with the host instance role's exact ARN.

---

## Collapsing the 2-hop

The external ID is the only thing forcing a second pass. You can remove it **without** giving OAK any
IAM-write:

- **Same-account (dev / opx-client):** the external ID guards against *cross-account* confused-deputy,
  so same-account it adds essentially nothing. **Drop the condition** — trust just `:root` of the host
  account. OAK still sends its generated ID; STS ignores an ID the trust doesn't require, so the assume
  succeeds. Infra can then provision role + trust + permissions entirely up front and OAK works on first
  boot — zero hops.
- **Cross-account/prod:** **pre-seed** the external ID instead of letting OAK generate a random one.
  Before first boot, write it into OAK's data dir:
  ```json
  // <OAK_DATA_DIR>/tool-settings.json
  { "AWS": { "OAK_EXTERNAL_ID": "oak-<your-chosen-id>" } }
  ```
  Infra then builds the trust with that known ID up front — still one pass, still zero IAM-write.

## Persist the data dir

OAK stores its settings and external ID in `<OAK_DATA_DIR>/tool-settings.json` (default `~/.oak`,
owner-only; secrets encrypted via `SecretStore`). If OAK runs in a **container, mount a persistent
volume for `OAK_DATA_DIR`** — otherwise a restart regenerates the external ID and silently breaks the
trust policy until you add the new one.

## Verify

After the trust + permissions are in place (discovery runs on the `oak.discovery.interval`, default
`5m`; restart OAK to trigger the initial scan):

1. **Settings → AWS** shows OAK's caller identity; if the assume fails it says so explicitly
   ("oak's host identity can't assume the linked role — check the trust policy / assume-role policy").
2. **Run** panel → `AWS_EC2_DESCRIBE_INSTANCES`:
   - assume error → fix the **host assume policy** or the **trust policy**;
   - `AccessDenied` on the describe → fix the **`oak-runner` permissions policy**;
   - results → you're done.
3. Log line `[<platform>] discovery: reported N resource(s)` confirms discovery is running. `0` with no
   error usually means wrong region (discovery scans the AWS setting's region unless
   `oak.discovery.regions` is set) or the permissions subset above.

## Why not let OAK update the role itself

Granting the host `iam:PutRolePolicy`/`iam:UpdateAssumeRolePolicy` even scoped to `oak-runner` limits
*which* role, not *what* OAK can put on it — so OAK (or anyone who compromises the host) could grant
`oak-runner` `"Action": "*"` and become account admin. The 2-hop is cheaper than that risk. Keep OAK
IAM-write-free.
