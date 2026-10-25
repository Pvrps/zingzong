package javax.jms;

/** Stub of the JMS listener a message-driven bean implements. */
public interface MessageListener {

    void onMessage(Message message);
}
