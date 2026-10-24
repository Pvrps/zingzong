#!/usr/bin/env bash
# Build the sample EARs and (re)deploy them to the running AdminServer.
#
#   wls-deploy              build, then deploy tax-ear and orders-ear
#   wls-deploy --no-build   deploy the EARs already in target/
#   wls-deploy --undeploy   remove both applications

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env WLS_ADMIN_HOST WLS_ADMIN_PORT WLS_ADMIN_USER WLS_ADMIN_PASSWORD WL_HOME

build=1
undeploy=0
case "${1:-}" in
  --no-build) build=0 ;;
  --undeploy) undeploy=1; build=0 ;;
  "") ;;
  *) die "unknown argument: $1" ;;
esac

# The server deploys (nostage) from the path it is given and re-reads it on restart, so each
# lab gets its own copy of the EARs: a rebuild for the other JDK must not change a deployed app.
deploy_dir="$LOCAL_DIR/deploy$WLS_LOG_SUFFIX"
# Deployment order matters only at runtime (orders looks tax up by JNDI per call),
# but deploying tax-ear first keeps the first smoke test clean.
apps=(
  "tax-ear:$deploy_dir/tax-ear.ear"
  "orders-ear:$deploy_dir/orders-ear.ear"
  "notify-ear:$deploy_dir/notify-ear.ear"
)

deployer() {
  "$JAVA_HOME/bin/java" -cp "$WL_HOME/server/lib/weblogic.jar" weblogic.Deployer \
    -adminurl "t3://$WLS_ADMIN_HOST:$WLS_ADMIN_PORT" \
    -username "$WLS_ADMIN_USER" -password "$WLS_ADMIN_PASSWORD" \
    -noexit "$@"
}

if [ "$build" = "1" ]; then
  log "building sample apps"
  profile=""; [ "$WLS_VERSION" = "12" ] && profile="-Pjava8"
  # clean: switching the target release between the two labs does not invalidate compiled classes otherwise
  (cd "$DEVENV_ROOT" && mvn -q $profile -pl sample-apps/orders-ear/orders-ear,sample-apps/tax-ear/tax-ear,sample-apps/notify-ear/notify-ear -am clean package -DskipTests)
fi

if [ "$undeploy" = "0" ]; then
  mkdir -p "$deploy_dir"
  for ear in tax-ear orders-ear notify-ear; do
    built="$DEVENV_ROOT/sample-apps/$ear/$ear/target/$ear.ear"
    [ -f "$built" ] || die "missing $built; run without --no-build"
    cp "$built" "$deploy_dir/$ear.ear"
  done
fi

deployed=$(deployer -listapps 2>/dev/null | sed -E 's/^[[:space:]]+|[[:space:]]+$//g' || true)

if [ "$undeploy" = "1" ]; then
  for ((i = ${#apps[@]} - 1; i >= 0; i--)); do
    name="${apps[$i]%%:*}"
    if grep -qx "$name" <<<"$deployed"; then
      log "undeploying $name"
      deployer -undeploy -name "$name"
    fi
  done
  exit 0
fi

for entry in "${apps[@]}"; do
  name="${entry%%:*}"
  source="${entry#*:}"
  [ -f "$source" ] || die "missing $source; run without --no-build"
  if grep -qx "$name" <<<"$deployed"; then
    log "redeploying $name"
    deployer -redeploy -name "$name" -source "$source"
  else
    log "deploying $name"
    deployer -deploy -name "$name" -source "$source" -targets AdminServer
  fi
done

log "deployed; open http://$WLS_ADMIN_HOST:$WLS_ADMIN_PORT/orders/"
