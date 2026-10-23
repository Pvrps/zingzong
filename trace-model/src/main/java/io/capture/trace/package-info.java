/**
 * The trace format. One {@link io.capture.trace.Trace} per file, spans stored flat with
 * {@code parentId}. Classes are plain mutable records with public fields: the JSON is the
 * contract, these are its direct image. Versioned by {@link io.capture.trace.Trace#formatVersion};
 * the schema is in {@code trace.schema.json} on the classpath.
 */
package io.capture.trace;
