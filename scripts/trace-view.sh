#!/usr/bin/env bash
# Open a recording in the viewer: build recording-index.json when it is missing or older than
# the traces, copy trace-tools/trace-viewer/index.html beside it, serve the directory.
#
#   trace-view <recording-dir> [port]        default port 8077; then open http://localhost:8077/

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

dir="${1:?usage: trace-view <recording-dir> [port]}"
port="${2:-8077}"
[ -d "$dir" ] || die "not a directory: $dir"
viewer="$DEVENV_ROOT/trace-tools/trace-viewer/index.html"
reader="$DEVENV_ROOT/trace-tools/trace-reader/target/trace-reader.jar"
[ -f "$reader" ] || die "trace-reader.jar not built; run mvn -q -pl trace-tools/trace-reader install"

stale=0
if [ ! -f "$dir/recording-index.json" ]; then
  stale=1
elif [ -n "$(find "$dir" -maxdepth 1 -name '[0-9]*-t-*.json' -newer "$dir/recording-index.json" -print -quit)" ]; then
  stale=1
fi
if [ "$stale" = 1 ]; then
  java -jar "$reader" index "$dir"
fi
cp "$viewer" "$dir/index.html"
log "serving $dir at http://localhost:$port/ (Ctrl-C to stop)"
exec python3 -m http.server --bind 127.0.0.1 --directory "$dir" "$port"
