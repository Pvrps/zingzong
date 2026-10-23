package io.capture.agent.serialize;

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
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.OffsetDateTime;
import java.time.OffsetTime;
import java.time.Period;
import java.time.Year;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.Calendar;
import java.util.Currency;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Leaf values: written as JSON scalars, read back by declared type. Enums by name,
 * BigDecimal/BigInteger as strings (no precision loss), java.time and java.util.Date as
 * ISO-8601 strings.
 */
final class Scalars {

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
            return TextNode.valueOf(Instant.ofEpochMilli(((Date) value).getTime()).toString());
        }
        if (value instanceof Calendar) {
            return TextNode.valueOf(Instant.ofEpochMilli(((Calendar) value).getTimeInMillis()).toString());
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

    static boolean isScalarType(Class<?> type) {
        return type.isPrimitive() || type == String.class || Number.class.isAssignableFrom(type)
                || type == Boolean.class || type == Character.class || type.isEnum()
                || CharSequence.class.isAssignableFrom(type) || Date.class.isAssignableFrom(type)
                || Calendar.class.isAssignableFrom(type) || Temporal.class.isAssignableFrom(type)
                || TemporalAmount.class.isAssignableFrom(type) || ZoneId.class.isAssignableFrom(type)
                || type == UUID.class || type == URI.class || type == URL.class || type == File.class
                || Path.class.isAssignableFrom(type) || type == Locale.class || type == Currency.class
                || type == AtomicBoolean.class;
    }

    private static JsonNode text(String s, SerializationContext ctx) {
        int max = ctx.config.maxStringLength;
        if (s.length() > max) {
            ctx.warn("string truncated from " + s.length() + " to " + max + " chars", String.class.getName());
            return TextNode.valueOf(s.substring(0, max));
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

    /** Reads a scalar into {@code type}; returns null for null nodes. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Object read(JsonNode node, Class<?> type) throws GraphException {
        if (node == null || node.isNull()) {
            return type.isPrimitive() ? defaultPrimitive(type) : null;
        }
        try {
            if (type == String.class || type == CharSequence.class || type == Object.class && node.isTextual()) {
                return node.asText();
            }
            if (type == int.class || type == Integer.class) {
                return node.isNumber() ? node.intValue() : Integer.parseInt(node.asText());
            }
            if (type == long.class || type == Long.class) {
                return node.isNumber() ? node.longValue() : Long.parseLong(node.asText());
            }
            if (type == double.class || type == Double.class) {
                return node.isNumber() ? node.doubleValue() : Double.parseDouble(node.asText());
            }
            if (type == float.class || type == Float.class) {
                return node.isNumber() ? node.floatValue() : Float.parseFloat(node.asText());
            }
            if (type == short.class || type == Short.class) {
                return node.isNumber() ? node.shortValue() : Short.parseShort(node.asText());
            }
            if (type == byte.class || type == Byte.class) {
                return node.isNumber() ? (byte) node.intValue() : Byte.parseByte(node.asText());
            }
            if (type == boolean.class || type == Boolean.class) {
                return node.isBoolean() ? node.booleanValue() : Boolean.parseBoolean(node.asText());
            }
            if (type == char.class || type == Character.class) {
                String s = node.asText();
                return s.isEmpty() ? '\0' : s.charAt(0);
            }
            if (type == BigDecimal.class) {
                return new BigDecimal(node.asText());
            }
            if (type == BigInteger.class) {
                return new BigInteger(node.asText());
            }
            if (type.isEnum()) {
                return Enum.valueOf((Class<? extends Enum>) type, node.asText());
            }
            if (type == StringBuilder.class) {
                return new StringBuilder(node.asText());
            }
            if (type == StringBuffer.class) {
                return new StringBuffer(node.asText());
            }
            if (type == Date.class) {
                return new Date(Instant.parse(node.asText()).toEpochMilli());
            }
            if (type == Timestamp.class) {
                return new Timestamp(Instant.parse(node.asText()).toEpochMilli());
            }
            if (type == java.sql.Date.class) {
                return new java.sql.Date(Instant.parse(node.asText()).toEpochMilli());
            }
            if (type == java.sql.Time.class) {
                return new java.sql.Time(Instant.parse(node.asText()).toEpochMilli());
            }
            if (Calendar.class.isAssignableFrom(type)) {
                GregorianCalendar c = new GregorianCalendar();
                c.setTimeInMillis(Instant.parse(node.asText()).toEpochMilli());
                return c;
            }
            Object time = readTime(node.asText(), type);
            if (time != null) {
                return time;
            }
            if (type == AtomicInteger.class) {
                return new AtomicInteger(node.intValue());
            }
            if (type == AtomicLong.class) {
                return new AtomicLong(node.longValue());
            }
            if (type == AtomicBoolean.class) {
                return new AtomicBoolean(node.booleanValue());
            }
            if (type == UUID.class) {
                return UUID.fromString(node.asText());
            }
            if (type == URI.class) {
                return URI.create(node.asText());
            }
            if (type == URL.class) {
                return new URL(node.asText());
            }
            if (type == File.class) {
                return new File(node.asText());
            }
            if (Path.class.isAssignableFrom(type)) {
                return Paths.get(node.asText());
            }
            if (type == Locale.class) {
                return Locale.forLanguageTag(node.asText().replace('_', '-'));
            }
            if (type == Currency.class) {
                return Currency.getInstance(node.asText());
            }
            if (type == Object.class || type == Number.class || type == Comparable.class || type == java.io.Serializable.class) {
                return untyped(node);
            }
        } catch (Throwable t) {
            throw new GraphException("cannot read " + node + " as " + type.getName(), t);
        }
        throw new GraphException("cannot read scalar " + node + " as " + type.getName());
    }

    private static Object readTime(String text, Class<?> type) {
        if (type == Instant.class) return Instant.parse(text);
        if (type == LocalDate.class) return LocalDate.parse(text);
        if (type == LocalDateTime.class) return LocalDateTime.parse(text);
        if (type == LocalTime.class) return LocalTime.parse(text);
        if (type == OffsetDateTime.class) return OffsetDateTime.parse(text);
        if (type == OffsetTime.class) return OffsetTime.parse(text);
        if (type == ZonedDateTime.class) return ZonedDateTime.parse(text);
        if (type == YearMonth.class) return YearMonth.parse(text);
        if (type == Year.class) return Year.parse(text);
        if (type == MonthDay.class) return MonthDay.parse(text);
        if (type == Duration.class) return Duration.parse(text);
        if (type == Period.class) return Period.parse(text);
        if (ZoneId.class.isAssignableFrom(type)) return ZoneId.of(text);
        return null;
    }

    /** Best effort for a scalar whose declared type carries no information. */
    static Object untyped(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isInt()) {
            return node.intValue();
        }
        if (node.isLong()) {
            return node.longValue();
        }
        if (node.isFloatingPointNumber()) {
            return node.doubleValue();
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        return node.isNull() ? null : node.asText();
    }

    private static Object defaultPrimitive(Class<?> type) {
        if (type == boolean.class) return false;
        if (type == char.class) return '\0';
        if (type == long.class) return 0L;
        if (type == double.class) return 0d;
        if (type == float.class) return 0f;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        return 0;
    }
}
