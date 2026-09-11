# One investigation flow

Describe the problem in the launch form. Service and tracking hints are optional when the description already identifies them; DEV/TEST can be selected or inferred. Legacy service and tracking POST endpoints use the same resolver. Examples:

- `entry-api returning errors in TEST`
- `entry-api failed in TEST on Sep 9, 2025; another team fixed it`
- `entry-api in TEST, X-TrackingId: DEMO-TRACE-123`
- `get me last tracking ID used for QA on this service` (with selected or saved service)
- `this service not working`
- `why was this broken yesterday?`
- `why was this broken on 9/5?`

Dates and labeled tracking IDs influence the same collection flow. Relative dates use the time the message is submitted in UTC. Slash dates default to month/day (`9/5` means September 5); set `jmops.input.date-order=DAY_MONTH` for day/month. The full UTC window is shown, and invalid dates request clarification before searching. Date-only clues cover that UTC day; timestamp-only clues use ten minutes on each side. Merge/deployment/fix dates are excluded when they are clearly labeled as changes. Ambiguous identity requires a concrete service or tracking hint; inference is deliberately limited and does not understand every phrasing.

If current TAS Splunk evidence does not establish a failure, discovery samples earlier failures through 72 hours and then 30 days by default. CF `logs --recent` has no reliable query-time filter and cannot establish recency by itself. Discovery chooses a timestamped candidate, narrows the evidence window, and collects historical dependency/deployment/source evidence. It explains that the sampled failure might not be the reported incident. Two search slots are reserved for dependencies under the default five-search budget. A dated description can target older retained evidence directly.

Application tracking IDs found in failure evidence can trigger tracing; router request IDs are not application tracking IDs. The model can request earlier failures, a trace, the latest tracking ID, dependency evidence or relevant source files. Source requests and code recommendations automatically read a bounded set of files at a validated revision and continue reasoning. No separate code button is required.

The same capabilities are available in follow-ups. Date or tracking corrections are resolved before traffic sampling or the first model call. A changed evidence window retains previous evidence with a visible label, excludes it from active reasoning, and clears the previous model session. The original diagnosis remains an audit record; the follow-up answers for the corrected period. Source-file and Splunk counters persist across turns, including unsuccessful read attempts. At a limit or missing source, the answer states the evidence gap.

Search, source-file, time and environment controls still bound connector access. This removes workflow prerequisites; it does not create expired logs or historical Kubernetes/DynamoDB coverage. See [retrospective source coverage](retrospective-investigations.md) and [dependency setup](dependency-triage.md). Local tests use fictional fixtures and mocked reasoning, not enterprise credentials or a real recovered incident.

A simple latest-ID question uses a dedicated application-call lookup, ordered by event time before limiting results. It returns the most recent observed qualifying ID, event timestamp, service/environment and searched interval without running outage triage. Successes and failures qualify; router IDs and request bodies do not. QA defaults to the existing TEST interpretation when no environment is known, and the answer explicitly distinguishes TEST traffic from proven QA-run ownership. Sources without this operation return a coverage gap. The lookup searches 72 hours, then up to 30 days after no data. An unqualified latest lookup in an old investigation searches current retained traffic without changing its incident window; a dated lookup uses that date.

The selected ID and evidence reference persist so `why did that fail?` can trace the returned request and inspect its historical changes. A new unsuccessful lookup or an explicit date/ID/target correction invalidates the old selection. Explicit lookup targets such as `latest tracking ID for data-api in DEV` change the active conversation target, with old evidence retained separately. Ordinary dependency questions retain the original incident target and time. Current-scope discovery uses collection time after reload; a resolved historical scope keeps its persisted recovery cutoff until corrected.

Calendar month/year phrases can reach old retained history directly, including `last month`, `three months ago`, `in May 2025`, and `last year`. There is no age cap; each explicit search period spans at most one year and broad diagnostic periods narrow to a sampled observed failure. Latest tracking-ID lookups search the whole requested period. Numeric dates use the configured date order and resolved UTC scope. Explicit current follow-ups reset historical scope. Collection budgets apply per user turn, shared across all model iterations and traffic reads, with atomic cumulative search/file counters.

User-supplied tracking IDs retain their identity across date corrections regardless of entry route. A dated combined lookup and diagnosis traces the just-selected request. Automatically found IDs from a superseded incident are cleared. Explicit current intent carries through all reasoning iterations of the turn. Freshly collected evidence is prioritized within the prompt limit, even when its event date is months old.
