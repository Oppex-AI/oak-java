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

import ai.oak.service.AwsCredentials;
import ai.oak.service.ToolSettings;
import ai.oak.tools.ToolResult;
import ai.oak.tools.cli.CommandRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Turns a logical {@code dbIdentifier} into an open {@link DbGateway}. It looks up the locally configured
 * {@link DbConnection}, resolves the endpoint (explicit host, or a read-only {@code rds:DescribeDBInstances}
 * when the host is blank), mints a short-lived RDS IAM auth token with the assumed role, and opens a TLS
 * JDBC connection. No password is ever stored — the token is generated per connect. Nothing here reaches
 * back to the platform, and the terminate/list operations themselves are pure SQL.
 */
@ApplicationScoped
public class DbConnectionProvider {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Inject
    DbConnections connections;

    @Inject
    AwsCredentials awsCredentials;

    @Inject
    ToolSettings toolSettings;

    /** Open a gateway for {@code dbIdentifier}; throws with the contract code when it cannot be reached. */
    public DbGateway open(final String dbIdentifier, final String stepRegion) throws DbAccessException {
        final DbConnection conn = connections.get(dbIdentifier)
                .orElseThrow(() -> new DbAccessException("NO_CONNECTION_CONFIGURED",
                        "no database connection configured for '" + dbIdentifier + "'"));
        final boolean iam = !conn.usesPassword();
        final boolean resolveHost = conn.host() == null || conn.host().isBlank();
        final String region = firstNonBlank(stepRegion, conn.region(), toolSettings.envFor("AWS").get("AWS_REGION"));
        if (region == null && (iam || resolveHost)) {
            throw new DbAccessException("DB_UNREACHABLE", "no region for '" + dbIdentifier + "' (set it in the DB config)");
        }
        // AWS creds are only needed to resolve an RDS endpoint or to mint an IAM token.
        final Map<String, String> awsEnv = iam || resolveHost ? awsEnv(region) : Map.of();
        final String host = resolveHost ? resolveEndpoint(dbIdentifier, region, awsEnv) : conn.host().trim();
        final int port = conn.portOrDefault();
        final String secret = iam ? authToken(host, port, region, conn.username(), awsEnv) : password(conn, dbIdentifier);
        return new JdbcDbGateway(connect(conn, host, port, secret));
    }

    private static String password(final DbConnection conn, final String dbIdentifier) throws DbAccessException {
        if (conn.password() == null || conn.password().isBlank()) {
            throw new DbAccessException("DB_UNREACHABLE",
                    "password auth selected but no password configured for '" + dbIdentifier + "'");
        }
        return conn.password();
    }

    private Connection connect(final DbConnection conn, final String host, final int port, final String token)
            throws DbAccessException {
        final String url = "jdbc:postgresql://" + host + ":" + port + "/" + conn.database() + "?sslmode=" +
                conn.sslmodeOrDefault() + "&connectTimeout=10&socketTimeout=30";
        final Properties props = new Properties();
        props.setProperty("user", conn.username());
        props.setProperty("password", token);
        try {
            return DriverManager.getConnection(url, props);
        } catch (SQLException e) {
            throw new DbAccessException("DB_UNREACHABLE", "could not connect to " + host + ":" + port + " — " + e.getMessage());
        }
    }

    /** The RDS endpoint host for an instance id, via a read-only describe (no control-plane mutation). */
    private String resolveEndpoint(final String dbIdentifier, final String region, final Map<String, String> awsEnv)
            throws DbAccessException {
        final List<String> argv = List.of("aws", "rds", "describe-db-instances", "--db-instance-identifier", dbIdentifier,
                "--region", region, "--query", "DBInstances[0].Endpoint.Address", "--output", "text");
        final ToolResult r = CommandRunner.run(argv, awsEnv);
        final String host = r.stdout().trim();
        if (!r.success() || host.isEmpty() || "None".equals(host)) {
            throw new DbAccessException("DB_UNREACHABLE", "could not resolve RDS endpoint for '" + dbIdentifier + "': " +
                    (r.stderr().isBlank() ? r.stdout() : r.stderr().strip()));
        }
        return host;
    }

    private String authToken(final String host, final int port, final String region, final String username,
            final Map<String, String> awsEnv) throws DbAccessException {
        final List<String> argv = List.of("aws", "rds", "generate-db-auth-token", "--hostname", host, "--port",
                Integer.toString(port), "--region", region, "--username", username);
        final ToolResult r = CommandRunner.run(argv, awsEnv);
        if (!r.success() || r.stdout().isBlank()) {
            throw new DbAccessException("DB_UNREACHABLE",
                    "could not generate RDS IAM auth token: " + (r.stderr().isBlank() ? r.stdout() : r.stderr().strip()));
        }
        return r.stdout().trim();
    }

    private Map<String, String> awsEnv(final String region) {
        final Map<String, String> raw = toolSettings.envFor("AWS");
        return awsCredentials.assume(raw.get("AWS_ROLE_ARN"), raw.get("OAK_EXTERNAL_ID"), region, raw.get("AWS_PROFILE"));
    }

    private static String firstNonBlank(final String... values) {
        for (final String v : values) {
            if (v != null && !v.isBlank()) {
                return v.trim();
            }
        }
        return null;
    }
}
