package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.util.Enumeration;

/**
 * Records a {@code javax.jms.Message} as its API view, headers, properties and body, through the
 * server's own JMS API by method name; the vendor object's fields describe the session, not the
 * payload. A bytes or stream body is not read, because reading would consume it: its length is
 * recorded and the omission marked.
 */
final class JmsMessages {

    static final String MARKER = "@jms";

    private static final String MESSAGE = "javax.jms.Message";
    private static final String[] HEADERS = {
            "JMSMessageID", "JMSCorrelationID", "JMSTimestamp", "JMSType", "JMSDeliveryMode",
            "JMSPriority", "JMSExpiration", "JMSRedelivered", "JMSDestination", "JMSReplyTo",
    };

    private JmsMessages() {
    }

    static boolean isMessage(java.util.Set<Class<?>> hierarchy) {
        for (Class<?> c : hierarchy) {
            if (c.getName().equals(MESSAGE)) {
                return true;
            }
        }
        return false;
    }

    static ObjectNode write(Object message, TypeInfo info, JacksonGraphSerializer serializer, SerializationContext ctx) {
        JsonNodeFactory nodes = JsonNodeFactory.instance;
        ObjectNode node = nodes.objectNode();
        node.put(Placeholders.TYPE, info.type.getName());
        node.put(MARKER, kind(info));
        ObjectNode headers = node.putObject("headers");
        for (String header : HEADERS) {
            Object value = call(message, "get" + header);
            if (value == null) {
                continue;
            }
            if (header.equals("JMSDestination") || header.equals("JMSReplyTo")) {
                headers.put(header, value.toString());
            } else {
                headers.set(header, serializer.writeValue(value, ctx));
            }
        }
        ObjectNode properties = node.putObject("properties");
        Object names = call(message, "getPropertyNames");
        if (names instanceof Enumeration) {
            Enumeration<?> e = (Enumeration<?>) names;
            while (e.hasMoreElements()) {
                String name = String.valueOf(e.nextElement());
                try {
                    Method get = message.getClass().getMethod("getObjectProperty", String.class);
                    get.setAccessible(true);
                    properties.set(name, serializer.writeValue(get.invoke(message, name), ctx));
                } catch (Throwable t) {
                    properties.put(name, "<unreadable: " + t + ">");
                }
            }
        }
        String kind = kind(info);
        if (kind.equals("TextMessage")) {
            Object text = call(message, "getText");
            node.put("text", text == null ? null : text.toString());
        } else if (kind.equals("MapMessage")) {
            ObjectNode map = node.putObject("map");
            Object mapNames = call(message, "getMapNames");
            if (mapNames instanceof Enumeration) {
                Enumeration<?> e = (Enumeration<?>) mapNames;
                while (e.hasMoreElements()) {
                    String name = String.valueOf(e.nextElement());
                    try {
                        Method get = message.getClass().getMethod("getObject", String.class);
                        get.setAccessible(true);
                        map.set(name, serializer.writeValue(get.invoke(message, name), ctx));
                    } catch (Throwable t) {
                        map.put(name, "<unreadable: " + t + ">");
                    }
                }
            }
        } else if (kind.equals("ObjectMessage")) {
            try {
                node.set("object", serializer.writeValue(call(message, "getObject"), ctx));
            } catch (Throwable t) {
                node.set("object", Placeholders.unserializable(null, "ObjectMessage.getObject failed: " + t, null));
            }
        } else if (kind.equals("BytesMessage")) {
            Object length = call(message, "getBodyLength");
            node.put("bodyLength", length == null ? null : length.toString());
            node.put("@truncated", "bytes body not read: reading a BytesMessage consumes it");
            ctx.warn("BytesMessage body not read (reading consumes it); length recorded", info.type.getName());
        } else if (kind.equals("StreamMessage")) {
            node.put("@truncated", "stream body not read: reading a StreamMessage consumes it");
            ctx.warn("StreamMessage body not read (reading consumes it)", info.type.getName());
        }
        return node;
    }

    /** TextMessage, MapMessage, ObjectMessage, BytesMessage, StreamMessage or Message. */
    private static String kind(TypeInfo info) {
        for (Class<?> c : TypeInfo.hierarchyOf(info.type)) {
            String n = c.getName();
            if (n.startsWith("javax.jms.") && n.endsWith("Message") && !n.equals(MESSAGE)) {
                return n.substring("javax.jms.".length());
            }
        }
        return "Message";
    }

    private static Object call(Object target, String getter) {
        try {
            Method m = target.getClass().getMethod(getter);
            m.setAccessible(true);
            return m.invoke(target);
        } catch (Throwable t) {
            return null;
        }
    }
}
