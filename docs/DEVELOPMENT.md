# Developing weblogic-trace

This guide covers working on the code: building, testing, checking a change against a real server, and keeping the documentation current. How the code is organised is in [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md).

## Prerequisites

- **JDK 8.**
- **Maven 3.6 or later.**

Nothing else. The build and tests need no WebLogic installation, no database and, once dependencies are downloaded, no network.

## Build

```sh
mvn -q install
```

This compiles Java 8 bytecode, runs every test, and puts the four `-SNAPSHOT` artifacts in the local Maven repository, which is where `mvn wltrace:<goal>` resolves the plugin from.

| module | produces |
| --- | --- |
| `weblogic-trace-model` | the format classes and `trace.schema.json` |
| `weblogic-trace-agent` | `weblogic-trace-agent.jar`, the `-javaagent`, with ByteBuddy and Jackson shaded in |
| `weblogic-trace-viewer` | the recording reader, the web server and the page |
| `weblogic-trace-maven-plugin` | the three goals, with the viewer, model and Jackson shaded in and the agent jar as a dependency |

## Testing

All tests are TestNG, run offline, and start no server.

**How WebLogic is replaced.** The agent compiles against no WebLogic or `javax.*` jar and finds everything by name ([CONCEPTS.md](architecture/CONCEPTS.md#reflection)). So the tests supply small classes with the real names and the members the agent reads:

| under `weblogic-trace-agent/src/test/java/` | stands in for |
| --- | --- |
| `weblogic/` | WebLogic's EJB invokers, `MDListener`, servlet pipeline, JDBC wrappers, `BeanInfo`, class loaders |
| `javax/` | the servlet, JMS, JAX-RS, EJB and JPA types the tests touch |
| `org/eclipse/persistence/` | EclipseLink's lazy containers and value holders |
| `ca/.../agent/fixtures/` | application-style DTOs and entities with awkward shapes: polymorphism, lazy collections, non-serializable members |
| `ca/.../agent/instrument/stubs/` | beans and calls for the hook tests |

**How hooks are tested.** The instrumentation tests use `byte-buddy-agent` to get an `Instrumentation` for the test JVM itself, install real hooks on the stub classes, call them, and check the spans. [`weblogic-trace-agent/pom.xml`][agent-pom] runs the tests with `-Djdk.attach.allowAttachSelf=true`. When the build runs on JDK 9 or later, its `jdk9plus` profile also adds the `--add-opens` flags `wltrace:enable` writes into a JDK 9+ domain.

**Guards.** [`BootstrapVisibilityTest`][BootstrapVisibilityTest] fails the build if an agent class is above Java 8 bytecode, or links to a JDK module the bootstrap loader can't see (`java.sql`, `javax.sql`, ...). See [CONCEPTS.md](architecture/CONCEPTS.md#class-loaders) for why.

**Recorded fixtures.** `weblogic-trace-agent/src/test/resources/captures/` and `weblogic-trace-viewer/src/test/resources/recordings/` hold traces and recordings in the current format, which the serializer, indexer and lineage tests read.

## Checking a change on a server

Unit tests prove the agent against stubs. A change to a hook, to how WebLogic objects are read, to the serializer's handling of container objects, or to how the plugin edits a domain is also checked on a real domain:

1. `mvn -q install`, then `mvn wltrace:enable -Dwltrace.domain=<domain>`. Read the state it prints.
2. Restart the servers. `agent.log` shows `weblogic-trace-agent ready, recording off`.
3. `mvn wltrace:start -Dwltrace.domain=<domain>`, press **Start**, and drive the case: an HTTP request that calls EJBs and runs SQL, an exception from a bean, an `@Asynchronous` call, a JMS message to an MDB.
4. Press **Stop**. Check the diagram and details, and that `agent.log` lists `instrumented <class>` for every target with no `ERROR`.
5. `mvn wltrace:disable -Dwltrace.domain=<domain>` and check that the domain's `bin/` is as before step 1.

## Keeping the documentation current

Each topic has one home. Update it in the same change:

| the change touches | update |
| --- | --- |
| what a user types or sees | [USAGE.md](USAGE.md) |
| a field, marker or limit in the files | the model class, `trace.schema.json`, [TRACE-FORMAT.md](TRACE-FORMAT.md) |
| a module, package or design rule | [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) |
| loading, startup, hook installation, advice, the MBean | [architecture/FRAMEWORK.md](architecture/FRAMEWORK.md) |
| a hook or a WebLogic internal the agent reads | [architecture/HOOKS.md](architecture/HOOKS.md) |
| spans, trace linking, writing files | [architecture/TRACING.md](architecture/TRACING.md) |
| how values become JSON | [architecture/SERIALIZATION.md](architecture/SERIALIZATION.md) |
| the goals, the domain edit, server discovery | [architecture/PLUGIN.md](architecture/PLUGIN.md) |
| the web server, lineage, the page | [architecture/VIEWER.md](architecture/VIEWER.md) |

Documents describe the code as it is, in the present tense. Every class, method, file and goal name in backticks exists in the tree.

[agent-pom]: ../weblogic-trace-agent/pom.xml
[BootstrapVisibilityTest]: ../weblogic-trace-agent/src/test/java/ca/gc/cra/xz02/devops/wltrace/agent/bootstrap/BootstrapVisibilityTest.java
