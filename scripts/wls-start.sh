#!/usr/bin/env bash
# Start the AdminServer as a plain domain. The capture agent is installed by `capture enable`
# (bin/setUserOverrides.sh), never by this script.
#
#   wls-start            foreground, Ctrl-C stops the server
#   wls-start -b         background; waits until the server reports RUNNING

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env WLS_ADMIN_HOST WLS_ADMIN_PORT

[ -f "$DOMAIN_HOME/config/config.xml" ] || die "no domain at $DOMAIN_HOME; run wls-domain first"

background=0
case "${1:-}" in
  -b|--background) background=1 ;;
  "") ;;
  *) die "unknown argument: $1" ;;
esac

pid_file="$LOG_DIR/wls$WLS_LOG_SUFFIX.pid"
out_file="$LOG_DIR/wls-start$WLS_LOG_SUFFIX.out"

if [ -f "$pid_file" ] && kill -0 "$(cat "$pid_file")" 2>/dev/null; then
  die "server already running with pid $(cat "$pid_file")"
fi

java_options="${JAVA_OPTIONS:-}"
java_options="$java_options -Djava.security.egd=file:/dev/./urandom"
# Lab only: the self-tuning pool starts at one thread and grows slowly, which serialises the
# concurrency checks (scripts/load.sh); a floor makes 8 parallel clients run on 8 threads.
java_options="$java_options -Dweblogic.threadpool.MinPoolSize=16"

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
