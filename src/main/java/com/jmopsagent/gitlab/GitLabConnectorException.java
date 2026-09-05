package com.jmopsagent.gitlab;

/** Signals that a live GitLab read failed, distinct from a successful empty result. */
public final class GitLabConnectorException extends RuntimeException {
    private final GitLabFailureKind failureKind;

    GitLabConnectorException(GitLabFailureKind failureKind, Throwable cause) {
        super("GitLab read failed (" + failureKind + ")", cause);
        this.failureKind = failureKind;
    }

    public GitLabFailureKind failureKind() {
        return failureKind;
    }
}
