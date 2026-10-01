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
 * How OAK reaches one logical database, keyed by the {@code dbIdentifier} the platform sends. Holds no
 * secret: authentication is a short-lived RDS IAM auth token generated at connect time, so there is no
 * password to store or keep in sync. {@code host} is optional — when blank, OAK resolves the endpoint from
 * the RDS API (read-only) using the dbIdentifier as the RDS instance id.
 *
 * @param username the Postgres role OAK connects as (must have rds_iam + pg_monitor + pg_signal_backend).
 * @param database the database name to connect to.
 * @param host     explicit endpoint host, or blank to resolve via rds:DescribeDBInstances.
 * @param port     Postgres port; 5432 when unset.
 * @param sslmode  JDBC sslmode; {@code require} when unset (IAM auth mandates TLS).
 * @param region   AWS region for RDS resolution / token signing; falls back to the step's region.
 */
public record DbConnection(String username, String database, String host, Integer port, String sslmode, String region) {

    public int portOrDefault() {
        return port == null || port <= 0 ? 5432 : port;
    }

    public String sslmodeOrDefault() {
        return sslmode == null || sslmode.isBlank() ? "require" : sslmode;
    }
}
