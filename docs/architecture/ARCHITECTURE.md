# Architecture

How weblogic-trace is built, for an engineer joining the project with no background in Java agents or WebLogic internals; read once, it makes the source tree navigable. Terms are defined in the glossary and used without re-explanation everywhere else.

## Contents

1. [Glossary](#1-glossary)
2. [Constraints that shape the code](#2-constraints-that-shape-the-code)
3. [Source tree](#3-source-tree)
4. [How the agent gets into the JVM](#4-how-the-agent-gets-into-the-jvm)
5. [Hooks](#5-hooks)
6. [From call to span](#6-from-call-to-span)
7. [Serialization](#7-serialization)
8. [The plugin](#8-the-plugin)
9. [The viewer](#9-the-viewer)
10. [Dependencies](#10-dependencies)
11. [Adding a hook](#11-adding-a-hook)

## 1. Glossary

**JVM class loader.** The object that turns a class name into a `Class`. Loaders form a tree: a loader asks its parent first and defines a class itself only when the parent cannot. Two loaders can define two different classes with the same name; such classes are unrelated to the JVM, and a cast between them fails.

**Bootstrap class loader.** The root of the tree, built into the JVM, which defines `java.lang.Object` and the rest of the core library. Every other loader can see its classes; it can see no one else's. Code it defines reports `null` as its class loader.

**Application class loader.** A loader WebLogic creates for a deployed application: one for an EAR and its EJB modules, one per web module. WebLogic creates a new one on every redeploy and may load the same application class into more than one of them.

**Java agent.** A jar the JVM loads at start, through the `-javaagent:<jar>=<arguments>` option, before the application's `main` method. The JVM calls the jar's `Premain-Class` with the arguments and an `Instrumentation` object, which is the only way to change bytecode in a running JVM.

**Instrumentation.** The JDK API an agent receives: it registers transformers that see every class as it is loaded, can retransform a class already loaded, and can append a jar to the bootstrap class loader's search path.

**Bytecode transformation.** Rewriting the bytes of a class as the JVM loads it, so the methods run different code from what the compiler produced. The source is untouched; only the running JVM sees the change, and a restart undoes it.

**ByteBuddy.** The library the agent uses to describe transformations: which classes and methods to change, and what code to put into them. It also generates classes at runtime, which the agent uses for the HTTP wrappers.

**Target.** A class and a method the agent changes, named by a matcher: either by exact class name or by annotation plus the class loader that defined the class.

**Advice.** A small class with two static methods, one run on entry to the target method and one on exit, that ByteBuddy copies into the target. Advice sees the target's arguments, return value and thrown exception.

**Inlining.** How advice is applied: its bytecode is pasted into the target method rather than called from it. Anything the advice references must therefore be visible from the target's class loader, and anything it allocates, such as a lambda, is allocated in the target's class.

**Hook.** One of the five places where the agent changes WebLogic: a target, the advice inlined there, and the recorder that advice reports to. Hooks are installed when a recording starts and removed when it stops.

**Span.** One recorded call: a request entering the server, a resource method, an EJB method, a message delivery or an SQL statement, with what crossed its boundary and how long it took. A span has a parent span unless it is the first call on its thread.

**Trace.** The spans one thread produced for one entry point, from the first call to its return, written as one JSON file. Spans in a trace are flat, linked by parent id.

**Entry point.** The call that starts a trace: a request arriving, a message delivered, or any first call on a thread while recording.

**Recording.** A start/stop window on a domain, identified by an id the plugin assigns (`rec-yyyyMMdd-HHmmss`) and shared by every server of the domain. One recording is one directory of trace files and one manifest per server.

**Marker.** A JSON key beginning with `@` that the agent writes where a value is not written as it crossed the boundary, saying what stands in its place: `@ref` for an object already written, `@lazy` for a collection not loaded, `@handle` for an OS or JVM resource, `@truncated` where a ceiling is reached, `@skipped` for fields not written, `@serializable` for an object that could not cross a remote boundary, `@text` for a string over the ceiling.

**Lineage.** The links the viewer computes between a value in one trace's input and the same value in an earlier trace's output: where an order id in a confirm call came from, for instance. Lineage annotates recordings; it changes nothing in them.

**Data source.** WebLogic's pool of JDBC connections, configured in the domain, named, and bound in JNDI. Every statement an application runs through a data source passes through WebLogic's wrapper around the driver's statement.

**WebLogic domain.** A directory holding the configuration of a set of servers (`config/config.xml`), their start scripts (`bin/`), and each server's files. The plugin works on one domain at a time.

**Server.** One WebLogic JVM of a domain, with a name (`AdminServer`, `ms1`). The plugin finds the servers of a domain among the JVMs running on the same machine.

**Invoker.** WebLogic's one static method through which every business-interface call into a session bean passes: `SessionLocalMethodInvoker.invoke` for local views, `SessionRemoteMethodInvoker.invoke` for remote views. The agent's EJB hook sits there.

**`BeanInfo`.** WebLogic's description of one deployed bean: class, EJB name, interfaces, JNDI names, bean type, transaction settings. The container carries it in its objects; the agent reads it by reflection.

**WorkContext.** WebLogic's mechanism for carrying key/value entries along with work: from a thread to the thread an `@Asynchronous` call runs on, and inside a JMS message to the thread that delivers it. The agent stores the current trace and span in it so a trace started on another thread names its cause.

**Overrides file.** `bin/setUserOverrides.sh` or `bin\setUserOverrides.cmd` in a domain: a script the server start scripts run if it exists, meant for options the operator adds. The plugin writes the `-javaagent` option there, in a delimited block.

**Node Manager.** WebLogic's process for starting servers remotely. With `StartScriptEnabled=false` in `nodemanager/nodemanager.properties` it starts servers without the start scripts, so the overrides file is not read.

**MBean.** A Java object registered under a name with an MBeanServer, readable and invocable over JMX, locally or remotely. The agent registers one for start, stop and status; WebLogic registers thousands describing the running server, which the agent reads.

## 2. Constraints that shape the code

Every constraint names where it is enforced. The rest of this document refers to them by number.

### (1) No WebLogic classes at compile time

The agent compiles against no WebLogic jar. Every WebLogic type is reached by name (`Class.forName`, string comparison of class names) and by reflection (`Reflection.call`, `Reflection.field`), and that code is confined to the `weblogic` package and the hook definitions in `instrument`.

The agent sits on the bootstrap class loader (section 4), which cannot see WebLogic's classes; a direct reference would fail at link time in the server even though it compiled. Reaching every type by name also means one jar runs unchanged on 12.2.1.4 and 14.1.2, and the build needs nothing from Oracle. The cost is that a renamed member fails at runtime, not at compile time: every reflective read degrades to null and warns once, and the stubs under `weblogic-trace-agent/src/test/java/weblogic/` give the matching and reading code something named like the real classes to run against in unit tests.

### (2) Boot-layer linking only

Agent classes link only against modules the bootstrap loader defines: `java.base`, `java.logging`, `java.naming`, `java.management`, `java.instrument`. `java.sql` and every other module the platform loader defines (`javax.sql`, `java.xml.crypto`, ...) is recognised by class name and reached reflectively; `Scalars` names `java.sql.Timestamp` as a string and `JdbcWrappers.isResultSet` compares interface names.

On JDK 9 and later a class loaded by the bootstrap loader cannot resolve a class from a platform-loader module; the reference compiles and fails in the server. `BootstrapVisibilityTest` reads the constant pool of every compiled agent class and fails on a reference into those packages.

### (3) Never throw into a business call

Every entry from advice into the agent is guarded: `SpanRecorder.enter` and `SpanRecorder.exit` catch `Throwable`, the advice itself has `suppress = Throwable.class`, and every reflective read degrades to null. A failure records nothing, or records a marker, and logs a warning once per cause (`AgentLog.warnOnce`).

The agent sits inside production requests; an exception from it would fail the user's call. The cost is that a broken lookup is silent to the application and visible only in `agent.log` and as null fields in the recording. Enforced in `SpanRecorder`, in the `*Advice` annotations, in `Reflection`, and in every class of `weblogic`.

### (4) Class-loader hygiene

Every per-type cache keys on the `Class` object (`ClassValue` in `TypeInfoCache`, `MethodResolver`, `BeanMetadataResolver`, `TeeClassCache`, `ResourceLayers`) or on name plus loader, never on the class name alone, and holds nothing strongly outside the class's own reachability. Classes are resolved through the loader of the object at hand (`SessionInvocation.beanClassOf` uses the invokable's loader; `MessageDelivery.read` uses the `BeanInfo`'s loader), never through the agent's.

WebLogic loads application classes in more than one loader and again on every redeploy. A cache keyed by name would hand one loader's fields to another's instance; a strong reference would pin a redeployed application in memory. `ClassLoaderIsolationTest` defines the same class name in two loaders and proves they serialize with their own fields.

### (5) Advice is inlined

An `*Advice` class contains two static methods, no fields, no lambdas, no method references, and exactly one call per method: into `TraceRuntime`.

ByteBuddy copies the advice bytecode into the target method; a lambda would be compiled into the target class, which may be a WebLogic class in a loader that cannot see the agent's lambda metafactory bootstrap. One call keeps the inlined code small and the real work in ordinary, testable objects. Enforced by inspection: `git grep` for `->` and `::` in `*Advice.java` is empty.

### (6) Raw capture

What crossed the boundary is recorded unchanged: every argument, result, exception, header, body, statement and parameter; every instance field of every object, transient fields included; no masking, no allow-lists, no deny-lists. The only transformations are the markers of section 7, and every ceiling that fires says so with `@truncated` and a count.

Recordings exist to show exactly what the system did; a filtered recording would hide the one field that explains a failure. The cost is that recordings are sensitive and the output directory must be treated as such. Enforced by `RawCaptureAuditTest`, which walks a fixture graph and the JSON side by side and fails on any field absent without a `@skipped` entry, and by `CapturedSpanFixtureTest` on recordings from a real server.

### (7) Java 8 bytecode

Every module compiles with `source`/`target` 1.8 and every build plugin runs on a Java 8 JDK; the build passes under JDK 8 and JDK 21 alike.

One agent jar runs inside a 12.2.1.4 server on JDK 8 and a 14.1.2 server on JDK 21; one plugin runs inside whatever JDK the user's Maven uses. The cost is Java 8 idioms only. `BootstrapVisibilityTest` fails on a class file above major version 52.

## 3. Source tree

Four Maven modules under the root package `ca.gc.cra.xz02.devops.wltrace`; arrows are compile dependencies.

```
weblogic-trace                          parent: versions, plugins, Java 8 toolchain
├── weblogic-trace-model                the trace format; Jackson only
│   └── model                           Trace, Span, EjbInfo, HttpInfo, SqlInfo, RecordingManifest, RecordingIndex, Lineage*, TraceJson
├── weblogic-trace-agent  ──> model     the -javaagent; ByteBuddy and Jackson shaded under ...wltrace.shaded
│   ├── bootstrap                       AgentMain (premain), AgentStartup (wiring), InertnessCheck, AgentLog, AgentVersion
│   ├── instrument   ──> record         Hook and its five subclasses, AdviceBinding, TypeMatcher, the *Advice classes,
│   │                                   HookInstaller, InstrumentationListener, ApplicationClassLoaders
│   ├── record       ──> weblogic,      TraceRuntime, TraceAgent, SpanRecorder and its five subclasses, *Facts, SpanStack,
│   │                    http,          OpenSpan, OpenTrace, TraceWriter, ManifestWriter, RecordingLifecycle, RecordingControl,
│   │                    serialize      MethodResolver, MethodSignature, ResourceLayers
│   ├── weblogic                        SessionInvocation, MessageDelivery, BeanMetadata, BeanMetadataResolver, BeanInfoReader,
│   │                                   RuntimeMBeans, DataSourceCatalog, JdbcWrappers, WorkContextPropagation, ServerRuntime, Reflection
│   ├── http                            HttpCapture, BodyBuffer, TeeClasses, TeeClassCache, TeeSupport, ServletApi, HttpInfoReader
│   └── serialize                       GraphSerializer, JacksonGraphSerializer, TypeInfo, TypeInfoCache, LazyState, Scalars,
│                                       InfrastructureTypes, JmsMessageView, Marker, SerializationContext, SerializationCeilings
├── weblogic-trace-viewer ──> model     the recording reader and the page
│   ├── recording                       RecordingDirectory, LoadedTrace, RecordingIndexer, RecordingCatalog, SpanNames
│   │   └── lineage                     LineageDetector, ValueFlattener, ValueLocation, DistinctiveValues, XmlBodies
│   └── web                             ViewerServer, DomainControl, ControlState, JsonHandler, PageHandler, StateHandler,
│                                       ControlHandler, RecordingsHandler; index.html, style.css, app.js, diagram.js
└── weblogic-trace-maven-plugin ──> viewer, agent (as a file)
    ├── mojo                            AbstractTraceMojo, EnableMojo, DisableMojo, StartMojo
    ├── domain                          Domain, DomainPlatform, UnixPlatform, WindowsPlatform, OverridesBlock, DomainJdk, NodeManagerSettings
    └── jvm                             LocalJvms, AgentControl, DomainState, DomainStateMonitor, MonitoredDomainControl
```

Inside the agent, `bootstrap` depends on everything because it wires everything; `instrument` depends on `record` for the recorders its hooks name and for `TraceRuntime`; `record` depends on `weblogic`, `http` and `serialize`; those three depend only on `bootstrap.AgentLog` and the model. The plugin depends on the agent jar as a Maven artifact it copies into the domain, not on its classes. The plugin jar shades the viewer, the model and Jackson, relocated under `ca.gc.cra.xz02.devops.wltrace.shaded`, so Maven's own Jackson is never confused with it.

## 4. How the agent gets into the JVM

`wltrace:enable` writes the overrides block (section 8) into the domain's overrides file. When a server starts, its start script runs that file, which appends to `JAVA_OPTIONS`:

```
-javaagent:<domain>/weblogic-trace/weblogic-trace-agent.jar=out=<output directory>
--add-opens=java.base/java.lang=ALL-UNNAMED      (and four more, on a JDK 9 or later domain)
```

The JVM loads the jar in the system class loader and calls `AgentMain.premain`. `AgentMain` parses its one argument, `out`, appends its own jar to the bootstrap class loader search path through `Instrumentation`, and only then calls `AgentStartup`. From that point every agent class resolves through the bootstrap loader, which is what lets advice inlined into WebLogic's classes, loaded by WebLogic's own loaders, reach the same `TraceRuntime` as the rest of the agent. Nothing but `AgentMain` itself is referenced before the append, so no agent class is defined in the system loader by mistake.

`AgentStartup.assemble` builds every object: the log, the serializer with its ceilings, the trace writer, the WebLogic readers, the five recorders, the five hooks, the installer, the lifecycle and the control MBean. Every dependency is passed through a constructor and held in a final field. `AgentStartup.start` publishes the result through `TraceRuntime.install` and registers the MBean.

`InertnessCheck` decides whether this JVM is a WebLogic server the agent can work in: the `weblogic.home` system property or the `weblogic.Server` class must be present, and every hook that is not optional must find its target classes on the system class path. Anywhere else the same objects exist, but `RecordingLifecycle.start` refuses with the reason, and the MBean reports `inert: <reason>`. The JDBC hook is optional: a server without `weblogic.jdbc.wrapper.*` records everything but SQL.

At startup nothing is instrumented. The agent registers no class file transformer until a recording starts; a server with the agent loaded and no recording runs its original bytecode.

## 5. Hooks

A `Hook` is a name, the recorder its advice reports to, the list of target class names it needs, and one or more `AdviceBinding`s. A binding is a `TypeMatcher` (which classes), a ByteBuddy method matcher (which methods) and the advice class to inline. `TypeMatcher.named` matches exact class names in any loader; `ResourceHook` builds a matcher that also asks `ApplicationClassLoaders.isApplication` about the defining loader, so that only classes of deployed applications are advised.

| hook | targets | advice | recorder |
| --- | --- | --- | --- |
| `SessionInvokerHook` | `SessionLocalMethodInvoker.invoke`, `SessionRemoteMethodInvoker.invoke` | `SessionInvokerAdvice` | `EjbRecorder` |
| `MessageListenerHook` | `MDListener.onMessage` | `MessageListenerAdvice` | `MessageRecorder` |
| `ServletPipelineHook` | `FilterChainImpl.doFilter`, `ServletStubImpl.execute` | `ServletPipelineAdvice` | `ServletRecorder` |
| `ResourceHook` | public instance methods of `@Path` and `@WebService` classes in application loaders | `ResourceAdvice` | `ResourceRecorder` |
| `JdbcStatementHook` | `execute*`, `set*`, `addBatch`, `clearParameters` of the statement wrappers; `postInvocationHandler` of the result-set wrapper | `JdbcStatementAdvice.Execute`, `.Bind`, `.RowRead` | `JdbcRecorder` |

What each target is inside WebLogic, how it is located and what is read from it is in [HOOKS.md](HOOKS.md).

`HookInstaller` turns the list into one ByteBuddy `AgentBuilder`: an ignore matcher for the JVM, the agent, WebLogic's own packages and the frameworks it embeds (with the hooks' named targets excepted), then one `type().transform()` pair per binding, with retransformation of classes already loaded and no class format changes, so that the original bytecode can be restored. `install` runs when a recording starts and `uninstall` when it stops; between recordings nothing is installed. After installing, `HookInstaller` retransforms any named target loaded during installation and missed, and warns about a target that is loaded but has no method of the expected shape. `InstrumentationListener` writes every transformation to `agent.log`.

## 6. From call to span

```
WebLogic method (instrumented)
   │ enter                                                    exit
   ▼                                                           ▼
 *Advice ──── TraceRuntime.xxx() ────> SpanRecorder<F>.enter   SpanRecorder<F>.exit(token, args, result, thrown)
                                        │ readFacts → F         │ facts.describe(span); recordOutcome(...)
                                        ▼                       ▼
                                  SpanStack (per thread) ── pop ──> Trace ──> TraceWriter ──> <out>/<rec>/NNNNNN-t-<id>.json
```

`TraceRuntime` is the only static mutable state in the agent: it holds the one `TraceAgent` and hands the advice its recorders. Everything behind it is ordinary objects.

`SpanRecorder<F extends SpanFacts>` owns the shape every kind shares, as template methods:

- `enter(first, second, third)` returns `NOT_RECORDED` unless a recording is active; otherwise asks the subclass to `readFacts` from the three objects the advice passed, and when the facts are recognised pushes an `OpenSpan` on the thread's `SpanStack` and returns its token. A root span also records the caller's principal and HTTP session id, which the facts supply (`SpanFacts.principal`, `SpanFacts.httpSessionId`).
- `exit(token, arguments, result, thrown)` finds the top of the stack, checks the token, builds the `Span` from the `OpenSpan` (ids, order, depth, kind, thread, timing), lets the facts `describe` it (the `ejb`, `http` or `sql` block), calls `recordOutcome`, pops the span into the thread's `OpenTrace`, and when that is the root hands the finished `Trace` to `TraceWriter`. A token that does not match means the recording started or stopped mid-call: the stack is abandoned and nothing is written.
- `recordOutcome` by default serializes the arguments under the facts' `MethodSignature`, then the exception or, for a non-void method, the result. The serialization helpers (`capture`, `exception`, `serialize`) are members of the base.

| recorder | facts | adds to the shape |
| --- | --- | --- |
| `EjbRecorder` | `EjbFacts` from `SessionInvocation` and `BeanMetadataResolver` | nothing |
| `MessageRecorder` | `EjbFacts` from `MessageDelivery`, invocation `MESSAGE` | nothing |
| `ResourceRecorder` | `ResourceFacts` from the advice origin `<class>|<method>|<descriptor>` and `ResourceLayers` | nothing |
| `ServletRecorder` | `HttpFacts` holding the `HttpCapture` and the tee wrappers | `enterRequest` hands the wrappers back to the advice; `recordOutcome` writes the completed exchange through `HttpInfoReader` rather than arguments and a result |
| `JdbcRecorder` | `JdbcFacts` with the `SqlInfo` known before the driver runs | `bind` keeps the parameters a prepared statement receives; `rowRead` counts rows read afterwards; `recordOutcome` records the count, the batch counts or the result set |

`SpanStack` is one thread's stack of open spans and its `OpenTrace`. A push on an empty stack starts a trace, asking `WorkContextPropagation.inbound` whether another thread's work caused this one and recording that as `parentTraceId`/`parentSpanId`; every push publishes the current trace and span through `WorkContextPropagation.publish`, so an `@Asynchronous` call or a JMS message carries them to the thread that continues the work. Popping the root clears the WorkContext and builds the `Trace` with the server name and the deployments from `RuntimeMBeans`. `TraceWriter` is a bounded queue (1000) drained by one daemon thread that writes each trace to a `.tmp` file and renames it; a full queue drops the trace and counts it, and the request thread never blocks. `RecordingLifecycle` owns start and stop: install the hooks, write this server's manifest (`ManifestWriter`), open the window; close the window, drain the writer, complete the manifest, remove the hooks.

**A worked example.** A browser sends `POST /orders/api/orders`; the resource calls `OrderService.createOrder`, which calls `PricingService.price`.

1. WebLogic's `FilterChainImpl.doFilter` runs. `ServletPipelineAdvice.enter` calls `TraceRuntime.servlet().enterRequest(request, response)`. `ServletRecorder.readFacts` confirms the request implements `HttpServletRequest`, unwraps it, sees no open `HttpFacts` on this thread, generates or fetches the tee classes for this servlet API (`TeeClassCache`), wraps the request and response, and returns `HttpFacts`. The base pushes span `s1` on an empty stack: a new `OpenTrace`, `t-…`, with the request's principal and session. `enterRequest` returns the wrappers; the advice substitutes them, so every filter and the servlet read and write through the tee.
2. Jersey calls `OrdersResource.create(String)`. `ResourceAdvice.enter` calls `TraceRuntime.resource().enter(self, "…OrdersResource|create|(Ljava/lang/String;)…", null)`. `ResourceRecorder.readFacts` resolves the method through `MethodResolver` and the layer `jaxrs` through `ResourceLayers`; span `s2` is pushed under `s1`.
3. The resource calls the bean through its local interface; WebLogic's `SessionLocalMethodInvoker.invoke` runs. `SessionInvokerAdvice.enter` calls `TraceRuntime.ejb().enter(invokable, clientObject, wrapper)`. `SessionInvocation.read` pulls the method descriptor, the bean class and the `BeanInfo` out of the three objects; `BeanMetadataResolver.of` answers, from its per-class cache or from the runtime MBeans and JNDI, the application, module, JNDI names and bean type. Span `s3` is pushed under `s2`; `WorkContextPropagation.publish` makes `t-…/s3` the current entry.
4. `createOrder` calls `PricingService.price`: the same invoker, `s4` under `s3`. `price` returns; `SessionInvokerAdvice.exit` calls `TraceRuntime.ejb().exit(token, args, result, null)`. The base matches the token, `EjbFacts.describe` fills `span.ejb`, `recordOutcome` serializes the arguments and the result, and `s4` is popped into the trace. The WorkContext entry goes back to `s3`.
5. `createOrder` returns: `s3` is completed the same way. The resource returns: `s2`. The filter chain returns: `ServletPipelineAdvice.exit` calls `exitRequest`; `ServletRecorder.recordOutcome` flushes the writer, drains an unread body, and `HttpInfoReader.describe` reads method, URI, headers, status and both bodies into `span.http`. `s1` is popped; the stack is empty, so `OpenTrace.build` produces the `Trace` and `TraceWriter.offer` queues it. The writer thread writes `000001-t-….json` and logs one `TRACE` line.

Had `createOrder` sent a JMS message, the message would carry `t-…/s3` in its WorkContext; `MDListener.onMessage` on the delivery thread would start a new trace whose `parentTraceId` and `parentSpanId` name that span, and the viewer draws it under the call that caused it.

## 7. Serialization

`JacksonGraphSerializer` turns a value into a Jackson tree by walking the object graph field by field. It never calls a getter, never loads a lazy value, and uses Jackson only for the tree model and the output. One `SerializationContext` per recorded value holds the JSON path for warnings, the depth, and the identity table.

**Identity and cycles.** Every object written as a JSON object is tracked by identity. A second reference to a tracked object writes `{"@ref": n}` and adds `"@id": n` to the first occurrence. A collection, map or array still being written that is reached again is a cycle through a container and becomes `@unserializable` with that reason; one already complete is written again in full.

**Lazy detection.** `TypeInfo` recognises, by interface name, EclipseLink's `IndirectContainer` and `ValueHolderInterface` and Hibernate's `PersistentCollection` and `HibernateProxy`, and resolves the method that reports their state (`isInstantiated`, `wasInitialized`, `getHibernateLazyInitializer`). `LazyState` reads that state; an uninstantiated value becomes `{"@lazy": true, "loaded": false}` and is not touched. EclipseLink weaving puts a `_persistence_<field>_vh` holder next to a field; `TypeInfo` pairs them, so the field reports the holder's state.

**Infrastructure types.** `InfrastructureTypes` lists, by name, the types whose fields describe the JVM or the OS rather than the payload: threads, class loaders, classes, processes, sockets, streams, channels, JDBC connections and statements, locks, executors, futures, timers. Such a value becomes `{"@handle": "<class>", "kind", "identity", "toString"}`. Nothing else is special: loggers, framework and container objects are walked like any other object.

**Ceilings.** `SerializationCeilings` holds four: depth 64, 100 000 elements per collection, map or array, 16 Mi characters per string, 64 MiB per byte array; `BodyBuffer` holds a fifth, 64 MiB per HTTP body. None is reachable by a real payload. Every ceiling that fires writes `@truncated` with a count and adds a `SerializationWarning` with the JSON path to the span.

**Leaves.** `Scalars` writes numbers and booleans as JSON, enums by name, `BigDecimal` and `BigInteger` as plain strings, `java.time` by its ISO text, `java.util.Date` as an ISO instant, `java.sql.Timestamp` with its nanoseconds, `Calendar` as a zoned date-time, and `UUID`, `URI`, `URL`, `File`, `Path`, `Locale`, `Currency` and `StackTraceElement` by their text. A `Throwable` is walked like any object, its own fields included, plus its full stack trace; a `javax.jms.Message` is written by `JmsMessageView` as its API view (headers, properties, body), because the vendor object's fields describe the session.

**Markers.** `Marker` is the one type behind every `@` key; `Marker.writeTo` and `Marker.toNode` are the only code that knows their JSON shape. Each kind has a factory and a meaning:

| marker | means |
| --- | --- |
| `Marker.type` | the runtime class of an object; on every walked object |
| `Marker.id`, `Marker.ref` | an object written in full once and referenced again |
| `Marker.notSerializable` | walked all the same; the class is not `java.io.Serializable` |
| `Marker.lazy` | an uninstantiated lazy collection or holder, left untouched |
| `Marker.handle` | a runtime handle, recorded as class, kind, identity and toString |
| `Marker.skipped` | a field not written, with its reason: compiler-injected, weaver-injected, JVM-internal, or inaccessible without `--add-opens` |
| `Marker.truncated` | a ceiling reached: what and how much is missing |
| `Marker.text`, `Marker.bytes` | a string over the ceiling, or a byte array as base64, with its length |
| `Marker.entries` | a map with non-string keys, as key/value pairs |
| `Marker.jms` | a JMS message recorded as its API view |
| `Marker.unserializable` | the walk failed: class, reason, identity, toString |

Their exact JSON is in [TRACE-FORMAT.md](TRACE-FORMAT.md#markers).

## 8. The plugin

**`DomainPlatform` and the overrides block.** `Domain` reads the platform from `bin/`: `setDomainEnv.sh` means `UnixPlatform`, `setDomainEnv.cmd` means `WindowsPlatform`, both or neither means the platform the plugin runs on. The platform answers every question that differs between the two: the overrides file name, the comment prefix and line ending, the line that appends to `JAVA_OPTIONS` (with an option quoted for `cmd` when it holds a space), the trailer (`export JAVA_OPTIONS`), the pattern of a JDK assignment in the environment script, and whether two paths name the same location. No other class asks which platform it is on. `OverridesBlock` writes the block between `>>> weblogic-trace >>>` and `<<< weblogic-trace <<<` markers in the platform's comment syntax, replaces it in place on a second enable, records whether it created the file, and removes exactly what it added. `DomainJdk` reads the domain's JDK major version from the environment script and the JDK's `release` file, which decides whether the `--add-opens` lines are written; `NodeManagerSettings` detects `StartScriptEnabled=false`, in which case `EnableMojo` prints the `Arguments=` line for `startup.properties`.

**JVM discovery and matching.** `LocalJvms` reaches the attach API reflectively (on JDK 8 from `lib/tools.jar`). `DomainStateMonitor` lists the local JVMs, skips its own and those it has already classified, attaches to each whose display name starts with `weblogic.Server`, and keeps those whose `weblogic.RootDirectory` (else `user.dir`) is the domain home under the platform's case rule and that carry a `weblogic.Name`. For each it starts the JVM's local JMX connector and connects an `AgentControl` to the agent's MBean; connections are kept, re-made when they break, and dropped when the JVM disappears.

**The MBean and `DomainControl`.** `RecordingControl` in the agent exposes `start(id, name)`, `stop()` and status attributes at `ca.gc.cra.xz02.devops.wltrace:type=Control`. `DomainStateMonitor.evaluate` combines the disk (block present, jar present, jar build equal to the plugin's) with the servers into a `DomainState`, one of five sentences: *not enabled*; *enabled, server not running*; *enabled, server running without the agent, restart it*; *disabled on disk, agent still loaded until restart*; *ready*. `startRecording` assigns one id and starts it on every server; `stopRecording` stops it everywhere. `MonitoredDomainControl` adapts that to the viewer's `DomainControl`, so the viewer knows nothing of domains or JVMs.

**The three mojos.** `AbstractTraceMojo` holds the parameters `wltrace.domain`, `wltrace.port`, `wltrace.out`, verifies the domain, locates the agent jar among the plugin's own resolved artifacts, and builds the monitor. `EnableMojo` copies the jar to `<domain>/weblogic-trace/weblogic-trace-agent.jar` by rename (a running server has the old file mapped), writes the block, and reports the state. `DisableMojo` stops an active recording, removes the block or the file, deletes the install directory, and reports; recordings are never touched. `StartMojo` refuses unless the domain is enabled on disk, then calls `ViewerServer.start` and blocks until Ctrl-C.

## 9. The viewer

**Indexing and caching.** `RecordingDirectory.load` reads one recording: every `NNNNNN-t-*.json` trace, ordered by start time then sequence, and the servers' manifests merged into one (earliest start, latest end or none while any server is still recording, summed counts, every server name). `RecordingIndexer.index` produces the `RecordingIndex` the page loads: the merged manifest, one `TraceSummary` per trace, and the lineage entries. `RecordingCatalog` lists the recordings under the output directory newest first and caches each index under a stamp of the directory (newest modification time and file count), so a trace landing or a manifest completing recomputes it; this is how the page refreshes a live recording once a second.

**Lineage.** `LineageDetector` walks the traces in order. `ValueFlattener` turns every span's result and every root response body into output values, and every argument, request body, URL path segment and query parameter into input values, each a `ValueLocation`; a JSON or XML body (`XmlBodies`) is walked element by element and counted once more as a whole. `DistinctiveValues` keeps only values unlikely to coincide by chance: strings of six characters or more that are not a single word, UUIDs, ISO dates and times, numbers of 1000 or more or with a decimal part, and smaller integers only where the path names an identifier. Each input is looked up among earlier outputs; a hit is an entry `to ← from`, nearest earlier source first and the others as alternatives. Three rules: a value produced by more than five traces is too common to be a source; a value first seen in a root span's input is user-originated and never linked; an input without a source is classified `environment` when it is an instant inside the trace's own window or appears only in nested spans, else `userInput`, and an entry whose value the same trace also produces is flagged `alsoProducedHere`.

**Endpoints.** `ViewerServer.start(Path recordings, int port, DomainControl control)` binds `127.0.0.1:<port>` on the JDK's HTTP server with one handler per group:

| method and path | handler | answers |
| --- | --- | --- |
| `GET /`, `/index.html`, `/style.css`, `/app.js`, `/diagram.js` | `PageHandler` | the page's files |
| `GET /api/state` | `StateHandler` | the `ControlState`, re-evaluated per call |
| `POST /api/control/start` `{"name"}` | `ControlHandler` | `{"id"}`; 409 with the reason when the state does not allow it |
| `POST /api/control/stop` | `ControlHandler` | `{"stopped": [ids]}` |
| `GET /api/recordings` | `RecordingsHandler` | every recording, newest first |
| `GET /api/recordings/{id}` | `RecordingsHandler` | the `RecordingIndex` |
| `GET /api/recordings/{id}/traces/{file}` | `RecordingsHandler` | one trace file as written |
| `DELETE /api/recordings/{id}` | `RecordingsHandler` | removes a finished recording; 409 while active |

**The page.** `index.html` is the frame: the state bar, the recordings list, the diagram area and the details window. `style.css` is its appearance. `app.js` owns the state bar and its polling, the recordings list, routing by URL hash, loading traces, the details window with its marker highlighting and lineage badges, and the Start, Stop and Delete actions. `diagram.js` owns the sequence diagram: it builds the model (one lane per component grouped under its application, one heading per entry point, a call row and a return row per span, work continued on another thread under the call that caused it, runs of four or more identical leaf calls folded into one row) and draws it as SVG.

## 10. Dependencies

| artifact | version | module | purpose |
| --- | --- | --- | --- |
| `com.fasterxml.jackson.core:jackson-databind` | 2.19.0 | model (agent, viewer and plugin through it) | the JSON tree model and the mapper; shaded in the agent and the plugin |
| `net.bytebuddy:byte-buddy` | 1.17.5 | agent | advice inlining, retransformation, generated HTTP wrappers; shaded |
| `net.bytebuddy:byte-buddy-agent` | 1.17.5 | agent, test scope | an `Instrumentation` for the unit tests' own JVM |
| `org.testng:testng` | 7.5.1 | every module, test scope | the unit tests |
| `org.apache.maven:maven-plugin-api`, `maven-core` | 3.6.3 | plugin, provided | the mojo contract |
| `org.apache.maven.plugin-tools:maven-plugin-annotations` | 3.9.0 | plugin, provided | `@Mojo` and `@Parameter` |

Everything else the tests need, the WebLogic targets, the servlet, JMS and JPA types and the EclipseLink indirection types, is a stub under `src/test/java`.

## 11. Adding a hook

An end-to-end walkthrough for a new kind of call, say a timer callback.

1. **Find the target.** Follow [HOOKS.md, Finding a hook on a new build](HOOKS.md#finding-a-hook-on-a-new-build): a thread dump during the call, the first WebLogic frame above the application code that takes generic arguments, confirmed with `javap`.
2. **Write the facts.** A `SpanFacts` subclass in `record` holding what the target's arguments tell you at enter, with `describe` writing the span's block, `signature` naming the method whose arguments and result are recorded, and `summary` for the log.
3. **Write the recorder.** A `SpanRecorder<YourFacts>` subclass in `record`: `kind()` and `readFacts(first, second, third)`, reading the WebLogic objects through a new reader in `weblogic` (`Reflection.call`, `Reflection.field`; constraints 1, 3 and 4). Override `recordOutcome` only when the outcome is not arguments and a result. Add it to `TraceAgent` and a static accessor to `TraceRuntime`.
4. **Write the advice.** A `*Advice` class in `instrument`: an `@Advice.OnMethodEnter` method returning the token from `TraceRuntime.yours().enter(...)` and an `@Advice.OnMethodExit` method calling `exit(token, arguments, result, thrown)`; both `suppress = Throwable.class`, no lambdas (constraint 5).
5. **Write the hook.** A `Hook` subclass in `instrument` with the target class names and one `AdviceBinding` per method shape; add it to the list in `AgentStartup.assemble`. Mark it `optional()` when a server without the target should still record everything else.
6. **Extend the model when the span needs new facts.** A new field on `EjbInfo`, `HttpInfo` or `SqlInfo`, or a new block on `Span`, with its Javadoc, plus the entry in `trace.schema.json`.
7. **Document the format.** The field, its type and when it is present in [TRACE-FORMAT.md](TRACE-FORMAT.md).
8. **Give the viewer a lane.** `kindOf` and `compName` in `diagram.js` decide the lane of a span; the details window in `app.js` shows every field, so a new block appears without further work.
9. **Test it.** A stub of the target under `src/test/java/weblogic/...` with the member names the reader uses, a case in `HookInstallerTest` that drives the stub and asserts the span, and a stub-based test of the reader when it has rules of its own.
10. **Document the hook.** A section in [HOOKS.md](HOOKS.md) with the standard headings, including the build you verified on and the frames that located the target.
11. **Verify against a server.** `wltrace:enable`, restart, `wltrace:start`, drive the call, confirm the span on the page and the `instrumented <target>` line in `agent.log`, on each WebLogic version you support.
