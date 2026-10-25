package ca.gc.cra.xz02.devops.wltrace.agent.hook.stubs;

import javax.jms.Message;
import javax.jms.MessageListener;

/** A message-driven bean. */
public class EventListenerBean implements MessageListener {

    public String lastText;

    @Override
    public void onMessage(Message message) {
        try {
            lastText = ((javax.jms.TextMessage) message).getText();
        } catch (javax.jms.JMSException e) {
            throw new IllegalStateException(e);
        }
    }
}
