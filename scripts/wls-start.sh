#!/usr/bin/env bash
# Start the AdminServer, wiring in the capture agent when its jar exists.
#
#   wls-start            foreground, Ctrl-C stops the server
#   wls-start -b         background; waits until the server reports RUNNING
#
# Set CAPTURE_AGENT=0 to start without the agent even if the jar is built.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env WLS_ADMIN_HOST WLS_ADMIN_PORT CAPTURE_OUT CAPTURE_AGENT_JAR

[ -f "$DOMAIN_HOME/config/config.xml" ] || die "no domain at $DOMAIN_HOME; run wls-domain first"

background=0
case "${1:-}" in
  -b|--background) background=1 ;;
  "") ;;
  *) die "unknown argument: $1" ;;
esac

pid_file="$LOG_DIR/wls.pid"
out_file="$LOG_DIR/wls-start.out"

if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
  die "server already running with pid $(cat "$pid_file")"
fi

mkdir -p "$CAPTURE_OUT"

java_options="${JAVA_OPTIONS:-}"
java_options="$java_options -Djava.security.egd=file:/dev/./urandom"

# Reflection into JDK internals by the serializer (Phase 2). Harmless without the agent.
# Omit these on a JDK 8 domain.
java_options="$java_options --add-opens=java.base/java.lang=ALL-UNNAMED"
java_options="$java_options --add-opens=java.base/java.util=ALL-UNNAMED"
java_options="$java_options --add-opens=java.base/java.time=ALL-UNNAMED"
java_options="$java_options --add-opens=java.sql/java.sql=ALL-UNNAMED"
java_options="$java_options --add-opens=java.logging/java.util.logging=ALL-UNNAMED"

if [ "${CAPTURE_AGENT:-1}" != "0" ] && [ -f "$CAPTURE_AGENT_JAR" ]; then
  log "attaching agent $CAPTURE_AGENT_JAR"
  java_options="$java_options -javaagent:$CAPTURE_AGENT_JAR=out=$CAPTURE_OUT"
else
  log "starting without capture agent"
fi

export JAVA_OPTIONS="$java_options"
export WLS_REDIRECT_LOG=""

start_script="$DOMAIN_HOME/bin/startWebLogic.sh"

if [ "$background" = "0" ]; then
  exec "$start_script"
fi

: > "$out_file"
nohup "$start_script" > "$out_file" 2>&1 &
echo $! > "$pid_file"
log "started pid $(cat "$pid_file"), output in $out_file"

timeout_s=${WLS_START_TIMEOUT:-240}
for ((i = 0; i < timeout_s; i++)); do
  if grep -q 'RUNNING mode' "$out_file" 2>/dev/null; then
    log "server is RUNNING on t3://$WLS_ADMIN_HOST:$WLS_ADMIN_PORT"
    exit 0
  fi
  if ! kill -0 "$(cat "$pid_file")" 2>/dev/null; then
    rm -f "$pid_file"
    tail -n 40 "$out_file" >&2
    die "server process exited before reaching RUNNING; see $out_file"
  fi
  sleep 1
done

tail -n 40 "$out_file" >&2
die "server did not reach RUNNING within ${timeout_s}s; see $out_file"
