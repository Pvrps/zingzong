package com.acme.notify;

import javax.ejb.Local;

/** What the listener hands each event to: a second hop on the consuming side. */
@Local
public interface NotificationService {

    String record(long orderId, String event, String messageId);
}
