package ai.oak.tools.aws.ec2;

import ai.oak.tools.ToolRegistry;

/** Registers the generic AWS EC2 tools. */
public final class Ec2Tools {

    private Ec2Tools() {
    }

    public static void registerAll(final ToolRegistry registry) {
        registry.register(new Ec2DescribeInstancesTool()).register(new Ec2DescribeInstanceStatusTool())
                .register(new Ec2StartInstancesTool()).register(new Ec2StopInstancesTool())
                .register(new Ec2RebootInstancesTool());
    }
}
