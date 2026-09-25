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
 * Vendor-neutral client for the remote tool-execution protocol ({@code /v1/tools}).
 *
 * <p>It is a client for <em>the protocol</em>, not for one vendor: the base URL and API key are given
 * to it, so today it talks to Oppex and tomorrow to anything that implements the same endpoints. It
 * always dials out — the tool service calls the platform, never the reverse — over the JDK HTTP client,
 * with the API key in {@code X-API-KEY}. WebSocket push is a later optimisation; polling is the baseline.
 */
public final class ToolServiceClient implements AutoCloseable {

    /** {@code /v1/tools}, the platform's top-level path for this API. */
    public static final String TOOLS_PATH = "/v1/tools";
    public static final String API_KEY_HEADER = "X-API-KEY";

    private static final String REGISTER = "/register";
    private static final String NEXT_STEP = "/steps/next";
    private static final String STEP_RESULT = "/steps/result";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String apiKey;
    private final String platformName;
    private final HttpClient http;
    private final ObjectMapper mapper;

    public ToolServiceClient(final String baseUrl, final String apiKey, final String platformName) {
        this.baseUrl = stripTrailingSlash(baseUrl);
        this.apiKey = apiKey;
        this.platformName = platformName;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                // Do not follow redirects: a redirect on an authenticated API call is either a
                // misconfiguration or something interposing, and replaying the API key to wherever it
                // points is not a thing to do quietly.
                .followRedirects(HttpClient.Redirect.NEVER).build();
        this.mapper = JsonMapper.builder().addModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
                .serializationInclusion(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL).build();
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
        final HttpResponse<String> raw = send(request(NEXT_STEP).GET());
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

    public String platformName() {
        return platformName;
    }

    private <T> ApiResponse<T> post(final String path, final Object payload, final TypeReference<ApiResponse<T>> type)
            throws IOException, InterruptedException {
        final HttpResponse<String> raw = send(request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))));
        requireSuccess(raw, path);
        return raw.body() == null || raw.body().isBlank() ? null : mapper.readValue(raw.body(), type);
    }

    private HttpRequest.Builder request(final String path) {
        return HttpRequest.newBuilder().uri(URI.create(baseUrl + TOOLS_PATH + path))
                .header(API_KEY_HEADER, apiKey == null ? "" : apiKey).timeout(REQUEST_TIMEOUT);
    }

    private HttpResponse<String> send(final HttpRequest.Builder builder) throws IOException, InterruptedException {
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** Turns a non-2xx into an exception carrying the server's own {@code message} when it has one. */
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
            throw new IOException(what + " rejected (HTTP " + status + "): " + detail +
                    " — check the API key is correct, active, and belongs to this workspace.");
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
}
