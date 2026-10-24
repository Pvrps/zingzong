# ejb-capture

Java agent that records EJB and HTTP invocations on WebLogic as JSON traces, packaged as a
Maven plugin: `capture:enable` / `capture:disable` install or remove the agent in a domain,
`capture:start` serves the page where captures are started, stopped, watched and viewed.
README.md is the user's view; docs/history/ holds the prototype's plan and status
(tag `v0.1-prototype` has everything that was removed when the tooling became the plugin).

## Hard constraints
- Targets: WebLogic 14.1.2 / JDK 21 (migration target) and 12.2.1 / JDK 8 (current). Both are `javax.*`. Never `jakarta.*`.
- JDK 21 for server, apps and tools; `capture-agent` compiled with `--release 8`. Agent must load before server classes exist.
- The agent has NO compile dependency on `javax.*` or `weblogic.*`. Match by name, reach by reflection.
- Shade ByteBuddy and Jackson under `io.capture.shaded`. Append agent jar to the bootstrap search path.
- Agent classes load from the bootstrap loader, so they can only link against boot-layer modules (`java.base`, `java.logging`, `java.naming`, `java.management`, `java.instrument`). Never reference `java.sql.*` (platform loader) or any other non-boot module by class literal or `instanceof`; recognise by name, call reflectively. `BootstrapVisibilityTest` checks the jar.
- Capture must never throw into a business call. Degrade to placeholders + warnings.
- No getters during serialization. Never trigger a lazy load.
- WebLogic loads every application class in more than one loader (analysis loader, app loader, again on redeploy). Any per-type cache (field lists, method lookups, resolved `@type` classes, bean descriptors) is keyed by the `Class` object (`ClassValue`) or by name + `ClassLoader`, never by class name alone, and must not pin a loader: no strong `Class` references held outside the class's own reachability graph. Resolve classes through the loader of the object at hand, never the agent's.
- `trace-model` depends on Jackson only. Everything reads it.
- Everything on the plugin's path (`trace-model`, `trace-reader`, `capture-maven-plugin` and their dependencies) compiles with `--release 8`: at work the plugin runs inside whatever JDK the developer's Maven uses, and that is 8 today. The sample apps stay on 21. The plugin jar is shaded with its dependencies relocated; the agent jar is a plain dependency of the plugin, located among its resolved artifacts, never a path the user supplies.
- Applications are never modified. Only the domain start script changes.
- Capture is raw. The agent records what crossed the boundary, as it was: no masking, no redaction, no allow-lists, no deny-lists of "sensitive" fields or headers, no assumptions about what matters. The only transformations permitted are structural necessities, each explicitly marked in the output and never silent: a cycle or repeated object becomes `@ref`; an uninstantiated lazy collection or holder is `{"@lazy": true, "loaded": false}` because loading it would change the call; a non-walkable runtime handle (threads, class loaders, sockets and streams, JDBC connections and statements, locks and executors) is `{"@handle": type, "identity": ..., "toString": ...}` because it is not payload. Any limit that exists for safety (depth, element count, bytes per value, body size) is set high enough never to be hit by real payloads, is documented with its reason, and when hit emits an explicit truncation marker stating what was dropped and how much. `trace-reader` and the page may classify and annotate, never remove or hide.
- The agent requires no per-application, per-model or per-type configuration. The only user actions are `capture:enable`, `capture:disable`, `capture:start` and the page. Everything else (which hook, what is infrastructure vs. data, limits) is auto-detected or decided by a built-in heuristic with documented constants. The agent's only argument is `out=DIR`, written by `capture:enable`; the plugin's only parameters are `capture.domain`, `capture.port`, `capture.out`.
- With no recording active the agent has no advice installed anywhere; that is what makes a loaded-but-idle agent harmless after `capture:disable`.

## Environment (NixOS)
- Everything runs inside `devenv shell`; no containers.
- WebLogic installer jar at `$WLS_INSTALLER` (outside the repo). Install into `.local/oracle`.
- Lab scripts (environment, not product): `wls-install`, `wls-domain`, `wls-start [-b]` (a plain domain: no agent), `wls-stop`, `wls-deploy [--no-build]`. The product: `capture enable|disable|start` runs `mvn io.capture:capture-maven-plugin:<version>:<goal> -Dcapture.domain=$DOMAIN_HOME -Dcapture.out=$CAPTURE_OUT` after `mvn -q install`.
- A running server has its agent jar mapped: never overwrite that file in place (`capture:enable` replaces it by rename); a rebuilt agent is picked up at the next server start.
- Oracle tooling (installer, WLST) runs through the `wls-fhs-run` shim; see docs/environment-notes.md before changing scripts.
- `.local/` is gitignored: install, domain, H2, captures.

## Build & test
- `mvn -q test` for the unit tests (no server). Modules: `trace-model`, `capture-agent`, `trace-reader`, `capture-maven-plugin`, `sample-apps`.
- Lab loop: `mvn -q install` → `capture enable` → `wls-start -b` → `wls-deploy` → `capture start` → the page (Start, click the three sample buttons, Stop) → `capture disable`.
- Agent log: `.local/captures/agent.log`. Server log: `.local/domains/capture/servers/AdminServer/logs/`.

## Style
- Small classes, explicit names, no frameworks beyond ByteBuddy/Jackson/JUnit in the agent; the plugin uses the JDK's `com.sun.net.httpserver`, no web framework, and one HTML file with no build step.
- When an internal WebLogic class is referenced, record the version and stack frame in docs/container-hook.md.
