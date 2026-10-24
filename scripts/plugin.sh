#!/usr/bin/env bash
# The product, driven as a user would: run a goal of capture-maven-plugin against the lab
# domain of $WLS_VERSION (14 by default, 12 for the 12.2.1.4 / JDK 8 lab).
#
#   capture enable|disable|start [extra -D options]
#
# Needs `mvn -q install` first: plugin and agent come from the local repository. Runs from
# the repository root (the goals are aggregators, so the reactor pom is harmless) with the
# repository's .mvn/settings.xml. JAVA_HOME is left alone: set it to $JDK8_HOME to exercise
# the plugin under a Java 8 Maven.

# lib.sh points JAVA_HOME at the domain's JDK for the Oracle scripts; the plugin must run in
# the JDK the caller's Maven uses, so remember that first.
CALLER_JAVA_HOME="${JAVA_HOME:-}"

. "$(dirname "${BASH_SOURCE[0]}")/lib.sh"
require_env CAPTURE_OUT

goal="${1:?usage: capture enable|disable|start [-D...]}"
shift
case "$goal" in
  enable|disable|start) ;;
  *) die "unknown goal: $goal (enable|disable|start)" ;;
esac

if [ -n "$CALLER_JAVA_HOME" ]; then
  export JAVA_HOME="$CALLER_JAVA_HOME"
  export PATH="$JAVA_HOME/bin:$PATH"
fi

cd "$DEVENV_ROOT"
exec mvn -q io.capture:capture-maven-plugin:0.1.0-SNAPSHOT:"$goal" \
  -Dcapture.domain="$DOMAIN_HOME" -Dcapture.out="$CAPTURE_OUT" "$@"
