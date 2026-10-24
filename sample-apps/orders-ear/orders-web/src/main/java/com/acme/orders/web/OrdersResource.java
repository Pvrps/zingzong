package com.acme.orders.web;

import com.acme.common.dto.ConfirmRequest;
import com.acme.common.dto.ConfirmResult;
import com.acme.common.dto.CreateOrderRequest;
import com.acme.common.model.Order;
import com.acme.orders.api.InsufficientStockException;
import com.acme.orders.api.OrderNotFoundException;
import com.acme.orders.api.OrderServiceLocal;

import javax.ejb.EJB;
import javax.json.Json;
import javax.servlet.http.HttpServletRequest;
import javax.ws.rs.Consumes;
import javax.ws.rs.DefaultValue;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import java.util.logging.Logger;

@Path("orders")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class OrdersResource {

    private static final Logger LOG = Logger.getLogger(OrdersResource.class.getName());

    @EJB
    private OrderServiceLocal orderService;

    @Context
    private HttpServletRequest request;

    /** Opens an HTTP session so the browser's calls are linked; the page calls this once on load. */
    @POST
    @Path("session")
    public Response session() {
        String id = request.getSession(true).getId();
        LOG.info("REST POST /orders/session -> " + id);
        return Response.ok(Json.createObjectBuilder().add("sessionId", id).build().toString()).build();
    }

    @POST
    public Response create(String body) {
        LOG.info("REST POST /orders");
        CreateOrderRequest request = OrderJson.toCreateOrderRequest(body);
        try {
            Order order = orderService.createOrder(request);
            return Response.status(Response.Status.CREATED).entity(OrderJson.fromOrder(order)).build();
        } catch (InsufficientStockException e) {
            // The business failure: an application exception (rollback) becomes 409 with the facts.
            return Response.status(Response.Status.CONFLICT)
                    .entity(OrderJson.error(e.getMessage() + " (sku=" + e.getSku() + ", requested=" + e.getRequested() + ", available=" + e.getAvailable() + ")"))
                    .build();
        }
        // A null customer is left alone on purpose: the NullPointerException in the bean becomes an
        // EJBException, Jersey's 500 and WebLogic's error page: the system-failure path.
    }

    @POST
    @Path("{id}/confirm")
    public Response confirm(@PathParam("id") long id, String body) {
        LOG.info("REST POST /orders/" + id + "/confirm");
        ConfirmRequest request = OrderJson.toConfirmRequest(id, body);
        try {
            ConfirmResult result = orderService.confirmOrder(request);
            return Response.ok(OrderJson.fromConfirmResult(result)).build();
        } catch (OrderNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(OrderJson.error(e.getMessage())).build();
        }
    }

    /** {@code ?delay=ms} holds the request open, for thread dumps of the servlet pipeline. */
    @GET
    @Path("{id}")
    public Response get(@PathParam("id") long id, @QueryParam("delay") @DefaultValue("0") long delayMillis) {
        LOG.info("REST GET /orders/" + id);
        if (delayMillis > 0) {
            try {
                Thread.sleep(Math.min(delayMillis, 30_000));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            return Response.ok(OrderJson.fromOrder(orderService.findOrder(id))).build();
        } catch (OrderNotFoundException e) {
            return Response.status(Response.Status.NOT_FOUND).entity(OrderJson.error(e.getMessage())).build();
        }
    }
}
