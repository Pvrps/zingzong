package ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage;

/** One scalar found in a span: where it is and its normalized string form. */
public final class ValueRef {

    public final int traceIndex;
    public final String traceId;
    public final long traceSeq;
    public final String spanId;
    public final int spanOrder;
    /** Relative to the span, e.g. "args[0].orderId" or "result.id". */
    public final String path;
    public final String value;
    /** True when the span is the trace's root (parentId null). */
    public final boolean rootSpan;

    public ValueRef(int traceIndex, String traceId, long traceSeq, String spanId, int spanOrder,
                    String path, String value, boolean rootSpan) {
        this.traceIndex = traceIndex;
        this.traceId = traceId;
        this.traceSeq = traceSeq;
        this.spanId = spanId;
        this.spanOrder = spanOrder;
        this.path = path;
        this.value = value;
        this.rootSpan = rootSpan;
    }

    @Override
    public String toString() {
        return "trace " + traceSeq + " / " + spanId + " / " + path + " = " + value;
    }
}
