package ai.oak.tools.aws;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** List objects in an S3 bucket, optionally under a prefix. */
public final class S3ListObjectsTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_S3_LIST_OBJECTS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "List objects in an S3 bucket, optionally under a prefix.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("bucket", "prefix", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("s3api", "list-objects-v2").required("--bucket", input.get("bucket"), "<bucket>")
                .opt("--prefix", input.get("prefix")).opt("--region", input.get("region"));
    }
}
