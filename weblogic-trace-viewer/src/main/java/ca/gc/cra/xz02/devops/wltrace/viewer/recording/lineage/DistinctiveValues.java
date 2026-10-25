package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Which scalars are worth indexing for lineage, and their normalised form. A value links two
 * traces only when it is unlikely to coincide by chance: strings of six characters or more that
 * are not a single word of letters; UUIDs; ISO dates, times and instants; numbers of 1000 or more
 * or with a decimal part; smaller integers only where the path names an identifier ({@code id},
 * {@code orderId}, {@code ...Key}, {@code ...Number}, {@code ...Ref}, {@code ...Uuid}). Booleans,
 * nulls, enum-like words and small quantities are never indexed.
 */
public final class DistinctiveValues {

    public static final int MIN_STRING_LENGTH = 6;
    public static final long MIN_INTEGER = 1000;

    private static final Pattern UUID = Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Pattern ISO_DATE_OR_TIME = Pattern.compile("\\d{4}-\\d{2}(-\\d{2})?([T ].*)?|\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?");
    private static final Pattern SINGLE_WORD = Pattern.compile("\\p{L}+");
    private static final Pattern NUMBER = Pattern.compile("[-+]?\\d+(\\.\\d+)?([eE][-+]?\\d+)?");
    /** id, ID, orderId, order_id, ORDER_ID, customerKey, poNumber, docRef, ...Uuid; not "valid" or "width". */
    private static final Pattern IDENTIFIER_PATH = Pattern.compile(
            "id|ID|.*Id|.*ID|.*_id|.*Key|.*KEY|.*_key|.*Number|.*NUMBER|.*_number|.*Ref|.*REF|.*_ref|.*Uuid|.*UUID|.*Guid|.*GUID");

    private DistinctiveValues() {
    }

    /** The normalised form, or null when the value is not distinctive at this path. */
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
            } catch (NumberFormatException notANumber) {
                // the string rules below decide
            }
        }
        if (UUID.matcher(text).matches()) {
            return text.toLowerCase(Locale.ROOT);
        }
        if (ISO_DATE_OR_TIME.matcher(text).matches()) {
            return text;
        }
        if (text.length() < MIN_STRING_LENGTH || SINGLE_WORD.matcher(text).matches()) {
            return null;
        }
        return text;
    }

    private static String number(BigDecimal value, String path) {
        BigDecimal canonical = value.stripTrailingZeros();
        boolean integer = canonical.scale() <= 0;
        if (!integer || canonical.abs().compareTo(BigDecimal.valueOf(MIN_INTEGER)) >= 0) {
            return canonical.toPlainString();
        }
        return isIdentifierPath(path) ? canonical.toPlainString() : null;
    }

    /** True when the last segment of the path names an identifier: id, orderId, customerKey, poNumber, ... */
    public static boolean isIdentifierPath(String path) {
        if (path == null) {
            return false;
        }
        String last = path.substring(path.lastIndexOf('.') + 1);
        int bracket = last.indexOf('[');
        if (bracket >= 0) {
            last = last.substring(0, bracket);
        }
        return !last.isEmpty() && IDENTIFIER_PATH.matcher(last).matches();
    }
}
