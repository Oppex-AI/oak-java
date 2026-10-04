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
package ai.oak.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * OAK's single operating region — connection context, not a per-step input. One OAK instance serves
 * exactly one AWS region; it reports that region once at registration and uses it for every AWS/SSM call
 * and every tool that takes a {@code region}.
 *
 * <p>Resolved by precedence: the <b>Settings override</b> (the AWS group's {@code AWS_REGION}) → the
 * <b>host environment</b> ({@code AWS_REGION} / {@code AWS_DEFAULT_REGION} the process was launched with)
 * → the <b>host metadata</b> (EC2 IMDSv2 {@code placement/region}). Setting and env are re-read on every
 * call so a change in Settings takes effect immediately; only the IMDS lookup is memoised (it is a remote
 * call and the host's region does not change).
 */
@ApplicationScoped
public class RegionProvider {

    private static final Logger LOG = LoggerFactory.getLogger(RegionProvider.class);
    private static final String IMDS = "http://169.254.169.254";
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    @Inject
    ToolSettings settings;

    private volatile boolean imdsTried;
    private volatile String imdsRegion;

    /** OAK's region, or {@code null} if it cannot be determined. */
    public String region() {
        final String override = trimToNull(settings.envFor("AWS").get("AWS_REGION"));
        if (override != null) {
            return override;
        }
        final String env = firstNonBlank(System.getenv("AWS_REGION"), System.getenv("AWS_DEFAULT_REGION"));
        if (env != null) {
            return env;
        }
        return imdsRegion();
    }

    /** Where {@link #region()} resolved from: {@code setting} / {@code host-env} / {@code host-metadata} / {@code ""}. */
    public String source() {
        if (trimToNull(settings.envFor("AWS").get("AWS_REGION")) != null) {
            return "setting";
        }
        if (firstNonBlank(System.getenv("AWS_REGION"), System.getenv("AWS_DEFAULT_REGION")) != null) {
            return "host-env";
        }
        return imdsRegion() != null ? "host-metadata" : "";
    }

    /** Best-effort EC2 IMDSv2 lookup of this host's region; memoised (including a failed attempt). */
    private synchronized String imdsRegion() {
        if (imdsTried) {
            return imdsRegion;
        }
        imdsTried = true;
        try {
            final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
            final HttpResponse<String> token = http.send(HttpRequest.newBuilder(URI.create(IMDS + "/latest/api/token"))
                    .timeout(TIMEOUT).header("X-aws-ec2-metadata-token-ttl-seconds", "60")
                    .PUT(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            if (token.statusCode() != 200) {
                return null;
            }
            final HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(IMDS + "/latest/meta-data/placement/region")).timeout(TIMEOUT)
                            .header("X-aws-ec2-metadata-token", token.body()).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200) {
                imdsRegion = trimToNull(resp.body());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException e) {
            LOG.debug("IMDS region lookup failed (not on EC2?): {}", e.toString());
        }
        return imdsRegion;
    }

    private static String firstNonBlank(final String a, final String b) {
        final String ta = trimToNull(a);
        return ta != null ? ta : trimToNull(b);
    }

    private static String trimToNull(final String s) {
        if (s == null) {
            return null;
        }
        final String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
