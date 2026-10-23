package io.capture.export;

import io.capture.export.placeholder.PlaceholderExporter;
import io.capture.reader.RecordingDir;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The three-action recording (Create order, Confirm order, SOAP computeTax; container hook,
 * rec-20261023-175858). Exporting the top EJB span of the first two traces must wire
 * {@code createOrder.result.id} into {@code confirmOrder.args[0].orderId}.
 */
class PlaceholderExporterTest {

    private static final Path RECORDING = Paths.get("src/test/resources/recordings/three-action");

    @Test
    void confirmOrderSubstitutesTheIdCreateOrderExtracts() throws Exception {
        RecordingDir recording = RecordingDir.load(RECORDING);
        Selection selection = Selection.of(recording, new LinkedHashSet<>(Arrays.asList("1:s3", "2:s3")));
        List<Step> steps = new PlaceholderExporter().export(recording, selection);

        assertEquals(2, steps.size());
        Step create = steps.get(0);
        Step confirm = steps.get(1);
        assertEquals("OrderService.createOrder", create.name);
        assertEquals("OrderService.confirmOrder", confirm.name);

        assertEquals(1, create.variables.size());
        Step.Variable id = create.variables.get(0);
        assertEquals("orderId", id.name, "named after the field that consumes it");
        assertEquals("result.id", id.path);
        assertEquals(Arrays.asList("step2"), id.usedBy);
        assertEquals(create.expectedResult.get("value").get("id").asText(), id.capturedValue);

        assertEquals(1, confirm.substitutions.size());
        Step.Substitution s = confirm.substitutions.get(0);
        assertEquals("args[0].orderId", s.path);
        assertEquals("orderId", s.variable);
        assertEquals("step1", s.fromStep);
        assertEquals("result.id", s.fromPath);
        assertEquals(id.capturedValue, s.capturedValue);
        assertEquals(Boolean.TRUE, s.alsoProducedHere, "confirmOrder's own result repeats the id");
        assertNull(s.alternatives, "the HTTP response body is not selected, so no selected alternative");

        assertEquals("java:global/orders-ear/orders-ejb/OrderService!com.acme.orders.api.OrderServiceLocal", create.lookup.jndiNames.get(0));
        assertEquals("com.acme.common.model.Order createOrder(com.acme.common.dto.CreateOrderRequest)", create.lookup.signature);
        assertEquals("LOCAL", create.lookup.invocation);
        assertEquals("com.acme.common.dto.CreateOrderRequest", create.payload.get(0).get("declaredType").asText());
        assertEquals("Ada Lovelace", create.payload.get(0).get("value").get("customer").get("name").asText(), "payload as captured");
        assertEquals(id.capturedValue, confirm.payload.get(0).get("value").get("orderId").asText(), "the Step keeps the captured value; only the YAML shows the placeholder");

        String yaml = PlaceholderExporter.toYaml(recording, steps);
        assertTrue(yaml.contains("\"orderId\": \"${orderId}\"") || yaml.contains("orderId: \"${orderId}\"") || yaml.contains("orderId: ${orderId}"), yaml);
        assertTrue(yaml.contains("name: \"orderId\"") || yaml.contains("name: orderId"), yaml);
    }

    @Test
    void httpRootsExportTheRawBodyAndThePathWithPlaceholders() throws Exception {
        RecordingDir recording = RecordingDir.load(RECORDING);
        Selection selection = Selection.of(recording, new LinkedHashSet<>(Arrays.asList("1:s1", "2:s1")));
        List<Step> steps = new PlaceholderExporter().export(recording, selection);

        Step create = steps.get(0);
        Step confirm = steps.get(1);
        assertEquals("POST", create.lookup.httpMethod);
        assertEquals("/orders/api/orders", create.lookup.path);
        assertTrue(create.lookup.headers.containsKey("Authorization"), "every header, raw");
        assertTrue(create.payload.isTextual(), "the body as captured");
        assertEquals("http.responseBody.id", create.variables.get(0).path);
        assertEquals(201, create.expectedResult.get("status").asInt());

        Step.Substitution s = confirm.substitutions.get(0);
        assertEquals("http.path[4]", s.path, "the id travels in the URL, not the body");
        assertEquals("id", s.variable);

        String yaml = PlaceholderExporter.toYaml(recording, steps);
        assertTrue(yaml.contains("pathWithPlaceholders: \"/orders/api/orders/${id}/confirm\""), yaml);
    }
}
