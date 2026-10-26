# Using weblogic-trace

This guide shows how to record what happens inside a WebLogic domain while you use an application, and how to read the result. You need three commands and a browser.

## Before you start

- **WebLogic Server 12.2.1.4 on JDK 8, or 14.1.2 on JDK 21**, running `javax.*` applications. On any other JVM the agent loads but does nothing.
- **A domain you can restart.** The agent is loaded when a server starts.
- **A JDK (8 or later) and Maven 3.6+** on the machine where the servers run, as the **same OS user** that runs them. The plugin finds the servers through the JDK's attach API, which only sees that user's JVMs.

## Install the plugin

Build and install it once, from a clone of this repository:

```sh
mvn -q install
```

To use the short `wltrace:` prefix from any directory, add the plugin group to `~/.m2/settings.xml`:

```xml
<settings>
  <pluginGroups>
    <pluginGroup>ca.gc.cra.xz02.devops</pluginGroup>
  </pluginGroups>
</settings>
```

Without it, write the full name instead of `wltrace:`, for example `mvn ca.gc.cra.xz02.devops:weblogic-trace-maven-plugin:1.0.0-SNAPSHOT:enable`. Inside this repository the short form already works.

## Record

```sh
mvn wltrace:enable -Dwltrace.domain=/path/to/domain    # 1. install the agent
# 2. restart the domain's servers
mvn wltrace:start  -Dwltrace.domain=/path/to/domain    # 3. open the page
```

1. **`wltrace:enable`** copies the agent into `<domain>/weblogic-trace/` and adds one block to `<domain>/bin/setUserOverrides.sh` (`.cmd` on Windows), creating the file if needed. Nothing else in the domain changes. It ends by printing the domain's state.
2. **Restart the servers**, however you normally start them. The agent loads with the JVM; a server that was already running doesn't have it until it restarts.
3. **`wltrace:start`** serves the page at <http://127.0.0.1:8077/> and opens your browser if it can. Then:
   1. Type a name for the recording (optional) and press **Start**.
   2. Use the application.
   3. Press **Stop**.

   The recording appears in the list. Press Ctrl-C in the terminal to close the page.

Until **Start** is pressed, the agent changes nothing in the server. After **Stop**, every class is restored.

## The page

### State bar

The top bar shows the domain's state and one chip per server, with its process id and whether the agent is idle or recording (and how many traces so far). **Start** and **Stop** only work when the state is *ready*.

| state | what to do |
| --- | --- |
| *not enabled* | Run `wltrace:enable`. The message says what's missing, or that the installed agent is from another build. |
| *enabled, server not running* | Start the domain. |
| *enabled, server running without the agent, restart it* | Restart the servers it names. |
| *disabled on disk, agent still loaded until restart* | Nothing; the agent is idle. Restart to unload it, or run `enable` again. |
| *ready* | Press **Start**. |

### Recordings

The home view lists every recording, newest first: name, start, duration, number of traces, servers. Click one to open it, or **Delete** to remove its files.

### The diagram

A recording is drawn as one sequence diagram:

- **Columns** are the components that took part: the client, REST and SOAP resources, each bean, JMS queues, data sources. They're grouped by application.
- **Each entry point** (an HTTP request, a remote EJB call, a message delivery with no recorded cause) starts a numbered section with its time, duration and status.
- **Arrows** are calls and returns, in time order. A failed call is red. Return arrows show the HTTP status, the exception, or the rows an SQL statement read or changed.
- **Work on another thread** is drawn under the call that caused it: a JMS message as a send to the queue followed by the delivery to the MDB, an `@Asynchronous` call with a tag.
- **Repeated calls** (four or more identical ones in a row) are folded into one `×N` row; click it to expand.

The up and down arrow keys move between entry points.

### Details

Click any row to open its details:

- **How we got here:** the chain of calls that led to this one.
- **Facts:** bean, interface, signature, JNDI names, transaction attribute, data source, timing, thread.
- **Input, Output, Exception:** the arguments, the result and the exception, exactly as they were, as foldable JSON.
- **HTTP:** headers and both bodies.
- **Notes from the capture:** anything that was left out of the values, and why.

Keys starting with `@` are markers the recorder added, such as `@type` for the class of an object, or `@lazy` for a JPA collection that wasn't loaded. Hover over one for its meaning. The full list is in [TRACE-FORMAT.md](TRACE-FORMAT.md#markers).

**Lineage badges** show where a value came from: an order id a confirm call received, for example, carries a badge pointing to the earlier call that returned it. Values with no earlier source are marked *user input* or *environment*. Click a badge to jump to the other end.

## Parameters

| parameter | default | |
| --- | --- | --- |
| `wltrace.domain` | (required) | the domain directory, the one containing `config/config.xml` |
| `wltrace.port` | `8077` | the page's port; it listens on `127.0.0.1` only |
| `wltrace.out` | `~/.weblogic-trace/<domain directory name>` | where the agent writes recordings and `agent.log` |

`enable` writes `wltrace.out` into the domain, so the servers use the value from the last `enable`. Pass the same value to `start`, or leave it at the default for both.

## Where the recordings are

```
~/.weblogic-trace/<domain>/
├── agent.log
└── rec-20261025-142233/
    ├── recording-AdminServer.json
    ├── 000001-t-3fa2c1d0.json
    └── ...
```

One directory per recording, one manifest per server, one JSON file per trace. Recordings stay until you delete them; `disable` doesn't touch them. The format is described in [TRACE-FORMAT.md](TRACE-FORMAT.md).

## Remove the agent

```sh
mvn wltrace:disable -Dwltrace.domain=/path/to/domain
```

This stops an active recording, removes the block from the overrides script (or deletes the script, if `enable` created it), and deletes `<domain>/weblogic-trace/`. A running server keeps the agent loaded, idle, until it restarts; after that, the domain is exactly as it was before `enable`.

## Special cases

- **Several servers.** Every server of the domain that runs as your user is found. **Start** records on all of them under one recording, and the diagram shows them together.
- **Windows domains.** Detected from `bin/setDomainEnv.cmd`; the block goes into `bin\setUserOverrides.cmd`. Run the plugin on the Windows machine itself.
- **Node Manager with `StartScriptEnabled=false`.** Servers started this way don't read the overrides script. `enable` warns and prints an `Arguments=` line to add to each server's `servers/<name>/data/nodemanager/startup.properties`.
- **JDK not detected.** On JDK 9+ the agent needs a few `--add-opens` flags, which `enable` adds after reading the domain's JDK from `bin/setDomainEnv.sh`. If it can't tell and no server is running, it warns and adds none; run `enable` again while a server is up.
- **Another build of the plugin.** If the installed agent jar isn't the one this plugin ships, the state is *not enabled*. Run `enable` again and restart.
- **Closing the page.** Ctrl-C closes the page but doesn't stop a running recording. Run `start` again and press **Stop**, or run `disable`.

## What's recorded, and what isn't

Recorded: HTTP requests with headers and bodies, REST and SOAP resource methods, EJB calls (local, remote, EJB 2.x), message-driven bean deliveries, and SQL through WebLogic data sources, each with its arguments, results and exceptions.

> **Recordings contain raw data.** Passwords, tokens, cookies and personal data are recorded exactly as they crossed each call. Nothing is masked. Treat the output directory accordingly.

Not recorded, or not completely:

- `jakarta.*` applications.
- Container error pages written after the application returns. The span notes that the body is missing.
- The body of a JMS `BytesMessage` or `StreamMessage`: reading it would consume it. Its length is recorded.
- Values past the size limits: 64 MiB per HTTP body and per `byte[]`, 16 Mi characters per string, 100,000 elements per collection, 64 levels of nesting. What's cut is marked `@truncated` or `@unserializable`.
- JPA collections that weren't loaded. They're marked `@lazy` and never loaded by the agent.
- Traces dropped under heavy load. If the agent can't write fast enough, it drops traces rather than slow the server; the manifest's `droppedTraces` and the server chip show how many.

## Troubleshooting

Look in `agent.log` in the output directory:

| line | means |
| --- | --- |
| `weblogic-trace-agent ready, recording off` | the agent loaded; the server is ready |
| `weblogic-trace-agent inert: ...` | this JVM isn't a supported WebLogic server; nothing will be recorded |
| `RECORDING START` / `RECORDING STOP` | a recording started or stopped on this server, with its trace count |
| `instrumented <class>` | a hook was put in place when the recording started |
| `hook targets loaded but not instrumented` | this WebLogic build differs from the supported ones |
| `TRACE t-... -> 000001-t-....json` | one trace written |
| `WARN` or `ERROR` | something wasn't recorded; the line says what |

If no `agent.log` exists after a restart, the server didn't get the `-javaagent` option. Check the overrides script and the Node Manager case above.
