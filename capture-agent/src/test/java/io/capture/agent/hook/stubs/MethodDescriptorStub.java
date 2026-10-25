package io.capture.agent.hook.stubs;

import javax.ejb.TransactionAttributeType;
import java.lang.reflect.Method;

/** The members of WebLogic's MethodDescriptor and TransactionPolicy that the agent reads by name. */
public final class MethodDescriptorStub {

    /** WebLogic's TransactionPolicy, reduced to the attribute. */
    public static final class TransactionPolicy {
        private final TransactionAttributeType attribute;

        TransactionPolicy(TransactionAttributeType attribute) {
            this.attribute = attribute;
        }

        public TransactionAttributeType getTxAttribute() {
            return attribute;
        }
    }

    private final String application;
    private final String moduleId;
    private final String ejbName;
    private final Method method;
    private final TransactionAttributeType attribute;
    private final boolean local;

    public MethodDescriptorStub(String application, String moduleId, String ejbName, Method method, TransactionAttributeType attribute, boolean local) {
        this.application = application;
        this.moduleId = moduleId;
        this.ejbName = ejbName;
        this.method = method;
        this.attribute = attribute;
        this.local = local;
    }

    public String getApplicationName() {
        return application;
    }

    public String getModuleId() {
        return moduleId;
    }

    public String getEjbName() {
        return ejbName;
    }

    public Method getMethod() {
        return method;
    }

    public TransactionPolicy getTransactionPolicy() {
        return attribute == null ? null : new TransactionPolicy(attribute);
    }

    public boolean isLocal() {
        return local;
    }
}
