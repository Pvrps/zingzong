# weblogic-trace

weblogic-trace records what crosses the EJB, HTTP and JDBC boundaries of a WebLogic domain, raw, as
JSON traces, and shows them as one sequence diagram. A `-javaagent` with no compile dependency on
WebLogic sits on the container's own choke points; a Maven plugin installs it in a domain and
serves the page where captures are started, stopped and read. Every argument, result, exception,
header, body, statement and parameter is recorded as it was, with every structural transformation
marked.

- `wltrace:enable` installs the agent in a domain; `wltrace:disable` removes it.
- `wltrace:start` serves the page: state bar, Start/Stop, the recordings, the sequence diagram, the details window.
- Three parameters, all `-D`: `wltrace.domain`, `wltrace.port`, `wltrace.out`.

Supported: WebLogic 12.2.1.4 on JDK 8 and 14.1.2 on JDK 21, both `javax.*`; the plugin runs inside
a JDK 8 or later Maven; domains on Linux (`bin/setUserOverrides.sh`) and on Windows
(`bin\setUserOverrides.cmd`). In any other JVM the agent stays inert.

```
mvn -q install
mvn wltrace:enable -Dwltrace.domain=/path/to/domain
# restart the servers
mvn wltrace:start  -Dwltrace.domain=/path/to/domain
```

Read next:

- [USAGE.md](USAGE.md): install, the three goals, the page, parameters, both platforms, recordings, limitations.
- [architecture/ARCHITECTURE.md](architecture/ARCHITECTURE.md): the modules, how the agent loads and records, the serializer and its markers, the trace model, the plugin, lineage.
- [architecture/HOOKS.md](architecture/HOOKS.md): every WebLogic internal the agent touches, with the build it is verified on.

Modules: `weblogic-trace-model` (the format), `weblogic-trace-agent` (the agent), `weblogic-trace-maven-plugin` (the goals,
the reader, the page). `mvn -q install` builds all three and runs the unit tests offline under
JDK 8 or 21.

The lab that runs and verifies the product against real WebLogic servers (installs, domains, sample
applications, load and check scripts) is the sibling repository **weblogic-trace-lab**; it consumes
the plugin from the local Maven repository by its coordinates and is not needed to use the product.
