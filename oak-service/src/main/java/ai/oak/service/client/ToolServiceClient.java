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

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/**
 * Vendor-neutral client for the remote tool protocol ({@code /v1/tools}).
 *
 * <p>It is a client for <em>the protocol</em>, not for one vendor: give it a base URL and it talks to
 * anything that implements the endpoints — Oppex today, another platform tomorrow. It always dials out
 * (the service calls the platform, never the reverse), over the JDK HTTP client.
 *
 * <p>{@link #pair} is anonymous — it is how the service earns its token. Every other call is
 * authenticated with that token in {@code X-API-KEY}; set it with {@link #setToken} once pairing
 * provides one. A {@code 401} on an authenticated call surfaces as an
 * {@link UnauthorizedException} so the caller can drop the token and re-pair.
 */
public final class ToolServiceClient implements AutoCloseable {

    /** {@code /v1/tools}, the platform's top-level path for this API. */
    public static final String TOOLS_PATH = "/v1/tools";
    public static final String API_KEY_HEADER = "X-API-KEY";

    private static final String PAIR = "/pair";
    private static final String REGISTER = "/register";
    private static final String NEXT_STEP = "/steps/next";
    private static final String STEP_RESULT = "/steps/result";
    private static final String DISCOVERY = "/discovery";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String platformName;
    private final HttpClient http;
    private final ObjectMapper mapper;
    private volatile String token;

    public ToolServiceClient(final String baseUrl, final String platformName) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.platformName = platformName;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                // Do not follow redirects: a redirect on an authenticated API call is either a
                // misconfiguration or something interposing, and replaying the token to wherever it
                // points is not a thing to do quietly.
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.mapper = JsonMapper.builder().addModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                // A pairing status the client doesn't know maps to PairStatus.UNKNOWN (give up this
                // attempt) rather than throwing — a new server status can never strand the client.
                .configure(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE, true)
                .serializationInclusion(JsonInclude.Include.NON_NULL).build();
    }

    /** Sets the token used for authenticated calls (obtained from pairing). */
    public void setToken(final String token) {
        this.token = token;
    }

    /** Anonymous pairing call. Returns the platform's current view of this pairing attempt. */
    public PairResponse pair(final PairRequest request) throws IOException, InterruptedException {
        final HttpResponse<String> raw = send(anon(PAIR).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request))));
        requireSuccess(raw, "pair");
        final ApiResponse<PairResponse> body = mapper.readValue(raw.body(), new TypeReference<ApiResponse<PairResponse>>() {
        });
        if (body == null || body.data() == null) {
            throw new IOException("pair returned no body from " + platformName);
        }
        return body.data();
    }

    /** Announces this service and the capabilities it can run. */
    public ToolServiceRegistrationResponse register(final ToolServiceRegistrationRequest request)
            throws IOException, InterruptedException {
        final ApiResponse<ToolServiceRegistrationResponse> body = post(REGISTER, request, new TypeReference<>() {
        });
        if (body != null && !body.success()) {
            throw new IOException("Registration refused by " + platformName + ": " + body.message());
        }
        return body == null ? null : body.data();
    }

    /** Asks for the next step. Empty when there is nothing to do, which is the normal idle case. */
    public Optional<RemoteStep> nextStep() throws IOException, InterruptedException {
        final HttpResponse<String> raw = send(authed(NEXT_STEP).GET());
        if (raw.statusCode() == 204) {
            return Optional.empty();
        }
        requireSuccess(raw, "next step");
        if (raw.body() == null || raw.body().isBlank()) {
            return Optional.empty();
        }
        final ApiResponse<RemoteStep> body = mapper.readValue(raw.body(), new TypeReference<ApiResponse<RemoteStep>>() {
        });
        return Optional.ofNullable(body == null ? null : body.data());
    }

    /** Reports the outcome of a step. */
    public void reportResult(final RemoteStepResultRequest result) throws IOException, InterruptedException {
        post(STEP_RESULT, result, new TypeReference<ApiResponse<Void>>() {
        });
    }

    /**
     * Reports the full infrastructure snapshot (facts only). The platform stores it and does its own
     * change-detection; this service always sends the complete set, never a diff. The returned
     * acknowledgement is informational (whether it changed); success is the only thing that matters.
     */
    public DiscoverySnapshotResponse postDiscovery(final DiscoverySnapshot snapshot) throws IOException, InterruptedException {
        final ApiResponse<DiscoverySnapshotResponse> body = post(DISCOVERY, snapshot, new TypeReference<>() {
        });
        return body == null ? null : body.data();
    }

    public String platformName() {
        return platformName;
    }

    private <T> ApiResponse<T> post(final String path, final Object payload, final TypeReference<ApiResponse<T>> type)
            throws IOException, InterruptedException {
        final HttpResponse<String> raw = send(authed(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))));
        requireSuccess(raw, path);
        return raw.body() == null || raw.body().isBlank() ? null : mapper.readValue(raw.body(), type);
    }

    private HttpRequest.Builder authed(final String path) {
        return anon(path).header(API_KEY_HEADER, token == null ? "" : token);
    }

    private HttpRequest.Builder anon(final String path) {
        return HttpRequest.newBuilder().uri(URI.create(baseUrl + TOOLS_PATH + path)).timeout(REQUEST_TIMEOUT);
    }

    private HttpResponse<String> send(final HttpRequest.Builder builder) throws IOException, InterruptedException {
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Turns a non-2xx into an exception; a 401/403 becomes {@link UnauthorizedException}. */
    private void requireSuccess(final HttpResponse<String> response, final String what) throws IOException {
        final int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return;
        }
        String detail = response.body() == null ? "" : response.body().trim();
        try {
            final ApiResponse<Object> parsed = mapper.readValue(detail, new TypeReference<ApiResponse<Object>>() {
            });
            if (parsed != null && parsed.message() != null) {
                detail = parsed.message();
            }
        } catch (IOException ignored) {
            if (detail.length() > 300) {
                detail = detail.substring(0, 300) + "…";
            }
        }
        if (status == 401 || status == 403) {
            throw new UnauthorizedException(what + " rejected (HTTP " + status + "): " + detail);
        }
        throw new IOException(what + " failed (HTTP " + status + "): " + detail);
    }

    private static String stripTrailingSlash(final String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    @Override
    public void close() {
        // HttpClient holds no resource needing explicit release on Java 17; the method exists so
        // callers can use try-with-resources and stay correct if that changes.
    }

    /** A 401/403 on an authenticated call — the token is invalid or was revoked; the caller re-pairs. */
    public static final class UnauthorizedException extends IOException {
        public UnauthorizedException(final String message) {
            super(message);
        }
    }
}
