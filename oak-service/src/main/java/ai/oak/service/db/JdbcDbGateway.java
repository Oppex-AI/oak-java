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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC implementation of {@link DbGateway} over one Postgres connection — reads {@code pg_stat_activity} and
 * runs the identity-guarded {@code pg_terminate_backend}. Works unchanged against RDS/Aurora Postgres (these
 * are plain SQL operations, never RDS control-plane calls). Insufficient-privilege errors (SQLState 42501)
 * map to {@code PERMISSION_DENIED}; any other SQL failure to {@code DB_UNREACHABLE}.
 */
public final class JdbcDbGateway implements DbGateway {

    private static final String ACTIVITY_SQL = "SELECT pid, backend_start, query_start, state, usename, datname," +
            " application_name, host(client_addr) AS client_addr, wait_event_type, wait_event, query," +
            " EXTRACT(EPOCH FROM (clock_timestamp() - query_start)) AS duration_seconds," +
            " CASE WHEN ? THEN pg_blocking_pids(pid) ELSE NULL END AS blocked_by" + " FROM pg_stat_activity" +
            " WHERE state = 'active' AND pid <> pg_backend_pid() AND query_start IS NOT NULL" +
            " AND clock_timestamp() - query_start >= make_interval(secs => ?)" +
            " ORDER BY clock_timestamp() - query_start DESC LIMIT ?";

    private final Connection connection;

    public JdbcDbGateway(final Connection connection) {
        this.connection = connection;
    }

    @Override
    public String serverTime() throws DbAccessException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT now() AS t"); ResultSet rs = ps.executeQuery()) {
            rs.next();
            return iso(rs.getObject("t", OffsetDateTime.class));
        } catch (SQLException e) {
            throw access(e);
        }
    }

    @Override
    public List<Map<String, Object>> listActivity(final int minDurationSeconds, final int limit, final boolean includeBlockers)
            throws DbAccessException {
        final List<Map<String, Object>> out = new ArrayList<>();
        try (PreparedStatement ps = connection.prepareStatement(ACTIVITY_SQL)) {
            ps.setBoolean(1, includeBlockers);
            ps.setDouble(2, Math.max(0, minDurationSeconds));
            ps.setInt(3, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(activity(rs, includeBlockers));
                }
            }
        } catch (SQLException e) {
            throw access(e);
        }
        return out;
    }

    @Override
    public Optional<String> backendStartOf(final int pid) throws DbAccessException {
        try (PreparedStatement ps = connection.prepareStatement("SELECT backend_start FROM pg_stat_activity WHERE pid = ?")) {
            ps.setInt(1, pid);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(iso(rs.getObject(1, OffsetDateTime.class))) : Optional.empty();
            }
        } catch (SQLException e) {
            throw access(e);
        }
    }

    @Override
    public TerminateResult guardedTerminate(final int pid, final String backendStartIso) throws DbAccessException {
        final String sql = "SELECT pg_terminate_backend(pid) AS ok FROM pg_stat_activity" +
                " WHERE pid = ? AND backend_start = ?::timestamptz";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setInt(1, pid);
            ps.setString(2, backendStartIso);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new TerminateResult(false, false);
                }
                return new TerminateResult(true, rs.getBoolean("ok"));
            }
        } catch (SQLException e) {
            throw access(e);
        }
    }

    @Override
    public void close() {
        try {
            connection.close();
        } catch (SQLException ignore) {
            // closing a connection that already failed is not worth surfacing
        }
    }

    private Map<String, Object> activity(final ResultSet rs, final boolean includeBlockers) throws SQLException {
        final String query = rs.getString("query");
        final Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("pid", rs.getInt("pid"));
        identity.put("backendStart", iso(rs.getObject("backend_start", OffsetDateTime.class)));
        identity.put("queryStart", iso(rs.getObject("query_start", OffsetDateTime.class)));

        final Map<String, Object> activity = new LinkedHashMap<>();
        activity.put("identity", identity);
        activity.put("queryFingerprint", fingerprint(query));
        activity.put("durationSeconds", rs.getDouble("duration_seconds"));
        activity.put("state", rs.getString("state"));
        activity.put("usename", rs.getString("usename"));
        activity.put("datname", rs.getString("datname"));
        activity.put("applicationName", rs.getString("application_name"));
        activity.put("clientAddr", rs.getString("client_addr"));
        activity.put("waitEventType", rs.getString("wait_event_type"));
        activity.put("waitEvent", rs.getString("wait_event"));
        if (includeBlockers) {
            activity.put("blockedBy", blockedBy(rs.getArray("blocked_by")));
        }
        activity.put("query", query);
        return activity;
    }

    private static List<Integer> blockedBy(final Array array) throws SQLException {
        final List<Integer> pids = new ArrayList<>();
        if (array != null) {
            for (final Object pid : (Object[]) array.getArray()) {
                pids.add(((Number) pid).intValue());
            }
        }
        return pids;
    }

    /** A stable fingerprint of the query: SHA-256 of a whitespace-collapsed, lower-cased, de-semicoloned form. */
    static String fingerprint(final String query) {
        if (query == null) {
            return null;
        }
        final String normalized = query.replaceAll("\\s+", " ").replaceAll("\\s*;+\\s*$", "").strip().toLowerCase();
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (final byte b : digest) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private static String iso(final OffsetDateTime value) {
        return value == null ? null : value.toInstant().toString();
    }

    private static DbAccessException access(final SQLException e) {
        final String code = "42501".equals(e.getSQLState()) ? "PERMISSION_DENIED" : "DB_UNREACHABLE";
        return new DbAccessException(code, e.getMessage());
    }
}
