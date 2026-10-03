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
