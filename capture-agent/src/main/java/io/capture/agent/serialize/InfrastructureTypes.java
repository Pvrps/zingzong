package io.capture.agent.serialize;

import java.util.Set;

/**
 * Built-in heuristic for objects that are plumbing rather than data: loggers, locks,
 * executors, threads, class loaders, streams, sockets, JDBC/JPA/JMS/JNDI handles,
 * container internals. Such values become placeholders. Decided by type hierarchy and
 * package, never by {@code Serializable}; not user-configurable by design.
 */
final class InfrastructureTypes {

    /** Matched against every class and interface in the object's hierarchy. */
    private static final String[] TYPE_NAMES = {
            "java.lang.Class", "java.lang.ClassLoader", "java.lang.Thread", "java.lang.ThreadGroup",
            "java.lang.Runtime", "java.lang.Process", "java.lang.ProcessHandle", "java.lang.Module",
            "java.lang.ClassValue", "java.lang.ref.Reference", "java.lang.invoke.MethodHandle",
            "java.lang.reflect.Method", "java.lang.reflect.Field", "java.lang.reflect.Constructor",
            "java.util.concurrent.Executor", "java.util.concurrent.ExecutorService", "java.util.concurrent.Future",
            "java.util.concurrent.CompletionStage", "java.util.concurrent.ForkJoinTask",
            "java.util.concurrent.locks.Lock", "java.util.concurrent.locks.ReadWriteLock",
            "java.util.concurrent.locks.Condition", "java.util.concurrent.Semaphore",
            "java.util.concurrent.CountDownLatch", "java.util.concurrent.CyclicBarrier", "java.util.concurrent.Phaser",
            "java.util.Timer", "java.util.TimerTask",
            "java.io.InputStream", "java.io.OutputStream", "java.io.Reader", "java.io.Writer",
            "java.io.RandomAccessFile", "java.io.Console",
            "java.nio.channels.Channel", "java.nio.file.FileSystem", "java.nio.file.WatchService",
            "java.net.Socket", "java.net.ServerSocket", "java.net.DatagramSocket", "java.net.URLConnection",
            "java.sql.Connection", "java.sql.Statement", "java.sql.ResultSet", "java.sql.DatabaseMetaData",
            "javax.sql.DataSource", "javax.sql.ConnectionPoolDataSource", "javax.sql.XADataSource",
            "javax.persistence.EntityManager", "javax.persistence.EntityManagerFactory", "javax.persistence.Query",
            "javax.persistence.EntityTransaction", "javax.persistence.Cache",
            "javax.transaction.UserTransaction", "javax.transaction.TransactionManager", "javax.transaction.Transaction",
            "javax.transaction.TransactionSynchronizationRegistry",
            "javax.naming.Context", "javax.ejb.EJBContext", "javax.ejb.TimerService", "javax.ejb.Timer",
            "javax.jms.Connection", "javax.jms.ConnectionFactory", "javax.jms.Session", "javax.jms.MessageProducer",
            "javax.jms.MessageConsumer", "javax.jms.JMSContext", "javax.jms.Destination",
            "javax.servlet.ServletRequest", "javax.servlet.ServletResponse", "javax.servlet.ServletContext",
            "javax.servlet.http.HttpSession", "javax.servlet.FilterChain", "javax.servlet.AsyncContext",
            "javax.websocket.Session", "javax.security.auth.Subject", "javax.security.auth.login.LoginContext",
            "javax.enterprise.inject.spi.BeanManager", "javax.enterprise.context.spi.Context",
            "javax.ws.rs.client.Client", "javax.ws.rs.client.WebTarget",
            "java.util.logging.Logger", "java.util.logging.Handler",
            "org.slf4j.Logger", "org.apache.logging.log4j.Logger", "org.apache.log4j.Logger", "org.apache.log4j.Category",
            "javax.management.MBeanServer", "javax.management.MBeanServerConnection",
            "javax.xml.parsers.DocumentBuilder", "javax.xml.transform.Transformer", "javax.xml.bind.JAXBContext",
    };

    /** Matched against the concrete class's package. Vendor and container internals. */
    private static final String[] PACKAGE_PREFIXES = {
            "weblogic.", "oracle.", "com.oracle.", "com.bea.",
            "org.eclipse.persistence.internal.", "org.eclipse.persistence.sessions.",
            "org.eclipse.persistence.queries.", "org.eclipse.persistence.descriptors.",
            "org.hibernate.engine.", "org.hibernate.internal.", "org.hibernate.persister.",
            "org.glassfish.", "org.jboss.", "org.apache.catalina.", "org.apache.tomcat.",
            "javax.management.", "javax.naming.", "javax.security.auth.", "javax.enterprise.",
            "sun.", "com.sun.", "jdk.", "java.lang.invoke.", "java.lang.ref.", "java.lang.reflect.",
            "java.util.logging.", "org.slf4j.", "org.apache.logging.", "org.apache.log4j.", "ch.qos.logback.",
            "java.nio.channels.", "java.util.concurrent.locks.",
    };

    private InfrastructureTypes() {
    }

    static boolean isInfrastructure(Class<?> type, Set<Class<?>> hierarchy) {
        String name = type.getName();
        for (String prefix : PACKAGE_PREFIXES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        for (Class<?> c : hierarchy) {
            String n = c.getName();
            for (String typeName : TYPE_NAMES) {
                if (n.equals(typeName)) {
                    return true;
                }
            }
        }
        return false;
    }
}
