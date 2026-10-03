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
package ai.oak.service.ui;

import ai.oak.service.db.DbConnection;
import ai.oak.service.db.DbConnections;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Manage the per-{@code dbIdentifier} database connections the DB tools resolve against. No secret is held
 * (RDS IAM auth), so this returns the full config. The platform sends only a logical dbIdentifier; this is
 * where an operator maps it to a Postgres role + database (+ optional explicit host / RDS region).
 */
@Path("/api")
public class DbConnectionsResource {

    @Inject
    DbConnections store;

    @GET
    @Path("/db-connections")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> list() {
        final Map<String, Object> out = new LinkedHashMap<>();
        store.all().forEach((id, c) -> out.put(id, view(c)));
        return out;
    }

    /** A connection as safe to return: no password, just whether one is set. */
    private static Map<String, Object> view(final DbConnection c) {
        final Map<String, Object> v = new LinkedHashMap<>();
        v.put("username", c.username());
        v.put("database", c.database());
        v.put("host", c.host());
        v.put("port", c.port());
        v.put("sslmode", c.sslmode());
        v.put("region", c.region());
        v.put("authMode", c.authModeOrDefault());
        v.put("passwordSet", c.password() != null && !c.password().isBlank());
        return v;
    }

    @POST
    @Path("/db-connections")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> save(final SaveRequest req) {
        if (req == null || blank(req.dbIdentifier()) || blank(req.username()) || blank(req.database())) {
            return Map.of("ok", false, "error", "dbIdentifier, username and database are required");
        }
        store.update(req.dbIdentifier().trim(),
                new DbConnection(req.username().trim(), req.database().trim(), trimOrNull(req.host()), req.port(),
                        trimOrNull(req.sslmode()), trimOrNull(req.region()), trimOrNull(req.authMode()),
                        trimOrNull(req.password())));
        return Map.of("ok", true, "dbIdentifier", req.dbIdentifier().trim());
    }

    @POST
    @Path("/db-connections/delete")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> delete(final DeleteRequest req) {
        if (req == null || blank(req.dbIdentifier())) {
            return Map.of("ok", false, "error", "dbIdentifier is required");
        }
        store.remove(req.dbIdentifier().trim());
        return Map.of("ok", true);
    }

    private static boolean blank(final String s) {
        return s == null || s.isBlank();
    }

    private static String trimOrNull(final String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /** Upsert a dbIdentifier → connection. authMode IAM (token, no secret) or PASSWORD (stored encrypted). */
    public record SaveRequest(String dbIdentifier, String username, String database, String host, Integer port, String sslmode,
            String region, String authMode, String password) {
    }

    public record DeleteRequest(String dbIdentifier) {
    }
}
