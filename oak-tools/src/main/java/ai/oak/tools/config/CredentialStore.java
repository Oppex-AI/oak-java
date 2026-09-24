package ai.oak.tools.config;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Stores the Oppex API key on disk encrypted, rather than in plain text.
 *
 * <h2>Be clear about what this protects against</h2>
 *
 * <p>The agent has to decrypt this unattended, so the passphrase must also be reachable on the same
 * host. That means this <strong>does not protect against an attacker who already has your
 * host</strong> — root, or the ability to read the agent's environment, gets the key either way.
 * Anyone who tells you otherwise is selling something.
 *
 * <p>What it genuinely buys you, which is not nothing:
 *
 * <ul>
 *   <li>The key is not sitting in a config file that gets read aloud in a screen share, pasted into
 *       a support ticket, or swept into a diagnostic bundle.
 *   <li>The key is not in a filesystem backup or a snapshot in recoverable form.
 *   <li>An accidental {@code git add} of the credentials file commits ciphertext, not a live key.
 *   <li>The ciphertext and the passphrase live in different places — file versus environment — so
 *       one leaking is not enough.
 * </ul>
 *
 * <p>The passphrase comes from an environment variable, which is what keeps it out of the backup
 * that contains the file. Set it from your init system, or from whatever secret manager you already
 * run.
 *
 * <h2>Format</h2>
 *
 * <p>Base64 of {@code salt(16) || iv(12) || AES-256-GCM ciphertext}. AES-GCM is authenticated, so a
 * corrupted or tampered file fails to decrypt rather than yielding rubbish. The key is derived with
 * PBKDF2-HMAC-SHA256 at 600,000 iterations, which is the current OWASP guidance and costs about
 * half a second once at startup.
 */
public final class CredentialStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialStore.class);

    /** Environment variable holding the passphrase that unlocks the file. */
    public static final String PASSPHRASE_ENV = "OPPEX_ADK_PASSPHRASE";

    private static final String KDF = "PBKDF2WithHmacSHA256";
    private static final int ITERATIONS = 600_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private CredentialStore() {
    }

    /**
     * Encrypts {@code apiKey} into {@code file}, readable only by the current user.
     *
     * @throws IllegalStateException if the passphrase environment variable is not set
     */
    public static void save(Path file, String apiKey, String passphrase) throws IOException {
        requirePassphrase(passphrase);
        final byte[] salt = new byte[SALT_BYTES];
        final byte[] iv = new byte[IV_BYTES];
        RANDOM.nextBytes(salt);
        RANDOM.nextBytes(iv);

        final byte[] cipherText;
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(TAG_BITS, iv));
            cipherText = cipher.doFinal(apiKey.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the credentials file", e);
        }

        final byte[] blob = ByteBuffer.allocate(salt.length + iv.length + cipherText.length)
                .put(salt).put(iv).put(cipherText).array();

        final Path parent = file.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(file, Base64.getEncoder().encodeToString(blob), StandardCharsets.US_ASCII);
        restrictToOwner(file);
        log.info("Credentials written to {} (encrypted, owner-only)", file.toAbsolutePath());
    }

    /** Reads and decrypts the API key. */
    public static String load(Path file, String passphrase) throws IOException {
        requirePassphrase(passphrase);
        final byte[] blob = Base64.getDecoder().decode(Files.readString(file, StandardCharsets.US_ASCII).trim());
        if (blob.length <= SALT_BYTES + IV_BYTES) {
            throw new IllegalStateException("Credentials file " + file + " is truncated or not a credentials file");
        }
        final byte[] salt = Arrays.copyOfRange(blob, 0, SALT_BYTES);
        final byte[] iv = Arrays.copyOfRange(blob, SALT_BYTES, SALT_BYTES + IV_BYTES);
        final byte[] cipherText = Arrays.copyOfRange(blob, SALT_BYTES + IV_BYTES, blob.length);
        try {
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, deriveKey(passphrase, salt), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            // Deliberately does not distinguish "wrong passphrase" from "tampered file" — both mean
            // the same thing to an operator, and distinguishing them tells an attacker which of the
            // two they got right.
            throw new IllegalStateException(
                    "Could not decrypt " + file + ". Wrong " + PASSPHRASE_ENV + ", or the file has been altered.", e);
        }
    }

    /** Reads the passphrase from the environment. */
    public static String passphraseFromEnv() {
        final String value = System.getenv(PASSPHRASE_ENV);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(PASSPHRASE_ENV + " is not set. It is the passphrase that "
                    + "unlocks the credentials file; set it from your init system or secret manager.");
        }
        return value;
    }

    private static void requirePassphrase(String passphrase) {
        if (passphrase == null || passphrase.isBlank()) {
            throw new IllegalStateException("A blank passphrase would make the encryption decorative. Refusing.");
        }
    }

    private static SecretKey deriveKey(String passphrase, byte[] salt) throws GeneralSecurityException {
        final PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, KEY_BITS);
        try {
            return new SecretKeySpec(SecretKeyFactory.getInstance(KDF).generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }

    /**
     * 0600 where the filesystem supports it. Silently skipped on Windows and other non-POSIX
     * filesystems, which is why the encryption is the actual protection and the permission bits are
     * a second layer rather than the first.
     */
    private static void restrictToOwner(Path file) {
        try {
            final Set<PosixFilePermission> ownerOnly =
                    EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
            Files.setPosixFilePermissions(file, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            log.warn("Could not restrict permissions on {} ({}). The file contents are still encrypted.",
                    file, e.getMessage());
        }
    }
}
