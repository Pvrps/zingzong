package com.acme.notify;

import javax.ejb.ActivationConfigProperty;
import javax.ejb.EJB;
import javax.ejb.MessageDriven;
import javax.jms.JMSException;
import javax.jms.Message;
import javax.jms.MessageListener;
import javax.jms.TextMessage;
import java.util.logging.Logger;

/** Consumes the events OrderService publishes on jms/OrderEvents, on a JMS thread of this server. */
@MessageDriven(name = "OrderEventListener", activationConfig = {
        @ActivationConfigProperty(propertyName = "destinationType", propertyValue = "javax.jms.Queue"),
        @ActivationConfigProperty(propertyName = "destinationJndiName", propertyValue = "jms/OrderEvents"),
        @ActivationConfigProperty(propertyName = "connectionFactoryJndiName", propertyValue = "jms/OrdersCF")
})
public class OrderEventListener implements MessageListener {

    private static final Logger LOG = Logger.getLogger(OrderEventListener.class.getName());

    @EJB
    private NotificationService notifications;

    @Override
    public void onMessage(Message message) {
        try {
            long orderId = message.getLongProperty("orderId");
            String event = message.getStringProperty("event");
            String body = message instanceof TextMessage ? ((TextMessage) message).getText() : null;
            LOG.info("OrderEventListener.onMessage " + message.getJMSMessageID() + " on " + Thread.currentThread().getName() + ": " + body);
            if ("dump".equals(message.getStringProperty("by"))) {
                Thread.sleep(8000); // confirmedBy=dump: hold the message open for a thread dump of the MDB path
            }
            notifications.record(orderId, event, message.getJMSMessageID());
        } catch (JMSException | InterruptedException e) {
            throw new IllegalStateException(e);
        }
    }
}
