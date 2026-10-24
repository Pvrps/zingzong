# ejb-capture

Records what crosses the EJB and HTTP boundaries of a WebLogic domain, raw, as JSON traces,
and shows them: three Maven goals and one web page.

- `capture:enable` installs the capture agent in a domain; `capture:disable` removes it.
- `capture:start` serves a page where you start and stop captures, watch the active one as
  traces land, and view previous ones: traces grouped by session, the span tree of each
  (EJB and HTTP calls, remote hops, self-invocations, exceptions, truncation), every span's
  arguments, results, headers and bodies exactly as captured, and lineage badges that show
  where a value in a later call came from.

The agent is a `-javaagent` with no compile dependency on WebLogic; the plugin runs inside
your Maven's JDK (8 or later). Verified on WebLogic 12.2.1.4 / JDK 8 and 14.1.2 / JDK 21, with
the plugin under both a JDK 8 and a JDK 21 Maven; what differs between the two is in
`docs/weblogic-12-vs-14.md`.

## Install

Build and install the plugin and the agent into your local repository:

```
mvn -q install
```

Coordinates: `io.capture:capture-maven-plugin:0.1.0-SNAPSHOT` (the agent,
`io.capture:capture-agent`, is a dependency of the plugin and installed with it). To use the
short form `mvn capture:<goal>` outside this repository, add the plugin group to
`~/.m2/settings.xml`:

```xml
<pluginGroups>
  <pluginGroup>io.capture</pluginGroup>
</pluginGroups>
```

Inside this repository `.mvn/settings.xml` already does that.

## Daily loop

```
mvn capture:enable  -Dcapture.domain=/path/to/domain
# restart the domain's servers
mvn capture:start   -Dcapture.domain=/path/to/domain
```

1. **enable** copies the agent to `<domain>/ejb-capture/capture-agent.jar` and appends a
   delimited block (`# >>> ejb-capture >>>` … `# <<< ejb-capture <<<`) to
   `<domain>/bin/setUserOverrides.sh` that adds the `-javaagent` option (and, on a JDK 9+
   domain, the `--add-opens` flags the serializer needs). It creates the file if the domain
   has none and says so in the block. Running it again updates the block in place.
2. **Restart** the servers. The agent loads at JVM start; a server that was already running
   keeps running without it, and `enable` tells you so.
3. **start** opens `http://127.0.0.1:8077/` (`-Dcapture.port` to change) and blocks until
   Ctrl-C. The top bar shows the domain's state and each server JVM with its pid and agent
   status. **Start** (with an optional name) begins a capture on every server of the domain
   under one recording id; **Stop** ends it. Both are disabled, with the reason shown, unless
   the state is *ready*. The home view lists all recordings under the output directory,
   newest first, with a REC marker on the active one and a **Delete** per finished recording
   (confirmed inline; it removes the directory). Clicking a recording opens three panes:
   the traces grouped by HTTP session (a trace started on another thread by `@Asynchronous`
   or JMS sits indented under the trace that caused it), the **call flow** of the selected
   trace, and the selected call in full. The flow is a chart: one card per call, callers on
   the left, callees to the right in call order, elbow arrows between them; a dashed amber
   arrow is a REMOTE call and the card names the EAR it entered, a dashed violet card is a
   trace this call started on another thread (click it to follow). Every card shows its
   duration and where it sat in the trace's time. A card's `▾ n` folds the calls below it;
   four or more consecutive calls of the same method that called nothing further and did not
   fail are drawn as one `×N` card, which expands to the individual calls on click (nothing
   is dropped; the card says what it stands for). The call pane shows the facts, then
   Arguments, Result, Exception and HTTP sections with the values as captured, markers
   highlighted, and lineage links to where a value came from or went. While a recording is
   active the page refreshes once a second and new traces appear as they land.

Parameters, all `-D`: `capture.domain` (required), `capture.port` (default 8077),
`capture.out` (default `~/.ejb-capture/<domain directory name>`). There are no others; the
agent takes only its output directory.

With nothing recording, the agent has no instrumentation installed anywhere: a loaded but
idle agent changes nothing about the server.

## Disable

```
mvn capture:disable -Dcapture.domain=/path/to/domain
```

Stops an active recording first (and says so), removes the block from `setUserOverrides.sh`
(or the whole file, if `enable` created it), deletes `<domain>/ejb-capture/`, and reports.
Recordings under `capture.out` are never touched; the goal prints where they are. A running
server keeps the agent loaded, idle, until it restarts; after that the domain is exactly as
it was.

## The two running-server cases

**Enabled while a server runs.** `enable` writes the files and reports *enabled, server
running without the agent, restart it*. Nothing is captured until that restart; the page
shows Start disabled with that reason and flips to *ready* on its own once the server comes
back with the agent.

**Disabled while a server runs.** `disable` removes the files and reports *disabled on disk,
agent still loaded until restart*. The agent stays in the JVM, idle, with no advice installed;
the application runs as before and no new trace files appear.

## What a recording contains

One directory per recording (`rec-<timestamp>`), one JSON file per trace, written when the
trace's root call completes, plus one manifest per server. Every argument, result, exception,
HTTP header and body is recorded as it crossed the boundary: no masking, no allow-lists.
Cycles become `@ref`, unloaded lazy collections `@lazy`, runtime handles `@handle`, objects
that are not serializable are marked `@serializable: false`, and any safety ceiling that is
hit leaves a `@truncated` marker with counts. The page highlights all of these.

## Limitations

- Local domains only: the plugin finds server JVMs through the JDK's attach API, so it must
  run on the same machine, as the same OS user, as the servers.
- Enabling and disabling take effect at the next server start.
- In-flight spans are not shown: a trace is written when its root call completes, so a
  request still running is not on the page yet.
- Recordings are raw and therefore sensitive: credentials in headers and bodies are recorded.
  Treat the output directory accordingly.
- Session beans and servlets are captured; message-driven beans are not. Calls that leave the
  server for another JVM start an unrelated trace there.

## Repository

| module | what |
| --- | --- |
| `trace-model` | the trace JSON model (Jackson only) and its schema |
| `capture-agent` | the `-javaagent`; `--release 8`, shaded, no `javax`/`weblogic` compile dependency |
| `trace-reader` | library: loads recordings, groups sessions, detects lineage, builds the page's index |
| `capture-maven-plugin` | the three goals, the page and its server |
| `sample-apps` | the lab's EARs: REST → EJB → EJB, a remote EJB hop, a SOAP endpoint |

`docs/container-hook.md` records every WebLogic internal the agent touches, with the version
it was verified on. `docs/history/` holds the plan and status of the prototype this grew from;
everything that was removed in the move to the plugin (CLI, exporter, replay check, lab
scripts) is recoverable from the tag `v0.1-prototype`.

The home lab runs inside `devenv shell`: `wls-install`, `wls-domain`, `wls-start [-b]`,
`wls-stop`, `wls-deploy` set up and run a plain domain; `capture enable|disable|start` runs
the plugin against it with `capture.out` under `.local/captures`. There are two labs: the
default is 14.1.2 / JDK 21 on port 7001; `WLS_VERSION=12` in front of any of those commands
selects 12.2.1.4 / JDK 8 on port 7101 (its own install, domain, H2 and `capture.out`), and both
can run at once. `JAVA_HOME=$JDK8_HOME capture …` runs the plugin under a Java 8 Maven.
`WLS_VERSION=12` is a verification lab, not a daily driver: it exists to prove the plugin on the
production version, and it runs on the JDK 8 `tools.jar` attach path; day to day, use the 14 lab.
