/**
 * The trace format. One {@link ca.gc.cra.xz02.devops.wltrace.model.Trace} per file, spans stored flat with
 * {@code parentId}. Classes are plain mutable records with public fields: the JSON is the
 * contract, these are its direct image. Versioned by {@link ca.gc.cra.xz02.devops.wltrace.model.Trace#formatVersion};
 * the schema is in {@code trace.schema.json} on the classpath.
 */
package ca.gc.cra.xz02.devops.wltrace.model;
