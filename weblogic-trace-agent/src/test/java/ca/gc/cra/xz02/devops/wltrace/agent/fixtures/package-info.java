/**
 * The serializer's fixtures: deliberately awkward DTOs and JPA entities of the shape a business
 * application passes across its EJB boundaries. {@code Order} has a lazy {@code @OneToMany}
 * relation, {@code AuditTrail} is neither Serializable nor default-constructible,
 * {@code PaymentMethod} has two implementations behind one declared type.
 */
package ca.gc.cra.xz02.devops.wltrace.agent.fixtures;
