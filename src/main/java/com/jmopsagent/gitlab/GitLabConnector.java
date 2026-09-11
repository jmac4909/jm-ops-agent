package com.jmopsagent.gitlab;

import com.jmopsagent.connector.CommitChange;
import com.jmopsagent.connector.RepositoryRef;
import com.jmopsagent.connector.ConnectorEvidence;
import com.jmopsagent.connector.Environment;
import com.jmopsagent.connector.EvidenceQuery;
import java.util.List;
import java.util.Optional;

public interface GitLabConnector {
    Optional<RepositoryRef> resolveRepository(String service);
    List<CommitChange> getCommits(String service, String revision, int limit);
    List<CommitChange> compareRevisions(String service, String fromRevision, String toRevision, int maxDiffCharacters);
    Optional<String> getFileContent(String service, String revision, String path, int maxCharacters);
    List<String> getRepositoryTree(String service, String revision, String path, int limit);
    /** Bounded branch history, advisory only; keywords are literal title terms (OR), never a query language. */
    default List<CommitChange> searchCommits(String service, List<String> keywords, EvidenceQuery query) {
        return List.of();
    }
    default List<ConnectorEvidence> getRepositoryConfiguration(String service, Environment environment, int maxCharacters) {
        return List.of();
    }
    /** Resolve a config-file commit at or before asOf; never substitute current branch content. */
    default List<ConnectorEvidence> getRepositoryConfigurationAt(String service, Environment environment,
                                                                java.time.Instant asOf, int maxCharacters) {
        return List.of();
    }
}
