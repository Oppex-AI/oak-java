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
package ai.oak.tools.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The preview string and the argv must come from the one definition and must not be derivable from
 * each other by splitting on spaces — that is the whole reason {@link Cli#argv()} exists next to
 * {@link Cli#build()}.
 */
class CliTest {

    @Test
    void listValueBecomesSeparateArgvTokensButOneSpaceJoinedPreview() {
        final Cli cli = Cli.aws("ec2", "start-instances").required("--instance-ids", List.of("i-1", "i-2"), "<instanceIds>")
                .opt("--region", "us-west-2");

        assertEquals("aws ec2 start-instances --instance-ids i-1 i-2 --region us-west-2", cli.build());
        assertEquals(List.of("aws", "ec2", "start-instances", "--instance-ids", "i-1", "i-2", "--region", "us-west-2"),
                cli.argv());
    }

    @Test
    void aValueContainingSpacesStaysOneArgvTokenEvenThoughThePreviewShowsItSplit() {
        final Cli cli = Cli.aws("s3api", "head-object").opt("--key", "my file.txt");

        // The preview reads as text; the argv is what actually runs, and there the key is one token.
        assertEquals("aws s3api head-object --key my file.txt", cli.build());
        assertEquals(List.of("aws", "s3api", "head-object", "--key", "my file.txt"), cli.argv());
    }

    @Test
    void absentRequiredValueEmitsPlaceholderInBothForms() {
        final Cli cli = Cli.aws("ec2", "stop-instances").required("--instance-ids", null, "<instanceIds>");

        assertEquals("aws ec2 stop-instances --instance-ids <instanceIds>", cli.build());
        assertEquals(List.of("aws", "ec2", "stop-instances", "--instance-ids", "<instanceIds>"), cli.argv());
    }

    @Test
    void absentOptionalFlagsAreOmitted() {
        final Cli cli = Cli.aws("ec2", "describe-instances").opt("--region", null).optList("--filters", List.of());

        assertEquals("aws ec2 describe-instances", cli.build());
        assertEquals(List.of("aws", "ec2", "describe-instances"), cli.argv());
    }

    @Test
    void structuredValuesSerializeAsJsonNotJavaToString() {
        final var filter = new java.util.LinkedHashMap<String, Object>();
        filter.put("Name", "tag:Name");
        filter.put("Values", List.of("product-service"));
        final Cli cli = Cli.aws("ec2", "describe-instances").opt("--filters", List.of(filter));

        final String expected = "[{\"Name\":\"tag:Name\",\"Values\":[\"product-service\"]}]";
        // The whole JSON is one argv token (not word-split), and it is valid JSON — not [{Name=..., Values=[...]}].
        assertEquals(List.of("aws", "ec2", "describe-instances", "--filters", expected), cli.argv());
        assertFalse(cli.build().contains("Name="), "must not use Java toString form");
    }

    @Test
    void argvIsProgramFirst() {
        assertTrue(Cli.docker("ps").argv().get(0).equals("docker"));
    }
}
