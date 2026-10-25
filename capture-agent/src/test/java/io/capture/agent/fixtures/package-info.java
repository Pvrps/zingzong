/**
 * The serializer's fixtures: the deliberately awkward DTOs and JPA entities of the lab's sample
 * applications (ejb-capture-lab, {@code sample-apps/common-dto}), copied here with the package
 * renamed so the unit tests need nothing from the lab. {@code Order} has the lazy
 * {@code @OneToMany} relation, {@code AuditTrail} is neither Serializable nor default-constructible,
 * {@code PaymentMethod} has two implementations behind one declared type. The recordings under
 * {@code src/test/resources/captures} name these types.
 */
package io.capture.agent.fixtures;
