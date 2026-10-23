#!/usr/bin/env bash
# Phase 6 JNDI proof: take a captured createOrder span, look up its first JNDI name over T3
# with the dev credentials and call findOrder with the captured order id (scripts/JndiProof.java).
#
#   jndi-proof <trace-file.json>        a trace whose tree contains OrderService.createOrder,
#                                       recorded through the remote interface (e.g. the Phase 1
#                                       T3 client), so that jndiNames[0] is a remote view
#
# Needs the sample apps built (common-dto, orders-api jars) and the server running.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env WLS_ADMIN_HOST WLS_ADMIN_PORT WLS_ADMIN_USER WLS_ADMIN_PASSWORD

trace="${1:?usage: jndi-proof <trace-file.json>}"
m2="$LOCAL_DIR/m2"
samples="$DEVENV_ROOT/sample-apps"
cp="$ORACLE_HOME/wlserver/server/lib/wlthint3client.jar"
cp="$cp:$ORACLE_HOME/oracle_common/modules/oracle.toplink/eclipselink.jar"   # IndirectList in Order.lines
cp="$cp:$(ls "$samples"/common-dto/target/common-dto-*.jar | head -1)"
cp="$cp:$(ls "$samples"/orders-api/target/orders-api-*.jar | head -1)"
cp="$cp:$(ls "$m2"/javax/javaee-api/*/javaee-api-*.jar | head -1)"
for a in jackson-databind jackson-core jackson-annotations; do
  cp="$cp:$(ls "$m2"/com/fasterxml/jackson/core/$a/*/$a-*.jar | grep -v sources | head -1)"
done

exec "$JAVA_HOME/bin/java" -cp "$cp" "$(dirname "${BASH_SOURCE[0]}")/JndiProof.java" \
  "$trace" "t3://$WLS_ADMIN_HOST:$WLS_ADMIN_PORT" "$WLS_ADMIN_USER" "$WLS_ADMIN_PASSWORD"
