package io.capture.agent.hook.stubs;

import weblogic.ejb.container.internal.SessionLocalMethodInvoker;
import weblogic.ejb.container.internal.SessionRemoteMethodInvoker;

import javax.ejb.TransactionAttributeType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/** Drives a {@link GreeterBean} method through the invoker stubs with the container objects WebLogic would pass. */
public final class Calls {

    /** Shaped like WebLogic's BaseLocalObject / BaseRemoteObject: the field the agent reads. */
    public static final class ClientObject {
        protected final Object beanInfo;

        ClientObject(Object beanInfo) {
            this.beanInfo = beanInfo;
        }
    }

    /** Shaped like WebLogic's InvocationWrapper, and runs the bean method for the invoker stub. */
    public static final class InvocationWrapper implements Invocation {
        private final MethodDescriptorStub descriptor;
        private final Object bean;

        InvocationWrapper(MethodDescriptorStub descriptor, Object bean) {
            this.descriptor = descriptor;
            this.bean = bean;
        }

        public MethodDescriptorStub getMethodDescriptor() {
            return descriptor;
        }

        public boolean isLocal() {
            return descriptor.isLocal();
        }

        @Override
        public Object call(Object[] args) throws Throwable {
            try {
                return descriptor.getMethod().invoke(bean, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private Calls() {
    }

    public static String local(GreeterBean bean, String method, Object[] args) {
        try {
            return (String) SessionLocalMethodInvoker.invoke(
                    localInvokable(), new ClientObject(beanInfo(bean)), wrapper(bean, method, true), args, 0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    public static String remote(GreeterBean bean, String method, Object[] args) {
        try {
            return (String) SessionRemoteMethodInvoker.invoke(
                    remoteInvokable(), new ClientObject(beanInfo(bean)), wrapper(bean, method, false), args, 0);
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            throw new AssertionError(t);
        }
    }

    public static SessionBeanInfoStub beanInfo(GreeterBean bean) {
        return new SessionBeanInfoStub(bean.getClass(), "Greeter", Greeter.class, null);
    }

    private static InvocationWrapper wrapper(GreeterBean bean, String method, boolean local) {
        for (Method m : Greeter.class.getMethods()) {
            if (m.getName().equals(method)) {
                return new InvocationWrapper(new MethodDescriptorStub("orders-ear", "orders-ejb.jar", "Greeter", m,
                        TransactionAttributeType.REQUIRED, local), bean);
            }
        }
        throw new IllegalArgumentException(method);
    }

    /** A local view's generated client object implements the business interface itself. */
    private static Object localInvokable() {
        return Proxy.newProxyInstance(Calls.class.getClassLoader(), new Class<?>[]{Greeter.class}, (p, m, a) -> null);
    }

    /** A remote view's generated client object implements only the generated {@code *RIntf}. */
    private static Object remoteInvokable() {
        return Proxy.newProxyInstance(Calls.class.getClassLoader(), new Class<?>[]{GreeterBean_x1y2z3_GreeterRIntf.class}, (p, m, a) -> null);
    }
}
