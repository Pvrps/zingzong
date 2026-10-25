# weblogic-trace

weblogic-trace records what happens inside a WebLogic domain while you use an application, and shows it as one sequence diagram. A Java agent sits on the server's own entry points (HTTP requests, REST and SOAP resources, EJB calls, message-driven beans, SQL through data sources) and writes every call as a JSON trace: arguments, results, exceptions, headers, bodies, statements and parameters, exactly as they crossed the boundary. A Maven plugin installs the agent in a domain, removes it again, and serves the page where recordings are started, stopped and read. Nothing in the application changes; the only file touched in the domain is its overrides script, and the agent does nothing until a recording is started.

```
mvn wltrace:enable  -Dwltrace.domain=/path/to/domain    # install the agent, then restart the servers
mvn wltrace:start   -Dwltrace.domain=/path/to/domain    # serve the page: Start, use the application, Stop
mvn wltrace:disable -Dwltrace.domain=/path/to/domain    # remove the agent; recordings stay
```

Supported: WebLogic Server 12.2.1.4 on JDK 8 and 14.1.2 on JDK 21, `javax.*` applications; domains with Linux (`bin/setUserOverrides.sh`) or Windows (`bin\setUserOverrides.cmd`) start scripts; the plugin runs inside a JDK 8 or later Maven on the same machine as the servers. In any other JVM the agent stays inert.

| document | for |
| --- | --- |
| [docs/USAGE.md](docs/USAGE.md) | the person who wants recordings: install, the three commands, the page, parameters, both platforms, the recordings, the limits |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) | a contributor's first day: building and testing under both JDKs, verifying against a server, what to update with a change, releasing |
| [docs/architecture/ARCHITECTURE.md](docs/architecture/ARCHITECTURE.md) | an engineer joining the project: the glossary, the constraints, the source tree, how a call becomes a span, serialization, the plugin, the viewer, adding a hook |
| [docs/architecture/HOOKS.md](docs/architecture/HOOKS.md) | an engineer touching WebLogic internals: every class, member and MBean the agent reads, where it is verified, how it fails |
| [docs/architecture/TRACE-FORMAT.md](docs/architecture/TRACE-FORMAT.md) | anyone consuming recordings: the files, every field, the markers, the ceilings, an annotated example |
| [CLAUDE.md](CLAUDE.md) | whoever edits the code: the constraints, the style, the build commands |
