/**
 * Values as they crossed the boundary, as JSON. {@link ca.gc.cra.xz02.devops.wltrace.agent.serialize.JacksonGraphSerializer}
 * walks every instance field of every object, never a getter, within the
 * {@link ca.gc.cra.xz02.devops.wltrace.agent.serialize.SerializationCeilings}; it knows each class
 * through {@link ca.gc.cra.xz02.devops.wltrace.agent.serialize.TypeInfo}, reads lazy state without
 * loading it, writes leaves through {@code Scalars}, JMS messages as their API view, and marks every
 * place a value is not written as it was with a {@link ca.gc.cra.xz02.devops.wltrace.agent.serialize.Marker}.
 */
package ca.gc.cra.xz02.devops.wltrace.agent.serialize;
