package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import java.lang.reflect.Method;

/**
 * Reads lazy-loading state without ever triggering a load, through the methods {@link TypeInfo}
 * resolved on the value's own class hierarchy.
 */
enum LazyState {
    NOT_LAZY, LOADED, UNLOADED, UNKNOWN;

    static LazyState of(Object value, TypeInfo info) {
        switch (info.lazyKind) {
            case NONE:
                return NOT_LAZY;
            case ECLIPSELINK_CONTAINER:
            case ECLIPSELINK_VALUE_HOLDER:
            case HIBERNATE_COLLECTION:
                // isInstantiated() and wasInitialized() both answer true once loaded.
                return fromBoolean(info.lazyStateMethod, value);
            case HIBERNATE_PROXY:
                return hibernateProxyState(info.lazyStateMethod, value);
            default:
                return UNKNOWN;
        }
    }

    /** The loaded value behind an EclipseLink ValueHolder or a Hibernate proxy; only for a LOADED value. */
    static Object loadedValue(Object value, TypeInfo info) {
        try {
            switch (info.lazyKind) {
                case ECLIPSELINK_VALUE_HOLDER:
                    return info.lazyValueMethod == null ? null : info.lazyValueMethod.invoke(value);
                case HIBERNATE_PROXY: {
                    Object initializer = info.lazyStateMethod.invoke(value);
                    Method implementation = initializer.getClass().getMethod("getImplementation");
                    implementation.setAccessible(true);
                    return implementation.invoke(initializer);
                }
                default:
                    return value;
            }
        } catch (Throwable failure) {
            return null;
        }
    }

    /** EclipseLink weaving: the state of the ValueHolder behind a field, or NOT_LAZY when there is none. */
    static LazyState ofHolder(Object holder, TypeInfoCache typeInfos) {
        if (holder == null) {
            return NOT_LAZY;
        }
        TypeInfo holderInfo = typeInfos.of(holder.getClass());
        return holderInfo.lazyKind == TypeInfo.LazyKind.ECLIPSELINK_VALUE_HOLDER ? of(holder, holderInfo) : NOT_LAZY;
    }

    private static LazyState fromBoolean(Method method, Object value) {
        if (method == null) {
            return UNKNOWN;
        }
        try {
            Object result = method.invoke(value);
            if (!(result instanceof Boolean)) {
                return UNKNOWN;
            }
            return (Boolean) result ? LOADED : UNLOADED;
        } catch (Throwable failure) {
            return UNKNOWN;
        }
    }

    private static LazyState hibernateProxyState(Method getInitializer, Object value) {
        if (getInitializer == null) {
            return UNKNOWN;
        }
        try {
            Object initializer = getInitializer.invoke(value);
            Method isUninitialized = initializer.getClass().getMethod("isUninitialized");
            isUninitialized.setAccessible(true);
            return (Boolean) isUninitialized.invoke(initializer) ? UNLOADED : LOADED;
        } catch (Throwable failure) {
            return UNKNOWN;
        }
    }
}
