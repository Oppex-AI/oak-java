/*
 * Copyright 2026 Oak Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.oak.service.db;

/**
 * How OAK reaches one logical database, keyed by the {@code dbIdentifier} the platform sends. {@code host}
 * is optional — when blank, OAK resolves the endpoint from the RDS API (read-only) using the dbIdentifier as
 * the RDS instance id.
 *
 * <p>Authentication is per-connection: {@code authMode} is {@code IAM} (a short-lived RDS IAM token minted at
 * connect time — no secret stored) or {@code PASSWORD} (for databases without IAM DB auth; the password is
 * stored encrypted and never returned to the UI). IAM is the default.
 *
 * @param username the Postgres role OAK connects as (needs pg_monitor + pg_signal_backend; + rds_iam for IAM).
 * @param database the database name to connect to.
 * @param host     explicit endpoint host, or blank to resolve via rds:DescribeDBInstances.
 * @param port     Postgres port; 5432 when unset.
 * @param sslmode  JDBC sslmode; {@code require} when unset.
 * @param region   AWS region for RDS resolution / token signing; falls back to the step's region.
 * @param authMode {@code IAM} (default) or {@code PASSWORD}.
 * @param password the password, used only in {@code PASSWORD} mode; stored encrypted, withheld from reads.
 */
public record DbConnection(String username, String database, String host, Integer port, String sslmode, String region,
        String authMode, String password) {

    public int portOrDefault() {
        return port == null || port <= 0 ? 5432 : port;
    }

    public String sslmodeOrDefault() {
        return sslmode == null || sslmode.isBlank() ? "require" : sslmode;
    }

    public String authModeOrDefault() {
        return authMode == null || authMode.isBlank() ? "IAM" : authMode.trim().toUpperCase();
    }

    public boolean usesPassword() {
        return "PASSWORD".equals(authModeOrDefault());
    }

    /** A copy with a different password value — used to (de)crypt it for storage without touching the rest. */
    public DbConnection withPassword(final String newPassword) {
        return new DbConnection(username, database, host, port, sslmode, region, authMode, newPassword);
    }
}
