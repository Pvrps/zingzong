#!/usr/bin/env bash
# Mechanical checks on a recording made under concurrent load (scripts/load.sh):
#   1. every trace's spans ran on one thread
#   2. every span's parent is a span of the same trace, one level up
#   3. every confirmOrder names an order that a createOrder of the same customer produced
#      (the EJB argument and the URL path agree, and the customer name equals confirmedBy)
#   4. the counts add up: as many confirmOrder as createOrder spans, each at most once per trace
# Exit status 1 on any violation. Needs jq.
#
#   check-recording <recording-dir> [expected-pairs]

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"

dir="${1:?usage: check-recording <recording-dir> [expected-pairs]}"
expected="${2:-}"
[ -d "$dir" ] || die "not a directory: $dir"

report=$(jq -s '
  def ejb(m): [.spans[] | select(.ejb != null and .ejb.method == m)];
  def fail(msg): {ok: false, msg: msg};
  def ok: {ok: true};

  (map(select(.spans != null))) as $traces
  | ($traces | map({id: .trace.id, threads: ([.spans[].thread] | unique)}) | map(select(.threads | length > 1))) as $multiThread
  | ($traces | map(. as $t | ($t.spans | map({key: .id, value: .depth}) | from_entries) as $ids
      | [$t.spans[] | select(.parentId != null) | select(($ids[.parentId] == null) or ($ids[.parentId] + 1 != .depth)) | {trace: $t.trace.id, span: .id, parent: .parentId}]) | add) as $badParents
  | ($traces | map(ejb("createOrder")[] | {name: .args[0].value.customer.name, id: .result.value.id}) ) as $creates
  | ($creates | map({key: (.id|tostring), value: .name}) | from_entries) as $byId
  | ($traces | map(. as $t | ejb("confirmOrder")[] | {trace: $t.trace.id, who: .args[0].value.confirmedBy, id: .args[0].value.orderId,
        url: ($t.spans[0].http.path // "" | capture("/orders/(?<n>[0-9]+)/confirm").n // null)})) as $confirms
  | ($confirms | map(select($byId[(.id|tostring)] != .who or (.url != null and (.url|tonumber) != .id)))) as $wrongLink
  | ($traces | map(select((ejb("createOrder") | length) > 1 or (ejb("confirmOrder") | length) > 1)) | map(.trace.id)) as $dupes
  | {
      traces: ($traces | length),
      creates: ($creates | length),
      confirms: ($confirms | length),
      multiThreadTraces: $multiThread,
      badParents: $badParents,
      wrongLinks: $wrongLink,
      tracesWithDuplicateCalls: $dupes,
      distinctCustomers: ($creates | map(.name) | unique | length)
    }' "$dir"/[0-9]*-t-*.json)

echo "$report" | jq -c '{traces, creates, confirms, distinctCustomers, multiThreadTraces: (.multiThreadTraces|length), badParents: (.badParents|length), wrongLinks: (.wrongLinks|length), tracesWithDuplicateCalls: (.tracesWithDuplicateCalls|length)}'
status=0
for key in multiThreadTraces badParents wrongLinks tracesWithDuplicateCalls; do
  n=$(echo "$report" | jq ".$key | length")
  if [ "$n" != "0" ]; then
    log "FAIL: $key ($n)"; echo "$report" | jq -c ".$key[:5]"; status=1
  fi
done
creates=$(echo "$report" | jq .creates); confirms=$(echo "$report" | jq .confirms)
if [ "$creates" != "$confirms" ]; then log "FAIL: $creates createOrder vs $confirms confirmOrder"; status=1; fi
if [ -n "$expected" ] && [ "$creates" != "$expected" ]; then log "FAIL: expected $expected pairs, found $creates"; status=1; fi
[ "$status" = 0 ] && log "OK: every trace single-threaded, every parent in its own trace, every confirm linked to its own create"
exit $status
