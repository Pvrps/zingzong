package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;

/** One scalar found in a span: where it is in the recording and its normalised string form. */
public final class ValueLocation {

    public final int traceIndex;
    public final String traceId;
    public final long traceSequence;
    public final String spanId;
    public final int spanOrder;
    /** Relative to the span: {@code args[0].orderId}, {@code result.id}, {@code http.requestBody.customer.name}. */
    public final String path;
    public final String value;
    /** True when the span is the trace's root. */
    public final boolean rootSpan;

    public ValueLocation(int traceIndex, String traceId, long traceSequence, String spanId, int spanOrder,
                         String path, String value, boolean rootSpan) {
        this.traceIndex = traceIndex;
        this.traceId = traceId;
        this.traceSequence = traceSequence;
        this.spanId = spanId;
        this.spanOrder = spanOrder;
        this.path = path;
        this.value = value;
        this.rootSpan = rootSpan;
    }

    @Override
    public String toString() {
        return "trace " + traceSequence + " / " + spanId + " / " + path + " = " + value;
    }
}
