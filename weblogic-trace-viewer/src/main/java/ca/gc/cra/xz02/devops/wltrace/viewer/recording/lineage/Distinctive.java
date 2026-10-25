package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which scalars are worth indexing for lineage, and their normalized form. A value links two
 * traces only if it is unlikely to coincide by chance:
 * <ul>
 *   <li>strings of length ≥ 6 that are not dictionary-like (a single word of letters)</li>
 *   <li>UUIDs; ISO dates, times and instants</li>
 *   <li>numbers ≥ 1000 or with a decimal part</li>
 *   <li>smaller integers only where the path names an identifier ({@code id}, {@code orderId},
 *       {@code ...Key}, {@code ...Number}, {@code ...Ref}), so that an order id like 42 can link</li>
 * </ul>
 * Booleans, nulls, enum-like words and small quantities are never indexed. Markers
 * ({@code @type}, {@code @id}, {@code @ref}, ...) are skipped by the flattener.
 */
public final class Distinctive {

    public static final int MIN_STRING_LENGTH = 6;
    public static final long MIN_INTEGER = 1000;

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern ISO_DATE_OR_TIME = Pattern.compile("\\d{4}-\\d{2}(-\\d{2})?([T ].*)?|\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");
    private static final Pattern SINGLE_WORD = Pattern.compile("\\p{L}+");
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    /** id, ID, orderId, order_id, ORDER_ID, customerKey, poNumber, docRef, ...Uuid; not "valid" or "width". */
    private static final Pattern IDENTIFIER_PATH = Pattern.compile(
            "id|ID|.*Id|.*ID|.*_id|.*Key|.*KEY|.*_key|.*Number|.*NUMBER|.*_number|.*Ref|.*REF|.*_ref|.*Uuid|.*UUID|.*Guid|.*GUID");

    private Distinctive() {
    }

    /** Normalized form, or null when the value is not distinctive at this path. */
    public static String normalize(JsonNode node, String path) {
        if (node == null || node.isNull() || node.isBoolean() || node.isContainerNode()) {
            return null;
        }
        if (node.isNumber()) {
            return number(node.decimalValue(), path);
        }
        String text = node.asText().trim();
        if (text.isEmpty()) {
            return null;
        }
        if (NUMBER.matcher(text).matches()) {
            try {
                return number(new BigDecimal(text), path);
            } catch (NumberFormatException ignored) {
                // fall through to the string rules
            }
        }
        if (UUID.matcher(text).matches()) {
            return text.toLowerCase(Locale.ROOT);
        }
        if (ISO_DATE_OR_TIME.matcher(text).matches()) {
            return text;
        }
        if (text.length() < MIN_STRING_LENGTH) {
            return null;
        }
        if (SINGLE_WORD.matcher(text).matches()) {
            return null; // dictionary-like: "Amsterdam", "CONFIRMED", "pending"
        }
        return text;
    }

    private static String number(BigDecimal value, String path) {
        BigDecimal canonical = value.stripTrailingZeros();
        boolean integer = canonical.scale() <= 0;
        if (!integer) {
            return canonical.toPlainString();
        }
        if (canonical.abs().compareTo(BigDecimal.valueOf(MIN_INTEGER)) >= 0) {
            return canonical.toPlainString();
        }
        return isIdentifierPath(path) ? canonical.toPlainString() : null;
    }

    /** The last segment of the path names an identifier: id, orderId, customerKey, poNumber, ... */
    public static boolean isIdentifierPath(String path) {
        if (path == null) {
            return false;
        }
        String last = path;
        int dot = last.lastIndexOf('.');
        if (dot >= 0) {
            last = last.substring(dot + 1);
        }
        int bracket = last.indexOf('[');
        if (bracket >= 0) {
            last = last.substring(0, bracket);
        }
        return !last.isEmpty() && IDENTIFIER_PATH.matcher(last).matches();
    }
}
