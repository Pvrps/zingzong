/**
 * Where the values in a recording came from. {@link ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.ValueFlattener}
 * turns inputs and outputs into {@link ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.ValueLocation}s,
 * {@link ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.DistinctiveValues} decides which are worth
 * indexing, and {@link ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.LineageDetector} links later
 * inputs to earlier outputs and classifies the rest.
 */
package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;
