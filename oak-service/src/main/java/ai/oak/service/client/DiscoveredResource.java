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
package ai.oak.service.client;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One infrastructure resource as the cloud/host API reported it — facts only. This service is eyes and
 * hands: it reports what exists and never interprets it. So there is deliberately no derived field (no
 * "service", "role", "isPrimary"); tags/labels are passed through verbatim, and the platform (the brain)
 * does all mapping and selection.
 *
 * <p>{@code type} and {@code id} are the identity/ordering keys and are always present. Everything else is
 * an open bag of {@code facts} that differs by type (an EC2 instance, an RDS instance and a Docker
 * container carry different fields) — serialised <em>flat</em> alongside type and id via
 * {@link JsonAnyGetter}, so the wire shape is {@code {"type":..,"id":..,<facts>}}. Reporting facts as an
 * open map (rather than a fixed schema) is exactly what keeps this side free of interpretation and lets
 * new resource types be added without a contract change.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class DiscoveredResource {

    private final String type;
    private final String id;
    private final Map<String, Object> facts;

    public DiscoveredResource(final String type, final String id, final Map<String, Object> facts) {
        this.type = type;
        this.id = id;
        this.facts = facts == null ? new LinkedHashMap<>() : new LinkedHashMap<>(facts);
    }

    public String getType() {
        return type;
    }

    public String getId() {
        return id;
    }

    /** The remaining facts, serialised flat next to type and id. Never contains {@code type} or {@code id}. */
    @JsonAnyGetter
    public Map<String, Object> getFacts() {
        return facts;
    }
}
