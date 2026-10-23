# EJB Capture & Replay — Implementation Plan

Oct 23, 2026 · @Purps

## Goals and scope

The prototype must prove that a Java agent attached to a WebLogic JVM can record every EJB invocation on the server, with full argument and result objects, as a tree per trigger and a sequence per scenario, without any change to the deployed applications. The output is a stable, framework-agnostic JSON trace model that a later exporter can turn into test steps.

**In scope**

- A `-javaagent` installed through the domain start script only; applications are never touched.
- Capture of EJB calls (local and remote), with a thread-local span stack so nested calls form a tree.
- Generic, reflection-based serialization of arbitrary argument and result graphs.
- Recordings: an explicit start/stop window that groups ordered traces into one scenario.
- Lineage detection: values in later inputs that came from earlier outputs.
- Web entry points (servlet, REST, SOAP) captured as root spans with request and response bodies.
- WebLogic-specific metadata per span: application, module, EJB name, interface, JNDI names.
- A tree viewer, a reference exporter to a placeholder format, and a 50-line sanity harness that replays one captured call over T3.

**Out of scope for now**

- The real exporter to the work framework (it has no EJB/T3 step type yet).
- Cross-server and JMS/MDB trace propagation (designed for, implemented last).
- Containers, the home server, CI.

**Target platform**: WebLogic 14.1.2 on JDK 21 (the migration target), with WebLogic 12.2.1.x on JDK 8 as the current production baseline. Both use the `javax.*` namespace (Java EE 7 and Jakarta EE 8 respectively), so the sample apps and the agent stay on `javax.*`. The agent is compiled with `--release 8` so one jar runs on both.

**Design rule**: the trace model is the product. The agent writes it, every tool reads it, and the exporter is a thin mapping over it.

## Local environment on NixOS

Everything runs on the bare JVM inside a `devenv` shell; no containers are needed because WebLogic, H2 and the tooling are all plain Java. The WebLogic installer is a Java jar, so it runs fine under Nix without an FHS wrapper (the WLST and domain scripts are POSIX shell and bash).

**Manual prerequisites (one-time, before the first Claude Code session)**

1. **devenv on NixOS.** Add `pkgs.devenv` to `environment.systemPackages` in `configuration.nix` and rebuild, or run `nix profile install nixpkgs#devenv`. Flakes must be enabled: `nix.settings.experimental-features = [ "nix-command" "flakes" ];`. Optional but convenient: `programs.direnv.enable = true;` with `nix-direnv`, so `cd ejb-capture` enters the shell automatically via `.envrc` (`use devenv`).
2. **WebLogic installer.** This cannot be scripted: Oracle requires a signed-in account and a click-through license. Open the [WebLogic Server Installers page](https://www.oracle.com/middleware/technologies/weblogic-server-installers-downloads.html), scroll to **Oracle WebLogic Server 14c (14.1.2.0)**, and under **Generic Installer for Oracle WebLogic Server and Oracle Coherence** click **Oracle WebLogic Server and Coherence (655 MB)**. Accept the OTN license in the lightbox, sign in (a free Oracle account is enough), and download. Skip the Examples and Fusion Middleware Infrastructure installers. Save the jar as `~/Documents/oracle/installers/<downloaded name>` and make `WLS_INSTALLER` in `devenv.nix` match that exact file name. The same page also lists the 12.2.1.4 generic installer if a local copy of the current production version is ever wanted.
3. **Nothing else.** JDK 21, Maven, jq and git come from `devenv shell`; H2 comes from Maven; `wlthint3client.jar` is part of the WebLogic install. Disk: budget about 2 GB under `.local/` for the install and domain.

Optional: the WebLogic Remote Console (a separate desktop app from Oracle's GitHub) if a GUI for the domain is wanted; the scripts do not depend on it.

**WebLogic download**: get `fmw_14.1.2.0.0_wls_lite_generic.jar` from Oracle Technology Network under the free developer license (requires an Oracle account; confirm the current OTN terms). Keep the jar outside the repo in a directory that survives reboots under impermanence, e.g. `~/Documents/oracle/installers/`, and reference it via an env var.

**JDK**: WebLogic 14.1.2 is certified on Oracle JDK 17/21; OpenJDK 21 (`pkgs.temurin-bin-21`) works for development. Use JDK 21 for the server, the sample apps and the tooling. The `capture-agent` module alone is compiled with `--release 8` so the same jar also loads in the 12.2.1 / JDK 8 servers still in production; ByteBuddy and Jackson both support that.

**devenv.nix sketch**

```nix
{ pkgs, config, ... }:
let
  oracleHome = "${config.env.DEVENV_ROOT}/.local/oracle";
  domainHome = "${config.env.DEVENV_ROOT}/.local/domains/capture";
in {
  packages = with pkgs; [ maven jq git ];

  languages.java = {
    enable = true;
    jdk.package = pkgs.temurin-bin-21;
    maven.enable = true;
  };

  env = {
    ORACLE_HOME = oracleHome;
    MW_HOME = oracleHome;
    DOMAIN_HOME = domainHome;
    WLS_INSTALLER = "$HOME/Documents/oracle/installers/fmw_14.1.2.0.0_wls.jar";  # persisted path (impermanence)
    CAPTURE_AGENT_JAR = "${config.env.DEVENV_ROOT}/capture-agent/target/capture-agent.jar";
    CAPTURE_OUT = "${config.env.DEVENV_ROOT}/.local/captures";
    MAVEN_OPTS = "-Dmaven.repo.local=${config.env.DEVENV_ROOT}/.local/m2";  # keep the Maven cache in the repo, not ~/.m2
  };

  scripts = {
    wls-install.exec  = "bash scripts/wls-install.sh";   # silent install into $ORACLE_HOME
    wls-domain.exec   = "bash scripts/wls-domain.sh";    # WLST: create domain, dev mode
    wls-start.exec    = "bash scripts/wls-start.sh";     # startWebLogic with -javaagent
    wls-deploy.exec   = "bash scripts/wls-deploy.sh";    # weblogic.Deployer for sample EARs
    capture-start.exec = "bash scripts/capture.sh start \"$@\"";  # JMX: begin recording
    capture-stop.exec  = "bash scripts/capture.sh stop";
  };
}
```

**Silent install**: `java -jar $WLS_INSTALLER -silent -responseFile scripts/wls-response.rsp -invPtrLoc scripts/oraInst.loc`. The response file sets `ORACLE_HOME` and `INSTALL_TYPE=WebLogic Server`.

**NixOS findings from Phase 0 (Oct 23 2026, WLS 14.1.2.0.0, Temurin 21).** Three things the sketch above got wrong; the scripts already handle them, so this is here so nobody removes the workarounds. Details in `docs/environment-notes.md`.

1. **Oracle tooling needs a minimal FHS shim.** The OUI launcher (`com.oracle.cie.common.util.Chmod`) and WLST both exec `/bin/chmod` by absolute path; NixOS only has `/bin/sh`. `devenv.nix` defines `wls-fhs-run`, a bubblewrap wrapper that mounts coreutils over `/bin` and `/usr/bin` and leaves everything else as-is. `scripts/lib.sh` exposes it as `oracle_run`; the installer and WLST go through it, the running server does not need it. Symptoms without it: "Unable to change access permissions on the temporary directory", or non-executable `$DOMAIN_HOME/bin/*.sh`.
2. **The installer rejects OpenJDK** with "The OpenJDK JVM is not supported on this platform". The launcher classifies the JVM by `java.vm.name` (Temurin: `OpenJDK 64-Bit Server VM`) and only accepts `VM_TYPES=HotSpot,IBM,JRockit` from its bundled `oraparam.ini`. `scripts/wls-oraparam.ini` is a copy with `OpenJDK` added, passed with `-paramFile`.
3. **`INST-07004` rejects dot-prefixed path components**, so `.local/oracle` fails response-file validation. `wls-install.sh` passes `-novalidation`; nothing else in the response file depends on that check.

Also: `-ignoreSysPrereqs` skips the certified-OS/`rpm` checks; `oui/.globalEnv.properties` and `setDomainEnv.sh` are pointed at the `.local/jdk` symlink (refreshed on `enterShell`) so a nixpkgs JDK bump cannot strand the install; `commBaseEnv.sh` only honours `JAVA_HOME` from the environment when `JAVA_VENDOR` is also set, which `lib.sh` does; the domain listens on `127.0.0.1` only because the dev credentials are defaults.

**Domain creation**: a WLST script (`scripts/create-domain.py`) that reads the basic template, sets dev mode, admin user and port 7001, and writes `$DOMAIN_HOME`. Dev mode keeps the admin console and auto-deploy available; Secured Production Mode (the 14.1.2 default for production domains) is deliberately avoided.

**Agent wiring**: `wls-start.sh` exports `JAVA_OPTIONS="$JAVA_OPTIONS -javaagent:$CAPTURE_AGENT_JAR=out=$CAPTURE_OUT"` and `JAVA_TOOL_OPTIONS` with the `--add-opens` flags the serializer needs on JDK 9+ (omit them on a JDK 8 domain) (`java.base/java.util`, `java.base/java.lang`, `java.base/java.time`, `java.sql/java.sql`), then runs `$DOMAIN_HOME/bin/startWebLogic.sh`.

**Database**: H2 in embedded mode via a WebLogic JDBC data source pointing at `$DEVENV_ROOT/.local/h2/sample`. No container, no daemon.

**Admin UI**: WebLogic 14.1.2 replaced the in-server console with the WebLogic Remote Console, a separate download. Deploy and inspect through WLST and `weblogic.Deployer` from the scripts instead, and add the Remote Console only if it proves useful.

Ignore `.local/` in git; it holds the install, domain, captures and H2 files.

## Repository layout

One Maven multi-module repo, `ejb-capture/`, with the environment and scripts at the root.

```text
ejb-capture/
├── devenv.nix / devenv.yaml
├── CLAUDE.md
├── pom.xml                      # parent: javax EE 8 BOM, JDK 17, shared versions
├── scripts/                     # wls-install.sh, wls-domain.sh, create-domain.py,
│                                # wls-start.sh, wls-deploy.sh, capture.sh, *.rsp
├── trace-model/                 # POJOs + JSON schema for the trace format (no deps beyond Jackson)
├── capture-agent/               # the -javaagent (ByteBuddy, shaded; depends on trace-model)
│   └── src/main/java/.../
│       ├── Agent.java           # premain, config parsing, installs transformers
│       ├── hook/                # EjbAnnotationHook, WlsContainerHook, ServletHook, ...
│       ├── span/                # SpanStack (thread-local), Recorder, RecordingControl (JMX)
│       ├── serialize/           # GraphSerializer SPI + JacksonGraphSerializer
│       └── meta/                # WebLogic MBean lookups (JNDI names, app/module)
├── sample-apps/
│   ├── common-dto/              # DTOs shared across "teams"
│   ├── orders-ear/              # OrderService (remote+local), InventoryService, PricingService
│   │   ├── orders-ejb/
│   │   ├── orders-web/          # static HTML/JS front end (two buttons) + JAX-RS resource
│   │   └── orders-ear/
│   ├── tax-ear/                 # TaxService, called remotely by PricingService
│   │   ├── tax-ejb/
│   │   └── tax-ear/
│   └── (later) notify-ear/      # MDB fed by OrderService, for async propagation
├── trace-tools/
│   ├── trace-reader/            # parse captures, build trees, lineage detection
│   ├── trace-viewer/            # static HTML tree viewer, reads JSON files
│   └── trace-export/            # Exporter SPI + PlaceholderExporter
└── replay-check/                # one JUnit test: deserialize a span, invoke over T3
```

**Why `trace-model` is separate**: the agent, the tools and the eventual work exporter all depend on it, and nothing else. It must not depend on ByteBuddy or WebLogic.

**Agent packaging**: shade ByteBuddy and Jackson into the agent jar under a relocated package (`io.capture.shaded.*`) so they cannot collide with application copies. Manifest: `Premain-Class`, `Agent-Class`, `Can-Retransform-Classes: true`. Keep the agent jar free of `javax.*` or `weblogic.*` compile dependencies; reach those reflectively or via ByteBuddy's name-based matchers so the agent loads before the server classes exist.

**Sample apps**: the primary chain is **button → REST → EJB A → EJB B**. A static `index.html` in `orders-web` calls `OrdersResource` (JAX-RS) with `fetch`; the resource injects `OrderService` (EJB A) with `@EJB`; `OrderService` calls `PricingService` and `InventoryService` (EJB B, local). A second EAR, `tax-ear`, adds an optional third hop: `PricingService` looks up `TaxService` remotely by JNDI. It is written as if by another team and exists to exercise remote invocation and T3 replay. DTOs include nested objects, lists and maps, enums, `java.time` and `java.util.Date`, `BigDecimal`, a JPA entity with a lazy `@OneToMany`, and one field that is not `Serializable`.

Two fixture details settled after the first build (Oct 23 2026):

- **The non-`Serializable` field is not Java-`transient`.** `Order.audit` (`AuditTrail`: no no-arg constructor, holds a `StringBuilder` and a lock object) is `@Transient` for JPA but a plain field otherwise, because Jackson and any field-walking serializer skip `transient` fields and the placeholder-and-warn path would never run. `Order` declares `serialPersistentFields` without `audit` plus a `writeObject`/`readObject` pair that carries `openedAt` across and rebuilds the trail, so `OrderServiceRemote.createOrder` still works over T3 (verified with an authenticated `wlthint3client` call; the client needs `eclipselink.jar` for the lazy `IndirectList`).
- **One polymorphic field for `@type` testing.** `CreateOrderRequest.paymentMethod` is a `PaymentMethod` interface with `CardPayment` (last four digits, `YearMonth` expiry) and `InvoicePayment` (purchase order number, net days), both `Serializable`. `index.html` alternates card and invoice on successive creates; the REST payload is `{"type":"CARD"|"INVOICE", ...}`; `OrderService` persists `PaymentMethod.summary()` as `Order.paymentSummary`.

## Trace model

One JSON file per trace, written when its root span completes, under `$CAPTURE_OUT/<recordingId>/<seq>-<traceId>.json`. Spans are stored flat with `parentId`; tools rebuild the tree. Versioned from day one (`"formatVersion": 1`).

```json
{
  "formatVersion": 1,
  "recording": { "id": "rec-20261023-1401", "name": "create-and-confirm-order", "seq": 2 },
  "trace": {
    "id": "t-8c1f…", "startedAt": "2026-10-23T18:01:12.345Z", "durationMs": 142,
    "principal": "weblogic", "httpSessionId": "abc…", "serverName": "AdminServer"
  },
  "spans": [
    {
      "id": "s1", "parentId": null, "depth": 0, "order": 0,
      "kind": "HTTP",
      "http": { "method": "POST", "path": "/orders/api/orders/42/confirm", "status": 200,
                "requestBody": "…", "responseBody": "…", "contentType": "application/json" },
      "app": "orders-ear", "module": "orders-web", "thread": "[ACTIVE] ExecuteThread: '3'",
      "startNs": 0, "durationNs": 142000000
    },
    {
      "id": "s2", "parentId": "s1", "depth": 1, "order": 1,
      "kind": "EJB",
      "ejb": {
        "app": "orders-ear", "module": "orders-ejb", "beanName": "OrderService",
        "beanClass": "com.acme.orders.OrderServiceBean",
        "interface": "com.acme.orders.api.OrderServiceRemote",
        "invocation": "REMOTE",
        "jndiNames": ["java:global/orders-ear/orders-ejb/OrderService!com.acme.orders.api.OrderServiceRemote",
                      "orders/OrderService"],
        "method": "confirmOrder",
        "signature": "com.acme.orders.api.dto.ConfirmResult confirmOrder(com.acme.orders.api.dto.ConfirmRequest)",
        "transaction": "REQUIRED"
      },
      "args": [ { "declaredType": "com.acme.orders.api.dto.ConfirmRequest", "value": { "@type": "…", "orderId": 42, "…": "…" } } ],
      "result": { "declaredType": "…", "value": { "…": "…" } },
      "exception": null,
      "serializationWarnings": [ { "path": "$.lines[0].product.supplier", "reason": "depth limit", "typeName": "…" } ],
      "startNs": 3100000, "durationNs": 97000000
    }
  ],
  "lineage": [
    { "to": { "span": "s2", "path": "args[0].orderId" },
      "from": { "trace": "t-11aa…", "span": "s4", "path": "result.id" }, "value": "42" }
  ]
}
```

**Rules the model commits to**

- `value` is the serialized object graph. Polymorphic types carry `@type` (fully qualified class) so a deserializer can rebuild them; the exporter decides whether to keep or strip it.
- Object identity: repeated references to the same object are emitted as `{"@ref": "<objectId>"}` with the first occurrence carrying `"@id"`. Cycles therefore terminate.
- Every value that could not be serialized is replaced by a placeholder (`{"@unserializable": "<class>", "toString": "…"}`) and recorded in `serializationWarnings`. Capture never fails a business call.
- `args` and `result` capture *after* the call returns (so mutated arguments reflect the post-state) **and** `argsBefore` optionally captures a pre-call snapshot when `snapshotArgs=true`. Default off; it doubles serialization cost.
- `exception` holds class, message, and the serialized exception object; `result` is null in that case.
- `lineage` is written by `trace-reader`, not by the agent; the agent leaves it absent.
- `kind` is an open enum: `EJB`, `HTTP`, `SOAP`, `JMS` to start.

A JSON Schema in `trace-model/src/main/resources/trace.schema.json` is the contract; `trace-reader` validates on load.

## Phase 0: environment and sample applications

Done when a developer can clone the repo, run `devenv shell`, and have a WebLogic domain with two deployed EARs that call each other, with no agent involved yet.

1. Write `devenv.nix`, the parent `pom.xml` (JDK 21 toolchain, `capture-agent` at `--release 8`, `javax.javaee-api:8.0` provided scope, ByteBuddy, Jackson, JUnit 5 versions) and `.gitignore`.
2. Write `scripts/wls-install.sh` with a silent-install response file; verify `$ORACLE_HOME/wlserver` exists.
3. Write `scripts/create-domain.py` (WLST) and `scripts/wls-domain.sh`; verify the domain starts in dev mode on port 7001 and `t3://localhost:7001` answers.
4. Build `common-dto` with the deliberately awkward types listed under Repository layout.
5. Build `orders-ear`, the primary chain:
   - `orders-web`: a static `index.html` with two buttons, **Create order** and **Confirm order**, calling `POST /orders/api/orders` and `POST /orders/api/orders/{id}/confirm` with `fetch`; the second button uses the ID returned by the first. `OrdersResource` (JAX-RS) injects `OrderService` with `@EJB`.
   - `orders-ejb`: `OrderService` (EJB A, local + remote) calls `PricingService` and `InventoryService` (EJB B, local). `createOrder` persists an `Order` entity via JPA/H2 and returns it with the lazy lines collection untouched.
6. Smoke test the primary chain by hand: both buttons work and the server log shows REST → `OrderService` → `PricingService`.
7. Build `tax-ear`, the third hop: `TaxService` with a remote interface, `computeTax(TaxRequest) -> TaxResult`; change `PricingService` to look it up by JNDI and call it.
8. Configure the H2 data source and JPA persistence unit in the domain; deploy both EARs with `scripts/wls-deploy.sh` and repeat the smoke test, now seeing the tax call land in the second EAR.

**Acceptance**: `wls-start` then `wls-deploy` from a fresh shell works end-to-end, and the two-button scenario completes.

**Status (Oct 23 2026): done.** Layout deviates slightly from the sketch: the business interfaces live in `sample-apps/orders-api` and `sample-apps/tax-api` (the jars a T3 client and `replay-check` need), DTOs and the JPA entities in `common-dto`. `InventoryService` is a `@Singleton` so stock survives across calls. Two things to keep: `orders-web/WEB-INF/beans.xml` (without CDI, Jersey does not honour `@EJB`) and `MODE=LEGACY` on the H2 URL (EclipseLink emits `BIGINT IDENTITY`). See `docs/environment-notes.md`.

The awkward types are not optional. They are the test fixtures for Phase 2, and adding them later means re-testing serialization.

## Phase 1: agent skeleton

Done when the agent loads into WebLogic and logs one line per EJB method call, with bean class, method and thread, and the server behaves exactly as before.

1. Create `capture-agent` with `premain(String args, Instrumentation inst)`. Parse `key=value,key=value` args: `out`, `enabled` (default `false`), `include`/`exclude` package globs, `hook` (`annotation` | `container`, default `annotation`). **The parent POM is `--release 21`; this module must set `<maven.compiler.release>${agent.compiler.release}</maven.compiler.release>` (= 8) so the same jar loads on the 12.2.1 / JDK 8 servers.** No `var`, records, text blocks or `java.lang.Module` in agent code.
2. Add the Maven Shade plugin: relocate `net.bytebuddy` and `com.fasterxml.jackson` under `io.capture.shaded`, set the manifest attributes, produce `capture-agent.jar`.
3. Implement `EjbAnnotationHook` with ByteBuddy's `AgentBuilder`:
   - Match types annotated with `javax.ejb.Stateless`, `Stateful`, `Singleton` or `MessageDriven` **by annotation name** (`isAnnotatedWith(named("javax.ejb.Stateless"))`), never by class literal.
   - Advise `isPublic().and(not(isStatic())).and(not(isConstructor()))` methods with an `Advice` class whose `@OnMethodEnter`/`@OnMethodExit(onThrowable = Throwable.class)` call into a static `Recorder` entry point.
   - Use `AgentBuilder.RedefinitionStrategy.RETRANSFORMATION` and `.with(InitializationStrategy.NoOp)` so classes already loaded get picked up when `enabled` is toggled on later.
   - Ignore `weblogic.*`, `oracle.*`, `com.oracle.*`, `com.bea.*` and the shaded packages.
4. The `Recorder` for this phase only logs. Advice code runs inside the application's classloader context, so everything it calls must be reachable from the bootstrap or system loader: append the agent jar to the **bootstrap** search path in `premain` (`inst.appendToBootstrapClassLoaderSearch`) and keep `Recorder` and the span classes dependency-free apart from shaded libs.
5. Add a `RecordingControl` JMX MBean (`io.capture:type=Control`) with `start(name)`, `stop()`, `isEnabled()`. `scripts/capture.sh` drives it via `jcmd` or a tiny `JMXConnector` client. Register it in `premain`.
6. Add an `AgentBuilder.Listener` that logs transformation errors to a file under `$CAPTURE_OUT/agent.log`, never to stdout.

**Acceptance**: with the agent on and `capture-start`, clicking **Create order** logs `OrderServiceBean.createOrder`, `InventoryServiceBean.reserve`, `PricingServiceBean.price`, and `TaxServiceBean.computeTax` in that order. With the agent on and recording off, nothing is logged and response times are unchanged.

**Status (Oct 23 2026): done.** Notes for the next phases:

- `trace-model` is compiled with `--release 8` as well, since the agent shades it. Its classes are plain public-field records; `TraceJson` is the shared mapper (public fields only, nulls omitted, unknown fields tolerated). The JSON key `interface` maps to `EjbInfo.interfaceName`.
- `Agent` (system loader) only touches `AgentBootstrap` (bootstrap loader, public) after `appendToBootstrapClassLoaderSearch`; everything else resolves through the bootstrap loader, so advice inlined into application classes and the agent share one `Recorder`.
- The hook ignores `io.capture.*`, so test fixtures live in `com.acme.fixtures`. `ForAdvice` locates the advice class through the system class loader.
- `capture.sh` uses `jcmd PID ManagementAgent.start_local` + `ManagementAgent.status` to get a same-user local JMX URL; no JMX flags on the server. `capture-status` was added next to start/stop.
- WebLogic loads each bean class twice (an analysis loader, then the application loader), so `agent.log` shows two `instrumented` lines per bean. Self-invocations (`confirmOrder → findOrder`, `price → unitPrice`) are logged as plain entries; Phase 3 marks them.
- Observed overhead with recording off: none measurable (6–10 ms per REST call before and after).

**Gotchas to expect**

- WebLogic's generated EJB wrappers and `_Impl` classes will match some patterns; the annotation matcher avoids them, but `beanClass` should always be the user's class, not a subclass.
- JDK 17 strong encapsulation: advice must not reflect into `java.*` internals without `--add-opens`.
- ByteBuddy `Advice` is inlined into the target method; keep it tiny and delegate immediately to `Recorder`.

## Phase 2: generic serialization

Done when every argument and result in the sample apps round-trips through JSON, or produces a placeholder plus a warning, and no capture ever throws into the business call. This is the phase to budget the most time for.

1. Define `GraphSerializer` in `capture-agent/serialize`: `JsonNode serialize(Object value, Class<?> declaredType, SerializationContext ctx)` and `Object deserialize(JsonNode, ClassLoader)`. Keep it an SPI so XStream or a hand-rolled walker can replace Jackson if needed.
2. Implement `JacksonGraphSerializer` with a defensively configured `ObjectMapper`:
   - Field visibility `ANY`, getters ignored (getters run code and trigger lazy loads).
   - `FAIL_ON_EMPTY_BEANS` off; `WRITE_DATES_AS_TIMESTAMPS` off; `JavaTimeModule` registered.
   - Default typing with a `BasicPolymorphicTypeValidator` that allows the application's packages, emitting `@type`.
   - Identity tracking via a custom `BeanSerializerModifier` (or `@JsonIdentityInfo` applied by mixin to `Object`) so repeated references become `@ref` and cycles terminate.
   - A `maxDepth` (default 12) and `maxElements` per collection (default 500) enforced by a wrapping `JsonGenerator`; overflow writes a placeholder and a warning.
   - A catch-all: any exception while serializing a sub-tree yields `{"@unserializable": …, "toString": …}` and a warning. Wrap the whole call so a failure degrades the span, never the request.
3. Handle the known traps explicitly:
   - **EclipseLink lazy indirection** (WebLogic's default JPA): detect `org.eclipse.persistence.indirection.IndirectContainer` and `ValueHolderInterface` by name; emit `{"@lazy": true, "loaded": false}` when not instantiated, serialize contents when loaded. Never trigger a load.
   - **Hibernate** proxies and `PersistentCollection`, same treatment, in case an app ships its own provider.
   - `Throwable`: class, message, stack top 10 frames, cause chain.
   - `Class`, `ClassLoader`, loggers, data sources, `EntityManager`: placeholder by type name.
   - Arrays, `Optional`, `BigDecimal` (as string), `byte[]` (base64, capped).
4. Classloader correctness: serialize on the calling thread with the current context classloader, and resolve classes for `@type` through the loader of the object being serialized, not the agent's loader.
5. Build a unit test suite in `capture-agent` using the `common-dto` types directly (no server): one test per awkward type, asserting the JSON shape and the deserialized equality where the type supports it.
6. Wire it into `Recorder`: on method exit, serialize args and result (or exception) into a `Span` and hand it to a writer. Writing happens on the calling thread for now; move it to a bounded queue + background thread in Phase 3.

**Acceptance**: a capture of **Create order** shows the `Order` entity with its lazy lines marked unloaded, the `BigDecimal` totals as strings, the enum by name, the non-serializable field as a placeholder with a warning, and the whole span deserializes back in the unit test.

**Decision deferred**: whether `args` are captured before the call, after, or both. Default to after with `snapshotArgs=true` available; revisit once lineage detection shows which one the exporter needs.

**Status (Oct 23 2026): done.** Acceptance met on the real server (recording `rec-20261023-155008`; the `createOrder` and `computeTax` files are checked in as `capture-agent/src/test/resources/captures/` and deserialized by `CapturedSpanFixtureTest`). Decisions that differ from the sketch above, all deliberate:

- **`JacksonGraphSerializer` walks the graph itself and emits Jackson trees; it does not go through `ObjectMapper` bean serialization.** Jackson's serializer cache keys `Class` strongly (would pin redeployed loaders), getters would need suppressing per type, identity and depth limits would need mixins plus a wrapping generator, and lazy wrappers must be recognised before any collection method runs. Walking fields directly keeps all of those rules in one class; Jackson still provides the tree model, scalar nodes and the output. Per-type state is a `ClassValue` (`TypeInfo`), method lookups too (`MethodSignatures`); `ClassLoaderIsolationTest` proves two loaders with the same class name do not confuse either.
- ~~Non-`Serializable` application objects become placeholders~~ **Superseded in Phase 3** by the zero-configuration rule: every application object is captured reflectively; a class that is not `java.io.Serializable` gets `"@serializable": false` on its node (data kept, exporter and replay know it cannot cross T3). What that rule used to keep out is now excluded by `InfrastructureTypes`, a built-in heuristic on type hierarchy (loggers, locks, executors, threads, class loaders, streams, sockets, JDBC/JPA/JMS/JNDI handles, servlet objects) and package (`weblogic.*`, `oracle.*`, `com.bea.*`, EclipseLink/Hibernate internals, logging frameworks, `sun.*`/`jdk.*`); those become `@unserializable` placeholders with an "infrastructure type" warning. `AuditTrail` is therefore captured (`openedAt`, `entryCount`, entries text) with no warning.
- **Lazy handling**: `IndirectContainer`/`ValueHolderInterface` (EclipseLink) and `PersistentCollection`/`HibernateProxy` (Hibernate) by name on the object's own hierarchy, `isInstantiated()`/`wasInitialized()` only. EclipseLink *weaving* is handled too: a field `x` with a sibling `_persistence_x_vh` holder is reported through the holder's state. `_persistence_*`, static, transient and synthetic fields are skipped. JPA entities and lazy wrappers never get `toString()` called, placeholders use the identity form for them.
- **Placeholders on read** (`@lazy`, `@unserializable`) leave the field at its constructor default rather than forcing `null`, and read as `null` at the top level. `@type` names must pass `TypeAllowList` (agent arg `types=`; JDK-internal and container packages always denied). Instances come from a no-arg constructor of any visibility, else the serialization constructor via `sun.reflect.ReflectionFactory` reached reflectively.
- **Format details** settled: enums by name without `@type`; string/enum/number-keyed maps as plain JSON objects, other maps as `{"@type", "@entries": [{key, value}]}`; `byte[]` as `{"@bytes", "length", "@truncated"?}` capped at 4 KB; capped collections end with `{"@truncated": n}`; strings capped at 64 K chars; Throwables as `{"@type", message, stackTrace[≤10], cause}`. Depth limit counts nesting of objects/collections (default 12, `maxDepth=`), element cap default 500 (`maxElements=`).
- ~~**Recorder** writes one trace file per span on the calling thread~~ Superseded in Phase 3 (span tree, background writer, advice only while recording). Still true: first serialization of a new type costs ~50 ms (descriptor computation).
- `common-dto` is compiled with `--release 8` as well, so the agent's release-8 tests can use it as fixtures; `org.eclipse.persistence:eclipselink:2.7.15` (the server's version) is a test dependency for real `IndirectList` fixtures. EclipseLink 2.7's `ValueHolderInterface` is not generic.

## Phase 3: span tree per trigger

Done when one click on **Create order** produces one trace file whose spans form the tree `OrderService.createOrder → {InventoryService.reserve, PricingService.price → TaxService.computeTax}`.

1. Implement `SpanStack`: a `ThreadLocal<Deque<Span>>`. On enter, create a span with `parentId` = top of stack (or null), push it, record `startNs`. On exit, pop, fill result/exception/duration, append to the current trace's span list. When the stack becomes empty, the trace is complete: hand it to the writer.
2. Assign `trace.id` when a span is created on an empty stack. Store the active trace in the same thread-local.
3. Writer: a bounded `ArrayBlockingQueue<Trace>` drained by one daemon thread that serializes with the `trace-model` mapper and writes atomically (`.tmp` then rename). If the queue is full, drop the trace and count it in a JMX attribute; never block thWebLogic Server downloads pagee request thread.
4. Self-invocation filter: with the annotation hook, a bean calling its own public method produces a nested span with the same bean class and thread. Mark these `"selfInvocation": true` rather than dropping them, so the viewer can hide them while the data stays.
5. Record `thread`, `principal` (via `weblogic.security.Security.getCurrentSubject()` reflectively, falling back to null) and `transaction` status (via `javax.transaction.TransactionSynchronizationRegistry` from JNDI, reflectively, cached per thread).
6. Cross-thread stitching, designed now and implemented last: when WebLogic's `WorkContextMap` is available, put the active trace id into it on root-span entry, and read it on span entry with an empty stack. This carries the id across T3 remote calls and JMS messages between servers. Spans arriving with a foreign trace id are written as separate files tagged with `"parentTraceId"`.

**Acceptance**: the tree above appears in the file; the remote hop to `TaxService` in the other EAR is a child of `PricingService.price` on the same server. Firing 50 clicks in a loop produces 50 files and no dropped traces.

**Design note**: the remote call to `TaxService` on the same server runs on the same thread in WebLogic's optimized local-RMI path, so nesting works without WorkContext. On two managed servers it would not, which is what step 6 is for.

**Status (Oct 23 2026): done except step 6, which is designed in `docs/trace-propagation.md` with the extension point (`TracePropagation`, called from `SpanStack.push`/`pop`) in place.** Acceptance on the real server: one Create order → one file with the tree `createOrder → {reserve, reserve, price → {unitPrice*, unitPrice*, computeTax}, unitPrice, unitPrice}` (`*` = `selfInvocation`; `computeTax` child of `price` from the other EAR; the `unitPrice` calls under `createOrder` are real local-interface calls, correctly not flagged); 50 creates in a loop → 50 files, 9 spans each, `seq` 1–50, `dropped=0`, no `.tmp` leftovers, 0 server errors. Files for the acceptance run are checked in under `capture-agent/src/test/resources/captures/` and verified by `CapturedSpanFixtureTest`.

- **Zero-configuration rule** (now in CLAUDE.md) applied: `hook=` is an undocumented test override, the hook is chosen by `ServerDetection` (`weblogic.home`/`weblogic.Name` property or `weblogic/Server.class` on the system class path → container hook once Phase 6 ships, annotation hook until then and elsewhere). `requireSerializable` is gone, see the Phase 2 status.
- **SpanStack** is a per-thread deque; `OpenSpan` → `Span` on exit; `TraceBuilder` sorts spans by entry `order` before the file is written, so files read top-down. Enter/exit mismatch (recording started or stopped mid-call) resets the thread's stack rather than producing a broken tree. `Recording` ids got a `-N` suffix when two start within one second (found by the tests; a user could do it too).
- **TraceWriter**: `ArrayBlockingQueue<Trace>(1000)` drained by the daemon thread `capture-writer`; `.tmp` + `ATOMIC_MOVE`; a full queue drops and counts (`DroppedTraces`, `WrittenTraces`, `QueuedTraces` on the Control MBean; `capture-status`/`capture-stop` print them). `stop()` waits up to 5 s for the queue to drain so `capture-stop` returns with the files on disk. The per-method ENTER/EXIT log lines are gone; one `TRACE` line per written trace instead.
- **Metadata**: `thread`; `trace.principal` via `weblogic.security.Security.getCurrentSubject()` reflectively (first `WLSUserImpl` principal; `weblogic` on an authenticated T3 call, null for anonymous REST); `ejb.transaction` = JTA status name via `java:comp/TransactionSynchronizationRegistry`, the registry cached per thread (a thread without a component context is marked unavailable, others keep working); `trace.serverName` from `weblogic.Name`. Every lookup degrades to null and warns once.
- **Advice only while recording.** Measured first with permanent advice: 10,000 sequential `createOrder` REST calls, 1,000 warm-up, agent detached vs. attached, recording off, two alternating pairs: detached 1.823 / 1.828 ms mean (p50 1.777 / 1.778), attached 1.844 / 1.843 ms (p50 1.793 / 1.791), i.e. +0.02 ms per request, ~1%, consistent (9 instrumented calls per request ≈ 2 µs each, the `Object[]` from `@Advice.AllArguments` plus two volatile reads). Measurable, so `EjbAnnotationHook` is now `install()`ed by `Recorder.start` (retransforming already-loaded matches, transforming later loads) and `uninstall()`ed by `Recorder.stop` (ByteBuddy `reset`, original bytecode restored). Re-measured attached with recording off: 1.835 / 1.676 ms, indistinguishable from detached. Cost moved to `capture-start`: ~85–100 ms to find and retransform 8 classes among everything loaded in the WebLogic JVM, ~70 ms to restore; the `capture-start` round trip is ~0.6 s, dominated by `jcmd` and the client JVM. Trade-off noted for a busy production server: each start/stop retransforms the bean classes (brief deoptimisation of those methods), instead of a permanent 1%. Invisible to the user either way.
- Phase 1's "recording off logs nothing" now holds trivially: with no recording there is no advice in any class; verified on the server (log and recording directories unchanged, 0 errors) and in `EjbAnnotationHookTest`.

## Phase 4: recordings, session linking, lineage

Done when `capture-start create-and-confirm`, two clicks, `capture-stop` yields one recording directory with two ordered traces, and `trace-reader` reports that the order ID in trace 2 came from the result of `createOrder` in trace 1.

**Recordings (agent)**

1. `RecordingControl.start(name)` creates `$CAPTURE_OUT/<recordingId>/` with a `recording.json` (name, start time, filters) and sets an `AtomicReference<Recording>` the `Recorder` consults on every root-span entry. `stop()` clears it and writes the end time. When no recording is active, the hooks return immediately.
2. Each trace gets a monotonic `seq` from the recording. File name `<seq>-<traceId>.json`.
3. Filters on the recording: `principal` (only this user) and `httpSessionId`, both optional. On a shared server these keep other traffic out; locally they are off.
4. Enabling a recording should also trigger ByteBuddy retransformation of already-loaded bean classes if the agent started with `enabled=false`; verify this with the `Listener`.

**Session linking (trace-reader)**

5. Traces in a recording are ordered by `seq`. Group additionally by `httpSessionId` and `principal` when present; the viewer shows one lane per session.

**Lineage detection (trace-reader)**

6. Walk every trace in order. For each trace *n*, flatten all `result` values of its spans into `(span, jsonPath, value)` triples and add them to an index keyed by a normalized string of the value.
7. For each trace *m > n*, flatten `args` (and HTTP request bodies) the same way and look each value up in the index. A hit becomes a `lineage` entry: `to` (trace m, span, path) ← `from` (trace n, span, path).
8. Only index **distinctive** values: strings of length ≥ 6 that are not dictionary-like, UUIDs, numbers ≥ 1000 or with a decimal part, dates. Skip booleans, small integers, enums, nulls, and values that appear more than *k* times in the recording (configurable, default 5). This keeps noise out.
9. Prefer the nearest earlier source when several match; keep the others as `alternatives` on the entry so a person can override.
10. Values in `args` that have no source are classified: `userInput` if they first appear in the root span's HTTP request, `environment` otherwise (likely read from the database). This classification is what the exporter uses to decide what to parameterize.

**Acceptance**: the recording directory validates against the schema; `trace-reader lineage <dir>` prints `trace 2 / OrderService.confirmOrder / args[0].orderId ← trace 1 / OrderService.createOrder / result.id`, and classifies the customer name typed in the form as `userInput`.

## Phase 5: web entry points as root spans

Done when a click in the browser produces a trace whose root is an `HTTP` span with the request and response bodies, and the EJB tree hangs under it. These roots are what the work framework can replay today over REST or SOAP.

1. `ServletHook`: advise `javax.servlet.http.HttpServlet.service(HttpServletRequest, HttpServletResponse)` by name. On enter, open a root span of kind `HTTP` and capture method, path, query, headers (allow-listed), content type, principal, `httpSessionId`. On exit, capture status.
2. Body capture: bodies are streams, so the hook cannot read them without consuming them. Wrap the request in a `HttpServletRequestWrapper` whose input stream tees into a capped buffer (default 1 MB), and the response in a wrapper that tees the output stream. Because advice cannot replace method arguments in place for an inlined `service`, instrument WebLogic's filter chain entry instead (`weblogic.servlet.internal.FilterChainImpl.doFilter` or the `WebAppServletContext` execute path, to be confirmed with a thread dump) where the wrapped objects can be passed down. Fallback if that proves fragile: register a servlet filter programmatically via `ServletContext.addFilter` from a `ServletContainerInitializer` placed on the bootstrap path.
3. `JaxRsHook`: the Jersey runtime on WebLogic goes through a servlet, so `ServletHook` already covers it; add resource class and method name to the span by advising methods on types annotated `javax.ws.rs.Path` (name-based), as a child span of kind `HTTP` with `"layer": "jaxrs"`.
4. `SoapHook`: JAX-WS endpoints also enter through a servlet; the body captured there is the SOAP envelope. Add a child span by advising methods on types annotated `javax.jws.WebService`. No separate SOAP parsing is needed for the prototype.
5. Add a `GET /orders/api/orders/{id}` and a SOAP endpoint for `TaxService` to the sample apps so both paths are exercised.
6. Mark the root span's request body as the `userInput` source for lineage.

**Acceptance**: **Confirm order** in the browser yields a trace with root `POST /orders/api/orders/42/confirm` (JSON body captured), child `HTTP/jaxrs` or servlet span, then the EJB tree. The REST and SOAP variants produce the same tree with JSON and XML bodies respectively.

**Caution**: never log `Authorization` or `Cookie` header values; keep an allow-list of headers. Body buffers are capped and binary bodies are recorded as length only.

## Phase 6: WebLogic container hook and metadata

Done when every EJB span carries application, module, bean name, invoked interface, local/remote, and JNDI names, and the `container` hook produces the same tree as the `annotation` hook from a single instrumentation point.

**Metadata (works with either hook)**

1. `meta/WlsMetadata`: on first sight of a bean class, resolve its deployment through the runtime MBean tree via the platform `MBeanServer` (`com.bea:Type=EJBComponentRuntime` → `EJBRuntimes` → `EJBName`, `ApplicationRuntime.Name`, `ComponentRuntime.ModuleId`). Match by implementation class name. Cache per bean class.
2. JNDI names: read the bean's `java:global/<app>/<module>/<bean>!<interface>` name (deterministic, build it) plus any descriptor-provided names from the `weblogic-ejb-jar.xml` mappings exposed on the same MBeans. Record both.
3. Deployment path: `AppDeploymentMBean.SourcePath` from the domain config MBeans gives the EAR location, recorded on the trace under `trace.deployments[]` so tooling can find interface classes for client jars.
4. Everything here is reflective against `javax.management` and MBean object names only; the agent never links against `weblogic.jar`.

**Container hook**

5. Find the choke point: start the domain with `-XX:+UnlockDiagnosticVMOptions`, make one local and one remote EJB call with a breakpoint on `OrderServiceBean.createOrder`, and read the stack. Candidates in 14.x are under `weblogic.ejb.container.internal` (e.g. `SessionLocalMethodInvoker`, `SessionRemoteMethodInvoker`, `BaseLocalObject.__WL_preInvoke`/`__WL_postInvoke`, `InvocationWrapper`). Record the exact class and method in `docs/container-hook.md` with the WebLogic version and patch level.
6. Implement `WlsContainerHook` advising that method. From its `InvocationWrapper` (or equivalent) read the `MethodDescriptor` for interface, method, transaction attribute and the bean instance, all reflectively by field/getter name.
7. Make `hook=container` and `hook=annotation` mutually exclusive, selected by agent argument. Keep both maintained: `annotation` is the portable fallback for any other server; `container` is the one to use at work.

**Acceptance**: both hooks produce byte-identical span trees (minus timings) for the two-button scenario, and spans show `jndiNames` that a T3 client can look up successfully in Phase 7.

**Risk**: internal class names can change with a patch set update. The `docs/container-hook.md` record and a startup self-check (the agent logs a warning if the target method is not found after the first EJB class loads) are the mitigation.

## Phase 7: tooling

Done when a person can open a recording, see the sequence of traces and the tree under each, follow the lineage arrows, export a placeholder step file, and prove one captured EJB call replays.

**trace-reader** (library + CLI, `java -jar trace-reader.jar <cmd> <dir>`)

1. `validate`: schema-check every file.
2. `tree`: print the span tree of one trace, indented, with durations.
3. `lineage`: run detection, write `lineage.json` into the recording directory.
4. `index`: emit one `recording-index.json` with traces, sessions and lineage, which the viewer loads.

**trace-viewer** (static HTML, no build step)

5. A single `index.html` loading `recording-index.json` from the same directory (open with `python -m http.server` or `devenv`'s `trace-view` script). Left: list of traces in sequence, grouped by session. Center: collapsible span tree. Right: args/result JSON for the selected span with `@type`, `@ref` and placeholders highlighted. Lineage shown as badges on argument fields that link to their source span.
6. Toggle to hide self-invocations and WebLogic-internal spans.

**trace-export**

7. `Exporter` SPI: `List<Step> export(Recording, Selection)` where `Selection` is a set of span ids. A `Step` has lookup info, method, payload JSON, extracted variables and substitutions derived from lineage.
8. `PlaceholderExporter`: writes YAML steps with `${var}` substitution, purely to validate the model has everything a step needs. The real exporter is written at work later.

**replay-check** (one JUnit 5 test)

9. Load a span file, `InitialContext` with `t3://localhost:7001` using `wlthint3client.jar` from `$ORACLE_HOME/wlserver/server/lib`, look up the first `jndiNames` entry, resolve the method by `signature`, deserialize `args` against the interface's classloader (the sample `*-api` jars on the test classpath), invoke, assert the result deserializes to the same type as `result.declaredType`.
10. No framework, no abstractions; roughly 50 lines. It exists to prove the capture format is sufficient and to serve as the reference for an eventual T3 step type in the work framework.

**Acceptance**: `trace-reader index` + viewer shows the two-button scenario with one lineage badge; `PlaceholderExporter` emits two steps where step 2 uses `${orderId}` extracted from step 1; `replay-check` passes against the running domain.

## Risks and open questions

| Risk | Likelihood | Mitigation |
| --- | --- | --- |
| Serialization of unknown graphs fails or triggers lazy loads | High | Field-based, no getters; lazy-indirection detection by class name; placeholder-and-warn policy; unit tests on awkward types before touching the server |
| Agent classes not visible from application classloaders | High | Append agent jar to the bootstrap search path; shade all dependencies; no `javax`/`weblogic` compile deps |
| WebLogic internal class names differ from the plan | Medium | Annotation hook first; container hook located by thread dump and documented per version; startup self-check |
| JDK 9+ module encapsulation blocks reflection (not on 12.2.1 / JDK 8) | Medium | `--add-opens` set in `wls-start.sh`; serializer catches `InaccessibleObjectException` and placeholders |
| Oracle JDK vs OpenJDK differences | Low | Dev only; switch to Oracle JDK 17 in `devenv.nix` if a WebLogic feature misbehaves |
| Capture overhead on busy servers | Medium at work, low here | Recording off by default; filters by principal/session; bounded queue with drop counter |
| Sensitive data in captures | Medium at work | Header allow-list; no credential capture; captures stay local; a redaction hook on the serializer is a planned extension point |
| Lineage false positives | Medium | Distinctiveness rules; frequency cap; alternatives kept for manual override |
| Scope creep into the real exporter | Medium | Exporter SPI + placeholder only; the work format is explicitly deferred |

**Open questions to settle during Phase 2–4**

- Capture args before, after, or both? Default after; decide once lineage shows what the exporter needs.
- Should `@type` be emitted for every object or only where the declared type is abstract? Everywhere is simpler to rebuild; the exporter can strip.
- Is `httpSessionId` enough to link thick-client (T3) scenarios? Likely not; `principal` + explicit recording window is the fallback.
- How much of `weblogic.*` MBean metadata is reachable from the platform `MBeanServer` without authentication in dev mode? Verify in Phase 6 step 1 before building on it.

## Kickoff for Claude Code

Export this doc as Markdown into the repo as `docs/PLAN.md`, then add the `CLAUDE.md` below at the root so every session starts with the constraints that are easy to violate.

```markdown
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
- Scripts: `wls-install`, `wls-domain`, `wls-start`, `wls-deploy`, `capture-start <name>`, `capture-stop`.
- `.local/` is gitignored: install, domain, H2, captures.

## Build & test
- `mvn -q -pl trace-model,capture-agent test` for fast unit tests (no server).
- Full loop: `wls-start` (background) → `wls-deploy` → `capture-start x` → click/curl → `capture-stop` → `trace-reader index .local/captures/<rec>`.
- Agent log: `.local/captures/agent.log`. Server log: `.local/domains/capture/servers/AdminServer/logs/`.

## Style
- Small classes, explicit names, no frameworks beyond ByteBuddy/Jackson/JUnit in the agent.
- When an internal WebLogic class is referenced, record the version and stack frame in docs/container-hook.md.
```

**First prompt for the Claude Code session**

> Read CLAUDE.md and docs/PLAN.md. Implement Phase 0. Start with devenv.nix, the parent pom.xml and the WebLogic install/domain scripts; do not write any agent code yet. The installer jar is at $WLS\_INSTALLER. Stop after the domain starts and ask me before deploying the sample apps.

**Suggested session boundaries**: one session per phase. Start each with `Read docs/PLAN.md, section "Phase N". Confirm the acceptance check of Phase N-1 still passes, then implement Phase N.` Keep `docs/container-hook.md` and the open-questions list updated as decisions land.
