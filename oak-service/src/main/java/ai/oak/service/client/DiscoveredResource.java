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

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

/**
 * One infrastructure resource as the cloud API reported it — facts only. This service is eyes and hands:
 * it reports what exists and never interprets it. So there is deliberately no derived field here (no
 * "service", no "role", no "isRelevant"); {@code tags} are passed through verbatim, and the platform
 * (the brain) does all mapping and selection.
 *
 * @param type      resource kind, e.g. {@code EC2_INSTANCE} (a stable, provider-neutral label).
 * @param id        the provider's own id, e.g. an instance id.
 * @param region    the region it lives in.
 * @param state     the provider's state string, e.g. {@code running} — verbatim, not normalised.
 * @param privateIp its private address, when the provider returns one.
 * @param tags      the resource's tags exactly as returned; never added to or interpreted.
 * @param asg       the auto-scaling group it belongs to, when applicable.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record DiscoveredResource(String type, String id, String region, String state, String privateIp, Map<String, String> tags,
        String asg) {
}
