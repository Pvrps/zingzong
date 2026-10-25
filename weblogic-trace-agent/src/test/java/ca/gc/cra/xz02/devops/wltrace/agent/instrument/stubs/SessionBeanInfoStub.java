package ca.gc.cra.xz02.devops.wltrace.agent.instrument.stubs;

import weblogic.ejb.container.interfaces.BeanInfo;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** The members of WebLogic's SessionBeanInfo that the agent reads by name. */
public final class SessionBeanInfoStub implements BeanInfo {

    private final Class<?> beanClass;
    private final String ejbName;
    private final Class<?> businessLocal;
    private final Class<?> businessRemote;

    public SessionBeanInfoStub(Class<?> beanClass, String ejbName, Class<?> businessLocal, Class<?> businessRemote) {
        this.beanClass = beanClass;
        this.ejbName = ejbName;
        this.businessLocal = businessLocal;
        this.businessRemote = businessRemote;
    }

    public String getBeanClassName() {
        return beanClass.getName();
    }

    public String getEJBName() {
        return ejbName;
    }

    public ClassLoader getClassLoader() {
        return beanClass.getClassLoader();
    }

    public Set<Class<?>> getBusinessLocals() {
        return businessLocal == null ? Collections.<Class<?>>emptySet() : new HashSet<Class<?>>(Collections.<Class<?>>singleton(businessLocal));
    }

    public Set<Class<?>> getBusinessRemotes() {
        return businessRemote == null ? Collections.<Class<?>>emptySet() : new HashSet<Class<?>>(Collections.<Class<?>>singleton(businessRemote));
    }

    public Map<Class<?>, Set<String>> getBusinessJNDINames() {
        Map<Class<?>, Set<String>> names = new HashMap<Class<?>, Set<String>>();
        if (businessLocal != null) {
            names.put(businessLocal, Collections.singleton("stubs/" + ejbName));
        }
        return names;
    }

    public boolean isLocalClientView(Class<?> businessInterface) {
        return businessInterface == businessLocal;
    }

    public boolean isStateless() {
        return true;
    }

    public boolean isStateful() {
        return false;
    }

    public boolean isSingleton() {
        return false;
    }

    public boolean usesBeanManagedTx() {
        return false;
    }
}
