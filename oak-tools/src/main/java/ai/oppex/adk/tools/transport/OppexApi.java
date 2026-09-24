package ai.oppex.adk.tools.transport;

import ai.oppex.adk.tools.config.VendorConfig;
import ai.oppex.adk.tools.protocol.ApiResponse;
import ai.oppex.adk.tools.protocol.ConnectResponse;
import ai.oppex.adk.tools.protocol.RegistrationRequest;
import ai.oppex.adk.tools.protocol.RemoteStep;
import ai.oppex.adk.tools.protocol.StepResult;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The HTTP half of the protocol: registration, the connect handshake, polling, and reporting
 * results. Shared by both transports.
 *
 * <p>Uses the JDK's own {@link HttpClient}. No HTTP library is pulled in, which matters for a jar
 * that runs inside somebody else's application — the commonest way to break a host process is to
 * drag in a second copy of a library it already uses at a different version.
 */
public class OppexApi implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(OppexApi.class);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final VendorConfig vendor;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public OppexApi(VendorConfig vendor) {
        this.vendor = vendor;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                // Do not follow redirects: a redirect on an authenticated API call is either a
                // misconfiguration or something interposing, and silently replaying the API key to
                // wherever it points is not a thing to do quietly.
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        this.mapper = new ObjectMapper()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    }

    /** Announces this agent and the capabilities it can run. */
    public void register(RegistrationRequest request) throws IOException, InterruptedException {
        final ApiResponse<Object> response = post(Paths.REGISTER, request, new TypeReference<>() { });
        log.info("Registered with {}: {} capabilities declared", vendor.getName(),
                request.getCapabilities().size());
        if (response != null && response.getMessage() != null && !response.isSuccess()) {
            throw new IOException("Registration refused: " + response.getMessage());
        }
    }

    /**
     * Exchanges the API key for a WebSocket URL and a short-lived ticket.
     *
     * @return null if this deployment has no WebSocket endpoint — an older Oppex, or one where it
     *         is not enabled. The caller falls back to polling rather than treating it as an error.
     */
    public ConnectResponse connect() throws IOException, InterruptedException {
        final HttpResponse<String> raw = send(HttpRequest.newBuilder()
                .uri(uri(Paths.CONNECT))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.noBody()));
        if (raw.statusCode() == 404) {
            log.info("{} has no /v1/tools/connect endpoint — falling back to polling", vendor.getName());
            return null;
        }
        requireSuccess(raw, "connect");
        final ApiResponse<ConnectResponse> body =
                mapper.readValue(raw.body(), new TypeReference<ApiResponse<ConnectResponse>>() { });
        return body == null ? null : body.getData();
    }

    /** Asks for the next step. Returns null when there is nothing to do, which is normal. */
    public RemoteStep nextStep() throws IOException, InterruptedException {
        final HttpResponse<String> raw = send(HttpRequest.newBuilder()
                .uri(uri(Paths.NEXT_STEP))
                .GET());
        requireSuccess(raw, "next step");
        final ApiResponse<RemoteStep> body =
                mapper.readValue(raw.body(), new TypeReference<ApiResponse<RemoteStep>>() { });
        return body == null ? null : body.getData();
    }

    /** Reports the outcome of a step. */
    public void reportResult(StepResult result) throws IOException, InterruptedException {
        post(Paths.STEP_RESULT, result, new TypeReference<ApiResponse<Object>>() { });
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public VendorConfig vendor() {
        return vendor;
    }

    private <T> ApiResponse<T> post(String path, Object payload, TypeReference<ApiResponse<T>> type)
            throws IOException, InterruptedException {
        final HttpResponse<String> raw = send(HttpRequest.newBuilder()
                .uri(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))));
        requireSuccess(raw, path);
        return raw.body() == null || raw.body().isBlank() ? null : mapper.readValue(raw.body(), type);
    }

    private HttpResponse<String> send(HttpRequest.Builder builder) throws IOException, InterruptedException {
        return http.send(
                builder.header(Paths.API_KEY_HEADER, vendor.getApiKey()).timeout(REQUEST_TIMEOUT).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Turns a non-2xx into an exception carrying the server's own {@code message}.
     *
     * <p>Oppex populates {@code message} on every error with text written for a person, so it is
     * surfaced verbatim instead of being replaced with something derived from the status code. The
     * body is truncated because an unexpected 502 from something in the middle returns an HTML
     * page, and a full one buries the log.
     */
    private void requireSuccess(HttpResponse<String> response, String what) throws IOException {
        final int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        String detail = response.body() == null ? "" : response.body().trim();
        try {
            final ApiResponse<Object> parsed =
                    mapper.readValue(detail, new TypeReference<ApiResponse<Object>>() { });
            if (parsed != null && parsed.getMessage() != null) {
                detail = parsed.getMessage();
            }
        } catch (IOException ignored) {
            if (detail.length() > 300) {
                detail = detail.substring(0, 300) + "…";
            }
        }
        if (status == 401 || status == 403) {
            throw new IOException(what + " rejected (HTTP " + status + "): " + detail
                    + " — check the API key is correct, active, and belongs to this workspace.");
        }
        throw new IOException(what + " failed (HTTP " + status + "): " + detail);
    }

    private URI uri(String path) {
        final String base = vendor.getBaseUrl();
        if (base == null || base.isBlank()) {
            throw new IllegalStateException("Vendor " + vendor.getName() + " has no baseUrl configured");
        }
        return URI.create(base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path);
    }

    @Override
    public void close() {
        // HttpClient holds no resource needing explicit release on Java 17; the method exists so
        // callers can use try-with-resources and stay correct if that changes.
    }
}
