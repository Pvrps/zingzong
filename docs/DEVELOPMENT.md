# Developing weblogic-trace

This guide covers working on the code: building, testing, checking a change against a real server, keeping the documentation current, and releasing. How the code is organised is in [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md); the rules for editing it are in [CLAUDE.md](../CLAUDE.md).

## Prerequisites

- **A JDK 8 and a JDK 21.** The build must pass on both: WebLogic 12.2.1.4 runs on JDK 8 and 14.1.2 on JDK 21, and the same agent jar runs on both.
- **Maven 3.6 or later.**

Nothing else. The build and tests need no WebLogic installation, no database and, once dependencies are downloaded, no network.

## Build

```sh
mvn -q install                          # with the JDK on the path
JAVA_HOME=/path/to/jdk8 mvn -q install  # the same with JDK 8
```

Both compile Java 8 bytecode and run every test. `install` puts the four artifacts in the local Maven repository, which is where `mvn wltrace:<goal>` resolves the plugin from.

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

**How hooks are tested.** The instrumentation tests use `byte-buddy-agent` to get an `Instrumentation` for the test JVM itself, install real hooks on the stub classes, call them, and check the spans. [`weblogic-trace-agent/pom.xml`][agent-pom] runs the tests with `-Djdk.attach.allowAttachSelf=true`. On JDK 9+ its `jdk9plus` profile adds the same `--add-opens` flags `wltrace:enable` writes into a domain, so the serializer sees the same JDK fields as on a server.

**Guards.** [`BootstrapVisibilityTest`][BootstrapVisibilityTest] fails the build if an agent class is above Java 8 bytecode, or links to a JDK module the bootstrap loader can't see (`java.sql`, `javax.sql`, ...). See [CONCEPTS.md](architecture/CONCEPTS.md#class-loaders) for why.

**Recorded fixtures.** `weblogic-trace-agent/src/test/resources/captures/` and `weblogic-trace-viewer/src/test/resources/recordings/` hold traces and recordings in the current format, which the serializer, indexer and lineage tests read.

## Checking a change on a server

Unit tests prove the agent against stubs. A change to a hook, to how WebLogic objects are read, to the serializer's handling of container objects, or to how the plugin edits a domain is also checked on a real domain:

1. `mvn -q install`, then `mvn wltrace:enable -Dwltrace.domain=<domain>`. Read the state it prints.
2. Restart the servers. `agent.log` shows `weblogic-trace-agent ready, recording off`.
3. `mvn wltrace:start -Dwltrace.domain=<domain>`, press **Start**, and drive the case: an HTTP request that calls EJBs and runs SQL, an exception from a bean, an `@Asynchronous` call, a JMS message to an MDB.
4. Press **Stop**. Check the diagram and details, and that `agent.log` lists `instrumented <class>` for every target with no `ERROR`.
5. `mvn wltrace:disable -Dwltrace.domain=<domain>` and check that the domain's `bin/` is as before step 1.
6. Repeat on both supported versions, and with the plugin run on JDK 8 (`JAVA_HOME=/path/to/jdk8 mvn wltrace:...`).

## Keeping the documentation current

Each topic has one home. Update it in the same change:

| the change touches | update |
| --- | --- |
| what a user types or sees | [USAGE.md](USAGE.md) |
| a field, marker or limit in the files | the model class, `trace.schema.json`, [TRACE-FORMAT.md](TRACE-FORMAT.md) |
| a module, package or design rule | [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md), and [CLAUDE.md](../CLAUDE.md) for a rule |
| loading, startup, hook installation, advice, the MBean | [architecture/FRAMEWORK.md](architecture/FRAMEWORK.md) |
| a hook or a WebLogic internal the agent reads | [architecture/HOOKS.md](architecture/HOOKS.md) |
| spans, trace linking, writing files | [architecture/TRACING.md](architecture/TRACING.md) |
| how values become JSON | [architecture/SERIALIZATION.md](architecture/SERIALIZATION.md) |
| the goals, the domain edit, server discovery | [architecture/PLUGIN.md](architecture/PLUGIN.md) |
| the web server, lineage, the page | [architecture/VIEWER.md](architecture/VIEWER.md) |

Documents describe the code as it is, in the present tense. Every class, method, file and goal name in backticks exists in the tree.

## Releasing

The version is in the root `pom.xml` and the four module poms (`1.0.0-SNAPSHOT`).

1. Set the release version in every pom (`mvn versions:set -DnewVersion=1.0.0`), build and test on both JDKs, and check on a server.
2. Commit and tag `v1.0.0`.
3. Deploy to the organisation's Maven repository: `mvn -q deploy -DaltDeploymentRepository=releases::default::https://<repository host>/repository/releases/`, with credentials in `~/.m2/settings.xml`.
4. Set the next `-SNAPSHOT` version in every pom and commit.

Users then name that version in the full plugin coordinates, and `wltrace:enable` installs the matching agent.

[agent-pom]: ../weblogic-trace-agent/pom.xml
[BootstrapVisibilityTest]: ../weblogic-trace-agent/src/test/java/ca/gc/cra/xz02/devops/wltrace/agent/bootstrap/BootstrapVisibilityTest.java
