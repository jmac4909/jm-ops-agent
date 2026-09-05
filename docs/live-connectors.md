# Live connector setup

`local-live` is an integration-development profile, not authorization to query every reachable system. Configure only approved non-production endpoints. The application rejects all environments except `DEV` and `TEST`, but credentials should still be least-privilege and read-only at the source system.

Credential-bearing Jenkins, GitLab, and Splunk base URLs must use HTTPS and cannot contain embedded credentials, a query string, or a fragment.

Start with `.env.example`. It is a variable-name template only; Spring Boot does not load it automatically. Use process environment variables, an IDE secret-aware launch configuration, or an approved external Spring configuration file excluded from source control.

## Startup diagnostics

Open `/diagnostics` after starting the application. It checks:

- Claude Code with `claude --version`;
- kubectl with a client-only version command;
- CF CLI and Git with version commands; and
- whether the required Jenkins, GitLab, and Splunk endpoint/credential settings are present; and
- whether each configured REST endpoint completes a credential-free, non-redirecting HTTPS `HEAD` probe.

Checks are capped at five seconds and bounded output. Diagnostics never returns endpoint names, controller IDs, or credential values. It distinguishes certificate-chain validation failures from timeouts/unreachable endpoints, but it does not send credentials or prove authorization, field mapping, or correct repository/job/index selection. Run a scoped DEV/TEST investigation to validate those separately.

## Claude Code CLI

Required settings:

| Variable | Purpose |
| --- | --- |
| `CLAUDE_EXECUTABLE` | Executable name or absolute path; defaults to `claude` |
| `CLAUDE_ENABLED` | Enables capability inspection/invocation; defaults to `true` |
| `CLAUDE_TIMEOUT` | Per-invocation timeout; defaults to `2m` |
| `JMOPS_CLAUDE_MAX_TURNS_PER_INVOCATION` | CLI turn bound; defaults to `1` |
| `CLAUDE_MODEL` | Optional CLI model identifier; blank uses the separately configured default |
| `JMOPS_CLAUDE_STRUCTURED_OUTPUT_MODE` | `AUTO` (default), `SCHEMA_REQUIRED`, or `PROMPT_ONLY` |

Authenticate Claude Code separately using the approved corporate GCP/Vertex setup. JM Ops Agent contains no Anthropic or Vertex API integration and does not store those credentials. The Claude child receives only the environment values needed to find the executable/configuration and use approved Vertex authentication; Jenkins, GitLab, Splunk, and unrelated environment values are removed.

At runtime the client inspects `--help` rather than assuming flags. Invocation is blocked if the CLI lacks `--print`, `--output-format`, `--tools`, `--disallowedTools`, `--bare`, `--safe-mode`, or `--system-prompt`. Bare mode prevents loading workstation/project hooks, skills, plugins, MCP configuration, memory, and `CLAUDE.md`; safe mode and the empty tool set add defense in depth. Optional supported flags add JSON schema validation, slash-command disabling, `dontAsk` permission mode, max turns, Chrome disabling, and session resume. There is no dangerous permission bypass. The application sends the JSON prompt over standard input and explicitly disallows MCP tools.

The JSON envelope is parsed for `session_id`, `result`/`structured_output`, `num_turns`, `total_cost_usd`, and `usage` where provided. Timestamps and duration are recorded by the process boundary. Errors are sanitized and bounded before persistence. In `AUTO` mode the client uses `--json-schema` when advertised. If the CLI wrapper specifically rejects transport of that argument, it retries once without the flag while retaining the exact schema and JSON-only instruction in the system prompt. The local parser still enforces field, enum, size, confidence, evidence-ID, and supported-request bounds. `SCHEMA_REQUIRED` disables that fallback.

If the installed corporate CLI uses different safety flags, the current adapter fails closed. Update and review the adapter/capability policy; do not remove safety requirements to force a call through.

## Kubernetes/EKS

Required per enabled environment:

| Variable | Purpose |
| --- | --- |
| `KUBECTL_EXECUTABLE` | Executable name or absolute path; defaults to `kubectl` |
| `KUBERNETES_DEV_CONTEXT` / `KUBERNETES_TEST_CONTEXT` | Exact kubeconfig context (required) |
| `KUBERNETES_DEV_NAMESPACE` / `KUBERNETES_TEST_NAMESPACE` | Optional namespace fallback when the service registry has no environment-specific namespace |

kubectl must already work under the developer's identity. The connector always includes an explicit context and namespace and validates Kubernetes names. Namespace, deployment, and Kubernetes service names come from the service registry when present; the environment namespace variables are controlled fallbacks. Implemented semantic reads cover deployment/pod/rollout health, recent workload-filtered events, bounded logs, deployment metadata, non-secret effective configuration, services, and ingress.

If the kubeconfig uses an AWS profile, set `AWS_PROFILE` in the same process that starts JM Ops Agent and complete the approved SSO login first. SSO expiry is external state: refresh it with the approved workstation tooling, then retry. Do not copy cached SSO credentials into project files.

Configuration projection intentionally returns environment variable names and source types, not literal values or secret contents. Resource settings and safe health-probe fields may be retained; probe headers and exec commands are omitted at source. A command policy allows only `get`, `logs`, and `rollout status` and rejects mutating or interactive verbs, including `exec`.

Current limitations: pod and ingress selection assumes the configured Kubernetes service name is also the `app.kubernetes.io/name` label value, and event ownership is inferred from pod names beginning with the configured deployment name. Validate those conventions against representative workloads.

## Tanzu Application Service

The legacy variables below configure one target per environment:

| Variable | Purpose |
| --- | --- |
| `CF_EXECUTABLE` | Executable name or absolute path; defaults to `cf` |
| `CF_DEV_API`, `CF_DEV_ORG`, `CF_DEV_SPACE` | DEV target |
| `CF_TEST_API`, `CF_TEST_ORG`, `CF_TEST_SPACE` | TEST target |
| `CF_DEV_HOME`, `CF_TEST_HOME` | Different absolute directories holding preconfigured CLI state for each environment |
| `JMOPS_TAS_APP_PATTERN` | Optional app pattern; defaults to `{service}-{environment}` |
| `JMOPS_TAS_ENVIRONMENT_METADATA_ENABLED` | Opt-in for redacted `cf env`; defaults to `false` |

CF CLI v8 does not accept API/org/space selection as per-command global flags. Configure and authenticate each CF home outside the application. The application supplies only the selected `CF_HOME`, runs read-only `cf target`, and verifies the actual API/org/space against the configured expectation before every application read. A mismatch fails closed.

When an environment contains more than one approved org/space, define logical targets in an ignored external Spring configuration file. Values stay in environment variables; only the logical target ID belongs in the service registry:

```yaml
jmops:
  tas:
    targets:
      group-one-dev:
        environment: DEV
        api: ${CF_GROUP_ONE_DEV_API}
        org: ${CF_GROUP_ONE_DEV_ORG}
        space: ${CF_GROUP_ONE_DEV_SPACE}
        home: ${CF_GROUP_ONE_DEV_HOME}
      group-one-test:
        environment: TEST
        api: ${CF_GROUP_ONE_TEST_API}
        org: ${CF_GROUP_ONE_TEST_ORG}
        space: ${CF_GROUP_ONE_TEST_SPACE}
        home: ${CF_GROUP_ONE_TEST_HOME}
      group-two-test:
        environment: TEST
        api: ${CF_GROUP_TWO_TEST_API}
        org: ${CF_GROUP_TWO_TEST_ORG}
        space: ${CF_GROUP_TWO_TEST_SPACE}
        home: ${CF_GROUP_TWO_TEST_HOME}
```

```yaml
services:
  - service: sample-api
    tas:
      target:
        DEV: group-one-dev
        TEST: group-one-test
      appPattern: sample-api-{environment}
  - service: another-api
    tas:
      target:
        TEST: group-two-test
```

Use `tas.target.DEV`/`TEST` (falling back to `tas.target`) to select a logical target. If exactly one target exists for the requested environment, the mapping may be omitted. Multiple candidates without a mapping, unknown targets, cross-environment mappings, incomplete targets, and mixed legacy/map settings all fail before CF runs. Two logical targets cannot share a normalized CF home when their allowed environment, expected API, org, or space differs. Calls that legitimately share an identical target state are serialized; independent homes may run concurrently and never mutate each other's CLI state.

The process allowlist permits only application status/instances, recent logs, routes, and the argument-free target verification. `cf env` is disabled by default because it is sensitive and commonly restricted by RBAC. When explicitly enabled, values and unrecognized lines are removed at source before the shared sanitizer. A forbidden response is reported as unavailable evidence; do not broaden a role solely to make optional metadata collection work.

The service registry's `tas.appPattern` takes precedence over the global pattern. Set `runtime.platform.<ENV>` to `TAS` when a service has both TAS and EKS metadata; this keeps runtime selection explicit and makes approved follow-on requests use the same adapter. Recent CF logs are output-bounded but still depend on the installed CLI's `--recent` behavior.

## Jenkins

| Variable | Purpose |
| --- | --- |
| `JENKINS_BASE_URL` | Legacy single-controller base URL |
| `JENKINS_USERNAME` | Legacy single-controller read-only account/user identifier |
| `JENKINS_TOKEN` | Legacy single-controller read-only API token |
| `JMOPS_INTEGRATIONS_JENKINS_JOB_PATTERN` | Optional pattern; defaults to `{service}-{environment}-deploy` |

The adapter retrieves recent build result/timestamp/URL, candidate commit SHA, change messages, bounded representative console errors for failed builds, and failed workflow stages where the Jenkins workflow API is present. Calls time out after 20 seconds. Authentication, authorization, TLS, timeout, not-found, invalid-response, and controller-selection failures are typed and surfaced as unavailable evidence rather than being confused with a successful empty build list.

For multiple controllers, put the following shape in an ignored external Spring configuration file and select it with `SPRING_CONFIG_ADDITIONAL_LOCATION`. Logical IDs are non-secret; URLs and credentials remain external:

```yaml
jmops:
  integrations:
    jenkins:
      controllers:
        primary:
          base-url: ${JENKINS_PRIMARY_BASE_URL}
          username: ${JENKINS_PRIMARY_USERNAME}
          token: ${JENKINS_PRIMARY_TOKEN}
        secondary:
          base-url: ${JENKINS_SECONDARY_BASE_URL}
          username: ${JENKINS_SECONDARY_USERNAME}
          token: ${JENKINS_SECONDARY_TOKEN}
```

Map a service with `jenkins.controller.DEV`/`TEST` (falling back to `jenkins.controller`) and `jenkins.job.DEV`/`TEST` (falling back to `jenkins.job`). When exactly one controller exists the controller mapping may be omitted. With multiple controllers an absent, ambiguous, or unknown mapping fails closed. Legacy variables cannot be mixed with the controller map. Each controller has a separately authenticated client, credentials are attached only to relative same-origin requests, redirects are rejected, and nested job folders use safe path segments and encoded tree parameters. The API token must not have build, replay, or job-configuration rights.

## GitLab

| Variable | Purpose |
| --- | --- |
| `GITLAB_BASE_URL` | GitLab host/base URL |
| `GITLAB_TOKEN` | Read-only token with repository/API read scope only |

The adapter searches projects by canonical service name, reads commits, compares two revisions, lists a bounded tree, and reads bounded file content at an explicit revision. Code escalation uses this API rather than cloning or modifying a local repository.

The adapter first derives an encoded project path from the service registry's `gitlab.repository` URL. Without a usable registry URL it searches for an exact project name/path match; ambiguous results are not selected. Remote failures are logged with only a fixed operation label and safe category, then surfaced separately from successful empty results. Registry URLs must map cleanly to a GitLab project path; pagination beyond configured first-page bounds plus richer rename/binary-diff handling remain follow-on work.

## Splunk

| Variable | Purpose |
| --- | --- |
| `SPLUNK_BASE_URL` | Splunk HTTPS origin or approved reverse-proxy prefix |
| `SPLUNK_AUTH_MODE` | `BEARER_TOKEN` (preferred/default), `SESSION_KEY`, or `SESSION_CSRF` |
| `SPLUNK_TOKEN` | Search-only authentication token or session key, interpreted by the selected mode |
| `SPLUNK_SESSION_COOKIE` | Complete cookie header used only in `SESSION_CSRF` mode |
| `SPLUNK_FORM_KEY` | Matching form/CSRF key used only in `SESSION_CSRF` mode |
| `SPLUNK_DEV_INDEXES` | Optional comma-separated DEV index fallback/allowlist |
| `SPLUNK_TEST_INDEXES` | Optional comma-separated TEST index fallback/allowlist |
| `SPLUNK_DEV_GATEWAY_INDEXES` | Optional comma-separated DEV gateway-trace indexes searched first |
| `SPLUNK_TEST_GATEWAY_INDEXES` | Optional comma-separated TEST gateway-trace indexes searched first |
| `SPLUNK_REQUEST_TIMEOUT` | Bounded request timeout; defaults to `45s` |
| `JMOPS_LIMITS_TRACKING_SEARCH_WINDOW` | Maximum tracking lookup horizon; defaults to `72h` and is capped at 30 days |

The connector merges these optional environment lists with validated logical `splunk.indexes.gateway`/`application` values (and the compatibility keys `apigee`/`tas`) from the external registry. A query needs at least one resulting index. Tracking lookup searches the gateway indexes first with top-level fields and no `spath`; only a successful no-data result permits the exact-application fallback. Kubernetes-localized operational evidence uses `kubectl` rather than service-log Splunk searches. Index names allow only letters, digits, `_`, `.`, and `-`; user input cannot inject an index or arbitrary SPL fragment. Searches post to the export endpoint with explicit earliest/latest times, a head limit, and a field projection. Tracking lookup starts with four hours and expands once to the configured maximum only after a successful no-data result.

Bearer-token authentication is the stable path. `BEARER_TOKEN` sends `Authorization: Bearer`; `SESSION_KEY` sends the older `Authorization: Splunk` scheme. Browser-session compatibility mode requires the complete cookie set plus the matching form key; it sends the cookie string as one raw HTTP header with `X-Splunk-Form-Key` and `X-Requested-With`. In this mode the connector appends `/en-US/splunkd/__raw` to the configured origin or proxy prefix automatically. The modes are mutually exclusive, redirects are disabled, and credentials are attached only after an exact-origin check. Put session values in the process environment or an approved secret store—never in YAML, source, command history, or support output. Prefer obtaining a scoped read-only authentication token instead of depending on session keys or expiring browser cookies.

The parser bounds results and content, groups duplicate signatures after normalizing changing UUIDs/numbers, keeps representative samples and frequencies, and reconstructs tracking traces from canonical fields. Application searches place exact `cf_app_name` and `sourcetype="cf:logmessage"` predicates before extraction; error searches also put fixed error/status markers there. Wildcard application names are rejected. Queries request one sentinel row beyond the retained limit so truncation is explicit, per-sample content clipping is marked, and the projection drops `_raw` before export. `RECENT_ACTIVITY` returns bounded successful-log counts. `RECENT_BUSINESS_CALLS` caps raw rows before extraction and returns tracking ID (when available), status, operation/method, URI, and latency only—never request/response bodies. Its result is marked scan-capped rather than claimed complete. After a successful application-log no-data result, a fixed HTTP-access fallback can return status/method/URI/latency; its router correlation ID remains distinct and is never labeled as the application tracking ID. Each outbound request, including gateway/application and application/access-log fallbacks, consumes its own persisted investigation permit.

Source fields are configured with `jmops.integrations.splunk.field-profiles` in the ignored external Spring YAML. A profile maps canonical keys (`TIME`, `TRACKING_ID`, `SERVICE`, `DOWNSTREAM_SERVICE`, `HTTP_STATUS`, `OUTCOME`, `SEVERITY`, `MESSAGE`, `OPERATION`, `HTTP_METHOD`, `REQUEST_URI`, `TARGET_URL`, `EXECUTION_TIME`) to validated field paths. `FIELD_ALIASES` handles top-level or nested structured fields; the opt-in `PREFIXED_TEXT` strategy adds one fixed tracking-ID `rex` for a legacy plain-text format. Arbitrary regular expressions are never configuration. A service selects one or more known profile IDs through `splunk.fieldProfiles.DEV`/`TEST`; unselected profiles do not add extraction stages. Runtime application identities belong under `splunk.appNames.DEV`/`TEST`, and gateway identities under `splunk.gatewayNames.DEV`/`TEST`. Only exact, manually confirmed values are suitable for query routing; missing mappings fail closed.

Synthetic field-profile example (replace paths only in the ignored local file):

```yaml
jmops:
  integrations:
    splunk:
      field-profiles:
        - name: json-application-log
          sourcetype: cf:logmessage
          fields:
            TRACKING_ID: [event.traceId]
            SERVICE: [event.application]
            DOWNSTREAM_SERVICE: [event.downstream]
            HTTP_STATUS: [event.statusCode]
            MESSAGE: [event.detail]
```

Search outcomes distinguish success/no data from unconfigured, rejected credentials, forbidden access, redirect rejection, timeout, TLS failure, remote failure, and parse failure. Logs contain only the operation class and safe outcome. Private source types, fields, indexes, and aliases must remain in ignored external configuration. The base URL is used as the evidence link rather than generating a saved-search URL.

## TLS certificate trust

All REST connectors require normal JDK certificate validation; there is no trust-all mode. If `/diagnostics` reports a certificate-chain failure while a browser or native client succeeds, the JDK used to launch Maven/application may lack an intermediate certificate. Obtain the public certificate through an approved channel, verify its fingerprint, and import it into the active approved JDK truststore using that JDK's `keytool`. On Windows, the safe command shape is `& "$env:JAVA_HOME\bin\keytool.exe" -importcert -trustcacerts -alias "<approved-ca-alias>" -file "C:\path\to\verified-intermediate.cer" -cacerts`; allow `keytool` to prompt for the truststore password rather than putting it in shell history. Follow workstation policy for elevation and truststore changes. Restart the terminal/IDE and rerun diagnostics. Never disable hostname or certificate verification. Certificate rotation may require a newly verified import with a new unique alias.

## Documentation and dependencies

`local-live` deliberately provides no wiki/runbook client yet. Documentation evidence is empty rather than blocking investigation, and future runbooks will remain advisory.

Dependency investigation is evidence-driven. The live fallback does not query PostgreSQL, Kafka, SQS, IBM MQ, or another API directly; it explains that existing log/config evidence should be used until a narrowly scoped read-only adapter is configured. It never tests a dependency by writing data.

## Service registry

The checked-in YAML registry contains fictional mock-safe seed metadata only. Point `JMOPS_SERVICE_REGISTRY_LOCATION` to an ignored external `file:` resource for live mappings. Kubernetes namespace/deployment/service, TAS app pattern, logical Jenkins controller/job, GitLab repository, and Splunk index/application aliases are consumed by the corresponding live adapters. Manual/confirmed values cannot be overwritten by discovery updates. Read-only GitLab, Kubernetes, and Jenkins hooks can enrich missing values with visible provenance; they use exact/bounded matches and do not persist an unknown seed unless a connector confirms it. Discovered enrichment is an in-memory overlay for the current process and is rebuilt after restart; the MVP never rewrites the operator's registry YAML. Never put endpoints containing credentials, tokens, cookies, or secret values in a registry.

Synthetic external-registry shape:

```yaml
services:
  - service: sample-service
    aliases: [sample-service-test]
    runtime:
      platform:
        TEST: TAS
    tas:
      appPattern: sample-service-{environment}
    jenkins:
      controller:
        TEST: primary
      job:
        TEST: demo/sample-service/deploy
    splunk:
      appNames:
        TEST: [sample-service-blue-test]
      indexes:
        tas: demo_application_events
```
