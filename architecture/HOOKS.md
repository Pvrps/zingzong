# WebLogic internals the agent touches

Every WebLogic class, method, field and MBean the agent reaches, by name and reflection, with
the version and build it is verified on and the stack frames that located it. The agent has no
compile dependency on any of them; each use degrades to null and logs once when a name is
missing.

Verified on:

| | WebLogic Server | build | JDK |
| --- | --- | --- | --- |
| 14 | 14.1.2.0.0 | Tue Nov 26 2024, 2171472, no patches | Temurin 21.0.12 |
| 12 | 12.2.1.4.0 | Thu Sep 12 2019, 1974621, no patches | Temurin 1.8.0_504 |

Every name below resolves identically on both. Stack frames are from `jcmd <pid> Thread.print` on
the 14 server while a sample call slept; where 12 differs in a frame it is noted.

## Contents

1. [Session invokers](#1-session-invokers-ejbrecorder)
2. [Message-driven beans: MDListener](#2-message-driven-beans-mdlistener-messagerecorder)
3. [Servlet pipeline: FilterChainImpl and ServletStubImpl](#3-servlet-pipeline-filterchainimpl-and-servletstubimpl-servletrecorder)
4. [JDBC wrappers](#4-jdbc-wrappers-jdbcrecorder)
5. [Class-loader ownership](#5-class-loader-ownership-applicationloaders)
6. [WorkContext propagation](#6-workcontext-propagation-workcontextpropagation)
7. [Runtime MBeanServer and BeanInfo](#7-runtime-mbeanserver-and-beaninfo-wlsmetadata-wlsdatasources)
8. [Principal, HTTP session, server name](#8-principal-http-session-server-name-wlsruntime)
9. [Re-verifying on a new WebLogic build](#9-re-verifying-on-a-new-weblogic-build)

## 1. Session invokers (`EjbRecorder`)

Every business-interface call into a session bean, stateless, stateful, singleton or EJB 2.x,
passes one static method per view in `com.oracle.weblogic.ejb.jar`:

| view | `weblogic.ejb.container.internal.` |
| --- | --- |
| local | `public static Object SessionLocalMethodInvoker.invoke(Invokable, BaseLocalObject, InvocationWrapper, Object[], int) throws Throwable` |
| remote | `public static Object SessionRemoteMethodInvoker.invoke(Invokable, BaseRemoteObject, InvocationWrapper, Object[], int) throws Throwable` |

`ContainerAdvice` matches `invoke`, static, five arguments. Frames, innermost first, while
`OrderServiceBean.createOrder` slept; interceptor frames (`com.bea.core.repackaged.springframework.aop`,
`com.oracle.pitchfork`, `org.jboss.weld`) elided:

```
local (REST → @EJB OrderServiceLocal)
com.acme.orders.OrderServiceBean.createOrder
jdk.proxy8.$Proxy325.createOrder                                                        12: com.sun.proxy.$ProxyN
com.acme.orders.OrderService_wdu466_OrderServiceLocalImpl.__WL_invoke
weblogic.ejb.container.internal.SessionLocalMethodInvoker.invoke(SessionLocalMethodInvoker.java:33)   <- advised
com.acme.orders.OrderService_wdu466_OrderServiceLocalImpl.createOrder
com.acme.orders.web.OrdersResource.create

remote (T3 → OrderServiceRemote)
com.acme.orders.OrderServiceBean.createOrder
jdk.proxy8.$Proxy325.createOrder
com.acme.orders.OrderService_wdu466_OrderServiceRemoteImpl.__WL_invoke
weblogic.ejb.container.internal.SessionRemoteMethodInvoker.invokeInternal(SessionRemoteMethodInvoker.java:54)
weblogic.ejb.container.internal.SessionRemoteMethodInvoker.invoke(SessionRemoteMethodInvoker.java:21)  <- advised
com.acme.orders.OrderService_wdu466_OrderServiceRemoteImpl.createOrder
com.acme.orders.OrderService_wdu466_OrderServiceRemoteImpl_WLSkel.invoke
weblogic.rmi.internal.BasicServerRef.invoke(BasicServerRef.java:694)                    12: :645

EJB 2.x (home → EJBObject), same invoker
com.acme.tax.RateLookupBean.rateFor
jdk.proxy12.$Proxy352.rateFor
com.acme.tax.RateLookup_j2unwk_EOImpl.__WL_invoke
weblogic.ejb.container.internal.SessionRemoteMethodInvoker.invoke(SessionRemoteMethodInvoker.java:21)  <- advised
com.acme.tax.RateLookup_j2unwk_EOImpl.rateFor
com.acme.tax.RateLookup_j2unwk_EOImpl_WLSkel.invoke
```

`home.create()` and `EJBObject.remove()` do not pass the invokers and are not spans. What
`ContainerInvocation` reads from the arguments:

| argument | member | gives |
| --- | --- | --- |
| 0 `Invokable` (generated `<pkg>.<Ejb>_<hash>_<Iface>Impl`) | its interfaces | the business interface: implemented directly for a local view; a remote view implements a generated `<pkg>.<Ejb>_<hash>_<Iface>RIntf extends java.rmi.Remote` whose simple name is resolved against the bean's interfaces |
| 1 `BaseLocalObject` / `BaseRemoteObject` | protected field `beanInfo` | the container's `BeanInfo` (section 7) |
| 2 `InvocationWrapper` | `getMethodDescriptor()` → `MethodDescriptor.getApplicationName()`, `getModuleId()`, `getEjbName()`, `getMethod()`, `getTransactionPolicy().getTxAttribute()`; `isLocal()` | application, module, EJB name, the interface `Method`, the effective `javax.ejb.TransactionAttributeType`, local or remote |
| 3 `Object[]` | | the call arguments, serialized at exit |

A class loaded *during* installation is skipped by Byte Buddy's circularity lock (describing a
loaded target resolves the types its methods mention); `CaptureHook` retransforms such named
targets explicitly afterwards and warns about any still untransformed.

## 2. Message-driven beans: `MDListener` (`MessageRecorder`)

```
com.acme.notify.OrderEventListener.onMessage
jdk.proxy9.$Proxy261.onMessage
weblogic.ejb.container.internal.MDListener.execute(MDListener.java:459)
weblogic.ejb.container.internal.MDListener.transactionalOnMessage(MDListener.java:370)
weblogic.ejb.container.internal.MDListener.onMessage(MDListener.java:306)          <- advised
weblogic.jms.client.JMSSession.onMessage(JMSSession.java:5172)
weblogic.work.SelfTuningWorkManagerImpl$WorkAdapterImpl.run
```

| member | gives |
| --- | --- |
| `public void MDListener.onMessage(javax.jms.Message)` | the advised method; the message is its argument |
| `MDListener.info` (private final `weblogic.ejb.container.interfaces.MessageDrivenBeanInfo`) | `getBeanClassName()`, `getClassLoader()` (the bean class in its own loader), `getEJBName()`, `getDestinationName()`, `getOnMessageMethodDescriptor().getTransactionPolicy().getTxAttribute()` |
| runtime MBean `com.bea:Type=MessageDrivenEJBRuntime,Name=<ejb>_<destination>,...`, attribute `EJBName` | application and module: the MBean is named per destination, so `WlsMetadata` matches on the attribute when the name key misses |

MDBs have no client view: `ejb.invocation` is `MESSAGE`, `ejb.interface` is
`javax.jms.MessageListener`, `jndiNames` is absent.

## 3. Servlet pipeline: `FilterChainImpl` and `ServletStubImpl` (`ServletRecorder`)

Frames during `GET /orders/api/orders/{id}`:

```
com.acme.orders.web.OrdersResource.get
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

| | class / method | why |
| --- | --- | --- |
| 1 | `weblogic.servlet.internal.FilterChainImpl.doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse)` | the first point that takes the servlet API interfaces: wrappers substituted here are what every filter and the servlet see. Re-entered once per filter; the advice recognises a request already being recorded (identity after unwrapping `ServletRequestWrapper`s) and does nothing. |
| 2 | `weblogic.servlet.internal.ServletStubImpl.execute(javax.servlet.ServletRequest, javax.servlet.ServletResponse)` | a web application without filters gets no `FilterChainImpl`; the stub is called directly. When (1) opened the span it is a no-op. |

Above these, `WebAppServletContext.execute(ServletRequestImpl, ServletResponseImpl)` takes the
concrete WebLogic types, so nothing can be substituted there. The tee wrappers are Byte Buddy
subclasses of `javax.servlet.http.HttpServletRequestWrapper` / `HttpServletResponseWrapper` and
`ServletInputStream` / `ServletOutputStream`, generated against the servlet API the server
loaded (`TeeClasses`).

One quirk: `HttpServletResponse.getHeaderNames()` lists the headers WebLogic sets itself as
`"Date: "`, `"Content-Length: "`, `"Content-Type: "`, and `getHeaders()` answers only for the
bare name; `ServletRecorder` tries both and records the bare name.

## 4. JDBC wrappers (`JdbcRecorder`)

Every statement a data source hands out is one of WebLogic's own wrappers around the driver's
object, in `com.bea.core.datasource6.jar`; the base classes implement the JDBC methods
themselves (`javap -c`):

```
weblogic.jdbc.wrapper.Statement.executeQuery(String):
   ldc "executeQuery"; invokevirtual preInvocationHandler(String, Object[])
   invokeinterface java/sql/Statement.executeQuery          <- the driver
weblogic.jdbc.wrapper.PreparedStatement.executeQuery():     same shape
```

| member | gives |
| --- | --- |
| `weblogic.jdbc.wrapper.Statement` / `PreparedStatement` / `CallableStatement`, public `execute*` | one SQL span per execution (`JdbcAdvice.Execute`), exceptions included |
| `Statement.sql` (public `String`), or the `String` argument of a plain `execute*(sql)` | `sql.sql` |
| `Statement.conn` (public `weblogic.jdbc.wrapper.Connection`) → `getPoolName()` | `sql.dataSource` |
| `PreparedStatement.set*(int, …)`, `setNull`, `addBatch`, `clearParameters` | the bound parameters and batches (`JdbcAdvice.Bind`), kept per statement in a weak map until the execute |
| `weblogic.jdbc.wrapper.ResultSet.postInvocationHandler(String, Object[], Object)`; private field `stmt` | the wrapper defines `close()` and `getStatement()` but no `next()`; the generated `ResultSet_<driver class>` subclass routes every call through this handler. A `next` that returned true is one more row the application read (`sql.rows`); nothing else is recorded and the agent never moves the cursor. |
| config MBean `com.bea:Type=JDBCSystemResource,Name=<pool>` → `JDBCResource` → `JDBCDataSourceParams.JNDINames`, `JDBCDriverParams.Url`; runtime `com.bea:Type=JDBCDataSourceRuntime,Name=<pool>` → `DatabaseProductName`, `DatabaseProductVersion` | `sql.jndiNames`, `sql.url`, `sql.database` (`WlsDataSources`, once per pool) |

The generated `PreparedStatement_<driver class>` subclasses add only vendor extras, so the base
classes see every execution whatever the driver (verified with H2 2.3 on 14 and H2 2.1 on 12).
A driver used directly, not through a data source, never passes the wrapper and is not seen.

## 5. Class-loader ownership (`ApplicationLoaders`)

Whether a `@Path` or `@WebService` class is an application class is decided by the loader that
defined it, never by its package. Seen in both domains:

| loader class | defines | treated as |
| --- | --- | --- |
| `weblogic.utils.classloaders.GenericClassLoader` | EAR and EJB module classes | application |
| `weblogic.utils.classloaders.ChangeAwareClassLoader` | web module classes | application |
| `com.oracle.classloader.PolicyClassLoader 'weblogic-launcher'` | the server and its class path, Coherence's `@Path` management resources included | not application |
| system, bootstrap | JDK, agent | not application |

The rule is "a subclass of `weblogic.utils.classloaders.*`". WebLogic also loads each application
class in an analysis loader of the same kind before the real one, which is why the same class is
logged as instrumented twice per install on 14.

## 6. WorkContext propagation (`WorkContextPropagation`)

`com.bea.core.workarea.jar`: `weblogic.workarea.WorkContextHelper.getWorkContextHelper().getWorkContextMap()`
→ `WorkContextMap.put(String, WorkContext, int)` / `get(String)` / `remove(String)`,
`PrimitiveContextFactory.create(String)`, `PrimitiveWorkContext.get()`. The agent keeps one key,
`io.capture.trace`, with value `<traceId>/<spanId>`, written at every span push and pop and removed
when the trace ends. The propagation mode is `LOCAL | WORK | RMI | JMS_QUEUE | JMS_TOPIC | SOAP |
MIME_HEADER` composed from `weblogic.workarea.PropagationMode`'s named constants: on both versions
`GLOBAL == DEFAULT == 212`, which leaves out `WORK` (2), the flag that follows a work-manager
handoff; with `GLOBAL` the `@Asynchronous` worker saw nothing while the MDB did.

Verified handoffs: `@Asynchronous` (`AsyncExecutor.run` under `weblogic.work.WorkAreaContextWrap.run`)
and JMS to an MDB in the same server. `RMI` is in the mode but not verified across servers.

## 7. Runtime MBeanServer and `BeanInfo` (`WlsMetadata`, `WlsDataSources`)

Which MBeanServer carries the `com.bea` runtime tree, probed once in this order:

1. the platform MBeanServer (holds none on these domains: `PlatformMBeanServerUsed` is off by default);
2. `MBeanServerFactory.findMBeanServer(null)` (lists one server, without them either);
3. JNDI `java:comp/env/jmx/runtime`, then `java:comp/jmx/runtime`: the runtime MBeanServer, reachable from any thread through the second name; the first needs a component context and fails on the agent's JMX control thread.

WebLogic's MBeanServer wrapper asserts a context class loader, which control threads lack; the
agent sets one for the duration of its calls.

| step | source | gives |
| --- | --- | --- |
| runtime MBeans `com.bea:Type=*EJBRuntime,Name=<ejbName>,ApplicationRuntime=<app>,EJBComponentRuntime=<moduleId>,*` | key properties | application, module id (`orders-ejb.jar`; the `java:global` module is the id without its extension). MDBs: matched on the `EJBName` attribute (section 2). |
| in-process `InitialContext().lookup("java:global/<app>/<module>/<ejb>[!<iface>]")` | the container's client object | a lookup inside the server needs no credentials |
| that object → `BeanInfo` | bounded field search over WebLogic-typed fields: local view `Impl.__WL_localObject.beanInfo`; remote view `Proxy` → `RemoteBusinessIntfProxy.ejbObject` → `Impl_CBV` → `Impl.__WL_remoteObject.beanInfo` | the same `BeanInfo` the invoker's client object carries |
| `weblogic.ejb.container.interfaces.BeanInfo` (marker interface, matched by name) | `getBeanClassName()`, `getEJBName()`, `usesBeanManagedTx()` | bean class, EJB name, `BEAN` as transaction attribute when the descriptor has none |
| `SessionBeanInfo` | `getBusinessLocals()`, `getBusinessRemotes()` (`Set<Class<?>>`), `getBusinessJNDINames()` (`Map<Class<?>, Set<String>>`), `isLocalClientView(Class<?>)`, `isStateless()`, `isStateful()`, `isSingleton()` | business interfaces with their view, every name bound per interface (`weblogic-ejb-jar.xml` mappings included), bean type |
| `ClientDrivenBeanInfo` (EJB 2.x) | `getRemoteInterfaceClass()`, `getLocalInterfaceClass()`; `getHomeInterfaceClass()` / `getLocalHomeInterfaceClass()` then `getJNDINameAsString()` / `getLocalJNDINameAsString()` | component interfaces (`ejb.ejb2x`), the home's bound names. Read only when the home interface exists: WebLogic binds an internal home name for every bean. |
| `weblogic.ejb.container.interfaces.MessageDrivenBeanInfo` (by name) | | `ejb.beanType = MESSAGE_DRIVEN` |
| `com.bea:Name=RuntimeService,Type=weblogic.management.mbeanservers.runtime.RuntimeServiceMBean` → `DomainConfiguration` → `AppDeployments` → `Name`, `SourcePath`, `ModuleType` | | `trace.deployments` |

`javap` on `com.oracle.weblogic.ejb.jar` and `com.oracle.weblogic.ejb.wlsapi.jar` of both
versions shows identical signatures for every member above.

## 8. Principal, HTTP session, server name (`WlsRuntime`)

| fact | path |
| --- | --- |
| principal | `weblogic.security.Security.getCurrentSubject()` → `javax.security.auth.Subject.getPrincipals()`; the `weblogic.security.principal.WLSUserImpl` entry is the user name, else the first principal |
| HTTP session id of a root that no servlet span opened (a T3 call, a timer) | `weblogic.work.ExecuteThread.getCurrentWork()` → `weblogic.servlet.provider.ContainerSupportProviderImpl$WlsRequestExecutor`, private field `connectionHandler` → `weblogic.servlet.internal.AbstractHttpConnectionHandler.getServletRequest()` → `HttpServletRequest.getSession(false).getId()`; never creates a session |
| server name | system property `weblogic.Name` |

HTTP roots take principal and session from the request itself.

## 9. Re-verifying on a new WebLogic build

1. Install the build in the lab (`wls-install`, `wls-domain`, `wls-deploy`) and run `capture enable`,
   a restart and `capture start`; `agent.log` lists `hook target discovered` and `instrumented`
   for every class in sections 1 to 4, or `hook targets loaded but not instrumented` when a
   method shape changed.
2. For each advised method, `javap -p -cp <jar> <class>` on the module jar named in its section
   and compare the signature with the table.
3. Locate a moved choke point with a thread dump: call the sample application with a sleep
   (`attributes.delayMillis` on Create order, region `DUMP` on the EJB 2.x bean, `confirmedBy=dump`
   on the MDB) and run `jcmd <pid> Thread.print`; the first `weblogic.*` frame above the bean
   method that takes the generic arguments is the new target.
4. For the reflective reads of section 7, `javap` the `BeanInfo` implementations in
   `com.oracle.weblogic.ejb.jar`; a renamed member shows up in `agent.log` as `metadata ...
   (problem)` and as null fields in the recording.
5. Run the lab's three-action scenario and compare an EJB span's `ejb` block and an SQL span's
   `sql` block with a recording from a verified build; then add the build to the table at the top.
