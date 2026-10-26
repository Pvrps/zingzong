# Trace format

This document describes the files a recording is made of: the folder layout, every field of a trace file and of a manifest, and the markers that appear inside recorded values. It's the reference for anyone who reads recordings, with the viewer or with their own tools.

The format is defined by the classes in [`weblogic-trace-model`][model] and by the JSON Schema [`trace.schema.json`][schema]. All files are UTF-8 JSON. Fields with no value are left out rather than written as `null`.

## Files

```
<out>/                               the output directory (wltrace.out)
├── agent.log                        the agents' log
└── rec-20261025-142233/             one recording
    ├── recording-AdminServer.json   manifest, one per server
    ├── 000001-t-3fa2c1d0.json       trace files
    └── 000002-t-9b01e7aa.json
```

- **Recording directory:** named by the recording id, `rec-yyyyMMdd-HHmmss`, with `-2`, `-3`, ... added if the name is taken.
- **Trace file:** `<seq>-<traceId>.json`. `seq` is the trace's position on its server, six digits; the trace id is `t-` plus 8 hex characters. Files appear whole: they're written as `.tmp` and renamed.
- **Manifest:** `recording-<serverName>.json`, or `recording.json` if the server has no name. Written when the recording starts, and completed when it stops.

## Trace file

One trace: every call one thread made for one outermost call.

```json
{
  "formatVersion": 1,
  "recording": { "id": "rec-20261025-142233", "name": "checkout", "seq": 1 },
  "trace": {
    "id": "t-3fa2c1d0",
    "startedAt": "2026-10-25T18:22:41.512Z",
    "durationMs": 48,
    "principal": "ada",
    "httpSessionId": "twtqdC7R...!357112069",
    "serverName": "AdminServer",
    "deployments": [ { "name": "orders-ear", "sourcePath": "/apps/orders-ear.ear", "moduleType": "ear" } ]
  },
  "spans": [
    { "id": "s1", "depth": 0, "order": 0, "kind": "HTTP", "http": { "method": "POST", "path": "/orders/api/orders", "status": 201 }, "...": "..." },
    { "id": "s2", "parentId": "s1", "depth": 1, "order": 1, "kind": "HTTP", "layer": "jaxrs", "...": "..." },
    { "id": "s3", "parentId": "s2", "depth": 2, "order": 2, "kind": "EJB", "ejb": { "beanClass": "com.example.OrderServiceBean", "method": "create" }, "...": "..." },
    { "id": "s4", "parentId": "s3", "depth": 3, "order": 3, "kind": "SQL", "sql": { "operation": "executeUpdate", "updateCount": 1 }, "...": "..." }
  ]
}
```

### Top level ([`Trace`][Trace])

| field | |
| --- | --- |
| `formatVersion` | `1` |
| `recording` | `id` and `name` of the recording; `seq`, this trace's sequence number on its server |
| `trace` | see below |
| `spans` | every span, sorted by `order`. The tree is given by `parentId`. |

### `trace` ([`TraceInfo`][TraceInfo])

| field | |
| --- | --- |
| `id` | `t-` + 8 hex characters |
| `startedAt` | ISO-8601 UTC, milliseconds |
| `durationMs` | from the root's start to the latest end of any span |
| `principal` | the caller's user name, if authenticated |
| `httpSessionId` | the caller's HTTP session, if any |
| `serverName` | the WebLogic server that recorded it |
| `parentTraceId`, `parentSpanId` | the trace and span whose work started this one on another thread, for example the call that sent the JMS message this trace received |
| `deployments` | the domain's applications at recording start: `name`, `sourcePath`, `moduleType` |

### Span ([`Span`][Span])

| field | |
| --- | --- |
| `id` | `s1`, `s2`, ... in entry order |
| `parentId` | the enclosing span; absent on the root |
| `depth` | 0 for the root |
| `order` | entry order, from 0 |
| `kind` | `HTTP`, `EJB` or `SQL` |
| `layer` | on a resource method span: `jaxrs` (REST) or `jaxws` (SOAP) |
| `http`, `ejb`, `sql` | the details for the kind, below |
| `app`, `module` | the application and module, on EJB spans |
| `thread` | the thread name |
| `args` | one [value capture](#value-captures) per argument |
| `result` | a value capture; absent for `void` methods and when an exception was thrown |
| `exception` | `className`, `message`, and `value`, the exception object serialized, stack trace included |
| `serializationWarnings` | what the serializer left out or couldn't read: `path`, `reason`, `typeName` |
| `startNs` | nanoseconds from the trace's start |
| `durationNs` | |

### `ejb` ([`EjbInfo`][EjbInfo])

| field | |
| --- | --- |
| `app`, `module`, `beanName` | as deployed |
| `beanClass` | the bean's own class, never a container subclass |
| `interface` | the business interface the call came through |
| `invocation` | `LOCAL`, `REMOTE` or `MESSAGE` (an MDB delivery) |
| `beanType` | `STATELESS`, `STATEFUL`, `SINGLETON` or `MESSAGE_DRIVEN` |
| `ejb2x` | `true` for an EJB 2.x component interface |
| `jndiNames` | the bean's JNDI names; the `java:global` name of the interface called comes first |
| `destination` | the MDB's queue or topic |
| `method`, `signature` | for example `create` and `com.example.Order create(com.example.OrderRequest)` |
| `transactionAttribute` | `REQUIRED`, `REQUIRES_NEW`, ..., or `BEAN` for bean-managed transactions |

### `http` ([`HttpInfo`][HttpInfo])

On the request span:

| field | |
| --- | --- |
| `method`, `path`, `query`, `protocol`, `scheme` | the request line; `path` has no query string |
| `contextPath`, `servletPath`, `pathInfo` | the servlet API's split of the path |
| `serverName`, `serverPort`, `remoteAddr` | where it arrived, and from where |
| `status` | the response status |
| `contentType`, `responseContentType` | |
| `requestHeaders`, `responseHeaders` | every header, name → list of values |
| `requestBody`, `responseBody` | the bodies, as text or base64 |
| `requestBodyEncoding`, `responseBodyEncoding` | the charset used to decode, with ` (assumed)` when the content type didn't say, or `base64` for binary |
| `requestBodyLength`, `responseBodyLength` | the full length in bytes |
| `requestBodyDropped`, `responseBodyDropped` | bytes over the 64 MiB limit, not kept |
| `sentError` | `status` and `message`, when the application called `sendError` |
| `responseBodyNote` | why the response body is empty when the container wrote an error page the agent couldn't see |

On a resource method span (`layer` is set), only `resourceClass`, `resourceMethod` and `resourceSignature`.

`requestBodyComplete` is defined in the model but isn't written by the agent.

### `sql` ([`SqlInfo`][SqlInfo])

| field | |
| --- | --- |
| `dataSource`, `jndiNames`, `url`, `database` | the WebLogic data source, its JNDI names, the JDBC URL, the database product and version |
| `statementType` | `statement`, `prepared` or `callable` |
| `operation` | the JDBC method: `execute`, `executeQuery`, `executeUpdate`, `executeBatch`, ... |
| `sql` | the statement text |
| `parameters` | the bound parameters by position: element 0 is parameter 1; unset positions are `null` |
| `batches` | for `executeBatch`: one parameter list per batch entry (or one SQL string each, for a plain statement) |
| `updateCount`, `updateCounts` | rows changed, or one count per batch entry |
| `resultSet`, `rows` | whether a result set came back, and how many rows the application read from it |

### Value captures

`args[i]` and `result` are [`ValueCapture`][ValueCapture]s: `declaredType`, the parameter or return type as declared, and `value`, the object as it was. Objects are JSON objects of their fields, with `@type` naming the runtime class; collections are arrays; maps with simple keys are objects. Anything that couldn't be written as-is is a marker.

## Markers

A marker is a key starting with `@`. Any key starting with `@` is never an application field. When and why each marker is used is in [architecture/SERIALIZATION.md](architecture/SERIALIZATION.md).

| marker | shape | means |
| --- | --- | --- |
| `@type` | `"@type": "com.example.Order"` | the runtime class of this object |
| `@id`, `@ref` | `"@id": 3` on the first occurrence, `{"@ref": 3}` after | the same object appears more than once in this value |
| `@serializable` | `"@serializable": false` | the class isn't `java.io.Serializable`; the fields are still written |
| `@skipped` | `"@skipped": [{"field": "_persistence_fetchGroup", "reason": "..."}]` | fields left out, with the reason |
| `@lazy` | `{"@lazy": true, "loaded": false}` | a JPA lazy value that wasn't loaded; it was not touched |
| `@handle` | `{"@handle": "java.lang.Thread", "kind": "thread", "identity": "java.lang.Thread@1b6d3586", "toString": "..."}` | a JVM or OS object (thread, stream, socket, connection, lock, ...), recorded by reference |
| `@text` | `{"@text": "<first part>", "length": 20000000, "@truncated": 3222784, "what": "chars"}` | a string over the length limit |
| `@bytes` | `{"@bytes": "<base64>", "length": 512}` (plus `@truncated` and `what` when over the limit) | a `byte[]` |
| `@truncated` | `{"@truncated": 25, "what": "elements"}` as the last element | a collection, array or map over the size limit; the number left out |
| `@entries` | `{"@type": "java.util.HashMap", "@entries": [{"key": ..., "value": ...}]}` | a map with a key that isn't a string, number, boolean, character or enum |
| `@jms` | `{"@type": "...", "@jms": "TextMessage", "headers": {...}, "properties": {...}, "text": "..."}` | a JMS message, as the JMS API shows it (`text`, `map` or `object` body) |
| `@unserializable` | `{"@unserializable": "com.example.X", "reason": "..."}` | this part couldn't be written; the reason says why |

## Manifest

[`RecordingManifest`][RecordingManifest], one per server:

| field | |
| --- | --- |
| `formatVersion` | `1` |
| `id`, `name` | the recording |
| `startedAt`, `endedAt` | ISO-8601 UTC; `endedAt` is absent while recording |
| `serverName` | |
| `agent` | `version`, `build` (a timestamp) and `jvm` (the server's Java version) |
| `traceCount`, `droppedTraces` | set at stop; above-zero `droppedTraces` means the server produced traces faster than it could write them |

```json
{
  "formatVersion": 1,
  "id": "rec-20261025-142233",
  "name": "checkout",
  "startedAt": "2026-10-25T18:22:33.104Z",
  "endedAt": "2026-10-25T18:24:02.880Z",
  "serverName": "AdminServer",
  "agent": { "version": "1.0.0-SNAPSHOT", "build": "2026-10-25T17:58:10Z", "jvm": "21.0.12.1" },
  "traceCount": 14,
  "droppedTraces": 0
}
```

`servers` is set only on the merged manifest the viewer serves for a recording made on several servers.

## Lineage

Trace files don't contain lineage. The viewer works it out when it reads a recording and returns it in the recording's index (`GET /api/recordings/{id}`), as [`LineageEntry`][LineageEntry]s:

| field | |
| --- | --- |
| `to` | where the value was received: `trace` (absent for the same trace), `span`, `path` such as `args[0].orderId` |
| `from` | the earlier output it came from, same shape, such as `result.id` |
| `value` | the value, as text |
| `alternatives` | other earlier outputs with the same value, nearest first |
| `classification` | for a value with no source: `userInput` or `environment` |
| `alsoProducedHere` | the same trace also produced this value |

How it's worked out is in [architecture/VIEWER.md](architecture/VIEWER.md#lineage).

## Compatibility

Readers should ignore fields they don't know; the schema allows additional properties, and [`TraceJson`][TraceJson] reads that way.

[model]: ../weblogic-trace-model/src/main/java/ca/gc/cra/xz02/devops/wltrace/model
[schema]: ../weblogic-trace-model/src/main/resources/trace.schema.json
[Trace]: ../weblogic-trace-model/src/main/java/ca/gc/cra/xz02/devops/wltrace/model/Trace.java
[TraceInfo]: ../weblogic-trace-model/src/main/java/ca/gc/cra/xz02/devops/wltrace/model/TraceInfo.java
[Span]: ../weblogic-trace-model/src/main/java/ca/gc/cra/xz02/devops/wltrace/model/Span.java
[EjbInfo]: ../weblogic-trace-model/src/main/java/ca/gc/cra/xz02/devops/wltrace/model/EjbInfo.java
[HttpInfo]: ../weblogic-trace-model/src/main/java/ca/gc/cra/xz02/devops/wltrace/model/HttpInfo.java
[SqlInfo]: ../weblogic-trace-model/src/main/java/ca/gc/cra/xz02/devops/wltrace/model/SqlInfo.java
[ValueCapture]: ../weblogic-trace-model/src/main/java/ca/gc/cra/xz02/devops/wltrace/model/ValueCapture.java
[RecordingManifest]: ../weblogic-trace-model/src/main/java/ca/gc/cra/xz02/devops/wltrace/model/RecordingManifest.java
[LineageEntry]: ../weblogic-trace-model/src/main/java/ca/gc/cra/xz02/devops/wltrace/model/LineageEntry.java
[TraceJson]: ../weblogic-trace-model/src/main/java/ca/gc/cra/xz02/devops/wltrace/model/TraceJson.java
