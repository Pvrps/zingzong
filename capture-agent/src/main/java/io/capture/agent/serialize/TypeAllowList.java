package io.capture.agent.serialize;


import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Which classes may be named by {@code "@type"} and instantiated on deserialization. The
 * agent emits {@code "@type"} for every object it sees; this list is the deserialization
 * side's guard, in the spirit of Jackson's polymorphic type validator. Empty allow-list
 * means "any application class"; JDK-internal and container packages are always denied.
 */
public final class TypeAllowList {

    private static final String[] DENIED_PREFIXES = {
            "java.lang.Runtime", "java.lang.Process", "java.lang.ClassLoader", "java.lang.Thread",
            "javax.management.", "javax.naming.", "javax.script.", "javax.sql.",
            "sun.", "com.sun.", "jdk.",
            "weblogic.", "com.bea.", "com.oracle.", "oracle.",
            "org.apache.commons.collections.functors.", "org.springframework.",
    };

    private final List<Pattern> allowed;

    public TypeAllowList(List<Pattern> allowed) {
        this.allowed = allowed == null ? Collections.<Pattern>emptyList() : allowed;
    }

    public static TypeAllowList allowAll() {
        return new TypeAllowList(Collections.<Pattern>emptyList());
    }

    /** Globs separated by {@code |}: {@code *} any run of characters, {@code ?} one; matched against the whole class name. */
    public static TypeAllowList of(String globs) {
        List<Pattern> patterns = new ArrayList<Pattern>();
        if (globs != null) {
            for (String glob : globs.split("\\|")) {
                String g = glob.trim();
                if (!g.isEmpty()) {
                    StringBuilder regex = new StringBuilder();
                    for (int i = 0; i < g.length(); i++) {
                        char c = g.charAt(i);
                        regex.append(c == '*' ? ".*" : c == '?' ? "." : Pattern.quote(String.valueOf(c)));
                    }
                    patterns.add(Pattern.compile(regex.toString()));
                }
            }
        }
        return new TypeAllowList(patterns);
    }

    public boolean allows(String className) {
        for (String prefix : DENIED_PREFIXES) {
            if (className.startsWith(prefix)) {
                return false;
            }
        }
        if (allowed.isEmpty()) {
            return true;
        }
        for (Pattern pattern : allowed) {
            if (pattern.matcher(className).matches()) {
                return true;
            }
        }
        return false;
    }

    List<Pattern> patterns() {
        return new ArrayList<Pattern>(allowed);
    }
}
