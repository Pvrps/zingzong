package io.capture.agent.fixtures;

import java.io.Serializable;

/**
 * Polymorphic payment details on an order request. Two implementations exist so the
 * capture serializer has a declared-type-vs-runtime-type case to handle (@type).
 */
public interface PaymentMethod extends Serializable {

    /** Short human-readable form, persisted on the order. */
    String summary();
}
