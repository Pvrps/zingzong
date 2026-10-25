package javax.jms;

import java.util.Enumeration;

/** Stub of the JMS message: the headers and properties the agent reads as the message's API view. */
public interface Message {

    String getJMSMessageID() throws JMSException;

    String getJMSCorrelationID() throws JMSException;

    long getJMSTimestamp() throws JMSException;

    String getJMSType() throws JMSException;

    int getJMSDeliveryMode() throws JMSException;

    int getJMSPriority() throws JMSException;

    long getJMSExpiration() throws JMSException;

    boolean getJMSRedelivered() throws JMSException;

    Destination getJMSDestination() throws JMSException;

    Destination getJMSReplyTo() throws JMSException;

    Enumeration<?> getPropertyNames() throws JMSException;

    Object getObjectProperty(String name) throws JMSException;
}
