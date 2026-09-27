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
package ai.oak.service.client;

/**
 * Body of {@code POST /v1/tools/pair}. Anonymous — it carries no API key, because pairing is how the
 * service earns one.
 *
 * <p>The FIRST request sends only {@code clientId} + {@code name} + {@code version} (pairingId/secret
 * null, omitted on the wire). Every POLL after that sends {@code clientId} + {@code pairingId} +
 * {@code pairingSecret} together — only all three together may claim the token; the clientId alone
 * cannot.
 */
public record PairRequest(String clientId, String name, String version, String pairingId, String pairingSecret) {

    /** The initial claim: identify the workspace and this instance, ask to be paired. */
    public static PairRequest first(final String clientId, final String name, final String version) {
        return new PairRequest(clientId, name, version, null, null);
    }

    /** A poll for the outcome of an in-flight pairing, proving instance identity with the secret. */
    public static PairRequest poll(final String clientId, final String pairingId, final String pairingSecret) {
        return new PairRequest(clientId, null, null, pairingId, pairingSecret);
    }
}
