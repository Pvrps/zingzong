package io.capture.agent;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Agent arguments: {@code key=value,key=value}.
 * <pre>
 *   out=DIR            where agent.log and recordings go (default: java.io.tmpdir/capture)
 *   enabled=BOOL       start with a recording already active (default false)
 *   include=GLOB|GLOB  only instrument classes matching one of these (default: all)
 *   exclude=GLOB|GLOB  never instrument classes matching one of these
 *   maxDepth=N         serializer nesting limit (default 12)
 *   maxElements=N      serializer per-collection limit (default 500)
 *   types=GLOB|GLOB    classes a deserializer may instantiate from "@type" (default: any application class)
 * </pre>
 * Globs match fully qualified class names; {@code *} matches any run of characters,
 * {@code ?} one character. Lists use {@code |} because {@code ,} separates arguments.
 *
 * None of these need setting for correct output; they exist for tests and diagnostics. The
 * hook is chosen automatically (see {@link ServerDetection}); {@code hook=annotation|container}
 * is an undocumented override for testing only.
 */
public final class AgentConfig {

    public static final String HOOK_ANNOTATION = "annotation";
    public static final String HOOK_CONTAINER = "container";

    public final File outDir;
    public final boolean enabled;
    public final List<Pattern> include;
    public final List<Pattern> exclude;
    /** Null unless overridden: the hook is auto-detected. */
    public final String hook;
    public final int maxDepth;
    public final int maxElements;
    public final List<Pattern> types;

    private AgentConfig(File outDir, boolean enabled, List<Pattern> include, List<Pattern> exclude, String hook,
                        int maxDepth, int maxElements, List<Pattern> types) {
        this.outDir = outDir;
        this.enabled = enabled;
        this.include = include;
        this.exclude = exclude;
        this.hook = hook;
        this.maxDepth = maxDepth;
        this.maxElements = maxElements;
        this.types = types;
    }

    public static AgentConfig parse(String args) {
        File outDir = new File(System.getProperty("java.io.tmpdir"), "capture");
        boolean enabled = false;
        List<Pattern> include = Collections.emptyList();
        List<Pattern> exclude = Collections.emptyList();
        String hook = null;
        int maxDepth = 12;
        int maxElements = 500;
        List<Pattern> types = Collections.emptyList();

        if (args != null) {
            for (String pair : args.split(",")) {
                String trimmed = pair.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int eq = trimmed.indexOf('=');
                String key = eq < 0 ? trimmed : trimmed.substring(0, eq).trim();
                String value = eq < 0 ? "" : trimmed.substring(eq + 1).trim();
                if (key.equals("out")) {
                    outDir = new File(value);
                } else if (key.equals("enabled")) {
                    enabled = value.isEmpty() || Boolean.parseBoolean(value);
                } else if (key.equals("include")) {
                    include = globs(value);
                } else if (key.equals("exclude")) {
                    exclude = globs(value);
                } else if (key.equals("hook")) {
                    if (!value.equals(HOOK_ANNOTATION) && !value.equals(HOOK_CONTAINER)) {
                        throw new IllegalArgumentException("hook must be annotation or container, got: " + value);
                    }
                    hook = value;
                } else if (key.equals("maxDepth")) {
                    maxDepth = Integer.parseInt(value);
                } else if (key.equals("maxElements")) {
                    maxElements = Integer.parseInt(value);
                } else if (key.equals("types")) {
                    types = globs(value);
                } else {
                    throw new IllegalArgumentException("unknown agent argument: " + key);
                }
            }
        }
        return new AgentConfig(outDir, enabled, include, exclude, hook, maxDepth, maxElements, types);
    }

    /** True when the class name passes the include list (if any) and no exclude matches. */
    public boolean accepts(String className) {
        if (!include.isEmpty() && !anyMatch(include, className)) {
            return false;
        }
        return !anyMatch(exclude, className);
    }

    private static boolean anyMatch(List<Pattern> patterns, String name) {
        for (Pattern pattern : patterns) {
            if (pattern.matcher(name).matches()) {
                return true;
            }
        }
        return false;
    }

    public static List<Pattern> globs(String list) {
        List<Pattern> patterns = new ArrayList<Pattern>();
        for (String glob : list.split("\\|")) {
            String g = glob.trim();
            if (!g.isEmpty()) {
                patterns.add(globToPattern(g));
            }
        }
        return patterns;
    }

    static Pattern globToPattern(String glob) {
        StringBuilder regex = new StringBuilder();
        for (int i = 0; i < glob.length(); i++) {
            char c = glob.charAt(i);
            if (c == '*') {
                regex.append(".*");
            } else if (c == '?') {
                regex.append('.');
            } else {
                regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return Pattern.compile(regex.toString());
    }

    @Override
    public String toString() {
        return "out=" + outDir + ", enabled=" + enabled + ", include=" + include
                + ", exclude=" + exclude + ", hook=" + hook + ", maxDepth=" + maxDepth
                + ", maxElements=" + maxElements + ", types=" + types;
    }
}
