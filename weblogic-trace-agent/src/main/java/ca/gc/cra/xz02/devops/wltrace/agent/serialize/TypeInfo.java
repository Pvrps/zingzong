package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

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
 * What the serializer knows about one class: its instance fields, walked or skipped with a reason,
 * and whether it is a JPA entity, a runtime handle, serializable, a JMS message or a known lazy
 * wrapper. Every detection is by name through the class's own hierarchy; nothing is resolved
 * through the agent's loader. Instances live in a {@link TypeInfoCache}.
 */
public final class TypeInfo {

    static final String ECLIPSELINK_CONTAINER = "org.eclipse.persistence.indirection.IndirectContainer";
    static final String ECLIPSELINK_VALUE_HOLDER = "org.eclipse.persistence.indirection.ValueHolderInterface";
    static final String HIBERNATE_COLLECTION = "org.hibernate.collection.spi.PersistentCollection";
    static final String HIBERNATE_PROXY = "org.hibernate.proxy.HibernateProxy";
    private static final String ECLIPSELINK_FIELD_PREFIX = "_persistence_";
    private static final String ECLIPSELINK_HOLDER_SUFFIX = "_vh";
    private static final String HIBERNATE_FIELD_PREFIX = "$$_hibernate_";

    /** The lazy-loading mechanisms the serializer recognises without touching them. */
    enum LazyKind { NONE, ECLIPSELINK_CONTAINER, ECLIPSELINK_VALUE_HOLDER, HIBERNATE_COLLECTION, HIBERNATE_PROXY }

    /** One declared instance field: walked through {@link #field}, or skipped with {@link #skipReason}. */
    static final class FieldInfo {
        final String name;
        final Field field;
        /** EclipseLink weaving: the ValueHolder that backs this field, or null. */
        final Field lazyHolder;
        final String skipReason;

        private FieldInfo(String name, Field field, Field lazyHolder, String skipReason) {
            this.name = name;
            this.field = field;
            this.lazyHolder = lazyHolder;
            this.skipReason = skipReason;
        }

        static FieldInfo walked(Field field, Field lazyHolder) {
            return new FieldInfo(field.getName(), field, lazyHolder, null);
        }

        static FieldInfo skipped(String name, String reason) {
            return new FieldInfo(name, null, null, reason);
        }

        boolean walked() {
            return field != null;
        }
    }

    final Class<?> type;
    final List<FieldInfo> fields;
    /** Transport concern only: captured either way, flagged {@code @serializable: false} when not. */
    final boolean serializable;
    final boolean entity;
    /** Non-null for a non-walkable runtime handle; see {@link InfrastructureTypes}. */
    final String handleKind;
    /** True when the type implements {@code javax.jms.Message}: recorded as its API view. */
    final boolean jmsMessage;
    final LazyKind lazyKind;
    /** {@code isInstantiated()}, {@code wasInitialized()} or {@code getHibernateLazyInitializer()}, per kind. */
    final Method lazyStateMethod;
    /** {@code getValue()} for a value holder; null otherwise. */
    final Method lazyValueMethod;

    TypeInfo(Class<?> type) {
        this.type = type;
        Set<Class<?>> hierarchy = hierarchyOf(type);
        this.serializable = Serializable.class.isAssignableFrom(type);
        this.entity = hasAnnotationNamed(type, "javax.persistence.Entity") || hasAnnotationNamed(type, "jakarta.persistence.Entity");
        this.handleKind = InfrastructureTypes.handleKind(hierarchy);
        this.jmsMessage = JmsMessageView.isMessage(hierarchy);

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
    }

    public Class<?> type() {
        return type;
    }

    /** The walked and skipped instance fields, superclass first. */
    public int fieldCount() {
        return fields.size();
    }

    /**
     * Instance fields of the class and its superclasses, superclass first. Transient fields are
     * walked like any other: they are part of the object as it was. Static fields are class state
     * and are not listed. A field that is not walked keeps its reason so the serializer can list
     * it under {@code @skipped}: compiler-injected ({@code this$0}, synthetic), weaver-injected
     * ({@code _persistence_*}, {@code $$_hibernate_*}), or inaccessible under JDK encapsulation
     * without {@code --add-opens}.
     */
    private static List<FieldInfo> collectFields(Class<?> type) {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            chain.add(0, current);
        }
        List<FieldInfo> result = new ArrayList<>();
        for (Class<?> current : chain) {
            Field[] declared;
            try {
                declared = current.getDeclaredFields();
            } catch (Throwable failure) {
                result.add(FieldInfo.skipped("<" + current.getName() + ">", "declared fields not readable: " + failure));
                continue;
            }
            Map<String, Field> holders = new LinkedHashMap<>();
            for (Field field : declared) {
                if (field.getName().startsWith(ECLIPSELINK_FIELD_PREFIX) && field.getName().endsWith(ECLIPSELINK_HOLDER_SUFFIX)) {
                    holders.put(field.getName(), field);
                }
            }
            for (Field field : declared) {
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                String name = field.getName();
                if (field.isSynthetic() || name.indexOf('$') >= 0) {
                    result.add(FieldInfo.skipped(name, "compiler-injected (synthetic)"));
                } else if (name.startsWith(ECLIPSELINK_FIELD_PREFIX)) {
                    result.add(FieldInfo.skipped(name, name.endsWith(ECLIPSELINK_HOLDER_SUFFIX)
                            ? "weaver-injected (EclipseLink); its state is reported on the field it backs"
                            : "weaver-injected (EclipseLink)"));
                } else if (name.startsWith(HIBERNATE_FIELD_PREFIX)) {
                    result.add(FieldInfo.skipped(name, "weaver-injected (Hibernate)"));
                } else {
                    String inaccessible = makeAccessible(field);
                    if (inaccessible != null) {
                        result.add(FieldInfo.skipped(name, "inaccessible: " + inaccessible));
                        continue;
                    }
                    Field holder = holders.get(ECLIPSELINK_FIELD_PREFIX + name + ECLIPSELINK_HOLDER_SUFFIX);
                    if (holder != null && makeAccessible(holder) != null) {
                        holder = null;
                    }
                    result.add(FieldInfo.walked(field, holder));
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** Null when accessible, else why not (JDK 9+ strong encapsulation without --add-opens). */
    private static String makeAccessible(Field field) {
        try {
            field.setAccessible(true);
            return null;
        } catch (Throwable failure) {
            return failure.getClass().getSimpleName() + (failure.getMessage() == null ? "" : ": " + failure.getMessage());
        }
    }

    /** The type, its interfaces and superclasses, each once. */
    static Set<Class<?>> hierarchyOf(Class<?> type) {
        Set<Class<?>> result = new LinkedHashSet<>();
        addHierarchy(type, result);
        return result;
    }

    private static void addHierarchy(Class<?> type, Set<Class<?>> into) {
        if (type == null || !into.add(type)) {
            return;
        }
        for (Class<?> implemented : type.getInterfaces()) {
            addHierarchy(implemented, into);
        }
        addHierarchy(type.getSuperclass(), into);
    }

    private static Class<?> find(Set<Class<?>> hierarchy, String name) {
        return hierarchy.stream().filter(member -> member.getName().equals(name)).findFirst().orElse(null);
    }

    private static boolean hasAnnotationNamed(Class<?> type, String name) {
        try {
            for (Annotation annotation : type.getAnnotations()) {
                if (annotation.annotationType().getName().equals(name)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
            // annotation classes missing at runtime: treated as absent
        }
        return false;
    }

    private static Method method(Class<?> owner, String name) {
        try {
            Method method = owner.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (Throwable failure) {
            return null;
        }
    }
}
