package com.jmopsagent.gitlab;

import com.jmopsagent.connector.CommitChange;
import com.jmopsagent.connector.RepositoryRef;
import java.util.List;
import java.util.Optional;

public interface GitLabConnector {
    Optional<RepositoryRef> resolveRepository(String service);
    List<CommitChange> getCommits(String service, String revision, int limit);
    List<CommitChange> compareRevisions(String service, String fromRevision, String toRevision, int maxDiffCharacters);
    Optional<String> getFileContent(String service, String revision, String path, int maxCharacters);
    List<String> getRepositoryTree(String service, String revision, String path, int limit);
}
