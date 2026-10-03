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
package ai.oak.tools.aws;

import ai.oak.tools.ToolRegistry;
import ai.oak.tools.aws.cloudwatch.CloudWatchTools;
import ai.oak.tools.aws.ec2.Ec2Tools;
import ai.oak.tools.aws.elasticache.ElastiCacheTools;
import ai.oak.tools.aws.msk.MskTools;
import ai.oak.tools.aws.rds.RdsTools;
import ai.oak.tools.aws.s3.S3Tools;
import ai.oak.tools.aws.ssm.SsmTools;

/**
 * Registers every generic AWS tool into a {@link ToolRegistry} — EC2, RDS, CloudWatch, S3,
 * ElastiCache (Redis), MSK and SSM.
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
        SsmTools.registerAll(registry);
    }
}
