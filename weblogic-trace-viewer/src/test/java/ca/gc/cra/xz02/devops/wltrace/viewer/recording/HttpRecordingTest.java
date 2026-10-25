package ca.gc.cra.xz02.devops.wltrace.viewer.recording;

import ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.LineageDetector;
import ca.gc.cra.xz02.devops.wltrace.model.LineageEntry;
import ca.gc.cra.xz02.devops.wltrace.model.LineageFile;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Proves that HTTP roots carry the request and response raw, with the resource child and the EJB tree under them, and that lineage reads the bodies. */
class HttpRecordingTest {

    private static Path recording() throws Exception {
        URL url = HttpRecordingTest.class.getResource("/recordings/create-confirm-soap/recording.json");
        assertNotNull(url);
        return Paths.get(url.toURI()).getParent();
    }

    @Test
    void hasHttpRootsWithResourceChildrenThenTheEjbTree() throws Exception {
        RecordingDirectory recording = RecordingDirectory.load(recording());
        assertEquals(3, recording.traces.size());

        Span confirmRoot = recording.traces.get(1).root();
        assertEquals(Span.KIND_HTTP, confirmRoot.kind);
        assertEquals("POST", confirmRoot.http.method);
        assertTrue(confirmRoot.http.path.matches("/orders/api/orders/\\d+/confirm"), confirmRoot.http.path);
        assertEquals(Integer.valueOf(200), confirmRoot.http.status);
        assertEquals("{\"confirmedBy\":\"ada\",\"notes\":\"please gift-wrap\"}", confirmRoot.http.requestBody, "the exact body the client sent");
        assertEquals(Collections.singletonList("Bearer test-token-123"), confirmRoot.http.requestHeaders.get("Authorization"), "nothing is redacted");
        assertTrue(confirmRoot.http.requestHeaders.get("Cookie").get(0).startsWith("JSESSIONID="));
        assertTrue(confirmRoot.http.responseHeaders.containsKey("Content-Type"));
        assertTrue(confirmRoot.http.responseBody.contains("\"status\":\"CONFIRMED\""));
        assertNotNull(recording.traces.get(1).trace.trace.httpSessionId);

        List<Span> spans = recording.traces.get(1).trace.spans;
        assertEquals("jaxrs", spans.get(1).layer);
        assertEquals("OrdersResource.confirm [jaxrs]", SpanLabels.span(spans.get(1)));
        assertEquals("s1", spans.get(1).parentId);
        assertEquals("OrderService.confirmOrder", SpanLabels.span(spans.get(2)));
        assertEquals("s2", spans.get(2).parentId);

        Span soapRoot = recording.traces.get(2).root();
        assertEquals("/tax/TaxWebService", soapRoot.http.path);
        assertTrue(soapRoot.http.requestBody.contains("<tax:computeTax>"), "the SOAP envelope as sent");
        assertTrue(soapRoot.http.responseBody.contains("<ns0:computeTaxResponse"));
        assertEquals("jaxws", recording.traces.get(2).trace.spans.get(1).layer);
        assertEquals("TaxService.computeTax", SpanLabels.span(recording.traces.get(2).trace.spans.get(2)));
    }

    @Test
    void theRootRequestBodyIsTheUserInputSource() throws Exception {
        RecordingDirectory recording = RecordingDirectory.load(recording());
        LineageFile lineage = new LineageDetector().detect(recording.manifest.id, recording.traces);

        LineageEntry name = entry(lineage, "Ada Lovelace");
        assertEquals(LineageEntry.CLASS_USER_INPUT, name.classification);
        assertEquals("http.requestBody.customer.name", name.to.path);
        assertEquals("s1", name.to.span);
        assertEquals(LineageEntry.CLASS_USER_INPUT, entry(lineage, "please gift-wrap").classification);
        assertEquals("http.requestBody.notes", entry(lineage, "please gift-wrap").to.path);

        // SOAP elements are flattened too: NL-NH and GOODS are short/dictionary-like, 123.74 is distinctive.
        boolean soapAmount = false;
        for (LineageEntry entry : lineage.entries) {
            if ("123.74".equals(entry.value) && entry.to.path.startsWith("http.requestBody.Envelope.Body.computeTax.amount")) {
                soapAmount = true;
            }
        }
        assertTrue(soapAmount, "SOAP envelope element flattened");

        LineageEntry orderId = null;
        for (LineageEntry entry : lineage.entries) {
            if (entry.from != null && "args[0].orderId".equals(entry.to.path)) {
                orderId = entry;
            }
        }
        assertNotNull(orderId);
        assertEquals("result.id", orderId.from.path);
        assertNull(entry(lineage, "GOLD"), "enum-like words are never indexed");
    }

    private static LineageEntry entry(LineageFile lineage, String value) {
        for (LineageEntry entry : lineage.entries) {
            if (value.equals(entry.value)) {
                return entry;
            }
        }
        return null;
    }
}
