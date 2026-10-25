/**
 * How the agent gets into the JVM. {@link ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentMain}
 * is the {@code -javaagent} entry point: it parses the one argument, appends the jar to the
 * bootstrap class loader search path and hands over to {@link ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentStartup},
 * which builds every object of the agent and publishes it. {@link ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.InertnessCheck}
 * decides whether this JVM is a WebLogic server the agent can work in;
 * {@link ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentLog} and
 * {@link ca.gc.cra.xz02.devops.wltrace.agent.bootstrap.AgentVersion} are what every other package reports through and as.
 */
package ca.gc.cra.xz02.devops.wltrace.agent.bootstrap;
