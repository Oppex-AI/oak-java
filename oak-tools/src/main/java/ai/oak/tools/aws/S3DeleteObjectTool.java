package ai.oak.tools.aws;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Delete an S3 object — irreversible, so DESTRUCTIVE. */
public final class S3DeleteObjectTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_S3_DELETE_OBJECT";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.DESTRUCTIVE;
    }

    @Override
    public String description() {
        return "Delete an S3 object (irreversible).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("bucket", "key", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("s3api", "delete-object").required("--bucket", input.get("bucket"), "<bucket>")
                .required("--key", input.get("key"), "<key>").opt("--region", input.get("region"));
    }
}
