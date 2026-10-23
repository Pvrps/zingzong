#!/usr/bin/env bash
# Create the dev-mode domain in $DOMAIN_HOME with WLST, and drop boot.properties
# so startWebLogic.sh does not prompt for credentials.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env DOMAIN_NAME WLS_ADMIN_PORT WLS_LISTEN_ADDRESS WLS_ADMIN_USER WLS_ADMIN_PASSWORD

wlst="$ORACLE_HOME/oracle_common/common/bin/wlst.sh"
[ -x "$wlst" ] || die "WLST not found at $wlst; run wls-install first"

if [ -f "$DOMAIN_HOME/config/config.xml" ] && [ "${1:-}" != "--force" ]; then
  log "domain already exists at $DOMAIN_HOME (pass --force to recreate)"
  exit 0
fi

if [ -d "$DOMAIN_HOME" ]; then
  log "removing existing domain at $DOMAIN_HOME"
  rm -rf "$DOMAIN_HOME"
fi
mkdir -p "$(dirname "$DOMAIN_HOME")"

export WLST_PROPERTIES="-Dpython.cachedir=$TMP_DIR/wlst-cache ${WLST_PROPERTIES:-}"
export CONFIG_JVM_ARGS="-Djava.security.egd=file:/dev/./urandom ${CONFIG_JVM_ARGS:-}"

log "running WLST create-domain.py"
oracle_run "$wlst" "$DEVENV_ROOT/scripts/create-domain.py"

[ -f "$DOMAIN_HOME/config/config.xml" ] || die "WLST finished but $DOMAIN_HOME/config/config.xml is missing"

# WLST marks these executable through /bin/chmod; make sure regardless of the shim.
chmod u+x "$DOMAIN_HOME"/*.sh "$DOMAIN_HOME"/bin/*.sh

security_dir="$DOMAIN_HOME/servers/AdminServer/security"
mkdir -p "$security_dir"
cat > "$security_dir/boot.properties" <<EOF
username=$WLS_ADMIN_USER
password=$WLS_ADMIN_PASSWORD
EOF
chmod 600 "$security_dir/boot.properties"

log "domain created at $DOMAIN_HOME (dev mode, admin port $WLS_ADMIN_PORT)"
log "next: wls-start"
