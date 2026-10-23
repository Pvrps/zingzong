package io.capture.agent.serialize;

import java.io.Serializable;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the serializer needs to know about a class: its instance fields, whether it is a
 * JPA entity, infrastructure, serializable, or one of the known lazy wrappers.
 *
 * Cached through {@link ClassValue}, i.e. keyed by the {@link Class} object itself. Two
 * loaders defining the same class name get two entries, and an entry is unloaded together
 * with its class, so a redeployed application's loader is never pinned by this cache.
 * All detection here is by <em>name</em>; nothing is resolved through the agent's loader.
 */
final class TypeInfo {

    private static final ClassValue<TypeInfo> CACHE = new ClassValue<TypeInfo>() {
        @Override
        protected TypeInfo computeValue(Class<?> type) {
            return new TypeInfo(type);
        }
    };

    static final String ECLIPSELINK_CONTAINER = "org.eclipse.persistence.indirection.IndirectContainer";
    static final String ECLIPSELINK_VALUE_HOLDER = "org.eclipse.persistence.indirection.ValueHolderInterface";
    static final String HIBERNATE_COLLECTION = "org.hibernate.collection.spi.PersistentCollection";
    static final String HIBERNATE_PROXY = "org.hibernate.proxy.HibernateProxy";
    private static final String ECLIPSELINK_FIELD_PREFIX = "_persistence_";
    private static final String ECLIPSELINK_HOLDER_SUFFIX = "_vh";

    enum LazyKind { NONE, ECLIPSELINK_CONTAINER, ECLIPSELINK_VALUE_HOLDER, HIBERNATE_COLLECTION, HIBERNATE_PROXY }

    final Class<?> type;
    final List<FieldInfo> fields;
    final Map<String, FieldInfo> fieldsByName;
    /** Transport concern only: captured either way, flagged with "@serializable": false when not. */
    final boolean serializable;
    final boolean entity;
    /** Plumbing rather than data; see {@link InfrastructureTypes}. */
    final boolean infrastructure;
    final LazyKind lazyKind;
    /** isInstantiated() / wasInitialized() / getHibernateLazyInitializer(), per kind. */
    final Method lazyStateMethod;
    /** getValue() / getImplementation() where applicable. */
    final Method lazyValueMethod;

    static TypeInfo of(Class<?> type) {
        return CACHE.get(type);
    }

    private TypeInfo(Class<?> type) {
        this.type = type;
        Set<Class<?>> hierarchy = hierarchyOf(type);
        this.serializable = Serializable.class.isAssignableFrom(type);
        this.entity = hasAnnotationNamed(type, "javax.persistence.Entity") || hasAnnotationNamed(type, "jakarta.persistence.Entity");
        this.infrastructure = InfrastructureTypes.isInfrastructure(type, hierarchy);

        Class<?> lazyInterface;
        if ((lazyInterface = find(hierarchy, ECLIPSELINK_CONTAINER)) != null) {
            lazyKind = LazyKind.ECLIPSELINK_CONTAINER;
            lazyStateMethod = method(lazyInterface, "isInstantiated");
            lazyValueMethod = null;
        } else if ((lazyInterface = find(hierarchy, ECLIPSELINK_VALUE_HOLDER)) != null) {
            lazyKind = LazyKind.ECLIPSELINK_VALUE_HOLDER;
            lazyStateMethod = method(lazyInterface, "isInstantiated");
            lazyValueMethod = method(lazyInterface, "getValue");
        } else if ((lazyInterface = find(hierarchy, HIBERNATE_COLLECTION)) != null) {
            lazyKind = LazyKind.HIBERNATE_COLLECTION;
            lazyStateMethod = method(lazyInterface, "wasInitialized");
            lazyValueMethod = null;
        } else if ((lazyInterface = find(hierarchy, HIBERNATE_PROXY)) != null) {
            lazyKind = LazyKind.HIBERNATE_PROXY;
            lazyStateMethod = method(lazyInterface, "getHibernateLazyInitializer");
            lazyValueMethod = null;
        } else {
            lazyKind = LazyKind.NONE;
            lazyStateMethod = null;
            lazyValueMethod = null;
        }

        this.fields = collectFields(type);
        Map<String, FieldInfo> byName = new LinkedHashMap<String, FieldInfo>();
        for (FieldInfo field : fields) {
            byName.put(field.name, field);
        }
        this.fieldsByName = Collections.unmodifiableMap(byName);
    }

    /** Instance fields of the class and its superclasses, superclass first; no static, transient or synthetic. */
    private static List<FieldInfo> collectFields(Class<?> type) {
        List<Class<?>> chain = new ArrayList<Class<?>>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            chain.add(0, c);
        }
        List<FieldInfo> result = new ArrayList<FieldInfo>();
        for (Class<?> c : chain) {
            Field[] declared;
            try {
                declared = c.getDeclaredFields();
            } catch (Throwable t) {
                continue;
            }
            Map<String, Field> holders = new LinkedHashMap<String, Field>();
            for (Field f : declared) {
                if (f.getName().startsWith(ECLIPSELINK_FIELD_PREFIX) && f.getName().endsWith(ECLIPSELINK_HOLDER_SUFFIX)) {
                    holders.put(f.getName(), f);
                }
            }
            for (Field f : declared) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || Modifier.isTransient(mod) || f.isSynthetic()
                        || f.getName().startsWith(ECLIPSELINK_FIELD_PREFIX) || f.getName().indexOf('$') >= 0) {
                    continue;
                }
                if (!makeAccessible(f)) {
                    continue;
                }
                Field holder = holders.get(ECLIPSELINK_FIELD_PREFIX + f.getName() + ECLIPSELINK_HOLDER_SUFFIX);
                if (holder != null && !makeAccessible(holder)) {
                    holder = null;
                }
                result.add(new FieldInfo(f, holder));
            }
        }
        return Collections.unmodifiableList(result);
    }

    private static boolean makeAccessible(Field field) {
        try {
            field.setAccessible(true);
            return true;
        } catch (Throwable t) {
            // JDK 9+ strong encapsulation without --add-opens: skip the field.
            return false;
        }
    }

    private static Set<Class<?>> hierarchyOf(Class<?> type) {
        Set<Class<?>> result = new LinkedHashSet<Class<?>>();
        addHierarchy(type, result);
        return result;
    }

    private static void addHierarchy(Class<?> type, Set<Class<?>> into) {
        if (type == null || !into.add(type)) {
            return;
        }
        for (Class<?> i : type.getInterfaces()) {
            addHierarchy(i, into);
        }
        addHierarchy(type.getSuperclass(), into);
    }

    private static Class<?> find(Set<Class<?>> hierarchy, String name) {
        for (Class<?> c : hierarchy) {
            if (c.getName().equals(name)) {
                return c;
            }
        }
        return null;
    }

    private static boolean hasAnnotationNamed(Class<?> type, String name) {
        try {
            for (Annotation a : type.getAnnotations()) {
                if (a.annotationType().getName().equals(name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // annotation classes missing at runtime: treat as absent
        }
        return false;
    }

    private static Method method(Class<?> owner, String name) {
        try {
            Method m = owner.getMethod(name);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    static final class FieldInfo {
        final String name;
        final Field field;
        /** EclipseLink weaving: the ValueHolder that backs this field, or null. */
        final Field lazyHolder;

        FieldInfo(Field field, Field lazyHolder) {
            this.name = field.getName();
            this.field = field;
            this.lazyHolder = lazyHolder;
        }
    }
}
