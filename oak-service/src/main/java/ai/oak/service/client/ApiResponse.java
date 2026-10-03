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
 * The envelope every {@code /v1/tools} response arrives in. Restated here from the platform's own
 * {@code APIResponse} so this SDK need not depend on its internals — the shape is the contract, not
 * the class. {@code data} is null on an error, and on a poll that found nothing to do.
 */
public record ApiResponse<T>(boolean success, int code, String message, T data) {
}
