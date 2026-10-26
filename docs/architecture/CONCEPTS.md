# Concepts

This document explains the ideas the rest of the architecture documentation builds on, in plain language. You don't need any background in Java agents, bytecode or WebLogic internals to read it. Each entry ends with where the idea shows up in this repository.

- [Java concepts](#java-concepts)
- [WebLogic concepts](#weblogic-concepts)
- [Tracing concepts](#tracing-concepts)

## Java concepts

### Java agent

A Java agent is a jar that the JVM loads before the application starts, given on the command line as `-javaagent:/path/to/agent.jar=arguments`. The JVM calls the jar's `premain` method, named in its manifest, and hands it an `Instrumentation` object: an API that lets the agent change classes as the JVM loads them, or after.

An agent changes how a program behaves without anyone changing the program's source. That's how weblogic-trace sees what happens in a server without modifying the deployed applications.

Used in: [`AgentMain`][AgentMain], the agent jar's manifest in [`weblogic-trace-agent/pom.xml`][agent-pom].

### Bytecode

Java source compiles to bytecode, the instructions in a `.class` file. The JVM runs bytecode, not source. An agent can rewrite a class's bytecode before the JVM uses it, for example to add a few instructions at the start and the end of a method.

Bytecode has versions. Java 8 produces major version 52; a JDK 8 JVM refuses anything newer. The agent is compiled to Java 8 bytecode so the same jar runs on a JDK 8 and a JDK 21 server.

Used in: every agent class; checked by [`BootstrapVisibilityTest`][BootstrapVisibilityTest].

### ByteBuddy

ByteBuddy is the library the agent uses to rewrite bytecode, so nobody writes bytecode by hand. You describe *which* classes and methods to change with matchers (for example "the static method `invoke` with five arguments of class `X`") and *what* to add, as ordinary Java code called advice.

Used in: [`HookInstaller`][HookInstaller] (rewriting existing classes) and [`TeeClasses`][TeeClasses] (generating new classes at runtime).

### Advice and inlining

Advice is a small piece of Java code that ByteBuddy copies into a target method: an `@Advice.OnMethodEnter` method runs at the start of the target, and an `@Advice.OnMethodExit` method runs when it returns or throws. The copy is called *inlining*. The advice class itself is never called; its bytecode becomes part of the target method.

Because the code is copied into someone else's class, it has to be self-contained: no fields, no lambdas, nothing that only makes sense inside the advice class. The agent's advice classes do one thing: call a static method of [`TraceRuntime`][TraceRuntime], which hands the call to an ordinary object.

Used in: every `*Advice` class in [`agent/instrument`][instrument]. See [FRAMEWORK.md](FRAMEWORK.md#advice).

### Transformation and retransformation

Rewriting a class as it is loaded is a *transformation*. Rewriting a class that is already loaded is a *retransformation*. The JVM allows retransformation as long as the class keeps the same shape: same fields, same method signatures. Only method bodies change.

Retransformation can also be undone: the JVM is asked to retransform the class again, without the change, and the original bytecode comes back. weblogic-trace relies on this to add its advice when a recording starts and remove it when the recording stops.

Used in: [`HookInstaller`][HookInstaller].

### Class loaders

Every class in a JVM is loaded by a class loader, and a class is identified by its name *and* its loader. Loaders form a tree; a loader asks its parent first and only loads a class itself when the parent can't. The ones that matter here:

| loader | loads |
| --- | --- |
| bootstrap | the core JDK (`java.lang`, `java.util`, ...). Its parent is nobody; every other loader can see its classes. |
| platform (JDK 9+) | the rest of the JDK, for example `java.sql`. The bootstrap loader can't see these. |
| system | the class path, which includes WebLogic's own jars and the agent jar. |
| application loaders | each deployed application and module. WebLogic creates them, under the system loader. |

The agent adds its own jar to the bootstrap loader's search path at startup. The reason: advice is inlined into WebLogic classes (system loader) and into application classes (application loaders), and it calls [`TraceRuntime`][TraceRuntime]. Only a class on the bootstrap loader is visible from both, and only then is there exactly one copy of it.

The price is that agent code can only link against JDK modules the bootstrap loader has. It can't refer to `java.sql.Statement` directly, for example; it recognises such types by name and calls them reflectively.

Used in: [`AgentMain`][AgentMain], [`ApplicationClassLoaders`][ApplicationClassLoaders], [`BootstrapVisibilityTest`][BootstrapVisibilityTest].

### Reflection

Reflection is calling methods and reading fields through `java.lang.reflect` by name, at runtime, instead of through compiled references. The agent compiles against no WebLogic jar and no `javax.*` API, so everything it reads from WebLogic or the application goes through reflection. A reflective read that fails returns null instead of throwing.

Used in: [`Reflection`][Reflection] and every class in [`agent/weblogic`][weblogic].

### Modules and `--add-opens`

From JDK 9, the JDK is split into modules, and code outside a module can't reflectively read the private fields of the module's classes unless the module is *opened* to it with `--add-opens`. The agent reads objects field by field, including JDK types such as `java.util.ArrayList`, so a JDK 9+ server needs a few `--add-opens` flags. JDK 8 has no modules and needs none.

Used in: [`OverridesBlock`][OverridesBlock] (writes the flags), [`TypeInfo`][TypeInfo] (reports a field it can't open).

### Shading

Shading copies a library's classes into your own jar under a different package name, for example `net.bytebuddy` becomes `ca.gc.cra.xz02.devops.wltrace.shaded.net.bytebuddy`. The agent shades ByteBuddy and Jackson so that its copies never clash with the versions WebLogic or an application bundles.

Used in: [`weblogic-trace-agent/pom.xml`][agent-pom], [`weblogic-trace-maven-plugin/pom.xml`][plugin-pom].

### JMX and MBeans

JMX is the JDK's built-in management API. A JVM has an MBean server, a registry of named management objects (MBeans) with attributes and operations. Tools connect to it and read the attributes or call the operations.

The agent registers one MBean, `ca.gc.cra.xz02.devops.wltrace:type=Control`, with `start` and `stop` operations. The plugin starts and stops recordings by calling them. WebLogic also publishes its own configuration and runtime state as MBeans, which the agent reads to name applications and data sources.

Used in: [`RecordingControl`][RecordingControl], [`AgentControl`][AgentControl], [`RuntimeMBeans`][RuntimeMBeans].

### Attach API

The attach API lets one JVM list the other JVMs of the same OS user on the machine, read their system properties, and open a JMX connection to them, without any port being configured. The plugin uses it to find the domain's running servers. On JDK 8 the API lives in `lib/tools.jar`; on JDK 9+ it's part of the JDK.

Used in: [`LocalJvms`][LocalJvms].

## WebLogic concepts

### Domain and server

A WebLogic domain is a directory holding the configuration (`config/config.xml`), start scripts (`bin/`) and runtime state of one or more servers. Each server is one JVM. The JVMs are started by the domain's start scripts, directly or through Node Manager.

### Overrides script

`bin/setUserOverrides.sh` (or `.cmd` on Windows) is a script the start scripts run, when it exists, before starting a server. It's the documented place to add JVM options. `wltrace:enable` adds the `-javaagent` option there and nothing else in the domain.

Used in: [`OverridesBlock`][OverridesBlock]. See [PLUGIN.md](PLUGIN.md#the-overrides-block).

### Node Manager

Node Manager is WebLogic's process for starting and stopping servers remotely. With `StartScriptEnabled=false` in `nodemanager/nodemanager.properties`, it starts servers without the start scripts, so the overrides script is never read. The JVM options then go into each server's `startup.properties` instead.

Used in: [`NodeManagerSettings`][NodeManagerSettings].

### Container internals

WebLogic runs every EJB call, message delivery and servlet request through its own classes before the application code runs. For example, every call to a session bean goes through `weblogic.ejb.container.internal.SessionLocalMethodInvoker.invoke` or its remote counterpart. These internal classes are the points weblogic-trace hooks into: one hook there sees every bean of every application, without touching application classes.

See [HOOKS.md](HOOKS.md).

### WorkContext

WorkContext is a WebLogic API for attaching small key/value entries to a unit of work. WebLogic carries the entries along when the work moves: to the thread an `@Asynchronous` method runs on, inside a JMS message to the thread that delivers it to a message-driven bean. The agent puts "the trace and span currently running" in it, so work that continues on another thread can say what caused it.

Used in: [`WorkContextPropagation`][WorkContextPropagation]. See [TRACING.md](TRACING.md#work-that-continues-on-another-thread).

### Message-driven bean (MDB)

An MDB is an EJB that receives JMS messages. The application sends a message to a queue or topic, and WebLogic later delivers it to the MDB's `onMessage` method, on a different thread, often after the sender has returned.

## Tracing concepts

### Recording

A recording is the window between pressing **Start** and **Stop**. It has one id across every server of the domain, and one directory on disk holding everything recorded in that window.

### Trace

A trace is everything one thread did for one entry point: an HTTP request, an EJB call from outside the server, or a message delivery. It's written as one JSON file.

### Span

A span is one recorded call inside a trace: an HTTP request, a REST or SOAP resource method, an EJB method, or an SQL statement. It holds what went in, what came out and how long it took. Spans nest: a span's parent is the call that was running when it started.

### Entry point and child trace

An entry point is a trace with no recorded cause, for example a request from a browser. A child trace is a trace started by another trace's work on a different thread, for example an MDB delivery caused by a message the entry point sent. The child names its cause by trace id and span id, and the viewer draws it under that span.

### Lineage

Lineage links a value a trace received to an earlier output it came from, for example an order id that a create call returned and a later confirm call received. The viewer works it out after the fact, from values alone.

See [VIEWER.md](VIEWER.md#lineage).

[agent-pom]: ../../weblogic-trace-agent/pom.xml
[plugin-pom]: ../../weblogic-trace-maven-plugin/pom.xml
[AgentMain]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/bootstrap/AgentMain.java
[BootstrapVisibilityTest]: ../../weblogic-trace-agent/src/test/java/ca/gc/cra/xz02/devops/wltrace/agent/bootstrap/BootstrapVisibilityTest.java
[HookInstaller]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/instrument/HookInstaller.java
[ApplicationClassLoaders]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/instrument/ApplicationClassLoaders.java
[instrument]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/instrument
[TeeClasses]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/http/TeeClasses.java
[TraceRuntime]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/record/TraceRuntime.java
[RecordingControl]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/record/RecordingControl.java
[Reflection]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/weblogic/Reflection.java
[weblogic]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/weblogic
[RuntimeMBeans]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/weblogic/RuntimeMBeans.java
[WorkContextPropagation]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/weblogic/WorkContextPropagation.java
[TypeInfo]: ../../weblogic-trace-agent/src/main/java/ca/gc/cra/xz02/devops/wltrace/agent/serialize/TypeInfo.java
[OverridesBlock]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/domain/OverridesBlock.java
[NodeManagerSettings]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/domain/NodeManagerSettings.java
[AgentControl]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/jvm/AgentControl.java
[LocalJvms]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/jvm/LocalJvms.java
