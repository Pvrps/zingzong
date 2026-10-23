package io.capture.export;

import io.capture.reader.RecordingDir;

import java.util.List;

/**
 * Turns selected spans of a recording into steps. {@link StepBuilder} does the mapping from
 * spans and lineage; an exporter decides the output format. The placeholder exporter proves
 * the model carries everything a step needs; the work framework's exporter is written against
 * the same {@link Step}s later.
 */
public interface Exporter {

    List<Step> export(RecordingDir recording, Selection selection);
}
