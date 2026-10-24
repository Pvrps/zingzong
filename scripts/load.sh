#!/usr/bin/env bash
# Concurrent load against the lab domain: N parallel clients, each firing Create+Confirm pairs
# with a distinct customer name and confirmedBy ("client-<c>-<i>") so a recording can be
# checked for cross-talk afterwards with scripts/check-recording.sh.
#
#   load [pairs-per-client] [clients]      defaults 25 and 8 (200 pairs)
#   LOAD_DELAY_MS (default 30) holds every createOrder open that long inside the bean so the
#   clients really overlap on distinct server threads instead of being served one by one.

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env WLS_ADMIN_HOST WLS_ADMIN_PORT

pairs="${1:-25}"
clients="${2:-8}"
base="http://$WLS_ADMIN_HOST:$WLS_ADMIN_PORT/orders/api/orders"
work="$TMP_DIR/load"
rm -rf "$work"; mkdir -p "$work"

client() {
  local c=$1 jar="$work/cookies-$c" out="$work/client-$c.log" i body id code
  : > "$out"
  curl -s -o /dev/null -b "$jar" -c "$jar" -X POST "$base/session"
  for ((i = 1; i <= pairs; i++)); do
    local who="client-$c-$i"
    body='{"customer":{"name":"'"$who"'","email":"'"$who"'@example.com","loyaltyTier":"GOLD","address":{"street":"Keizersgracht 1","city":"Amsterdam","region":"NH","postalCode":"1015 CC","country":"NL"}},"lines":[{"sku":"WIDGET-1","quantity":1},{"sku":"GADGET-2","quantity":1}],"paymentMethod":{"type":"CARD","lastFour":"4242","expiry":"2028-06"},"attributes":{"channel":"load","delayMillis":"'"${LOAD_DELAY_MS:-30}"'"},"deliveryDate":"2026-10-30"}'
    code=$(curl -s -b "$jar" -c "$jar" -o "$work/resp-$c" -w '%{http_code}' -X POST "$base" -H 'Content-Type: application/json' -d "$body")
    id=$(sed -n 's/.*"id":\([0-9]*\).*/\1/p' "$work/resp-$c")
    if [ "$code" != "201" ] || [ -z "$id" ]; then echo "create failed: $code $(head -c 120 "$work/resp-$c")" >> "$out"; continue; fi
    code=$(curl -s -b "$jar" -c "$jar" -o /dev/null -w '%{http_code}' -X POST "$base/$id/confirm" -H 'Content-Type: application/json' -d '{"confirmedBy":"'"$who"'","notes":"load"}')
    [ "$code" = "200" ] || echo "confirm failed: $code for $id" >> "$out"
    echo "$who $id" >> "$work/pairs-$c"
  done
}

log "$clients clients x $pairs Create+Confirm pairs against $base"
start=$(date +%s)
for ((c = 1; c <= clients; c++)); do client "$c" & done
wait
log "done in $(( $(date +%s) - start ))s; $(cat "$work"/pairs-* | wc -l) pairs, $(cat "$work"/client-*.log | wc -l) failures"
cat "$work"/client-*.log | head -5
