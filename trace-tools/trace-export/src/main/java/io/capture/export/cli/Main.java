package io.capture.export.cli;

import io.capture.export.Selection;
import io.capture.export.Step;
import io.capture.export.placeholder.PlaceholderExporter;
import io.capture.reader.RecordingDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <pre>
 *   trace-reader export &lt;dir&gt; --spans 1:s3,2:s3   export the selected spans as steps.yaml
 * </pre>
 * A span is named {@code <trace seq>:<span id>} (or {@code <trace id>:<span id>}).
 */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2 || !args[0].equals("export")) {
            usage();
            System.exit(2);
        }
        Path dir = Paths.get(args[1]);
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 2; i < args.length; i++) {
            if (args[i].equals("--spans") && i + 1 < args.length) {
                for (String id : args[++i].split(",")) {
                    if (!id.trim().isEmpty()) {
                        ids.add(id.trim());
                    }
                }
            } else {
                usage();
                System.exit(2);
            }
        }
        if (ids.isEmpty()) {
            usage();
            System.exit(2);
        }
        RecordingDir recording = RecordingDir.load(dir);
        Selection selection = Selection.of(recording, ids);
        List<Step> steps = new PlaceholderExporter().write(recording, selection);
        for (Step step : steps) {
            System.out.println(step.id + ": " + step.name + " (" + step.traceId + "/" + step.spanId + ")"
                    + (step.variables.isEmpty() ? "" : " extracts " + names(step))
                    + (step.substitutions.isEmpty() ? "" : " substitutes " + step.substitutions.size() + " field(s)"));
        }
        System.out.println("wrote " + dir.resolve(PlaceholderExporter.FILE_NAME));
    }

    private static String names(Step step) {
        StringBuilder sb = new StringBuilder();
        for (Step.Variable v : step.variables) {
            sb.append(sb.length() == 0 ? "" : ", ").append("${").append(v.name).append("} = ").append(v.path);
        }
        return sb.toString();
    }

    private static void usage() {
        System.err.println("usage: trace-reader export <dir> --spans <trace seq>:<span id>[,...]   e.g. --spans 1:s3,2:s3");
    }
}
