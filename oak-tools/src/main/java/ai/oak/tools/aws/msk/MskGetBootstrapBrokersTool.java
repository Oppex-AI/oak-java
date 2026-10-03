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
package ai.oak.tools.aws.msk;

import ai.oak.tools.CommandTool;
import ai.oak.tools.ToolPermission;
import ai.oak.tools.cli.Cli;
import java.util.List;
import java.util.Map;

/** Get an MSK cluster's bootstrap broker connection string. */
public final class MskGetBootstrapBrokersTool extends CommandTool {

    @Override
    public String capability() {
        return "AWS_MSK_GET_BOOTSTRAP_BROKERS";
    }

    @Override
    public ToolPermission permission() {
        return ToolPermission.READ;
    }

    @Override
    public String description() {
        return "Get an MSK cluster's bootstrap broker connection string.";
    }

    @Override
    public List<String> inputKeys() {
        return List.of("clusterArn", "region");
    }

    @Override
    public Cli command(final Map<String, Object> input) {
        return Cli.aws("kafka", "get-bootstrap-brokers").required("--cluster-arn", input.get("clusterArn"), "<clusterArn>")
                .opt("--region", input.get("region"));
    }
}
