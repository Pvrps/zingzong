package io.capture.agent;

import java.io.File;

/**
 * Agent arguments. There is one: {@code out=DIR}, where agent.log and the recordings go
 * (default: {@code java.io.tmpdir/capture}).
 */
public final class AgentConfig {

    public final File outDir;

    private AgentConfig(File outDir) {
        this.outDir = outDir;
    }

    public static AgentConfig parse(String args) {
        File outDir = new File(System.getProperty("java.io.tmpdir"), "capture");
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
                } else {
                    throw new IllegalArgumentException("unknown agent argument: " + key + " (the only argument is out=DIR)");
                }
            }
        }
        return new AgentConfig(outDir);
    }

    @Override
    public String toString() {
        return "out=" + outDir;
    }
}
