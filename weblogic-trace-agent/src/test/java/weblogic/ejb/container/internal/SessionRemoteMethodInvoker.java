package weblogic.ejb.container.internal;

import ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs.Invocation;

/** Stub of the WebLogic remote-view invoker: the static five-argument {@code invoke} the hook advises. */
public final class SessionRemoteMethodInvoker {

    private SessionRemoteMethodInvoker() {
    }

    public static Object invoke(Object invokable, Object clientObject, Object wrapper, Object[] args, int methodIndex) throws Throwable {
        return ((Invocation) wrapper).call(args);
    }
}
