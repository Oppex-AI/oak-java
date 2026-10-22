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

import java.time.Instant;

/**
 * One code repository OAK has been told to make available to the local <b>Code Agent</b>: the git
 * {@code url}, an optional {@code branch}, and the state of its local download.
 *
 * <p>OAK <b>stores no git credentials</b>. The download shells out to the host's {@code git}, which uses
 * whatever the host is already configured with (an SSH key, a credential helper) — the same "use the host's
 * CLI and its credentials" model as the AWS and Docker tools. A private repo the host's git can't reach
 * simply fails the download, surfaced as {@link RepoStatus#FAILED}.
 *
 * @param url       the git clone URL (HTTPS or SSH).
 * @param branch    the branch to check out, or blank for the repository default.
 * @param status    where the local download stands.
 * @param commit    the checked-out HEAD commit once {@link RepoStatus#READY}, else blank.
 * @param localPath absolute path of the local checkout on this host.
 * @param detail    a human-readable note — the failure reason when {@link RepoStatus#FAILED}.
 * @param updatedAt ISO-8601 instant of the last status change.
 */
public record CodeRepo(String url, String branch, RepoStatus status, String commit, String localPath, String detail,
        String updatedAt) {

    /** The branch to check out, or {@code null} to let git pick the repository's default. */
    public String branchOrNull() {
        return branch == null || branch.isBlank() ? null : branch.trim();
    }

    public boolean isReady() {
        return status == RepoStatus.READY;
    }

    private CodeRepo at(final RepoStatus newStatus, final String newCommit, final String newDetail) {
        return new CodeRepo(url, branch, newStatus, newCommit, localPath, newDetail, Instant.now().toString());
    }

    public CodeRepo cloning() {
        return at(RepoStatus.CLONING, commit, "Download in progress…");
    }

    public CodeRepo ready(final String headCommit) {
        return at(RepoStatus.READY, headCommit, "Downloaded and ready.");
    }

    public CodeRepo failed(final String reason) {
        return at(RepoStatus.FAILED, "", reason);
    }

    public CodeRepo reset(final String reason) {
        return at(RepoStatus.NONE, "", reason);
    }
}
