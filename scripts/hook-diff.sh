#!/usr/bin/env bash
# Compare two recordings of the same scenario trace by trace, e.g. one taken under the
# container hook and one under hook=annotation (Phase 6 equivalence check).
#
#   hook-diff <recording-dir-A> <recording-dir-B>
#
# Traces are normalised with hook-diff.jq before diffing: span ids, timings, threads and
# values that differ between any two runs (order ids, timestamps, UUIDs) are blanked, and a
# JAX-RS Response result is reduced to its type, status and entity (Jersey's internals are
# hash-ordered). Nothing hook-specific is removed: spans only one hook produces (the
# annotation hook's selfInvocation spans) show up as differences and are listed.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

a="${1:?usage: hook-diff <recording-dir-A> <recording-dir-B>}"
b="${2:?usage: hook-diff <recording-dir-A> <recording-dir-B>}"
jqfile="$(dirname "${BASH_SOURCE[0]}")/hook-diff.jq"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

hook_of() { jq -r '.agent.hook // "?"' "$1/recording.json" 2>/dev/null; }
log "A=$a (hook=$(hook_of "$a"))  B=$b (hook=$(hook_of "$b"))"

status=0
for fa in "$a"/[0-9]*-t-*.json; do
  seq="$(basename "$fa" | cut -d- -f1)"
  fb="$(ls "$b"/"$seq"-t-*.json 2>/dev/null | head -1)"
  if [ -z "$fb" ]; then log "trace $seq: missing in B"; status=1; continue; fi
  jq -S -f "$jqfile" "$fa" > "$work/a.json"
  jq -S -f "$jqfile" "$fb" > "$work/b.json"
  diff "$work/a.json" "$work/b.json" > "$work/diff" || true
  lines="$(grep -c '^[<>]' "$work/diff" || true)"
  echo "trace $seq: $(jq '.spans|length' "$work/a.json") vs $(jq '.spans|length' "$work/b.json") spans, $lines differing lines"
  if [ "$lines" != "0" ]; then
    status=1
    for side in a b; do
      f="$fa"; [ "$side" = b ] && f="$fb"
      jq -r --arg side "$side" '.spans[] | select(.selfInvocation == true)
        | "  only possible in \($side): selfInvocation \(.ejb.beanName).\(.ejb.method) under \(.parentId)"' "$f"
    done
    grep -E '^[<>]\s+"(method|selfInvocation|interface|invocation|transactionAttribute|beanClass)"' "$work/diff" | sed 's/^/  /' | sort | uniq -c | sort -rn | head -20
  fi
done
exit $status
