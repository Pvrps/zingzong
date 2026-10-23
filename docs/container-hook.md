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

### Transaction status (`WlsRuntime.transactionStatus`)

JNDI `java:comp/TransactionSynchronizationRegistry` → `getTransactionStatus()`; standard JTA,
nothing WebLogic-specific, registry cached per thread.

### Container hook (Phase 6)

Not yet located. Candidates in 14.x: `weblogic.ejb.container.internal.SessionLocalMethodInvoker`,
`SessionRemoteMethodInvoker`, `BaseLocalObject.__WL_preInvoke/__WL_postInvoke`,
`InvocationWrapper`. To be confirmed with a thread dump in Phase 6.

## WebLogic Server 12.2.1.4 / JDK 8

Not verified yet. The same reflective paths are expected to exist; the agent logs once and
records nulls if they do not.
