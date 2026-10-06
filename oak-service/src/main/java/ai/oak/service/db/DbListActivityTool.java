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

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.ToolResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * DB_LIST_ACTIVITY (READ) — reads {@code pg_stat_activity} for active backends running longer than
 * {@code minDurationSeconds} and returns them as structured facts. Eyes only: it does not pick a candidate
 * or interpret anything; the platform's approval resolver reads {@code activities[n].identity.pid}. Works on
 * RDS/Aurora Postgres (plain SQL). 0 rows is a definite answer — success with {@code activityCount: 0}.
 */
public final class DbListActivityTool implements Tool {

    private static final int DEFAULT_LIMIT = 50;

    private final DbConnectionProvider provider;

    public DbListActivityTool(final DbConnectionProvider provider) {
        this.provider = provider;
    }

    @Override
    public String capability() {
        return "DB_LIST_ACTIVITY";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "List active Postgres backends (pg_stat_activity) running longer than a threshold.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("dbIdentifier", "minDurationSeconds", "limit", "includeBlockers");
    }

    @Override
    public String render(final Map<String, Object> input) {
        return "pg_stat_activity: active backends on " + DbTools.str(input.get("dbIdentifier")) + " running >= " +
                DbTools.intOf(input.get("minDurationSeconds"), 0) + "s";
    }

    @Override
    public ToolResult execute(final Map<String, Object> input) {
        final String dbIdentifier = DbTools.str(input.get("dbIdentifier"));
        final int minDuration = DbTools.intOf(input.get("minDurationSeconds"), 0);
        final int limit = DbTools.intOf(input.get("limit"), DEFAULT_LIMIT);
        final boolean includeBlockers = DbTools.boolOf(input.get("includeBlockers"));
        try (DbGateway gateway = provider.open(dbIdentifier, DbTools.str(input.get("region")))) {
            final List<Map<String, Object>> activities = gateway.listActivity(minDuration, limit, includeBlockers);
            final Map<String, Object> output = new LinkedHashMap<>();
            output.put("dbIdentifier", dbIdentifier);
            output.put("serverTime", gateway.serverTime());
            output.put("activityCount", activities.size());
            output.put("activities", activities);
            return DbTools.ok(output);
        } catch (DbAccessException e) {
            return DbTools.error(e.code(), e.getMessage());
        }
    }
}
