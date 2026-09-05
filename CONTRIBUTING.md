# Contributing

Keep changes focused and describe the behavior being changed, the supporting tests, and any remaining validation gaps.

## Development

Use JDK 21 and the checked-in Maven wrapper:

```sh
./mvnw --batch-mode clean verify
```

On Windows, use `mvnw.cmd` with the same arguments. Tests use mock connectors and must not require enterprise credentials, external integrations, or model usage.

Preserve the read-only connector boundaries, explicit environment restrictions, evidence provenance, and application-owned execution controls. New integrations need a narrowly scoped adapter and tests for unavailable, denied, incomplete, and malformed responses.

## Repository safety check

Before publication, validate candidate files and reachable Git history:

```sh
./mvnw --batch-mode -Djmops.publication.scanHistory=true -Dtest=RepositoryPublicationSafetyTest test
```

For organization-specific checks, supply `JMOPS_PUBLICATION_DENY_TERMS` through a private process environment as a `|`-separated list. Never commit those values or place them in shell history, CI configuration, screenshots, or support output. On PowerShell, remove the transient value after the check with `Remove-Item Env:JMOPS_PUBLICATION_DENY_TERMS -ErrorAction SilentlyContinue`.

The test rejects unapproved URL hosts, private-network references, high-confidence credential formats, configured private markers, and matching content in Git history and reference metadata. It scans all local refs by default; use `-Djmops.publication.historyRef=HEAD` to check the intended branch. The ref is validated and passed to Git as one argument.

Treat a failed check as a release blocker. Investigate the finding, revoke any exposed credentials, and resolve the exposure before publishing. Do not disable or narrow the checks to obtain a passing result.

Fixtures must remain fictional. Keep live organization mappings in the ignored external registry and credentials in environment variables or an approved credential provider, never in either registry.
