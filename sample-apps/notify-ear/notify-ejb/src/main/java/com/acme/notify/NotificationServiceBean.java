package com.acme.notify;

import javax.ejb.Stateless;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

@Stateless(name = "NotificationService")
public class NotificationServiceBean implements NotificationService {

    private static final Logger LOG = Logger.getLogger(NotificationServiceBean.class.getName());
    private static final AtomicLong COUNTER = new AtomicLong();

    @Override
    public String record(long orderId, String event, String messageId) {
        String receipt = "N-" + COUNTER.incrementAndGet();
        LOG.info("NotificationService.record " + receipt + ": order " + orderId + " " + event + " (message " + messageId + ")");
        return receipt;
    }
}
