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
package ai.oak.service.db;

/**
 * A database operation could not be performed, carrying the contract error code the tool reports:
 * {@code NO_CONNECTION_CONFIGURED}, {@code DB_UNREACHABLE} or {@code PERMISSION_DENIED}.
 */
public class DbAccessException extends Exception {

    private final String code;

    public DbAccessException(final String code, final String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
