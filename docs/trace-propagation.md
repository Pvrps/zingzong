# Cross-server trace propagation (design, not yet implemented)

Status: designed in Phase 3, scheduled last (PLAN.md, Phase 3 step 6). The agent exposes the
extension point already; this document fixes the behaviour so the later implementation and
the tooling agree.

## The problem

A trace is a tree of spans built on one thread by `SpanStack`. On a single server that is
enough: local EJB calls and WebLogic's same-server remote path (`TaxService` in the other
EAR) run on the caller's thread, so `computeTax` nests under `price` without help.

Two cases leave the thread:

1. **T3 remote call to another server.** `PricingService` on server A calls `TaxService`
   on server B. B runs `computeTax` on its own execute thread with an empty `SpanStack`, so
   B's agent would start an unrelated trace.
2. **JMS / MDB.** `OrderService` sends a message; `NotifyMDB.onMessage` runs later on a JMS
   thread, possibly on another server, again with an empty stack.

Without propagation the recording contains the pieces but not the link between them.

## Mechanism: WebLogic WorkContext

WebLogic's `weblogic.workarea.WorkContextMap` carries key/value pairs along with a request:
it is marshalled with T3/IIOP calls and with JMS messages (as message properties) and is
available on the receiving thread before the EJB or MDB method runs. It is the same
mechanism WebLogic uses for its own diagnostic context id (`weblogic.diagnostics.*`).

The agent reaches it reflectively (never linked):

```
WorkContextHelper.getWorkContextHelper().getWorkContextMap()   // weblogic.workarea.WorkContextHelper
map.put(KEY, PrimitiveContextFactory.create(traceId), PropagationMode.DEFAULT)
map.get(KEY) -> WorkContext -> toString()/StringWorkContext.get()
map.remove(KEY)
```

`PropagationMode.DEFAULT` covers LOCAL, RMI (T3/IIOP), JMS_QUEUE, JMS_TOPIC and SOAP; that is
exactly the set we want. The key is `io.capture.traceId`.

## Behaviour

`TracePropagation` (in `capture-agent`, `io.capture.agent.span`) has three calls, made by
`SpanStack`:

| Call | When | WorkContext implementation |
| --- | --- | --- |
| `inboundTraceId()` | a span opens on an **empty** stack | read `io.capture.traceId` from the map; return it or null |
| `publish(traceId)` | right after a root span opened a new trace | put `io.capture.traceId = traceId` into the map |
| `clear()` | the root span completed and the trace was handed to the writer | remove the key |

Rules:

- A trace started with an inbound id records it as `trace.parentTraceId`. The trace is still
  its own file with its own `trace.id`; nothing is merged on the server. Tooling joins the two
  files: a span tree under `parentTraceId` gets the remote tree attached under the span that
  was open on the caller at the time (the caller's outbound call is the innermost open span
  whose `durationNs` window contains the callee's `startedAt`; when that is ambiguous, the
  tree is attached at the root and flagged).
- `publish` writes the id of the **current** trace. On a server that received an inbound id,
  the published id is the local trace id, not the inherited one, so a chain A → B → C yields
  C.parentTraceId = B.trace.id and B.parentTraceId = A.trace.id; depth is recovered by
  following the chain.
- `clear` runs when the stack empties so a pooled execute thread never carries a stale id
  into the next request. If a request leaves the thread without completing (timeout,
  abandoned work), the next root span on that thread overwrites the key anyway, because
  `publish` always follows a fresh `inboundTraceId()` read and a put.
- When the WorkContext classes are not present (unit tests, other servers), the
  implementation degrades to `TracePropagation.NONE`: nulls and no-ops, logged once.

### What a propagated file looks like

Server B, `TaxService.computeTax` called over T3 from server A's trace `t-ca3e4c8f`:

```json
{
  "formatVersion": 1,
  "recording": { "id": "rec-20261023-155008", "name": "two-servers", "seq": 12 },
  "trace": {
    "id": "t-5d1e9a02",
    "parentTraceId": "t-ca3e4c8f",
    "startedAt": "2026-10-23T19:50:08.540Z",
    "durationMs": 1,
    "principal": "weblogic",
    "serverName": "ManagedServer2"
  },
  "spans": [
    { "id": "s1", "parentId": null, "depth": 0, "order": 0, "kind": "EJB",
      "ejb": { "beanClass": "com.acme.tax.TaxServiceBean", "method": "computeTax", "...": "..." },
      "thread": "[ACTIVE] ExecuteThread: '7' for queue: 'weblogic.kernel.Default (self-tuning)'",
      "args": [ "..." ], "result": { "...": "..." }, "startNs": 0, "durationNs": 1200000 }
  ]
}
```

Server A's file is unchanged: its `price` span simply has no `computeTax` child. The
reader (Phase 7 `trace-reader`) attaches B's tree under A's `price` span when it loads the
recording, keyed on `parentTraceId` + timing, and the viewer shows the server boundary.

### Recording scope across servers

Each server runs its own agent and its own `RecordingControl`; `capture-start` on one server
does not start the other. For a two-server scenario `capture.sh` is run against both (same
name), and the recording directories are merged by the reader on `recording.name` and the
overlapping time window. Recording ids differ per server (`rec-<timestamp>` on each), which
is fine because `parentTraceId` links traces, not recordings.

### JMS specifics

The WorkContext travels as a JMS message property, so it survives the queue. The MDB's
`onMessage` is instrumented by the annotation hook (`javax.ejb.MessageDriven`) and starts a
trace with `parentTraceId` = the sender's trace. Because the sender's root span has usually
completed by then, the reader attaches asynchronous children at the sending span (found by
the open-span window at send time) and marks the edge `async`.

## Out of scope for the first implementation

- Non-WebLogic callers (plain RMI clients, other servers): no WorkContext, no link.
- HTTP calls between servers: would need a header; the Phase 5 servlet hook can add
  `X-Capture-Trace-Id` symmetrically later, same `TracePropagation` contract.
- Clock skew between servers: the reader uses `parentTraceId` for the link and timing only to
  pick the attachment point; skew degrades the attachment point, never the link.
