# The Maven plugin

This document explains how the plugin's three goals work: how `enable` and `disable` change a domain, how the plugin finds the domain's running servers and talks to their agents, how it decides the domain's state, and how a recording is started on every server at once. How to *use* the goals is in [USAGE.md](../USAGE.md).

## Why a Maven plugin

The people who record are Java developers who already have a JDK and Maven. A plugin needs no installer: `mvn wltrace:enable` resolves the plugin and, as one of its dependencies, the exact agent jar it was built with. None of the goals needs a Maven project (`requiresProject = false`), so they run from any directory.

## The goals

| goal | class | does |
| --- | --- | --- |
| `wltrace:enable` | [`EnableMojo`][EnableMojo] | installs the agent jar in the domain and adds the `-javaagent` option to the overrides script |
| `wltrace:start` | [`StartMojo`][StartMojo] | serves the page on `127.0.0.1` and blocks until Ctrl-C |
| `wltrace:disable` | [`DisableMojo`][DisableMojo] | stops an active recording, removes the option and the jar |

[`AbstractTraceMojo`][AbstractTraceMojo] holds what they share:
- **the three parameters:** `wltrace.domain` (required), `wltrace.port` (default `8077`), `wltrace.out` (default `~/.weblogic-trace/<domain directory name>`);
- checking that the domain has `config/config.xml`;
- finding the agent jar among the plugin's own resolved artifacts, so the user never gives its path;
- building the [`DomainStateMonitor`][DomainStateMonitor].

The plugin changes nothing in a domain except the overrides script and `<domain>/weblogic-trace/`.

## The domain on disk

[`Domain`][Domain] knows the paths: `config/config.xml` (and the WebLogic version in it), `bin/setUserOverrides.*`, `bin/setDomainEnv.*`, `nodemanager/nodemanager.properties`, and the install directory `<domain>/weblogic-trace/`.

### Linux or Windows

[`DomainPlatform.detect`][DomainPlatform] looks at `bin/`:

| `bin/` has | platform |
| --- | --- |
| only `setDomainEnv.sh` | [`UnixPlatform`][UnixPlatform] |
| only `setDomainEnv.cmd` | [`WindowsPlatform`][WindowsPlatform] |
| both or neither | the OS the plugin runs on |

The platform answers every question that differs between the two: the overrides file name, comment syntax, line endings, how to append to `JAVA_OPTIONS` and how to quote, whether the file needs `export` and the executable bit, how a JDK path is assigned in `setDomainEnv`, and whether paths compare case-insensitively. No other class asks which platform it's on.

### The domain's JDK

The `--add-opens` flags are needed on JDK 9+ only, and a JDK 8 server refuses them. [`DomainJdk`][DomainJdk] finds the last literal `JAVA_HOME` (or `SUN_JAVA_HOME`, `BEA_JAVA_HOME`) assignment in `setDomainEnv` and reads the major version from that JDK's `release` file, falling back to `rt.jar` meaning JDK 8. If that fails, `enable` asks a running server for its `java.specification.version`. If no answer is found, `enable` writes no flags and warns.

### The overrides block

[`OverridesBlock`][OverridesBlock] writes a delimited block at the end of `bin/setUserOverrides.sh`, or the `.cmd` equivalent:

```sh
# >>> weblogic-trace >>>
# Added by wltrace:enable (ca.gc.cra.xz02.devops:weblogic-trace-maven-plugin). Removed by wltrace:disable.
JAVA_OPTIONS="${JAVA_OPTIONS} -javaagent:<domain>/weblogic-trace/weblogic-trace-agent.jar=out=<out>"
# JDK 9+: reflective access the serializer needs to read JDK value types as fields.
JAVA_OPTIONS="${JAVA_OPTIONS} --add-opens=java.base/java.lang=ALL-UNNAMED"
JAVA_OPTIONS="${JAVA_OPTIONS} --add-opens=java.base/java.util=ALL-UNNAMED"
JAVA_OPTIONS="${JAVA_OPTIONS} --add-opens=java.base/java.time=ALL-UNNAMED"
JAVA_OPTIONS="${JAVA_OPTIONS} --add-opens=java.sql/java.sql=ALL-UNNAMED"
JAVA_OPTIONS="${JAVA_OPTIONS} --add-opens=java.logging/java.util.logging=ALL-UNNAMED"
export JAVA_OPTIONS
# <<< weblogic-trace <<<
```

It's built to be reversible exactly:

- **The file doesn't exist:** `enable` creates it (with `#!/bin/sh`, made executable on Linux) and marks the block "weblogic-trace created this file". `disable` deletes the file.
- **The file exists:** the block is appended after one blank line. If the file didn't end with a newline, the block notes that, and `disable` restores it. `disable` removes exactly the block and the separator.
- **Running `enable` again** replaces the block in place.
- **Writes are atomic:** to `<file>.weblogic-trace.tmp`, keeping the file's permissions, then renamed over the original.

### The agent jar

`enable` copies the jar to `<domain>/weblogic-trace/weblogic-trace-agent.jar` by writing a `.tmp` file and renaming it over the old one. A running server has the old jar open and mapped; overwriting it in place would corrupt the classes that server still loads. A rename leaves the old file intact for the running server, and the new one is used from the next restart.

### Node Manager

When `nodemanager/nodemanager.properties` has `StartScriptEnabled=false` ([`NodeManagerSettings`][NodeManagerSettings]), servers started by Node Manager never run the overrides script. `enable` then prints the `Arguments=` line to add to each server's `servers/<name>/data/nodemanager/startup.properties`.

## Finding the servers

The plugin runs on the same machine as the servers and finds them through the attach API ([CONCEPTS.md](CONCEPTS.md#attach-api)), with no port or credentials:

1. [`LocalJvms`][LocalJvms] lists the JVMs of the current OS user. On JDK 8 it loads the attach API from `tools.jar`.
2. [`DomainStateMonitor`][DomainStateMonitor] keeps those whose main class is `weblogic.Server`, and whose `weblogic.RootDirectory` (or working directory) is this domain. The server's name is its `weblogic.Name`.
3. For each, `LocalJvms` gets the JVM's local JMX address, starting its local management agent if needed, and [`AgentControl`][AgentControl] connects to it and looks for the agent's MBean `ca.gc.cra.xz02.devops.wltrace:type=Control`.

Connections are kept and reused. A server that goes away is dropped, a failed connection is retried every 2 seconds, and a JVM that isn't one of this domain's servers is remembered and skipped.

## The domain's state

[`DomainStateMonitor.evaluate`][DomainStateMonitor] combines the disk and the running servers:

- **on disk:** is the block present; is the jar present; is the jar the same build as the plugin's, compared by the `build=` stamp in `build.properties` inside each jar;
- **per server:** is the agent loaded; its build; is it recording, and how many traces so far.

The result is a [`DomainState`][DomainState], one of five kinds:

| kind | means | message tells you |
| --- | --- | --- |
| `NOT_ENABLED` | the block or jar is missing, or the jar is another build | which part is missing, or to run `enable` again |
| `ENABLED_SERVER_DOWN` | enabled on disk, no server running | to start the domain |
| `ENABLED_SERVER_NO_AGENT` | enabled on disk, a server is running without the agent | which servers to restart |
| `DISABLED_AGENT_LOADED` | not enabled on disk, a server still has the agent loaded | that the agent stays idle until restart |
| `READY` | enabled, every running server has the agent | the servers and any active recording |

`enable` and `disable` print it, and the page shows it in its state bar ([`MonitoredDomainControl`][MonitoredDomainControl] translates it for the viewer).

## Starting a recording

[`DomainStateMonitor.startRecording`][DomainStateMonitor] runs only when the state is `READY` and nothing is recording:

1. Generates the id `rec-yyyyMMdd-HHmmss`, adding `-2`, `-3`, ... if a directory of that name exists in the output directory.
2. Calls `start(id, name)` on the MBean of every server with the agent. Every server records under the same id into the same directory.
3. Reports every server that failed.

`stopRecording` calls `stop()` on every server. `disable` stops an active recording before removing anything.

## `wltrace:start`

[`StartMojo`][StartMojo] refuses unless the domain is enabled on disk. It starts [`ViewerServer`][ViewerServer] on `127.0.0.1:<port>`, giving it the output directory and a [`MonitoredDomainControl`][MonitoredDomainControl] for the state bar and the Start and Stop buttons. It opens a browser when the desktop allows, otherwise prints the URL, and blocks until Ctrl-C. Ctrl-C closes the page; it doesn't stop a recording that is still running. The viewer itself is in [VIEWER.md](VIEWER.md).

[AbstractTraceMojo]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/mojo/AbstractTraceMojo.java
[EnableMojo]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/mojo/EnableMojo.java
[DisableMojo]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/mojo/DisableMojo.java
[StartMojo]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/mojo/StartMojo.java
[Domain]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/domain/Domain.java
[DomainPlatform]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/domain/DomainPlatform.java
[UnixPlatform]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/domain/UnixPlatform.java
[WindowsPlatform]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/domain/WindowsPlatform.java
[DomainJdk]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/domain/DomainJdk.java
[OverridesBlock]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/domain/OverridesBlock.java
[NodeManagerSettings]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/domain/NodeManagerSettings.java
[LocalJvms]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/jvm/LocalJvms.java
[AgentControl]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/jvm/AgentControl.java
[DomainStateMonitor]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/jvm/DomainStateMonitor.java
[DomainState]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/jvm/DomainState.java
[MonitoredDomainControl]: ../../weblogic-trace-maven-plugin/src/main/java/ca/gc/cra/xz02/devops/wltrace/plugin/jvm/MonitoredDomainControl.java
[ViewerServer]: ../../weblogic-trace-viewer/src/main/java/ca/gc/cra/xz02/devops/wltrace/viewer/web/ViewerServer.java
