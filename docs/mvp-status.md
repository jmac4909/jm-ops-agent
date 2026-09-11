# MVP status and limitations

## Implemented vertical slice

- Spring Boot 4 / Java 21 / Maven application with server-rendered Thymeleaf UI.
- File-backed H2 persistence through Spring Data JPA for investigations, sanitized evidence, progress events, Claude metadata, follow-up exchanges, and feedback.
- Unified issue-description entry with service/tracking hints and strict `DEV`/`TEST` validation.
- Inferred or discovered incident windows for investigations started after recovery, using retained TAS/Splunk evidence, historical Jenkins candidates, GitLab history/config snapshots, and scope that can be corrected in follow-ups.
- Asynchronous investigation execution with externally meaningful progress polling.
- `DEMO-TRACE-001`/`TEST` fictional fixture covering Edge Gateway, successful Identity Service, Catalog Service HTTP 500, degraded readiness, recent successful deployment, Splunk error grouping, and a database-parameter path typo.
- Independent interfaces and mock/live profiles for Kubernetes, TAS, Jenkins, GitLab, Splunk, documentation, dependencies, and Claude reasoning.
- Evidence sanitization before persistence/reasoning, plus source-specific reduction and configurable budgets.
- Structured Claude response schema, supported-request mapping, evidence-ID validation, session resume boundary, and deterministic fallback.
- Automatic code inspection using a validated current or historical revision and a bounded relevant-file set.
- Deterministic historical incident matching that prefers confirmed feedback without a vector database.
- Follow-up questions with additional approved evidence collection and persisted correctness/remediation feedback.
- Startup diagnostics that disclose availability/configuration state and credential-free HTTPS/TLS reachability, but never endpoints or credentials.
- Apache Maven Wrapper scripts for PowerShell/cmd and POSIX systems.

## Live adapter readiness

| Adapter | Implementation state | Enterprise validation still needed |
| --- | --- | --- |
| Claude Code | Safe, non-interactive CLI client with capability inspection, schema-transport fallback, optional model selection, and strict local parsing | Confirm required flags/output envelope in the approved CLI distribution |
| Kubernetes | Semantic ProcessBuilder reads with command allowlist and safe config projection | Naming/selectors, contexts, namespaces, RBAC, and representative workloads |
| TAS/CF | CF v8-compatible logs/events with registry org/space or target-selected isolated homes, target verification, conflict checks, locking, and opt-in source-redacted environment metadata | App mapping, approved preconfigured targets, RBAC, and auth/session expiry |
| Jenkins | Multi-controller read-only REST retrieval with credential isolation, nested paths, safe URI encoding, and typed failures | Private folder/multibranch naming, plugins, SHA source, and least-privilege accounts |
| GitLab | Read-only project/commit/compare/tree/file REST retrieval with typed, privacy-safe failures | Deterministic project mapping, pagination, and local TLS/proxy behavior |
| Splunk | Gateway-first trace lookup, exact-app prefilters, token-first bounded searches, optional session/CSRF compatibility, registry-selected field profiles, plain-text message/regex fallback, metadata-only recent calls over the tracking window, access-log fallback, and typed outcomes | Private index/source-type/field mappings and a durable read-only token |
| Dependencies | Evidence-only fallback | Focused read-only adapters when a concrete need is approved |
| Runbooks | No-op live connector | Approved documentation source and staleness metadata |

## Known POC constraints

- This is a local proof of concept without application login, role-based authorization, centralized audit export, TLS termination, or multi-user isolation. It binds to loopback, enables CSRF protection and browser security headers by default; do not override that binding or expose it on a shared network.
- H2 and Hibernate `ddl-auto=update` are for local use. PostgreSQL plus versioned schema migrations is required before a shared deployment.
- Secrets are injected through the environment/external configuration; integration with a corporate secret manager is not included.
- Some CLI adapters represent remote failures as bounded unavailable evidence rather than a shared connector-result type; investigations record that limitation and still finish.
- Registry discovery is deliberately exact and bounded. It enriches candidates already named by an investigation; it does not crawl every project, job, namespace, or account at startup.
- Historical matching is deterministic token/text matching. It is intentionally advisory and has no embeddings/vector database.
- Retrospective collection does not recover expired source evidence. Historical Kubernetes application logs and historical data-store snapshots are unavailable; Jenkins seeks the incident date and samples 100 nearby retained builds. See [Retrospective investigations](retrospective-investigations.md).
- A unified description form and follow-ups share service, trace, timing and deeper evidence capabilities. Follow-ups can request more approved evidence within persisted search/file limits; corrected timing is applied before collection. Automatic archive discovery currently targets TAS Splunk logs; historical Kubernetes logs remain unavailable.
- The UI uses polling rather than SSE/WebSocket.
- The code path is tested against mocks; private endpoints, proxies, certificates, RBAC, and response variations still require workstation validation.

## Next increments

1. Validate the fictional tracking slice with sanitized representative field shapes in an isolated TEST setup.
2. Validate external registry deployment/app/job/repository/index mappings and review the provenance produced by bounded discovery.
3. Add contract tests against recorded, fully synthetic response shapes for any new connector variants.
4. Add focused dependency adapters only for evidence-supported branches.
5. Integrate an approved documentation source and surface stale/live conflicts explicitly.
6. Add authentication, authorization, audit retention/export, PostgreSQL migrations, proxy/TLS configuration, and operational packaging before any shared deployment.

Production support is explicitly out of scope. The environment enum/policy must remain limited to `DEV` and `TEST` until a separately reviewed product/security decision changes that requirement.

Registered dependency traversal, advisory cross-service GitLab history, configuration-repository lookup, and opt-in GET fixture comparisons are implemented. Direct DynamoDB and other data-store reads remain future work. See [Dependency triage setup](dependency-triage.md).
