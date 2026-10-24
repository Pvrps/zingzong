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
