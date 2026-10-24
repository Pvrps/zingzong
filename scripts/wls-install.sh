#!/usr/bin/env bash
# Silent install of WebLogic Server into $ORACLE_HOME from the OTN installer jar.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env WLS_INSTALLER ORACLE_INVENTORY

if [ -d "$ORACLE_HOME/wlserver" ]; then
  log "already installed at $ORACLE_HOME (delete it to reinstall)"
  exit 0
fi

[ -f "$WLS_INSTALLER" ] || die "installer not found: $WLS_INSTALLER (WLS_INSTALLER, or WLS_INSTALLER_12 for WLS_VERSION=12)"

rsp="$TMP_DIR/wls-response.rsp"
inv_ptr="$TMP_DIR/oraInst.loc"
install_tmp="$TMP_DIR/install"
mkdir -p "$ORACLE_INVENTORY" "$install_tmp"

sed "s|@ORACLE_HOME@|$ORACLE_HOME|" "$DEVENV_ROOT/scripts/wls-response.rsp.in" > "$rsp"
cat > "$inv_ptr" <<EOF
inventory_loc=$ORACLE_INVENTORY
inst_group=$(id -gn)
EOF

log "installing from $WLS_INSTALLER"
log "ORACLE_HOME=$ORACLE_HOME"
log "JAVA_HOME=$JAVA_HOME ($(readlink -f "$JAVA_HOME"))"

# -ignoreSysPrereqs: the OUI prerequisite checks look for certified distributions
# (RHEL, OL, SLES) and swap sizing; neither applies on NixOS.
# -paramFile: see scripts/wls-oraparam.ini for why OpenJDK needs its own ini.
# -novalidation: INST-07004 rejects any path component starting with a dot (.local).
oracle_run "$JAVA_HOME/bin/java" \
  -Djava.io.tmpdir="$install_tmp" \
  -jar "$WLS_INSTALLER" \
  -silent \
  -ignoreSysPrereqs \
  -novalidation \
  -paramFile "$WLS_ORAPARAM" \
  -responseFile "$rsp" \
  -invPtrLoc "$inv_ptr" \
  -logLevel info

[ -d "$ORACLE_HOME/wlserver" ] || die "install finished but $ORACLE_HOME/wlserver is missing; check the installer log above"
[ -f "$ORACLE_HOME/wlserver/server/lib/weblogic.jar" ] || die "weblogic.jar missing under $ORACLE_HOME/wlserver/server/lib"

# The installer resolves the JDK symlink and stores the /nix/store path; point it back
# at the symlink so a JDK bump does not strand the install.
global_env="$ORACLE_HOME/oui/.globalEnv.properties"
if [ -f "$global_env" ]; then
  sed -i -E "s|^(JAVA_HOME(_[0-9_]+)?)=.*|\1=$WLS_JAVA_HOME|" "$global_env"
fi

rm -rf "$install_tmp"
log "installed: $("$JAVA_HOME/bin/java" -cp "$ORACLE_HOME/wlserver/server/lib/weblogic.jar" weblogic.version 2>/dev/null | grep -m1 'WebLogic Server' || echo 'version check skipped')"
