package javax.jms;

/** Stub of the JMS text message: its body is {@link #getText()}. */
public interface TextMessage extends Message {

    String getText() throws JMSException;
}
