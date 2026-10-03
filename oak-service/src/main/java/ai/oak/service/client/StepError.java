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
 * The error half of a failed step result: a short machine-readable {@code code} (e.g. {@code EXIT_NONZERO},
 * {@code ACCESS_DENIED}, {@code TIMEOUT}) the platform can branch on, plus a human {@code message}. Present
 * only when the step failed.
 */
public record StepError(String code, String message) {
}
