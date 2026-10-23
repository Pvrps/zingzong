# ejb-capture

Java agent that records EJB and HTTP invocations on WebLogic 14.1.2 as JSON traces,
plus tooling to view them, detect data lineage across a scenario, and export test steps.
Full plan: docs/PLAN.md. Work phase by phase; each phase has an acceptance check.

## Hard constraints
- Targets: WebLogic 14.1.2 / JDK 21 (migration target) and 12.2.1 / JDK 8 (current). Both are `javax.*`. Never `jakarta.*`.
- JDK 21 for server, apps and tools; `capture-agent` compiled with `--release 8`. Agent must load before server classes exist.
- The agent has NO compile dependency on `javax.*` or `weblogic.*`. Match by name, reach by reflection.
- Shade ByteBuddy and Jackson under `io.capture.shaded`. Append agent jar to the bootstrap search path.
- Agent classes load from the bootstrap loader, so they can only link against boot-layer modules (`java.base`, `java.logging`, `java.naming`, `java.management`, `java.instrument`). Never reference `java.sql.*` (platform loader) or any other non-boot module by class literal or `instanceof`; recognise by name, call reflectively. `BootstrapVisibilityTest` checks the jar.
- Capture must never throw into a business call. Degrade to placeholders + warnings.
- No getters during serialization. Never trigger a lazy load.
- WebLogic loads every application class in more than one loader (analysis loader, app loader, again on redeploy). Any per-type cache (field lists, method lookups, resolved `@type` classes, bean descriptors) is keyed by the `Class` object (`ClassValue`) or by name + `ClassLoader`, never by class name alone, and must not pin a loader: no strong `Class` references held outside the class's own reachability graph. Resolve classes through the loader of the object at hand, never the agent's.
- `trace-model` depends on Jackson only. Everything reads it; the exporter is a thin mapping over it.
- Applications are never modified. Only the domain start script changes.
- Capture is raw. The agent records what crossed the boundary, as it was: no masking, no redaction, no allow-lists, no deny-lists of "sensitive" fields or headers, no assumptions about what matters. The only transformations permitted are structural necessities, each explicitly marked in the output and never silent: a cycle or repeated object becomes `@ref`; an uninstantiated lazy collection or holder is `{"@lazy": true, "loaded": false}` because loading it would change the call; a non-walkable runtime handle (threads, class loaders, sockets and streams, JDBC connections and statements, locks and executors) is `{"@handle": type, "identity": ..., "toString": ...}` because it is not payload. Any limit that exists for safety (depth, element count, bytes per value, body size) is set high enough never to be hit by real payloads, is documented with its reason, and when hit emits an explicit truncation marker stating what was dropped and how much. Analysis tools in `trace-tools` may classify and annotate, never remove or hide.
- The agent requires no per-application, per-model or per-type configuration. The only user actions are `capture-start` and `capture-stop`. Everything else (which hook, what is infrastructure vs. data, limits) is auto-detected or decided by a built-in heuristic with sane defaults. Agent arguments exist for tests and diagnostics, never as something a user must set to get correct output.

## Environment (NixOS)
- Everything runs inside `devenv shell`; no containers.
- WebLogic installer jar at `$WLS_INSTALLER` (outside the repo). Install into `.local/oracle`.
- Scripts: `wls-install`, `wls-domain`, `wls-start [-b]`, `wls-stop`, `wls-deploy [--no-build]`, `capture-start <name> [principal=..] [session=..]`, `capture-stop`, `capture-status`, `trace-reader validate|tree|lineage|index`, `soap-tax`, `hook-diff <recA> <recB>`, `jndi-proof <trace.json>`.
- Never rebuild `capture-agent.jar` expecting a running server to pick it up: `wls-start` runs from a copy; restart to use a new build.
- Oracle tooling (installer, WLST) runs through the `wls-fhs-run` shim; see docs/environment-notes.md before changing scripts.
- `.local/` is gitignored: install, domain, H2, captures.

## Build & test
- `mvn -q -pl trace-model,capture-agent,trace-tools/trace-reader test` for fast unit tests (no server).
- Full loop: `wls-start` (background) → `wls-deploy` → `capture-start x` → click/curl → `capture-stop` → `trace-reader index .local/captures/<rec>`.
- Agent log: `.local/captures/agent.log`. Server log: `.local/domains/capture/servers/AdminServer/logs/`.

## Style
- Small classes, explicit names, no frameworks beyond ByteBuddy/Jackson/JUnit in the agent.
- When an internal WebLogic class is referenced, record the version and stack frame in docs/container-hook.md.
