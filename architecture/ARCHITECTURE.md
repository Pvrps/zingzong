# Architecture

How weblogic-trace is built: three modules, an agent that records, a plugin that installs the agent
and shows what it recorded. WebLogic internals are in [HOOKS.md](HOOKS.md).

## Contents

1. [Modules](#1-modules)
2. [How the agent loads](#2-how-the-agent-loads)
3. [Recording lifecycle](#3-recording-lifecycle)
4. [The serializer and the raw-capture rule](#4-the-serializer-and-the-raw-capture-rule)
5. [The trace model](#5-the-trace-model)
6. [The plugin](#6-the-plugin)
7. [Lineage](#7-lineage)
8. [Deliberately not done](#8-deliberately-not-done)

## 1. Modules

| module | artifact | depends on | compiles with |
| --- | --- | --- | --- |
| `weblogic-trace-model` | `ca.gc.cra.xz02.devops:weblogic-trace-model` | Jackson | `--release 8` |
| `weblogic-trace-agent` | `ca.gc.cra.xz02.devops:weblogic-trace-agent` (shaded, `Premain-Class: ca.gc.cra.xz02.devops.wltrace.agent.Agent`) | weblogic-trace-model, Byte Buddy, Jackson; nothing from `javax.*` or `weblogic.*` | `--release 8` |
| `weblogic-trace-maven-plugin` | `ca.gc.cra.xz02.devops:weblogic-trace-maven-plugin` (shaded) | weblogic-trace-model, Maven plugin API (provided), weblogic-trace-agent as a plain dependency | `--release 8` |

`weblogic-trace-model` is the format: plain classes with public fields, one JSON file per trace, and
`trace.schema.json` as its description. The agent writes it, the plugin reads it. The plugin's
`reader` package (`RecordingDir`, `Indexer`, `Labels`, `LoadedTrace`, `lineage`) loads recordings
and computes what the page shows.

Every module targets Java 8 because the plugin runs inside the developer's Maven JVM, which may
be a JDK 8, and the agent runs inside a 12.2.1.4 server on JDK 8.

## 2. How the agent loads

`-javaagent:<domain>/weblogic-trace/weblogic-trace-agent.jar=out=<dir>` loads `ca.gc.cra.xz02.devops.wltrace.agent.Agent` in
the system class loader. `Agent` parses its one argument, appends its own jar to the bootstrap
class loader search path and hands over to `AgentBootstrap`; from then on every agent class
resolves through the bootstrap loader, which is what lets advice inlined into server classes,
loaded by WebLogic's own loaders, reach the same `Recorder`.

Consequences:

- Byte Buddy and Jackson are shaded under `ca.gc.cra.xz02.devops.wltrace.shaded` so the server's and the applications'
  copies are never confused with the agent's.
- Bootstrap-loaded code links only against boot-layer modules (`java.base`, `java.logging`,
  `java.naming`, `java.management`, `java.instrument`). `java.sql`, `javax.servlet`, `javax.jms`,
  `javax.ejb` and every `weblogic.*` type are recognised by name and reached by reflection;
  `BootstrapVisibilityTest` scans the compiled classes for forbidden constant-pool references.
- Serializing JDK value types as fields needs `--add-opens` on JDK 9+; `wltrace:enable` writes
  the five flags next to the `-javaagent` option.

`AgentBootstrap` decides whether this JVM is a WebLogic server: the `weblogic.home` system property
or the `weblogic.Server` class, plus the five container classes of HOOKS.md sections 1 to 3 on the
system class path. Anywhere else it logs one line, registers the control MBean with state
`inert: <reason>` and installs nothing; `start` on that MBean fails with the same reason.

## 3. Recording lifecycle

```
plugin ── JMX (local connector) ──> ca.gc.cra.xz02.devops.wltrace:type=Control (RecordingControl)
                                      │ start(id, name)        stop()
                                      ▼                        ▼
                                   Recorder.start           Recorder.stop
                                      │ CaptureHook.install    │ flush writer, complete manifest
                                      │ manifest (start)       │ CaptureHook.uninstall
                                      ▼                        ▼
         advice ──> EjbRecorder / MessageRecorder / ResourceRecorder / JdbcRecorder / ServletRecorder
                                      │ Spans.push … Spans.complete
                                      ▼
                            SpanStack (per thread) ──root popped──> TraceWriter queue ──> <out>/<rec>/NNNNNN-<trace>.json
```

- **Idle agent.** With no recording active `CaptureHook` has no transformer installed; the server
  runs unmodified bytecode. `start` installs the Byte Buddy transformer with retransformation of
  already-loaded targets; `stop` resets it, restoring the original bytecode.
- **Recording id.** The plugin assigns one id (`rec-yyyyMMdd-HHmmss`) and starts it on every server
  JVM of the domain, so one capture is one directory however many servers took part.
- **Span stack.** Each thread has a stack of open spans. A push on an empty stack starts a trace; the
  WorkContext (HOOKS.md section 6) may carry in the trace and span that caused this thread's work,
  recorded as `parentTraceId` / `parentSpanId`. Exit advice serializes arguments, result or exception
  on the calling thread, pops, and when the stack is empty hands the finished trace to the writer.
  A span the exit cannot match (the recording started or stopped mid-call) resets the thread's stack.
- **Trace writer.** A bounded queue (1000) drained by one daemon thread, writing each trace to a
  `.tmp` file and renaming it. A full queue drops the trace and counts it; the request thread never
  blocks or throws.
- **Manifests.** Each server writes `recording-<serverName>.json` at start and completes it at stop
  with end time, trace count and dropped count. The plugin merges the servers' manifests into one.
- **Agent log.** `<out>/agent.log`, never stdout: startup, every instrumented class, metadata
  resolution, one `TRACE` line per written file, and every degraded lookup, warned once.

Entry points and what each records:

| advice | target (HOOKS.md) | span |
| --- | --- | --- |
| `ContainerAdvice` → `EjbRecorder` | session invokers | `EJB`: bean, method, interface, LOCAL/REMOTE, transaction attribute, JNDI names, bean type, args, result |
| `MessageAdvice` → `MessageRecorder` | `MDListener.onMessage` | `EJB` with invocation `MESSAGE`, destination, the message as its API view |
| `ServletAdvice` → `ServletRecorder` | `FilterChainImpl.doFilter`, `ServletStubImpl.execute` | `HTTP` root: method, URI, every header, status, both bodies in full through tee wrappers |
| `ResourceAdvice` → `ResourceRecorder` | `@Path` / `@WebService` classes in application loaders | `HTTP` child with layer `jaxrs` / `jaxws`: resource method, args, result |
| `JdbcAdvice` → `JdbcRecorder` | the JDBC wrappers | `SQL`: data source, statement, bound parameters or batches, update count or rows read, exception |

## 4. The serializer and the raw-capture rule

The agent records what crossed the boundary, as it was. `JacksonGraphSerializer` walks every
instance field of every object (transient fields included, static fields excluded), never calls
a getter, never loads a lazy value, and uses Jackson only for the tree model and the output.
Per-type knowledge (`TypeInfo`) is cached per `Class` object in a `ClassValue`, so two loaders
with the same class name never share an entry and a redeployed loader is never pinned.

No masking, no redaction, no allow-lists or deny-lists. The only transformations are structural
necessities, and every one is marked in the output:

| marker | where | means |
| --- | --- | --- |
| `"@type": "fqcn"` | every object | the runtime class |
| `"@id": n` / `{"@ref": n}` | an object referenced more than once / each later reference | a cycle or a shared object; written in full once |
| `"@serializable": false` | an object whose class is not `java.io.Serializable` | walked all the same; it could not cross a remote boundary |
| `"@skipped": [{"field", "reason"}]` | an object with fields not written | compiler-injected (`this$0`, synthetic), weaver-injected (`_persistence_*`, `$$_hibernate_*`), JVM-internal (`Throwable.backtrace`, `depth`), or inaccessible without `--add-opens` |
| `{"@lazy": true, "loaded": false}` | an uninstantiated EclipseLink `IndirectContainer` / `ValueHolder` or Hibernate collection / proxy | loading it would change the call |
| `{"@handle": "fqcn", "kind", "identity", "toString"}` | threads, class loaders, classes, processes, sockets, streams, channels, JDBC connections and statements, locks, executors, futures, timers (`InfrastructureTypes`) | describes the JVM or the OS, not the payload |
| `{"@type": ..., "@entries": [{"key", "value"}]}` | a map whose keys are not strings, enums, numbers or booleans | key/value pairs instead of a JSON object |
| `{"@type": "java.util.Optional", "present", "value"}` | an `Optional` | |
| `{"@jms": "TextMessage", "headers", "properties", "text" ...}` | a `javax.jms.Message` | its API view: headers, properties, body; a bytes or stream body is not read |
| `{"@bytes": base64, "length"}` | a `byte[]` | |
| `{"@truncated": n, "what": "elements" / "entries" / "chars" / "bytes"}` | a collection, map, string (`@text`) or byte array over a ceiling | how much was dropped |
| `{"@unserializable": "fqcn", "reason", "identity", "toString"}` | a value whose walk failed, or a subtree past the depth ceiling | plus a `serializationWarnings` entry on the span |

The ceilings (`SerializerConfig`) are set to be unreachable by real payloads: depth 64, 100 000
elements per container, 16 Mi characters per string, 64 MiB per byte array, 64 MiB per HTTP body.
Every warning the walk produces is listed in the span's `serializationWarnings` with the JSON path
of the value.

Scalars are written losslessly: numbers and booleans as JSON, enums by name, `BigDecimal` and
`BigInteger` as plain strings (scale preserved), `java.time` by its ISO text, `java.util.Date` as
an ISO instant, `java.sql.Timestamp` with nanoseconds, `Calendar` as a zoned date-time, `UUID`,
`URI`, `URL`, `File`, `Path`, `Locale`, `Currency`, `StackTraceElement` by their text.

## 5. The trace model

One file per trace; spans flat, linked by `parentId`, ordered by `order`. An abbreviated Create
order trace from the lab (`weblogic-trace-agent/src/test/resources/captures/create-order-trace.json`):

```jsonc
{
  "formatVersion": 1,
  "recording": { "id": "rec-20261024-224813", "name": "step2-wls14", "seq": 2 },   // seq: this server's position in the recording
  "trace": {
    "id": "t-6d95b4b8",
    "startedAt": "2026-10-25T02:48:14.145Z", "durationMs": 301,
    "httpSessionId": "nTpw9Wog...",                 // from the request; principal is absent for an anonymous call
    "serverName": "AdminServer",
    // parentTraceId / parentSpanId appear on a trace another thread's work caused (@Asynchronous, JMS)
    "deployments": [ { "name": "tax-ear", "sourcePath": "/.../tax-ear.ear", "moduleType": "ear" }, ... ]
  },
  "spans": [
    { "id": "s1", "parentId": null, "depth": 0, "order": 0, "kind": "HTTP",       // the servlet root
      "thread": "[ACTIVE] ExecuteThread: '8' ...", "startNs": 0, "durationNs": 301628149,
      "http": { "method": "POST", "path": "/orders/api/orders", "status": 201,
                "requestHeaders": { "Content-Type": ["application/json"], "Cookie": ["JSESSIONID=..."], ... },
                "requestBody": "{\"customer\":{\"name\":\"Ada Lovelace\",...", "requestBodyEncoding": "UTF-8 (assumed)", "requestBodyLength": 420,
                "responseHeaders": { ... }, "responseBody": "{\"id\":6,\"status\":\"CREATED\",...", "responseBodyEncoding": "UTF-8", "responseBodyLength": 229 } },
    { "id": "s2", "parentId": "s1", "depth": 1, "order": 1, "kind": "HTTP", "layer": "jaxrs",   // the resource method
      "http": { "resourceClass": "com.acme.orders.web.OrdersResource", "resourceMethod": "create",
                "resourceSignature": "javax.ws.rs.core.Response create(java.lang.String)" },
      "args": [ { "declaredType": "java.lang.String", "value": "{\"customer\":..." } ],
      "result": { "declaredType": "javax.ws.rs.core.Response", "value": { "@type": "org.glassfish.jersey.message.internal.OutboundJaxrsResponse", "status": 201, "context": { ... "@handle" ... } } },
      "serializationWarnings": [ { "path": "$.result.context...", "reason": "runtime handle (stream), recorded as reference", "typeName": "..." } ] },
    { "id": "s3", "parentId": "s2", "depth": 2, "order": 2, "kind": "EJB", "app": "orders-ear", "module": "orders-ejb",
      "ejb": { "app": "orders-ear", "module": "orders-ejb", "beanName": "OrderService", "beanClass": "com.acme.orders.OrderServiceBean",
               "interface": "com.acme.orders.api.OrderServiceLocal", "invocation": "LOCAL", "beanType": "STATELESS",
               "jndiNames": [ "java:global/orders-ear/orders-ejb/OrderService!com.acme.orders.api.OrderServiceLocal", ..., "orders/OrderService" ],
               "method": "createOrder", "signature": "com.acme.common.model.Order createOrder(com.acme.common.dto.CreateOrderRequest)",
               "transactionAttribute": "REQUIRED" },
      "args": [ { "declaredType": "com.acme.common.dto.CreateOrderRequest",
                  "value": { "@type": "com.acme.common.dto.CreateOrderRequest", "customer": { "@type": "...Customer", "name": "Ada Lovelace", ... },
                             "paymentMethod": { "@type": "com.acme.common.dto.CardPayment", "lastFour": "4242", "expiry": "2028-06" }, ... } } ],
      "result": { "declaredType": "com.acme.common.model.Order",
                  "value": { "@type": "com.acme.common.model.Order", "id": 6, "status": "CREATED", "total": "149.73",
                             "lines": { "@lazy": true, "loaded": false },                       // not loaded when the call returned
                             "audit": { "@type": "com.acme.common.model.AuditTrail", "@serializable": false, ... },
                             "@skipped": [ { "field": "_persistence_fetchGroup", "reason": "weaver-injected (EclipseLink)" }, ... ] } } },
    { "id": "s11", "parentId": "s3", "depth": 3, "order": 10, "kind": "SQL",
      "sql": { "dataSource": "OrdersDS", "jndiNames": ["jdbc/OrdersDS"], "url": "jdbc:h2:file:...", "database": "H2 2.3.232 (2024-08-11)",
               "statementType": "prepared", "operation": "executeUpdate",
               "sql": "INSERT INTO ORDERS (CREATED_AT, CURRENCY, ...) VALUES (?, ?, ...)",
               "parameters": [ "2026-10-25T02:48:14.255689086Z", "EUR", "ada@example.com", ... ],   // by position
               "updateCount": 1 } },                                                             // or "resultSet": true, "rows": n
    ...                                                                                           // an exception: "exception": { "className", "message", "value": { "@type", ..., "stackTrace": [...] } }
  ]
}
```

`trace.schema.json` in `weblogic-trace-model` describes every field.

## 6. The plugin

Three goals, no project needed, three parameters: `wltrace.domain` (required), `wltrace.port`
(default 8077), `wltrace.out` (default `~/.weblogic-trace/<domain directory name>`). Every path is a
`java.nio.file.Path`.

- **Platform detection** (`Domain`). `bin/setDomainEnv.sh` means a Unix domain, `bin/setDomainEnv.cmd`
  a Windows domain; with both, the platform of the JVM running the plugin. The platform selects
  `bin/setUserOverrides.sh` or `bin\setUserOverrides.cmd` and the syntax of the block. The domain's
  JDK is the last literal assignment of `JAVA_HOME`, `SUN_JAVA_HOME` or `BEA_JAVA_HOME` in that
  script (`VAR="..."` or `set VAR=...`), its major version read from `<jdk>/release` or inferred from
  `rt.jar`; without a readable JDK, a running server's `java.specification.version` decides whether
  the `--add-opens` flags are written.
- **The overrides block** (`OverridesBlock`). `enable` copies the agent jar to
  `<domain>/weblogic-trace/weblogic-trace-agent.jar` (replacing by rename, so a running server's mapped jar is
  untouched) and writes a block delimited by `# >>> weblogic-trace >>>` / `# <<< weblogic-trace <<<` in
  the `.sh` or `rem >>> weblogic-trace >>>` / `rem <<< weblogic-trace <<<` in the `.cmd`. The shell form
  appends to `JAVA_OPTIONS` with `JAVA_OPTIONS="${JAVA_OPTIONS} ..."` lines and `export JAVA_OPTIONS`;
  the cmd form with `set JAVA_OPTIONS=%JAVA_OPTIONS% ...` lines, CRLF, an option quoted when it
  contains a space or a cmd metacharacter. A file the plugin created carries a line saying so and is
  deleted by `disable`; an existing file keeps every other byte, line endings and a missing trailing
  newline included. A second `enable` replaces the block in place.
- **State detection** (`StateMonitor`, `DomainState`). Enabled on disk means: block present, agent
  jar present, and its `build.properties` stamp equal to the plugin's. Server JVMs are found through
  the attach API (`com.sun.tools.attach`, from `tools.jar` on JDK 8): a JVM whose main class is
  `weblogic.Server`, whose `weblogic.RootDirectory` (else `user.dir`) is the domain home after
  normalisation, case-insensitively on Windows, and which carries `weblogic.Name`. Each is attached
  once, its local JMX connector started, and the agent's MBean looked up; the result is one of five
  states: *not enabled*, *enabled, server not running*, *enabled, server running without the agent,
  restart it*, *disabled on disk, agent still loaded until restart*, *ready*.
- **Web server** (`WebServer`), `127.0.0.1:<port>` on `com.sun.net.httpserver`:

  | | |
  | --- | --- |
  | `GET /`, `/index.html`, `/style.css`, `/app.js`, `/diagram.js` | the page, four static files |
  | `GET /api/state` | the domain state, re-evaluated per call |
  | `POST /api/capture/start` `{"name"}` / `POST /api/capture/stop` | start on every server JVM under one id / stop |
  | `GET /api/recordings` | every recording under `wltrace.out`, newest first |
  | `GET /api/recordings/{id}` | the index: merged manifest, trace summaries, lineage |
  | `GET /api/recordings/{id}/traces/{file}` | one trace file |
  | `DELETE /api/recordings/{id}` | remove a finished recording (409 while active) |

- **Index caching** (`Recordings`). The index of a recording is computed in-process and cached by a
  stamp of the directory (newest modification time and file count); a trace landing or a manifest
  completing changes the stamp, which is how the page refreshes a live recording once a second.
- **The page**. `app.js` holds the state bar, the home list, polling, routing and the details
  window; `diagram.js` builds and draws the sequence diagram: one lane per component (REST and SOAP
  resources, beans by type, JMS queues, data sources) grouped under its application, one heading per
  entry point, a call row and a return row per span, work that continued on another thread drawn
  under the call that caused it, runs of four or more identical leaf calls folded into one row until
  expanded. Nothing is hidden; markers are highlighted.

## 7. Lineage

`LineageDetector` finds values in later inputs that came from earlier outputs; it annotates, never
removes. Traces are walked in order. Every distinctive scalar in a span's `result` and in a root
HTTP span's response body goes into a source index under its normalised form; every distinctive
scalar in a later trace's `args`, request body, URL path segments and query parameters is looked
up there. A hit is an entry `to ← from`, nearest earlier source first, the others as alternatives.

Rules:

- **Distinctive** (`Distinctive`): strings of six characters or more that are not a single word of
  letters; UUIDs; ISO dates, times and instants; numbers of 1000 or more or with a decimal part;
  smaller integers only where the path names an identifier (`id`, `orderId`, `...Key`, `...Number`,
  `...Ref`, `...Uuid`). Booleans, nulls, enum-like words and small quantities are never indexed.
  Markers (`@type`, `@id`, `@ref`, ...) are skipped.
- **Bodies** (`ValueFlattener`, `XmlBodies`): a JSON body is walked element by element; an XML body
  (a SOAP envelope) is turned into a JSON-shaped tree by local names; the whole text is a value too.
- **Frequency cap**: a value produced as a result by more than five distinct traces is too common to
  be a source (an echoed constant, a status) and is not indexed.
- **Origin**: a value first seen in a root span's input is user-originated and is never linked when
  it appears again; only system-originated values (ids, computed amounts, rows read back) are looked
  up.
- **Classification** of inputs without a source: `environment` when the value is an instant inside
  the trace's own time window (2 s slack) or appears only in nested spans; `userInput` when it
  appears in the root span's input. An entry whose value is also produced by the same trace's
  results is flagged `alsoProducedHere`.

The page shows an entry as a badge next to the value: `← #n span / path` with links, or the
classification.

## 8. Deliberately not done

- **Cross-server propagation.** A call that leaves the server (T3 to another server, JMS consumed
  elsewhere) starts an unrelated trace there. The WorkContext mode includes `RMI`, but no reader
  joins traces across servers and nothing is verified.
- **In-flight spans.** A trace is written when its root call completes; a request still running is
  not on the page.
- **Remote domains.** The plugin finds server JVMs through the attach API: same machine, same OS user.
- **Replay or deserialization.** The agent writes; nothing reconstructs objects from a recording.
- **Configuration.** No per-application, per-type or per-field settings exist; the only knobs are the
  three plugin parameters and the agent's output directory.
