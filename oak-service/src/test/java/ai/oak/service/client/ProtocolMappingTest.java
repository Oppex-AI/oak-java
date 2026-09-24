package ai.oak.service.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.tools.ToolPermission;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The restated DTOs must mirror the platform's {@code /v1/tools} wire shapes exactly, since nothing
 * shares a type across the repo boundary. These assertions are that guarantee: field names and the
 * response envelope, checked against JSON written the way the platform writes it.
 */
class ProtocolMappingTest {

    private final ObjectMapper mapper = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .serializationInclusion(JsonInclude.Include.NON_NULL)
            .build();

    @Test
    void registrationRequestSerialisesToTheExpectedShape() throws Exception {
        final var request = new ToolServiceRegistrationRequest("oak-service", "0.1.0",
                List.of(new CapabilityDeclaration("AWS_EC2_START_INSTANCES", ToolPermission.WRITE, "Start EC2 instances.")));

        final String json = mapper.writeValueAsString(request);
        final Map<String, Object> parsed = mapper.readValue(json, new TypeReference<>() { });

        assertEquals("oak-service", parsed.get("name"));
        assertEquals("0.1.0", parsed.get("version"));
        @SuppressWarnings("unchecked")
        final var caps = (List<Map<String, Object>>) parsed.get("capabilities");
        assertEquals("AWS_EC2_START_INSTANCES", caps.get(0).get("capability"));
        assertEquals("WRITE", caps.get(0).get("permission"));
        assertEquals("Start EC2 instances.", caps.get(0).get("description"));
    }

    @Test
    void nextStepEnvelopeParsesIntoRemoteStep() throws Exception {
        final String wire = """
                {"success":true,"code":200,"message":null,
                 "data":{"workflowId":42,"taskId":7,"capability":"AWS_EC2_START_INSTANCES",
                         "input":{"instanceIds":["i-1","i-2"],"region":"us-west-2"},
                         "referenceType":"INCIDENT","referenceId":"INC-9","sequenceOrder":1}}""";

        final ApiResponse<RemoteStep> response =
                mapper.readValue(wire, new TypeReference<ApiResponse<RemoteStep>>() { });

        assertTrue(response.success());
        final RemoteStep step = response.data();
        assertEquals(42L, step.workflowId());
        assertEquals(7L, step.taskId());
        assertEquals("AWS_EC2_START_INSTANCES", step.capability());
        assertEquals(List.of("i-1", "i-2"), step.input().get("instanceIds"));
        assertEquals("INC-9", step.referenceId());
    }

    @Test
    void idlePollHasNullData() throws Exception {
        final ApiResponse<RemoteStep> response = mapper.readValue(
                "{\"success\":true,\"code\":200,\"data\":null}", new TypeReference<ApiResponse<RemoteStep>>() { });
        assertTrue(response.success());
        assertEquals(null, response.data());
    }

    @Test
    void registrationResponseParsesInstant() throws Exception {
        final String wire = """
                {"success":true,"code":200,"data":{"id":3,"name":"oak-service","version":"0.1.0",
                 "lastSeenAt":"2026-09-24T10:15:30Z","capabilities":["AWS_EC2_START_INSTANCES"]}}""";

        final ApiResponse<ToolServiceRegistrationResponse> response =
                mapper.readValue(wire, new TypeReference<ApiResponse<ToolServiceRegistrationResponse>>() { });

        assertEquals(Instant.parse("2026-09-24T10:15:30Z"), response.data().lastSeenAt());
        assertEquals(List.of("AWS_EC2_START_INSTANCES"), response.data().capabilities());
    }

    @Test
    void resultRequestOmitsNullErrorMessage() throws Exception {
        final var success = new RemoteStepResultRequest(1L, 2L, TaskStatus.SUCCESS, Map.of("exitCode", 0), null);
        final String json = mapper.writeValueAsString(success);
        assertTrue(json.contains("\"status\":\"SUCCESS\""));
        assertFalse(json.contains("errorMessage"));
    }
}
