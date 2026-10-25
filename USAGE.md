# Using weblogic-trace

## Contents

1. [Install](#1-install)
2. [Enable, restart, start](#2-enable-restart-start)
3. [The page](#3-the-page)
4. [Disable](#4-disable)
5. [The two running-server cases](#5-the-two-running-server-cases)
6. [Parameters](#6-parameters)
7. [Linux and Windows domains](#7-linux-and-windows-domains)
8. [Recordings](#8-recordings)
9. [Limitations](#9-limitations)

## 1. Install

A JDK (8 or later) and Maven build and install the plugin and the agent into the local repository;
the unit tests run offline, without a server:

```
mvn -q install
```

Coordinates: `ca.gc.cra.xz02.devops:weblogic-trace-maven-plugin:1.0.0-SNAPSHOT`. The agent,
`ca.gc.cra.xz02.devops:weblogic-trace-agent`, is a dependency of the plugin and is installed with it; it is never
given as a path. The short form `mvn wltrace:<goal>` needs the plugin group in
`~/.m2/settings.xml`:

```xml
<pluginGroups>
  <pluginGroup>ca.gc.cra.xz02.devops</pluginGroup>
</pluginGroups>
```

Without it, use the full form `mvn ca.gc.cra.xz02.devops:weblogic-trace-maven-plugin:1.0.0-SNAPSHOT:<goal>`. The
goals need no project; run them from any directory.

## 2. Enable, restart, start

```
mvn wltrace:enable -Dwltrace.domain=/path/to/domain
# restart the domain's servers
mvn wltrace:start  -Dwltrace.domain=/path/to/domain
```

**enable** copies the agent to `<domain>/weblogic-trace/weblogic-trace-agent.jar` and writes a delimited
block into the domain's overrides file (`bin/setUserOverrides.sh` or `bin\setUserOverrides.cmd`,
section 7) that adds the `-javaagent` option and, on a JDK 9 or later domain, the `--add-opens`
flags the serializer needs. It creates the file when the domain has none and says so in the
block. Running it again updates the block in place. The domain's JDK is read from
`bin/setDomainEnv.sh` or `.cmd`; when that fails and a server is running, from the server.

**Restart** the servers. The agent loads at JVM start. A server that was already running keeps
running without it, and `enable` says so.

**start** serves `http://127.0.0.1:8077/` (`-Dwltrace.port` to change), opens it in the browser
when the desktop allows and otherwise prints the URL, and blocks until Ctrl-C. It refuses when the
domain is not enabled on disk.

If `nodemanager/nodemanager.properties` has `StartScriptEnabled=false`, servers started by Node
Manager do not read the overrides file; `enable` prints the `Arguments=` line to add to each
server's `startup.properties` instead.

## 3. The page

**State bar.** The domain's state in one sentence, and each server JVM of the domain with its pid
and whether the agent is loaded, idle or recording. **Start** (with an optional name) begins a
capture on every server under one recording id; **Stop** ends it. Both are disabled, with the
reason shown, unless the state is *ready*. A **REC** link leads to the active recording.

**Home.** Every recording under the output directory, newest first: name, id, start, duration,
trace count, servers, a REC marker on the active one, and **Delete** on a finished one (confirmed
inline; it removes the directory).

**The sequence diagram.** A recording is one continuous diagram. Lanes are the components that
took part: the client, REST resources and SOAP endpoints, session beans with their type,
message-driven beans, JMS queues, data sources, grouped under their application; time runs down.
Each entry point (a request, or any work nothing in the recording caused) is a heading with its
time, duration, HTTP status and principal. Under it every call is a solid arrow from caller to
callee and a dashed arrow back when it returns; the bar between them is how long the callee was
busy. The HTTP status rides on the return to the client; a failure is a red return with the
exception. Work that continued on another thread (`@Asynchronous`, JMS) is drawn right under the
call that started it, marked *continues on another thread*, so an MDB's `onMessage` sits under
the request that produced the message. SQL through a data source is a call into the data source's
lane labelled with the statement, returning the rows read or the update count. Four or more
consecutive identical calls that called nothing further and did not fail are one `×N` row until
drawn one by one; nothing is dropped. The diagram fits the window width. Arrow keys move between
entry points.

**The details window.** Clicking a call opens a draggable, resizable window: how we got here, the
facts (duration, thread, signature, interface, bean, JNDI names, data source, request), then
Input and Output (arguments and result, request and response body, SQL statement with its
parameters and its count), the exception, the HTTP headers, the data source, the capture notes,
and everything else on the span. Values are shown as captured; every marker (`@type`, `@ref`,
`@lazy`, `@handle`, `@skipped`, `@truncated`, `@serializable`, `@jms`) is highlighted, with a
tooltip saying what it means; red means data is missing at that point.

**Lineage links.** A value that came from an earlier output carries a badge `← #n span / path`
linking to where it was produced; an output that a later input used carries `→`. A value with no
source is classified *userInput* or *environment*.

**Live refresh.** While a recording is active the page polls once a second; new work appears as it
completes, and the home list updates.

## 4. Disable

```
mvn wltrace:disable -Dwltrace.domain=/path/to/domain
```

Stops an active recording first (and says so), removes the block from the overrides file, or the
whole file when `enable` created it, deletes `<domain>/weblogic-trace/`, and reports. Recordings are
never touched; the goal prints where they are. A running server keeps the agent loaded, idle,
until it restarts; after that the domain is exactly as it was.

## 5. The two running-server cases

**Enabled while a server runs.** `enable` writes the files and reports *enabled, server running
without the agent, restart it*. Nothing is captured until that restart; the page shows Start
disabled with that reason and flips to *ready* on its own once the server comes back with the
agent.

**Disabled while a server runs.** `disable` removes the files and reports *disabled on disk, agent
still loaded until restart*. The agent stays in the JVM, idle, with no advice installed; the
application runs as before and no new trace files appear.

## 6. Parameters

All `-D`:

| parameter | | default |
| --- | --- | --- |
| `wltrace.domain` | the domain home, the directory with `config/config.xml` | required |
| `wltrace.port` | the page's port on 127.0.0.1 | `8077` |
| `wltrace.out` | where recordings and `agent.log` go | `~/.weblogic-trace/<domain directory name>` |

There are no others. The agent takes only its output directory, written by `enable`; nothing is
configured per application, type or field.

## 7. Linux and Windows domains

The plugin reads the domain's platform from `bin/`: `setDomainEnv.sh` means a Linux (Unix) domain,
`setDomainEnv.cmd` a Windows domain; with both present, the platform of the machine running the
plugin.

| | Linux | Windows |
| --- | --- | --- |
| overrides file | `bin/setUserOverrides.sh` | `bin\setUserOverrides.cmd` |
| block markers | `# >>> weblogic-trace >>>` … `# <<< weblogic-trace <<<` | `rem >>> weblogic-trace >>>` … `rem <<< weblogic-trace <<<` |
| option lines | `JAVA_OPTIONS="${JAVA_OPTIONS} -javaagent:…"`, `export JAVA_OPTIONS` | `set JAVA_OPTIONS=%JAVA_OPTIONS% -javaagent:…` |
| JDK read from | `bin/setDomainEnv.sh` (`JAVA_HOME=`, `SUN_JAVA_HOME=`, `BEA_JAVA_HOME=`) | `bin\setDomainEnv.cmd` (`set JAVA_HOME=`, …) |

The plugin runs on the same machine as the servers, so a Windows domain is handled by a plugin
running on Windows: paths keep their backslashes, an option containing a space is quoted for cmd,
the `.cmd` file is written with CRLF, and server JVMs are matched to the domain case-insensitively.

The Windows path is covered by unit tests on the `.cmd` syntax and the platform detection; its
first run against a real Windows domain is still to be done. The checks for that run:

1. `mvn wltrace:enable -Dwltrace.domain=C:\...\base_domain`: `bin\setUserOverrides.cmd` exists (or
   has the block appended), contains the `rem >>> weblogic-trace >>>` block with `set JAVA_OPTIONS=`
   lines and, on a JDK 9+ domain, the five `--add-opens` lines; `weblogic-trace\weblogic-trace-agent.jar` is
   in the domain; the output says which `setDomainEnv.cmd` assignment gave the JDK.
2. Restart the server with `bin\startWebLogic.cmd`: `agent.log` under `wltrace.out` shows
   `premain` and `weblogic-trace-agent ready`; the server starts without errors.
3. `mvn wltrace:start`: the state is *ready* with the server's pid; the browser opens or the URL is
   printed.
4. Record: Start, a few requests, Stop; the traces appear on the page with EJB, HTTP and SQL spans.
5. `mvn wltrace:disable`: the block is gone, or the file when `enable` created it;
   `weblogic-trace\` is gone; `bin\` lists exactly what it listed before step 1.
6. A domain path with a space (`C:\Program Files\...`): the `-javaagent` option in the block is
   quoted and the server still starts.

## 8. Recordings

One directory per recording under `wltrace.out`, `rec-<yyyyMMdd-HHmmss>`:

- `NNNNNN-t-<id>.json`: one file per trace, written when the trace's root call completes. A trace
  is the work one thread did for one entry point: a flat list of spans with parent ids. Each span
  records what crossed its boundary: for an HTTP root the method, URI, every header, status and
  both bodies; for a resource method and an EJB call the arguments and the result or exception,
  with the bean, interface, view, transaction attribute and JNDI names; for an SQL statement the
  data source, the statement, the bound parameters, and the update count or the rows read.
- `recording-<serverName>.json`: one manifest per server, with start and end, agent version and
  build, trace count and dropped count.
- `agent.log` in `wltrace.out`: the agent's own log, every degraded lookup warned once.

Capture is raw: no masking, no allow-lists. Cycles become `@ref`, unloaded lazy collections
`@lazy`, runtime handles `@handle`, non-serializable objects carry `@serializable: false`, fields
the walker cannot write are listed under `@skipped` with a reason, and a safety ceiling that is
hit leaves `@truncated` with counts. The format is described by `weblogic-trace-model`'s `trace.schema.json`.

## 9. Limitations

- Local domains only: server JVMs are found through the JDK's attach API, so the plugin runs on
  the same machine, as the same OS user, as the servers.
- Enabling and disabling take effect at the next server start.
- In-flight spans are not shown: a trace appears when its root call completes.
- Calls that leave the server for another JVM start an unrelated trace there.
- SQL is seen through WebLogic data sources only; a driver used directly is not.
- Recordings are raw and therefore sensitive: credentials in headers, bodies and SQL parameters are
  recorded. Treat the output directory accordingly.
- A Windows domain is handled by code verified by unit tests on the `.cmd` syntax; the first run
  against a real Windows domain is still to be done (section 7).
