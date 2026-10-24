# WebLogic 12.2.1.4 / JDK 8 versus 14.1.2 / JDK 21

What differs between the two versions the plugin supports, found by running both lab domains
at the same time (`WLS_VERSION=12` on port 7101 with Temurin 1.8.0_504, the default lab on
7001 with Temurin 21.0.12) with the same sample EARs, the same three-action scenario (Create,
Confirm, SOAP) and the plugin under both a JDK 8 and a JDK 21 Maven. Recordings
`rec-20261023-204737` (14, under `.local/captures`) and `rec-20261023-204739` (12, under `.local/captures12`).

| | 12.2.1.4.0 | 14.1.2.0.0 |
| --- | --- | --- |
| build | 1974621, Sep 12 2019, fresh OTN install, no patches | 2171472, Nov 26 2024, no patches |
| JDK | 8 (1.8.0_504) | 21 (21.0.12.1) |
| Java EE | 7 (JAX-RS 2.0, JSON-P 1.0, JPA 2.1, EJB 3.2) | 8 |
| Jersey | 2.22.4 | 2.45 |
| EclipseLink | 2.6.7 | 2.7.15 |
| JDK dynamic proxies | `com.sun.proxy.$ProxyN` | `jdk.proxyN.$ProxyN` |
| attach API | `lib/tools.jar` | `jdk.attach` module |

## What is the same

- **The container choke point.** `SessionLocalMethodInvoker.invoke` and
  `SessionRemoteMethodInvoker.invoke` have the same classes, signatures and even the same line
  numbers in the thread dumps (33; 21 and 54). One `ContainerTargets` entry covers both; the agent
  logs `container choke points of WebLogic 12.2.1.4.0 and 14.1.2.0.0 present` on either.
- **Every reflective path**: `InvocationWrapper`, `MethodDescriptor`, `TransactionPolicy`,
  `BaseLocalObject.beanInfo`, `SessionBeanInfo.getBusinessJNDINames`, the generated client class
  names, `FilterChainImpl.doFilter`, `ServletStubImpl.execute`, the `ExecuteThread` walk for the
  session id, `weblogic.security.Security.getCurrentSubject`.
- **Metadata.** The runtime MBeanServer is `java:comp/jmx/runtime` on both (the platform
  MBeanServer has no `com.bea` MBeans on either); the `*EJBRuntime` names and attributes match; the
  `ejb` block of every EJB span (app, module, bean name and class, interface, invocation,
  transaction attribute, all seven `jndiNames`) is identical byte for byte between the two
  recordings. `trace.deployments[]` has the same names and module types; only `sourcePath`
  differs, because each lab deploys from its own copy (`.local/deploy` vs `.local/deploy12`).
- **The trees.** Same spans in the same order with the same labels in all four traces (session,
  create with 9 spans, confirm with 3, SOAP with 3), the `TaxService.computeTax` hop REMOTE on
  both, no self-invocation spans on either (container hook on both). The `confirmOrder.args[0]
  .orderId ← createOrder.result.id` lineage is detected on both, with the HTTP response body as the
  alternative on both.
- **The sample EARs** deploy unchanged. The only accommodation is a Maven profile (`-Pjava8`:
  Java 8 bytecode, compiled against the Java EE 7 API) because class files built for 21 cannot
  load on 8; no source differs.
- **The page and the goals** behave identically; `capture start` for the 12 domain was run under
  the JDK 8 Maven (port 8078) next to the 14 one under JDK 21 (port 8077) and each listed only its
  own server JVM.

## What differs

1. **JAX-RS `Response` internals (Jersey).** Under the raw-capture rule a resource method returning
   `javax.ws.rs.core.Response` yields Jersey's `OutboundJaxrsResponse` walked in full. Jersey 2.22.4
   holds fewer runtime handles in it than 2.45: the resource span carries **4**
   `runtime handle (class reference)` / `(stream)` warnings on 12 against **12** on 14. The entity,
   status and type are the same. After normalising those internals, the create trace differs in
   nothing but port and body length.
2. **Response body lengths** differ by the width of the order id (a fresh database on 12 gives
   `4`, the 14 one `142670`); the bodies are otherwise the same JSON.
3. **Coherence on the server class path.** 12.2.1.4 loads
   `com.tangosol.coherence.management.internal.resources.VersionsResource`, a `@javax.ws.rs.Path`
   class, before any recording; the resource matcher instrumented it (harmless: never called in the
   scenario) because `com.tangosol.` was not among the ignored packages. It is now. 14.1.2 did not
   have it loaded at install time.
4. **Double loading.** On 14.1.2 `OrdersResource` and `TaxWebService` are instrumented twice per
   install (WebLogic's analysis loader and the application loader, the known behaviour the
   `ClassValue` caches exist for); on 12.2.1.4 once. No effect on the traces.
5. **`Throwable` on JDK 8.** `Throwable.backtrace` is hidden from reflection and `depth` does not
   exist, so a serialized exception on 8 has no `@skipped` entries for them; on 21 both are listed as
   skipped (`JVM-internal frame data; see stackTrace`). The unit test asserts per JDK.
6. **No `--add-opens` on 8.** `capture:enable` reads the domain's JDK from `bin/setDomainEnv.sh`
   (the last literal `JAVA_HOME=`/`SUN_JAVA_HOME=`/`BEA_JAVA_HOME=`; falling back to a running
   server's `java.specification.version`) and writes the flags only for 9+. A JDK 8 JVM would
   refuse to start with them. The block on the 12 domain is three lines shorter.
7. **Attach API.** Under a JDK 8 Maven `com.sun.tools.attach` is not on the class path; the plugin
   loads it from `$JAVA_HOME/lib/tools.jar` (or `../lib/tools.jar` when `java.home` is the JRE).
   Listing, attaching, `startLocalManagementAgent` and the JMX connector all worked; both
   `capture:enable` runs above were made with the JDK 8 Maven.
8. **Install and lab mechanics** (not the product): the 12.2.1.4 installer's `oraparam.ini` lists no
   JRockit section and needs `OpenJDK` added to `VM_TYPES` just like the 14 one
   (`scripts/wls-oraparam-12.ini`); H2 2.3 needs Java 11, so the 12 domain uses H2 2.1.214; the
   JDK proxy frames between the generated client and the bean are `com.sun.proxy.$ProxyN` instead
   of `jdk.proxyN.$ProxyN` (both are pass-through prefixes for the annotation hook's stack walk).

## What was checked and found equal although it could have differed

- `weblogic.Name`, `user.dir` as the domain home and the absence of `weblogic.RootDirectory` in
  a script-started server: the plugin's domain matching works the same on both.
- `getHeaderNames()` listing WebLogic's own response headers as `"Date: "`: present on both,
  handled by trying both forms.
- `java:global`/`java:app`/`java:module` names and the `weblogic-ejb-jar.xml` mappings: identical.
- `recording-AdminServer.json` manifests, trace file naming, atomic writes: identical.

## Lab lessons, not product differences

- A dev-mode WebLogic deploys *nostage* from the path it is given and re-reads that path on
  restart. Deploying both labs straight from `sample-apps/*/target/` meant a rebuild for one
  JDK silently replaced the other domain's application (12.2.1.4 then failed to activate the
  Java 21 classes after a restart: `Module null can not be activated`). `wls-deploy` now copies
  the EARs to `.local/deploy[12]/` first.
- The maven-compiler-plugin does not treat a changed `release` as a reason to recompile;
  `wls-deploy` runs `clean package` so the 8 and 21 builds do not share stale class files.

## Redeploy (hardening item 5)

Identical: both versions keep one copy of the previous deployment's classes per redeploy without
any agent (see `docs/HARDENING.md`, item 5). The only difference is in the tooling: JDK 21's
`jcmd VM.class_hierarchy` lists every loaded copy; JDK 8 has only `GC.class_histogram`, which
shows a retained copy only through classes that still have live instances (the generated
`*Impl` client objects and `OrdersApplication` do).

## Exceptions (hardening item 3)

Same spans, same `EJBException` wrapping at the invoker, same `sendError(500, "Request failed.")`
from Jersey on both. JDK difference only: on JDK 21 the wrapping `EJBException`'s message carries
the helpful `NullPointerException` text (`Cannot invoke "…getAddress()" because … is null`),
computed by `getMessage()`; the NPE's own `detailMessage` field is null on both JDKs, and on JDK 8
the message is null everywhere.
