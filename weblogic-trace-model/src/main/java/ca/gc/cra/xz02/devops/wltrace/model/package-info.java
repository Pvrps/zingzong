/**
 * The trace format: plain classes with public fields whose JSON is the contract between the agent
 * that writes recordings and everything that reads them. One {@link ca.gc.cra.xz02.devops.wltrace.model.Trace}
 * per file holds its spans flat, linked by {@code parentId}; a {@link ca.gc.cra.xz02.devops.wltrace.model.RecordingManifest}
 * describes one server's part of a recording; {@link ca.gc.cra.xz02.devops.wltrace.model.RecordingIndex}
 * and the lineage classes are what the viewer computes from them. {@link ca.gc.cra.xz02.devops.wltrace.model.TraceJson}
 * is the one mapper configuration; {@code trace.schema.json} on the class path describes every field.
 */
package ca.gc.cra.xz02.devops.wltrace.model;
