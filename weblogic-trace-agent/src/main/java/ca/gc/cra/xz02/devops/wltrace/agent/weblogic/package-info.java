/**
 * Every WebLogic internal the agent reads, reached by name and reflection because none of them is
 * visible at compile time. {@link ca.gc.cra.xz02.devops.wltrace.agent.weblogic.SessionInvocation}
 * and {@link ca.gc.cra.xz02.devops.wltrace.agent.weblogic.MessageDelivery} read the objects the
 * container passes at a call; {@link ca.gc.cra.xz02.devops.wltrace.agent.weblogic.BeanMetadataResolver}
 * resolves a bean's deployment facts through {@link ca.gc.cra.xz02.devops.wltrace.agent.weblogic.RuntimeMBeans}
 * and {@link ca.gc.cra.xz02.devops.wltrace.agent.weblogic.BeanInfoReader};
 * {@link ca.gc.cra.xz02.devops.wltrace.agent.weblogic.DataSourceCatalog} and
 * {@link ca.gc.cra.xz02.devops.wltrace.agent.weblogic.JdbcWrappers} describe data sources and statements;
 * {@link ca.gc.cra.xz02.devops.wltrace.agent.weblogic.WorkContextPropagation} carries the trace across
 * threads and {@link ca.gc.cra.xz02.devops.wltrace.agent.weblogic.ServerRuntime} names the server, the
 * principal and the session. {@link ca.gc.cra.xz02.devops.wltrace.agent.weblogic.Reflection} is the
 * shared, never-throwing access they are built on.
 */
package ca.gc.cra.xz02.devops.wltrace.agent.weblogic;
