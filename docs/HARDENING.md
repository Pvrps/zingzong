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

## 3. Exceptions

**Added**: the business failure, `InsufficientStockException` (`@ApplicationException(rollback =
true)`, thrown by `InventoryService.reserve` for `GIZMO-3 × 999`, mapped by `OrdersResource` to
409 with an error body), and the system failure, a JSON `"customer": null` that the resource now
passes on as null so the `NullPointerException` happens in `OrderServiceBean.createOrder`. Run on
both domains (14: `rec-20261023-213741`, 12: `rec-20261023-213744`).

**What the agent did**: the business path was right from the start: `reserve` and `createOrder`
each carry the exception walked under the raw rule (`@type`, `detailMessage`, the bean's own
fields `sku`/`requested`/`available`, 101 stack frames, cause null, JVM-internal fields listed
under `@skipped`), the resource span completes normally and the HTTP root has status 409 and the
JSON error body. The system path showed two things: the `createOrder` span's exception is the
container's `javax.ejb.EJBException` wrapping the NPE, because that is what crosses the invoker
(the cause chain holds the NPE, 77 frames, top `OrderServiceBean.createOrder:53`); and the HTTP
root had status 500 but no body, while the client received WebLogic's `Request failed.` page.

**What changed**: the response wrapper now intercepts `sendError(status[, message])` and the
root records it as `http.sentError` (`{500, "Request failed."}` on both versions: Jersey calls it
for an unmapped exception, with the text the client then sees); when nothing was written inside
the pipeline the root carries `responseBodyNote` saying that the container produced its error
page afterwards through its own stream. The bytes of that page are not captured: they are
written after the request has left `FilterChainImpl`, when the span is already closed. Also
recorded: on JDK 21 a `NullPointerException`'s `detailMessage` field is null (the helpful
message is computed by `getMessage()` on demand and shows up in the wrapping `EJBException`'s
message); on JDK 8 both are null. No getters were added for that.

## 4. Stateful and EJB 2.x

**Added**: `CartService` (`@Stateful`, `@Remove checkout`; `CartResource` keeps the instance in
the HTTP session so `POST /cart/items`, `GET /cart/total`, `DELETE /cart` reach one bean across
requests), and in `tax-ear` an EJB 2.x `RateLookup` (`RateLookupHome`/`RateLookup` in `tax-api`,
`RateLookupBean implements SessionBean` with `ejbCreate`, declared only in `ejb-jar.xml`, home
bound to `tax/RateLookupHome` by `weblogic-ejb-jar.xml`), called by `PricingService.price` the old
way: home lookup, `create()`, `rateFor`, `remove()`. 14 only (`rec-20261023-214657`).

**What the agent did**: the stateful conversation was right from the start: `CartService.addItem`,
then `items`/`total → PricingService.unitPrice`, then `checkout`, three traces in one lane with
full metadata (`LOCAL`, `REQUIRED`, the six portable names). The 2.x call was captured, by the
same choke point (`SessionRemoteMethodInvoker.invoke`, reached from the generated
`RateLookup_j2unwk_EOImpl`; `docs/container-hook.md`, "EJB 2.x beans"), but with weak metadata:
`beanName` was the class name `RateLookupBean`, no interface, no `app`/`module`, no names: the bean
has no annotations and does not implement its component interface, so nothing on the class said
who it was, and the runtime MBean lookup by that wrong name missed. `home.create()` is not a span.

**What changed**:
- `WlsMetadata.of(Class, Hint)`: the container hook passes what it has at the call site, the
  descriptor's EJB name (`InvocationWrapper.getMethodDescriptor().getEJBName()`) and the
  container's `BeanInfo`; the name wins over the class name, and
  `getRemoteInterfaceClass`/`getLocalInterfaceClass` supply the 2.x views. Home names
  (`getJNDINameAsString`/`getLocalJNDINameAsString`) are added only when the bean has a home
  interface, because WebLogic binds an internal `tax-eartax-ejb_jarTaxService_Home` for every
  bean and that had crept into `TaxService`'s names.
- No portable name is synthesized for a 2.x component interface (it has no `java:global/…!iface`
  binding); the name the lookup actually resolved is recorded instead, and `OpenSpan` moves the
  invoked view's name to the front only when it is really bound.
- Sample: `javax.rmi.PortableRemoteObject.narrow` does not exist on JDK 21 (CORBA removed); a
  WebLogic T3 stub implements the home directly, so the sample casts. `SessionContext` fields
  must not be `transient` (compliance check), and a JAX-RS `Application` listing its classes has
  to list the new resource.

After: `RateLookup.rateFor` with `beanName: RateLookup`, `interface: com.acme.tax.api.RateLookup`
REMOTE, `app: tax-ear`, `module: tax-ejb`, `jndiNames: [java:global/tax-ear/tax-ejb/RateLookup,
tax/RateLookupHome]`, args `["NL","NH"]`, result `0.21`.
