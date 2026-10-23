#!/usr/bin/env bash
# Drive the agent's RecordingControl MBean in the running server.
#
#   capture.sh start <name>   begin a recording window
#   capture.sh stop           end it
#   capture.sh status
#
# Uses the JDK's local JMX connector: `jcmd ManagementAgent.start_local` needs no server
# flags and is reachable only by the same OS user on this machine.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env CAPTURE_AGENT_JAR

command="${1:-}"
case "$command" in
  start|stop|status) shift ;;
  *) die "usage: capture.sh start <name> | stop | status" ;;
esac

[ -f "$CAPTURE_AGENT_JAR" ] || die "agent jar not built: $CAPTURE_AGENT_JAR (mvn -pl capture-agent -am package)"

pid=$("$JAVA_HOME/bin/jcmd" -l | awk '/weblogic\.Server/ { print $1; exit }')
[ -n "$pid" ] || die "no running weblogic.Server JVM found (jcmd -l)"

"$JAVA_HOME/bin/jcmd" "$pid" ManagementAgent.start_local >/dev/null
url=$("$JAVA_HOME/bin/jcmd" "$pid" ManagementAgent.status | sed -n 's/^URL *: *//p' | head -1)
[ -n "$url" ] || die "could not read the local JMX connector URL from jcmd $pid ManagementAgent.status"

exec "$JAVA_HOME/bin/java" -cp "$CAPTURE_AGENT_JAR" io.capture.agent.control.ControlClient "$url" "$command" "$@"
