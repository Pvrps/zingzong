package io.capture.agent.serialize;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * Creates instances for deserialization: a no-arg constructor of any visibility, else the
 * JDK's serialization constructor (as ObjectInputStream does), reached reflectively so the
 * agent has no compile-time dependency on {@code sun.reflect}.
 */
final class Instantiator {

    private static final ClassValue<Constructor<?>> CONSTRUCTORS = new ClassValue<Constructor<?>>() {
        @Override
        protected Constructor<?> computeValue(Class<?> type) {
            return resolve(type);
        }
    };

    private Instantiator() {
    }

    static Object newInstance(Class<?> type) throws GraphException {
        Constructor<?> constructor = CONSTRUCTORS.get(type);
        if (constructor == null) {
            throw new GraphException("no usable constructor for " + type.getName());
        }
        try {
            return constructor.newInstance();
        } catch (Throwable t) {
            throw new GraphException("cannot instantiate " + type.getName(), t);
        }
    }

    private static Constructor<?> resolve(Class<?> type) {
        try {
            Constructor<?> c = type.getDeclaredConstructor();
            c.setAccessible(true);
            return c;
        } catch (Throwable ignored) {
            // fall through to the serialization constructor
        }
        try {
            Class<?> factoryClass = Class.forName("sun.reflect.ReflectionFactory");
            Method getFactory = factoryClass.getMethod("getReflectionFactory");
            Object factory = getFactory.invoke(null);
            Method newConstructor = factoryClass.getMethod("newConstructorForSerialization", Class.class, Constructor.class);
            Constructor<?> objectConstructor = Object.class.getDeclaredConstructor();
            Constructor<?> c = (Constructor<?>) newConstructor.invoke(factory, type, objectConstructor);
            c.setAccessible(true);
            return c;
        } catch (Throwable t) {
            return null;
        }
    }
}
