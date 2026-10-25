# Developing weblogic-trace

For a contributor's first day: what to install, how to build and test offline under both JDKs, what the build produces, how to verify against a real server, what to update with which kind of change, and how a release is made.

## Prerequisites

- A JDK 8 and a JDK 21 (Temurin builds of both are known to work). The build must pass under each; `JAVA_HOME` selects which one Maven runs on.
- Maven 3.6 or later.

Nothing else: no WebLogic installation, no database, no network once the dependencies are in the local repository. `devenv.nix` is an optional convenience that puts a JDK 21 and Maven on the path and sets `JDK8_HOME`; deleting it changes nothing about the build.

## Building and testing

```
mvn -q install                                   # under the JDK on the path
JAVA_HOME=$JDK8_HOME mvn -q install              # the same under a Java 8 JDK
```

Both compile Java 8 bytecode (`maven.compiler.source` and `target` 1.8) and run every unit test with TestNG. The tests run offline and start no server: WebLogic's classes are stubs named like the real ones under `weblogic-trace-agent/src/test/java/weblogic/`, the servlet, JMS, JPA and EclipseLink types the tests touch are stubs next to them, and the agent's instrumentation tests install the hooks on the test JVM itself through `byte-buddy-agent`. `BootstrapVisibilityTest` fails the build on a class file above major version 52 or on a reference from agent code into a platform-loader module.

The modules build in this order, each depending on the ones before it:

| module | produces |
| --- | --- |
| `weblogic-trace-model` | `weblogic-trace-model-<version>.jar`: the format classes and `trace.schema.json` |
| `weblogic-trace-agent` | `weblogic-trace-agent.jar`: the `-javaagent`, with ByteBuddy and Jackson shaded under `ca.gc.cra.xz02.devops.wltrace.shaded` and `Premain-Class: ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentMain` |
| `weblogic-trace-viewer` | `weblogic-trace-viewer-<version>.jar`: the recording reader, the web server and the page files |
| `weblogic-trace-maven-plugin` | `weblogic-trace-maven-plugin-<version>.jar`: the three goals, with the viewer, the model and Jackson shaded in; the agent jar is a plain dependency the goal copies into the domain |

`install` puts all four into the local Maven repository, which is where `mvn wltrace:<goal>` resolves them from. The repository's `.mvn/settings.xml` adds the `pluginGroup`, so the short form works inside this directory without touching `~/.m2/settings.xml`.

## Verifying against a server

The unit tests prove the agent against stubs; a change that touches a hook, the serializer's handling of container objects, or the plugin's handling of a domain is verified on a real server as well. You need a WebLogic domain you can restart and an application deployed to it with at least one EJB call behind an HTTP entry point.

1. `mvn -q install` here, then `mvn wltrace:enable -Dwltrace.domain=<domain>`; read the state it prints.
2. Restart the domain's servers. `agent.log` under the output directory shows the agent's start line and `weblogic-trace-agent ready, recording off`.
3. `mvn wltrace:start -Dwltrace.domain=<domain>`; the page reports *ready* with each server's pid.
4. Press **Start**, exercise the application, press **Stop**.
5. Confirm the trace on the page: the request as the entry point, the resource method and the EJB calls under it, SQL spans under the beans that ran statements, the arguments and results in the details window. `agent.log` lists `instrumented <class>` for every hook target and no `ERROR` line.
6. `mvn wltrace:disable -Dwltrace.domain=<domain>` and confirm the domain's `bin/` lists exactly what it listed before step 1.
7. Repeat on each WebLogic version you support (12.2.1.4 on JDK 8 and 14.1.2 on JDK 21), and with the plugin run under a Java 8 Maven (`JAVA_HOME=$JDK8_HOME mvn wltrace:...`).

For a change to a hook, also check the cases that hook is responsible for: an exception thrown by a bean, an `@Asynchronous` call or a JMS message (the continuation drawn under its cause), and a statement with parameters.

## What to update with a change

| the change touches | update in the same change |
| --- | --- |
| a WebLogic class, member or MBean the agent reads | its section in [architecture/HOOKS.md](architecture/HOOKS.md): class and method, what is read, verified on, when it fails |
| a field of the trace format, a marker or a ceiling | the `model` class and its Javadoc, `trace.schema.json`, and [architecture/TRACE-FORMAT.md](architecture/TRACE-FORMAT.md) |
| one of the seven constraints | its subsection in [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md#2-constraints-that-shape-the-code) and the matching item in `CLAUDE.md` |
| a recorder, hook, package or the plugin's flow | the matching section of [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md) |
| what the user sees or types | [USAGE.md](USAGE.md) |

Every class, method, package, file and goal name a document mentions is in backticks and exists in the tree; `git grep` for it before committing.

## Versioning and release

The version lives in the parent `pom.xml` and the four module poms (`1.0.0-SNAPSHOT`). A release:

1. Set the release version in every pom (`mvn versions:set -DnewVersion=1.0.0` or by hand), build and test under both JDKs, verify against a server.
2. Commit, tag `v1.0.0`.
3. Deploy to the organisation's repository: `mvn -q deploy -DaltDeploymentRepository=releases::default::https://<repository host>/repository/releases/` (the organisation's repository URL and credentials go in `~/.m2/settings.xml`).
4. Set the next `-SNAPSHOT` version in every pom and commit.

Users then set the plugin version in the full-form command or in their `pluginGroups` configuration, and `wltrace:enable` installs the matching agent.
