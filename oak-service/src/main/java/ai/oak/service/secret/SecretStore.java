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
package ai.oak.service.secret;

import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Encrypts short secrets — the pairing secret and the dedicated API token — for storage at rest.
 * AES-256-GCM, JDK crypto only.
 *
 * <p>The key comes from one of two places, in order:
 *
 * <ol>
 *   <li>{@code OAK_SECRET_PASSPHRASE} in the environment — the key is derived from it with PBKDF2 over
 *       a per-install salt persisted next to the data. This is the recommended setup: the ciphertext on
 *       disk is useless without the passphrase, which lives in the environment / a secret manager.</li>
 *   <li>otherwise a random key generated once and stored {@code 0600} in {@code oak.key} beside the
 *       data. Convenient, but the key sits next to what it protects, so it guards against a stray copy
 *       of the data (a backup, a support bundle) — not against someone who already has the host.</li>
 * </ol>
 *
 * <p>Ciphertext is {@code base64(iv || ciphertext+tag)}. {@link #encrypt}/{@link #decrypt} pass null
 * and blank through unchanged so callers can store "no secret" without special-casing.
 */
@ApplicationScoped
public class SecretStore {

    private static final Logger LOG = LoggerFactory.getLogger(SecretStore.class);
    private static final String PASSPHRASE_ENV = "OAK_SECRET_PASSPHRASE";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;
    private static final int KEY_BYTES = 32;
    private static final int SALT_BYTES = 16;
    private static final int PBKDF2_ITERATIONS = 210_000;

    private final SecureRandom random = new SecureRandom();

    @ConfigProperty(name = "oak.data-dir")
    Optional<String> dataDir;

    private SecretKey key;

    @PostConstruct
    void init() {
        try {
            final Path dir = dataDir.filter(d -> !d.isBlank()).map(Path::of)
                    .orElseGet(() -> Path.of(System.getProperty("user.home", "."), ".oak"));
            Files.createDirectories(dir);
            final String passphrase = System.getenv(PASSPHRASE_ENV);
            if (passphrase != null && !passphrase.isBlank()) {
                key = deriveFromPassphrase(passphrase, saltFile(dir));
                LOG.info("Secret store: key derived from {}", PASSPHRASE_ENV);
            } else {
                key = loadOrCreateRandomKey(dir.resolve("oak.key"));
                LOG.warn("Secret store: no {} set; using a local key file. Set a passphrase in production.", PASSPHRASE_ENV);
            }
        } catch (IOException | GeneralSecurityRuntimeException e) {
            throw new IllegalStateException("Could not initialise the secret store", e);
        }
    }

    /** Encrypts {@code plaintext} to base64; null/blank pass through unchanged. */
    public String encrypt(final String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return plaintext;
        }
        try {
            final byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            final byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            final byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    /** Decrypts what {@link #encrypt} produced; null/blank pass through unchanged. */
    public String decrypt(final String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) {
            return ciphertext;
        }
        try {
            final byte[] all = Base64.getDecoder().decode(ciphertext);
            final byte[] iv = new byte[IV_BYTES];
            System.arraycopy(all, 0, iv, 0, IV_BYTES);
            final Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_BITS, iv));
            final byte[] plain = cipher.doFinal(all, IV_BYTES, all.length - IV_BYTES);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (java.security.GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Decryption failed (wrong passphrase or key, or corrupt data)", e);
        }
    }

    private SecretKey deriveFromPassphrase(final String passphrase, final Path saltFile) throws IOException {
        final byte[] salt = readOrCreate(saltFile, SALT_BYTES);
        try {
            final SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            final KeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_BYTES * 8);
            return new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
        } catch (java.security.GeneralSecurityException e) {
            throw new GeneralSecurityRuntimeException(e);
        }
    }

    private SecretKey loadOrCreateRandomKey(final Path keyFile) throws IOException {
        return new SecretKeySpec(readOrCreate(keyFile, KEY_BYTES), "AES");
    }

    private Path saltFile(final Path dir) {
        return dir.resolve("oak.salt");
    }

    /** Reads {@code length} bytes from {@code file}, or creates it with fresh random bytes ({@code 0600}). */
    private byte[] readOrCreate(final Path file, final int length) throws IOException {
        if (Files.exists(file)) {
            final byte[] existing = Files.readAllBytes(file);
            if (existing.length == length) {
                return existing;
            }
            LOG.warn("{} has unexpected length {}; regenerating", file, existing.length);
        }
        final byte[] fresh = new byte[length];
        random.nextBytes(fresh);
        writeAtomic(file, fresh);
        return fresh;
    }

    /**
     * Write {@code bytes} to {@code file} atomically (temp file, then rename) with {@code 0600}. Atomicity
     * matters because a half-written {@code oak.key} — say a crash mid-write — would come back the wrong
     * length on the next boot and be regenerated, silently orphaning everything encrypted with the old key.
     */
    private void writeAtomic(final Path file, final byte[] bytes) throws IOException {
        final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-------"));
        } catch (IOException | UnsupportedOperationException ignore) {
            // non-POSIX filesystem — best effort
        }
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException | UnsupportedOperationException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Unchecked wrapper so {@link #init} can funnel both IO and crypto setup failures into one catch. */
    private static final class GeneralSecurityRuntimeException extends RuntimeException {
        GeneralSecurityRuntimeException(final Throwable cause) {
            super(cause);
        }
    }
}
