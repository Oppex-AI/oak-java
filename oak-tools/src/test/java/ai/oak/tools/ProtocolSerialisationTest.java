package ai.oak.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ai.oak.tools.protocol.ApiResponse;
import ai.oak.tools.protocol.RemoteStep;
import ai.oak.tools.protocol.StepResult;
import ai.oak.tools.protocol.StepStatus;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * These matter more than they look. The SDK restates Oppex's DTOs rather than sharing them, so a
 * server-side rename is caught by nothing at compile time. These tests pin the wire shape.
 */
class ProtocolSerialisationTest {

    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    @DisplayName("a step envelope from Oppex parses into RemoteStep")
    void parsesStepEnvelope() throws Exception {
        final String json = """
            {"success":true,"statusCode":200,"message":"ok","data":{
               "workflowId":12,"taskId":57,"capability":"FIND_ASG",
               "input":{"serviceKey":"payment-service"},
               "referenceType":"INCIDENT","referenceId":"INC-901","sequenceOrder":1}}
            """;
        final ApiResponse<RemoteStep> response =
                mapper.readValue(json, new TypeReference<ApiResponse<RemoteStep>>() { });

        assertTrue(response.isSuccess());
        final RemoteStep step = response.getData();
        assertNotNull(step);
        assertEquals(57L, step.getTaskId());
        assertEquals("FIND_ASG", step.getCapability());
        assertEquals("payment-service", step.getInput().get("serviceKey"));
        assertEquals("INC-901", step.getReferenceId());
    }

    @Test
    @DisplayName("an idle queue is success with null data, not an error")
    void idleQueueIsNullData() throws Exception {
        final String json = """
            {"success":true,"statusCode":200,"message":"No step available","data":null}
            """;
        final ApiResponse<RemoteStep> response =
                mapper.readValue(json, new TypeReference<ApiResponse<RemoteStep>>() { });

        assertTrue(response.isSuccess());
        assertNull(response.getData());
    }

    @Test
    @DisplayName("an unknown field from a newer Oppex does not break an older agent")
    void unknownFieldsAreIgnored() throws Exception {
        // The agent runs on a customer host and is upgraded on their schedule, so it will meet a
        // platform newer than itself. Adding a field server-side must not take agents offline.
        final String json = """
            {"workflowId":1,"taskId":2,"capability":"FIND_ASG","input":{},
             "somethingAddedNextYear":{"nested":true}}
            """;
        final RemoteStep step = mapper.readValue(json, RemoteStep.class);
        assertEquals(2L, step.getTaskId());
    }

    @Test
    @DisplayName("a null input map becomes empty, so a capability never sees null")
    void nullInputBecomesEmpty() throws Exception {
        final RemoteStep step = mapper.readValue(
                "{\"workflowId\":1,\"taskId\":2,\"capability\":\"X\",\"input\":null}", RemoteStep.class);
        assertNotNull(step.getInput());
        assertTrue(step.getInput().isEmpty());
    }

    @Test
    @DisplayName("a result serialises with the ids Oppex re-checks ownership against")
    void resultCarriesBothIds() throws Exception {
        final RemoteStep step = new RemoteStep();
        step.setWorkflowId(12L);
        step.setTaskId(57L);

        final String json = mapper.writeValueAsString(
                StepResult.success(step, Map.of("asgName", "payment-asg")));

        final Map<String, Object> parsed = mapper.readValue(json, new TypeReference<>() { });
        assertEquals(12, parsed.get("workflowId"));
        assertEquals(57, parsed.get("taskId"));
        assertEquals("SUCCESS", parsed.get("status"));
    }

    @Test
    @DisplayName("a failure carries the message and no output")
    void failureCarriesMessage() {
        final RemoteStep step = new RemoteStep();
        step.setWorkflowId(1L);
        step.setTaskId(2L);

        final StepResult result = StepResult.failure(step, "no such ASG");
        assertEquals(StepStatus.FAILED, result.getStatus());
        assertEquals("no such ASG", result.getErrorMessage());
        assertTrue(result.getOutput().isEmpty());
    }

    @Test
    @DisplayName("the API key never appears in a config object's toString")
    void configDoesNotLeakTheKey() {
        final var vendor = new ai.oak.tools.config.VendorConfig(
                "oppex", "https://api.example", "oppex_live_secret_value");
        assertTrue(vendor.toString().indexOf("oppex_live_secret_value") < 0,
                "VendorConfig.toString() leaked the API key");
    }
}
