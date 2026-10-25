# The trace format

The reference for anyone consuming recordings, including tooling that generates test payloads from them: the files of a recording and every field in them. `trace.schema.json` in `weblogic-trace-model` is the JSON Schema this document accompanies; the classes of the `model` package are its direct image, one public field per JSON key.

## Recording directory

One directory per recording under the output directory, named by the recording id, `rec-yyyyMMdd-HHmmss` (with `-2`, `-3`, ... when the second is taken):

```
<out>/
  agent.log                          the agent's own log; one line per written trace, every degraded lookup once
  rec-20261025-143012/
    recording-AdminServer.json       one manifest per server that took part
    recording-ms1.json
    000001-t-6d95b4b8.json           one trace per file: this server's sequence number, the trace id
    000002-t-9d99b436.json
```

A trace file is written when the trace's root call completes, as `<name>.tmp` renamed into place, so a reader never sees a partial file. Sequence numbers are per server; two servers in one recording each start at `000001`. A manifest is `recording.json` when the agent runs outside a named server.

## The manifest

`recording-<serverName>.json`, a `RecordingManifest`, written at start and rewritten complete at stop:

| field | type | present |
| --- | --- | --- |
| `formatVersion` | integer, `1` | always |
| `id` | string | always; the recording id, the same on every server |
| `name` | string | always; `unnamed` when none is given |
| `startedAt` | ISO-8601 instant, UTC | always |
| `endedAt` | ISO-8601 instant, UTC | after stop |
| `serverName` | string | always |
| `agent.version`, `agent.build`, `agent.jvm` | strings | always; the agent's version, build stamp and `java.version` |
| `traceCount` | integer | after stop; traces this server wrote |
| `droppedTraces` | integer | after stop; traces this server dropped because the writer queue is full |

The viewer merges the servers' manifests into one with `servers` (every server name), the earliest `startedAt`, the latest `endedAt` (absent while any server is still recording) and summed counts; that merged form appears only in the viewer's index, never on disk.

## The trace file

A `Trace`:

| field | type | present |
| --- | --- | --- |
| `formatVersion` | integer, `1` | always |
| `recording.id` | string | always |
| `recording.name` | string | always |
| `recording.seq` | integer | always; this server's position of the trace in the recording, from 1 |
| `trace` | `TraceInfo` | always |
| `spans` | array of `Span`, ordered by `order` | always; never empty |
| `lineage` | array of lineage entries | never in a file the agent writes |

`TraceInfo`:

| field | type | present |
| --- | --- | --- |
| `id` | string, `t-` and eight hex characters | always |
| `startedAt` | ISO-8601 instant, UTC, millisecond precision | always |
| `durationMs` | integer | always; from the root's start to the latest span end |
| `principal` | string | when the server knows the caller: the request's user principal for an HTTP root, else the WebLogic subject |
| `httpSessionId` | string | when the thread's request has a session |
| `serverName` | string | in a named server |
| `parentTraceId`, `parentSpanId` | strings | when another thread's work caused this trace (`@Asynchronous`, JMS): the trace and the span active at the handoff |
| `deployments` | array of `{name, sourcePath, moduleType}` | when the runtime MBeans are reachable: every application deployed in the domain |

`Span`:

| field | type | present |
| --- | --- | --- |
| `id` | string, `s1`, `s2`, ... | always |
| `parentId` | string | on every span but the root |
| `depth` | integer | always; 0 for the root |
| `order` | integer | always; the order of entry, from 0 |
| `kind` | `EJB`, `HTTP` or `SQL` | always |
| `layer` | `jaxrs` or `jaxws` | on a resource method span |
| `ejb`, `http`, `sql` | the kind's block | exactly one, by kind |
| `app`, `module` | strings | on EJB spans when the runtime MBeans named them |
| `thread` | string | always; the thread's name at exit |
| `args` | array of `ValueCapture` | on EJB and resource spans; empty for a method without parameters |
| `result` | `ValueCapture` | on EJB and resource spans whose method is not void and did not throw |
| `exception` | `ExceptionInfo` | when the call threw |
| `serializationWarnings` | array of `{path, reason, typeName}` | when serializing anything on the span left a marker other than `@type`, `@id`, `@ref`, `@skipped` or `@serializable`; `path` is the JSON path within the span, `$.args[0].lines[2]` |
| `startNs` | integer | always; nanoseconds after the trace started |
| `durationNs` | integer | always |

`ValueCapture` is `{"declaredType": "<fully qualified type>", "value": <JSON>}`; `declaredType` is the parameter or return type of the recorded method, `value` the serialized object (see [Values](#values)). `ExceptionInfo` is `{"className", "message", "value"}` with `value` the serialized exception: its own fields, `detailMessage`, `cause`, `suppressedExceptions`, and `stackTrace` as an array of frame strings.

## Span kinds

### EJB

A call into a session bean through its business interface, or a message delivered to a message-driven bean. `ejb`:

| field | type | present |
| --- | --- | --- |
| `beanClass` | string | always; the bean's own class, never a generated subclass |
| `method` | string | always |
| `signature` | string, `ReturnType name(ParamType, ...)` with fully qualified names | always; the name and descriptor when the method could not be resolved |
| `interface` | string | the business interface the call came in through; `javax.jms.MessageListener` for a message |
| `invocation` | `LOCAL`, `REMOTE` or `MESSAGE` | always |
| `transactionAttribute` | string | when the container declares one; `BEAN` for a bean-managed bean without one |
| `app`, `module`, `beanName` | strings | when the runtime MBeans named them |
| `jndiNames` | array of strings | session beans: every name the bean is bound under, the invoked view's `java:global` name first, the rest sorted |
| `beanType` | `STATELESS`, `STATEFUL`, `SINGLETON` or `MESSAGE_DRIVEN` | when the container says |
| `ejb2x` | `true` | when the invoked view is an EJB 2.x component interface |
| `destination` | string | message-driven beans: the destination as configured |

`args` holds the call arguments; for a message, `args[0]` is the message as its API view (`@jms`). `result` is absent for a void method and for a message.

### HTTP

A request entering the servlet pipeline (no `layer`) or a JAX-RS resource or JAX-WS endpoint method under it (`layer`). `http` on a request:

| field | type | present |
| --- | --- | --- |
| `method`, `path`, `contextPath`, `servletPath`, `pathInfo`, `query`, `protocol`, `scheme`, `serverName`, `serverPort`, `remoteAddr` | strings, `serverPort` an integer | as the request reports them; `path` is the request URI without the query |
| `contentType`, `responseContentType` | strings | when set |
| `status` | integer | always |
| `requestHeaders`, `responseHeaders` | object: name to array of strings | always; every header, multi-valued, nothing filtered; `@unreadable` as the only key when the headers cannot be read |
| `requestBody`, `responseBody` | string | when the body is read or written: text decoded with `*BodyEncoding`, or base64 when that is `base64` |
| `requestBodyEncoding`, `responseBodyEncoding` | string | with a body: a charset name, with ` (assumed)` when inferred from the content type rather than declared; `chars` for a response written through the writer without a declared charset |
| `requestBodyLength`, `responseBodyLength` | integer | with a body: the full length in bytes (characters for `chars`) |
| `requestBodyDropped`, `responseBodyDropped` | integer | when the body exceeded the 64 MiB ceiling: how many bytes are missing from the end |
| `sentError` | `{status, message}` | when the application called `sendError` |
| `responseBodyNote` | string | when no body is written inside the pipeline and the container produced an error page through its own stream |

`http` on a resource method: `resourceClass`, `resourceMethod`, `resourceSignature`; the method's arguments and result are the span's `args` and `result`.

### SQL

A statement executed through a data source. `sql`:

| field | type | present |
| --- | --- | --- |
| `dataSource` | string | always; the pool name, `?` when unreadable |
| `jndiNames`, `url`, `database` | array of strings, string, string | when the server's MBeans could tell |
| `statementType` | `statement`, `prepared` or `callable` | always |
| `operation` | string | always; the `execute*` method name |
| `sql` | string | the statement text, when the wrapper knows it |
| `parameters` | array of JSON values | prepared and callable statements with parameters bound: by position, element 0 is position 1, a position never set is `null` |
| `batches` | array of arrays | `executeBatch`: one parameter array per `addBatch`, or one-element arrays of statement text for plain batches |
| `updateCount` | integer | `executeUpdate` |
| `updateCounts` | array of integers | `executeBatch` |
| `resultSet` | boolean | `execute`, and `executeQuery` as `true` |
| `rows` | integer | with a result set: how many rows the application read from it |

An SQL span has no `args` or `result`; an `exception` when the driver threw.

## Values

An object is `{"@type": "<runtime class>", "<field>": <value>, ...}` with every instance field of the class and its superclasses, transient fields included, static fields excluded. Scalars are lossless JSON: numbers and booleans as JSON, strings as strings, enums by name, `BigDecimal` and `BigInteger` as strings (scale preserved), `java.time` types as their ISO text, `java.util.Date` as an ISO instant, `java.sql.Timestamp` with nanoseconds, `Calendar` as a zoned date-time, `UUID`, `URI`, `URL`, `File`, `Path`, `Locale`, `Currency` and `StackTraceElement` as their text, `NaN` and infinities as strings. Collections and arrays are JSON arrays, `char[]` a string, `byte[]` a `@bytes` marker. A map whose keys are all strings, enums, numbers, booleans or characters is a JSON object (enum keys by name); any other map is `{"@type": ..., "@entries": [{"key": ..., "value": ...}]}`. An `Optional` is `{"@type": "java.util.Optional", "present": bool, "value": ...}`.

## Markers

A marker is a key beginning with `@`. These are their exact shapes and meanings; `@type` is on every object, the rest appear only where a value is not written as it crossed the boundary.

| JSON | meaning |
| --- | --- |
| `"@type": "<class>"` | the runtime class of the object |
| `"@id": n` on an object; `{"@ref": n}` elsewhere | the object is referenced more than once (a cycle or a shared reference): written in full once, carrying `@id`, every later reference is `@ref`. Ids are per recorded value (per argument, result or exception), from 1 |
| `"@serializable": false` on an object | the class does not implement `java.io.Serializable`; walked all the same |
| `{"@lazy": true, "loaded": false}` | an uninstantiated lazy collection or holder (EclipseLink `IndirectContainer` / `ValueHolderInterface`, Hibernate `PersistentCollection` / `HibernateProxy`); loading it would change the call, so its content is not recorded |
| `{"@handle": "<class>", "kind": "<kind>", "identity": "<class>@<hex>", "toString": "<text>"}` | a runtime handle (thread, class loader, class reference, OS process, socket, connection, stream, file handle, console, channel, JDBC connection, statement, result set, connection factory, lock, lock condition, semaphore, latch, barrier, phaser, executor, future, timer): not payload, so only identity and text; `toString` absent when it could not be read |
| `"@skipped": [{"field": "<name>", "reason": "<text>"}]` on an object | fields not written: `compiler-injected (synthetic)`, `weaver-injected (EclipseLink)`, `weaver-injected (Hibernate)`, `JVM-internal frame data; see stackTrace`, `inaccessible: <exception>` (JDK encapsulation without `--add-opens`), `declared fields not readable: <exception>` |
| `{"@truncated": n, "what": "elements"}` as the last element of an array; `"@truncated": n, "what": "entries"` on a map | a ceiling is reached; `n` elements or entries are missing |
| `{"@text": "<prefix>", "length": n, "@truncated": m, "what": "chars"}` | a string over the ceiling: the kept prefix, the full length, the characters dropped |
| `{"@bytes": "<base64>", "length": n}` plus `"@truncated": m, "what": "bytes"` when over the ceiling | a byte array |
| `{"@type": "<class>", "@entries": [{"key": ..., "value": ...}]}` | a map whose keys are not scalars |
| `{"@type": "<class>", "@jms": "<TextMessage|MapMessage|ObjectMessage|BytesMessage|StreamMessage|Message>", "headers": {...}, "properties": {...}, <body>}` | a JMS message as its API view: the `JMS*` headers, every property, and `text`, `map`, `object`, or for a bytes or stream message `bodyLength` and a `@truncated` note, because reading the body would consume it |
| `{"@unserializable": "<class>", "reason": "<text>", "identity": "<class>@<hex>", "toString": "<text>"}` | the walk failed or the depth ceiling is reached; `identity` absent for a null, `toString` absent when unsafe (a JPA entity or lazy wrapper) or unreadable |

Every `@lazy`, `@handle`, `@truncated`, `@text`, `@bytes` over the ceiling and `@unserializable` also adds a `serializationWarnings` entry on the span with the JSON path of the value.

## Ceilings

| ceiling | default | marker |
| --- | --- | --- |
| nesting depth of objects and containers | 64 | `@unserializable` with reason `depth ceiling 64 reached; subtree dropped` |
| elements per collection, map or array | 100 000 | `@truncated` with `what` `elements` or `entries` |
| characters per string | 16 777 216 | `@text` |
| bytes per `byte[]` | 67 108 864 | `@bytes` with `@truncated` |
| bytes per HTTP body | 67 108 864 | `requestBodyDropped` / `responseBodyDropped` |

None is reachable by a real payload; they exist so that one pathological value cannot exhaust the server's heap or the trace file.

## Lineage output

The viewer's index (`GET /api/recordings/{id}`) carries `lineage`, an array of entries computed from the recording, never written by the agent:

| field | type | present |
| --- | --- | --- |
| `to` | `{trace, span, path}` | always; where the value is used: the trace id, the span id, and the path within the span, `args[0].orderId`, `http.requestBody.customer.name`, `http.path[4]`, `http.query.id` |
| `from` | `{trace, span, path}` | on a sourced entry: the nearest earlier output with the same value, `result.id`, `http.responseBody.id` |
| `value` | string | always; the normalised value |
| `alternatives` | array of `{trace, span, path}` | when more than one earlier output carries the value, nearest first |
| `classification` | `userInput` or `environment` | on an unsourced entry |
| `alsoProducedHere` | `true` | on a sourced entry whose value the same trace also produces |

Sourced entries come first, then the unsourced ones.

## An annotated example

A request that creates an order: the servlet root, the resource method, two EJB calls and one statement, abbreviated.

```jsonc
{
  "formatVersion": 1,
  "recording": { "id": "rec-20261025-143012", "name": "create", "seq": 2 },     // seq: this server's second trace
  "trace": {
    "id": "t-6d95b4b8",
    "startedAt": "2026-10-25T18:30:14.145Z",
    "durationMs": 301,
    "httpSessionId": "nTpw9Wog...",                                                // principal absent: an anonymous call
    "serverName": "AdminServer",
    "deployments": [ { "name": "orders-ear", "sourcePath": "/opt/applications/orders-ear.ear", "moduleType": "ear" } ]
  },
  "spans": [
    { "id": "s1", "parentId": null, "depth": 0, "order": 0, "kind": "HTTP",       // the request
      "thread": "[ACTIVE] ExecuteThread: '8' for queue: 'weblogic.kernel.Default (self-tuning)'",
      "startNs": 0, "durationNs": 301628149,
      "http": { "method": "POST", "path": "/orders/api/orders", "contextPath": "/orders", "status": 201,
                "requestHeaders": { "Content-Type": ["application/json"], "Cookie": ["JSESSIONID=nTpw9Wog..."] },
                "requestBody": "{\"customer\":{\"name\":\"Ada Lovelace\"},\"lines\":[...]}",
                "requestBodyEncoding": "UTF-8 (assumed)", "requestBodyLength": 420,              // JSON without a charset parameter
                "responseHeaders": { "Content-Type": ["application/json"], "Date": ["..."] },
                "responseBody": "{\"id\":6,\"status\":\"CREATED\",\"total\":\"149.73\"}",
                "responseBodyEncoding": "UTF-8", "responseBodyLength": 229 } },
    { "id": "s2", "parentId": "s1", "depth": 1, "order": 1, "kind": "HTTP", "layer": "jaxrs",  // the resource method
      "thread": "...", "startNs": 1200311, "durationNs": 299100042,
      "http": { "resourceClass": "com.example.orders.web.OrdersResource", "resourceMethod": "create",
                "resourceSignature": "javax.ws.rs.core.Response create(java.lang.String)" },
      "args": [ { "declaredType": "java.lang.String", "value": "{\"customer\":..." } ],
      "result": { "declaredType": "javax.ws.rs.core.Response",
                  "value": { "@type": "org.glassfish.jersey.message.internal.OutboundJaxrsResponse", "status": 201,
                             "context": { "@type": "...", "entityStream": { "@handle": "...EntityOutputStream", "kind": "stream",
                                          "identity": "...EntityOutputStream@1a2b3c", "toString": "..." } } } },
      "serializationWarnings": [ { "path": "$.result.context.entityStream", "reason": "runtime handle (stream), recorded as reference",
                                   "typeName": "org.glassfish.jersey.message.internal.OutboundMessageContext$EntityOutputStream" } ] },
    { "id": "s3", "parentId": "s2", "depth": 2, "order": 2, "kind": "EJB", "app": "orders-ear", "module": "orders-ejb",
      "thread": "...", "startNs": 2100000, "durationNs": 290000000,
      "ejb": { "app": "orders-ear", "module": "orders-ejb", "beanName": "OrderService",
               "beanClass": "com.example.orders.OrderServiceBean",
               "interface": "com.example.orders.api.OrderServiceLocal", "invocation": "LOCAL", "beanType": "STATELESS",
               "jndiNames": [ "java:global/orders-ear/orders-ejb/OrderService!com.example.orders.api.OrderServiceLocal",
                              "java:global/orders-ear/orders-ejb/OrderService", "orders/OrderService" ],
               "method": "createOrder",
               "signature": "com.example.model.Order createOrder(com.example.dto.CreateOrderRequest)",
               "transactionAttribute": "REQUIRED" },
      "args": [ { "declaredType": "com.example.dto.CreateOrderRequest",
                  "value": { "@type": "com.example.dto.CreateOrderRequest",
                             "customer": { "@type": "com.example.dto.Customer", "name": "Ada Lovelace", "loyaltyTier": "GOLD" },
                             "paymentMethod": { "@type": "com.example.dto.CardPayment", "lastFour": "4242", "expiry": "2028-06" },  // runtime type of a polymorphic field
                             "attributes": { "channel": "web" } } } ],                                                      // a string-keyed map is a plain object
      "result": { "declaredType": "com.example.model.Order",
                  "value": { "@type": "com.example.model.Order", "@id": 1, "id": 6, "status": "CREATED", "total": "149.73",  // BigDecimal as text
                             "lines": { "@lazy": true, "loaded": false },                                                  // not loaded when the call returned
                             "audit": { "@type": "com.example.model.AuditTrail", "@serializable": false, "entryCount": 1 },
                             "@skipped": [ { "field": "_persistence_fetchGroup", "reason": "weaver-injected (EclipseLink)" } ] } } },
    { "id": "s4", "parentId": "s3", "depth": 3, "order": 3, "kind": "EJB", "app": "orders-ear", "module": "orders-ejb",
      "thread": "...", "startNs": 5000000, "durationNs": 12000000,
      "ejb": { "beanClass": "com.example.orders.PricingServiceBean", "beanName": "PricingService", "method": "price",
               "signature": "com.example.dto.PriceQuote price(com.example.dto.CreateOrderRequest)",
               "interface": "com.example.orders.api.PricingServiceLocal", "invocation": "LOCAL", "beanType": "STATELESS",
               "transactionAttribute": "REQUIRED" },
      "args": [ { "declaredType": "com.example.dto.CreateOrderRequest", "value": { "@type": "com.example.dto.CreateOrderRequest", "...": "..." } } ],
      "result": { "declaredType": "com.example.dto.PriceQuote", "value": { "@type": "com.example.dto.PriceQuote", "subtotal": "123.74", "total": "149.73" } } },
    { "id": "s5", "parentId": "s3", "depth": 3, "order": 4, "kind": "SQL",
      "thread": "...", "startNs": 110000000, "durationNs": 2300000,
      "sql": { "dataSource": "OrdersDS", "jndiNames": ["jdbc/OrdersDS"], "url": "jdbc:h2:file:/var/lib/h2/orders", "database": "H2 2.3.232 (2024-08-11)",
               "statementType": "prepared", "operation": "executeUpdate",
               "sql": "INSERT INTO ORDERS (CREATED_AT, CURRENCY, CUSTOMER_EMAIL, TOTAL) VALUES (?, ?, ?, ?)",
               "parameters": [ "2026-10-25T18:30:14.255689086Z", "EUR", "ada@example.com", "149.73" ],                    // by position; a Timestamp keeps its nanoseconds
               "updateCount": 1 } }
  ]
}
```

A call that throws carries `exception` and no `result`:

```jsonc
"exception": { "className": "com.example.orders.api.OrderNotFoundException", "message": "no order 999999",
               "value": { "@type": "com.example.orders.api.OrderNotFoundException", "orderId": 999999,      // the exception's own fields are data
                          "detailMessage": "no order 999999", "cause": null, "suppressedExceptions": [],
                          "stackTrace": [ "com.example.orders.OrderServiceBean.confirmOrder(OrderServiceBean.java:92)", "..." ],
                          "@skipped": [ { "field": "backtrace", "reason": "JVM-internal frame data; see stackTrace" },
                                        { "field": "depth", "reason": "JVM-internal frame data; see stackTrace" } ] } }
```
