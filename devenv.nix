{ pkgs, config, ... }:
let
  root = config.env.DEVENV_ROOT;
  localDir = "${root}/.local";
  oracleHome = "${localDir}/oracle";
  domainHome = "${localDir}/domains/capture";

  # Oracle's installer and some of its tooling hardcode /bin/chmod, /bin/cat, /bin/uname
  # and friends, which do not exist on NixOS. `wls-fhs-run <cmd>` runs a command in a
  # bubblewrap namespace where /bin and /usr/bin are populated from these packages;
  # everything else (including /nix/store and the network) is the host as-is.
  fhsBin = pkgs.buildEnv {
    name = "wls-fhs-bin";
    paths = with pkgs; [ bash coreutils gnused gawk gnugrep procps util-linux which hostname ];
    pathsToLink = [ "/bin" ];
  };
  wlsFhsRun = pkgs.writeShellScriptBin "wls-fhs-run" ''
    exec ${pkgs.bubblewrap}/bin/bwrap \
      --dev-bind / / \
      --ro-bind ${fhsBin}/bin /bin \
      --ro-bind ${fhsBin}/bin /usr/bin \
      -- "$@"
  '';
in
{
  packages = with pkgs; [
    wlsFhsRun
    jq
    git
    curl
    coreutils
    gnused
    gawk
    gnugrep
    which
    procps
    hostname
    unzip
  ];

  languages.java = {
    enable = true;
    jdk.package = pkgs.temurin-bin-21;
    maven.enable = true;
  };

  env = {
    ORACLE_HOME = oracleHome;
    MW_HOME = oracleHome;
    WL_HOME = "${oracleHome}/wlserver";
    DOMAIN_HOME = domainHome;
    DOMAIN_NAME = "capture";
    ORACLE_INVENTORY = "${localDir}/oraInventory";

    # Stable JDK path for Oracle scripts, see enterShell.
    WLS_JAVA_HOME = "${localDir}/jdk";

    WLS_ADMIN_HOST = "localhost";
    WLS_ADMIN_PORT = "7001";
    # Loopback only: the dev credentials below must not be reachable from the LAN.
    WLS_LISTEN_ADDRESS = "127.0.0.1";
    WLS_ADMIN_USER = "weblogic";
    WLS_ADMIN_PASSWORD = "welcome1";

    CAPTURE_AGENT_JAR = "${root}/capture-agent/target/capture-agent.jar";
    CAPTURE_OUT = "${localDir}/captures";

    # Embedded H2 for the sample apps; the driver is copied into $DOMAIN_HOME/lib by wls-domain.
    H2_DIR = "${localDir}/h2";
    H2_VERSION = "2.3.232";

    MAVEN_OPTS = "-Dmaven.repo.local=${localDir}/m2";
  };

  enterShell = ''
    export WLS_INSTALLER="''${WLS_INSTALLER:-$HOME/Documents/oracle/installers/fmw_14.1.2.0.0_wls.jar}"

    # WebLogic bakes JAVA_HOME into its install and domain scripts. Point them at a
    # symlink that is refreshed on every shell entry so a nixpkgs JDK bump cannot
    # leave a dangling /nix/store path inside .local/.
    mkdir -p "$DEVENV_ROOT/.local"
    ln -sfn "$JAVA_HOME" "$WLS_JAVA_HOME"
  '';

  scripts = {
    wls-install.exec = ''bash "$DEVENV_ROOT/scripts/wls-install.sh" "$@"'';
    wls-domain.exec = ''bash "$DEVENV_ROOT/scripts/wls-domain.sh" "$@"'';
    wls-start.exec = ''bash "$DEVENV_ROOT/scripts/wls-start.sh" "$@"'';
    wls-stop.exec = ''bash "$DEVENV_ROOT/scripts/wls-stop.sh" "$@"'';
    wls-deploy.exec = ''bash "$DEVENV_ROOT/scripts/wls-deploy.sh" "$@"'';
    capture-start.exec = ''bash "$DEVENV_ROOT/scripts/capture.sh" start "$@"'';
    capture-stop.exec = ''bash "$DEVENV_ROOT/scripts/capture.sh" stop "$@"'';
  };
}
