# Windows and Claude Code handoff

This document is for setting up JM Ops Agent on the intended Windows developer workstation. The zero-connectivity mock demonstration does not require Claude Code, kubectl, CF, Jenkins, GitLab, Splunk, or corporate network access.

## Quick start for the developer

1. Clone the repository (or extract a trusted archive) into a new directory, for example `C:\dev\jm-ops-agent`.
2. Open PowerShell in that directory.
3. Verify Java and the Maven wrapper:

```powershell
java -version
$env:JAVA_HOME
.\mvnw.cmd --version
```

Both Java commands must report Java 21. `JAVA_HOME` must point to the JDK root, not its `bin` directory. A global Maven installation is not required.

4. Build and test:

```powershell
.\mvnw.cmd clean test
.\mvnw.cmd package
```

5. Run the mock profile:

```powershell
$env:SPRING_PROFILES_ACTIVE = "local-mock"
.\mvnw.cmd spring-boot:run
```

6. Open `http://127.0.0.1:8080`, select **Trace Tracking ID**, choose **TEST**, and enter `DEMO-TRACE-001`.

The expected path is:

```text
Edge Gateway succeeds
  -> Identity Service succeeds
  -> Catalog Service fails with HTTP 500
```

The expected conclusion is a high-confidence TEST configuration problem involving `/catalog/db-ur` instead of `/catalog/db-url`.

## Ready-to-paste prompt for the Claude agent

Paste the following prompt into Claude Code from the project root:

```text
You are setting up and validating the JM Ops Agent MVP on this Windows workstation. Work from the project root.

Goals:
1. Get the zero-connectivity local-mock profile building and running.
2. Verify the TEST / DEMO-TRACE-001 vertical slice.
3. Assess local-live readiness.
4. Configure only approved DEV/TEST integrations for which I already have access.
5. Give me a concise final readiness report.

Read these files completely before making changes:
- README.md
- WINDOWS_CLAUDE_HANDOFF.md
- docs/live-connectors.md
- docs/architecture.md
- docs/mvp-status.md
- .env.example

Non-negotiable boundaries:
- Java 21 is required.
- Use mvnw.cmd; do not require global Maven.
- Prove local-mock before considering local-live.
- Only DEV and TEST are permitted. Never query STAGE or PROD.
- Keep the application strictly read-only.
- Do not add arbitrary command execution or mutating Kubernetes, CF, Jenkins, GitLab, Splunk, Git, database, or dependency operations.
- Do not run kubectl apply/edit/delete/patch/exec, restart workloads, rerun Jenkins jobs, push Git changes, or modify TAS applications.
- Do not use MCP.
- Do not implement direct Anthropic or Vertex API calls. The application must invoke the separately installed Claude Code CLI using the workstation's existing approved corporate GCP/Vertex authentication.
- Never use --dangerously-skip-permissions.
- Do not remove or weaken Claude's --bare, --safe-mode, empty tool set, MCP denial, schema validation, timeouts, or iteration limits merely to accommodate an incompatible CLI.
- Do not weaken sanitization, CSRF, environment restrictions, HTTPS endpoint validation, evidence limits, or loopback binding.
- Keep JM_OPS_BIND_ADDRESS at 127.0.0.1.
- Never print, echo, paste, inspect, commit, or persist credential values. Do not create a populated .env file.
- Before any public push, run the repository publication test with history scanning enabled and provide organization-specific deny markers through a private transient environment setting. Do not put the marker list in source or command history. Stop if the test reports any violation; a later cleanup commit does not erase an earlier commit.
- Use process environment variables, an approved IDE secret store, or the company's approved secret-injection mechanism. Spring Boot does not automatically import .env.
- Do not bypass TLS or corporate certificate validation.
- Do not change source merely to work around workstation configuration. If you find a genuine Windows portability defect, make the smallest safe patch and rerun the complete suite.

Phase 1 - inspect the workstation

Run only safe availability/version checks. Do not display environment variables other than JAVA_HOME and do not display credentials:

java -version
$env:JAVA_HOME
.\mvnw.cmd --version
git --version
claude --version
claude --help
kubectl version --client
cf version

Missing optional CLIs must not block local-mock. Both java and mvnw.cmd must report Java 21.

If an approved JDK 21 is installed but inactive, set JAVA_HOME to its root and prepend its bin directory to PATH for the current PowerShell session. If JDK 21 is not installed, tell me to install an approved distribution through the corporate software catalog; do not download an unapproved JDK.

If Maven dependency resolution fails, diagnose the approved Maven mirror, proxy, or corporate CA configuration. Never disable TLS verification.

Phase 2 - prove local-mock

Run:

.\mvnw.cmd clean test
.\mvnw.cmd package

Do not hide skipped or failed tests. Record the executed test count from Maven's summary in the readiness report.

Start the application:

$env:SPRING_PROFILES_ACTIVE = "local-mock"
.\mvnw.cmd spring-boot:run

Verify:
- http://127.0.0.1:8080/actuator/health reports UP.
- http://127.0.0.1:8080/diagnostics loads without exposing secrets.
- http://127.0.0.1:8080 loads the UI.

Exercise the UI:
1. Select Trace Tracking ID.
2. Select TEST.
3. Enter DEMO-TRACE-001.
4. Start the trace and wait for COMPLETED.
5. Confirm Edge Gateway succeeds, Identity Service succeeds, and Catalog Service fails with HTTP 500.
6. Confirm the diagnosis is CONFIG with HIGH confidence and cites the incorrect TEST database parameter reference.
7. Confirm evidence, timeline, recommendations, and recent-investigations sidebar render.
8. Submit a follow-up and correctness feedback to confirm both persist.
9. Confirm PROD and STAGE submissions are rejected. Do not change the allowlist.

If browser automation is unavailable, start the server and give me the exact manual steps rather than skipping acceptance.

Phase 3 - assess local-live readiness

Stop the mock server before reusing its port. Assess Claude Code, kubectl, CF CLI, Git, Jenkins, GitLab, and Splunk individually. Missing enterprise configuration should remain unconfigured rather than being invented.

Claude Code must already be authenticated through the approved corporate GCP/Vertex setup. Do not create an API integration or copy authentication material into this repository.

Confirm that the installed claude --help exposes every required safety capability:
- --print
- --output-format
- --tools
- --disallowedTools
- --bare
- --safe-mode
- --system-prompt

Optional capabilities include --json-schema, --resume, --max-turns, --permission-mode, --permission-prompts, --model, --restricted, --no-chrome, and --disable-slash-commands. If a required flag is absent, report that live Claude reasoning will fail closed and recommend an approved CLI update. Do not weaken the application to force invocation. `AUTO` structured-output mode first uses `--json-schema` when supported and retries once with the same schema in the system prompt if the Windows CLI wrapper rejects the schema argument; local parsing and semantic validation remain mandatory. Do not set `PROMPT_ONLY` merely to accept an otherwise malformed response.

Use .env.example only as a list of variable names. Ask me to set missing secret values privately; never ask me to paste tokens into this conversation. Verify only whether each setting is present, never its value. Credentials must be least-privilege and read/search-only. Credential-bearing endpoints must use HTTPS.

Configuration groups:
- Claude: CLAUDE_ENABLED, CLAUDE_EXECUTABLE, CLAUDE_TIMEOUT.
- Kubernetes: KUBECTL_EXECUTABLE, KUBERNETES_DEV_CONTEXT or KUBERNETES_TEST_CONTEXT, and corresponding namespace fallbacks where needed.
- TAS: CF_EXECUTABLE plus either the single-target DEV/TEST variables or an ignored external Spring YAML map of logical targets, each with an isolated CF home already pointed at its expected DEV/TEST API, org, and space.
- Jenkins: legacy single-controller variables or an ignored external Spring YAML file containing the logical multi-controller map.
- GitLab: GITLAB_BASE_URL, GITLAB_TOKEN.
- Splunk: SPLUNK_BASE_URL, preferred search token (or explicitly selected session/CSRF compatibility settings), approved DEV/TEST indexes, and any private field profiles.

Non-secret live mappings belong in an ignored external registry selected by `JMOPS_SERVICE_REGISTRY_LOCATION`, never in the checked-in fictional fixture. Ask only for canonical service names, aliases, DEV/TEST runtime platform, contexts/namespaces, deployment/service names, logical Jenkins controller/job, GitLab repository, TAS app pattern, Splunk index/application aliases, and health paths. Preserve provenance and never put credentials in either registry.

Only after local-mock passes, start live mode:

$env:SPRING_PROFILES_ACTIVE = "local-live"
.\mvnw.cmd spring-boot:run

Open http://127.0.0.1:8080/diagnostics. Report each component as available/unavailable or configured/unconfigured without revealing values. Configured does not prove reachability or correct mappings.

Do not execute a live investigation until I provide an approved DEV or TEST service or tracking ID and confirm that its contexts, namespaces, jobs, repositories, indexes, and credentials are read-only. Do not make a discovery request against PROD or an unknown environment.

Final report:
- Provide a table for JDK 21, Maven wrapper, Git, Claude CLI/safe flags, kubectl, CF CLI, Jenkins, GitLab, Splunk, local-mock tests, fictional-trace acceptance, and local-live diagnostics.
- List files changed and why.
- List failed commands using sanitized summaries.
- State the exact next action I need to take.
- Confirm no secret was printed or persisted.
- Confirm no write-capable operational command was run.
- Do not declare setup complete unless the full tests and fictional tracking flow pass.
```

## Common setup failures

### `release version 21 not supported`

The Maven wrapper is running with an older JDK. Set `JAVA_HOME` to an approved JDK 21 root, update `PATH`, open a new PowerShell window, and rerun `.\mvnw.cmd --version`.

### Maven cannot download dependencies

The first wrapper/build run needs an approved Maven repository or internet route. Configure the organization's Maven mirror, proxy, and CA trust using approved workstation guidance. Do not disable certificate verification.

### Diagnostics says Claude Code is unavailable

Run `claude --version` from the same PowerShell session. If it works only through an alias, set `CLAUDE_EXECUTABLE` to the actual executable path. Then inspect `claude --help`. JM Ops Agent intentionally refuses live Claude invocation when required safety flags cannot be verified.

### Port 8080 is already in use

Choose another loopback port for that PowerShell session:

```powershell
$env:JM_OPS_PORT = "8081"
.\mvnw.cmd spring-boot:run
```

### An enterprise connector is unconfigured

Leave it unconfigured until the approved endpoint, mapping, and least-privilege credential are known. The mock profile remains fully functional, and live mode reports partial readiness without inventing configuration.

### CF target mismatch or busy state

For one target per environment, use different absolute `CF_DEV_HOME` and `CF_TEST_HOME` directories. For multiple org/space targets within DEV or TEST, configure the logical `jmops.tas.targets` map and registry `tas.target.<ENV>` selector described in `docs/live-connectors.md`. Prepare every directory outside JM Ops Agent with the approved CF login flow, target its expected API/org/space, and then leave that state unchanged while the app runs. The app executes argument-free `cf target` before every read and refuses unknown, ambiguous, cross-environment, or mismatched targets. It never logs in or changes a target. Conflicting targets cannot share a home; same-home calls use a bounded lock, while independent homes can safely run concurrently.

### Splunk returns unauthorized, redirect, or no normalized trace

Prefer a scoped read-only authentication token with `SPLUNK_AUTH_MODE=BEARER_TOKEN`. Use `SESSION_KEY` only for a login/session key. `SESSION_CSRF` is a temporary compatibility mode and requires the complete cookie header plus matching form key; enter values through a secret-aware process environment, never a file in the repository. The connector appends the read-only web-proxy suffix automatically. A successful search with no reconstructed path usually means the private source fields or exact per-environment application aliases need to be mapped in the ignored external Spring/registry YAML. Keep logical gateway/application indexes and field-profile selections there as well. Wildcards and arbitrary extraction expressions are intentionally unsupported, and EKS-localized runtime logs come from the bounded `kubectl` adapter. Do not add private mappings to the public fixture.

### Diagnostics reports TLS certificate-chain validation failure

Confirm which JDK 21 actually launches Maven and the application. Obtain the missing public root/intermediate certificate and its fingerprint through an approved channel, then use that JDK's `keytool` and the approved workstation procedure to import it into the applicable truststore. Restart the terminal/IDE and refresh diagnostics. Never enable a trust-all client or disable hostname verification.

Run the import from an elevated PowerShell window only when workstation policy permits it; omit a command-line truststore password so `keytool` prompts instead of recording it in shell history:

```powershell
& "$env:JAVA_HOME\bin\keytool.exe" -importcert -trustcacerts -alias "<approved-ca-alias>" -file "C:\path\to\verified-intermediate.cer" -cacerts
```

Verify the displayed certificate owner, issuer, and fingerprint before accepting it. This changes the selected JDK truststore, not JM Ops Agent. If the certificate rotates, repeat the approved verification/import process with a new unique alias; do not download certificates from an unverified endpoint.

### Multiple Jenkins controllers

Use logical IDs in an ignored `application-local.yml` as shown in `docs/live-connectors.md`, and reference only those IDs from the ignored external service registry. Do not mix the legacy `JENKINS_BASE_URL` variables with the controller map. Each controller requires its own read-only credential; the app prevents those credentials from crossing origins.

## Live configuration reference

See [`.env.example`](.env.example) for variable names and [Live connector setup](docs/live-connectors.md) for detailed read-only behavior, isolated CF setup, multi-controller Jenkins configuration, Splunk field profiles, and TLS diagnostics.
