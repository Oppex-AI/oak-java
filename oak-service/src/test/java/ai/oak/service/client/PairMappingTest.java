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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

/** The pairing DTOs must mirror the {@code /v1/tools/pair} wire shapes and never strand on a new status. */
class PairMappingTest {

    private final ObjectMapper mapper = JsonMapper.builder().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true)
            .serializationInclusion(JsonInclude.Include.NON_NULL).build();

    @Test
    void createRequestCarriesThePreSharedSecretAndNoPairingId() throws Exception {
        final String json = mapper.writeValueAsString(PairRequest.create("client-123", "secret-1", "oak-service", "0.1.0"));
        assertTrue(json.contains("\"clientId\":\"client-123\""));
        assertTrue(json.contains("\"name\":\"oak-service\""));
        assertTrue(json.contains("\"pairingSecret\":\"secret-1\""));
        assertFalse(json.contains("pairingId"));
    }

    @Test
    void pollRequestCarriesIdAndSecretButNotName() throws Exception {
        final String json = mapper.writeValueAsString(PairRequest.poll("client-123", "pair-1", "secret-1"));
        assertTrue(json.contains("\"clientId\":\"client-123\""));
        assertTrue(json.contains("\"pairingId\":\"pair-1\""));
        assertTrue(json.contains("\"pairingSecret\":\"secret-1\""));
        assertFalse(json.contains("\"name\""));
    }

    @Test
    void pendingResponseParses() throws Exception {
        final String wire = """
                {"success":true,"code":200,"data":{"status":"PENDING_APPROVAL","pairingId":"p1"}}""";
        final ApiResponse<PairResponse> r = mapper.readValue(wire, new TypeReference<ApiResponse<PairResponse>>() {
        });
        assertEquals(PairStatus.PENDING_APPROVAL, r.data().status());
        assertEquals("p1", r.data().pairingId());
    }

    @Test
    void approvedResponseCarriesTokenAndConnectionId() throws Exception {
        final String wire = """
                {"success":true,"code":200,"data":{"status":"APPROVED","connectionId":"conn-9","token":"tok-abc"}}""";
        final ApiResponse<PairResponse> r = mapper.readValue(wire, new TypeReference<ApiResponse<PairResponse>>() {
        });
        assertEquals(PairStatus.APPROVED, r.data().status());
        assertEquals("conn-9", r.data().connectionId());
        assertEquals("tok-abc", r.data().token());
    }

    @Test
    void anUnknownStatusBecomesUnknownRatherThanThrowing() throws Exception {
        final PairResponse r = mapper.readValue("{\"status\":\"SOMETHING_NEW\"}", PairResponse.class);
        assertEquals(PairStatus.UNKNOWN, r.status());
    }
}
