# DB Runbook — OAK setup (Postgres / RDS)

OAK's DB tools let the platform inspect and safely stop a long-running query on your
Postgres database (RDS/Aurora included), through a connection you configure locally.
OAK is **eyes + hands**: it reports facts and runs the exact action it's told to — it
never picks a target or interprets intent (the platform does that, and a human
approves the kill).

| Capability | Permission | What it does |
|---|---|---|
| `DB_LIST_ACTIVITY` | READ | Read `pg_stat_activity` — active backends over a duration threshold |
| `DB_TERMINATE` | DESTRUCTIVE | Identity-guarded `pg_terminate_backend` (kills a query, **not** the instance) |
| `DOCKER_LOGS` | READ | A container's logs (on its host via SSM when an instance id is given) |

These are plain SQL operations over a normal Postgres connection — **no `rds:*`
control-plane calls**, no `RebootDBInstance`. Terminating a query needs no AWS
permission beyond opening the connection.

---

## 1. Create the Postgres role (run once per database, as the master user)

Connect to the database as the RDS master (`rds_superuser`) and create the role OAK
will connect as. Pick the auth style per database:

```sql
-- the role OAK connects as
CREATE ROLE oak_runner LOGIN;

-- read other sessions' query text in pg_stat_activity
GRANT pg_monitor TO oak_runner;          -- or: GRANT pg_read_all_stats TO oak_runner;

-- terminate another user's backend (the RDS master already holds this)
GRANT pg_signal_backend TO oak_runner;
```

Then, **depending on the auth mode**:

```sql
-- (A) IAM auth — no password stored; OAK mints a short-lived token per connect
GRANT rds_iam TO oak_runner;

-- (B) Password auth — for databases without IAM DB auth
ALTER ROLE oak_runner WITH PASSWORD 'choose-a-strong-password';
```

The role is **not** a superuser and needs nothing more. On RDS you have no true
superuser; the two predefined roles above are the minimum.

## 2. (IAM auth only) Enable IAM database authentication on the instance

IAM auth is a per-instance RDS setting, separate from the SQL grant:

- Console: RDS → your instance → Modify → **Database authentication** → enable
  *IAM database authentication* → apply.
- Or CLI:
  ```
  aws rds modify-db-instance --db-instance-identifier <id> \
    --enable-iam-database-authentication --apply-immediately
  ```

Skip this entirely if the database uses **Password** auth.

## 3. Grant OAK's AWS role the needed actions

OAK assumes its runner role (e.g. `oak-runner-local`) for AWS calls. For the DB tools
it needs (on top of the existing actions):

- `rds:DescribeDBInstances` — resolve the endpoint when you leave **Host** blank.
- `rds-db:connect` — **IAM auth only**; mint the DB auth token.

Minimal additions:

```json
{
  "Effect": "Allow",
  "Action": ["rds:DescribeDBInstances", "rds-db:connect"],
  "Resource": "*"
}
```

`rds-db:connect` can be scoped to
`arn:aws:rds-db:<region>:<account>:dbuser:<dbResourceId>/<oak_runner>` instead of
`"*"`. (`<dbResourceId>` is the instance's resource id, e.g. `db-ABC123…`.) The full
runner policy, including the SSM/discovery actions, is shown in **Settings → AWS →
Role permissions policy**.

> Per the no-AWS-write rule, apply these yourself — OAK never modifies your account.

## 4. Configure the connection in OAK

**Settings → Databases → add** a mapping for each `dbIdentifier` the platform sends:

| Field | Notes |
|---|---|
| dbIdentifier | The logical id the platform uses. For RDS, the DB-instance identifier works directly. |
| DB username | The Postgres role from step 1 (`oak_runner`). |
| Database | The database name to connect to. |
| Auth | **IAM** (token, no secret) or **Password**. |
| Password | Only for Password auth; stored encrypted, never shown back. |
| Host | Leave **blank** to resolve the RDS endpoint automatically; set it for a non-RDS/explicit host. |
| Port / Region | Optional; default port 5432; region falls back to the AWS setting / the step's region. |

No configured mapping for a requested `dbIdentifier` → the tool returns
`success:false, error.code=NO_CONNECTION_CONFIGURED` (it never guesses).

---

## RDS / Aurora caveats

- **RDS-internal / `rdsadmin` backends** (replication, maintenance) can't be
  terminated — they aren't application queries, so they're irrelevant to a
  slow-query kill.
- **Aurora**: connect to the instance actually running the query (usually the
  **writer**); `pg_stat_activity` and `pg_terminate_backend` act only on the
  instance you're connected to.
- IAM auth requires TLS; OAK connects with `sslmode=require` by default.

## How the safe kill works

`DB_TERMINATE` re-reads the backend at terminate time and issues
`pg_terminate_backend(pid)` **only if** the live `backend_start` still matches the
identity resolved from the earlier `DB_LIST_ACTIVITY` snapshot — never by pid alone.
A reused pid (the original query already gone) is reported as `already_gone`
(`success:true`, nothing killed). It is idempotent.
