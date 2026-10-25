package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.Set;

/**
 * Records a {@code javax.jms.Message} as its API view, headers, properties and body, through the
 * server's own JMS API by method name; the vendor object's fields describe the session, not the
 * payload. A bytes or stream body is not read, because reading would consume it: its length is
 * recorded and the omission marked.
 */
final class JmsMessageView {

    private static final String MESSAGE = "javax.jms.Message";
    private static final String JMS_PACKAGE = "javax.jms.";
    private static final String[] HEADERS = {
            "JMSMessageID", "JMSCorrelationID", "JMSTimestamp", "JMSType", "JMSDeliveryMode",
            "JMSPriority", "JMSExpiration", "JMSRedelivered", "JMSDestination", "JMSReplyTo",
    };
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final JacksonGraphSerializer serializer;

    JmsMessageView(JacksonGraphSerializer serializer) {
        this.serializer = serializer;
    }

    static boolean isMessage(Set<Class<?>> hierarchy) {
        return hierarchy.stream().anyMatch(member -> member.getName().equals(MESSAGE));
    }

    ObjectNode write(Object message, TypeInfo info, SerializationContext context) {
        ObjectNode node = NODES.objectNode();
        Marker.type(info.type.getName()).writeTo(node);
        String kind = kind(info);
        Marker.jms(kind).writeTo(node);
        writeHeaders(message, node.putObject("headers"), context);
        writeProperties(message, node.putObject("properties"), context);
        writeBody(message, kind, info, node, context);
        return node;
    }

    private void writeHeaders(Object message, ObjectNode headers, SerializationContext context) {
        for (String header : HEADERS) {
            Object value = call(message, "get" + header);
            if (value == null) {
                continue;
            }
            if (header.equals("JMSDestination") || header.equals("JMSReplyTo")) {
                headers.put(header, value.toString());
            } else {
                headers.set(header, serializer.writeValue(value, context));
            }
        }
    }

    private void writeProperties(Object message, ObjectNode properties, SerializationContext context) {
        Object names = call(message, "getPropertyNames");
        if (!(names instanceof Enumeration)) {
            return;
        }
        Enumeration<?> propertyNames = (Enumeration<?>) names;
        while (propertyNames.hasMoreElements()) {
            String name = String.valueOf(propertyNames.nextElement());
            try {
                Method getter = message.getClass().getMethod("getObjectProperty", String.class);
                getter.setAccessible(true);
                properties.set(name, serializer.writeValue(getter.invoke(message, name), context));
            } catch (Throwable failure) {
                properties.put(name, "<unreadable: " + failure + ">");
            }
        }
    }

    private void writeBody(Object message, String kind, TypeInfo info, ObjectNode node, SerializationContext context) {
        switch (kind) {
            case "TextMessage": {
                Object text = call(message, "getText");
                node.put("text", text == null ? null : text.toString());
                break;
            }
            case "MapMessage":
                writeMapBody(message, node.putObject("map"), context);
                break;
            case "ObjectMessage":
                try {
                    node.set("object", serializer.writeValue(call(message, "getObject"), context));
                } catch (Throwable failure) {
                    node.set("object", Marker.unserializable(null, "ObjectMessage.getObject failed: " + failure, null).toNode());
                }
                break;
            case "BytesMessage": {
                Object length = call(message, "getBodyLength");
                node.put("bodyLength", length == null ? null : length.toString());
                node.put(Marker.Kind.TRUNCATED.key(), "bytes body not read: reading a BytesMessage consumes it");
                context.warn("BytesMessage body not read (reading consumes it); length recorded", info.type.getName());
                break;
            }
            case "StreamMessage":
                node.put(Marker.Kind.TRUNCATED.key(), "stream body not read: reading a StreamMessage consumes it");
                context.warn("StreamMessage body not read (reading consumes it)", info.type.getName());
                break;
            default:
                break;
        }
    }

    private void writeMapBody(Object message, ObjectNode map, SerializationContext context) {
        Object names = call(message, "getMapNames");
        if (!(names instanceof Enumeration)) {
            return;
        }
        Enumeration<?> mapNames = (Enumeration<?>) names;
        while (mapNames.hasMoreElements()) {
            String name = String.valueOf(mapNames.nextElement());
            try {
                Method getter = message.getClass().getMethod("getObject", String.class);
                getter.setAccessible(true);
                map.set(name, serializer.writeValue(getter.invoke(message, name), context));
            } catch (Throwable failure) {
                map.put(name, "<unreadable: " + failure + ">");
            }
        }
    }

    /** TextMessage, MapMessage, ObjectMessage, BytesMessage, StreamMessage or Message. */
    private static String kind(TypeInfo info) {
        return TypeInfo.hierarchyOf(info.type).stream()
                .map(Class::getName)
                .filter(name -> name.startsWith(JMS_PACKAGE) && name.endsWith("Message") && !name.equals(MESSAGE))
                .map(name -> name.substring(JMS_PACKAGE.length()))
                .findFirst()
                .orElse("Message");
    }

    private static Object call(Object target, String getter) {
        try {
            Method method = target.getClass().getMethod(getter);
            method.setAccessible(true);
            return method.invoke(target);
        } catch (Throwable failure) {
            return null;
        }
    }
}
