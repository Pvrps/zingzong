# ejb-capture: status after Phase 7 (Oct 23 2026)

A `-javaagent` that records EJB and HTTP invocations on WebLogic 14.1.2 / JDK 21 as JSON
traces, plus tools to view a recording, follow data lineage across its traces, export the
selected calls as steps, and replay one captured call. Phase-by-phase detail and acceptance
evidence: `docs/PLAN.md`; WebLogic internals touched: `docs/container-hook.md`.

## What the prototype proves

- **Capture is raw and zero-configuration.** `capture-start` / `capture-stop` are the only user
  actions. Every argument, result, exception, HTTP header and body that crossed an EJB or HTTP
  boundary is recorded as it was, with cycles as `@ref`, unloaded lazy collections as
  `@lazy`, runtime handles as `@handle`, non-serializable objects marked `@serializable: false`,
  and every safety limit that fires marked as a truncation. Recording off costs nothing
  measurable: 1.84 / 1.66 ms per call attached vs 1.83 ms detached over 10,000 calls.
- **Two hooks, one tree.** On WebLogic the agent advises the container's own invokers
  (`SessionLocalMethodInvoker` / `SessionRemoteMethodInvoker.invoke`) and reads interface,
  bean, method, view and transaction attribute from the container's invocation objects; the
  portable annotation hook (advice inlined into `@Stateless/@Singleton/...` classes) is the
  fallback and an override. Their trees are byte-identical after normalisation except for the
  spans only the annotation hook can see: a bean calling its own method directly, marked
  `selfInvocation: true` with no interface and no view (`hook-diff`).
- **Every EJB span carries what a client needs**: application, module, bean name and class,
  the invoked business interface, LOCAL/REMOTE, the declared transaction attribute, and every
  JNDI name the bean is bound under with the invoked view's `java:global` name first,
  descriptor mappings (`orders/OrderService`) included; each trace lists the deployed EARs
  with their `SourcePath`. All of it from `javax.management` / `javax.naming` and reflection by
  name, no `weblogic.jar` at compile time, and the runtime MBeanServer reached in-process without
  credentials (`java:comp/jmx/runtime`).
- **HTTP entry points are roots.** REST (JAX-RS) and SOAP (JAX-WS) requests open the trace
  with method, path, every header and the whole body on both sides; the resource method and
  the EJB tree hang under them.
- **Lineage across a scenario.** The reader finds values in later inputs (EJB args, request
  bodies, URL path segments, query parameters) that earlier outputs (results, response bodies)
  produced, keeps alternatives and flags values a trace also produced itself; unsourced values
  are classified `userInput` or `environment`. Confirm order's `orderId` ← Create order's
  `result.id` is detected, shown in the viewer as a badge that jumps to its source, and exported
  as `${orderId}`.
- **A captured call replays.** `replay-check` rebuilds the captured arguments from the span
  file with the agent's own reader, looks `jndiNames[0]` up over T3 with `wlthint3client.jar`,
  invokes the method named by `signature`, and gets a result of `result.declaredType`.

## What it does not do (yet)

- **Cross-server propagation.** Nesting works because WebLogic runs a collocated remote call
  on the calling thread (`TaxService` under `PricingService.price`). A remote EJB or JMS hop
  to *another* server starts an unrelated trace there: `TracePropagation` is an extension
  point with a `NONE` implementation (design in `docs/trace-propagation.md`), and
  `parentTraceId` exists in the model but nothing fills it.
- **JMS / message-driven beans.** Only session beans (stateless, singleton; stateful untested)
  and servlets are hooked. MDBs take a different container path and are not recorded.
- **The work exporter.** `PlaceholderExporter` emits YAML to prove the `Step` model is
  complete (lookup, payload, expected result, variables, substitutions); the work framework's
  format has to be written against `io.capture.export.Step` later. It has no EJB/T3 step type
  today; `replay-check` is the reference for one.
- **WebLogic 12.2.1 / JDK 8.** The agent compiles for Java 8 and matches by name, but it has
  only been run on 14.1.2.0.0 (build 2171472, no patches). Internal class names and the MBean
  path are recorded in `docs/container-hook.md` for re-verification.
- **Scale.** Captures are complete, not sampled; a recording of a long scenario is large (a
  JAX-RS `Response` result alone is ~20 KB with Jersey's internals). The viewer loads every
  trace of a recording into memory.
- **Not a security tool.** Capture is raw by design: credentials in headers and bodies are
  recorded. Recordings must be treated as sensitive.

## Recording a scenario and exporting it

All commands run inside `devenv shell` (`.local/` holds the install, domain and captures).

1. Start the domain with the agent and deploy the sample apps (once per session):
   `wls-start -b`, then `wls-deploy`. The agent log is `.local/captures/agent.log`; look for
   `server=WEBLOGIC hook=container (auto)`.
2. Open a recording: `capture-start <name>`. Optional filters on a shared server:
   `principal=<user>`, `session=<http session id>`.
3. Drive the scenario: the buttons at `http://localhost:7001/orders/`, or `curl`, or
   `soap-tax`, or a T3 client. Each entry point becomes one trace file in
   `.local/captures/rec-<timestamp>/`.
4. Close it: `capture-stop` (prints the directory and the trace count; `recording.json` holds
   the hook in force).
5. Look at it: `trace-view .local/captures/rec-<timestamp>` and open `http://localhost:8077/`.
   Left: traces by session lane; centre: span tree; right: the selected span in full with
   lineage badges. ↑/↓ change trace, ←/→ collapse/expand.
   (`trace-reader validate|tree|lineage|index` are the CLI equivalents.)
6. Export the calls that matter: `trace-reader export .local/captures/rec-<timestamp> --spans 1:s3,2:s3`
   (`<trace seq>:<span id>`, as shown in the viewer). `steps.yaml` appears in the recording
   directory: one step per selected span with lookup, payload (`${var}` where a value came
   from an earlier step, captured value kept beside it), expected result, `extract:` lists.
7. Prove one captured call replays: `mvn -q -pl replay-check test` against the running domain
   (the test uses the checked-in remote `createOrder` span; point it at another span file to
   try a different call).

To compare hooks: `CAPTURE_AGENT_ARGS=hook=annotation wls-start -b`, record the same scenario,
then `hook-diff <recA> <recB>`.
