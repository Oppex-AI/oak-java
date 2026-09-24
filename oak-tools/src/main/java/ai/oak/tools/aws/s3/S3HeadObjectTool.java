package ai.oak.tools.aws.s3;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Read an S3 object's metadata / existence (size, last-modified, content-type). */
public final class S3HeadObjectTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_S3_HEAD_OBJECT";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Read an S3 object's metadata / existence (size, last-modified, content-type).";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("bucket", "key", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("s3api", "head-object").required("--bucket", input.get("bucket"), "<bucket>")
                .required("--key", input.get("key"), "<key>").opt("--region", input.get("region"));
    }
}
