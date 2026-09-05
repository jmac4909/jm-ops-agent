# Architecture and trust boundaries

## Runtime flow

```text
Developer
   |
   v
Spring MVC controller -> Investigation application service -> H2/JPA workflow state
                                      |
                                      v
                           Investigation orchestrator
                         /       |       |        \
                        v        v       v         v
                    Splunk   Runtime   Delivery   History
                              K8s/CF   Jenkins/   matcher
                                       GitLab
                         \       |       |        /
                                      v
                         sanitizer + bounded store
                                      |
                                      v
                         structured ClaudeCodeClient
                                      |
                            validated decision only
                                      |
                  approved next evidence request or conclusion
```

The Spring application is the policy enforcement point. Claude never receives connector credentials, connector clients, arbitrary process execution, a repository checkout, or write capabilities. It receives evidence that has already been bounded, projected where possible, redacted, and persisted as an auditable `EvidenceItem`.

## Investigation workflow

An `Investigation` records its type, service/environment scope, optional tracking ID, user problem, state transitions, Claude execution metadata, diagnosis, confidence, category, recommendations, feedback, actual root cause, and successful remediation. Statuses cover discovery, evidence collection, analysis, requests for more evidence, explicit code investigation, completion, and safe failure.

For a tracking-ID trace, the orchestrator:

1. searches a bounded four-hour Splunk window and expands once to the configured maximum only after a successful no-data result;
2. orders normalized trace events and finds the first meaningful failure;
3. restricts later service requests to names observed in that trace/registry scope;
4. localizes the failing service;
5. collects runtime, deployment, error, effective-configuration, and recent-change evidence;
6. adds advisory historical matches;
7. asks the reasoning adapter for a structured decision; and
8. maps any supported follow-up evidence request to a semantic Java connector method.

Service triage starts with service identity and runtime health, then follows the same “find the concrete error, work backward” strategy. It does not scan an entire repository.

## Reasoning contract

Claude may return only one of these states:

- `NEEDS_MORE_EVIDENCE`
- `COMPLETE`
- `CODE_INVESTIGATION_RECOMMENDED`

The response also carries a bounded summary, hypotheses with confidence and known evidence IDs, supported `nextEvidenceRequests`, a root-cause category, and recommended actions. JSON parsing and semantic validation happen before the orchestrator uses the decision. A request cannot invent a connector operation. A request for another service is accepted only when that service is in the resolved investigation scope.

Code inspection is a separate, user-initiated state. The orchestrator resolves the deployed SHA from Jenkins, ranks repository paths against observed stack traces/error text, and reads at most the configured number of files through the GitLab API. No local source tree is changed.

Follow-up questions use the completed investigation's stored evidence. Live evidence is not automatically recollected. The existing Claude session is resumed only when supported and safe; otherwise the configured reasoning adapter answers from stored context or reports a bounded failure.

## Evidence and sensitive data

Connectors return structured `ConnectorEvidence`, not uncontrolled console/log dumps. Source-specific projections reduce exposure before the shared sanitizer runs. Examples include Kubernetes configuration values redacted at source, bounded Jenkins error lines, and Splunk canonical-field projection, explicit `_raw` removal, signature grouping, representative samples, and frequency counts.

The shared sanitizer covers authorization headers, bearer/JWT tokens, cookies, password/API/access-token/secret fields, URI credentials, private keys, AWS access-key patterns, and configurable additional patterns. It records whether redaction and truncation occurred. Full request/response bodies are disabled by default.

Documentation and historical incidents have lower authority than live evidence. Confirmed feedback ranks above unconfirmed diagnoses, but remains advisory context rather than guaranteed truth.

## Read-only process boundary

`JavaProcessRunner` uses `ProcessBuilder` with an executable and argument list—never a shell command string. Standard input, standard output, and standard error are handled separately with UTF-8-safe bounds; processes have timeouts; and executable-not-found errors are converted into safe results. Operational CLIs retain the environment they need for their own approved authentication, while the Claude child receives only an explicit workstation/GCP Vertex allowlist; connector tokens and unrelated application secrets are removed before process start. TAS reads receive one registry-selected, isolated `CF_HOME`; the expected DEV/TEST API, org, and space are verified before every read. Same-home calls are serialized, independently targeted homes may run concurrently, and conflicting target definitions sharing a home are rejected before execution.

CLI connector methods are semantic. Defense-in-depth command policies apply after argument construction:

- kubectl: `get`, `logs`, and `rollout status` only;
- CF CLI: argument-free `target` verification plus `app`, `apps`, `logs`, opt-in `env`, and `routes` only;
- Claude: bare print/JSON mode, empty tool set, MCP disallowed, safe mode, optional `dontAsk`, bounded turns, and no dangerous permission bypass.

The system contains no controller or Claude request type for deployment, restart, delete, edit, rerun, database write, or credential mutation.

## Budgets

Defaults are three Claude iterations, two minutes wall-clock time, 50 evidence items, 200 log events, five persisted outbound Splunk searches, a 72-hour maximum tracking window, eight code files, five historical incidents, ten follow-up questions, and one targeted follow-up evidence refresh per investigation. Every outbound Splunk request, including an internal fallback, must atomically acquire its own investigation-wide permit. Each connector also applies request/output bounds. Ordinary follow-ups use stored sanitized evidence. Only an explicit recent-request/call/traffic question can invoke the single allowlisted refresh: TAS uses metadata-only Splunk evidence, while Kubernetes uses bounded pod logs and labels that weaker coverage. At a limit, the system records the limitation and returns the best-supported conclusion available instead of looping indefinitely.

## Persistence and future PostgreSQL migration

JPA repositories own investigations, evidence, timeline events, and follow-up exchanges. H2 is a local file database selected only for the POC. Business services depend on repositories/domain objects rather than H2-specific APIs, allowing a later PostgreSQL datasource and migrations to replace `ddl-auto=update` without changing the connector or orchestration boundaries.

## Profiles

| Profile | Evidence adapters | Reasoning adapter | Connectivity |
| --- | --- | --- | --- |
| `local-mock` (default) | Deterministic fixtures | Deterministic mock Claude | None |
| `local-live` | Read-only CLI/REST adapters, no-op docs, evidence-only dependency fallback | Claude Code CLI with fail-closed capability checks | Only configured enterprise systems |

Never activate both profiles together: each profile defines competing implementations of the same connector interfaces.
