# WebLogic internals referenced by the agent

Every internal class the agent touches reflectively, with the version it was verified on and
where in the stack it was found, as required by CLAUDE.md. Names can change with a patch set;
each use degrades to null and logs once when it does.

## WebLogic Server 14.1.2.0.0 (Nov 26 2024 build 2171472), JDK 21

### Current HTTP session id (Phase 4 stopgap, `WlsRuntime.httpSessionId`)

Used on root-span entry so a trace can be linked to the browser session. Replaced by the
Phase 5 servlet hook, which sees the request directly.

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
