package ai.oak.tools.aws;

import ai.oak.tools.ToolRegistry;

/** Registers the generic AWS S3 tools. */
public final class S3Tools {

    private S3Tools() {
    }

    public static void registerAll(final ToolRegistry registry) {
        registry.register(new S3ListBucketsTool()).register(new S3ListObjectsTool()).register(new S3HeadObjectTool())
                .register(new S3DeleteObjectTool());
    }
}
