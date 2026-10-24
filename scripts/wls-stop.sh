#!/usr/bin/env bash
# Stop the AdminServer gracefully via stopWebLogic.sh, falling back to the pid.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env WLS_ADMIN_HOST WLS_ADMIN_PORT WLS_ADMIN_USER WLS_ADMIN_PASSWORD

pid_file="$LOG_DIR/wls$WLS_LOG_SUFFIX.pid"
stop_script="$DOMAIN_HOME/bin/stopWebLogic.sh"

if [ -x "$stop_script" ]; then
  export wlsUserID="$WLS_ADMIN_USER"
  export wlsPassWord="$WLS_ADMIN_PASSWORD"
  export WLST_PROPERTIES="-Dpython.cachedir=$TMP_DIR/wlst-cache ${WLST_PROPERTIES:-}"
  log "stopping via stopWebLogic.sh"
  "$stop_script" "$WLS_ADMIN_USER" "$WLS_ADMIN_PASSWORD" "t3://$WLS_ADMIN_HOST:$WLS_ADMIN_PORT" || log "stopWebLogic.sh failed"
fi

if [ -f "$pid_file" ]; then
  pid=$(cat "$pid_file")
  for ((i = 0; i < 60; i++)); do
    kill -0 "$pid" 2>/dev/null || break
    sleep 1
  done
  if kill -0 "$pid" 2>/dev/null; then
    log "pid $pid still alive, sending SIGTERM"
    kill "$pid" 2>/dev/null || true
    sleep 5
    kill -0 "$pid" 2>/dev/null && kill -9 "$pid" 2>/dev/null || true
  fi
  rm -f "$pid_file"
fi

log "stopped"
