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

    private static final String SQL_TIMESTAMP = "java.sql.Timestamp";

    private Scalars() {
    }

    /** Returns a scalar node, or null when the value is not a scalar. */
    static JsonNode write(Object value, SerializationContext ctx) {
        if (value instanceof String) {
            return text((String) value, ctx);
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
            return text(value.toString(), ctx);
        }
        if (value instanceof Date) {
            // java.sql lives in the platform loader, invisible to the bootstrap-loaded agent: recognised by name.
            Date date = (Date) value;
            long millis = date.getTime();
            if (value.getClass().getName().equals(SQL_TIMESTAMP)) {
                try {
                    int nanos = (Integer) value.getClass().getMethod("getNanos").invoke(value);
                    return TextNode.valueOf(Instant.ofEpochSecond(Math.floorDiv(millis, 1000L), nanos).toString());
                } catch (Throwable ignored) {
                    // millisecond precision below
                }
            }
            return TextNode.valueOf(Instant.ofEpochMilli(millis).toString());
        }
        if (value instanceof Calendar) {
            Calendar c = (Calendar) value;
            return TextNode.valueOf(ZonedDateTime.ofInstant(Instant.ofEpochMilli(c.getTimeInMillis()), c.getTimeZone().toZoneId()).toString());
        }
        if (value instanceof StackTraceElement) {
            return TextNode.valueOf(value.toString());
        }
        if (value instanceof Temporal || value instanceof TemporalAmount || value instanceof ZoneId) {
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

    private static JsonNode text(String s, SerializationContext ctx) {
        int max = ctx.config.maxStringLength;
        if (s.length() > max) {
            int dropped = s.length() - max;
            ctx.warn("string ceiling " + max + " reached; " + dropped + " of " + s.length() + " chars dropped", String.class.getName());
            return Placeholders.text(s.substring(0, max), s.length(), dropped);
        }
        return TextNode.valueOf(s);
    }

    private static JsonNode number(Number n) {
        if (n instanceof Integer) {
            return IntNode.valueOf(n.intValue());
        }
        if (n instanceof Long || n instanceof AtomicLong) {
            return LongNode.valueOf(n.longValue());
        }
        if (n instanceof Short || n instanceof Byte) {
            return ShortNode.valueOf(n.shortValue());
        }
        if (n instanceof Double) {
            double d = n.doubleValue();
            return Double.isNaN(d) || Double.isInfinite(d) ? TextNode.valueOf(n.toString()) : DoubleNode.valueOf(d);
        }
        if (n instanceof Float) {
            float f = n.floatValue();
            return Float.isNaN(f) || Float.isInfinite(f) ? TextNode.valueOf(n.toString()) : FloatNode.valueOf(f);
        }
        if (n instanceof BigDecimal) {
            return TextNode.valueOf(((BigDecimal) n).toPlainString());
        }
        if (n instanceof BigInteger) {
            return TextNode.valueOf(n.toString());
        }
        if (n instanceof AtomicInteger) {
            return IntNode.valueOf(n.intValue());
        }
        return TextNode.valueOf(n.toString());
    }
}
