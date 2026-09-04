package ai.oppex.adk.tools.capabilities;

import ai.oppex.adk.tools.Capability;
import ai.oppex.adk.tools.ToolPermission;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import software.amazon.awssdk.services.autoscaling.AutoScalingClient;
import software.amazon.awssdk.services.autoscaling.model.AutoScalingGroup;
import software.amazon.awssdk.services.autoscaling.model.DescribeAutoScalingGroupsRequest;
import software.amazon.awssdk.services.autoscaling.model.TagDescription;

/**
 * {@code FIND_ASG} — finds the Auto Scaling group behind a service.
 *
 * <p>The first capability, and the reason this whole rail exists: Oppex cannot answer "which ASG
 * runs the payment service?" because it holds no credentials in the customer's account, and asking
 * for them is what customers refuse. So it runs here instead.
 *
 * <h2>How it matches</h2>
 *
 * <p>In order, stopping at the first that finds something:
 *
 * <ol>
 *   <li>A tag whose value equals the key exactly — {@code Service}, {@code service},
 *       {@code app}, {@code Application}, or one named by {@code tagKey} in the input.
 *   <li>The group name equal to the key.
 *   <li>The group name containing the key.
 * </ol>
 *
 * <p>Tags first because they are what a team actually curates; names first would match
 * {@code payment-service-old} as readily as the live one. The fallbacks exist because plenty of
 * estates have no tagging convention at all, and a capability that only works in a tidy account is
 * not much use during an incident.
 *
 * <p><strong>Every match is returned, not just the best one.</strong> Two groups matching
 * {@code payment} is a real and interesting situation — a blue/green pair, or a leftover — and
 * silently picking one would hide exactly the thing an investigator needs to see. {@code matchedBy}
 * says which rule fired, so the next step and the LLM can judge the quality of the match.
 *
 * <h2>Input</h2>
 * <ul>
 *   <li>{@code serviceKey} (required) — the service to look for
 *   <li>{@code tagKey} (optional) — an additional tag name to consider
 * </ul>
 *
 * <h2>Credentials</h2>
 *
 * <p>Uses the AWS default credentials chain, so it picks up the instance role, environment
 * variables, or {@code ~/.aws/credentials} — whatever the host already has. It needs only
 * {@code autoscaling:DescribeAutoScalingGroups}, which is read-only. <strong>Grant it nothing
 * more.</strong> The IAM policy on this process is the boundary that actually constrains what
 * Oppex can cause to happen in your account; {@link ToolPermission} is only a label.
 */
public class FindAsgCapability implements Capability {

    public static final String NAME = "FIND_ASG";

    private static final List<String> DEFAULT_TAG_KEYS = List.of("Service", "service", "app", "Application");

    private final AutoScalingClient client;

    /** Uses the default credentials chain and the region from the environment. */
    public FindAsgCapability() {
        this(AutoScalingClient.create());
    }

    /** For tests, or to supply a client with an explicit region or profile. */
    public FindAsgCapability(AutoScalingClient client) {
        this.client = client;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> input) {
        final String serviceKey = string(input, "serviceKey");
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalArgumentException("FIND_ASG needs a non-blank 'serviceKey' in its input");
        }
        final String extraTagKey = string(input, "tagKey");

        final List<AutoScalingGroup> groups = describeAll();
        final List<Map<String, Object>> matches = match(groups, serviceKey, extraTagKey);

        final Map<String, Object> output = new LinkedHashMap<>();
        output.put("serviceKey", serviceKey);
        output.put("matchCount", matches.size());
        output.put("groups", matches);
        output.put("scannedGroupCount", groups.size());
        return output;
    }

    /** Pages through every group. An estate with hundreds is normal and one page is 100. */
    private List<AutoScalingGroup> describeAll() {
        final List<AutoScalingGroup> all = new ArrayList<>();
        String token = null;
        do {
            final DescribeAutoScalingGroupsRequest.Builder request = DescribeAutoScalingGroupsRequest.builder();
            if (token != null) {
                request.nextToken(token);
            }
            final var response = client.describeAutoScalingGroups(request.build());
            all.addAll(response.autoScalingGroups());
            token = response.nextToken();
        } while (token != null && !token.isBlank());
        return all;
    }

    private List<Map<String, Object>> match(List<AutoScalingGroup> groups, String key, String extraTagKey) {
        final List<Map<String, Object>> byTag = new ArrayList<>();
        final List<Map<String, Object>> byExactName = new ArrayList<>();
        final List<Map<String, Object>> byNameContains = new ArrayList<>();

        for (AutoScalingGroup group : groups) {
            final String tagKey = matchingTagKey(group, key, extraTagKey);
            if (tagKey != null) {
                byTag.add(describe(group, "tag:" + tagKey));
            } else if (key.equalsIgnoreCase(group.autoScalingGroupName())) {
                byExactName.add(describe(group, "name:exact"));
            } else if (group.autoScalingGroupName().toLowerCase().contains(key.toLowerCase())) {
                byNameContains.add(describe(group, "name:contains"));
            }
        }
        if (!byTag.isEmpty()) {
            return byTag;
        }
        return byExactName.isEmpty() ? byNameContains : byExactName;
    }

    private String matchingTagKey(AutoScalingGroup group, String key, String extraTagKey) {
        for (TagDescription tag : group.tags()) {
            final boolean interesting = DEFAULT_TAG_KEYS.contains(tag.key())
                    || (extraTagKey != null && extraTagKey.equalsIgnoreCase(tag.key()));
            if (interesting && key.equalsIgnoreCase(tag.value())) {
                return tag.key();
            }
        }
        return null;
    }

    /**
     * Facts about a group, not prose.
     *
     * <p>The next step and the LLM read this, so it carries the instance ids and the
     * desired/min/max triple — enough to answer "is it scaled where it should be?" without another
     * round trip. A human-readable summary here would be useless to both.
     */
    private Map<String, Object> describe(AutoScalingGroup group, String matchedBy) {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("asgName", group.autoScalingGroupName());
        out.put("matchedBy", matchedBy);
        out.put("desiredCapacity", group.desiredCapacity());
        out.put("minSize", group.minSize());
        out.put("maxSize", group.maxSize());
        out.put("availabilityZones", group.availabilityZones());
        final List<String> instanceIds = new ArrayList<>();
        group.instances().forEach(i -> instanceIds.add(i.instanceId()));
        out.put("instanceIds", instanceIds);
        out.put("instanceCount", instanceIds.size());
        if (group.autoScalingGroupARN() != null) {
            out.put("asgArn", group.autoScalingGroupARN());
        }
        return out;
    }

    private static String string(Map<String, Object> input, String key) {
        final Object value = input == null ? null : input.get(key);
        return value == null ? null : String.valueOf(value);
    }
}
