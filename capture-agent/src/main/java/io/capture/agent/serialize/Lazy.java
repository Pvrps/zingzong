package io.capture.agent.serialize;

import java.lang.reflect.Method;

/**
 * Reads lazy-loading state without ever triggering a load. Everything goes through the
 * methods {@link TypeInfo} resolved on the object's own class hierarchy.
 */
final class Lazy {

    enum State { NOT_LAZY, LOADED, UNLOADED, UNKNOWN }

    private Lazy() {
    }

    static State stateOf(Object value, TypeInfo info) {
        switch (info.lazyKind) {
            case NONE:
                return State.NOT_LAZY;
            case ECLIPSELINK_CONTAINER:
            case ECLIPSELINK_VALUE_HOLDER:
            case HIBERNATE_COLLECTION:
                // isInstantiated() and wasInitialized() both answer true once loaded.
                return booleanState(info.lazyStateMethod, value);
            case HIBERNATE_PROXY:
                return hibernateProxyState(info.lazyStateMethod, value);
            default:
                return State.UNKNOWN;
        }
    }

    /** The loaded value behind an EclipseLink ValueHolder or a Hibernate proxy; only call when LOADED. */
    static Object loadedValue(Object value, TypeInfo info) {
        try {
            switch (info.lazyKind) {
                case ECLIPSELINK_VALUE_HOLDER:
                    return info.lazyValueMethod == null ? null : info.lazyValueMethod.invoke(value);
                case HIBERNATE_PROXY: {
                    Object initializer = info.lazyStateMethod.invoke(value);
                    Method impl = initializer.getClass().getMethod("getImplementation");
                    impl.setAccessible(true);
                    return impl.invoke(initializer);
                }
                default:
                    return value;
            }
        } catch (Throwable t) {
            return null;
        }
    }

    /** EclipseLink weaving: state of the ValueHolder behind a field, or NOT_LAZY when there is none. */
    static State holderState(Object holder) {
        if (holder == null) {
            return State.NOT_LAZY;
        }
        TypeInfo holderInfo = TypeInfo.of(holder.getClass());
        if (holderInfo.lazyKind != TypeInfo.LazyKind.ECLIPSELINK_VALUE_HOLDER) {
            return State.NOT_LAZY;
        }
        return stateOf(holder, holderInfo);
    }

    private static State booleanState(Method method, Object value) {
        if (method == null) {
            return State.UNKNOWN;
        }
        try {
            Object result = method.invoke(value);
            if (!(result instanceof Boolean)) {
                return State.UNKNOWN;
            }
            boolean loaded = (Boolean) result;
            return loaded ? State.LOADED : State.UNLOADED;
        } catch (Throwable t) {
            return State.UNKNOWN;
        }
    }

    private static State hibernateProxyState(Method getInitializer, Object value) {
        if (getInitializer == null) {
            return State.UNKNOWN;
        }
        try {
            Object initializer = getInitializer.invoke(value);
            Method isUninitialized = initializer.getClass().getMethod("isUninitialized");
            isUninitialized.setAccessible(true);
            return ((Boolean) isUninitialized.invoke(initializer)) ? State.UNLOADED : State.LOADED;
        } catch (Throwable t) {
            return State.UNKNOWN;
        }
    }
}
