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
package ai.oak.tools.aws.s3;

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
