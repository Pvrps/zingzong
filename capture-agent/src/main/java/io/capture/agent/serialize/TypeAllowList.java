package io.capture.agent.serialize;

import io.capture.agent.AgentConfig;

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

    public static TypeAllowList of(String globs) {
        return new TypeAllowList(globs == null ? Collections.<Pattern>emptyList() : AgentConfig.globs(globs));
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
