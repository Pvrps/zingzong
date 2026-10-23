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

## Domain

- Created from the "Basic WebLogic Server Domain" template, dev mode, `AdminServer` on
  `127.0.0.1:7001`. Loopback only because the credentials are the defaults from
  `devenv.nix` (`weblogic` / `welcome1`).
- `boot.properties` is written by `wls-domain.sh` so `startWebLogic.sh` never prompts.
- `wls-start -b` backgrounds the server, writes `.local/logs/wls.pid`, and waits for
  `RUNNING mode` in `.local/logs/wls-start.out`. `wls-stop` uses `stopWebLogic.sh` and
  falls back to the pid.
- The server starts cleanly on OpenJDK 21 (`BEA-000377` names Eclipse Adoptium); there
  is no in-server console in 14.1.2, so WLST online and `weblogic.Deployer` are the
  management path.
