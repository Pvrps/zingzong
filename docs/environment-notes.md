# Environment notes (NixOS, WebLogic 14.1.2.0.0, Temurin 21)

Findings from Phase 0 that differ from the assumptions in `docs/PLAN.md`.

## Oracle tooling needs a minimal FHS shim

The OUI launcher (`com.oracle.cie.common.util.Chmod`) execs `/bin/chmod` by absolute
path, and WLST does the same when it writes a domain. NixOS has `/bin/sh` only.
`devenv.nix` therefore defines `wls-fhs-run`, a bubblewrap wrapper that bind-mounts a
coreutils/bash `bin` over `/bin` and `/usr/bin` and leaves everything else (including
`/nix/store` and the network) untouched. `scripts/lib.sh` exposes it as `oracle_run`;
the installer and WLST go through it. The server itself (`startWebLogic.sh`) does not
need it.

Symptoms when it is missing: "Unable to change access permissions on the temporary
directory" from the installer, or non-executable `$DOMAIN_HOME/bin/*.sh` after WLST.

## Installer flags

`scripts/wls-install.sh` passes three non-default flags:

- `-ignoreSysPrereqs`: the certified-OS and package checks (`/bin/rpm`, `lsb_release`).
- `-paramFile scripts/wls-oraparam.ini`: the bundled `oraparam.ini` allows
  `VM_TYPES=HotSpot,IBM,JRockit` and the launcher classifies a JVM by `java.vm.name`.
  Temurin reports `OpenJDK 64-Bit Server VM`, so the launcher aborts with
  "The OpenJDK JVM is not supported on this platform". The copied ini adds `OpenJDK`.
- `-novalidation`: `INST-07004` rejects any path component that starts with a dot, which
  rules out `.local/oracle`. Nothing else in the response file needs validating.

## JDK path stability

Oracle writes `JAVA_HOME` into `oui/.globalEnv.properties` and `bin/setDomainEnv.sh`.
Both point at `$DEVENV_ROOT/.local/jdk`, a symlink that `enterShell` refreshes to the
current `JAVA_HOME` from nixpkgs, so a JDK bump does not strand the install.
`commBaseEnv.sh` only honours `JAVA_HOME` from the environment when `JAVA_VENDOR` is also
set; `lib.sh` exports `JAVA_VENDOR=Oracle` (plain HotSpot settings).

## Sample applications

- `@EJB` injection into a Jersey resource is null unless the war is a CDI archive.
  `orders-web/WEB-INF/beans.xml` (`bean-discovery-mode="all"`) makes Jersey hand the
  resource to CDI; no code change needed.
- EclipseLink 2.7.15 (bundled with 14.1.2) emits `BIGINT IDENTITY` DDL for H2, which
  H2 2.x rejects outside `MODE=LEGACY`. The data-source URL carries that flag.
- Cross-EAR remote lookup from `PricingServiceBean` uses
  `java:global/tax-ear/tax-ejb/TaxService!com.acme.tax.api.TaxService`; the module name
  is the EJB jar file name, hence the version-free `outputFileNameMapping` in the EAR
  plugin config. `weblogic-ejb-jar.xml` also maps `tax/TaxService` and
  `orders/OrderService` for T3 clients.
- `REST → OrderService.createOrder → InventoryService.reserve → PricingService.price →
  TaxService.computeTax` shows up as consecutive `INFO:` lines in
  `.local/logs/wls-start.out`; that is the Phase 0 smoke test.
- T3 clients (Phase 7 `replay-check`): 14.1.2 rejects anonymous remote RMI
  (`BEA-000582`), so pass `Context.SECURITY_PRINCIPAL`/`SECURITY_CREDENTIALS`. A client
  that receives JPA entities needs `$ORACLE_HOME/oracle_common/modules/oracle.toplink/eclipselink.jar`
  for `IndirectList`, next to `wlthint3client.jar`, `common-dto` and `orders-api`.

## Domain

- Created from the "Basic WebLogic Server Domain" template, dev mode, `AdminServer` on
  `127.0.0.1:7001`. Loopback only because the credentials are the defaults from
  `devenv.nix` (`weblogic` / `welcome1`).
- `boot.properties` is written by `wls-domain.sh` so `startWebLogic.sh` never prompts.
- `OrdersDS` (`jdbc/OrdersDS`) is an embedded H2 file database under `.local/h2/`; the
  driver jar is fetched by Maven into `$DOMAIN_HOME/lib` by `wls-domain.sh`.
- `wls-start -b` backgrounds the server, writes `.local/logs/wls.pid`, and waits for
  `RUNNING mode` in `.local/logs/wls-start.out`. `wls-stop` uses `stopWebLogic.sh` and
  falls back to the pid.
- The server starts cleanly on OpenJDK 21 (`BEA-000377` names Eclipse Adoptium); there
  is no in-server console in 14.1.2, so WLST online and `weblogic.Deployer` are the
  management path.
