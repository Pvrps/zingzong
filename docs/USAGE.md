# Using weblogic-trace

For the person who wants recordings of a WebLogic domain and nothing else: install, the three commands, the page, the parameters, the two platforms, where the recordings are, and the limits.

## Install

The plugin is `ca.gc.cra.xz02.devops:weblogic-trace-maven-plugin:1.0.0-SNAPSHOT`; the agent it installs, `ca.gc.cra.xz02.devops:weblogic-trace-agent`, comes with it and is never given as a path. The short form `mvn wltrace:<goal>` needs the plugin group in `~/.m2/settings.xml`:

```xml
<pluginGroups>
  <pluginGroup>ca.gc.cra.xz02.devops</pluginGroup>
</pluginGroups>
```

Without it, the full form is `mvn ca.gc.cra.xz02.devops:weblogic-trace-maven-plugin:1.0.0-SNAPSHOT:<goal>`. The goals need no project; run them from any directory, with a JDK (8 or later) and Maven.

## Enable, restart, start

```
mvn wltrace:enable -Dwltrace.domain=/path/to/domain
# restart the domain's servers
mvn wltrace:start  -Dwltrace.domain=/path/to/domain
```

**`wltrace:enable`** copies the agent to `<domain>/weblogic-trace/weblogic-trace-agent.jar` and writes a delimited block into the domain's overrides file (`bin/setUserOverrides.sh` or `bin\setUserOverrides.cmd`) that adds the `-javaagent` option and, on a JDK 9 or later domain, the `--add-opens` flags the agent needs. It creates the file when the domain has none and says so in the block; running it again updates the block in place. It then prints the domain's state. If the domain's JDK cannot be read from `bin/setDomainEnv.sh` (or `.cmd`) and no server is running, it says so and writes no `--add-opens` flags; run it again while a server is up. If `nodemanager/nodemanager.properties` has `StartScriptEnabled=false`, servers started by Node Manager do not read the overrides file, and `enable` prints the `Arguments=` line to add to each server's `startup.properties` instead.

**Restart** the servers. The agent loads at JVM start and never into a running server. A server that keeps running without it is reported as *enabled, server running without the agent, restart it*; nothing is recorded there until it restarts.

**`wltrace:start`** serves the page at `http://127.0.0.1:8077/` (`-Dwltrace.port` to change it), opens it in the browser when the desktop allows and otherwise prints the URL, and blocks until Ctrl-C. It refuses when the domain is not enabled on disk.

## The page

**State bar.** One sentence about the domain, and each server JVM of the domain with its pid and whether the agent is loaded, idle or recording (with the count of traces so far). The sentence is one of: *not enabled*; *enabled, server not running*; *enabled, server running without the agent, restart it*; *disabled on disk, agent still loaded until restart*; *ready*. **Start**, with an optional recording name, begins a recording on every server of the domain under one id; **Stop** ends it. Both are disabled, with the reason shown, unless the state is *ready*. A **REC** link leads to the active recording. The bar refreshes once a second.

**Recordings.** Every recording under the output directory, newest first: name, id, start, duration, trace count, servers, a REC marker on the active one, and **Delete** on a finished one (confirmed inline; it removes the directory). Click a name to open it.

**The sequence diagram.** A recording is one continuous diagram. Lanes are the components that took part: the caller, REST resources and SOAP endpoints, session beans with their type, message-driven beans, JMS queues, data sources, grouped under their application; time runs down. Each entry point (a request, or any work nothing else in the recording caused) is a heading with its time, duration, HTTP status and principal. Under it every call is a solid arrow from caller to callee and a dashed arrow back when it returns; the bar between them is how long the callee is busy, and the duration is on the right. The HTTP status rides on the return to the caller; a failure is a red return with the exception. Work that continued on another thread (`@Asynchronous`, JMS) is drawn right under the call that started it, marked *continues on another thread*, so a message-driven bean's `onMessage` sits under the request that produced the message. SQL through a data source is a call into the data source's lane captioned with the statement, returning the rows read or the update count. Four or more consecutive identical calls that called nothing further and did not fail are one `×N` row until drawn one by one; nothing is dropped. The diagram fits the window width; arrow keys move between entry points. While a recording is active, new work appears as it completes.

**The details window.** Clicking a call opens a draggable, resizable window (**Details** toggles it): the path to the call, the facts (duration, thread, signature, interface, bean, JNDI names, data source, request), then **Input** and **Output** (arguments and result; request and response body; SQL statement with its parameters and its count), the exception, the HTTP headers, the data source, the notes from the agent, and everything else on the span. Values are shown as recorded; every marker (`@type`, `@ref`, `@lazy`, `@handle`, `@skipped`, `@truncated`, `@serializable`, `@jms`) is highlighted with a tooltip saying what it means, and red means data is missing at that point. A value that came from an earlier output carries a badge `← #n span / path` linking to where it is produced; an output that a later input used carries `→`; a value with no source is marked *userInput* or *environment*.

## Disable

```
mvn wltrace:disable -Dwltrace.domain=/path/to/domain
```

Stops an active recording first (and says so), removes the block from the overrides file, or the whole file when `enable` created it, deletes `<domain>/weblogic-trace/`, and prints the state. Recordings are never touched; the goal prints where they are. A running server keeps the agent loaded, idle, until it restarts; after that the domain is exactly as before `enable`.

## Parameters

All three are `-D` properties.

| parameter | default | meaning |
| --- | --- | --- |
| `wltrace.domain` | required | the domain home: the directory with `config/config.xml` |
| `wltrace.port` | `8077` | the page's port, on 127.0.0.1 |
| `wltrace.out` | `~/.weblogic-trace/<domain directory name>` | where recordings and `agent.log` go |

There are no others. Nothing is configured per application, type or field.

## Linux and Windows

The plugin reads the domain's platform from `bin/`: `setDomainEnv.sh` means a Linux (Unix) domain, `setDomainEnv.cmd` a Windows domain; with both present, the platform of the machine running the plugin.

| | Linux | Windows |
| --- | --- | --- |
| overrides file | `bin/setUserOverrides.sh` | `bin\setUserOverrides.cmd` |
| block markers | `# >>> weblogic-trace >>>` … `# <<< weblogic-trace <<<` | `rem >>> weblogic-trace >>>` … `rem <<< weblogic-trace <<<` |
| option lines | `JAVA_OPTIONS="${JAVA_OPTIONS} -javaagent:…"` and `export JAVA_OPTIONS` | `set JAVA_OPTIONS=%JAVA_OPTIONS% -javaagent:…` |
| JDK read from | `bin/setDomainEnv.sh` (`JAVA_HOME=`, `SUN_JAVA_HOME=`, `BEA_JAVA_HOME=`) | `bin\setDomainEnv.cmd` (`set JAVA_HOME=`, …) |

The plugin runs on the same machine as the servers, so a Windows domain is handled by a plugin running on Windows: paths keep their backslashes, an option containing a space is quoted for `cmd`, the `.cmd` file is written with CRLF, and server JVMs are matched to the domain case-insensitively.

## Recordings

One directory per recording under `wltrace.out`, named `rec-<yyyyMMdd-HHmmss>`:

- `NNNNNN-t-<id>.json`: one file per trace, written when the trace's root call completes. A trace is the work one thread did for one entry point, as a flat list of spans with parent ids. Each span records what crossed its boundary: for a request the method, URI, every header, status and both bodies; for a resource method and an EJB call the arguments and the result or exception, with the bean, interface, view, transaction attribute and JNDI names; for an SQL statement the data source, the statement, the bound parameters, and the update count or the rows read.
- `recording-<serverName>.json`: one manifest per server, with start and end, agent version and build, trace count and dropped count.
- `agent.log`, directly under `wltrace.out`: the agent's own log, with one line per written trace and every degraded lookup warned once.

Recording is raw: no masking, no allow-lists. The only transformations are the markers, each saying what stands in the value's place. Every field is described in [architecture/TRACE-FORMAT.md](architecture/TRACE-FORMAT.md).

## Limitations

- **Local domains only.** Server JVMs are found through the JDK's attach API, so the plugin runs on the same machine, as the same OS user, as the servers.
- **Enabling and disabling take effect at the next server start.** A running server is reported as such; nothing changes inside it until it restarts.
- **In-flight spans are not shown.** A trace appears when its root call completes; a request still running is not on the page.
- **Recordings are raw and therefore sensitive.** Credentials in headers, bodies and SQL parameters are recorded as they were; treat the output directory accordingly.
- **No cross-server propagation.** A call that leaves the server for another JVM (T3, JMS consumed elsewhere) starts an unrelated trace there.
- **SQL is seen through WebLogic data sources only.** A driver used directly, not through a data source, is not recorded.
