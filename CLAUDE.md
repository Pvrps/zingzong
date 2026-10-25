# ejb-capture

Java agent that records EJB, HTTP and JDBC calls on WebLogic as JSON traces, packaged as a Maven
plugin: `capture:enable` / `capture:disable` install or remove the agent in a domain,
`capture:start` serves the page where captures are started, stopped, watched and viewed.
README.md and USAGE.md are the user's view; architecture/ is the developer's. This repository is
the product only; the lab that runs and verifies it (WebLogic installs and domains, sample apps,
load and check scripts) is the sibling repository `../ejb-capture-lab`.

## Hard constraints
- WebLogic only: 14.1.2 / JDK 21 and 12.2.1.4 / JDK 8, both `javax.*`, never `jakarta.*`. One container hook (architecture/HOOKS.md); no other strategy. In any other JVM the agent logs one line, registers its MBean with an inert state and installs nothing.
- Domains on Linux and on Windows: the plugin writes `bin/setUserOverrides.sh` or `bin\setUserOverrides.cmd` as the domain's `bin/` dictates, handles every path with `java.nio.file.Path`, and every unit test passes on any OS.
- `capture-agent` compiles with `--release 8` and has NO compile dependency on `javax.*` or `weblogic.*`: match by name, reach by reflection. ByteBuddy and Jackson are shaded under `io.capture.shaded`; the agent jar is appended to the bootstrap search path.
- Agent classes load from the bootstrap loader, so they link only against boot-layer modules (`java.base`, `java.logging`, `java.naming`, `java.management`, `java.instrument`). Never reference `java.sql.*` or any other non-boot module by class literal or `instanceof`; recognise by name, call reflectively. `BootstrapVisibilityTest` checks the jar.
- Capture must never throw into a business call. Degrade to placeholders and warnings.
- No getters during serialization. Never trigger a lazy load.
- WebLogic loads every application class in more than one loader. Any per-type cache is keyed by the `Class` object (`ClassValue`) or by name + `ClassLoader`, never by class name alone, and holds no strong `Class` reference outside the class's own reachability graph. Resolve classes through the loader of the object at hand, never the agent's.
- `trace-model` depends on Jackson only. Everything reads it.
- Every module compiles with `--release 8` (parent pom): the plugin runs inside whatever JDK the developer's Maven uses. The plugin jar is shaded with its dependencies relocated; the agent jar is a plain dependency of the plugin, located among its resolved artifacts, never a path the user supplies.
- Applications are never modified. Only the domain's overrides file changes.
- Capture is raw. The agent records what crossed the boundary, as it was: no masking, no redaction, no allow-lists, no deny-lists of "sensitive" fields or headers. The only transformations are structural necessities, each marked in the output: a cycle or repeated object becomes `@ref`; an uninstantiated lazy collection or holder is `{"@lazy": true, "loaded": false}`; a non-walkable runtime handle is `{"@handle": type, "identity": ..., "toString": ...}`. Every safety ceiling is set high enough never to be hit by real payloads, is documented with its reason, and when hit emits a `@truncated` marker stating what was dropped and how much. The plugin and the page classify and annotate, never remove or hide.
- No per-application, per-model or per-type configuration. The only user actions are `capture:enable`, `capture:disable`, `capture:start` and the page. The agent's only argument is `out=DIR`, written by `capture:enable`; the plugin's only parameters are `capture.domain`, `capture.port`, `capture.out`.
- With no recording active the agent has no advice installed anywhere; a loaded-but-idle agent changes nothing about the server.

## Environment
- The build needs a JDK (8 or 21) and Maven, nothing else: nothing in this repository may reference `DEVENV_ROOT`, `.local`, a lab path or a lab script. Unit tests run offline, with no server, on any OS.
- `devenv.nix` / `.envrc` are optional convenience: JDK 21 and Maven on the path, `JDK8_HOME` a Temurin 8. Deleting both leaves a repository that builds identically.
- The product is consumed by coordinates: the lab runs `mvn io.capture:capture-maven-plugin:<version>:<goal>` from the local Maven repository after `mvn -q install` here. A version bump here means bumping `PRODUCT_VERSION` in the lab's `devenv.nix`.
- A running server has its agent jar mapped: never overwrite that file in place (`capture:enable` replaces it by rename); a rebuilt agent is picked up at the next server start.

## Build & test
- `mvn -q install` builds and runs the unit tests, green under JDK 21 and under JDK 8 (`JAVA_HOME=$JDK8_HOME`). Modules: `trace-model`, `capture-agent`, `capture-maven-plugin`.
- `capture-agent` tests run against stubs named like the WebLogic targets (`src/test/java/weblogic/...`), serializer fixtures in `src/test/java/io/capture/agent/fixtures/` and recordings captured on the lab's 14.1.2 domain under `src/test/resources/captures/`. EclipseLink and `javaee-api` are test-scoped only. No test may depend on the lab.
- Verification against real servers happens in the lab: `mvn -q install` here, then in `../ejb-capture-lab` `capture enable` → `wls-start -b` → `wls-deploy` → `capture start` → the page (Start, the three sample buttons, Stop) → `capture disable`, on the 14 domain and with `WLS_VERSION=12` on the 12 domain.

## Code documentation
- Javadoc states what a class or method does and any constraint a caller must know, in one to three sentences, present tense, no history, no rationale essays, no restating the signature.
- A private method gets a comment only when its name is not enough. Comments inside methods explain a non-obvious why, never a what.
- No comment narrates a phase, a session, a finding or a decision; that belongs in architecture/.
- Every public class in main sources has a Javadoc; every test class has one line saying what it proves.

## Style
- Small classes, explicit names, no frameworks beyond ByteBuddy/Jackson/JUnit in the agent; the plugin uses the JDK's `com.sun.net.httpserver`, no web framework, and a page of four plain files (`index.html`, `style.css`, `app.js`, `diagram.js`) with no build step.
- When an internal WebLogic class is referenced, record the version, build and stack frame in architecture/HOOKS.md for both 12.2.1.4 and 14.1.2.
