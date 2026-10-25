/**
 * Where the agent touches bytecode. A {@link ca.gc.cra.xz02.devops.wltrace.agent.instrument.Hook}
 * names its target classes, binds {@code *Advice} classes to their methods and reports to a
 * recorder; the five subclasses define the five instrumentation points.
 * {@link ca.gc.cra.xz02.devops.wltrace.agent.instrument.HookInstaller} installs and removes them
 * with Byte Buddy while a recording is active. The advice classes are inlined into WebLogic's
 * methods and call only {@code TraceRuntime}; {@link ca.gc.cra.xz02.devops.wltrace.agent.instrument.ApplicationClassLoaders}
 * decides which annotated classes belong to a deployed application.
 */
package ca.gc.cra.xz02.devops.wltrace.agent.instrument;
