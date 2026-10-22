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
package ai.oak.service.code;

/**
 * Where a configured code repository is in its download lifecycle.
 *
 * <ul>
 *   <li>{@link #NONE} — configured, not yet downloaded (or its local checkout went missing).</li>
 *   <li>{@link #CLONING} — a download is in progress.</li>
 *   <li>{@link #READY} — a local checkout exists; the repo is ready for the Code Agent to work on.</li>
 *   <li>{@link #FAILED} — the last download failed; see the entry's {@code detail} for why.</li>
 * </ul>
 */
public enum RepoStatus {
    NONE, CLONING, READY, FAILED
}
