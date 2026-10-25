package ca.gc.cra.xz02.devops.wltrace.viewer.recording;

import ca.gc.cra.xz02.devops.wltrace.viewer.recording.lineage.LineageDetector;
import ca.gc.cra.xz02.devops.wltrace.model.LineageEntry;
import ca.gc.cra.xz02.devops.wltrace.model.LineageFile;
import ca.gc.cra.xz02.devops.wltrace.model.Span;
import org.testng.annotations.Test;

import java.net.URL;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertNull;
import static org.testng.Assert.assertTrue;

/** Proves that HTTP roots carry the request and response raw, with the resource child and the EJB tree under them, and that lineage reads the bodies. */
public class HttpRecordingTest {

    private static Path recording() throws Exception {
        URL url = HttpRecordingTest.class.getResource("/recordings/create-confirm-soap/recording.json");
        assertNotNull(url);
        return Paths.get(url.toURI()).getParent();
    }

    @Test
    public void hasHttpRootsWithResourceChildrenThenTheEjbTree() throws Exception {
        RecordingDirectory recording = RecordingDirectory.load(recording());
        assertEquals(recording.traces.size(), 3);

        Span confirmRoot = recording.traces.get(1).root();
        assertEquals(confirmRoot.kind, Span.KIND_HTTP);
        assertEquals(confirmRoot.http.method, "POST");
        assertTrue(confirmRoot.http.path.matches("/orders/api/orders/\\d+/confirm"), confirmRoot.http.path);
        assertEquals(confirmRoot.http.status, Integer.valueOf(200));
        assertEquals(confirmRoot.http.requestBody, "{\"confirmedBy\":\"ada\",\"notes\":\"please gift-wrap\"}", "the exact body the client sent");
        assertEquals(confirmRoot.http.requestHeaders.get("Authorization"), Collections.singletonList("Bearer test-token-123"), "nothing is redacted");
        assertTrue(confirmRoot.http.requestHeaders.get("Cookie").get(0).startsWith("JSESSIONID="));
        assertTrue(confirmRoot.http.responseHeaders.containsKey("Content-Type"));
        assertTrue(confirmRoot.http.responseBody.contains("\"status\":\"CONFIRMED\""));
        assertNotNull(recording.traces.get(1).trace.trace.httpSessionId);

        List<Span> spans = recording.traces.get(1).trace.spans;
        assertEquals(spans.get(1).layer, "jaxrs");
        assertEquals(SpanNames.span(spans.get(1)), "OrdersResource.confirm [jaxrs]");
        assertEquals(spans.get(1).parentId, "s1");
        assertEquals(SpanNames.span(spans.get(2)), "OrderService.confirmOrder");
        assertEquals(spans.get(2).parentId, "s2");

        Span soapRoot = recording.traces.get(2).root();
        assertEquals(soapRoot.http.path, "/tax/TaxWebService");
        assertTrue(soapRoot.http.requestBody.contains("<tax:computeTax>"), "the SOAP envelope as sent");
        assertTrue(soapRoot.http.responseBody.contains("<ns0:computeTaxResponse"));
        assertEquals(recording.traces.get(2).trace.spans.get(1).layer, "jaxws");
        assertEquals(SpanNames.span(recording.traces.get(2).trace.spans.get(2)), "TaxService.computeTax");
    }

    @Test
    public void theRootRequestBodyIsTheUserInputSource() throws Exception {
        RecordingDirectory recording = RecordingDirectory.load(recording());
        LineageFile lineage = new LineageDetector().detect(recording.manifest.id, recording.traces);

        LineageEntry name = entry(lineage, "Ada Lovelace");
        assertEquals(name.classification, LineageEntry.CLASS_USER_INPUT);
        assertEquals(name.to.path, "http.requestBody.customer.name");
        assertEquals(name.to.span, "s1");
        assertEquals(entry(lineage, "please gift-wrap").classification, LineageEntry.CLASS_USER_INPUT);
        assertEquals(entry(lineage, "please gift-wrap").to.path, "http.requestBody.notes");

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
        assertEquals(orderId.from.path, "result.id");
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
