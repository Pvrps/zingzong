/**
 * From a call to a span to a trace file. {@link ca.gc.cra.xz02.devops.wltrace.agent.record.TraceRuntime}
 * is the static edge the advice calls; it holds the one {@link ca.gc.cra.xz02.devops.wltrace.agent.record.TraceAgent}.
 * A {@link ca.gc.cra.xz02.devops.wltrace.agent.record.SpanRecorder} subclass per kind reads
 * {@link ca.gc.cra.xz02.devops.wltrace.agent.record.SpanFacts} at enter and completes the span at
 * exit; the thread's {@link ca.gc.cra.xz02.devops.wltrace.agent.record.SpanStack} turns nested
 * spans into a trace, which {@link ca.gc.cra.xz02.devops.wltrace.agent.record.TraceWriter} writes.
 * {@link ca.gc.cra.xz02.devops.wltrace.agent.record.RecordingLifecycle} starts and stops recordings
 * and {@link ca.gc.cra.xz02.devops.wltrace.agent.record.RecordingControl} exposes that over JMX.
 */
package ca.gc.cra.xz02.devops.wltrace.agent.record;
