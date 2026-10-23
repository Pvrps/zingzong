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
