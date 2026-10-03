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
 * <p>The {@code pairingSecret} is the <b>pre-shared</b> workspace secret the admin issued alongside the
 * client id; it is sent on <b>every</b> request (CREATE and POLL) and the platform verifies it. A CREATE
 * (no {@code pairingId}) sends {@code clientId} + {@code pairingSecret} + {@code name} + {@code version};
 * a POLL adds the {@code pairingId} the CREATE returned. {@code clientId} alone never yields a token.
 */
public record PairRequest(String clientId, String name, String version, String pairingId, String pairingSecret) {

    /** CREATE: identify the workspace + this instance and prove identity with the pre-shared secret. */
    public static PairRequest create(final String clientId, final String pairingSecret, final String name, final String version) {
        return new PairRequest(clientId, name, version, null, pairingSecret);
    }

    /** POLL: ask for the outcome of the in-flight pairing, re-proving with the pre-shared secret. */
    public static PairRequest poll(final String clientId, final String pairingId, final String pairingSecret) {
        return new PairRequest(clientId, null, null, pairingId, pairingSecret);
    }
}
