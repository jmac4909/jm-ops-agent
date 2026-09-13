# JM Ops Agent

JM Ops Agent is a local, read-only operational triage proof of concept for standardized Spring Boot services on Kubernetes/EKS and Tanzu Application Service. It gathers bounded evidence through narrow adapters, sanitizes it, and then uses Claude Code CLI as a reasoning engine. The application—not Claude—owns integration credentials, evidence collection, workflow state, limits, persistence, and auditability.

The default `local-mock` profile is a complete zero-connectivity vertical slice. It reconstructs the fictional tracking ID `DEMO-TRACE-001` in `TEST`, follows a failure from Edge Gateway through Identity Service to Catalog Service, correlates runtime, deployment, log, and configuration evidence, and persists the diagnosis for feedback and follow-up questions.

## Design and validation

| Start here | What to look for |
| --- | --- |
| [Run the mock demo](#run-the-zero-connectivity-demo) | A reproducible investigation without enterprise credentials or model usage |
| [Architecture and trust boundaries](docs/architecture.md) | The application owns adapters, evidence, workflow state and authority; the model proposes reasoning |
| [Adaptive investigations](docs/adaptive-investigations.md) | Bounded follow-up evidence requests rather than arbitrary model-generated commands |
| [Tests](src/test/java/com/jmopsagent/) and [repository safety](CONTRIBUTING.md#repository-safety-check) | Connector denial paths, redaction and source/history checks |

This is a proof of concept: diagnoses can recommend a fix, but the application cannot apply one. The mock flow uses fictional fixtures and requires no external systems or credentials.

For a new Windows workstation or an AI-assisted local setup, use [Windows and Claude Code handoff](WINDOWS_CLAUDE_HANDOFF.md). It includes a ready-to-paste prompt that keeps the read-only and credential boundaries intact.

## Safety boundary

This MVP is intentionally read-only:

- Only `DEV` and `TEST` are accepted. `STAGE`, `PROD`, and all other environment values are rejected before connector execution.
- The unauthenticated local POC binds to loopback, requires CSRF tokens for state-changing browser requests, and emits restrictive browser security headers.
- The UI and Claude response contract expose semantic evidence requests, never arbitrary commands.
- Kubernetes permits only bounded `get`, `logs`, and `rollout status` operations. A second allowlist blocks mutating verbs, `exec`, `cp`, port forwarding, and similar operations.
- CF CLI permits argument-free `target` verification plus `app`, `apps`, `logs`, `events`, opt-in `env`, and `routes`; isolated configured homes prevent shared-target races.
- Jenkins, GitLab, and Splunk adapters issue retrieval requests only.
- Raw connector evidence passes through redaction before it is persisted or sent to Claude. Request and response bodies are disabled by default.
- Claude is launched in bare/safe mode with no tools, MCP explicitly disallowed, non-interactive output, bounded turns and time, and no `--dangerously-skip-permissions` flag. Bare mode prevents workstation/project hooks, skills, plugins, memory, and configuration from entering the reasoning process. Its child process receives a narrow workstation/Vertex environment allowlist, never Jenkins, GitLab, or Splunk credentials.
- Code investigation reads a bounded set of files at the Jenkins-reported deployed SHA, or a labeled historical candidate for a past incident, and can only recommend a textual fix. It cannot edit, commit, or push.

Do not broaden these allowlists merely to work around missing access. Add a narrowly scoped semantic connector method and tests instead.

## Requirements

- Windows 10/11 developer workstation
- JDK 21 (a JDK, not only a JRE)
- Internet or an approved Maven mirror for the wrapper's first dependency download
- For `local-live` only: separately installed/authenticated Claude Code CLI and any enterprise CLIs or API credentials you enable

Maven does not need to be installed globally. The checked-in Apache Maven Wrapper 3.3.4 downloads Maven 3.9.11 into the current user's Maven cache on first use.

### Windows Java 21 setup

Install an approved JDK 21 distribution through the corporate software catalog, then open a new PowerShell window. Verify that both `JAVA_HOME` and `PATH` point to that JDK:

```powershell
java -version
$env:JAVA_HOME
.\mvnw.cmd --version
```

Both version commands must report Java 21. If `java` still reports an older version, update `JAVA_HOME`, prepend `$env:JAVA_HOME\bin` to the user or system `PATH`, and restart the terminal/IDE. Do not point `JAVA_HOME` at the `bin` directory itself.

## Run the zero-connectivity demo

From the repository root in PowerShell:

```powershell
.\mvnw.cmd test
$env:SPRING_PROFILES_ACTIVE = "local-mock"
.\mvnw.cmd spring-boot:run
```

The default profile is already `local-mock`; setting it explicitly makes the safety mode visible. Open [http://localhost:8080](http://localhost:8080).

The embedded server binds to `127.0.0.1` by default because this POC has no multi-user authentication. Do not change `JM_OPS_BIND_ADDRESS` to a shared interface without adding the required security controls.

No Claude, kubectl, CF, Jenkins, GitLab, Splunk, or corporate network access is needed. A deterministic reasoning adapter is used, so tests and demo output do not consume AI usage.

### Exercise the first vertical slice

1. Enter **DEMO-TRACE-001** in the issue description.
2. Select environment **TEST** and choose **Investigate**.
3. Watch the externally meaningful progress timeline; the detail page automatically reloads while the investigation runs.
4. Review the localized failure path, evidence, timeline, diagnosis, and recommended actions.

Expected mock story:

```text
Edge Gateway ✓
  → Identity Service ✓
  → Catalog Service ✕ (HTTP 500)
```

The fictional fixtures place a successful Jenkins deployment at `2026-01-15T10:37:00Z` and the failure at `10:40:00Z`. Catalog Service is degraded because its TEST database parameter reference changed from `/catalog/db-url` to `/catalog/db-ur`. The deterministic adapter concludes that this is a high-confidence configuration issue and recommends correction through the normal reviewed deployment process. JM Ops Agent performs no correction itself.

Other mock service inputs exercise individual branches:

| Service | Fixture |
| --- | --- |
| `healthy-service` | Healthy workload |
| `readiness-failure-service` | Kubernetes readiness failure |
| `bad-config-service` | Incorrect configuration |
| `deployment-failure-service` | Failed Jenkins deployment |
| `downstream-500-service` | Downstream API HTTP 500 |
| `database-error-service` | PostgreSQL connectivity evidence |

Use the same form for service triage, for example `catalog-service in TEST returning 500s after the latest deployment`.

## UI and local endpoints

| Endpoint | Purpose |
| --- | --- |
| `/` | Describe an issue; service, tracking and timing clues share one flow |
| `/investigations/{id}` | Live status, diagnosis, failure path, sanitized evidence, timeline, feedback, follow-ups, and automatically collected source evidence |
| `/api/investigations/{id}` | Small JSON status response available to status clients |
| `/diagnostics` | Human-readable executable and connector configuration checks |
| `/api/diagnostics` | The same diagnostics as JSON; no credential values are returned |
| `/actuator/health` | Spring Boot health endpoint |

The application stores investigations asynchronously. Refreshing or revisiting the detail URL does not start a second investigation.

## Persistence

H2 is file-backed so investigations survive application restarts:

- `local-mock`: `./data/jmopsagent-mock.mv.db`
- `local-live` (and the base configuration): `./data/jmopsagent.mv.db`

Paths are relative to the process working directory. `data/`, H2 database files, `.env`, and `application-local.yml` are ignored by Git. Only sanitized evidence is stored. The H2 console is not exposed. The JPA model is deliberately portable so PostgreSQL can replace H2 later.

To clear local demo history, stop the application and move the mock database files out of `data/`; keep a backup if the investigation history matters.

## Run with live read-only connectors

`local-live` is an opt-in profile. It does not make an unavailable integration a startup blocker; diagnostics reports the capability, and connectors return no/bounded unavailable evidence. Start with the template in [`.env.example`](.env.example), but do not put secrets in a committed file. Spring Boot does not automatically import `.env`.

In PowerShell, set values in the current process or use an IDE launch configuration/approved secret injection mechanism:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local-live"
$env:KUBERNETES_TEST_CONTEXT = "<approved-context>"
$env:KUBERNETES_TEST_NAMESPACE = "<approved-namespace>"
$env:GITLAB_BASE_URL = "<approved-base-url>"
# Supply GITLAB_TOKEN through the approved IDE or secret-injection mechanism.
.\mvnw.cmd spring-boot:run
```

Use the same pattern for other variables in `.env.example`; never place a real token in source, command history, screenshots, or support output. Open `/diagnostics` before a live investigation. It runs bounded version checks for Claude, kubectl, CF, and Git, reports connector configuration without values, and performs credential-free HTTPS reachability checks for configured REST endpoints. A TLS failure is reported explicitly; certificate validation is never bypassed.

Full connector requirements, current behavior, and limitations are in [Live connector setup](docs/live-connectors.md).

## Claude Code boundary

`local-mock` uses `DeterministicClaudeCodeClient`. `local-live` inspects the installed `claude --version` and `claude --help` output before invocation. It fails closed unless the corporate CLI exposes the required safety flags.

Conceptually, the live call is:

```text
claude --bare -p --output-format json --tools "" --disallowedTools "mcp__*" --safe-mode ...
```

The prompt is supplied on standard input and contains only sanitized, bounded evidence plus the supported evidence-request enum. The application validates the returned JSON, evidence IDs, confidence values, list sizes, and request types before acting. Unsupported evidence requests are rejected. When safe invocation is unavailable or the response is invalid, the investigation completes with deterministic best-effort heuristics and records the limitation.

Where the CLI returns them, JM Ops Agent stores the session ID, start/end timestamps, duration, turn count, total cost, usage metadata, and a sanitized error. Follow-up questions reuse stored evidence and resume the session only when the installed CLI supports `--resume` and the stored session ID passes validation.

See [Architecture and trust boundaries](docs/architecture.md) for the orchestration contract.

## Build and test

PowerShell:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd package
java -jar .\target\jm-ops-agent-0.1.0-SNAPSHOT.jar --spring.profiles.active=local-mock
```

macOS/Linux with JDK 21:

```sh
./mvnw clean test
./mvnw spring-boot:run -Dspring-boot.run.profiles=local-mock
```

Tests use mock connectors and must not require enterprise access or Claude authentication.

See the [contributor guide](CONTRIBUTING.md) for development conventions and repository-safety checks.

## Configuration and service registry

`src/main/resources/service-registry.yml` contains fictional mock-safe service mappings only. Set `JMOPS_SERVICE_REGISTRY_LOCATION` to an approved external YAML resource for live canonical names, aliases, platform locations, job/repository hints, Splunk mappings, and health paths. Never add live organization mappings to the checked-in fixture. Manual values carry `MANUAL` provenance and take precedence over discovered values; bounded live discovery can enrich missing GitLab, Kubernetes, and Jenkins values with explicit provenance. Discovered enrichment is held in memory for the current application run; it is intentionally not written back to the source YAML in this MVP.

The live Kubernetes, TAS, Jenkins, GitLab, and Splunk adapters consume their applicable registry mappings. Environment variables provide explicit CLI contexts/endpoints/credentials and controlled fallbacks; enterprise naming and field conventions still require validation. Jenkins services select logical, separately authenticated controllers, while TAS services can select logical DEV/TEST targets backed by immutable isolated CF homes; both mappings fail closed when unknown or ambiguous.

Service triage follows registered `dependencies` and registered destinations found in Feign logs, collecting each dependency's runtime, deployment, repository history, and configured config-server evidence. Plain-text Splunk messages survive canonical projection and pass through the normal sanitizer. Recent business-call searches use the configured tracking window (72 hours by default). Optional registered GET fixtures compare selected response fields for known-good and known-bad inputs. See [Dependency triage setup](docs/dependency-triage.md) for mappings, bounds, and evidence limitations.

Describe the issue in the single launch form, including any service, tracking ID, or timing clues you know. No tracking tab or historical switch is required. If another team already fixed it, the agent can reconstruct retained evidence without an earlier agent run: it infers dates from the description or searches earlier TAS failures (72 hours, then 30 days by default). Follow-ups can correct timing and request deeper evidence in the same conversation. Questions such as `latest tracking ID for this service`, `this service not working`, and `why was this broken on 9/5` share that context. Slash dates default to month/day; the resolved UTC day is shown. See [Retrospective investigations](docs/retrospective-investigations.md) for source coverage and limits.

The checked-in registry contains only `.example.invalid` URLs and deliberately fictional identifiers. Keep all organization-specific identifiers and mappings in the ignored external registry. Put secrets only in environment variables or an approved credential provider, never in either registry.

## Current MVP status

The working slice includes a unified issue-description workflow, asynchronous progress, bounded/sanitized evidence, mock and live connector implementations, structured Claude decisions with deterministic fallback, automatic source inspection at a validated revision, correctness feedback, deterministic historical matching, file-backed persistence, diagnostics, and the internal UI. Follow-ups reuse useful stored evidence and can collect more through the same approved dispatcher when reasoning needs it. Date and tracking clues apply before collection; source-file and Splunk budgets persist across the investigation. See [Adaptive investigations](docs/adaptive-investigations.md).

Enterprise rollout work remains: supply and validate private Splunk field/index mappings; validate Jenkins folder/job mapping and pipeline plugins; add authenticated runbook/documentation sources; implement focused live dependency adapters; add production-grade authentication/authorization and audit export; migrate to PostgreSQL; and complete deployment/operational hardening. Production remains prohibited even if local credentials can see it.

More detail is tracked in [MVP status and limitations](docs/mvp-status.md).
