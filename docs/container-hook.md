# WebLogic internals referenced by the agent

Every internal class the agent touches reflectively, with the version it was verified on and
where in the stack it was found, as required by CLAUDE.md. Names can change with a patch set;
each use degrades to null and logs once when it does.

## WebLogic Server 14.1.2.0.0 (Nov 26 2024 build 2171472), JDK 21

### Servlet pipeline entry (Phase 5, `ServletAdvice` / `ServletRecorder`)

Where HTTP root spans open and the tee wrappers are substituted. Found with
`jcmd <pid> Thread.print` during `GET /orders/api/orders/{id}?delay=8000`; the relevant frames,
innermost first:

```
com.acme.orders.web.OrdersResource.get(OrdersResource.java:77)
org.glassfish.jersey.servlet.ServletContainer.service(ServletContainer.java:205)
weblogic.servlet.internal.StubSecurityHelper$ServletServiceAction.run(StubSecurityHelper.java:295)
weblogic.servlet.internal.StubSecurityHelper.invokeServlet(StubSecurityHelper.java:137)
weblogic.servlet.internal.ServletStubImpl.execute(ServletStubImpl.java:353)          <- hooked (2)
weblogic.servlet.internal.TailFilter.doFilter(TailFilter.java:25)
weblogic.servlet.internal.FilterChainImpl.doFilter(FilterChainImpl.java:87)
weblogic.servlet.internal.RequestEventsFilter.doFilter(RequestEventsFilter.java:46)
weblogic.servlet.internal.FilterChainImpl.doFilter(FilterChainImpl.java:87)          <- hooked (1)
weblogic.servlet.internal.WebAppServletContext$ServletInvocationAction.wrapRun(WebAppServletContext.java:3895)
weblogic.servlet.internal.WebAppServletContext.execute(WebAppServletContext.java:2324)
weblogic.servlet.internal.ServletRequestImpl.run(ServletRequestImpl.java:1783)
weblogic.servlet.provider.ContainerSupportProviderImpl$WlsRequestExecutor.run(ContainerSupportProviderImpl.java:287)
weblogic.work.ExecuteThread.execute(ExecuteThread.java:430)
```

| Hook | Class / method | Why |
| --- | --- | --- |
| 1 | `weblogic.servlet.internal.FilterChainImpl.doFilter(javax.servlet.ServletRequest, javax.servlet.ServletResponse)` | the first point that takes the servlet API *interfaces*: wrappers substituted here are what every filter and the servlet see. Re-entered once per filter; the advice recognises the request already being recorded (identity after unwrapping `ServletRequestWrapper`s) and does nothing. |
| 2 | `weblogic.servlet.internal.ServletStubImpl.execute(javax.servlet.ServletRequest, javax.servlet.ServletResponse)` | WebLogic builds no `FilterChainImpl` for a web app without filters (`tax-web`) and calls the stub directly, so this is hooked too; when (1) opened the span it is a no-op. |

Above these, `WebAppServletContext.execute(ServletRequestImpl, ServletResponseImpl)` takes the
concrete WebLogic types, so nothing can be substituted there. The wrappers themselves are
Byte Buddy subclasses of `javax.servlet.http.HttpServletRequestWrapper` /
`HttpServletResponseWrapper` and `ServletInputStream` / `ServletOutputStream`, generated at
runtime against the servlet API the server loaded (`TeeClasses`), because the agent has no
javax dependency. Fallback if this proves fragile on another patch level: a programmatic
filter registered from the bootstrap path (not needed on 14.1.2.0.0).

One container quirk recorded for 14.1.2.0.0: `HttpServletResponse.getHeaderNames()` lists the
headers WebLogic sets itself as `"Date: "`, `"Content-Length: "`, `"Content-Type: "` and
`getHeaders()` only answers for the bare name; `ServletRecorder` tries both.

### Current HTTP session id (Phase 4, now the fallback for non-HTTP roots, `WlsRuntime.httpSessionId`)

Used on root-span entry of an EJB or resource span that has no servlet span above it (a T3
call, a timer). HTTP roots take principal and session from the request itself.

| Step | Class / member | Notes |
| --- | --- | --- |
| 1 | `weblogic.work.ExecuteThread.getCurrentWork()` → `weblogic.work.WorkAdapter` | the request being processed on this thread (`com.bea.core.weblogic.workmanager.jar`) |
| 2 | `weblogic.servlet.provider.ContainerSupportProviderImpl$WlsRequestExecutor` (extends `ServerWorkAdapter`), private field `connectionHandler` | the work item for an HTTP request (`com.oracle.weblogic.servlet.jar`) |
| 3 | `weblogic.servlet.internal.AbstractHttpConnectionHandler.getServletRequest()` → `weblogic.servlet.internal.ServletRequestImpl` | |
| 4 | `javax.servlet.http.HttpServletRequest.getSession(false).getId()` | public API from here; `false` so no session is ever created |

Found by inspecting the jars with `javap` (there is no public "current request" accessor in
14.1.2; `ServletRequestImpl.getOriginalRequest(ServletRequest)` needs a request to start
from). Verified on the server: both traces of recording `rec-20261023-162921` carry the id
created by `POST /orders/api/orders/session`.

### Current principal (`WlsRuntime.principal`)

`weblogic.security.Security.getCurrentSubject()` (static, public) → `javax.security.auth.Subject`
→ `getPrincipals()`; the `weblogic.security.principal.WLSUserImpl` entry is the user name.
Public API, listed here because the class name is matched as a string.

### EJB container choke point (Phase 6, `ContainerAdvice` / `ContainerInvocation`)

Where the `container` hook opens EJB spans: one static method per view that every
business-interface call into a session bean (stateless and singleton alike; MDBs take another
path and are out of scope) passes through. No patches installed (`OPatch`: none; `weblogic.version`
reports 14.1.2.0.0 build 2171472). Found with `jcmd <pid> Thread.print` while
`OrderServiceBean.createOrder` slept on `attributes.delayMillis`, once called locally from the
REST resource and once remotely from a `wlthint3client` program. Innermost first, interceptor
frames (`com.bea.core.repackaged.springframework.aop`, `com.oracle.pitchfork`, `org.jboss.weld`)
elided:

```
local (REST → @EJB OrderServiceLocal)
com.acme.orders.OrderServiceBean.createOrder(OrderServiceBean.java:42)
jdk.proxy8.$Proxy325.createOrder
com.acme.orders.OrderService_wdu466_OrderServiceLocalImpl.__WL_invoke
weblogic.ejb.container.internal.SessionLocalMethodInvoker.invoke(SessionLocalMethodInvoker.java:33)   <- hooked
com.acme.orders.OrderService_wdu466_OrderServiceLocalImpl.createOrder
com.acme.orders.web.OrdersResource.create(OrdersResource.java:53)

remote (T3 → OrderServiceRemote)
com.acme.orders.OrderServiceBean.createOrder(OrderServiceBean.java:42)
jdk.proxy8.$Proxy325.createOrder
com.acme.orders.OrderService_wdu466_OrderServiceRemoteImpl.__WL_invoke
weblogic.ejb.container.internal.SessionRemoteMethodInvoker.invokeInternal(SessionRemoteMethodInvoker.java:54)
weblogic.ejb.container.internal.SessionRemoteMethodInvoker.invoke(SessionRemoteMethodInvoker.java:21)  <- hooked
com.acme.orders.OrderService_wdu466_OrderServiceRemoteImpl.createOrder
com.acme.orders.OrderService_wdu466_OrderServiceRemoteImpl_WLSkel.invoke
weblogic.rmi.internal.BasicServerRef.invoke(BasicServerRef.java:694)
```

| Hook | Class / method (`com.oracle.weblogic.ejb.jar`) |
| --- | --- |
| local | `public static Object weblogic.ejb.container.internal.SessionLocalMethodInvoker.invoke(Invokable, BaseLocalObject, InvocationWrapper, Object[], int) throws Throwable` |
| remote | `public static Object weblogic.ejb.container.internal.SessionRemoteMethodInvoker.invoke(Invokable, BaseRemoteObject, InvocationWrapper, Object[], int) throws Throwable` |

These are the only `*MethodInvoker` classes in the jar besides `WSOMethodInvoker` (web service
objects). The advice matches `invoke`, static, five arguments. What it reads from the arguments,
all by name:

| Argument | Member | Gives |
| --- | --- | --- |
| 0 `Invokable` (generated `<pkg>.<EjbName>_<hash>_<Iface>Impl`) | its interfaces | the invoked business interface: implemented directly for a local view; for a remote view the impl implements a generated `<pkg>.<EjbName>_<hash>_<Iface>RIntf extends java.rmi.Remote` and the name is resolved against the bean's known interfaces |
| 1 `BaseLocalObject` / `BaseRemoteObject` | protected field `beanInfo` (`weblogic.ejb.container.interfaces.BeanInfo`) → `getBeanClassName()` | the bean implementation class |
| 2 `InvocationWrapper` | `getMethodDescriptor()` → `MethodDescriptor.getApplicationName()`, `getModuleId()`, `getEjbName()`, `getMethod()`, `getTransactionPolicy().getTxAttribute()` (a `javax.ejb.TransactionAttributeType`); `isLocal()` | application, module, EJB name, the interface `Method`, the effective transaction attribute, local/remote |
| 3 `Object[]` | | the call arguments (serialized at exit, like the annotation hook) |

Startup self-check (`FallbackHook`): after installation, if either invoker class is loaded but
was not transformed, the agent warns and switches to the annotation hook. A class that is loaded
*during* installation (a loaded target described reflectively pulls in the classes its methods
mention) is skipped by Byte Buddy's circularity lock; `CaptureHook` retransforms such named
targets explicitly afterwards (observed for `FilterChainImpl` on the first recording after a
restart).

### EJB metadata (Phase 6, `WlsMetadata`)

Application, module and JNDI names per bean class, resolved once per class (`ClassValue`).

| Step | Source | Notes |
| --- | --- | --- |
| 1 | the bean class's `@Stateless/@Stateful/@Singleton(name=)` and `@Local/@Remote` annotations, by name | EJB name, business interfaces |
| 2 | runtime MBeans `com.bea:Type=StatelessEJBRuntime|SingletonEJBRuntime|…,Name=<ejbName>,ApplicationRuntime=<app>,EJBComponentRuntime=<moduleId>,*` | application, module id (`orders-ejb.jar`; the `java:global` module is the id without its extension). The MBeans carry no implementation class and no JNDI names. |
| 3 | in-process `InitialContext().lookup("java:global/<app>/<module>/<ejb>[!<iface>]")` | the container's client object for the bean; a lookup needs no credentials from inside the server |
| 4 | that object → `beanInfo` (bounded field search: local view `Impl.__WL_localObject.beanInfo`; remote view `Proxy` → `RemoteBusinessIntfProxy.ejbObject` (`Impl_CBV`) → `Impl.__WL_remoteObject.beanInfo`) → `SessionBeanInfo.getBusinessJNDINames()` (`Map<Class, Set<String>>`), `getBeanClassName()` | every name the container bound per interface, descriptor mappings (`weblogic-ejb-jar.xml`, e.g. `orders/OrderService`) included, and the check that the candidate is this bean class |

Which MBeanServer (verified in-process, no credentials): the platform MBeanServer holds no
`com.bea` MBeans on this domain (`PlatformMBeanServerUsed` is off by default);
`MBeanServerFactory.findMBeanServer(null)` lists one server, without them either; the runtime
MBeanServer is reachable through JNDI as `java:comp/jmx/runtime` from any thread, while
`java:comp/env/jmx/runtime` needs a component context (fails on the agent's JMX control thread).
`WlsMetadata.server()` tries them in that order and logs the answer once. The MBeanServer
wrapper asserts a context class loader, which control threads lack; the agent sets one for the
duration of its calls.

Deployments: `com.bea:Name=RuntimeService,Type=weblogic.management.mbeanservers.runtime.RuntimeServiceMBean`
→ attribute `DomainConfiguration` → `AppDeployments` → `Name`, `SourcePath`, `ModuleType`.

## WebLogic Server 12.2.1.4.0 (Sep 12 2019 build 1974621), JDK 8 (Temurin 1.8.0_504)

Verified on the lab's second domain (`WLS_VERSION=12`), fresh OTN install, no patches
(`inventory/patches` absent; OPatch itself does not run under the NixOS shim). Every path above
resolved unchanged; the agent selects the same `ContainerTargets` entry on both versions.

### EJB container choke point

Same classes, same methods, same line numbers as 14.1.2.0.0. From `jcmd <pid> Thread.print`
while `OrderServiceBean.createOrder` slept on `attributes.delayMillis`; interceptor frames elided:

```
local (REST → @EJB OrderServiceLocal)
com.acme.orders.OrderServiceBean.createOrder(OrderServiceBean.java:42)
com.sun.proxy.$Proxy254.createOrder                                                     <- JDK 8: com.sun.proxy, not jdk.proxyN
com.acme.orders.OrderService_wdu466_OrderServiceLocalImpl.__WL_invoke
weblogic.ejb.container.internal.SessionLocalMethodInvoker.invoke(SessionLocalMethodInvoker.java:33)   <- hooked
com.acme.orders.OrderService_wdu466_OrderServiceLocalImpl.createOrder
com.acme.orders.web.OrdersResource.create(OrdersResource.java:53)

remote (T3 → OrderServiceRemote)
com.acme.orders.OrderServiceBean.createOrder(OrderServiceBean.java:42)
com.sun.proxy.$Proxy254.createOrder
com.acme.orders.OrderService_wdu466_OrderServiceRemoteImpl.__WL_invoke
weblogic.ejb.container.internal.SessionRemoteMethodInvoker.invokeInternal(SessionRemoteMethodInvoker.java:54)
weblogic.ejb.container.internal.SessionRemoteMethodInvoker.invoke(SessionRemoteMethodInvoker.java:21)  <- hooked
com.acme.orders.OrderService_wdu466_OrderServiceRemoteImpl.createOrder
com.acme.orders.OrderService_wdu466_OrderServiceRemoteImpl_WLSkel.invoke
weblogic.rmi.internal.BasicServerRef.invoke(BasicServerRef.java:645)
```

`javap` of `com.oracle.weblogic.ejb.jar` (12.2.1.4.0) shows the identical signatures:
`SessionLocalMethodInvoker.invoke(Invokable, BaseLocalObject, InvocationWrapper, Object[], int)`,
`SessionRemoteMethodInvoker.invoke(Invokable, BaseRemoteObject, InvocationWrapper, Object[], int)`,
`InvocationWrapper.getMethodDescriptor()/getBean()/isLocal()`, `MethodDescriptor.getApplicationName()/
getModuleId()/getEjbName()/getMethod()/getTransactionPolicy()`, `TransactionPolicy.getTxAttribute()`,
`BaseLocalObject.beanInfo`, `SessionBeanInfo.getBusinessJNDINames()`, `BeanInfo.getBeanClassName()`.
The generated client classes follow the same naming (`OrderService_wdu466_OrderServiceLocalImpl`,
`*RemoteImpl`, `*_WLSkel`); the JDK proxy in between is `com.sun.proxy.$ProxyN` on JDK 8 and
`jdk.proxyN.$ProxyN` on JDK 21, which `InvokedInterface` treats alike (both pass-through prefixes).

### Servlet pipeline, HTTP session, principal, metadata

`FilterChainImpl.doFilter` and `ServletStubImpl.execute` exist with the same shapes and are
retransformed at install (the circularity-lock case of `FilterChainImpl` occurs here too and is
handled the same way). The runtime MBeanServer is reached as `java:comp/jmx/runtime`, the platform
MBeanServer holds no `com.bea` MBeans, `EJBComponentRuntime` / `*EJBRuntime` names and attributes
are the same, and `jndiNames` come out identical (including `java:app`, `java:module` and the
`weblogic-ejb-jar.xml` mappings).

### Differences that are not the agent's

See `docs/weblogic-12-vs-14.md`: Jersey version (fewer `@handle` markers in a JAX-RS `Response`),
Coherence's management REST resources being `@Path`-annotated classes on the server class path
(now in the hook's ignored packages), JDK 8 reflection hiding `Throwable.backtrace`.
