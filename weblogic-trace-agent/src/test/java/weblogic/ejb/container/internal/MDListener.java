package weblogic.ejb.container.internal;

import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.MessageDrivenBeanInfoStub;

import javax.jms.Message;
import javax.jms.MessageListener;

/** Stub of WebLogic's MDB listener: the {@code info} field and the {@code onMessage} the hook advises. */
public final class MDListener {

    private final MessageDrivenBeanInfoStub info;
    private final MessageListener bean;

    public MDListener(MessageDrivenBeanInfoStub info, MessageListener bean) {
        this.info = info;
        this.bean = bean;
    }

    public void onMessage(Message message) {
        bean.onMessage(message);
    }
}
