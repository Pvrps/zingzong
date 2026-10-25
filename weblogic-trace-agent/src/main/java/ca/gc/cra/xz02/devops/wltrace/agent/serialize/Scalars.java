package ca.gc.cra.xz02.devops.wltrace.agent.serialize;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.FloatNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ShortNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.File;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leaf values as JSON scalars, each form lossless for the value: enums by name, BigDecimal and
 * BigInteger as plain strings, java.time by its ISO-8601 text, java.util.Date as an ISO instant,
 * java.sql.Timestamp with its nanoseconds, Calendar as a zoned date-time, UUID, URI, URL, File,
 * Path, Locale, Currency and StackTraceElement by their canonical text.
 */
final class Scalars {

    /** Recognised by name: java.sql lives in the platform loader, invisible to the bootstrap-loaded agent. */
    private static final String SQL_TIMESTAMP = "java.sql.Timestamp";

    private Scalars() {
    }

    /** The scalar node for the value, or null when the value is not a scalar. */
    static JsonNode write(Object value, SerializationContext context) {
        if (value instanceof String) {
            return text((String) value, context);
        }
        if (value instanceof Boolean) {
            return BooleanNode.valueOf((Boolean) value);
        }
        if (value instanceof Number) {
            return number((Number) value);
        }
        if (value instanceof Character) {
            return TextNode.valueOf(value.toString());
        }
        if (value instanceof Enum) {
            return TextNode.valueOf(((Enum<?>) value).name());
        }
        if (value instanceof CharSequence) {
            return text(value.toString(), context);
        }
        if (value instanceof Date) {
            return date((Date) value);
        }
        if (value instanceof Calendar) {
            Calendar calendar = (Calendar) value;
            return TextNode.valueOf(ZonedDateTime.ofInstant(Instant.ofEpochMilli(calendar.getTimeInMillis()), calendar.getTimeZone().toZoneId()).toString());
        }
        if (value instanceof StackTraceElement || value instanceof Temporal || value instanceof TemporalAmount || value instanceof ZoneId) {
            return TextNode.valueOf(value.toString());
        }
        if (value instanceof AtomicBoolean) {
            return BooleanNode.valueOf(((AtomicBoolean) value).get());
        }
        if (value instanceof UUID || value instanceof URI || value instanceof URL || value instanceof File
                || value instanceof Path || value instanceof Locale || value instanceof Currency) {
            return TextNode.valueOf(value.toString());
        }
        return null;
    }

    private static JsonNode date(Date date) {
        long millis = date.getTime();
        if (date.getClass().getName().equals(SQL_TIMESTAMP)) {
            try {
                int nanos = (Integer) date.getClass().getMethod("getNanos").invoke(date);
                return TextNode.valueOf(Instant.ofEpochSecond(Math.floorDiv(millis, 1000L), nanos).toString());
            } catch (Throwable ignored) {
                // millisecond precision below
            }
        }
        return TextNode.valueOf(Instant.ofEpochMilli(millis).toString());
    }

    private static JsonNode text(String text, SerializationContext context) {
        int maximum = context.ceilings.maxStringLength;
        if (text.length() > maximum) {
            int dropped = text.length() - maximum;
            context.warn("string ceiling " + maximum + " reached; " + dropped + " of " + text.length() + " chars dropped", String.class.getName());
            return Marker.text(text.substring(0, maximum), text.length(), dropped).toNode();
        }
        return TextNode.valueOf(text);
    }

    private static JsonNode number(Number number) {
        if (number instanceof Integer || number instanceof AtomicInteger) {
            return IntNode.valueOf(number.intValue());
        }
        if (number instanceof Long || number instanceof AtomicLong) {
            return LongNode.valueOf(number.longValue());
        }
        if (number instanceof Short || number instanceof Byte) {
            return ShortNode.valueOf(number.shortValue());
        }
        if (number instanceof Double) {
            double value = number.doubleValue();
            return Double.isNaN(value) || Double.isInfinite(value) ? TextNode.valueOf(number.toString()) : DoubleNode.valueOf(value);
        }
        if (number instanceof Float) {
            float value = number.floatValue();
            return Float.isNaN(value) || Float.isInfinite(value) ? TextNode.valueOf(number.toString()) : FloatNode.valueOf(value);
        }
        if (number instanceof BigDecimal) {
            return TextNode.valueOf(((BigDecimal) number).toPlainString());
        }
        if (number instanceof BigInteger) {
            return TextNode.valueOf(number.toString());
        }
        return TextNode.valueOf(number.toString());
    }
}
