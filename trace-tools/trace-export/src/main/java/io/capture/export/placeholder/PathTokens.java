package io.capture.export.placeholder;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/** Lineage paths ("args[0].lines[1].sku") as tokens: names and "[n]" indexes. */
final class PathTokens {

    private PathTokens() {
    }

    static List<String> tokenize(String path) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            if (c == '.') {
                flush(current, tokens);
            } else if (c == '[') {
                flush(current, tokens);
                int close = path.indexOf(']', i);
                tokens.add(path.substring(i, close + 1));
                i = close;
            } else {
                current.append(c);
            }
        }
        flush(current, tokens);
        return tokens;
    }

    private static void flush(StringBuilder current, List<String> tokens) {
        if (current.length() > 0) {
            tokens.add(current.toString());
            current.setLength(0);
        }
    }

    static JsonNode child(JsonNode node, String token) {
        if (token.startsWith("[")) {
            return node.isArray() ? node.get(Integer.parseInt(token.substring(1, token.length() - 1))) : null;
        }
        return node.isObject() ? node.get(token) : null;
    }
}
