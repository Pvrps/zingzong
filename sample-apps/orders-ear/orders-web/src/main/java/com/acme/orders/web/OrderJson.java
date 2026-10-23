package com.acme.orders.web;

import com.acme.common.dto.Address;
import com.acme.common.dto.ConfirmRequest;
import com.acme.common.dto.ConfirmResult;
import com.acme.common.dto.CreateOrderRequest;
import com.acme.common.dto.Customer;
import com.acme.common.dto.LoyaltyTier;
import com.acme.common.dto.OrderLineRequest;
import com.acme.common.model.Order;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonArrayBuilder;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonReader;
import javax.json.JsonValue;
import java.io.StringReader;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Date;
import java.util.Map;

/** JSON-P mapping between the REST payloads and the EJB DTOs. Only JSON-P 1.0 APIs. */
final class OrderJson {

    private OrderJson() {
    }

    static CreateOrderRequest toCreateOrderRequest(String body) {
        JsonObject json = parse(body);
        CreateOrderRequest request = new CreateOrderRequest();
        request.setRequestedAt(Instant.now());
        request.setCustomer(toCustomer(json.getJsonObject("customer")));
        if (json.containsKey("deliveryDate") && !json.isNull("deliveryDate")) {
            request.setRequestedDeliveryDate(LocalDate.parse(json.getString("deliveryDate")));
        }
        JsonArray lines = json.getJsonArray("lines");
        if (lines != null) {
            for (JsonValue value : lines) {
                JsonObject line = (JsonObject) value;
                request.getLines().add(new OrderLineRequest(line.getString("sku"), line.getInt("quantity")));
            }
        }
        JsonObject attributes = json.containsKey("attributes") && !json.isNull("attributes")
                ? json.getJsonObject("attributes") : null;
        if (attributes != null) {
            for (String key : attributes.keySet()) {
                request.getAttributes().put(key, attributes.getString(key));
            }
        }
        return request;
    }

    static ConfirmRequest toConfirmRequest(long orderId, String body) {
        JsonObject json = body == null || body.trim().isEmpty() ? Json.createObjectBuilder().build() : parse(body);
        return new ConfirmRequest(
                orderId,
                json.getString("confirmedBy", "anonymous"),
                new Date(),
                json.getString("notes", null));
    }

    static String fromOrder(Order order) {
        JsonObjectBuilder builder = Json.createObjectBuilder()
                .add("id", order.getId())
                .add("status", order.getStatus().name())
                .add("customerName", order.getCustomerName())
                .add("currency", order.getCurrency())
                .add("subtotal", order.getSubtotal())
                .add("tax", order.getTax())
                .add("total", order.getTotal())
                .add("createdAt", order.getCreatedAt().toString());
        if (order.getDeliveryDate() != null) {
            builder.add("deliveryDate", order.getDeliveryDate().toString());
        }
        return builder.build().toString();
    }

    static String fromConfirmResult(ConfirmResult result) {
        JsonArrayBuilder messages = Json.createArrayBuilder();
        for (String message : result.getMessages()) {
            messages.add(message);
        }
        return Json.createObjectBuilder()
                .add("orderId", result.getOrderId())
                .add("status", result.getStatus().name())
                .add("confirmedAt", result.getConfirmedAt().toString())
                .add("messages", messages)
                .build()
                .toString();
    }

    static String error(String message) {
        return Json.createObjectBuilder().add("error", message).build().toString();
    }

    private static Customer toCustomer(JsonObject json) {
        if (json == null) {
            throw new IllegalArgumentException("customer is required");
        }
        JsonObject address = json.getJsonObject("address");
        return new Customer(
                json.getString("name"),
                json.getString("email", null),
                LoyaltyTier.valueOf(json.getString("loyaltyTier", LoyaltyTier.NONE.name())),
                address == null ? null : new Address(
                        address.getString("street", null),
                        address.getString("city", null),
                        address.getString("region", null),
                        address.getString("postalCode", null),
                        address.getString("country", null)));
    }

    private static JsonObject parse(String body) {
        try (JsonReader reader = Json.createReader(new StringReader(body))) {
            return reader.readObject();
        }
    }
}
