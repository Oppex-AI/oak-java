package ai.oak.tools.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        final Cli cli = Cli.aws("ec2", "start-instances")
                .required("--instance-ids", List.of("i-1", "i-2"), "<instanceIds>")
                .opt("--region", "us-west-2");

        assertEquals("aws ec2 start-instances --instance-ids i-1 i-2 --region us-west-2", cli.build());
        assertEquals(
                List.of("aws", "ec2", "start-instances", "--instance-ids", "i-1", "i-2", "--region", "us-west-2"),
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
        final Cli cli = Cli.aws("ec2", "describe-instances")
                .opt("--region", null)
                .optList("--filters", List.of());

        assertEquals("aws ec2 describe-instances", cli.build());
        assertEquals(List.of("aws", "ec2", "describe-instances"), cli.argv());
    }

    @Test
    void argvIsProgramFirst() {
        assertTrue(Cli.docker("ps").argv().get(0).equals("docker"));
    }
}
