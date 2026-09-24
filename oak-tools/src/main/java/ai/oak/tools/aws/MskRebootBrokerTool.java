package ai.oak.tools.aws;

import ai.oak.tools.Tool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Reboot specific brokers of an MSK cluster. */
public final class MskRebootBrokerTool implements Tool {

    @Override
    public String capability() {
        return "AWS_MSK_REBOOT_BROKER";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.WRITE;
    }

    @Override
    public String description() {
        return "Reboot specific brokers of an MSK cluster.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("clusterArn", "brokerIds", "region");
    }

    @Override
    public String render(final Map<String, Object> input) {
        return Cli.aws("kafka", "reboot-broker").required("--cluster-arn", input.get("clusterArn"), "<clusterArn>")
                .required("--broker-ids", input.get("brokerIds"), "<brokerIds>").opt("--region", input.get("region")).build();
    }
}
