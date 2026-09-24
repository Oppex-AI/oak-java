package ai.oak.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.tools.config.CredentialStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CredentialStoreRoundTripTest {

    private static final String KEY = "oppex_live_2f9c41ab8e7d";
    private static final String PASSPHRASE = "correct horse battery staple";

    @Test
    @DisplayName("a saved key reads back identically")
    void roundTrip(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("agent.oppex-credentials");
        CredentialStore.save(file, KEY, PASSPHRASE);
        assertEquals(KEY, CredentialStore.load(file, PASSPHRASE));
    }

    @Test
    @DisplayName("the key is not recoverable from the file by reading it")
    void fileDoesNotContainThePlaintext(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("agent.oppex-credentials");
        CredentialStore.save(file, KEY, PASSPHRASE);
        assertTrue(Files.readString(file).indexOf(KEY) < 0,
                "the API key appeared verbatim in the credentials file");
    }

    @Test
    @DisplayName("the wrong passphrase fails loudly rather than returning rubbish")
    void wrongPassphraseThrows(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("agent.oppex-credentials");
        CredentialStore.save(file, KEY, PASSPHRASE);
        assertThrows(IllegalStateException.class, () -> CredentialStore.load(file, "not it"));
    }

    @Test
    @DisplayName("a tampered file is detected — AES-GCM is authenticated, not just encrypted")
    void tamperingIsDetected(@TempDir Path dir) throws IOException {
        final Path file = dir.resolve("agent.oppex-credentials");
        CredentialStore.save(file, KEY, PASSPHRASE);

        final String original = Files.readString(file).trim();
        // Flip one character of the base64 payload, near the end so it lands in the ciphertext
        // rather than the salt.
        final int at = original.length() - 6;
        final char replacement = original.charAt(at) == 'A' ? 'B' : 'A';
        Files.writeString(file, original.substring(0, at) + replacement + original.substring(at + 1));

        assertThrows(IllegalStateException.class, () -> CredentialStore.load(file, PASSPHRASE));
    }

    @Test
    @DisplayName("the same key encrypts differently each time — the salt and IV are random")
    void saltAndIvAreRandom(@TempDir Path dir) throws IOException {
        final Path a = dir.resolve("a.oppex-credentials");
        final Path b = dir.resolve("b.oppex-credentials");
        CredentialStore.save(a, KEY, PASSPHRASE);
        CredentialStore.save(b, KEY, PASSPHRASE);
        assertNotEquals(Files.readString(a), Files.readString(b));
    }

    @Test
    @DisplayName("a blank passphrase is refused rather than silently producing decorative encryption")
    void blankPassphraseRefused(@TempDir Path dir) {
        final Path file = dir.resolve("x.oppex-credentials");
        assertThrows(IllegalStateException.class, () -> CredentialStore.save(file, KEY, ""));
        assertThrows(IllegalStateException.class, () -> CredentialStore.save(file, KEY, null));
    }
}
