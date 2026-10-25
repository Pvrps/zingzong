# WebLogic internals the agent touches

Every WebLogic class, method, field and MBean the agent reaches, for an engineer touching those internals: what each is, why the agent uses that point, what it reads and how it degrades. The agent has no compile dependency on any of them (ARCHITECTURE.md, constraint 1); each is reached by name and reflection, and each use degrades to null and warns once when a name is missing. How hooks fit into the agent is in [ARCHITECTURE.md](ARCHITECTURE.md#5-hooks); this document says what each one is.

## Contents

1. [Session invokers](#1-session-invokers)
2. [MDListener](#2-mdlistener)
3. [FilterChainImpl and ServletStubImpl](#3-filterchainimpl-and-servletstubimpl)
4. [JDBC wrappers](#4-jdbc-wrappers)
5. [Class-loader ownership](#5-class-loader-ownership)
6. [WorkContext](#6-workcontext)
7. [Runtime MBeanServer and BeanInfo](#7-runtime-mbeanserver-and-beaninfo)
8. [Principal and session](#8-principal-and-session)
9. [Finding a hook on a new build](#finding-a-hook-on-a-new-build)

Verified builds, referred to below as 12 and 14:

| | WebLogic Server | build | JDK |
| --- | --- | --- | --- |
| 14 | 14.1.2.0.0 | Tue Nov 26 2024, 2171472, no patches | Temurin 21.0.12 |
| 12 | 12.2.1.4.0 | Thu Sep 12 2019, 1974621, no patches | Temurin 1.8.0_504 |

Every name below resolves identically on both. Stack frames are from `jcmd <pid> Thread.print` on 14 while the call slept inside the application code; where 12 differs in a frame it is noted. Application frames are shown as `com.example...`; WebLogic generates the `<Bean>_<hash>_<Interface>Impl` classes per deployment.

## 1. Session invokers

Code: `SessionInvokerHook`, `SessionInvokerAdvice`, `EjbRecorder`, `SessionInvocation`.

**What it is.** The two static methods in `com.oracle.weblogic.ejb.jar` through which every business-interface call into a session bean passes: stateless, stateful, singleton and EJB 2.x alike, local and remote views.

**Why this point.** Above them the generated client object (`<Bean>_<hash>_<Interface>Impl`) is per deployment and per interface; below them the container's interceptor chain and the bean are application-specific. The invokers are the one place with a fixed signature that sees the client object, the container's bean object, the method descriptor and the arguments together.

**Class and method.**

| view | `weblogic.ejb.container.internal.` |
| --- | --- |
| local | `public static Object SessionLocalMethodInvoker.invoke(Invokable, BaseLocalObject, InvocationWrapper, Object[], int) throws Throwable` |
| remote | `public static Object SessionRemoteMethodInvoker.invoke(Invokable, BaseRemoteObject, InvocationWrapper, Object[], int) throws Throwable` |

The advice matches `invoke`, static, five arguments. Frames, innermost first, with interceptor frames (`com.bea.core.repackaged.springframework.aop`, `com.oracle.pitchfork`, `org.jboss.weld`) elided:

```
local view (a resource calling an @EJB)
com.example.orders.OrderServiceBean.createOrder
jdk.proxy8.$Proxy325.createOrder                                                        12: com.sun.proxy.$ProxyN
com.example.orders.OrderService_wdu466_OrderServiceLocalImpl.__WL_invoke
weblogic.ejb.container.internal.SessionLocalMethodInvoker.invoke(SessionLocalMethodInvoker.java:33)   <- advised
com.example.orders.OrderService_wdu466_OrderServiceLocalImpl.createOrder
com.example.orders.web.OrdersResource.create

remote view (a T3 call)
com.example.orders.OrderServiceBean.createOrder
jdk.proxy8.$Proxy325.createOrder
com.example.orders.OrderService_wdu466_OrderServiceRemoteImpl.__WL_invoke
weblogic.ejb.container.internal.SessionRemoteMethodInvoker.invokeInternal(SessionRemoteMethodInvoker.java:54)
weblogic.ejb.container.internal.SessionRemoteMethodInvoker.invoke(SessionRemoteMethodInvoker.java:21)  <- advised
com.example.orders.OrderService_wdu466_OrderServiceRemoteImpl.createOrder
com.example.orders.OrderService_wdu466_OrderServiceRemoteImpl_WLSkel.invoke
weblogic.rmi.internal.BasicServerRef.invoke(BasicServerRef.java:694)                    12: :645

EJB 2.x (home, then EJBObject): the same remote invoker
com.example.tax.RateLookupBean.rateFor
jdk.proxy12.$Proxy352.rateFor
com.example.tax.RateLookup_j2unwk_EOImpl.__WL_invoke
weblogic.ejb.container.internal.SessionRemoteMethodInvoker.invoke(SessionRemoteMethodInvoker.java:21)  <- advised
com.example.tax.RateLookup_j2unwk_EOImpl.rateFor
com.example.tax.RateLookup_j2unwk_EOImpl_WLSkel.invoke
```

`home.create()` and `EJBObject.remove()` do not pass the invokers and are not spans.

**What is read and how.** `SessionInvocation.read` reads the first three arguments:

| argument | member | gives |
| --- | --- | --- |
| 0 `Invokable`, the generated `<pkg>.<Bean>_<hash>_<Interface>Impl` | its interfaces | the business interface: implemented directly by a local view; a remote view implements a generated `<pkg>.<Bean>_<hash>_<Interface>RIntf extends java.rmi.Remote`, whose simple name `SessionInvocation.businessInterface` resolves against the bean's interfaces |
| 1 `BaseLocalObject` / `BaseRemoteObject` | protected field `beanInfo` | the container's `BeanInfo` (section 7) |
| 2 `InvocationWrapper` | `getMethodDescriptor()`, then `MethodDescriptor.getApplicationName()`, `getModuleId()`, `getEjbName()`, `getMethod()`, `getTransactionPolicy().getTxAttribute()`; `isLocal()` | application, module, EJB name, the interface `Method`, the effective `javax.ejb.TransactionAttributeType`, local or remote |
| 3 `Object[]` | | the call arguments, serialized at exit |

The bean class is loaded by name from `BeanInfo.getBeanClassName()` through the invokable's class loader.

**What the span records.** Kind `EJB`: `beanClass`, `method`, `signature`, `interface`, `invocation` LOCAL or REMOTE, `transactionAttribute` (`BEAN` for a bean-managed bean without one), and from the metadata of section 7 `app`, `module`, `beanName`, `jndiNames` (the invoked view's `java:global` name first), `beanType`, `ejb2x`; `args` and `result` or `exception`.

**Verified on.** 12 and 14, the frames above. A class loaded during installation is skipped by Byte Buddy's circularity lock, because describing a loaded target resolves the types its methods mention; `HookInstaller` retransforms such named targets explicitly afterwards.

**When it fails.** A descriptor without `getMethodDescriptor` or a `BeanInfo` without `getBeanClassName` makes `SessionInvocation.read` return empty; `EjbRecorder` warns once, `session invoker hook: invocation objects not recognised`, and records nothing for that call.

## 2. MDListener

Code: `MessageListenerHook`, `MessageListenerAdvice`, `MessageRecorder`, `MessageDelivery`.

**What it is.** The container's JMS listener that receives a message for a message-driven bean, opens the transaction and calls the bean's `onMessage`.

**Why this point.** It is the first frame with the message as a plain argument and the bean's `MessageDrivenBeanInfo` at hand; the bean's own `onMessage` is application code.

**Class and method.** `public void weblogic.ejb.container.internal.MDListener.onMessage(javax.jms.Message)`.

```
com.example.notify.OrderEventListener.onMessage
jdk.proxy9.$Proxy261.onMessage
weblogic.ejb.container.internal.MDListener.execute(MDListener.java:459)
weblogic.ejb.container.internal.MDListener.transactionalOnMessage(MDListener.java:370)
weblogic.ejb.container.internal.MDListener.onMessage(MDListener.java:306)          <- advised
weblogic.jms.client.JMSSession.onMessage(JMSSession.java:5172)
weblogic.work.SelfTuningWorkManagerImpl$WorkAdapterImpl.run
```

**What is read and how.** `MessageDelivery.read` reads the listener's private final field `info`, a `weblogic.ejb.container.interfaces.MessageDrivenBeanInfo`: `getBeanClassName()` and `getClassLoader()` (the bean class in its own loader), `getDestinationName()`, `getOnMessageMethodDescriptor().getTransactionPolicy().getTxAttribute()`. The runtime MBean `com.bea:Type=MessageDrivenEJBRuntime,Name=<ejb>_<destination>,...` is named per destination, so `RuntimeMBeans.ejbRuntimes` matches on its `EJBName` attribute when the name key misses.

**What the span records.** Kind `EJB` with `invocation` MESSAGE, `interface` `javax.jms.MessageListener`, `destination`, `beanType` MESSAGE_DRIVEN, no `jndiNames`; `args[0]` is the message as its API view (`@jms`).

**Verified on.** 12 and 14, the frames above.

**When it fails.** A listener without `info`, or an `info` without a loadable bean class, makes `MessageDelivery.read` return empty; `MessageRecorder` warns once, `message listener hook: listener not recognised`, and the delivery is not recorded.

## 3. FilterChainImpl and ServletStubImpl

Code: `ServletPipelineHook`, `ServletPipelineAdvice`, `ServletRecorder`, `TeeClasses`, `TeeSupport`, `HttpInfoReader`, `ServletApi`.

**What it is.** The two points of WebLogic's servlet pipeline that take the servlet API interfaces (`ServletRequest`, `ServletResponse`) rather than WebLogic's concrete request and response classes.

**Why this point.** A wrapper substituted here is what every filter and the servlet see, so both bodies can be copied as they stream. Above these, `WebAppServletContext.execute(ServletRequestImpl, ServletResponseImpl)` takes the concrete types and nothing can be substituted.

**Class and method.**

| | class and method | role |
| --- | --- | --- |
| 1 | `weblogic.servlet.internal.FilterChainImpl.doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse)` | a web application with filters; re-entered once per filter, and `ServletRecorder` recognises a request already being recorded (identity of the request behind any `ServletRequestWrapper`) and does nothing |
| 2 | `weblogic.servlet.internal.ServletStubImpl.execute(javax.servlet.ServletRequest, javax.servlet.ServletResponse)` | a web application without filters, where the stub is called directly; a no-op when 1 opened the span |

```
com.example.orders.web.OrdersResource.get
org.glassfish.jersey.servlet.ServletContainer.service
weblogic.servlet.internal.StubSecurityHelper$ServletServiceAction.run
weblogic.servlet.internal.StubSecurityHelper.invokeServlet
weblogic.servlet.internal.ServletStubImpl.execute(ServletStubImpl.java:353)          <- advised (2)
weblogic.servlet.internal.TailFilter.doFilter
weblogic.servlet.internal.FilterChainImpl.doFilter(FilterChainImpl.java:87)
weblogic.servlet.internal.RequestEventsFilter.doFilter
weblogic.servlet.internal.FilterChainImpl.doFilter(FilterChainImpl.java:87)          <- advised (1)
weblogic.servlet.internal.WebAppServletContext$ServletInvocationAction.wrapRun
weblogic.servlet.internal.WebAppServletContext.execute
weblogic.servlet.internal.ServletRequestImpl.run
weblogic.servlet.provider.ContainerSupportProviderImpl$WlsRequestExecutor.run
weblogic.work.ExecuteThread.execute
```

**What is read and how.** The tee wrappers are Byte Buddy subclasses of `javax.servlet.http.HttpServletRequestWrapper`, `HttpServletResponseWrapper`, `ServletInputStream` and `ServletOutputStream`, generated by `TeeClasses` against the servlet API the server loaded and cached per API loader by `TeeClassCache`. `TeeSupport` intercepts `getInputStream`, `getReader`, `getOutputStream`, `getWriter` and `sendError`; every byte passes through unchanged and a copy lands in the `HttpCapture`. At exit `HttpInfoReader` reads, through the public servlet API by name: `getMethod`, `getRequestURI`, `getContextPath`, `getServletPath`, `getPathInfo`, `getQueryString`, `getProtocol`, `getScheme`, `getServerName`, `getServerPort`, `getRemoteAddr`, `getContentType`, `getCharacterEncoding`, `getHeaderNames` and `getHeaders` on the request; `getStatus`, `getContentType`, `getCharacterEncoding`, `getHeaderNames` and `getHeaders` on the response; `getUserPrincipal` and `getSession(false)` for the trace. One quirk: WebLogic's response `getHeaderNames()` lists the headers it sets itself as `"Date: "`, `"Content-Length: "`, `"Content-Type: "`, and `getHeaders()` answers only for the bare name; `HttpInfoReader` tries both and records the bare name.

**What the span records.** Kind `HTTP` without a layer: method, URI, every header, status, both bodies in full with their encoding and length, `sentError` when the application called it, and `responseBodyNote` when the container produced the error page through its own stream.

**Verified on.** 12 and 14, the frames above.

**When it fails.** When the wrapper classes cannot be generated, `ServletRecorder` warns once, `servlet tee wrappers cannot be generated, bodies will not be recorded`, and records the exchange without bodies.

## 4. JDBC wrappers

Code: `JdbcStatementHook`, `JdbcStatementAdvice`, `JdbcRecorder`, `JdbcWrappers`, `DataSourceCatalog`.

**What it is.** WebLogic's own wrappers around the driver's statement and result set objects, in `com.bea.core.datasource6.jar`. Every statement a data source hands out is one of them; a driver used directly, not through a data source, never passes them and is not seen.

**Why this point.** The base wrapper classes implement the JDBC methods themselves and the generated `PreparedStatement_<driver class>` subclasses add only vendor extras, so the base classes see every execution whatever the driver (`javap -c`):

```
weblogic.jdbc.wrapper.Statement.executeQuery(String):
   ldc "executeQuery"; invokevirtual preInvocationHandler(String, Object[])
   invokeinterface java/sql/Statement.executeQuery          <- the driver
weblogic.jdbc.wrapper.PreparedStatement.executeQuery():     same shape
```

**Class and method.**

| member | binding |
| --- | --- |
| `weblogic.jdbc.wrapper.Statement`, `PreparedStatement`, `CallableStatement`: public non-static `execute*` | `JdbcStatementAdvice.Execute`: one SQL span per execution, exceptions included |
| the same classes: public non-static `set*(int, ...)` with two or more arguments, `addBatch`, `clearParameters` | `JdbcStatementAdvice.Bind`: the parameters and batches, kept per statement in a weak map until the execution |
| `weblogic.jdbc.wrapper.ResultSet.postInvocationHandler(String, Object[], Object)` | `JdbcStatementAdvice.RowRead`: the wrapper defines `close()` and `getStatement()` but no `next()`; the generated `ResultSet_<driver class>` subclass routes every call through this handler, and a `next` that returned true is one more row the application read |

**What is read and how.** `JdbcWrappers` reads the public field `sql` of a prepared statement (else the `String` argument of a plain `execute*(sql)`), the public field `conn` and its `getPoolName()`, the wrapper's superclass names for the statement type, and the result-set wrapper's private field `stmt`. `DataSourceCatalog` reads once per pool the config MBean `com.bea:Type=JDBCSystemResource,Name=<pool>` → `JDBCResource` → `JDBCDataSourceParams.JNDINames` and `JDBCDriverParams.Url`, and the runtime MBean `com.bea:Type=JDBCDataSourceRuntime,Name=<pool>` → `DatabaseProductName`, `DatabaseProductVersion`.

**What the span records.** Kind `SQL`: `dataSource`, `jndiNames`, `url`, `database`, `statementType`, `operation`, `sql`, `parameters` by position or `batches`, and `updateCount`, `updateCounts`, or `resultSet` with `rows`; `exception` when the driver threw. The agent never moves the cursor.

**Verified on.** 12 with H2 2.1 and 14 with H2 2.3; `javap -p` on both versions shows the members above.

**When it fails.** A server without the wrapper classes on the system class path runs without this hook (it is optional) and records everything but SQL. An unreadable `conn` records `dataSource` as `?`; MBeans that cannot be read leave `jndiNames`, `url` and `database` absent, logged once per pool.

## 5. Class-loader ownership

Code: `ApplicationClassLoaders`, `ResourceHook`.

**What it is.** The rule that decides whether a `@Path` or `@WebService` class belongs to a deployed application, by the loader that defined it, never by its package.

**Why this point.** WebLogic's own `@Path` classes (Coherence management resources, for instance) sit on the server class path; a package rule would need a list that changes per build, a loader rule does not.

**Class and method.** No advice; `ApplicationClassLoaders.isApplication(ClassLoader)` walks the loader's class hierarchy for a name starting with `weblogic.utils.classloaders.`.

| loader class | defines | treated as |
| --- | --- | --- |
| `weblogic.utils.classloaders.GenericClassLoader` | EAR and EJB module classes | application |
| `weblogic.utils.classloaders.ChangeAwareClassLoader` | web module classes | application |
| `com.oracle.classloader.PolicyClassLoader 'weblogic-launcher'` | the server and its class path | not application |
| system, bootstrap | JDK, agent | not application |

**What is read and how.** The loader's class name chain only.

**What the span records.** Nothing directly; it decides whether `ResourceHook` advises the class at all. A class kept out is logged once: `not instrumented (resource annotation, but not an application class)`.

**Verified on.** 12 and 14. WebLogic also loads each application class in an analysis loader of the same kind before the real one, which is why the same class is logged as instrumented twice per install on 14.

**When it fails.** A loader class outside `weblogic.utils.classloaders.` defining application classes would keep those resources out of the recording; the EJB calls under them are still recorded.

## 6. WorkContext

Code: `WorkContextPropagation`, `SpanStack`.

**What it is.** WebLogic's WorkArea API in `com.bea.core.workarea.jar`, which carries key/value entries along with work across threads and inside JMS messages.

**Why this point.** It is the only mechanism that follows both an `@Asynchronous` handoff (a work-manager thread) and a JMS delivery to an MDB in the same server without touching application code.

**Class and method.** `weblogic.workarea.WorkContextHelper.getWorkContextHelper().getWorkContextMap()` → `WorkContextMap.put(String, WorkContext, int)`, `get(String)`, `remove(String)`; `PrimitiveContextFactory.create(String)`; `PrimitiveWorkContext.get()`; the named constants of `weblogic.workarea.PropagationMode`.

**What is read and how.** The agent keeps one key, `ca.gc.cra.xz02.devops.wltrace`, with the value `<traceId>/<spanId>`, written at every span push and pop and removed when the trace ends. The propagation mode is `LOCAL | WORK | RMI | JMS_QUEUE | JMS_TOPIC | SOAP | MIME_HEADER`, composed from the constants by name: on both versions `GLOBAL == DEFAULT == 212`, which leaves out `WORK` (2), the flag that follows a work-manager handoff, and with `GLOBAL` the `@Asynchronous` worker sees nothing while the MDB does.

**What the span records.** On the trace that a handoff caused, `parentTraceId` and `parentSpanId`.

**Verified on.** 12 and 14: `@Asynchronous` (`AsyncExecutor.run` under `weblogic.work.WorkAreaContextWrap.run`) and JMS to an MDB in the same server. `RMI` is in the mode; a trace started on another server is not joined by any reader.

**When it fails.** Without the API every method is a no-op and traces are not linked across threads, logged once at startup; a failing call is logged once and the trace in hand is unaffected.

## 7. Runtime MBeanServer and BeanInfo

Code: `RuntimeMBeans`, `BeanMetadataResolver`, `BeanInfoReader`, `BeanMetadata`.

**What it is.** The MBeanServer that carries WebLogic's `com.bea` runtime tree, and the container's `BeanInfo` family that describes each deployed bean.

**Why this point.** The invoker's arguments name the EJB and carry its `BeanInfo`, but not where the bean is deployed or what it is bound as; the runtime MBeans and a JNDI lookup inside the server complete that, once per bean class.

**Class and method.** Which MBeanServer carries the tree, probed once in this order by `RuntimeMBeans.server`:

1. the platform MBeanServer (holds none by default: `PlatformMBeanServerUsed` is off);
2. `MBeanServerFactory.findMBeanServer(null)` (lists one server, without them either);
3. JNDI `java:comp/env/jmx/runtime`, then `java:comp/jmx/runtime`: the runtime MBeanServer, reachable from any thread through the second name; the first needs a component context and fails on the agent's JMX control thread.

WebLogic's MBeanServer wrapper asserts a context class loader, which control threads lack; `RuntimeMBeans.ensureContextLoader` sets one for the duration of its calls.

**What is read and how.**

| step | source | gives |
| --- | --- | --- |
| runtime MBeans `com.bea:Type=*EJBRuntime,Name=<ejbName>,ApplicationRuntime=<app>,EJBComponentRuntime=<moduleId>,*` | key properties | application, module id (`orders-ejb.jar`; the `java:global` module is the id without its extension); MDBs matched on the `EJBName` attribute (section 2) |
| in-process `InitialContext().lookup("java:global/<app>/<module>/<ejb>[!<interface>]")` | the container's client object | a lookup inside the server needs no credentials |
| that object → `BeanInfo` | `BeanInfoReader.findBeanInfo`: a bounded field search over WebLogic-typed fields, local view `Impl.__WL_localObject.beanInfo`, remote view `Proxy` → `RemoteBusinessIntfProxy.ejbObject` → `Impl_CBV` → `Impl.__WL_remoteObject.beanInfo` | the same `BeanInfo` the invoker's client object carries |
| `weblogic.ejb.container.interfaces.BeanInfo`, a marker interface matched by name | `getBeanClassName()`, `getEJBName()`, `usesBeanManagedTx()` | bean class, EJB name, `BEAN` as transaction attribute when the descriptor has none |
| `SessionBeanInfo` | `getBusinessLocals()`, `getBusinessRemotes()` (`Set<Class<?>>`), `getBusinessJNDINames()` (`Map<Class<?>, Set<String>>`), `isLocalClientView(Class<?>)`, `isStateless()`, `isStateful()`, `isSingleton()` | business interfaces with their view, every name bound per interface (`weblogic-ejb-jar.xml` mappings included), bean type |
| `ClientDrivenBeanInfo` (EJB 2.x) | `getRemoteInterfaceClass()`, `getLocalInterfaceClass()`; `getHomeInterfaceClass()` / `getLocalHomeInterfaceClass()` then `getJNDINameAsString()` / `getLocalJNDINameAsString()` | component interfaces (`ejb2x`), the home's bound names; read only when the home interface exists, because WebLogic binds an internal home name for every bean |
| `weblogic.ejb.container.interfaces.MessageDrivenBeanInfo`, by name | | `beanType` MESSAGE_DRIVEN |
| `com.bea:Name=RuntimeService,Type=weblogic.management.mbeanservers.runtime.RuntimeServiceMBean` → `DomainConfiguration` → `AppDeployments` → `Name`, `SourcePath`, `ModuleType` | | `trace.deployments` |

**What the span records.** `app`, `module`, `beanName`, `jndiNames`, `beanType`, `ejb2x` on EJB spans; `deployments` on the trace.

**Verified on.** 12 and 14; `javap` on `com.oracle.weblogic.ejb.jar` and `com.oracle.weblogic.ejb.wlsapi.jar` of both shows identical signatures for every member above.

**When it fails.** An unreachable MBeanServer or a missing runtime MBean leaves `app` and `module` null and `jndiNames` reduced to the deterministic `java:global` names; `BeanMetadataResolver` logs one `metadata` line per bean class with the problem in parentheses. A renamed `BeanInfo` member shows up as null fields in the recording.

## 8. Principal and session

Code: `ServerRuntime`, `HttpFacts`.

**What it is.** Who made the call and which HTTP session it belongs to, for the trace.

**Why this point.** An HTTP root reads both from its own request. A root that no servlet span opened, such as a T3 call or a timer, has only the thread.

**Class and method.**

| fact | path |
| --- | --- |
| principal | `weblogic.security.Security.getCurrentSubject()` → `javax.security.auth.Subject.getPrincipals()`; the `weblogic.security.principal.WLSUserImpl` entry is the user name, else the first principal |
| HTTP session id of a root no servlet span opened | `weblogic.work.ExecuteThread.getCurrentWork()` → `weblogic.servlet.provider.ContainerSupportProviderImpl$WlsRequestExecutor`, private field `connectionHandler` → `weblogic.servlet.internal.AbstractHttpConnectionHandler.getServletRequest()` → `HttpServletRequest.getSession(false).getId()`; never creates a session |
| server name | system property `weblogic.Name` |

**What is read and how.** By reflection through the context class loader, once resolved and then cached (`getCurrentSubject`).

**What the span records.** `principal`, `httpSessionId` and `serverName` on the trace.

**Verified on.** 12 and 14.

**When it fails.** A missing class or member marks the lookup as failed, warns once, and records null from then on; a server objection to one particular request (an `InvocationTargetException`) records null for that trace only.

## Finding a hook on a new build

1. Install the agent on the new build (`wltrace:enable`, restart, `wltrace:start`, Start) and read `agent.log`: it lists `hook target discovered` and `instrumented` for every class of sections 1 to 4, or `hook targets loaded but not instrumented` when a method shape changed.
2. For each advised method, run `javap -p -cp <jar> <class>` on the module jar named in its section and compare the signature with the table.
3. Locate a moved choke point with a thread dump: hold a call open inside the application code (a breakpoint, a sleep) and run `jcmd <pid> Thread.print`. Read the frames between the caller and the bean: the first `weblogic.*` frame above the application method whose parameters are generic (`Object[]`, the servlet API interfaces, an `InvocationWrapper`) rather than per-deployment generated types is the new target.
4. Confirm the target with the agent: add its name to the hook's `targetClassNames` and the method shape to its `AdviceBinding`, rebuild, and look for `instrumented <class> (retransformed)` in `agent.log` at the next Start.
5. For the reflective reads of section 7, `javap` the `BeanInfo` implementations in `com.oracle.weblogic.ejb.jar`; a renamed member shows up in `agent.log` as a `metadata ... (problem)` line and as null fields in the recording.
6. Verify against the server: drive a request with an EJB call and an SQL statement, compare the EJB span's `ejb` block and the SQL span's `sql` block with a recording from a verified build, then add the build to the table at the top of this document.
