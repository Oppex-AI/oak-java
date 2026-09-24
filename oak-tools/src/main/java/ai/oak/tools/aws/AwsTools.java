package ai.oak.tools.aws;

import ai.oak.tools.ToolRegistry;
import ai.oak.tools.aws.cloudwatch.CloudWatchTools;
import ai.oak.tools.aws.ec2.Ec2Tools;
import ai.oak.tools.aws.elasticache.ElastiCacheTools;
import ai.oak.tools.aws.msk.MskTools;
import ai.oak.tools.aws.rds.RdsTools;
import ai.oak.tools.aws.s3.S3Tools;

/**
 * Registers every generic AWS tool into a {@link ToolRegistry} — EC2, RDS, CloudWatch, S3,
 * ElastiCache (Redis) and MSK.
 *
 * <p>Delegates to one registrar per service ({@link Ec2Tools}, {@link RdsTools}, ...) rather than
 * naming every tool class here: that keeps this class's fan-out small, and a new service is a new
 * registrar + one line below (register the service's own tools in its {@code *Tools} class, not here).
 */
public final class AwsTools {

    private AwsTools() {
    }

    public static void registerAll(final ToolRegistry registry) {
        Ec2Tools.registerAll(registry);
        RdsTools.registerAll(registry);
        CloudWatchTools.registerAll(registry);
        S3Tools.registerAll(registry);
        ElastiCacheTools.registerAll(registry);
        MskTools.registerAll(registry);
    }
}
