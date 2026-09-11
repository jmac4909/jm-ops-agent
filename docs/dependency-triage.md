# Dependency triage setup

Service investigations collect CF application status, recent logs and events for TAS workloads, plus bounded Splunk evidence. They then follow registered downstream relationships and collect each service's own runtime, deployment, repository changes and repository configuration before reasoning. The original investigated service remains the investigation's identity.

All examples below are fictional. Put live mappings in the external registry selected by `JMOPS_SERVICE_REGISTRY_LOCATION`.

```yaml
services:
  - service: demo-entry-api
    runtime:
      platform: TAS
    dependencies: [demo-search-api]
    tas:
      appPattern: demo-entry-api-{environment}
      org:
        TEST: demo-interactive
      space:
        TEST: demo-test
  - service: demo-search-api
    runtime:
      platform: TAS
    dependencies: [demo-data-api]
    feignClients: [SearchClient]
    dependencyHosts: [search.test.example.invalid]
    tas:
      target:
        TEST: data-test
      appPattern: demo-search-api-{environment}
  - service: demo-data-api
    runtime:
      platform: TAS
    dependencies: []
    feignClients: [DataClient]
    tas:
      target:
        TEST: data-test
      appPattern: demo-data-api-{environment}
    gitlab:
      repository: https://gitlab.example.invalid/demo/data
      defaultBranch: master
      # Optional literal title keywords, matched with OR within the bounded sample.
      commitKeywords: [projection, column, mapping]
    configServer:
      repository: https://gitlab.example.invalid/demo/config
      ref: master
      # Default: <environment>/<canonical-service>-<environment>.yml
      path: '{environment}/{service}-{environment}.yml'
```

Feign method names such as `DataClient#getDetails` and HTTP destinations are resolved against service names, aliases, `feignClients`, `dependencyHosts`, or environment-specific TAS app names. Unregistered destinations never become probe targets. Registry relationships are labeled as relationships; they do not assert that a particular request traversed the edge. Aliases and cycles are deduplicated. Every additional service uses its own runtime and repository mapping.

Configure a separate, already authenticated CF home for each TAS target in external application configuration:

```yaml
jmops:
  tas:
    targets:
      interactive-test:
        environment: TEST
        api: https://api.test.example.invalid
        org: demo-interactive
        space: demo-test
        home: C:/approved-cf-homes/interactive-test
      data-test:
        environment: TEST
        api: https://api.test.example.invalid
        org: demo-data
        space: demo-test
        home: C:/approved-cf-homes/data-test
```

`tas.target.TEST` selects a target explicitly. Otherwise `tas.org.TEST` and `tas.space.TEST` filter configured targets; exactly one must match. When both selectors are supplied, they must agree. The connector verifies the actual API/org/space using argument-free `cf target` before every operation. It does not log in or change targets. CF events retain the CLI's event times, actors and operation types in bounded text; the evidence timestamp is collection time. CLI recent-log/event retention is not a guarantee of coverage for the requested time window.

Splunk normalization preserves nonblank structured message fields first, then plain `msg`, a JSON envelope's `msg`, or `_raw`, before dropping `_raw` from the projection. Fixed regex fallbacks extract tracking IDs, status codes, severity, Feign operations and target URLs. Every profile's business predicate includes `X-TrackingId` and `statusCode`, together with configured aliases. Plain and prefixed JSON log bodies pass through redaction before storage or reasoning; this does not enable response-body collection.

```yaml
jmops:
  limits:
    max-dependency-services: 3
    tracking-search-window: 72h
    commit-search-window: 30d
```

Dependencies share the active user turn's evidence, Splunk-search and wall-clock budgets. Follow-up turns receive a fresh bounded allowance, shared across all reasoning iterations and targeted traffic reads; cumulative search and source-file counters remain persisted. Each dependency's Splunk sample is limited to five rows. Exhaustion produces a bounded partial investigation. Recent business-call requests and TAS follow-up refreshes use `tracking-search-window`; ordinary error searches retain their shorter window. Commit history has a separate, longer window because a merged change can precede a restage by days. The GitLab history scan reads at most 100 branch commits and returns at most ten matching titles per collection. It is explicitly a sample; absence is not proof that no change exists.

GitLab config lookup uses the configured GitLab origin, the separate `configServer.repository`, and the configured ref/path. YAML properties are flattened so table names, destinations and feature flags remain visible; credential-named properties, including multiline values, are removed at source and the output receives normal redaction. Repository configuration is not proof of runtime values. A branch commit and a later CF restage are correlation evidence: neither proves the deployed SHA or a causal mapping gap. Code escalation retains its existing Jenkins revision boundary; verify that a platform restage has not made that revision stale.

## Registered API comparisons

Enable only service operations that the operator has confirmed are read-only GET health/detail endpoints. Configure fixtures and any authentication reference externally; the model cannot supply URLs, methods, headers or fixture inputs.

```yaml
# Inside a registered service entry:
apiProbes:
  enabled:
    TEST: true
  baseUrl:
    TEST: https://data.test.example.invalid
  healthPath:
    TEST: /actuator/health
  knownGoodPath:
    TEST: /detail?recordId=demo-good
  knownBadPath:
    TEST: /detail?recordId=demo-bad
  responseFields: [data.address.state, data.routes, data.items.0.address]
  # Optional: name of a process environment variable, never the token itself.
  bearerTokenEnv:
    TEST: DEMO_DATA_TEST_PROBE_TOKEN
```

Only explicit DEV/TEST fixture settings are used. URLs must stay on the configured HTTPS origin, redirects are rejected, and every fixture is validated before calls begin. Each fixture gets one request, a five-second timeout and a 64 KiB response limit. There are at most three fixtures per service and one probe collection per service in an investigation. Results retain status, selected field presence/type and container sizes, enabling good/bad comparisons. Non-JSON responses retain their HTTP status and explicitly mark response fields unavailable. Field values, full response bodies and fixture URLs are not retained. Dotted paths support numeric array indices; arbitrary expressions and user-supplied inputs are unsupported. HTTP-only services need an approved HTTPS endpoint to use this adapter.

DynamoDB access remains future work. Config evidence can identify a table, but this implementation cannot query it or prove that a missing response value exists in the database. A complete data-to-response comparison still requires a future bounded read-only data-store adapter.
