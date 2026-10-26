# weblogic-trace

weblogic-trace records what happens inside a WebLogic domain while you use an application, and shows it as a sequence diagram.

Press **Start**, use the application, press **Stop**. Every HTTP request, REST and SOAP call, EJB invocation, message-driven bean delivery and SQL statement that ran in the domain is recorded with its real arguments, results, exceptions, headers, bodies and parameters, and drawn in order. Work handed to another thread, like a JMS message to an MDB or an `@Asynchronous` call, is linked back to the call that caused it.

It's useful for understanding an unfamiliar application, seeing what a screen actually does across beans and databases, reproducing a bug, or capturing real payloads for tests.

No application changes and no redeploys are needed. The only change to the domain is one block in its overrides script, which comes out again with one command.

## How it works

- **A Java agent** is loaded into each WebLogic server. It does nothing until a recording starts. Then it hooks a few of WebLogic's own entry points, records every call that passes through them, and writes each one as a JSON trace.
- **A Maven plugin** installs and removes the agent, finds the domain's running servers, and starts and stops recordings on all of them at once.
- **A local web page**, served by the plugin, lists the recordings and draws them as sequence diagrams, with every recorded value one click away.

## Quick start

```sh
mvn -q install                                           # once, in this repository

mvn wltrace:enable  -Dwltrace.domain=/path/to/domain     # install the agent, then restart the servers
mvn wltrace:start   -Dwltrace.domain=/path/to/domain     # open the page: Start, use the app, Stop
mvn wltrace:disable -Dwltrace.domain=/path/to/domain     # remove the agent; recordings are kept
```

**Supported:** WebLogic Server with `javax.*` applications, on Linux or Windows. The plugin runs on the same machine as the servers, with JDK 8 or later and Maven 3.6+.

> Recordings contain the raw data that crossed each call, including credentials and personal data. Nothing is masked.

## Documentation

| | |
| --- | --- |
| [docs/USAGE.md](docs/USAGE.md) | Recording a domain: install, the three commands, the page, parameters, special cases, limits, troubleshooting |
| [docs/TRACE-FORMAT.md](docs/TRACE-FORMAT.md) | The recording files: layout, every field, the markers |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | Building, testing, checking a change on a server |
| [docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) | How the code is organised, and where to read next |
| [docs/architecture/CONCEPTS.md](docs/architecture/CONCEPTS.md) | Agents, bytecode, class loaders, WorkContext and JMX, explained from scratch |
| [docs/architecture/FRAMEWORK.md](docs/architecture/FRAMEWORK.md) | How the agent loads, and how hooks, advice and recorders work |
| [docs/architecture/HOOKS.md](docs/architecture/HOOKS.md) | The WebLogic methods that are hooked, and why |
| [docs/architecture/TRACING.md](docs/architecture/TRACING.md) | How calls become traces and how traces are linked |
| [docs/architecture/SERIALIZATION.md](docs/architecture/SERIALIZATION.md) | How recorded objects become JSON |
| [docs/architecture/PLUGIN.md](docs/architecture/PLUGIN.md) | How the Maven goals change a domain and control its servers |
| [docs/architecture/VIEWER.md](docs/architecture/VIEWER.md) | The page, lineage, and how the diagram is built |

## Repository

| module | |
| --- | --- |
| [`weblogic-trace-model`](weblogic-trace-model) | the trace format |
| [`weblogic-trace-agent`](weblogic-trace-agent) | the Java agent |
| [`weblogic-trace-viewer`](weblogic-trace-viewer) | the web page and the recording reader |
| [`weblogic-trace-maven-plugin`](weblogic-trace-maven-plugin) | the `wltrace` goals |
