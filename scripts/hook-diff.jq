# Trace → comparable tree, for hook-diff.sh.
# Removed: span ids, timings, thread, startedAt, session id, recording reference.
# Blanked: values that differ between any two runs (order ids, timestamps, UUIDs, identity
# hashes, lambda class names). A JAX-RS Response result is reduced to @type/status/entity:
# Jersey's provider sets are hash-ordered and differ run to run.
def scrub:
  walk(if type == "object" then
         with_entries(select(.key | IN("id","parentId","startNs","durationNs","thread","order",
                                       "startedAt","durationMs","httpSessionId","recording") | not))
       else . end);
def volatile:
  walk(if type == "object" then
         with_entries(if (.key | IN("orderId","id","createdAt","confirmedAt","requestedAt","openedAt",
                                    "reservedUntil","computedAt","Date","Set-Cookie","Cookie",
                                    "requestBody","responseBody","path","requestUri","Content-Length"))
                      then .value = "<v>" else . end)
       elif type == "string" then
         (gsub("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"; "<uuid>")
          | gsub("[0-9]{5,}"; "<n>") | gsub("@[0-9a-f]{6,}"; "@<h>") | gsub("Lambda/0x[0-9a-f]+"; "Lambda/<h>"))
       elif type == "number" and . > 10000 then "<n>"
       else . end);
{trace: (.trace | scrub), spans: [.spans[] | scrub | volatile]}
| .spans |= map(if .http.resourceClass != null and .result != null then
                  .result.value |= {"@type": .["@type"], status, entity}
                else . end)
