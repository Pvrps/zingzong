package ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs;

import weblogic.ejb.container.interfaces.MessageDrivenBeanInfo;

import javax.ejb.TransactionAttributeType;

/** The members of WebLogic's MessageDrivenBeanInfo that the agent reads by name. */
public final class MessageDrivenBeanInfoStub implements MessageDrivenBeanInfo {

    private final Class<?> beanClass;
    private final String ejbName;
    private final String destination;

    public MessageDrivenBeanInfoStub(Class<?> beanClass, String ejbName, String destination) {
        this.beanClass = beanClass;
        this.ejbName = ejbName;
        this.destination = destination;
    }

    public String getBeanClassName() {
        return beanClass.getName();
    }

    public ClassLoader getClassLoader() {
        return beanClass.getClassLoader();
    }

    public String getEJBName() {
        return ejbName;
    }

    public String getDestinationName() {
        return destination;
    }

    public MethodDescriptorStub getOnMessageMethodDescriptor() throws Exception {
        return new MethodDescriptorStub("notify-ear", "notify-ejb.jar", ejbName,
                beanClass.getMethod("onMessage", javax.jms.Message.class), TransactionAttributeType.REQUIRED, true);
    }

    public boolean usesBeanManagedTx() {
        return false;
    }
}
