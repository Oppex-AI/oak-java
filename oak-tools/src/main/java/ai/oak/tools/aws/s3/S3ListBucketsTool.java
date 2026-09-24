package ai.oak.tools.aws.s3;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** List the account's S3 buckets. */
public final class S3ListBucketsTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_S3_LIST_BUCKETS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "List the account's S3 buckets.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("s3api", "list-buckets").opt("--region", input.get("region"));
    }
}
