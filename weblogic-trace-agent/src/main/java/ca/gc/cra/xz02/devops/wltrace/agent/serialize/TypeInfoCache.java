package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

/**
 * One {@link TypeInfo} per {@link Class} object, computed on first use and released with the
 * class. Keyed by the Class, never by its name: two loaders defining the same name get two
 * entries, and a redeployed loader is never pinned.
 */
public final class TypeInfoCache {

    private final ClassValue<TypeInfo> cache = new ClassValue<TypeInfo>() {
        @Override
        protected TypeInfo computeValue(Class<?> type) {
            return new TypeInfo(type);
        }
    };

    public TypeInfo of(Class<?> type) {
        return cache.get(type);
    }
}
