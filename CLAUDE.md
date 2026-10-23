# ejb-capture

Java agent that records EJB and HTTP invocations on WebLogic 14.1.2 as JSON traces,
plus tooling to view them, detect data lineage across a scenario, and export test steps.
Full plan: docs/PLAN.md. Work phase by phase; each phase has an acceptance check.

## Hard constraints
- Targets: WebLogic 14.1.2 / JDK 21 (migration target) and 12.2.1 / JDK 8 (current). Both are `javax.*`. Never `jakarta.*`.
- JDK 21 for server, apps and tools; `capture-agent` compiled with `--release 8`. Agent must load before server classes exist.
- The agent has NO compile dependency on `javax.*` or `weblogic.*`. Match by name, reach by reflection.
- Shade ByteBuddy and Jackson under `io.capture.shaded`. Append agent jar to the bootstrap search path.
- Capture must never throw into a business call. Degrade to placeholders + warnings.
- No getters during serialization. Never trigger a lazy load.
- `trace-model` depends on Jackson only. Everything reads it; the exporter is a thin mapping over it.
- Applications are never modified. Only the domain start script changes.

## Environment (NixOS)
- Everything runs inside `devenv shell`; no containers.
- WebLogic installer jar at `$WLS_INSTALLER` (outside the repo). Install into `.local/oracle`.
- Scripts: `wls-install`, `wls-domain`, `wls-start [-b]`, `wls-stop`, `wls-deploy [--no-build]`, `capture-start <name>`, `capture-stop`.
- Oracle tooling (installer, WLST) runs through the `wls-fhs-run` shim; see docs/environment-notes.md before changing scripts.
- `.local/` is gitignored: install, domain, H2, captures.

## Build & test
- `mvn -q -pl trace-model,capture-agent test` for fast unit tests (no server).
- Full loop: `wls-start` (background) → `wls-deploy` → `capture-start x` → click/curl → `capture-stop` → `trace-reader index .local/captures/<rec>`.
- Agent log: `.local/captures/agent.log`. Server log: `.local/domains/capture/servers/AdminServer/logs/`.

## Style
- Small classes, explicit names, no frameworks beyond ByteBuddy/Jackson/JUnit in the agent.
- When an internal WebLogic class is referenced, record the version and stack frame in docs/container-hook.md.
