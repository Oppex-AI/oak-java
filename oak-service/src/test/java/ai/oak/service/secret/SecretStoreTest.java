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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SecretStoreTest {

    private SecretStore store(final Path dir) {
        final SecretStore s = new SecretStore();
        s.dataDir = Optional.of(dir.toString());
        s.init();
        return s;
    }

    @Test
    void roundTripsASecret(@TempDir final Path dir) {
        final SecretStore s = store(dir);
        final String token = "dedicated-api-token-xyz";
        final String ct = s.encrypt(token);
        assertNotEquals(token, ct, "ciphertext must not equal plaintext");
        assertEquals(token, s.decrypt(ct));
    }

    @Test
    void nullAndBlankPassThrough(@TempDir final Path dir) {
        final SecretStore s = store(dir);
        assertNull(s.encrypt(null));
        assertEquals("", s.encrypt(""));
        assertNull(s.decrypt(null));
    }

    @Test
    void eachEncryptionIsFreshButDecryptsTheSame(@TempDir final Path dir) {
        final SecretStore s = store(dir);
        final String a = s.encrypt("same");
        final String b = s.encrypt("same");
        assertNotEquals(a, b, "random IV should make ciphertexts differ");
        assertEquals("same", s.decrypt(a));
        assertEquals("same", s.decrypt(b));
    }

    @Test
    void aNewStoreOnTheSameDirDecryptsWhatTheOldOneWrote(@TempDir final Path dir) {
        final String ct = store(dir).encrypt("persisted");
        assertEquals("persisted", store(dir).decrypt(ct), "key persisted in the dir must be reused");
    }

    @Test
    void keyFileIsCreatedInTheDataDir(@TempDir final Path dir) {
        store(dir).encrypt("x");
        assertTrue(java.nio.file.Files.exists(dir.resolve("oak.key")), "a local key file should be created");
    }
}
