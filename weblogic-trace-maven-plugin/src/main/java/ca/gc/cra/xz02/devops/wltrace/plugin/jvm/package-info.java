/**
 * The running servers. {@link ca.gc.cra.xz02.devops.wltrace.plugin.jvm.LocalJvms} lists the local JVMs
 * through the attach API; {@link ca.gc.cra.xz02.devops.wltrace.plugin.jvm.DomainStateMonitor} finds the
 * domain's servers among them, talks to each agent through {@link ca.gc.cra.xz02.devops.wltrace.plugin.jvm.AgentControl}
 * and evaluates the {@link ca.gc.cra.xz02.devops.wltrace.plugin.jvm.DomainState};
 * {@link ca.gc.cra.xz02.devops.wltrace.plugin.jvm.MonitoredDomainControl} presents that to the viewer.
 */
package ca.gc.cra.xz02.devops.wltrace.plugin.jvm;
