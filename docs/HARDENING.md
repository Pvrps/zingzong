# Hardening log

Conditions the sample applications did not produce until now, added one at a time. Each entry
records what was added, what the agent did with it, and what had to change.

## 0. Resource matching by ownership, not by package prefix

**Added**: nothing to the applications; the 12.2.1.4 lab already had Coherence's management
REST resources (`com.tangosol.coherence.management.internal.resources.*`, `@javax.ws.rs.Path`)
on the server class path, loaded after one request to `/management/coherence/`.

**What the agent did**: with the package-prefix rule, `VersionsResource` was instrumented like an
application resource (it matched `@Path` and no prefix excluded it until `com.tangosol.` was
added by hand). At work the server class path carries many more vendor `@Path`/`@WebService`
classes than a list can keep up with.

**What changed**: `ApplicationLoaders`. A `@Path`/`@WebService` class (and, under the annotation
strategy, an EJB class) is instrumented only when its defining loader is an application or
module loader: on WebLogic a `weblogic.utils.classloaders.*` loader (`GenericClassLoader`,
`ChangeAwareClassLoader`), never the launcher's `PolicyClassLoader`, the system or the bootstrap
loader (`docs/container-hook.md`, "Class loaders"). `com.tangosol.` left the ignore list; the
list now holds only the JVM, the agent, the server's own package roots and the frameworks it
embeds. A class the rule keeps out is logged once:

```
not instrumented (resource annotation, but not an application class):
  com.tangosol.coherence.management.internal.resources.VersionsResource loader=PolicyClassLoader 'weblogic-launcher' @1a3869f4
instrumented com.acme.orders.web.OrdersResource (retransformed) loader=weblogic.utils.classloaders.ChangeAwareClassLoader@3b8614e1
instrumented com.acme.tax.ws.TaxWebService (retransformed) loader=weblogic.utils.classloaders.ChangeAwareClassLoader@119e6702
```

Verified on 12.2.1.4 (`rec-20261023-210341`) and 14.1.2 (`rec-20261023-210211`): the sample
resources are instrumented on both, the Coherence resource on neither. Outside WebLogic
ownership cannot be read from the loader (a plain JVM runs applications on the system loader),
so there every non-bootstrap loader counts and the package list decides; the unit tests run that
way.

## 1. Concurrency

**Added**: `scripts/load.sh` (`load [pairs] [clients]`, default 8 parallel clients × 25
Create+Confirm pairs, each call with its own customer name and `confirmedBy`
`client-<c>-<i>`; `LOAD_DELAY_MS`, default 30, holds each `createOrder` open inside the bean so
the clients really overlap), and `scripts/check-recording.sh`, which asserts over a recording:
every trace's spans ran on one thread; every span's parent is a span of the same trace one
level up; every `confirmOrder` names an order that a `createOrder` of the same customer
produced (EJB argument, URL path and `confirmedBy` agree); as many confirms as creates, each at
most once per trace.

**What the agent did**: nothing wrong. On both domains, 408 traces (8 sessions + 200 + 200),
200 distinct customers, 0 multi-thread traces, 0 foreign parents, 0 wrong links, 0 dropped
traces; 16 distinct server threads carried the work, 200 pairs in 2 s
(14: `rec-20261023-211150`, 12: `rec-20261023-211155`).

**What changed**: not the agent. The first runs were not a concurrency test at all: WebLogic's
self-tuning pool served the 8 clients on 2–3 threads, one request after another (8 parallel
2-second calls took 8 s). The lab start script now sets `-Dweblogic.threadpool.MinPoolSize=16`
so the pool starts wide; with it the same 8 calls take 2.5 s. The checker counts distinct
threads so a serialised run cannot pass as evidence again.

## 2. Async and messaging

**Added**: `OrderService.notifyAsync` (`@Asynchronous`, called by `confirmOrder` through the
bean's own local view) and `notify-ear`: `OrderEventListener`, an MDB on `jms/OrderEvents`
that hands each event to a stateless `NotificationService`; `confirmOrder` publishes a
`TextMessage` to that queue. Domain side: `scripts/jms-resources.py` (an XA connection factory
`jms/OrdersCF`, the queue, a JMS server), `wls-domain --add-jms` for existing domains, and the
data source on `EmulateTwoPhaseCommit` (one-phase cannot commit with the XA send; H2 rejects
LLR's table name).

**What the agent did, before**: three unrelated traces per Confirm. `notifyAsync` a root of its
own on a work-manager thread, `NotificationService.record` a root on a JMS thread, and the MDB's
`onMessage` not captured at all: message delivery does not pass the session invokers.

**What changed**:
- `MDListener.onMessage(Message)` is the third container choke point (`MessageAdvice`,
  `ContainerTargets`); an MDB span has `invocation: MESSAGE`, `interface:
  javax.jms.MessageListener`, `destination`, and its runtime MBean is found by the `EJBName`
  attribute (the MBean is named `<ejb>_<destination>`).
- A `javax.jms.Message` argument is recorded as its API view (`JmsMessages`: headers,
  properties, text / map / object body; a bytes or stream body is not read because reading
  consumes it, which is marked). This is the serializer's one use of getters, documented there:
  the vendor object's fields describe the session, not the payload.
- `TracePropagation` is implemented over WebLogic's WorkContext (`WlsWorkContextPropagation`):
  the active span is published on every push/pop, removed at trace end, and a span opening on
  an empty stack with the key present records `parentTraceId` / `parentSpanId`. The first run
  linked only the MDB: `PropagationMode.GLOBAL` (212) omits `WORK`, so the `@Asynchronous`
  worker got no context until the mode was composed from the named flags.
- Reader and page: a child trace joins its parent's lane after the parent, marked `↳` with a
  "caused by #4 / s3" link; the causing span's pane lists "handed off to another thread: #3
  notifyAsync · #5 OrderEventListener.onMessage"; a child's root says what caused it.

After (`rec-20261023-213036`): Confirm yields `#4 POST …/confirm → confirmOrder (s3)`,
`↳#3 OrderService.notifyAsync` and `↳#5 OrderEventListener.onMessage → NotificationService.record`,
both with `parentTraceId = t(#4)`, `parentSpanId = s3`, the message view carrying
`{"orderId":143699,"event":"CONFIRMED","by":"ann"}` with its JMS headers and properties.
Cross-server handoffs stay out of scope (`docs/trace-propagation.md`).
