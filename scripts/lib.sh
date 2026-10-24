# Shared helpers for the scripts/ directory. Source, do not execute.

set -euo pipefail

require_env() {
  local name
  for name in "$@"; do
    if [ -z "${!name:-}" ]; then
      echo "error: \$$name is not set; run this inside 'devenv shell'" >&2
      exit 1
    fi
  done
}

require_env DEVENV_ROOT ORACLE_HOME DOMAIN_HOME WLS_JAVA_HOME

LOCAL_DIR="$DEVENV_ROOT/.local"
TMP_DIR="$LOCAL_DIR/tmp"
LOG_DIR="$LOCAL_DIR/logs"
mkdir -p "$TMP_DIR" "$LOG_DIR"

# Two labs side by side. devenv.nix carries the 14.1.2 / JDK 21 set; WLS_VERSION=12 swaps in
# 12.2.1.4 / JDK 8: its own install, domain, port, H2 (2.3 needs Java 11; 2.1 is the last for 8)
# and log files, so both domains can run at the same time.
WLS_VERSION="${WLS_VERSION:-14}"
WLS_ORAPARAM="$DEVENV_ROOT/scripts/wls-oraparam.ini"
WLS_LOG_SUFFIX=""
case "$WLS_VERSION" in
  14) ;;
  12)
    export ORACLE_HOME="$LOCAL_DIR/oracle12"
    export MW_HOME="$ORACLE_HOME"
    export WL_HOME="$ORACLE_HOME/wlserver"
    export DOMAIN_HOME="$LOCAL_DIR/domains/capture12"
    export DOMAIN_NAME="capture12"
    export WLS_ADMIN_PORT="7101"
    export WLS_JAVA_HOME="$LOCAL_DIR/jdk8"
    export WLS_INSTALLER="${WLS_INSTALLER_12:-}"
    export H2_DIR="$LOCAL_DIR/h2-12"
    export CAPTURE_OUT="$LOCAL_DIR/captures12"
    export H2_VERSION="2.1.214"
    WLS_ORAPARAM="$DEVENV_ROOT/scripts/wls-oraparam-12.ini"
    WLS_LOG_SUFFIX="12"
    ;;
  *) echo "error: WLS_VERSION must be 14 or 12, got $WLS_VERSION" >&2; exit 1 ;;
esac

# Oracle scripts read JAVA_HOME; keep them on the stable symlink managed by devenv.nix.
# commBaseEnv.sh only honours JAVA_HOME from the environment when JAVA_VENDOR is also
# set; "Oracle" selects the plain HotSpot settings (-server), which fit Temurin.
export JAVA_HOME="$WLS_JAVA_HOME"
export JAVA_VENDOR="${JAVA_VENDOR:-Oracle}"
export PATH="$JAVA_HOME/bin:$PATH"

# Run an Oracle tool inside the FHS shim from devenv.nix (provides /bin/chmod etc.).
oracle_run() {
  command -v wls-fhs-run >/dev/null || die "wls-fhs-run not on PATH; run inside 'devenv shell'"
  wls-fhs-run "$@"
}

log() {
  printf '[%s] %s\n' "$(basename "$0" .sh)" "$*" >&2
}

die() {
  log "error: $*"
  exit 1
}
