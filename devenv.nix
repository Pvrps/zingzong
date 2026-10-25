{ pkgs, ... }:
# A JDK 21 and Maven on the path, nothing else: the build needs no environment. JDK8_HOME is a
# Temurin 8 for `JAVA_HOME=$JDK8_HOME mvn -q install`, the plugin under a Java 8 Maven.
# Delete this file and .envrc and the repository builds identically.
{
  languages.java = {
    enable = true;
    jdk.package = pkgs.temurin-bin-21;
    maven.enable = true;
  };

  env.JDK8_HOME = "${pkgs.temurin-bin-8}";
}
